package com.vesper.flipper.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.vesper.flipper.MainActivity
import com.vesper.flipper.R
import com.vesper.flipper.VesperApplication
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Connection service for the Flipper Zero (BLE and USB serial).
 *
 * Lifecycle:
 *  - created by [bindService] or a warm start; both are silent and post no notification;
 *  - promoted to the foreground only when a scan or a connection actually starts;
 *  - keeps the foreground notification while scanning/connecting/connected/reconnecting;
 *  - drops the foreground and stops itself when disconnected and idle.
 *
 * All Bluetooth work runs on [serviceScope]; GATT callbacks are guarded so a
 * vendor-stack exception cannot crash the process. Reconnects use capped
 * exponential backoff and at most one series can be in flight.
 */
@AndroidEntryPoint
class FlipperBleService : Service() {

    private val binder = LocalBinder()

    /**
     * Scope for all connection work. A SupervisorJob keeps one failed session
     * from killing the others, and the handler keeps an unexpected throw on a
     * background job from crashing the process.
     */
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Unhandled service coroutine failure", throwable)
        }
    )

    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var usbManager: UsbManager? = null
    @Volatile
    private var usbSerialDriver: UsbSerialDriver? = null
    @Volatile
    private var usbSerialPort: UsbSerialPort? = null
    @Volatile
    private var usbDeviceConnection: UsbDeviceConnection? = null
    @Volatile
    private var connectedUsbDevice: UsbDevice? = null
    private var usbReadJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectAttemptTimeoutJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var broadScanFallbackJob: Job? = null
    private var idleStopJob: Job? = null
    private var usbReceiverRegistered = false
    private var bluetoothReceiverRegistered = false
    @Volatile
    private var broadScanStarted = false
    @Volatile
    private var scanActive = false
    @Volatile
    private var foregroundActive = false
    @Volatile
    private var serviceStartRequested = false
    @Volatile
    private var hasBoundClients = false
    @Volatile
    private var sessionRequested = false
    @Volatile
    private var connectedSinceMs: Long? = null
    @Volatile
    private var lastPacketAtMs: Long = 0L
    @Volatile
    private var lastHealthPublishAtMs: Long = 0L
    @Volatile
    private var totalReconnectCount: Int = 0
    @Volatile
    private var lastPublishedState: ConnectionState? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionHealth = MutableStateFlow(ConnectionHealth())
    val connectionHealth: StateFlow<ConnectionHealth> = _connectionHealth.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<FlipperDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<FlipperDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<FlipperDevice?>(null)
    val connectedDevice: StateFlow<FlipperDevice?> = _connectedDevice.asStateFlow()
    val cliCapabilityStatus: StateFlow<CliCapabilityStatus>
        get() = flipperProtocol.cliStatus

    private val pendingOperations = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private val rawOperationMutex = Mutex()
    @Volatile
    private var pendingOperationId: String? = null
    private val characteristicBuffer = ConcurrentHashMap<UUID, ByteArray>()
    private val discoveredDeviceNames = ConcurrentHashMap<String, String>()
    private val discoveredBluetoothDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val confirmedFlipperAddresses = ConcurrentHashMap.newKeySet<String>()
    private val writeMutex = Mutex()
    @Volatile
    private var pendingWriteAck: CompletableDeferred<Boolean>? = null
    @Volatile
    private var notificationsReady: Boolean = false
    @Volatile
    private var pendingConnectedDevice: FlipperDevice? = null
    @Volatile
    private var negotiatedMtu: Int = DEFAULT_ATT_MTU
    @Volatile
    private var currentUsbBaudRate: Int? = null
    @Volatile
    private var lastWriteFailureReason: String? = null
    @Volatile
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var serialRxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var serialServiceUuid: UUID? = null
    @Volatile
    private var serialOverflowCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var serialResetCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var remainingSerialBufferBytes: Int? = null
    @Volatile
    private var pendingConnectionName: String? = null
    @Volatile
    private var lastRequestedDeviceAddress: String? = null
    @Volatile
    private var lastRequestedDeviceName: String? = null
    @Volatile
    private var lastRequestedBluetoothDevice: BluetoothDevice? = null
    @Volatile
    private var reconnectAttemptCount: Int = 0
    @Volatile
    private var gattLinkConnected: Boolean = false
    @Volatile
    private var activeTransport: CommandTransport = CommandTransport.NONE
    @Volatile
    private var pendingUsbPermissionRequest: CompletableDeferred<Boolean>? = null
    @Volatile
    private var lastBleActivityAtMs: Long = 0L
    @Volatile
    private var lastBlePriorityRequestAtMs: Long = 0L

    private val usbBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    pendingUsbPermissionRequest?.let { deferred ->
                        if (!deferred.isCompleted) {
                            deferred.complete(granted)
                        }
                    }
                    pendingUsbPermissionRequest = null
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val attachedDevice = intent.extractUsbDeviceExtra() ?: return
                    serviceScope.launch {
                        if (isCommandTransportConnected()) return@launch
                        if (!isLikelyFlipperUsbDevice(attachedDevice)) return@launch
                        ensureForeground()
                        sessionRequested = true
                        val connected = connectUsbInternal(preferredDevice = attachedDevice)
                        if (!connected) {
                            sessionRequested = false
                            scheduleIdleShutdown()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = intent.extractUsbDeviceExtra() ?: return
                    serviceScope.launch {
                        if (detachedDevice.deviceId != connectedUsbDevice?.deviceId) return@launch
                        disconnectUsbTransport(
                            setState = true,
                            errorMessage = "USB cable disconnected. Plug it back in or connect over Bluetooth.",
                            failure = ConnectionFailure.TRANSPORT_UNAVAILABLE
                        )
                        sessionRequested = false
                        scheduleIdleShutdown()
                    }
                }
            }
        }
    }

    /**
     * Keeps the BLE session honest across radio state changes. Turning Bluetooth
     * off always tears the link down and cancels pending reconnects; turning it
     * back on resumes a single reconnect for a session the user still wants.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val reportedState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            serviceScope.launch {
                // The receiver is exported (required to receive this broadcast on
                // Android 14+), so re-read the real adapter state instead of
                // trusting the intent extras.
                val actualState = runCatching { bluetoothAdapter?.state }.getOrNull()
                handleBluetoothStateChanged(actualState ?: reportedState)
            }
        }
    }

    @Inject
    lateinit var flipperProtocol: FlipperProtocol

    inner class LocalBinder : Binder() {
        fun getService(): FlipperBleService = this@FlipperBleService
    }

    override fun onBind(intent: Intent?): IBinder {
        hasBoundClients = true
        idleStopJob?.cancel()
        idleStopJob = null
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClients = false
        scheduleIdleShutdown()
        return false
    }

    override fun onCreate() {
        super.onCreate()
        initializeBluetooth()
        initializeUsb()
        registerUsbReceivers()
        registerBluetoothReceivers()
        flipperProtocol.setBleService(this)
        serviceScope.launch {
            runCatching { loadPersistedConfirmedFlippers() }
                .onFailure { Log.w(TAG, "Could not restore confirmed Flippers: ${it.message}") }
        }
        // Publish notification + health for every distinct state, whichever thread
        // produced it. StateFlow values are conflated, which is what we want here.
        serviceScope.launch {
            _connectionState.collect { state ->
                runCatching { onConnectionStatePublished(state) }
                    .onFailure { Log.w(TAG, "Could not publish connection state: ${it.message}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> startScan()
            ACTION_CONNECT -> {
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent?.getStringExtra(EXTRA_DEVICE_NAME)
                if (address.isNullOrBlank()) {
                    setConnectionState(
                        ConnectionState.Error(
                            message = "No device address was supplied. Scan again and pick the Flipper.",
                            reason = ConnectionFailure.DEVICE_NOT_FOUND
                        )
                    )
                    scheduleIdleShutdown()
                } else {
                    connect(
                        FlipperDevice(
                            address = address,
                            name = name?.takeIf { it.isNotBlank() } ?: DEFAULT_FLIPPER_NAME,
                            rssi = 0
                        )
                    )
                }
            }
            ACTION_CONNECT_USB -> connectUsbIfAvailable()
            ACTION_STOP_SCAN -> stopScan()
            ACTION_STOP -> {
                // Teardown performs Binder/USB calls; never block the main thread
                // that dispatches onStartCommand.
                serviceScope.launch {
                    stopSession()
                    serviceStartRequested = false
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START_FOREGROUND, ACTION_SESSION_ACTIVE -> {
                // Legacy action kept for API compatibility: it only promotes the
                // service and keeps it alive while a session is busy.
                ensureForeground()
                scheduleIdleShutdown()
            }
            ACTION_WARM_START -> scheduleIdleShutdown()
            else -> {
                // Warm start (and null intents): stay available without posting a
                // notification. If no session starts, the service stops itself.
                scheduleIdleShutdown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionRequested = false
        // Detach from system events first so no late broadcast can start work on
        // a service that is being destroyed.
        unregisterUsbReceivers()
        unregisterBluetoothReceivers()
        stopScanInternal(updateState = false)
        cancelPendingReconnect()
        cancelConnectAttemptTimeout()
        stopConnectionWatchdog()
        idleStopJob?.cancel()
        idleStopJob = null
        disconnectInternal()
        dropForeground()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initializeBluetooth() {
        runCatching {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            bleScanner = currentBleScanner(bluetoothAdapter)
        }.onFailure { Log.w(TAG, "Bluetooth initialization failed: ${it.message}") }
    }

    private fun refreshBluetoothScanner() {
        runCatching {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.let { adapter ->
                bluetoothAdapter = adapter
                bleScanner = currentBleScanner(adapter)
            }
        }.onFailure { Log.w(TAG, "Could not refresh BLE scanner: ${it.message}") }
    }

    private fun currentBleScanner(adapter: BluetoothAdapter?): BluetoothLeScanner? {
        if (adapter == null) return null
        if (!hasBluetoothPermissions()) return null
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return null
        return runCatching { adapter.bluetoothLeScanner }.getOrNull()
    }

    /**
     * Picks a scan mode that matches the app visibility: low latency while the
     * user is watching the scan, low power otherwise so a forgotten scan does
     * not keep the radio at full duty cycle.
     */
    private fun resolveScanMode(): Int {
        return if (isAppInForeground()) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        return runCatching {
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)
    }

    private fun initializeUsb() {
        usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private fun registerUsbReceivers() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        usbReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                usbBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.isSuccess
    }

    private fun unregisterUsbReceivers() {
        if (!usbReceiverRegistered) return
        runCatching { unregisterReceiver(usbBroadcastReceiver) }
        usbReceiverRegistered = false
    }

    private fun registerBluetoothReceivers() {
        if (bluetoothReceiverRegistered) return
        val registered = runCatching {
            // Bluetooth state changes are sent by the Bluetooth stack process, not
            // the system server. Android 14+ does not deliver them to
            // NOT_EXPORTED receivers, so this one must be exported.
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.isSuccess
        bluetoothReceiverRegistered = registered
    }

    private fun unregisterBluetoothReceivers() {
        if (!bluetoothReceiverRegistered) return
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        bluetoothReceiverRegistered = false
    }

    /**
     * Promotes the service to the foreground for an active scan/connection.
     *
     * The service is normally created by a bind, so it is explicitly marked as
     * started first; that keeps the foreground promotion valid when the last
     * client unbinds. Foreground type is passed explicitly on API 29+ because
     * Android 14 requires it to match the manifest declaration.
     */
    private fun ensureForeground() {
        idleStopJob?.cancel()
        idleStopJob = null
        if (foregroundActive) {
            updateNotification()
            return
        }
        foregroundActive = true
        if (!serviceStartRequested) {
            // Marking the service as started is idempotent and must not be
            // repeated on every promotion: each re-entry would retry a failing
            // startForeground() and could spin.
            serviceStartRequested = runCatching {
                super.startService(
                    Intent(this, FlipperBleService::class.java).setAction(ACTION_SESSION_ACTIVE)
                )
                true
            }.onFailure {
                Log.w(TAG, "Could not mark service as started: ${it.message}")
            }.getOrDefault(false)
        }

        val notification = buildNotification()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (started.isFailure) {
            foregroundActive = false
            Log.w(TAG, "startForeground failed: ${started.exceptionOrNull()?.message}")
        }
    }

    private fun dropForeground() {
        if (!foregroundActive) return
        foregroundActive = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_CONTENT_INTENT_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val statusText = getConnectionText()
        return NotificationCompat.Builder(this, VesperApplication.CHANNEL_BLE)
            .setContentTitle(getString(R.string.notification_channel_ble))
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(R.drawable.ic_flipper_notification)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun getConnectionText(): String {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connected -> {
                val reconnectCount = _connectionHealth.value.reconnectCount
                val base = getString(R.string.ble_connected, state.deviceName)
                if (reconnectCount > 0) "$base • $reconnectCount reconnects" else base
            }
            is ConnectionState.Connecting -> getString(R.string.ble_connecting, state.deviceName)
            is ConnectionState.Disconnected -> getString(R.string.ble_disconnected)
            is ConnectionState.Scanning -> getString(R.string.ble_scanning)
            is ConnectionState.Error -> getString(R.string.ble_error, state.message.take(180))
        }
    }

    private fun updateNotification() {
        if (!foregroundActive) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        onConnectionStatePublished(state)
    }

    private fun onConnectionStatePublished(state: ConnectionState) {
        // setConnectionState() calls this directly and the StateFlow collector
        // calls it again; publish each distinct state only once.
        if (lastPublishedState === state) return
        lastPublishedState = state
        if (state is ConnectionState.Connected) {
            if (connectedSinceMs == null) {
                connectedSinceMs = System.currentTimeMillis()
            }
        } else if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            connectedSinceMs = null
        }
        updateConnectionHealth(force = true)
        updateNotification()
    }

    private fun updateConnectionHealth(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHealthPublishAtMs < HEALTH_PUBLISH_MIN_INTERVAL_MS) return
        lastHealthPublishAtMs = now
        val connectedAt = connectedSinceMs
        val lastActivity = lastBleActivityAtMs
        _connectionHealth.value = ConnectionHealth(
            lastPacketAtMs = lastPacketAtMs,
            lastActivityAtMs = lastActivity,
            connectedSinceMs = connectedAt,
            reconnectCount = totalReconnectCount,
            silentForMs = if (connectedAt != null && lastActivity > 0L) {
                (now - lastActivity).coerceAtLeast(0L)
            } else {
                0L
            }
        )
    }

    private fun isSessionBusy(): Boolean {
        if (scanActive) return true
        if (reconnectJob?.isActive == true) return true
        if (activeTransport != CommandTransport.NONE) return true
        return when (_connectionState.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Scanning -> true
            else -> false
        }
    }

    /**
     * Drops the foreground notification and stops the service once a session is
     * over. A grace period avoids notification flicker between back-to-back
     * transports (for example, scan -> connect).
     */
    private fun scheduleIdleShutdown() {
        idleStopJob?.cancel()
        if (isSessionBusy()) return
        idleStopJob = serviceScope.launch {
            delay(IDLE_STOP_GRACE_MS)
            if (isSessionBusy()) return@launch
            stopConnectionWatchdog()
            dropForeground()
            if (!hasBoundClients) {
                serviceStartRequested = false
                stopSelf()
            }
        }
    }

    private fun stopSession() {
        sessionRequested = false
        cancelPendingReconnect()
        cancelConnectAttemptTimeout()
        stopScanInternal(updateState = false)
        disconnectInternal()
        scheduleIdleShutdown()
    }

    private suspend fun handleBluetoothStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                Log.i(TAG, "Bluetooth turned off; tearing down BLE session")
                stopScanInternal(updateState = false)
                cancelPendingReconnect()
                cancelConnectAttemptTimeout()
                stopConnectionWatchdog()
                teardownBleTransport(clearRequestedDevice = false)
                _connectedDevice.value = null
                connectedSinceMs = null
                if (sessionRequested || _connectionState.value is ConnectionState.Connected ||
                    _connectionState.value is ConnectionState.Connecting
                ) {
                    setConnectionState(
                        ConnectionState.Error(
                            message = "Bluetooth was turned off. Turn Bluetooth on, unlock the Flipper, then reconnect.",
                            reason = ConnectionFailure.BLUETOOTH_DISABLED
                        )
                    )
                } else {
                    setConnectionState(ConnectionState.Disconnected)
                }
                scheduleIdleShutdown()
            }
            BluetoothAdapter.STATE_ON -> {
                if (!hasBluetoothPermissions()) {
                    sessionRequested = false
                    return
                }
                // The user still wants the session: make one reconnect attempt
                // series with a fresh backoff budget.
                if (sessionRequested &&
                    lastRequestedDeviceAddress != null &&
                    _connectionState.value !is ConnectionState.Connected
                ) {
                    reconnectAttemptCount = 0
                    if (!scheduleReconnect(reason = "Bluetooth re-enabled")) {
                        sessionRequested = false
                        scheduleIdleShutdown()
                    }
                }
            }
            BluetoothAdapter.STATE_TURNING_ON,
            BluetoothAdapter.STATE_TURNING_OFF -> Unit
        }
    }

    // Scanning - Now finds ALL potential Flippers, even renamed ones

    /**
     * Starts a scan session. Promotes the service to the foreground and runs all
     * Bluetooth work off the caller thread.
     */
    fun startScan() {
        serviceScope.launch { startScanInternal() }
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        // Satisfy the startForegroundService() contract before any early return:
        // Android kills the app if startForeground() is not called within 5s.
        ensureForeground()
        val connectingOrConnected = _connectionState.value is ConnectionState.Connected ||
                _connectionState.value is ConnectionState.Connecting ||
                activeTransport != CommandTransport.NONE ||
                reconnectJob?.isActive == true
        if (connectingOrConnected) {
            Log.d(TAG, "Scan request ignored: a connection session is already active")
            scheduleIdleShutdown()
            return
        }
        sessionRequested = true
        if (!hasBluetoothPermissions()) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Nearby devices permission is required to scan. Grant it in Settings, then scan again.",
                    reason = ConnectionFailure.PERMISSION_DENIED
                )
            )
            sessionRequested = false
            scheduleIdleShutdown()
            return
        }

        refreshBluetoothScanner()
        if (bluetoothAdapter?.isEnabled != true) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Bluetooth is unavailable or off. Turn Bluetooth on, then scan again.",
                    reason = ConnectionFailure.BLUETOOTH_DISABLED
                )
            )
            sessionRequested = false
            scheduleIdleShutdown()
            return
        }

        val scanner = bleScanner
        if (scanner == null) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Bluetooth LE scanning is unavailable right now. Toggle Bluetooth off and on, then retry.",
                    reason = ConnectionFailure.SCAN_FAILED
                )
            )
            sessionRequested = false
            scheduleIdleShutdown()
            return
        }

        if (scanActive) {
            stopScanInternal(updateState = false)
        }
        setConnectionState(ConnectionState.Scanning)
        _discoveredDevices.value = emptyList()
        seedBondedFlippersIntoDiscoveredList()
        broadScanStarted = false

        // Filter by known Flipper service UUIDs so renamed devices are still discovered.
        val flipperFilters = FLIPPER_SCAN_SERVICE_UUIDS.map { serviceUuid ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(resolveScanMode())
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(flipperFilters, scanSettings, scanCallback)
            scanActive = true
        } catch (security: SecurityException) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Nearby devices permission was revoked while starting the scan. Grant it and retry.",
                    reason = ConnectionFailure.PERMISSION_DENIED
                )
            )
            sessionRequested = false
            scheduleIdleShutdown()
            return
        } catch (e: IllegalStateException) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Bluetooth could not start scanning (${e.message ?: "adapter busy"}). Toggle Bluetooth off/on and retry.",
                    reason = ConnectionFailure.SCAN_FAILED
                )
            )
            sessionRequested = false
            scheduleIdleShutdown()
            return
        }

        // Fallback broad scan only if filtered scan found nothing after a short window.
        broadScanFallbackJob?.cancel()
        broadScanFallbackJob = serviceScope.launch {
            delay(BROAD_SCAN_FALLBACK_DELAY_MS)
            if (scanActive && _discoveredDevices.value.isEmpty() && !broadScanStarted) {
                startBroadScan()
            }
        }

        // Auto-stop the scan after the timeout so a forgotten scan cannot drain the radio.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (scanActive) {
                scanTimeoutJob = null
                stopScanInternal(updateState = true)
                sessionRequested = false
                scheduleIdleShutdown()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBroadScan() {
        if (!hasBluetoothPermissions()) return
        if (broadScanStarted) return

        // Broad scan without filters - catches renamed Flippers
        val broadSettings = ScanSettings.Builder()
            .setScanMode(resolveScanMode())
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bleScanner?.startScan(null, broadSettings, broadScanCallback)
            broadScanStarted = true
        } catch (_: SecurityException) {
            // Permission revoked; the filtered scan is torn down by the same event.
        } catch (_: IllegalStateException) {
            // Keep silent; filtered scan remains primary path.
        }
    }

    /**
     * Stops scanning, cancels the scan timeout/fallback jobs, and ends the scan
     * session when no connection or reconnect still needs the service.
     */
    fun stopScan() {
        serviceScope.launch {
            stopScanInternal(updateState = true)
            if (!isSessionBusy()) {
                sessionRequested = false
                scheduleIdleShutdown()
            }
        }
    }

    private fun stopScanInternal(updateState: Boolean) {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        broadScanFallbackJob?.cancel()
        broadScanFallbackJob = null
        if (scanActive || broadScanStarted) {
            // stopScan() throws if the permission was revoked; attempt it anyway
            // so the scanner registration cannot leak.
            runCatching { bleScanner?.stopScan(scanCallback) }
            runCatching { bleScanner?.stopScan(broadScanCallback) }
        }
        scanActive = false
        broadScanStarted = false
        if (updateState && _connectionState.value is ConnectionState.Scanning) {
            setConnectionState(ConnectionState.Disconnected)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                addDiscoveredDevice(result, isConfirmedFlipper = true)
            } catch (t: Throwable) {
                // Scan callbacks run on a Binder thread; never let one crash the app.
                Log.w(TAG, "Filtered scan result ignored: ${t.message}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            try {
                results.forEach { result ->
                    addDiscoveredDevice(result, isConfirmedFlipper = true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Filtered scan batch ignored: ${t.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            try {
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    stopScanInternal(updateState = false)
                    sessionRequested = false
                    setConnectionState(
                        ConnectionState.Error(
                            message = "Android rejected the BLE scan registration. Toggle Bluetooth off and on, then scan again.",
                            reason = ConnectionFailure.SCAN_FAILED
                        )
                    )
                    scheduleIdleShutdown()
                    return
                }
                // Fallback to broad scan when hardware rejects filtered mode.
                if (!broadScanStarted) {
                    startBroadScan()
                }
                if (!broadScanStarted) {
                    stopScanInternal(updateState = false)
                    sessionRequested = false
                    setConnectionState(
                        ConnectionState.Error(
                            message = "BLE scan failed (code $errorCode). Move closer to the Flipper and try again.",
                            reason = ConnectionFailure.SCAN_FAILED
                        )
                    )
                    scheduleIdleShutdown()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Scan failure handler failed: ${t.message}")
            }
        }
    }

    // Broad scan callback - validates devices by checking for Flipper characteristics
    private val broadScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                if (!isLikelyFlipperScanResult(result)) return
                addDiscoveredDevice(
                    result = result,
                    isConfirmedFlipper = isConfirmedFlipperScanResult(result)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Broad scan result ignored: ${t.message}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            try {
                results.forEach { result ->
                    if (isLikelyFlipperScanResult(result)) {
                        addDiscoveredDevice(
                            result = result,
                            isConfirmedFlipper = isConfirmedFlipperScanResult(result)
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Broad scan batch ignored: ${t.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            broadScanStarted = false
            Log.w(TAG, "Broad scan failed with code $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyFlipperScanResult(result: ScanResult): Boolean {
        return hasKnownFlipperService(result) ||
                normalizeBleAddress(result.device.address)
                    ?.let { confirmedFlipperAddresses.contains(it) } == true ||
                hasKnownFlipperAddressPrefix(result.device.address) ||
                isLikelyFlipperName(resolveScanDeviceName(result)) ||
                hasLikelyFlipperManufacturerData(result)
    }

    @SuppressLint("MissingPermission")
    private fun isConfirmedFlipperScanResult(result: ScanResult): Boolean {
        return hasKnownFlipperService(result) ||
                normalizeBleAddress(result.device.address)
                    ?.let { confirmedFlipperAddresses.contains(it) } == true ||
                hasKnownFlipperAddressPrefix(result.device.address) ||
                hasLikelyFlipperManufacturerData(result)
    }

    @SuppressLint("MissingPermission")
    private fun addDiscoveredDevice(result: ScanResult, isConfirmedFlipper: Boolean) {
        val device = result.device
        val normalizedAddress = normalizeBleAddress(device.address)
        discoveredBluetoothDevices[device.address] = device
        val name = resolveScanDeviceName(result)
        if (!isPlaceholderName(name)) {
            discoveredDeviceNames[device.address] = name
        }
        if (isConfirmedFlipper && normalizedAddress != null) {
            if (confirmedFlipperAddresses.add(normalizedAddress)) {
                persistConfirmedFlipperAddress(normalizedAddress)
            }
        }

        val flipperDevice = FlipperDevice(
            address = device.address,
            name = name,
            rssi = result.rssi,
            isConfirmedFlipper = isConfirmedFlipper
        )

        _discoveredDevices.update { currentDevices ->
            val updated = currentDevices.toMutableList()
            val existingIndex = updated.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                // Update existing device, prefer confirmed status
                val existing = updated[existingIndex]
                val resolvedName = if (isPlaceholderName(name) && !isPlaceholderName(existing.name)) {
                    existing.name
                } else {
                    name
                }
                updated[existingIndex] = flipperDevice.copy(
                    name = resolvedName,
                    isConfirmedFlipper = existing.isConfirmedFlipper || isConfirmedFlipper
                )
            } else {
                updated.add(flipperDevice)
            }
            // Sort: confirmed Flippers first, then by signal strength
            updated.sortedWith(
                compareByDescending<FlipperDevice> { it.isConfirmedFlipper }
                    .thenByDescending { it.rssi }
            ).take(MAX_DISCOVERED_DEVICES)
        }
    }

    // Connection

    /**
     * Connects to a discovered Flipper. The actual GATT work runs on the service
     * scope so callers, including UI callbacks, never block on Bluetooth.
     */
    fun connect(device: FlipperDevice) {
        // Mark the session immediately so a quick Disconnect wins the race with
        // the coroutine below.
        sessionRequested = true
        serviceScope.launch {
            ensureForeground()
            if (!sessionRequested) {
                scheduleIdleShutdown()
                return@launch
            }
            connectInternal(device)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(device: FlipperDevice) {
        if (!sessionRequested) return
        if (!hasBluetoothPermissions()) {
            sessionRequested = false
            setConnectionState(
                ConnectionState.Error(
                    message = "Nearby devices permission is required to connect. Grant it, then retry.",
                    reason = ConnectionFailure.PERMISSION_DENIED
                )
            )
            scheduleIdleShutdown()
            return
        }

        refreshBluetoothScanner()
        if (bluetoothAdapter?.isEnabled != true) {
            sessionRequested = false
            setConnectionState(
                ConnectionState.Error(
                    message = "Bluetooth is unavailable or off. Turn Bluetooth on, unlock the Flipper, then connect.",
                    reason = ConnectionFailure.BLUETOOTH_DISABLED
                )
            )
            scheduleIdleShutdown()
            return
        }

        stopScanInternal(updateState = false)
        cancelPendingReconnect()
        cancelConnectAttemptTimeout()
        teardownAllTransports(updateState = false, clearRequestedDevice = true)
        flipperProtocol.onConnectionReset()

        val resolvedDevice = discoveredBluetoothDevices[device.address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(device.address) }.getOrNull()
        val targetAddress = resolvedDevice?.address ?: device.address
        lastRequestedDeviceAddress = targetAddress
        lastRequestedDeviceName = device.name
        lastRequestedBluetoothDevice = resolvedDevice
        reconnectAttemptCount = 0
        pendingConnectionName = device.name.takeIf { it.isNotBlank() && !isPlaceholderName(it) }

        setConnectionState(ConnectionState.Connecting(device.name))

        Log.d(
            TAG,
            "connect requested name=${device.name} target=$targetAddress resolved=${resolvedDevice != null}"
        )
        val gatt = resolvedDevice?.let { connectGattCompat(it, autoConnect = false) }
        if (!sessionRequested ||
            !currentCoroutineContext().isActive ||
            activeTransport != CommandTransport.NONE
        ) {
            // Disconnected or another transport won while connectGatt() was in
            // flight: do not install an orphaned connection.
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gattLinkConnected = false
            connectedSinceMs = null
            return
        }
        bluetoothGatt = gatt
        if (gatt == null) {
            pendingConnectionName = null
            sessionRequested = false
            setConnectionState(
                ConnectionState.Error(
                    message = "Could not start a BLE connection to ${device.name}. " +
                            "Keep the Flipper unlocked with its Bluetooth app open, then retry.",
                    reason = ConnectionFailure.DEVICE_NOT_FOUND
                )
            )
            scheduleIdleShutdown()
        } else {
            startConnectAttemptTimeout()
        }
    }

    /**
     * Connects over USB when a compatible serial Flipper is attached. Cancels any
     * pending BLE reconnect first so the two transports cannot fight.
     */
    fun connectUsbIfAvailable() {
        sessionRequested = true
        serviceScope.launch {
            ensureForeground()
            if (!sessionRequested) {
                scheduleIdleShutdown()
                return@launch
            }
            val connected = connectUsbInternal()
            if (!connected) {
                sessionRequested = false
                scheduleIdleShutdown()
            }
        }
    }

    private suspend fun connectUsbInternal(preferredDevice: UsbDevice? = null): Boolean {
        if (!sessionRequested) return false
        // A USB connect always wins: stop scanning and tear the BLE side down
        // first so two transports cannot own the protocol at the same time.
        stopScanInternal(updateState = false)
        cancelPendingReconnect()
        cancelConnectAttemptTimeout()
        teardownAllTransports(updateState = false, clearRequestedDevice = true)

        val manager = usbManager ?: run {
            setConnectionState(
                ConnectionState.Error(
                    message = "USB host support is unavailable on this device. Use Bluetooth instead.",
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            return false
        }
        setConnectionState(ConnectionState.Connecting("USB"))

        val attachedDevices = manager.deviceList.values.toList()
        if (attachedDevices.isEmpty()) {
            setConnectionState(
                ConnectionState.Error(
                    message = "No USB device detected. Use an OTG adapter, unlock the Flipper, and reconnect the cable.",
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            return false
        }

        val driver = resolveUsbSerialDriver(manager, preferredDevice)
        if (driver == null) {
            setConnectionState(
                ConnectionState.Error(
                    message = "No compatible Flipper USB serial endpoint found. Attached: " +
                            describeUsbDeviceList(attachedDevices),
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            return false
        }
        val device = driver.device

        if (!ensureUsbPermission(manager, device)) {
            setConnectionState(
                ConnectionState.Error(
                    message = "USB permission was not granted for ${resolveUsbDeviceName(device)}. " +
                            "Accept the system prompt and retry.",
                    reason = ConnectionFailure.USB_PERMISSION_DENIED
                )
            )
            return false
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            setConnectionState(
                ConnectionState.Error(
                    message = "The Flipper USB device could not be opened. Reconnect the cable and retry.",
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            return false
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            runCatching { connection.close() }
            setConnectionState(
                ConnectionState.Error(
                    message = "The Flipper USB device exposes no serial port. Use Bluetooth instead.",
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            return false
        }

        if (!sessionRequested) {
            runCatching { connection.close() }
            return false
        }

        return try {
            port.open(connection)
            currentUsbBaudRate = configureUsbPort(port)
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
            primeUsbRpcSession(port)

            // Close any late BLE connection that completed while the USB port was
            // opening; exactly one transport may own the protocol.
            teardownBleTransport(clearRequestedDevice = true)
            usbSerialDriver = driver
            usbSerialPort = port
            usbDeviceConnection = connection
            connectedUsbDevice = device
            activeTransport = CommandTransport.USB
            flipperProtocol.onConnectionReset()
            startUsbReadLoop()

            val deviceName = resolveUsbDeviceName(device)
            _connectedDevice.value = FlipperDevice(
                address = "usb:${device.deviceId}",
                name = "$deviceName (USB)",
                rssi = 0,
                isConfirmedFlipper = true
            )
            setConnectionState(ConnectionState.Connected("$deviceName (USB)"))
            connectedSinceMs = System.currentTimeMillis()
            updateConnectionHealth(force = true)

            serviceScope.launch {
                runCatching {
                    validateUsbBaudWithRpcProbe(port)
                    flipperProtocol.probeCliAvailability(force = true)
                }.onFailure { Log.w(TAG, "USB post-connect probe failed: ${it.message}") }
            }
            true
        } catch (t: Throwable) {
            runCatching { port.close() }
            runCatching { connection.close() }
            usbSerialDriver = null
            usbSerialPort = null
            usbDeviceConnection = null
            connectedUsbDevice = null
            currentUsbBaudRate = null
            activeTransport = CommandTransport.NONE
            setConnectionState(
                ConnectionState.Error(
                    message = "USB connect failed: ${t.message ?: t::class.java.simpleName}. " +
                            "Reconnect the cable or use Bluetooth.",
                    reason = ConnectionFailure.USB_UNAVAILABLE
                )
            )
            false
        }
    }

    private suspend fun primeUsbRpcSession(port: UsbSerialPort) {
        drainUsbInput(port, USB_RPC_PRIME_DRAIN_WINDOW_MS)
        USB_RPC_PRIME_COMMANDS.forEach { command ->
            runCatching {
                port.write(command.toByteArray(Charsets.UTF_8), USB_WRITE_TIMEOUT_MS.toInt())
            }
            delay(USB_RPC_PRIME_COMMAND_DELAY_MS)
        }
    }

    private suspend fun validateUsbBaudWithRpcProbe(port: UsbSerialPort) {
        val currentBaud = currentUsbBaudRate
        val initialProbe = flipperProtocol.probeRpcAvailability(
            detail = if (currentBaud != null) {
                "RPC ping responded after USB connect bootstrap (baud=$currentBaud)"
            } else {
                "RPC ping responded after USB connect bootstrap"
            }
        )
        if (initialProbe.supportsRpc) {
            return
        }

        val fallbackBauds = USB_BAUD_RATE_CANDIDATES.filter { it != currentBaud }
        for (baud in fallbackBauds) {
            if (activeTransport != CommandTransport.USB || usbSerialPort !== port) {
                return
            }

            val reconfigured = runCatching {
                port.setParameters(
                    baud,
                    USB_DATA_BITS,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            }.isSuccess
            if (!reconfigured) continue

            currentUsbBaudRate = baud
            drainUsbInput(port, USB_RPC_BAUD_RETRY_DRAIN_WINDOW_MS)
            primeUsbRpcSession(port)
            flipperProtocol.onConnectionReset()

            val probe = flipperProtocol.probeRpcAvailability(
                detail = "RPC ping responded after USB baud fallback (baud=$baud)"
            )
            if (probe.supportsRpc) {
                Log.i(TAG, "USB RPC probe recovered after baud fallback to $baud")
                return
            }
        }
    }

    private fun drainUsbInput(port: UsbSerialPort, durationMs: Long) {
        val deadlineMs = System.currentTimeMillis() + durationMs
        val drainBuffer = ByteArray(USB_READ_BUFFER_SIZE_BYTES)
        while (System.currentTimeMillis() < deadlineMs) {
            val read = runCatching {
                port.read(drainBuffer, USB_READ_TIMEOUT_MS.toInt())
            }.getOrDefault(0)
            if (read <= 0) {
                break
            }
        }
    }

    private fun resolveUsbSerialDriver(
        manager: UsbManager,
        preferredDevice: UsbDevice? = null
    ): UsbSerialDriver? {
        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val customDrivers = buildCustomUsbProber().findAllDrivers(manager)
        val drivers = (defaultDrivers + customDrivers)
            .associateBy { it.device.deviceId }
            .values
            .toList()
        Log.d(
            TAG,
            "USB probe drivers=${drivers.size} attached=${describeUsbDeviceList(manager.deviceList.values.toList())}"
        )
        if (drivers.isEmpty()) return null

        if (preferredDevice != null) {
            drivers.firstOrNull { it.device.deviceId == preferredDevice.deviceId }?.let { candidate ->
                Log.d(TAG, "USB selected preferred deviceId=${candidate.device.deviceId}")
                return candidate
            }
        }

        drivers.firstOrNull { isLikelyFlipperUsbDevice(it.device) }?.let { candidate ->
            Log.d(TAG, "USB selected likely Flipper deviceId=${candidate.device.deviceId}")
            return candidate
        }
        return if (drivers.size == 1) {
            Log.d(TAG, "USB selected single available serial deviceId=${drivers.first().device.deviceId}")
            drivers.first()
        } else {
            null
        }
    }

    private suspend fun ensureUsbPermission(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            usbPermissionPendingIntentFlags()
        )
        val deferred = CompletableDeferred<Boolean>()
        // Superseded requests complete as denied instead of throwing into the
        // connect coroutine.
        pendingUsbPermissionRequest?.let { previous ->
            if (!previous.isCompleted) previous.complete(false)
        }
        pendingUsbPermissionRequest = deferred
        val requested = runCatching {
            manager.requestPermission(device, pendingIntent)
        }.isSuccess
        if (!requested) {
            if (pendingUsbPermissionRequest === deferred) {
                pendingUsbPermissionRequest = null
            }
            return false
        }
        val granted = withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) {
            deferred.await()
        } ?: false
        if (pendingUsbPermissionRequest === deferred) {
            pendingUsbPermissionRequest = null
        }
        return granted
    }

    private fun usbPermissionPendingIntentFlags(): Int {
        // The USB system service adds EXTRA_PERMISSION_GRANTED to this intent, so
        // the PendingIntent must stay mutable; the notification intent does not.
        val updateFlag = PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateFlag or PendingIntent.FLAG_MUTABLE
        } else {
            updateFlag
        }
    }

    private fun buildCustomUsbProber(): UsbSerialProber {
        val table = ProbeTable()
        KNOWN_FLIPPER_USB_VENDOR_IDS.forEach { vendorId ->
            KNOWN_FLIPPER_USB_PRODUCT_IDS.forEach { productId ->
                table.addProduct(vendorId, productId, CdcAcmSerialDriver::class.java)
            }
        }
        return UsbSerialProber(table)
    }

    private fun configureUsbPort(port: UsbSerialPort): Int? {
        USB_BAUD_RATE_CANDIDATES.forEach { baud ->
            val configured = runCatching {
                port.setParameters(
                    baud,
                    USB_DATA_BITS,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            }.isSuccess
            if (configured) {
                return baud
            }
        }
        Log.w(TAG, "USB serial port rejected all baud candidates; continuing with device defaults")
        return null
    }

    private fun resolveWriteLockTimeoutMs(
        payloadSize: Int,
        preferNoResponse: Boolean
    ): Long {
        val isControlPacket = preferNoResponse && payloadSize <= CONTROL_PACKET_MAX_BYTES
        return if (isControlPacket) {
            WRITE_MUTEX_CONTROL_WAIT_TIMEOUT_MS
        } else {
            WRITE_MUTEX_STANDARD_WAIT_TIMEOUT_MS
        }
    }

    private fun resolveBleAckTimeoutMs(
        payloadSize: Int,
        preferNoResponse: Boolean
    ): Long {
        val isControlPacket = preferNoResponse && payloadSize <= CONTROL_PACKET_MAX_BYTES
        return if (isControlPacket) WRITE_ACK_TIMEOUT_CONTROL_MS else WRITE_ACK_TIMEOUT_MS
    }

    private fun resolveBleWriteAttempts(
        payloadSize: Int,
        preferNoResponse: Boolean
    ): Int {
        val isControlPacket = preferNoResponse && payloadSize <= CONTROL_PACKET_MAX_BYTES
        return if (isControlPacket) MAX_WRITE_START_ATTEMPTS_CONTROL else MAX_WRITE_START_ATTEMPTS
    }

    private fun resolveBleWriteRetryDelayMs(
        payloadSize: Int,
        preferNoResponse: Boolean
    ): Long {
        val isControlPacket = preferNoResponse && payloadSize <= CONTROL_PACKET_MAX_BYTES
        return if (isControlPacket) WRITE_START_RETRY_DELAY_CONTROL_MS else WRITE_START_RETRY_DELAY_MS
    }

    private fun resolveBleNoResponseDelayMs(
        payloadSize: Int,
        preferNoResponse: Boolean
    ): Long {
        val isControlPacket = preferNoResponse && payloadSize <= CONTROL_PACKET_MAX_BYTES
        return if (isControlPacket) WRITE_NO_RESPONSE_CHUNK_DELAY_CONTROL_MS else WRITE_NO_RESPONSE_CHUNK_DELAY_MS
    }

    private fun startUsbReadLoop() {
        usbReadJob?.cancel()
        usbReadJob = serviceScope.launch {
            val readBuffer = ByteArray(USB_READ_BUFFER_SIZE_BYTES)
            while (isActive && activeTransport == CommandTransport.USB) {
                val port = usbSerialPort ?: break
                try {
                    val bytesRead = port.read(readBuffer, USB_READ_TIMEOUT_MS.toInt())
                    if (bytesRead > 0) {
                        val payload = readBuffer.copyOf(bytesRead)
                        markPacketReceived()
                        completePendingOperation(payload)
                        flipperProtocol.processIncomingData(payload)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "USB read failed: ${t.message}")
                    disconnectUsbTransport(
                        setState = true,
                        errorMessage = "USB transport error: ${t.message ?: "read failed"}. " +
                                "Reconnect the cable or use Bluetooth.",
                        failure = ConnectionFailure.TRANSPORT_UNAVAILABLE
                    )
                    sessionRequested = false
                    scheduleIdleShutdown()
                    break
                }
            }
        }
    }

    private fun markBleActivity() {
        lastBleActivityAtMs = System.currentTimeMillis()
        updateConnectionHealth()
    }

    private fun markPacketReceived() {
        val now = System.currentTimeMillis()
        lastBleActivityAtMs = now
        lastPacketAtMs = now
        updateConnectionHealth()
    }

    /**
     * Starts the BLE connection watchdog.
     *
     * One loop handles liveness detection and keep-alive traffic:
     *  - probes the link (overflow read / RSSI) when it has been quiet;
     *  - treats [SILENT_DROP_TIMEOUT_MS] without any callback as a silent drop;
     *  - refreshes [connectionHealth] so the UI can show link age and reconnect count.
     *
     * The job is cancelled on disconnect, stop, transport switch, and service
     * destruction; it never outlives a BLE session.
     */
    private fun startConnectionWatchdog() {
        stopConnectionWatchdog()
        connectionWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(CONNECTION_WATCHDOG_TICK_MS)
                if (activeTransport != CommandTransport.BLE) continue
                if (!sessionRequested) continue
                if (_connectionState.value !is ConnectionState.Connected) continue
                if (!notificationsReady || !gattLinkConnected) continue

                if (!hasBluetoothPermissions()) {
                    setConnectionState(
                        ConnectionState.Error(
                            message = "Nearby devices permission was revoked. Grant it and reconnect.",
                            reason = ConnectionFailure.PERMISSION_DENIED
                        )
                    )
                    sessionRequested = false
                    connectionWatchdogJob = null
                    teardownBleTransport(clearRequestedDevice = true)
                    scheduleIdleShutdown()
                    return@launch
                }

                val now = System.currentTimeMillis()
                val lastActivity = if (lastBleActivityAtMs > 0L) {
                    lastBleActivityAtMs
                } else {
                    connectedSinceMs ?: now
                }
                val silentForMs = now - lastActivity
                updateConnectionHealth(force = true)

                if (silentForMs >= SILENT_DROP_TIMEOUT_MS) {
                    Log.w(TAG, "Silent BLE drop detected after ${silentForMs}ms without traffic")
                    handleSilentBleDrop()
                    return@launch
                }

                requestHighPriorityConnectionIfNeeded()
                if (silentForMs >= BLE_KEEPALIVE_IDLE_THRESHOLD_MS) {
                    sendBleKeepaliveProbe()
                }
            }
        }
    }

    private fun stopConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
    }

    private fun handleSilentBleDrop() {
        // A user-initiated disconnect may race with this tick; stay quiet then.
        if (!sessionRequested) return
        failPendingWriteAck()
        flipperProtocol.onConnectionReset()
        // Clear the handle before teardown: teardown cancels this job, and the
        // remaining reconnect scheduling must still run.
        connectionWatchdogJob = null
        teardownBleTransport(clearRequestedDevice = false)
        _connectedDevice.value = null
        connectedSinceMs = null
        if (!scheduleReconnect(reason = "BLE link went silent")) {
            // A series may already be in flight; let it own the state.
            if (reconnectJob?.isActive == true) return
            sessionRequested = false
            val detail = when {
                !hasBluetoothPermissions() ->
                    "Nearby devices permission is missing. Grant it, then reconnect."
                runCatching { bluetoothAdapter?.isEnabled != true }.getOrDefault(true) ->
                    "Bluetooth is off. Turn it on, then reconnect."
                else -> "Auto-reconnect gave up. Move closer, keep the Flipper unlocked, then reconnect."
            }
            setConnectionState(
                ConnectionState.Error(
                    message = "The Flipper stopped responding for ${SILENT_DROP_TIMEOUT_MS / 1000}s. $detail",
                    reason = ConnectionFailure.SILENT_DROP
                )
            )
            scheduleIdleShutdown()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBleKeepaliveProbe() {
        if (!hasBluetoothPermissions()) return
        val gatt = bluetoothGatt ?: return
        if (!isCurrentGatt(gatt)) return

        // Do not mark activity here: the read callback is what proves the link
        // is alive, and a dead link still accepts the queued request.
        when {
            serialOverflowCharacteristic != null -> {
                runCatching { gatt.readCharacteristic(serialOverflowCharacteristic) }
            }
            else -> {
                runCatching { gatt.readRemoteRssi() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestHighPriorityConnectionIfNeeded() {
        if (!hasBluetoothPermissions()) return
        if (activeTransport != CommandTransport.BLE) return
        val gatt = bluetoothGatt ?: return
        if (!isCurrentGatt(gatt)) return

        val now = System.currentTimeMillis()
        if (now - lastBlePriorityRequestAtMs < BLE_PRIORITY_REFRESH_INTERVAL_MS) return
        val requested = runCatching {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }.getOrDefault(false)
        if (requested) {
            lastBlePriorityRequestAtMs = now
        }
    }

    /**
     * Disconnects the active transport and closes the session.
     *
     * Safe from any thread: teardown is dispatched to the service scope and all
     * pending watchdog/reconnect jobs are cancelled first.
     */
    fun disconnect() {
        sessionRequested = false
        cancelPendingReconnect()
        cancelConnectAttemptTimeout()
        serviceScope.launch {
            disconnectInternal()
            scheduleIdleShutdown()
        }
    }

    private fun disconnectInternal() {
        failPendingWriteAck()
        flipperProtocol.onConnectionReset()
        stopScanInternal(updateState = false)
        stopConnectionWatchdog()
        cancelConnectAttemptTimeout()
        teardownBleTransport(clearRequestedDevice = true)
        disconnectUsbTransport(setState = false)
        _connectedDevice.value = null
        activeTransport = CommandTransport.NONE
        sessionRequested = false
        connectedSinceMs = null
        if (_connectionState.value !is ConnectionState.Error) {
            setConnectionState(ConnectionState.Disconnected)
        } else {
            updateConnectionHealth(force = true)
            updateNotification()
        }
    }

    private fun teardownAllTransports(updateState: Boolean, clearRequestedDevice: Boolean) {
        stopConnectionWatchdog()
        cancelConnectAttemptTimeout()
        teardownBleTransport(clearRequestedDevice = clearRequestedDevice)
        disconnectUsbTransport(setState = false)
        _connectedDevice.value = null
        connectedSinceMs = null
        activeTransport = CommandTransport.NONE
        if (updateState) {
            setConnectionState(ConnectionState.Disconnected)
        } else {
            updateConnectionHealth(force = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun teardownBleTransport(clearRequestedDevice: Boolean) {
        stopConnectionWatchdog()
        cancelConnectAttemptTimeout()
        gattLinkConnected = false
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            // Attempt cleanup even when the permission was revoked so the GATT
            // handle cannot leak; both calls are safe to run twice.
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        serialCharacteristic = null
        serialRxCharacteristic = null
        serialServiceUuid = null
        serialOverflowCharacteristic = null
        serialResetCharacteristic = null
        remainingSerialBufferBytes = null
        pendingConnectionName = null
        if (clearRequestedDevice) {
            lastRequestedDeviceAddress = null
            lastRequestedDeviceName = null
            lastRequestedBluetoothDevice = null
            reconnectAttemptCount = 0
        }
        pendingConnectedDevice = null
        notificationsReady = false
        negotiatedMtu = DEFAULT_ATT_MTU
        lastBleActivityAtMs = 0L
        lastBlePriorityRequestAtMs = 0L
        connectedSinceMs = null
        if (activeTransport == CommandTransport.BLE) {
            activeTransport = CommandTransport.NONE
        }
        updateConnectionHealth(force = true)
    }

    private fun disconnectUsbTransport(
        setState: Boolean,
        errorMessage: String? = null,
        failure: ConnectionFailure = ConnectionFailure.UNKNOWN
    ) {
        usbReadJob?.cancel()
        usbReadJob = null
        runCatching { usbSerialPort?.close() }
        runCatching { usbDeviceConnection?.close() }
        usbSerialDriver = null
        usbSerialPort = null
        usbDeviceConnection = null
        connectedUsbDevice = null
        currentUsbBaudRate = null
        pendingUsbPermissionRequest?.let { pending ->
            if (!pending.isCompleted) pending.complete(false)
        }
        pendingUsbPermissionRequest = null
        if (activeTransport == CommandTransport.USB) {
            activeTransport = CommandTransport.NONE
        }
        flipperProtocol.onConnectionReset()
        if (setState) {
            _connectedDevice.value = null
            connectedSinceMs = null
            setConnectionState(
                if (!errorMessage.isNullOrBlank()) {
                    ConnectionState.Error(errorMessage, failure)
                } else {
                    ConnectionState.Disconnected
                }
            )
        } else {
            updateConnectionHealth(force = true)
        }
    }

    /**
     * Guards every BluetoothGattCallback entry point. These methods run on a
     * Binder thread; an escaping exception kills the process instead of just
     * failing the connection, so each callback delegates into a guarded handler.
     */
    private fun runGattCallback(name: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "GATT callback $name failed", t)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            runGattCallback("onConnectionStateChange") {
                handleConnectionStateChange(gatt, status, newState)
            }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            runGattCallback("onServicesDiscovered") {
                handleServicesDiscovered(gatt, status)
            }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = runGattCallback("onDescriptorWrite") {
            handleDescriptorWrite(gatt, descriptor, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            runGattCallback("onMtuChanged") {
                if (isCurrentGatt(gatt) && status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu.coerceIn(DEFAULT_ATT_MTU, REQUESTED_ATT_MTU)
                }
            }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = runGattCallback("onCharacteristicChanged") {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) = runGattCallback("onCharacteristicChanged(legacy)") {
            handleCharacteristicChangedLegacy(gatt, characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) = runGattCallback("onCharacteristicRead") {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = runGattCallback("onCharacteristicRead(legacy)") {
            handleCharacteristicReadLegacy(gatt, characteristic, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = runGattCallback("onCharacteristicWrite") {
            handleCharacteristicWrite(gatt, status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) =
            runGattCallback("onReadRemoteRssi") {
                if (isCurrentGatt(gatt) && status == BluetoothGatt.GATT_SUCCESS) {
                    markBleActivity()
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isCurrentGatt(gatt)) {
            Log.d(
                TAG,
                "Ignoring stale onConnectionStateChange addr=${gatt.device.address} " +
                        "status=${describeGattStatus(status)} state=$newState"
            )
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { gatt.close() }
            }
            return
        }
        Log.d(
            TAG,
            "onConnectionStateChange addr=${gatt.device.address} " +
                    "status=${describeGattStatus(status)} state=$newState"
        )
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (!sessionRequested || activeTransport == CommandTransport.USB) {
                    // The user disconnected, or USB won the race, while
                    // connectGatt() was in flight.
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    return
                }
                cancelConnectAttemptTimeout()
                cancelPendingReconnect()
                reconnectAttemptCount = 0
                gattLinkConnected = true
                connectedSinceMs = System.currentTimeMillis()
                markBleActivity()
                lastRequestedBluetoothDevice = gatt.device
                lastRequestedDeviceAddress = gatt.device.address
                if (!hasBluetoothPermissions()) {
                    handleBleSetupFailure(
                        message = "Nearby devices permission was revoked while connecting. Grant it and retry.",
                        reason = ConnectionFailure.PERMISSION_DENIED,
                        retry = false
                    )
                    return
                }
                runCatching {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
                lastBlePriorityRequestAtMs = System.currentTimeMillis()
                runCatching { gatt.requestMtu(REQUESTED_ATT_MTU) }
                runCatching { gatt.discoverServices() }
            }
            BluetoothProfile.STATE_DISCONNECTED -> handleGattDisconnected(gatt, status)
        }
    }

    private fun handleGattDisconnected(gatt: BluetoothGatt, status: Int) {
        failPendingWriteAck()
        flipperProtocol.onConnectionReset()
        gattLinkConnected = false
        notificationsReady = false
        pendingConnectedDevice = null
        negotiatedMtu = DEFAULT_ATT_MTU
        serialCharacteristic = null
        serialRxCharacteristic = null
        serialServiceUuid = null
        serialOverflowCharacteristic = null
        serialResetCharacteristic = null
        stopConnectionWatchdog()
        cancelConnectAttemptTimeout()
        remainingSerialBufferBytes = null
        lastBleActivityAtMs = 0L
        lastBlePriorityRequestAtMs = 0L
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
        }
        runCatching { gatt.close() }
        _connectedDevice.value = null
        connectedSinceMs = null
        if (activeTransport == CommandTransport.BLE) {
            activeTransport = CommandTransport.NONE
        }

        if (!sessionRequested) {
            // Local disconnect in progress: never surface its GATT status as an
            // error to the user.
            setConnectionState(ConnectionState.Disconnected)
            scheduleIdleShutdown()
            return
        }

        val scheduled = scheduleReconnect(reason = "GATT disconnected (${describeGattStatus(status)})")
        if (scheduled || reconnectJob?.isActive == true) {
            val deviceName = lastRequestedDeviceName ?: pendingConnectionName ?: DEFAULT_FLIPPER_NAME
            setConnectionState(
                ConnectionState.Connecting(
                    "$deviceName (reconnect $reconnectAttemptCount/$MAX_RECONNECT_ATTEMPTS)"
                )
            )
            return
        }

        if (!hasBluetoothPermissions()) {
            sessionRequested = false
            setConnectionState(
                ConnectionState.Error(
                    message = "Nearby devices permission is required to keep the connection. " +
                            "Grant it, then reconnect.",
                    reason = ConnectionFailure.PERMISSION_DENIED
                )
            )
            scheduleIdleShutdown()
            return
        }

        // If the radio is off, keep the user's session intent so the Bluetooth
        // state receiver can resume with a fresh backoff budget when it returns.
        val radioOff = runCatching {
            bluetoothAdapter?.isEnabled != true
        }.getOrDefault(true)
        if (radioOff) {
            setConnectionState(
                ConnectionState.Error(
                    message = "Bluetooth is off. Turn Bluetooth on, unlock the Flipper, then reconnect.",
                    reason = ConnectionFailure.BLUETOOTH_DISABLED
                )
            )
            scheduleIdleShutdown()
            return
        }

        sessionRequested = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val statusText = describeGattStatus(status)
            val reasonHint = when (status) {
                GATT_STATUS_CONN_TERMINATE_PEER_USER ->
                    "The Flipper closed the session. Keep its Bluetooth app open and close competing Flipper apps."
                GATT_STATUS_GENERIC_ERROR ->
                    "Android BLE stack error. Toggle Bluetooth off/on and reconnect."
                else ->
                    "Keep the Flipper close and unlocked, close competing Flipper apps, then reconnect."
            }
            val exhaustedHint = if (reconnectAttemptCount >= MAX_RECONNECT_ATTEMPTS) {
                " Auto-reconnect gave up after $reconnectAttemptCount attempts."
            } else {
                ""
            }
            setConnectionState(
                ConnectionState.Error(
                    message = "BLE disconnected ($statusText). $reasonHint$exhaustedHint",
                    reason = when (status) {
                        GATT_STATUS_CONN_TERMINATE_PEER_USER -> ConnectionFailure.PROTOCOL_ERROR
                        GATT_STATUS_CONN_TIMEOUT -> ConnectionFailure.CONNECTION_TIMEOUT
                        else -> ConnectionFailure.TRANSPORT_UNAVAILABLE
                    }
                )
            )
        } else {
            setConnectionState(ConnectionState.Disconnected)
        }
        scheduleIdleShutdown()
    }

    @SuppressLint("MissingPermission")
    private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isCurrentGatt(gatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Service discovery failed status=${describeGattStatus(status)}")
            handleBleSetupFailure(
                message = "Android could not read the Flipper services (${describeGattStatus(status)}). " +
                        "Move closer, keep the Flipper unlocked, and retry.",
                reason = ConnectionFailure.SERVICE_DISCOVERY_FAILED,
                retry = true
            )
            return
        }

        val serialService = resolveSerialService(gatt)
        if (serialService == null) {
            val discoveredServices = gatt.services
                ?.joinToString(limit = 8) { it.uuid.toString() }
                .orEmpty()
            handleBleSetupFailure(
                message = "Flipper serial service not found. Leave Bluetooth Remote/Control mode on the " +
                        "Flipper, then retry. Services: $discoveredServices",
                reason = ConnectionFailure.SERVICE_DISCOVERY_FAILED,
                retry = false
            )
            return
        }

        val resolvedWrite = resolveWriteCharacteristic(serialService)
        val resolvedNotify = resolveNotifyCharacteristic(serialService, resolvedWrite)
        serialCharacteristic = resolvedWrite
        serialRxCharacteristic = resolvedNotify
        serialServiceUuid = serialService.uuid
        serialOverflowCharacteristic = serialService.getCharacteristic(FLIPPER_SERIAL_OVERFLOW_UUID)
        serialResetCharacteristic = serialService.getCharacteristic(FLIPPER_SERIAL_RESET_UUID)
        remainingSerialBufferBytes = null
        if (serialCharacteristic == null || serialRxCharacteristic == null) {
            handleBleSetupFailure(
                message = "The Flipper serial characteristics are not usable on this firmware. " +
                        "Update the Flipper firmware, then reconnect.",
                reason = ConnectionFailure.TRANSPORT_UNAVAILABLE,
                retry = false
            )
            return
        }

        val deviceName = resolveConnectedDeviceName(gatt.device.address, gatt.device.name)
        val device = FlipperDevice(
            address = gatt.device.address,
            name = deviceName,
            rssi = 0
        )
        pendingConnectedDevice = device
        notificationsReady = false

        if (!hasBluetoothPermissions()) {
            handleBleSetupFailure(
                message = "Nearby devices permission was revoked while connecting. Grant it and retry.",
                reason = ConnectionFailure.PERMISSION_DENIED,
                retry = false
            )
            return
        }

        // Enable notifications on the RX characteristic and wait for the
        // descriptor write callback before declaring the link connected.
        val rx = serialRxCharacteristic
        if (rx == null) {
            handleBleSetupFailure(
                message = "The Flipper RX characteristic is unavailable. Reconnect to retry.",
                reason = ConnectionFailure.TRANSPORT_UNAVAILABLE,
                retry = true
            )
            return
        }
        gatt.setCharacteristicNotification(rx, true)
        val descriptor = rx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            descriptor.value = if (supportsNotification(rx)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else if (supportsIndication(rx)) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            val started = gatt.writeDescriptor(descriptor)
            if (!started) {
                handleBleSetupFailure(
                    message = "Could not enable Flipper notifications. Reconnect to retry.",
                    reason = ConnectionFailure.TRANSPORT_UNAVAILABLE,
                    retry = true
                )
            }
            return
        }

        // Descriptor not available; continue with best-effort connection readiness.
        notificationsReady = true
        finalizeConnectedState(device)
    }

    private fun handleDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        if (!isCurrentGatt(gatt)) return
        if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG) return
        if (descriptor.characteristic?.uuid != serialRxCharacteristic?.uuid) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingConnectedDevice = null
            notificationsReady = false
            handleBleSetupFailure(
                message = "Could not enable Flipper notifications (${describeGattStatus(status)}). " +
                        "Reconnect to retry.",
                reason = ConnectionFailure.TRANSPORT_UNAVAILABLE,
                retry = true
            )
            return
        }

        notificationsReady = true
        val device = pendingConnectedDevice
        if (device != null) {
            finalizeConnectedState(device)
        }
    }

    private fun handleBleSetupFailure(
        message: String,
        reason: ConnectionFailure,
        retry: Boolean
    ) {
        if (!sessionRequested) {
            teardownBleTransport(clearRequestedDevice = true)
            return
        }
        Log.w(TAG, "BLE setup failed: $message (retry=$retry)")
        failPendingWriteAck()
        flipperProtocol.onConnectionReset()
        teardownBleTransport(clearRequestedDevice = false)
        _connectedDevice.value = null
        connectedSinceMs = null
        val scheduled = retry && scheduleReconnect(reason = message)
        if (!scheduled) {
            // Do not clobber a reconnect series that is already in flight.
            if (reconnectJob?.isActive == true) return
            sessionRequested = false
            setConnectionState(ConnectionState.Error(message, reason))
            scheduleIdleShutdown()
        }
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (!isCurrentGatt(gatt)) return
        markPacketReceived()
        if (characteristic.uuid == serialOverflowCharacteristic?.uuid) {
            updateOverflowCapacityFromBytes(value)
            return
        }
        if (shouldHandleIncomingCharacteristic(characteristic)) {
            completePendingOperation(value)
            flipperProtocol.processIncomingData(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCharacteristicChangedLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (!isCurrentGatt(gatt)) return
        markPacketReceived()
        if (characteristic.uuid == serialOverflowCharacteristic?.uuid) {
            characteristic.value?.let { updateOverflowCapacityFromBytes(it) }
            return
        }
        if (shouldHandleIncomingCharacteristic(characteristic)) {
            characteristic.value?.let { data ->
                completePendingOperation(data)
                flipperProtocol.processIncomingData(data)
            }
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (!isCurrentGatt(gatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) return
        markPacketReceived()
        if (characteristic.uuid == serialOverflowCharacteristic?.uuid) {
            updateOverflowCapacityFromBytes(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCharacteristicReadLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (!isCurrentGatt(gatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) return
        markPacketReceived()
        if (characteristic.uuid == serialOverflowCharacteristic?.uuid) {
            characteristic.value?.let { updateOverflowCapacityFromBytes(it) }
        }
    }

    private fun handleCharacteristicWrite(gatt: BluetoothGatt, status: Int) {
        if (!isCurrentGatt(gatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            markBleActivity()
        }
        pendingWriteAck?.let { ack ->
            if (!ack.isCompleted) {
                ack.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            flipperProtocol.onWriteComplete()
        } else {
            flipperProtocol.onWriteError(status)
        }
    }
    // Data transmission

    @SuppressLint("MissingPermission")
    suspend fun sendData(
        data: ByteArray,
        preferNoResponse: Boolean = false,
        ignoreOverflowBudget: Boolean = false
    ): Boolean {
        lastWriteFailureReason = null
        if (!awaitCommandTransportReady()) {
            lastWriteFailureReason = "Command transport not ready"
            return false
        }

        return if (isUsbCommandTransportConnected()) {
            sendDataOverUsb(data, preferNoResponse)
        } else {
            sendDataOverBle(data, preferNoResponse, ignoreOverflowBudget)
        }
    }

    private suspend fun sendDataOverUsb(
        data: ByteArray,
        preferNoResponse: Boolean
    ): Boolean {
        val port = usbSerialPort ?: run {
            lastWriteFailureReason = "USB serial port unavailable"
            return false
        }
        val lockTimeoutMs = resolveWriteLockTimeoutMs(data.size, preferNoResponse)
        val writeResult = withWriteMutexOrFail(
            lockTimeoutMs = lockTimeoutMs,
            timeoutReason = "USB write queue busy (timeout waiting for writer lock)"
        ) {
            withContext(Dispatchers.IO) {
                try {
                    val chunks = data.toList().chunked(USB_WRITE_CHUNK_SIZE_BYTES)
                    chunks.forEachIndexed { index, chunk ->
                        port.write(chunk.toByteArray(), USB_WRITE_TIMEOUT_MS.toInt())
                        if (index < chunks.lastIndex) {
                            delay(USB_WRITE_INTER_CHUNK_DELAY_MS)
                        }
                    }
                    lastWriteFailureReason = null
                    true
                } catch (t: Throwable) {
                    lastWriteFailureReason = "USB write failed: ${t.message ?: t::class.java.simpleName}"
                    false
                }
            }
        } ?: return false
        return writeResult
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendDataOverBle(
        data: ByteArray,
        preferNoResponse: Boolean,
        ignoreOverflowBudget: Boolean
    ): Boolean {
        if (!hasBluetoothPermissions()) {
            lastWriteFailureReason = "Bluetooth permission missing"
            return false
        }

        val characteristic = serialCharacteristic ?: run {
            lastWriteFailureReason = "Serial TX characteristic unavailable"
            return false
        }
        if (!notificationsReady) {
            // Descriptor callbacks can be delayed on some stacks; allow a short
            // settle window, but stop as soon as readiness flips.
            var waited = 0
            while (!notificationsReady && waited < NOTIFICATION_READY_WAIT_ATTEMPTS) {
                delay(NOTIFICATION_READY_POLL_MS)
                waited += 1
            }
            if (!notificationsReady) {
                lastWriteFailureReason = "Notifications not ready"
                return false
            }
        }
        requestHighPriorityConnectionIfNeeded()

        val lockTimeoutMs = resolveWriteLockTimeoutMs(data.size, preferNoResponse)
        val writeAttempts = resolveBleWriteAttempts(data.size, preferNoResponse)
        val writeRetryDelayMs = resolveBleWriteRetryDelayMs(data.size, preferNoResponse)
        val writeAckTimeoutMs = resolveBleAckTimeoutMs(data.size, preferNoResponse)
        val writeNoResponseDelayMs = resolveBleNoResponseDelayMs(data.size, preferNoResponse)

        val writeResult = withWriteMutexOrFail(
            lockTimeoutMs = lockTimeoutMs,
            timeoutReason = "BLE write queue busy (timeout waiting for writer lock)"
        ) {
            withContext(Dispatchers.IO) {
                val payloadChunkSize = (negotiatedMtu - ATT_WRITE_OVERHEAD_BYTES)
                    .coerceIn(MIN_BLE_CHUNK_BYTES, MAX_SERIAL_CHAR_VALUE_BYTES)
                val chunks = data.toList().chunked(payloadChunkSize)
                val writeTypeCandidates = resolveWriteTypeCandidates(
                    characteristic = characteristic,
                    preferNoResponse = preferNoResponse
                )

                for (chunk in chunks) {
                    val chunkData = chunk.toByteArray()
                    if (!ignoreOverflowBudget && !waitForOverflowBudget(chunkData.size)) {
                        pendingWriteAck = null
                        lastWriteFailureReason = "Serial overflow budget timeout"
                        return@withContext false
                    }

                    var chunkWritten = false
                    writeTypeLoop@ for ((attemptIndex, writeType) in writeTypeCandidates.withIndex()) {
                        for (retryIndex in 1..writeAttempts) {
                            val gatt = bluetoothGatt
                            if (gatt == null || !gattLinkConnected || _connectionState.value !is ConnectionState.Connected) {
                                pendingWriteAck = null
                                lastWriteFailureReason = "BLE link not connected while writing"
                                return@withContext false
                            }
                            if (!isCurrentGatt(gatt)) {
                                pendingWriteAck = null
                                lastWriteFailureReason = "BLE link changed during write"
                                return@withContext false
                            }

                            val requiresAck = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            val ack = if (requiresAck) CompletableDeferred<Boolean>() else null
                            pendingWriteAck = ack

                            characteristic.value = chunkData
                            characteristic.writeType = writeType

                            val started = gatt.writeCharacteristic(characteristic)
                            if (!started) {
                                pendingWriteAck = null
                                lastWriteFailureReason = "Gatt rejected write " +
                                        "(type=$writeType, attempt=${attemptIndex + 1}, retry=$retryIndex)"
                                if (retryIndex < writeAttempts) {
                                    delay(writeRetryDelayMs * retryIndex)
                                }
                                continue
                            }

                            if (requiresAck && ack != null) {
                                val confirmed = withTimeoutOrNull(writeAckTimeoutMs) {
                                    ack.await()
                                } ?: false
                                pendingWriteAck = null
                                if (!confirmed) {
                                    lastWriteFailureReason = "Write ack timeout " +
                                            "(type=$writeType, attempt=${attemptIndex + 1}, retry=$retryIndex)"
                                    if (retryIndex < writeAttempts) {
                                        delay(writeRetryDelayMs)
                                        continue
                                    }
                                    break
                                }
                                markBleActivity()
                            } else {
                                pendingWriteAck = null
                                markBleActivity()
                                // WRITE_NO_RESPONSE does not guarantee onCharacteristicWrite callbacks.
                                delay(writeNoResponseDelayMs)
                            }

                            chunkWritten = true
                            break@writeTypeLoop
                        }
                    }

                    if (!chunkWritten) {
                        remainingSerialBufferBytes = null
                        if (lastWriteFailureReason == null) {
                            lastWriteFailureReason = "Unknown write failure"
                        }
                        return@withContext false
                    }
                }
                lastWriteFailureReason = null
                true
            }
        } ?: run {
            pendingWriteAck = null
            return false
        }
        return writeResult
    }

    private suspend fun <T> withWriteMutexOrFail(
        lockTimeoutMs: Long,
        timeoutReason: String,
        block: suspend () -> T
    ): T? {
        val lockAcquired = withTimeoutOrNull(lockTimeoutMs) {
            writeMutex.lock()
            true
        } ?: false
        if (!lockAcquired) {
            lastWriteFailureReason = timeoutReason
            return null
        }

        return try {
            block()
        } finally {
            writeMutex.unlock()
        }
    }

    private fun failPendingWriteAck() {
        pendingWriteAck?.let { ack ->
            if (!ack.isCompleted) {
                ack.complete(false)
            }
        }
        pendingWriteAck = null
    }

    private fun updateOverflowCapacityFromBytes(bytes: ByteArray) {
        if (bytes.size < OVERFLOW_VALUE_SIZE_BYTES) return
        val remaining = java.nio.ByteBuffer.wrap(bytes.copyOfRange(0, OVERFLOW_VALUE_SIZE_BYTES)).int
        remainingSerialBufferBytes = remaining.coerceAtLeast(0)
    }

    private suspend fun waitForOverflowBudget(requiredBytes: Int): Boolean {
        val overflowCharacteristic = serialOverflowCharacteristic ?: return true
        val deadline = System.currentTimeMillis() + OVERFLOW_WAIT_TIMEOUT_MS

        while (true) {
            val available = remainingSerialBufferBytes
            if (available == null) {
                // No telemetry yet: best effort write path.
                return true
            }
            if (available >= requiredBytes) {
                remainingSerialBufferBytes = (available - requiredBytes).coerceAtLeast(0)
                return true
            }
            if (System.currentTimeMillis() >= deadline) {
                return false
            }
            // Try to refresh once while waiting in case notifications are stale.
            refreshOverflowCapacity(overflowCharacteristic)
            delay(OVERFLOW_WAIT_POLL_MS)
        }
    }

    private fun completePendingOperation(data: ByteArray) {
        val operationId = pendingOperationId ?: return
        val deferred = pendingOperations[operationId] ?: return
        if (!deferred.isCompleted) {
            deferred.complete(data.copyOf())
        }
        pendingOperations.remove(operationId)
        if (pendingOperationId == operationId) {
            pendingOperationId = null
        }
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        return bluetoothGatt === gatt
    }

    /**
     * Schedules one capped, cancellable reconnect series.
     *
     * Backoff is exponential (1s, 2s, 4s, 8s, 16s, capped at [RECONNECT_MAX_DELAY_MS])
     * and is reset as soon as a connection is established. Only one series can
     * run at a time, so callbacks, the watchdog and the UI cannot create a
     * reconnect storm.
     *
     * @return true when a series was scheduled, false when it is not eligible
     * (no requested session, radio off, permission missing, attempts exhausted).
     */
    @SuppressLint("MissingPermission")
    private fun scheduleReconnect(reason: String): Boolean {
        if (!sessionRequested) return false
        if (reconnectJob?.isActive == true) return false
        if (reconnectAttemptCount >= MAX_RECONNECT_ATTEMPTS) return false
        if (!hasBluetoothPermissions()) return false
        if (bluetoothAdapter?.isEnabled != true) return false

        val address = lastRequestedDeviceAddress ?: return false
        val bluetoothDevice = lastRequestedBluetoothDevice
            ?: discoveredBluetoothDevices[address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return false
        val deviceName = lastRequestedDeviceName ?: pendingConnectionName ?: DEFAULT_FLIPPER_NAME

        reconnectJob = serviceScope.launch {
            Log.w(TAG, "Reconnect series started: $reason")
            while (isActive && sessionRequested) {
                if (reconnectAttemptCount >= MAX_RECONNECT_ATTEMPTS) break
                reconnectAttemptCount += 1
                totalReconnectCount += 1
                updateConnectionHealth(force = true)
                val delayMs = resolveReconnectDelayMs(reconnectAttemptCount)
                setConnectionState(
                    ConnectionState.Connecting(
                        "$deviceName (reconnect $reconnectAttemptCount/$MAX_RECONNECT_ATTEMPTS)"
                    )
                )
                Log.w(
                    TAG,
                    "Reconnect attempt=$reconnectAttemptCount delayMs=$delayMs reason=$reason"
                )
                delay(delayMs)
                if (!isActive || !sessionRequested) return@launch
                // A radio that was turned off during the backoff is not an error:
                // the Bluetooth state receiver resumes the session when it is on.
                if (bluetoothAdapter?.isEnabled != true) return@launch

                teardownBleTransport(clearRequestedDevice = false)
                flipperProtocol.onConnectionReset()
                val useAutoConnect = reconnectAttemptCount > 1
                val gatt = connectGattCompat(bluetoothDevice, autoConnect = useAutoConnect)
                if (!sessionRequested || activeTransport != CommandTransport.NONE) {
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    gattLinkConnected = false
                    connectedSinceMs = null
                    return@launch
                }
                bluetoothGatt = gatt
                if (gatt != null) {
                    startConnectAttemptTimeout()
                    return@launch
                }
            }

            if (sessionRequested) {
                // Clear the job reference first: isSessionBusy() must see this
                // series as finished before idle shutdown is scheduled.
                reconnectJob = null
                sessionRequested = false
                setConnectionState(
                    ConnectionState.Error(
                        message = "Could not reconnect to $deviceName after " +
                                "$reconnectAttemptCount attempts. Make sure Bluetooth is on, the Flipper " +
                                "is unlocked and no other app is connected, then tap Connect.",
                        reason = ConnectionFailure.CONNECTION_TIMEOUT
                    )
                )
                scheduleIdleShutdown()
            }
        }
        return true
    }

    private fun resolveReconnectDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (RECONNECT_BASE_DELAY_MS shl exponent).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    private fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Guards a single connectGatt() attempt. Some stacks never deliver
     * onConnectionStateChange for a failed link, which would otherwise leave the
     * UI stuck on "Connecting" until the user retries.
     */
    private fun startConnectAttemptTimeout() {
        cancelConnectAttemptTimeout()
        connectAttemptTimeoutJob = serviceScope.launch {
            delay(CONNECT_ATTEMPT_TIMEOUT_MS)
            if (!sessionRequested) return@launch
            // A GATT link can be up while service discovery/notification setup is
            // still stuck. Treat that as a failed attempt too.
            if (notificationsReady) return@launch
            if (_connectionState.value !is ConnectionState.Connecting) return@launch
            Log.w(TAG, "BLE connect attempt timed out after ${CONNECT_ATTEMPT_TIMEOUT_MS}ms")
            connectAttemptTimeoutJob = null
            teardownBleTransport(clearRequestedDevice = false)
            if (!scheduleReconnect(reason = "Connect attempt timed out")) {
                // A series may already be in flight; let it own the state.
                if (reconnectJob?.isActive == true) return@launch
                sessionRequested = false
                setConnectionState(
                    ConnectionState.Error(
                        message = "Timed out connecting to the Flipper. Keep it unlocked and nearby, " +
                                "close other Flipper apps, then tap Connect again.",
                        reason = ConnectionFailure.CONNECTION_TIMEOUT
                    )
                )
                scheduleIdleShutdown()
            }
        }
    }

    private fun cancelConnectAttemptTimeout() {
        connectAttemptTimeoutJob?.cancel()
        connectAttemptTimeoutJob = null
    }

    private fun describeGattStatus(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "success"
            GATT_STATUS_CONN_TIMEOUT -> "conn_timeout (status=8)"
            GATT_STATUS_CONN_TERMINATE_PEER_USER -> "peer_terminated (status=19)"
            GATT_STATUS_CONN_TERMINATE_LOCAL_HOST -> "local_terminated (status=22)"
            GATT_STATUS_FAILURE -> "failure (status=257)"
            GATT_STATUS_GENERIC_ERROR -> "generic_error (status=133)"
            else -> "status=$status"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattCompat(device: BluetoothDevice, autoConnect: Boolean): BluetoothGatt? {
        if (!hasBluetoothPermissions()) return null
        return runCatching {
            device.connectGatt(
                this,
                autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }.onFailure { Log.w(TAG, "connectGatt failed: ${it.message}") }.getOrNull()
    }

    private fun resolveSerialService(gatt: BluetoothGatt): BluetoothGattService? {
        gatt.getService(FLIPPER_SERIAL_SERVICE_UUID)?.let { return it }

        val allServices = gatt.services.orEmpty()
        val candidateServices = allServices.filter { service ->
            service.uuid == FLIPPER_SERVICE_UUID ||
                    service.uuid == FLIPPER_SERVICE_UUID_BLACK ||
                    service.uuid == FLIPPER_SERVICE_UUID_TRANSPARENT ||
                    isFlipperFamilyService(service.uuid) ||
                    service.characteristics.orEmpty().any { characteristic ->
                        characteristic.uuid == FLIPPER_SERIAL_TX_UUID ||
                                characteristic.uuid == FLIPPER_SERIAL_RX_UUID
                    }
        }

        candidateServices.firstOrNull { service ->
            val characteristics = service.characteristics.orEmpty()
            val hasKnownTxRx = characteristics.any { it.uuid == FLIPPER_SERIAL_TX_UUID } &&
                    characteristics.any { it.uuid == FLIPPER_SERIAL_RX_UUID }
            val hasWrite = characteristics.any(::supportsWrite)
            val hasNotify = characteristics.any(::supportsNotifyOrIndicate)
            hasKnownTxRx && hasWrite && hasNotify
        }?.let { return it }

        candidateServices.firstOrNull { service ->
            val characteristics = service.characteristics.orEmpty()
            val hasWrite = characteristics.any(::supportsWrite)
            val hasNotify = characteristics.any(::supportsNotifyOrIndicate)
            hasWrite && hasNotify
        }?.let { return it }

        return null
    }

    private fun resolveWriteCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        val characteristics = service.characteristics.orEmpty()
        val preferredByUuid = listOf(FLIPPER_SERIAL_TX_UUID, FLIPPER_SERIAL_RX_UUID)
            .mapNotNull { uuid -> characteristics.firstOrNull { it.uuid == uuid } }
            .firstOrNull { supportsWrite(it) }
        if (preferredByUuid != null) return preferredByUuid

        return characteristics.firstOrNull { supportsWrite(it) }
    }

    private fun resolveNotifyCharacteristic(
        service: BluetoothGattService,
        writeCharacteristic: BluetoothGattCharacteristic?
    ): BluetoothGattCharacteristic? {
        val characteristics = service.characteristics.orEmpty()
        val preferredByUuid = listOf(FLIPPER_SERIAL_RX_UUID, FLIPPER_SERIAL_TX_UUID)
            .mapNotNull { uuid -> characteristics.firstOrNull { it.uuid == uuid } }
            .firstOrNull { supportsNotifyOrIndicate(it) }
        if (preferredByUuid != null) return preferredByUuid

        return characteristics.firstOrNull { it !== writeCharacteristic && supportsNotifyOrIndicate(it) }
            ?: characteristics.firstOrNull { supportsNotifyOrIndicate(it) }
    }

    private fun supportsWrite(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    private fun resolveWriteTypeCandidates(
        characteristic: BluetoothGattCharacteristic,
        preferNoResponse: Boolean = false
    ): List<Int> {
        val props = characteristic.properties
        val supportsWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        return when {
            supportsWrite && supportsNoResponse && preferNoResponse -> listOf(
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            supportsWrite && supportsNoResponse -> listOf(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            supportsWrite -> listOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            supportsNoResponse -> listOf(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            else -> listOf(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    fun consumeLastWriteFailureReason(): String? {
        val reason = lastWriteFailureReason
        lastWriteFailureReason = null
        return reason
    }

    private fun supportsNotifyOrIndicate(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }

    private fun supportsNotification(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    }

    private fun supportsIndication(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }

    private fun shouldHandleIncomingCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        if (characteristic.uuid == serialRxCharacteristic?.uuid) return true
        val serviceMatch = characteristic.service?.uuid == serialServiceUuid
                || characteristic.service?.uuid == FLIPPER_SERIAL_SERVICE_UUID
        return serviceMatch && supportsNotifyOrIndicate(characteristic)
    }

    private fun finalizeConnectedState(device: FlipperDevice) {
        pendingConnectionName = null
        pendingConnectedDevice = null
        activeTransport = CommandTransport.BLE
        gattLinkConnected = true
        cancelConnectAttemptTimeout()
        cancelPendingReconnect()
        // Success resets the backoff series; the cumulative count stays as a
        // health metric for the UI.
        reconnectAttemptCount = 0
        connectedSinceMs = System.currentTimeMillis()
        markBleActivity()
        normalizeBleAddress(device.address)?.let { normalized ->
            if (confirmedFlipperAddresses.add(normalized)) {
                persistConfirmedFlipperAddress(normalized)
            }
        }
        _connectedDevice.value = device
        setConnectionState(ConnectionState.Connected(device.name))
        updateConnectionHealth(force = true)
        startConnectionWatchdog()

        // Enable overflow control but do NOT auto-probe the automation channel on connect.
        // The CLI/RPC probe is expensive and should only run when the user explicitly
        // requests diagnostics or when a command actually needs the automation channel.
        serviceScope.launch {
            runCatching { enableOverflowControl() }
                .onFailure { Log.w(TAG, "Could not enable overflow control: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableOverflowControl() {
        val gatt = bluetoothGatt ?: return
        val overflowCharacteristic = serialOverflowCharacteristic ?: return
        if (!hasBluetoothPermissions()) return

        gatt.setCharacteristicNotification(overflowCharacteristic, true)
        val descriptor = overflowCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null && supportsNotifyOrIndicate(overflowCharacteristic)) {
            descriptor.value = if (supportsNotification(overflowCharacteristic)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            gatt.writeDescriptor(descriptor)
        }
        refreshOverflowCapacity(overflowCharacteristic)
    }

    @SuppressLint("MissingPermission")
    private fun refreshOverflowCapacity(overflowCharacteristic: BluetoothGattCharacteristic) {
        if (!hasBluetoothPermissions()) return
        bluetoothGatt?.readCharacteristic(overflowCharacteristic)
    }

    /**
     * Send a CLI command string to the Flipper.
     * Uses the protocol layer for CLI transport and compatibility mapping.
     * Supports Flipper CLI commands like: subghz, ir, nfc, ble_spam, etc.
     */
    suspend fun sendCommand(command: String): Boolean {
        return sendCommandWithOutput(command).isSuccess
    }

    /**
     * Send a CLI command and return raw output when available.
     * Use this when callers need actionable error text instead of a boolean.
     */
    suspend fun sendCommandWithOutput(command: String): Result<String> {
        return try {
            val response = flipperProtocol.sendCliCommand(command)
            when (response) {
                is ProtocolResponse.Success -> Result.success(response.message)
                is ProtocolResponse.FileContent -> Result.success(response.content)
                is ProtocolResponse.BinaryContent -> Result.success(response.data.toString(Charsets.UTF_8))
                is ProtocolResponse.Error -> Result.failure(FlipperException(response.message, response.code))
                is ProtocolResponse.DirectoryList,
                is ProtocolResponse.DeviceInformation ->
                    Result.failure(FlipperException("Unexpected response type for CLI command"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send a raw CLI command without protocol framing.
     * Use this for direct serial console interaction.
     */
    suspend fun sendRawCliCommand(command: String): Boolean {
        return flipperProtocol.sendRawCli(command)
    }

    suspend fun probeCliCapability(force: Boolean = false): CliCapabilityStatus {
        return flipperProtocol.probeCliAvailability(force)
    }

    fun isCommandTransportConnected(): Boolean {
        return isUsbCommandTransportConnected() || isBleCommandTransportConnected()
    }

    private fun isBleCommandTransportConnected(): Boolean {
        return activeTransport == CommandTransport.BLE &&
                gattLinkConnected &&
                bluetoothGatt != null &&
                serialCharacteristic != null &&
                serialRxCharacteristic != null &&
                notificationsReady &&
                _connectionState.value is ConnectionState.Connected
    }

    private fun isUsbCommandTransportConnected(): Boolean {
        return activeTransport == CommandTransport.USB &&
                usbSerialPort != null &&
                usbDeviceConnection != null &&
                usbReadJob?.isActive == true &&
                _connectionState.value is ConnectionState.Connected
    }

    /**
     * Waits briefly for the command transport to become usable.
     *
     * Fails fast when there is no session at all (disconnected/error with no
     * pending reconnect) so a disconnection cannot stall every queued command
     * for the full readiness window.
     */
    suspend fun awaitCommandTransportReady(
        timeoutMs: Long = COMMAND_TRANSPORT_READY_TIMEOUT_MS
    ): Boolean {
        if (isCommandTransportConnected()) return true
        val waitingForSession = sessionRequested ||
                scanActive ||
                reconnectJob?.isActive == true ||
                _connectionState.value is ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Connected
        if (!waitingForSession) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isCommandTransportConnected()) return true
            delay(COMMAND_TRANSPORT_READY_POLL_MS)
        }
        return isCommandTransportConnected()
    }

    suspend fun runConnectionDiagnostics(): ConnectionDiagnosticsReport {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val checks = mutableListOf<ConnectionCheckResult>()

            suspend fun runCheck(
                name: String,
                block: suspend () -> Pair<ConnectionCheckLevel, String>
            ) {
                val checkStartedAt = System.currentTimeMillis()
                val result = try {
                    val (level, detail) = block()
                    ConnectionCheckResult(
                        name = name,
                        level = level,
                        detail = detail,
                        elapsedMs = System.currentTimeMillis() - checkStartedAt
                    )
                } catch (t: Throwable) {
                    ConnectionCheckResult(
                        name = name,
                        level = ConnectionCheckLevel.FAIL,
                        detail = t.message?.take(180) ?: "Unexpected exception",
                        elapsedMs = System.currentTimeMillis() - checkStartedAt
                    )
                }
                checks += result
            }

            runCheck("Command Transport") {
                val connected = connectionState.value is ConnectionState.Connected
                val transportConnected = isCommandTransportConnected()
                val activeMode = activeTransport
                val writeUuid = serialCharacteristic?.uuid
                val notifyUuid = serialRxCharacteristic?.uuid
                when {
                    transportConnected && activeMode == CommandTransport.USB -> {
                        val usbDevice = connectedUsbDevice
                        val usbName = usbDevice?.let { resolveUsbDeviceName(it) } ?: "USB"
                        val baud = currentUsbBaudRate?.toString() ?: "default"
                        ConnectionCheckLevel.PASS to "Connected over USB: $usbName (baud=$baud)"
                    }
                    transportConnected -> ConnectionCheckLevel.PASS to
                            "Connected. mtu=$negotiatedMtu tx=$writeUuid rx=$notifyUuid"
                    writeUuid == null || notifyUuid == null ->
                        ConnectionCheckLevel.FAIL to "BLE serial characteristics are unavailable."
                    gattLinkConnected ->
                        ConnectionCheckLevel.WARN to "BLE GATT link is up, but command transport is not ready yet."
                    !notificationsReady ->
                        ConnectionCheckLevel.WARN to "Connected over BLE, but notifications are not fully ready yet."
                    !connected ->
                        ConnectionCheckLevel.FAIL to "Service is not in connected state."
                    else ->
                        ConnectionCheckLevel.WARN to "Transport not ready despite connected state."
                }
            }

            runCheck("USB Host") {
                val manager = usbManager
                if (manager == null) {
                    return@runCheck ConnectionCheckLevel.FAIL to "USB host manager unavailable."
                }
                val attached = manager.deviceList.values.toList()
                if (attached.isEmpty()) {
                    return@runCheck ConnectionCheckLevel.WARN to "No USB devices attached."
                }
                val summary = describeUsbDeviceList(attached)
                if (activeTransport == CommandTransport.USB && isUsbCommandTransportConnected()) {
                    ConnectionCheckLevel.PASS to "Active USB transport. Attached: $summary"
                } else {
                    ConnectionCheckLevel.WARN to "USB devices detected but not active transport. Attached: $summary"
                }
            }

            var cliStatus = CliCapabilityStatus()
            runCheck("CLI/RPC Capability Probe") {
                cliStatus = flipperProtocol.probeCliAvailability(force = true)
                when {
                    cliStatus.level == CliCapabilityLevel.READY &&
                            cliStatus.supportsCli &&
                            cliStatus.supportsRpc ->
                        ConnectionCheckLevel.PASS to "CLI and RPC responsive."
                    cliStatus.level == CliCapabilityLevel.READY && cliStatus.supportsCli ->
                        ConnectionCheckLevel.WARN to "CLI responsive; RPC not confirmed."
                    cliStatus.level == CliCapabilityLevel.READY && cliStatus.supportsRpc ->
                        ConnectionCheckLevel.WARN to "RPC responsive; CLI unavailable on this session."
                    cliStatus.level == CliCapabilityLevel.READY ->
                        ConnectionCheckLevel.WARN to "Transport responsive, capability profile is partial."
                    else ->
                        ConnectionCheckLevel.FAIL to cliStatus.details
                }
            }

            runCheck("RPC Ping") {
                val rpcStatus = flipperProtocol.probeRpcAvailability()
                if (rpcStatus.supportsRpc) {
                    ConnectionCheckLevel.PASS to "RPC ping acknowledged."
                } else if (rpcStatus.level == CliCapabilityLevel.READY && rpcStatus.supportsCli) {
                    ConnectionCheckLevel.WARN to "RPC ping unavailable; CLI still responsive."
                } else {
                    ConnectionCheckLevel.FAIL to rpcStatus.details
                }
            }

            runCheck("Firmware Compatibility") {
                val profile = flipperProtocol.firmwareCompatibility.value
                val mode = when (profile.transportMode) {
                    FirmwareTransportMode.CLI_AND_RPC -> "CLI+RPC"
                    FirmwareTransportMode.CLI_ONLY -> "CLI-only"
                    FirmwareTransportMode.RPC_ONLY -> "RPC-only"
                    FirmwareTransportMode.PROBING -> "Probing..."
                    FirmwareTransportMode.UNAVAILABLE -> "Unavailable"
                }
                val detail = "${profile.label} | mode=$mode | ${profile.notes}"
                when (profile.transportMode) {
                    FirmwareTransportMode.CLI_AND_RPC -> ConnectionCheckLevel.PASS to detail
                    FirmwareTransportMode.CLI_ONLY,
                    FirmwareTransportMode.RPC_ONLY -> ConnectionCheckLevel.WARN to detail
                    FirmwareTransportMode.PROBING -> ConnectionCheckLevel.WARN to detail
                    FirmwareTransportMode.UNAVAILABLE -> ConnectionCheckLevel.FAIL to detail
                }
            }

            runCheck("CLI Echo (version)") {
                if (!cliStatus.supportsCli) {
                    return@runCheck ConnectionCheckLevel.SKIPPED to
                            "Skipped because this session is RPC-only."
                }
                val versionResult = sendCommandWithOutput("version")
                if (versionResult.isSuccess) {
                    val firstLine = versionResult.getOrNull()
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.firstOrNull { it.isNotEmpty() }
                        ?.take(160)
                        .orEmpty()
                    if (firstLine.isBlank()) {
                        ConnectionCheckLevel.WARN to "Command returned without visible output."
                    } else {
                        ConnectionCheckLevel.PASS to firstLine
                    }
                } else {
                    val message = versionResult.exceptionOrNull()?.message ?: "No CLI response."
                    val normalized = message.lowercase()
                    val cliModeMismatch = normalized.contains("non-protocol response") ||
                            normalized.contains("no cli response") ||
                            normalized.contains("rpc is responsive") ||
                            normalized.contains("rpc-only")
                    if (cliModeMismatch && cliStatus.supportsRpc) {
                        when (val infoResponse = flipperProtocol.getDeviceInfo()) {
                            is ProtocolResponse.DeviceInformation -> {
                                val info = infoResponse.deviceInfo
                                ConnectionCheckLevel.WARN to
                                        "CLI unavailable on this session; RPC is responsive (fw=${info.firmwareVersion})."
                            }
                            else -> ConnectionCheckLevel.WARN to
                                    "CLI unavailable on this session, but RPC is responsive."
                        }
                    } else {
                        ConnectionCheckLevel.FAIL to message.take(180)
                    }
                }
            }

            runCheck("Device Info RPC/CLI") {
                when (val response = flipperProtocol.getDeviceInfo()) {
                    is ProtocolResponse.DeviceInformation -> {
                        val info = response.deviceInfo
                        ConnectionCheckLevel.PASS to
                                "name=${info.name}, fw=${info.firmwareVersion}, battery=${info.batteryLevel}%"
                    }
                    is ProtocolResponse.Error -> {
                        ConnectionCheckLevel.FAIL to response.message.take(180)
                    }
                    else -> {
                        ConnectionCheckLevel.WARN to "Unexpected response: ${response::class.simpleName}"
                    }
                }
            }

            runCheck("Storage Probe") {
                when (val response = flipperProtocol.getStorageInfo()) {
                    is ProtocolResponse.DeviceInformation -> {
                        val storage = response.storageInfo
                        val internalFreeMb = storage.internalFree / (1024 * 1024)
                        val internalTotalMb = storage.internalTotal / (1024 * 1024)
                        ConnectionCheckLevel.PASS to
                                "internal=${internalFreeMb}MB/${internalTotalMb}MB free, sd=${storage.hasSdCard}"
                    }
                    is ProtocolResponse.Error -> {
                        if (shouldUseCliDiagnosticFallback(response.message)) {
                            val cliFallback = sendCommandWithOutput("storage info")
                            if (cliFallback.isSuccess) {
                                val summary = summarizeCliLine(cliFallback.getOrNull())
                                ConnectionCheckLevel.PASS to
                                        "CLI fallback succeeded: ${summary.ifBlank { "storage info responded" }}"
                            } else {
                                val cliMessage = cliFallback.exceptionOrNull()?.message?.take(120).orEmpty()
                                val combined = buildString {
                                    append(response.message.take(120))
                                    if (cliMessage.isNotBlank()) {
                                        append(" | CLI fallback failed: ")
                                        append(cliMessage)
                                    }
                                }
                                ConnectionCheckLevel.WARN to combined
                            }
                        } else {
                            ConnectionCheckLevel.WARN to response.message.take(180)
                        }
                    }
                    else -> {
                        ConnectionCheckLevel.WARN to "Unexpected response: ${response::class.simpleName}"
                    }
                }
            }

            runCheck("Filesystem Listing") {
                val probePaths = listOf("/", "/ext", "/any")
                val failures = mutableListOf<String>()
                for (path in probePaths) {
                    when (val response = flipperProtocol.listDirectory(path)) {
                        is ProtocolResponse.DirectoryList -> {
                            return@runCheck ConnectionCheckLevel.PASS to
                                    "Listed ${response.entries.size} entries at $path"
                        }
                        is ProtocolResponse.Error -> {
                            if (shouldUseCliDiagnosticFallback(response.message)) {
                                val cliFallback = sendCommandWithOutput("storage list $path")
                                if (cliFallback.isSuccess) {
                                    val entryCount = estimateCliListingEntries(cliFallback.getOrNull().orEmpty())
                                    return@runCheck ConnectionCheckLevel.PASS to
                                            if (entryCount > 0) {
                                                "Listed $entryCount entries at $path (CLI fallback)"
                                            } else {
                                                "CLI listing responded at $path (fallback), no visible entries."
                                            }
                                } else {
                                    val cliMessage = cliFallback.exceptionOrNull()?.message?.take(50).orEmpty()
                                    failures += "$path: ${response.message.take(40)} | CLI: $cliMessage"
                                }
                            } else {
                                failures += "$path: ${response.message.take(60)}"
                            }
                        }
                        else -> failures += "$path: ${response::class.simpleName}"
                    }
                }
                ConnectionCheckLevel.WARN to
                        "Directory probe did not return a listing. ${failures.joinToString(limit = 3)}"
            }

            ConnectionDiagnosticsReport(
                startedAtMs = startedAt,
                completedAtMs = System.currentTimeMillis(),
                checks = checks.toList()
            )
        }
    }

    private fun shouldUseCliDiagnosticFallback(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("non-protocol response") ||
                normalized.contains("invalid protocol frame") ||
                normalized.contains("no rpc response") ||
                normalized.contains("rpc transport is unavailable") ||
                normalized.contains("rpc transport unavailable") ||
                normalized.contains("rpc ping did not respond") ||
                normalized.contains("timed out")
    }

    private fun summarizeCliLine(rawOutput: String?): String {
        return rawOutput.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                        !line.startsWith(">") &&
                        !line.startsWith("storage>")
            }
            ?.take(160)
            .orEmpty()
    }

    private fun estimateCliListingEntries(rawOutput: String): Int {
        return rawOutput.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                        !line.startsWith(">") &&
                        !line.startsWith("storage>") &&
                        !line.startsWith("usage", ignoreCase = true) &&
                        !line.startsWith("error", ignoreCase = true)
            }
            .count()
    }

    @SuppressLint("MissingPermission")
    suspend fun restartSerialRpc(): Boolean {
        val resetCharacteristic = serialResetCharacteristic ?: return false
        val gatt = bluetoothGatt ?: return false
        if (!hasBluetoothPermissions()) return false

        return withWriteMutexOrFail(
            lockTimeoutMs = WRITE_MUTEX_CONTROL_WAIT_TIMEOUT_MS,
            timeoutReason = "Serial reset skipped: write queue busy"
        ) {
            withContext(Dispatchers.IO) {
                val ack = CompletableDeferred<Boolean>()
                pendingWriteAck = ack
                resetCharacteristic.value = byteArrayOf(0)
                resetCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val started = gatt.writeCharacteristic(resetCharacteristic)
                if (!started) {
                    pendingWriteAck = null
                    return@withContext false
                }
                val confirmed = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.await() } ?: false
                pendingWriteAck = null
                confirmed
            }
        } ?: false
    }

    /**
     * Send raw data and wait for response
     */
    suspend fun sendRawData(data: ByteArray): Result<ByteArray> {
        return rawOperationMutex.withLock {
            if (pendingOperationId != null) {
                return@withLock Result.failure(Exception("Raw operation already in progress"))
            }

            val responseDeferred = CompletableDeferred<ByteArray>()
            val operationId = java.util.UUID.randomUUID().toString()
            pendingOperations[operationId] = responseDeferred
            pendingOperationId = operationId

            try {
                if (!sendData(data)) {
                    return@withLock Result.failure(Exception("Failed to send data"))
                }

                val response = withTimeoutOrNull(5000L) {
                    responseDeferred.await()
                }

                if (response != null) {
                    Result.success(response)
                } else {
                    Result.failure(Exception("Response timeout"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pendingOperations.remove(operationId)
                if (pendingOperationId == operationId) {
                    pendingOperationId = null
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun resolveScanDeviceName(result: ScanResult): String {
        return result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: getDeviceNameSafely(result.device)
            ?: "$BLE_DEVICE_PREFIX ${result.device.address.takeLast(5)}"
    }

    private fun getDeviceNameSafely(device: BluetoothDevice): String? {
        if (!hasBluetoothPermissions()) return null
        return try {
            device.name?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun resolveConnectedDeviceName(address: String, deviceName: String?): String {
        return deviceName?.takeIf { it.isNotBlank() }
            ?: discoveredDeviceNames[address]
            ?: pendingConnectionName
            ?: DEFAULT_FLIPPER_NAME
    }

    @SuppressLint("MissingPermission")
    private fun seedBondedFlippersIntoDiscoveredList() {
        if (!hasBluetoothPermissions()) return
        val adapter = bluetoothAdapter ?: return
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) return

        val seeded = _discoveredDevices.value.toMutableList()
        bonded.forEach { bondedDevice ->
            val address = bondedDevice.address ?: return@forEach
            val normalizedAddress = normalizeBleAddress(address)
            val name = getDeviceNameSafely(bondedDevice).orEmpty()
            val addressMatch = hasKnownFlipperAddressPrefix(address)
            val nameMatch = isLikelyFlipperName(name)
            val wasConfirmedEarlier = normalizedAddress?.let { confirmedFlipperAddresses.contains(it) } == true
            if (!addressMatch && !nameMatch && !wasConfirmedEarlier) return@forEach

            discoveredBluetoothDevices[address] = bondedDevice
            if (name.isNotBlank() && !isPlaceholderName(name)) {
                discoveredDeviceNames[address] = name
            }
            if (addressMatch || wasConfirmedEarlier) {
                normalizedAddress?.let { confirmedFlipperAddresses.add(it) }
            }

            val existingIndex = seeded.indexOfFirst { it.address == address }
            val seededDevice = FlipperDevice(
                address = address,
                name = if (name.isNotBlank()) name else DEFAULT_FLIPPER_NAME,
                rssi = Int.MIN_VALUE,
                isConfirmedFlipper = addressMatch || wasConfirmedEarlier
            )
            if (existingIndex >= 0) {
                val existing = seeded[existingIndex]
                val resolvedName = if (isPlaceholderName(existing.name) && name.isNotBlank()) {
                    name
                } else {
                    existing.name
                }
                seeded[existingIndex] = existing.copy(
                    name = resolvedName,
                    isConfirmedFlipper = existing.isConfirmedFlipper || addressMatch || wasConfirmedEarlier
                )
            } else {
                seeded += seededDevice
            }
        }

        _discoveredDevices.value = seeded.sortedWith(
            compareByDescending<FlipperDevice> { it.isConfirmedFlipper }
                .thenByDescending { it.rssi }
        ).take(MAX_DISCOVERED_DEVICES)
    }

    private fun hasKnownFlipperService(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        val advertisedUuids = buildList {
            addAll(record.serviceUuids.orEmpty().map { it.uuid })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addAll(record.serviceSolicitationUuids.orEmpty().map { it.uuid })
            }
            addAll(record.serviceData.keys.map { it.uuid })
        }
        if (advertisedUuids.isEmpty()) return false
        return advertisedUuids.any { uuid ->
            uuid == FLIPPER_SERVICE_UUID ||
                    uuid == FLIPPER_SERIAL_SERVICE_UUID ||
                    isFlipperFamilyService(uuid)
        }
    }

    private fun hasLikelyFlipperManufacturerData(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        val manufacturerData = record.manufacturerSpecificData ?: return false
        for (index in 0 until manufacturerData.size()) {
            val manufacturerId = manufacturerData.keyAt(index)
            if (manufacturerId in KNOWN_FLIPPER_BLE_MANUFACTURER_IDS) {
                return true
            }
            val payload = manufacturerData.valueAt(index) ?: continue
            val asciiPayload = payload.toString(Charsets.UTF_8)
                .lowercase(Locale.US)
                .take(80)
            if (asciiPayload.contains("flipper")) {
                return true
            }
        }
        return false
    }

    private fun isFlipperFamilyService(uuid: UUID): Boolean {
        val normalized = uuid.toString().lowercase(Locale.US)
        return normalized.startsWith(FLIPPER_SERVICE_PREFIX) &&
                normalized.endsWith(BLUETOOTH_BASE_UUID_SUFFIX)
    }

    private fun hasKnownFlipperAddressPrefix(address: String?): Boolean {
        val normalized = normalizeBleAddress(address) ?: return false
        return normalized.startsWith(FLIPPER_MAC_PREFIX)
    }

    private fun isLikelyFlipperName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.isBlank()) return false
        return normalized.startsWith(FLIPPER_NAME_PREFIX.lowercase(Locale.US)) ||
                normalized.contains(" flipper") ||
                normalized.contains("f0") ||
                normalized.contains("f-zero")
    }

    private fun isPlaceholderName(name: String): Boolean {
        return name.isBlank() ||
                name == UNKNOWN_DEVICE_NAME ||
                name.startsWith(BLE_DEVICE_PREFIX)
    }

    private fun isLikelyFlipperUsbDevice(device: UsbDevice): Boolean {
        val metadata = listOf(
            device.productName.orEmpty(),
            device.manufacturerName.orEmpty(),
            device.deviceName.orEmpty()
        ).joinToString(separator = " ").lowercase(Locale.US)
        if (metadata.contains(FLIPPER_NAME_PREFIX.lowercase(Locale.US))) {
            return true
        }

        val vendorMatch = device.vendorId in KNOWN_FLIPPER_USB_VENDOR_IDS
        val productMatch = device.productId in KNOWN_FLIPPER_USB_PRODUCT_IDS
        return vendorMatch && productMatch
    }

    private fun resolveUsbDeviceName(device: UsbDevice): String {
        val productName = device.productName?.takeIf { it.isNotBlank() }
        val manufacturerName = device.manufacturerName?.takeIf { it.isNotBlank() }
        val compositeName = listOfNotNull(manufacturerName, productName)
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotBlank() }
        if (compositeName != null) return compositeName
        return "Flipper USB #${device.deviceId}"
    }

    private fun describeUsbDeviceList(devices: List<UsbDevice>): String {
        if (devices.isEmpty()) return "none"
        return devices.joinToString(limit = 4) { device ->
            buildString {
                append(resolveUsbDeviceName(device))
                append(" [vid=0x")
                append(device.vendorId.toString(16))
                append(", pid=0x")
                append(device.productId.toString(16))
                append(']')
            }
        }
    }

    private fun normalizeBleAddress(address: String?): String? {
        return address?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
    }

    private fun loadPersistedConfirmedFlippers() {
        val prefs = getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE)
        val savedAddresses = prefs.getStringSet(KEY_CONFIRMED_FLIPPER_ADDRESSES, emptySet()).orEmpty()
        confirmedFlipperAddresses.addAll(
            savedAddresses.mapNotNull { normalizeBleAddress(it) }
        )
    }

    private fun persistConfirmedFlipperAddress(address: String) {
        val normalized = normalizeBleAddress(address) ?: return
        val prefs = getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_CONFIRMED_FLIPPER_ADDRESSES, emptySet()).orEmpty()
        if (normalized in existing) return
        val updated = existing.toMutableSet().apply { add(normalized) }
        prefs.edit().putStringSet(KEY_CONFIRMED_FLIPPER_ADDRESSES, updated).apply()
    }

    private fun Intent.extractUsbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val TAG = "FlipperBleService"

        /**
         * Legacy action: promotes the service to the foreground without starting
         * any new work. Kept for callers compiled against the old lifecycle.
         */
        const val ACTION_START_FOREGROUND = "com.vesper.flipper.START_FOREGROUND"

        /** Starts a scan session in the foreground (user-initiated scan). */
        const val ACTION_START_SCAN = "com.vesper.flipper.START_SCAN"

        /** Stops an in-flight scan; the service drops to idle when nothing else is busy. */
        const val ACTION_STOP_SCAN = "com.vesper.flipper.STOP_SCAN"

        /** Starts a BLE connection session. Requires [EXTRA_DEVICE_ADDRESS]. */
        const val ACTION_CONNECT = "com.vesper.flipper.CONNECT"

        /** Starts a USB connection session. */
        const val ACTION_CONNECT_USB = "com.vesper.flipper.CONNECT_USB"

        /** Keeps the foreground session alive without starting new work. */
        const val ACTION_SESSION_ACTIVE = "com.vesper.flipper.SESSION_ACTIVE"

        /** Warms the service without promoting it to the foreground. */
        const val ACTION_WARM_START = "com.vesper.flipper.WARM_START"

        /** Stops the session and the service. */
        const val ACTION_STOP = "com.vesper.flipper.STOP"

        const val ACTION_USB_PERMISSION = "com.vesper.flipper.USB_PERMISSION"

        const val EXTRA_DEVICE_ADDRESS = "com.vesper.flipper.extra.DEVICE_ADDRESS"
        const val EXTRA_DEVICE_NAME = "com.vesper.flipper.extra.DEVICE_NAME"

        const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CONTENT_INTENT_REQUEST_CODE = 0

        const val SCAN_TIMEOUT_MS = 30_000L
        private const val BROAD_SCAN_FALLBACK_DELAY_MS = 4_000L
        private const val MAX_DISCOVERED_DEVICES = 60

        // Flipper Zero BLE UUIDs
        val FLIPPER_SERVICE_UUID: UUID = UUID.fromString("00003082-0000-1000-8000-00805f9b34fb")
        val FLIPPER_SERVICE_UUID_BLACK: UUID = UUID.fromString("00003081-0000-1000-8000-00805f9b34fb")
        val FLIPPER_SERVICE_UUID_TRANSPARENT: UUID = UUID.fromString("00003083-0000-1000-8000-00805f9b34fb")
        val FLIPPER_SERIAL_SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val FLIPPER_SERIAL_TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        val FLIPPER_SERIAL_RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        val FLIPPER_SERIAL_OVERFLOW_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")
        val FLIPPER_SERIAL_RESET_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e64fe0000")
        private val FLIPPER_SCAN_SERVICE_UUIDS = listOf(
            FLIPPER_SERVICE_UUID,
            FLIPPER_SERVICE_UUID_BLACK,
            FLIPPER_SERVICE_UUID_TRANSPARENT,
            FLIPPER_SERIAL_SERVICE_UUID
        )

        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val UNKNOWN_DEVICE_NAME = "Unknown Device"
        private const val BLE_DEVICE_PREFIX = "BLE Device"
        private const val DEFAULT_FLIPPER_NAME = "Flipper"
        private const val FLIPPER_NAME_PREFIX = "Flipper"
        private const val FLIPPER_MAC_PREFIX = "80:E1:26:"
        private const val FLIPPER_SERVICE_PREFIX = "0000308"
        private const val BLUETOOTH_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
        private const val PREFS_BLE = "flipper_ble_prefs"
        private const val KEY_CONFIRMED_FLIPPER_ADDRESSES = "confirmed_flipper_addresses"
        private const val CONTROL_PACKET_MAX_BYTES = 96
        private const val WRITE_ACK_TIMEOUT_MS = 3_000L
        private const val WRITE_ACK_TIMEOUT_CONTROL_MS = 800L
        private const val WRITE_NO_RESPONSE_CHUNK_DELAY_MS = 8L
        private const val WRITE_NO_RESPONSE_CHUNK_DELAY_CONTROL_MS = 3L
        private const val MAX_WRITE_START_ATTEMPTS = 5
        private const val MAX_WRITE_START_ATTEMPTS_CONTROL = 3
        private const val WRITE_START_RETRY_DELAY_MS = 80L
        private const val WRITE_START_RETRY_DELAY_CONTROL_MS = 30L
        private const val WRITE_MUTEX_STANDARD_WAIT_TIMEOUT_MS = 10_000L
        private const val WRITE_MUTEX_CONTROL_WAIT_TIMEOUT_MS = 500L
        private const val NOTIFICATION_READY_WAIT_ATTEMPTS = 20
        private const val NOTIFICATION_READY_POLL_MS = 50L
        private const val COMMAND_TRANSPORT_READY_TIMEOUT_MS = 5_000L
        private const val COMMAND_TRANSPORT_READY_POLL_MS = 50L
        private const val CONNECTION_WATCHDOG_TICK_MS = 5_000L
        private const val BLE_KEEPALIVE_IDLE_THRESHOLD_MS = 3_500L
        private const val BLE_PRIORITY_REFRESH_INTERVAL_MS = 45_000L
        private const val SILENT_DROP_TIMEOUT_MS = 25_000L
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 20_000L
        private const val IDLE_STOP_GRACE_MS = 2_500L
        private const val HEALTH_PUBLISH_MIN_INTERVAL_MS = 1_000L
        private const val OVERFLOW_WAIT_TIMEOUT_MS = 3_000L
        private const val OVERFLOW_WAIT_POLL_MS = 20L
        private const val OVERFLOW_VALUE_SIZE_BYTES = 4
        private const val DEFAULT_ATT_MTU = 23
        private const val REQUESTED_ATT_MTU = 517
        private const val ATT_WRITE_OVERHEAD_BYTES = 3
        private const val MIN_BLE_CHUNK_BYTES = 20
        // Flipper serial profile max characteristic value length (firmware serial_service.h).
        private const val MAX_SERIAL_CHAR_VALUE_BYTES = 243
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val GATT_STATUS_CONN_TIMEOUT = 8
        private const val GATT_STATUS_CONN_TERMINATE_PEER_USER = 19
        private const val GATT_STATUS_CONN_TERMINATE_LOCAL_HOST = 22
        private const val GATT_STATUS_GENERIC_ERROR = 133
        private const val GATT_STATUS_FAILURE = 257
        private const val USB_PERMISSION_TIMEOUT_MS = 4_000L
        private const val USB_READ_TIMEOUT_MS = 120L
        private const val USB_WRITE_TIMEOUT_MS = 1_500L
        private const val USB_READ_BUFFER_SIZE_BYTES = 4096
        private const val USB_WRITE_CHUNK_SIZE_BYTES = 256
        private const val USB_WRITE_INTER_CHUNK_DELAY_MS = 2L
        private const val USB_DATA_BITS = 8
        private const val USB_RPC_PRIME_DRAIN_WINDOW_MS = 220L
        private const val USB_RPC_BAUD_RETRY_DRAIN_WINDOW_MS = 160L
        private const val USB_RPC_PRIME_COMMAND_DELAY_MS = 80L
        private val USB_RPC_PRIME_COMMANDS = listOf(
            "start_rpc_session\r",
            "start_rpc_session\r\n",
            "\n"
        )
        private val USB_BAUD_RATE_CANDIDATES = listOf(230_400, 115_200, 460_800, 921_600)
        private val KNOWN_FLIPPER_BLE_MANUFACTURER_IDS = setOf(0x0483)
        private val KNOWN_FLIPPER_USB_VENDOR_IDS = setOf(0x0483)
        private val KNOWN_FLIPPER_USB_PRODUCT_IDS = setOf(0x5740, 0x5741)

        /**
         * Warms the service process without promoting it to the foreground.
         *
         * This is the current semantic of the legacy [startService] entry point:
         * no persistent notification is posted. The service promotes itself only
         * when a scan/connect actually starts, and stops itself when idle and
         * unbound.
         */
        fun warmStart(context: Context) {
            val intent = Intent(context, FlipperBleService::class.java)
                .setAction(ACTION_WARM_START)
            runCatching { context.startService(intent) }
        }

        /**
         * Legacy entry point kept for source compatibility.
         *
         * Semantics changed: this no longer starts a foreground service. Use
         * [startScanSession], [connectSession] or [connectUsbSession] when the
         * user explicitly starts work so Android keeps the session alive.
         */
        fun startService(context: Context) = warmStart(context)

        /**
         * Stops the service, and with it any active session. Safe to call when
         * the service is not running: it never starts the service just to stop it.
         */
        fun stopService(context: Context) {
            runCatching {
                context.stopService(Intent(context, FlipperBleService::class.java))
            }
        }

        /** Starts a foreground scan session (user tapped Scan). */
        fun startScanSession(context: Context) {
            startForegroundSession(
                context,
                Intent(context, FlipperBleService::class.java).setAction(ACTION_START_SCAN),
                Manifest.permission.BLUETOOTH_SCAN
            )
        }

        /** Starts a foreground BLE connect session for [device] (user picked a device). */
        fun connectSession(context: Context, device: FlipperDevice) {
            val intent = Intent(context, FlipperBleService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_DEVICE_ADDRESS, device.address)
                .putExtra(EXTRA_DEVICE_NAME, device.name)
            startForegroundSession(context, intent, Manifest.permission.BLUETOOTH_CONNECT)
        }

        /** Starts a foreground USB connect session. */
        fun connectUsbSession(context: Context) {
            startForegroundSession(
                context,
                Intent(context, FlipperBleService::class.java).setAction(ACTION_CONNECT_USB),
                requiredPermission = null
            )
        }

        /**
         * Starts the service through [Context.startForegroundService] so a user
         * action can outlive the UI. When the required Bluetooth permission is
         * missing the call degrades to a warm start instead: posting a foreground
         * service without its permission prerequisite would throw.
         */
        private fun startForegroundSession(
            context: Context,
            intent: Intent,
            requiredPermission: String?
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                requiredPermission != null &&
                ContextCompat.checkSelfPermission(context, requiredPermission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                warmStart(context)
                return
            }
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
            if (started.isFailure) {
                // Background foreground-service starts are restricted on Android 8+.
                // Fall back to a warm start; a bound client can still drive the
                // session, and the service promotes itself when possible.
                runCatching { context.startService(intent) }
            }
        }
    }
}

private enum class CommandTransport {
    NONE,
    BLE,
    USB
}

/**
 * Represents a discovered or connected Flipper device
 */
data class FlipperDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isConfirmedFlipper: Boolean = true,
    val firmwareVersion: String? = null,
    val hardwareModel: String? = null
)

/**
 * Connection state for the BLE service.
 *
 * Every state is published through a StateFlow, so consumers always observe the
 * latest transition. [Error] carries a machine-readable [reason] in addition to
 * the actionable message shown to the user.
 *
 * Transition summary:
 *  - Disconnected -> Scanning -> Disconnected (timeout/cancel) or Connecting;
 *  - Disconnected -> Connecting -> Connected;
 *  - Connecting/Connected -> Connecting("reconnect n/max") on a drop, then
 *    Connected on success or Error when the backoff series is exhausted;
 *  - any state -> Error for permission, radio, scan, USB or setup failures;
 *  - Error/Disconnected -> idle: the foreground notification is removed and the
 *    service stops when nothing is bound.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(
        val message: String,
        val reason: ConnectionFailure = ConnectionFailure.UNKNOWN,
    ) : ConnectionState()
}

/**
 * Machine-readable cause of a [ConnectionState.Error], so the UI can offer the
 * right recovery action instead of a generic failure.
 */
enum class ConnectionFailure {
    UNKNOWN,
    PERMISSION_DENIED,
    BLUETOOTH_DISABLED,
    SCAN_FAILED,
    DEVICE_NOT_FOUND,
    CONNECTION_TIMEOUT,
    SILENT_DROP,
    SERVICE_DISCOVERY_FAILED,
    TRANSPORT_UNAVAILABLE,
    USB_PERMISSION_DENIED,
    USB_UNAVAILABLE,
    PROTOCOL_ERROR,
}

/**
 * Lightweight connection health snapshot for the UI.
 *
 * @property lastPacketAtMs wall-clock time of the last truly received packet
 * (notification or serial read), 0 when none has arrived this session.
 * @property lastActivityAtMs wall-clock time of the last successful link
 * activity (packet, completed write, or read callback).
 * @property connectedSinceMs when the current connection was established.
 * @property reconnectCount cumulative auto-reconnect attempts for this service
 * instance; reset only when the process/service restarts.
 * @property silentForMs how long the link has been quiet; the watchdog treats a
 * sustained silence (25s) as a silent drop and starts a reconnect series.
 */
data class ConnectionHealth(
    val lastPacketAtMs: Long = 0L,
    val lastActivityAtMs: Long = 0L,
    val connectedSinceMs: Long? = null,
    val reconnectCount: Int = 0,
    val silentForMs: Long = 0L,
) {
    /** Age of the last received packet, or -1 when no packet was seen yet. */
    fun lastPacketAgeMs(nowMs: Long = System.currentTimeMillis()): Long {
        return if (lastPacketAtMs <= 0L) -1L else (nowMs - lastPacketAtMs).coerceAtLeast(0L)
    }
}
