package com.vesper.flipper.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
 * Foreground service managing BLE connection to Flipper Zero.
 * Handles scanning, connection, and GATT operations.
 */
@AndroidEntryPoint
class FlipperBleService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var usbManager: UsbManager? = null
    private var usbSerialDriver: UsbSerialDriver? = null
    private var usbSerialPort: UsbSerialPort? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var connectedUsbDevice: UsbDevice? = null
    private var usbReadJob: Job? = null
    private var bleKeepaliveJob: Job? = null
    private var usbReceiverRegistered = false
    private var broadScanStarted = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    private var serialRxCharacteristic: BluetoothGattCharacteristic? = null
    private var serialServiceUuid: UUID? = null
    private var serialOverflowCharacteristic: BluetoothGattCharacteristic? = null
    private var serialResetCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var remainingSerialBufferBytes: Int? = null
    private var pendingConnectionName: String? = null
    private var lastRequestedDeviceAddress: String? = null
    private var lastRequestedDeviceName: String? = null
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
                    if (!isLikelyFlipperUsbDevice(attachedDevice)) return
                    serviceScope.launch {
                        if (!isCommandTransportConnected()) {
                            connectUsbInternal(preferredDevice = attachedDevice)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = intent.extractUsbDeviceExtra() ?: return
                    if (detachedDevice.deviceId == connectedUsbDevice?.deviceId) {
                        disconnectUsbTransport(
                            setState = true,
                            errorMessage = "USB disconnected"
                        )
                    }
                }
            }
        }
    }

    @Inject
    lateinit var flipperProtocol: FlipperProtocol

    inner class LocalBinder : Binder() {
        fun getService(): FlipperBleService = this@FlipperBleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        initializeBluetooth()
        initializeUsb()
        loadPersistedConfirmedFlippers()
        registerUsbReceivers()
        flipperProtocol.setBleService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startForegroundService()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbReceivers()
        disconnect()
        serviceScope.cancel()
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
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
        ContextCompat.registerReceiver(
            this,
            usbBroadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbReceiverRegistered = true
    }

    private fun unregisterUsbReceivers() {
        if (!usbReceiverRegistered) return
        runCatching { unregisterReceiver(usbBroadcastReceiver) }
        usbReceiverRegistered = false
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VesperApplication.CHANNEL_BLE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getConnectionText())
            .setSmallIcon(R.drawable.ic_flipper_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getConnectionText(): String {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connected -> getString(R.string.ble_connected, state.deviceName)
            is ConnectionState.Connecting -> getString(R.string.ble_connecting, state.deviceName)
            is ConnectionState.Disconnected -> getString(R.string.ble_disconnected)
            is ConnectionState.Scanning -> getString(R.string.ble_scanning)
            is ConnectionState.Error -> getString(R.string.ble_error, state.message)
        }
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // Scanning - Now finds ALL potential Flippers, even renamed ones

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        _connectionState.value = ConnectionState.Scanning
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bleScanner?.startScan(flipperFilters, scanSettings, scanCallback)
        } catch (e: IllegalStateException) {
            _connectionState.value = ConnectionState.Error("Failed to start BLE scan: ${e.message}")
            return
        }

        // Fallback broad scan only if filtered scan found nothing after a short window.
        serviceScope.launch {
            delay(BROAD_SCAN_FALLBACK_DELAY_MS)
            if (_connectionState.value is ConnectionState.Scanning &&
                _discoveredDevices.value.isEmpty() &&
                !broadScanStarted
            ) {
                startBroadScan()
            }
        }

        // Auto-stop scan after timeout
        serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBroadScan() {
        if (!hasBluetoothPermissions()) return
        if (broadScanStarted) return

        // Broad scan without filters - catches renamed Flippers
        val broadSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            bleScanner?.startScan(null, broadSettings, broadScanCallback)
            broadScanStarted = true
        } catch (_: IllegalStateException) {
            // Keep silent; filtered scan remains primary path.
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasBluetoothPermissions()) {
            bleScanner?.stopScan(scanCallback)
            bleScanner?.stopScan(broadScanCallback)
        }
        broadScanStarted = false
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDiscoveredDevice(result, isConfirmedFlipper = true)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                addDiscoveredDevice(result, isConfirmedFlipper = true)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Fallback to broad scan when hardware rejects filtered mode.
            if (!broadScanStarted) {
                startBroadScan()
            }
            if (!broadScanStarted) {
                _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
            }
        }
    }

    // Broad scan callback - validates devices by checking for Flipper characteristics
    private val broadScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hasKnownService = hasKnownFlipperService(result)
            val normalizedAddress = normalizeBleAddress(result.device.address)
            val wasConfirmedEarlier = normalizedAddress?.let { confirmedFlipperAddresses.contains(it) } == true
            val hasKnownAddressPrefix = hasKnownFlipperAddressPrefix(result.device.address)
            val hasLikelyFlipperName = isLikelyFlipperName(resolveScanDeviceName(result))
            val hasLikelyManufacturerData = hasLikelyFlipperManufacturerData(result)

            if (hasKnownService ||
                wasConfirmedEarlier ||
                hasKnownAddressPrefix ||
                hasLikelyFlipperName ||
                hasLikelyManufacturerData
            ) {
                addDiscoveredDevice(
                    result = result,
                    isConfirmedFlipper = hasKnownService ||
                            wasConfirmedEarlier ||
                            hasKnownAddressPrefix ||
                            hasLikelyManufacturerData
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val hasKnownService = hasKnownFlipperService(result)
                val normalizedAddress = normalizeBleAddress(result.device.address)
                val wasConfirmedEarlier = normalizedAddress?.let { confirmedFlipperAddresses.contains(it) } == true
                val hasKnownAddressPrefix = hasKnownFlipperAddressPrefix(result.device.address)
                val hasLikelyFlipperName = isLikelyFlipperName(resolveScanDeviceName(result))
                val hasLikelyManufacturerData = hasLikelyFlipperManufacturerData(result)
                if (hasKnownService ||
                    wasConfirmedEarlier ||
                    hasKnownAddressPrefix ||
                    hasLikelyFlipperName ||
                    hasLikelyManufacturerData
                ) {
                    addDiscoveredDevice(
                        result = result,
                        isConfirmedFlipper = hasKnownService ||
                                wasConfirmedEarlier ||
                                hasKnownAddressPrefix ||
                                hasLikelyManufacturerData
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // Silent fail for broad scan - filtered scan is primary
        }
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

        val currentDevices = _discoveredDevices.value.toMutableList()
        val existingIndex = currentDevices.indexOfFirst { it.address == device.address }
        if (existingIndex >= 0) {
            // Update existing device, prefer confirmed status
            val existing = currentDevices[existingIndex]
            val resolvedName = if (isPlaceholderName(name) && !isPlaceholderName(existing.name)) {
                existing.name
            } else {
                name
            }
            currentDevices[existingIndex] = flipperDevice.copy(
                name = resolvedName,
                isConfirmedFlipper = existing.isConfirmedFlipper || isConfirmedFlipper
            )
        } else {
            currentDevices.add(flipperDevice)
        }
        // Sort: confirmed Flippers first, then by signal strength
        _discoveredDevices.value = currentDevices.sortedWith(
            compareByDescending<FlipperDevice> { it.isConfirmedFlipper }
                .thenByDescending { it.rssi }
        ).take(MAX_DISCOVERED_DEVICES)
    }

    // Connection

    @SuppressLint("MissingPermission")
    fun connect(device: FlipperDevice) {
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }

        stopScan()
        disconnect()
        val resolvedDevice = discoveredBluetoothDevices[device.address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(device.address) }.getOrNull()
        val targetAddress = resolvedDevice?.address ?: device.address
        lastRequestedDeviceAddress = targetAddress
        lastRequestedDeviceName = device.name
        lastRequestedBluetoothDevice = resolvedDevice
        reconnectAttemptCount = 0
        pendingConnectionName = device.name.takeIf { it.isNotBlank() && !isPlaceholderName(it) }

        _connectionState.value = ConnectionState.Connecting(device.name)

        Log.d(
            TAG,
            "connect requested name=${device.name} target=$targetAddress resolved=${resolvedDevice != null}"
        )
        bluetoothGatt = resolvedDevice?.let { connectGattCompat(it, autoConnect = false) }
        if (bluetoothGatt == null) {
            pendingConnectionName = null
            _connectionState.value = ConnectionState.Error("Failed to initiate BLE connection to ${device.name}")
            updateNotification()
        }
    }

    fun connectUsbIfAvailable() {
        serviceScope.launch {
            stopScan()
            disconnect()
            connectUsbInternal()
        }
    }

    private suspend fun connectUsbInternal(preferredDevice: UsbDevice? = null): Boolean {
        val manager = usbManager ?: run {
            _connectionState.value = ConnectionState.Error("USB host manager unavailable")
            updateNotification()
            return false
        }
        _connectionState.value = ConnectionState.Connecting("USB")
        updateNotification()

        val attachedDevices = manager.deviceList.values.toList()
        if (attachedDevices.isEmpty()) {
            _connectionState.value = ConnectionState.Error(
                "No USB device detected. Use OTG, unlock Flipper, and reconnect cable."
            )
            updateNotification()
            return false
        }

        val driver = resolveUsbSerialDriver(manager, preferredDevice)
        if (driver == null) {
            _connectionState.value = ConnectionState.Error(
                "No compatible Flipper USB serial endpoint found. " +
                        "Attached: ${describeUsbDeviceList(attachedDevices)}"
            )
            updateNotification()
            return false
        }
        val device = driver.device

        if (!ensureUsbPermission(manager, device)) {
            _connectionState.value = ConnectionState.Error("USB permission denied for ${resolveUsbDeviceName(device)}")
            updateNotification()
            return false
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            _connectionState.value = ConnectionState.Error("Failed to open USB device")
            updateNotification()
            return false
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            runCatching { connection.close() }
            _connectionState.value = ConnectionState.Error("USB serial port unavailable on device")
            updateNotification()
            return false
        }

        return try {
            port.open(connection)
            currentUsbBaudRate = configureUsbPort(port)
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
            primeUsbRpcSession(port)

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
            _connectionState.value = ConnectionState.Connected("$deviceName (USB)")
            updateNotification()

            serviceScope.launch {
                validateUsbBaudWithRpcProbe(port)
                flipperProtocol.probeCliAvailability(force = true)
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
            _connectionState.value = ConnectionState.Error(
                "USB connect failed: ${t.message ?: t::class.java.simpleName}"
            )
            updateNotification()
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
        pendingUsbPermissionRequest?.cancel()
        pendingUsbPermissionRequest = deferred
        manager.requestPermission(device, pendingIntent)
        return withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) {
            deferred.await()
        } ?: false
    }

    private fun usbPermissionPendingIntentFlags(): Int {
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
                        completePendingOperation(payload)
                        flipperProtocol.processIncomingData(payload)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.w(TAG, "USB read failed: ${t.message}")
                    disconnectUsbTransport(
                        setState = true,
                        errorMessage = "USB transport error: ${t.message ?: "read failed"}"
                    )
                    break
                }
            }
        }
    }

    private fun markBleActivity() {
        lastBleActivityAtMs = System.currentTimeMillis()
    }

    private fun startBleKeepalive() {
        stopBleKeepalive()
        bleKeepaliveJob = serviceScope.launch {
            while (isActive) {
                delay(BLE_KEEPALIVE_INTERVAL_MS)
                if (activeTransport != CommandTransport.BLE) continue
                if (_connectionState.value !is ConnectionState.Connected) continue
                if (!notificationsReady || !gattLinkConnected) continue

                val idleForMs = System.currentTimeMillis() - lastBleActivityAtMs
                if (idleForMs < BLE_KEEPALIVE_IDLE_THRESHOLD_MS) continue
                sendBleKeepaliveProbe()
            }
        }
    }

    private fun stopBleKeepalive() {
        bleKeepaliveJob?.cancel()
        bleKeepaliveJob = null
    }

    @SuppressLint("MissingPermission")
    private fun sendBleKeepaliveProbe() {
        if (!hasBluetoothPermissions()) return
        val gatt = bluetoothGatt ?: return
        if (!isCurrentGatt(gatt)) return

        val sent = when {
            serialOverflowCharacteristic != null -> {
                gatt.readCharacteristic(serialOverflowCharacteristic)
            }
            else -> {
                gatt.readRemoteRssi()
            }
        }
        if (sent) {
            markBleActivity()
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

    @SuppressLint("MissingPermission")
    fun disconnect() {
        failPendingWriteAck()
        flipperProtocol.onConnectionReset()
        stopBleKeepalive()
        disconnectBleTransport()
        disconnectUsbTransport(setState = false)
        _connectedDevice.value = null
        activeTransport = CommandTransport.NONE
        if (_connectionState.value !is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectBleTransport() {
        bluetoothGatt?.let { gatt ->
            if (hasBluetoothPermissions()) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }
        bluetoothGatt = null
        serialCharacteristic = null
        serialRxCharacteristic = null
        serialServiceUuid = null
        serialOverflowCharacteristic = null
        serialResetCharacteristic = null
        remainingSerialBufferBytes = null
        pendingConnectionName = null
        lastRequestedDeviceAddress = null
        lastRequestedDeviceName = null
        lastRequestedBluetoothDevice = null
        reconnectAttemptCount = 0
        gattLinkConnected = false
        pendingConnectedDevice = null
        notificationsReady = false
        negotiatedMtu = DEFAULT_ATT_MTU
        lastBleActivityAtMs = 0L
        lastBlePriorityRequestAtMs = 0L
        if (activeTransport == CommandTransport.BLE) {
            activeTransport = CommandTransport.NONE
        }
    }

    private fun disconnectUsbTransport(
        setState: Boolean,
        errorMessage: String? = null
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
        pendingUsbPermissionRequest?.cancel()
        pendingUsbPermissionRequest = null
        if (activeTransport == CommandTransport.USB) {
            activeTransport = CommandTransport.NONE
        }
        if (setState) {
            _connectedDevice.value = null
            _connectionState.value = if (!errorMessage.isNullOrBlank()) {
                ConnectionState.Error(errorMessage)
            } else {
                ConnectionState.Disconnected
            }
            updateNotification()
            flipperProtocol.onConnectionReset()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                    reconnectAttemptCount = 0
                    gattLinkConnected = true
                    markBleActivity()
                    lastRequestedBluetoothDevice = gatt.device
                    lastRequestedDeviceAddress = gatt.device.address
                    if (hasBluetoothPermissions()) {
                        runCatching {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        }
                        lastBlePriorityRequestAtMs = System.currentTimeMillis()
                        gatt.requestMtu(REQUESTED_ATT_MTU)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    failPendingWriteAck()
                    flipperProtocol.onConnectionReset()
                    gattLinkConnected = false
                    notificationsReady = false
                    pendingConnectedDevice = null
                    negotiatedMtu = DEFAULT_ATT_MTU
                    serialServiceUuid = null
                    serialOverflowCharacteristic = null
                    serialResetCharacteristic = null
                    stopBleKeepalive()
                    remainingSerialBufferBytes = null
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    runCatching { gatt.close() }
                    _connectedDevice.value = null
                    if (activeTransport == CommandTransport.BLE) {
                        activeTransport = CommandTransport.NONE
                    }
                    val autoReconnectScheduled = scheduleAutoReconnectIfEligible(status)
                    _connectionState.value = if (autoReconnectScheduled) {
                        val deviceName = lastRequestedDeviceName
                            ?: pendingConnectionName
                            ?: DEFAULT_FLIPPER_NAME
                        ConnectionState.Connecting("$deviceName (reconnect ${reconnectAttemptCount}/${MAX_TIMEOUT_RECONNECT_ATTEMPTS})")
                    } else if (status != BluetoothGatt.GATT_SUCCESS) {
                        val statusText = describeGattStatus(status)
                        val reasonHint = when (status) {
                            GATT_STATUS_CONN_TERMINATE_PEER_USER ->
                                "Flipper closed the session. Keep the Flipper Bluetooth app open and close other apps connected to it."
                            GATT_STATUS_GENERIC_ERROR ->
                                "Android BLE stack error. Toggle Bluetooth off/on and reconnect."
                            else ->
                                "Keep Flipper close, disable competing Flipper apps, and retry connect."
                        }
                        ConnectionState.Error("BLE disconnected ($statusText). $reasonHint")
                    } else {
                        ConnectionState.Disconnected
                    }
                    updateNotification()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed status=${describeGattStatus(status)}")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                return
            }

            val serialService = resolveSerialService(gatt)
            if (serialService == null) {
                val discoveredServices = gatt.services
                    ?.joinToString(limit = 8) { it.uuid.toString() }
                    .orEmpty()
                _connectionState.value = ConnectionState.Error(
                    "Flipper serial service not found. Exit Bluetooth Remote/Control mode on Flipper and retry. " +
                            "Services: $discoveredServices"
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
                _connectionState.value = ConnectionState.Error("Flipper serial characteristics not usable")
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

            // Enable notifications on RX characteristic and wait for descriptor write completion.
            if (hasBluetoothPermissions()) {
                val rx = serialRxCharacteristic ?: run {
                    _connectionState.value = ConnectionState.Error("Flipper RX characteristic unavailable")
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
                        pendingConnectedDevice = null
                        _connectionState.value = ConnectionState.Error("Failed to enable Flipper notifications")
                    }
                    return
                }
            }

            // Descriptor not available; continue with best-effort connection readiness.
            notificationsReady = true
            finalizeConnectedState(device)
        }

        override fun onDescriptorWrite(
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
                _connectionState.value = ConnectionState.Error("Failed to enable Flipper notifications")
                updateNotification()
                return
            }

            notificationsReady = true
            val device = pendingConnectedDevice
            if (device != null) {
                finalizeConnectedState(device)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu.coerceIn(DEFAULT_ATT_MTU, REQUESTED_ATT_MTU)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentGatt(gatt)) return
            markBleActivity()
            if (characteristic.uuid == serialOverflowCharacteristic?.uuid) {
                updateOverflowCapacityFromBytes(value)
                return
            }
            if (shouldHandleIncomingCharacteristic(characteristic)) {
                completePendingOperation(value)
                flipperProtocol.processIncomingData(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!isCurrentGatt(gatt)) return
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

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS &&
                characteristic.uuid == serialOverflowCharacteristic?.uuid
            ) {
                markBleActivity()
                updateOverflowCapacityFromBytes(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS &&
                characteristic.uuid == serialOverflowCharacteristic?.uuid
            ) {
                characteristic.value?.let { updateOverflowCapacityFromBytes(it) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
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
            // Descriptor callbacks can be delayed on some stacks; allow a short settle window.
            repeat(NOTIFICATION_READY_WAIT_ATTEMPTS) {
                if (notificationsReady) return@repeat
                delay(NOTIFICATION_READY_POLL_MS)
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
                                    delay(writeRetryDelayMs)
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

    @SuppressLint("MissingPermission")
    private fun scheduleAutoReconnectIfEligible(status: Int): Boolean {
        val reconnectableStatus = status == GATT_STATUS_CONN_TIMEOUT ||
                status == GATT_STATUS_CONN_TERMINATE_PEER_USER ||
                status == GATT_STATUS_GENERIC_ERROR
        if (!reconnectableStatus) return false
        if (reconnectAttemptCount >= MAX_TIMEOUT_RECONNECT_ATTEMPTS) return false
        if (!hasBluetoothPermissions()) return false
        if (bluetoothAdapter?.isEnabled != true) return false

        val address = lastRequestedDeviceAddress ?: return false
        val bluetoothDevice = lastRequestedBluetoothDevice
            ?: discoveredBluetoothDevices[address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return false

        reconnectAttemptCount += 1
        val retryDelayMs =
            TIMEOUT_RECONNECT_DELAY_MS + ((reconnectAttemptCount - 1) * TIMEOUT_RECONNECT_BACKOFF_STEP_MS)
        serviceScope.launch {
            Log.w(
                TAG,
                "Scheduling reconnect attempt=$reconnectAttemptCount delayMs=$retryDelayMs " +
                        "status=${describeGattStatus(status)}"
            )
            delay(retryDelayMs)
            if (_connectionState.value !is ConnectionState.Connecting) {
                return@launch
            }
            bluetoothGatt?.let { existing ->
                runCatching { existing.disconnect() }
                runCatching { existing.close() }
                bluetoothGatt = null
            }
            val useAutoConnect = reconnectAttemptCount > 1
            Log.w(
                TAG,
                "Starting reconnect attempt=$reconnectAttemptCount autoConnect=$useAutoConnect " +
                        "address=${bluetoothDevice.address}"
            )
            bluetoothGatt = connectGattCompat(
                device = bluetoothDevice,
                autoConnect = useAutoConnect
            )
            if (bluetoothGatt == null) {
                _connectionState.value = ConnectionState.Error(
                    "Auto-reconnect could not start (${describeGattStatus(status)}). Retry manually."
                )
                updateNotification()
            }
        }
        return true
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
        return device.connectGatt(
            this,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
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
        markBleActivity()
        normalizeBleAddress(device.address)?.let { normalized ->
            if (confirmedFlipperAddresses.add(normalized)) {
                persistConfirmedFlipperAddress(normalized)
            }
        }
        _connectedDevice.value = device
        _connectionState.value = ConnectionState.Connected(device.name)
        updateNotification()
        startBleKeepalive()

        // Enable overflow control but do NOT auto-probe the automation channel on connect.
        // The CLI/RPC probe is expensive and should only run when the user explicitly
        // requests diagnostics or when a command actually needs the automation channel.
        serviceScope.launch {
            enableOverflowControl()
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

    suspend fun awaitCommandTransportReady(
        timeoutMs: Long = COMMAND_TRANSPORT_READY_TIMEOUT_MS
    ): Boolean {
        if (isCommandTransportConnected()) return true
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

        return writeMutex.withLock {
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
        }
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
        const val ACTION_START_FOREGROUND = "com.vesper.flipper.START_FOREGROUND"
        const val ACTION_STOP = "com.vesper.flipper.STOP"
        const val ACTION_USB_PERMISSION = "com.vesper.flipper.USB_PERMISSION"
        const val NOTIFICATION_ID = 1

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
        private const val WRITE_ACK_TIMEOUT_MS = 1_500L
        private const val WRITE_ACK_TIMEOUT_CONTROL_MS = 450L
        private const val WRITE_NO_RESPONSE_CHUNK_DELAY_MS = 8L
        private const val WRITE_NO_RESPONSE_CHUNK_DELAY_CONTROL_MS = 3L
        private const val MAX_WRITE_START_ATTEMPTS = 3
        private const val MAX_WRITE_START_ATTEMPTS_CONTROL = 2
        private const val WRITE_START_RETRY_DELAY_MS = 40L
        private const val WRITE_START_RETRY_DELAY_CONTROL_MS = 18L
        private const val WRITE_MUTEX_STANDARD_WAIT_TIMEOUT_MS = 6_000L
        private const val WRITE_MUTEX_CONTROL_WAIT_TIMEOUT_MS = 350L
        private const val NOTIFICATION_READY_WAIT_ATTEMPTS = 6
        private const val NOTIFICATION_READY_POLL_MS = 50L
        private const val COMMAND_TRANSPORT_READY_TIMEOUT_MS = 2_500L
        private const val COMMAND_TRANSPORT_READY_POLL_MS = 50L
        private const val BLE_KEEPALIVE_INTERVAL_MS = 3_000L
        private const val BLE_KEEPALIVE_IDLE_THRESHOLD_MS = 3_500L
        private const val BLE_PRIORITY_REFRESH_INTERVAL_MS = 45_000L
        private const val OVERFLOW_WAIT_TIMEOUT_MS = 1_200L
        private const val OVERFLOW_WAIT_POLL_MS = 15L
        private const val OVERFLOW_VALUE_SIZE_BYTES = 4
        private const val DEFAULT_ATT_MTU = 23
        private const val REQUESTED_ATT_MTU = 517
        private const val ATT_WRITE_OVERHEAD_BYTES = 3
        private const val MIN_BLE_CHUNK_BYTES = 20
        // Flipper serial profile max characteristic value length (firmware serial_service.h).
        private const val MAX_SERIAL_CHAR_VALUE_BYTES = 243
        private const val TIMEOUT_RECONNECT_DELAY_MS = 1_000L
        private const val TIMEOUT_RECONNECT_BACKOFF_STEP_MS = 500L
        private const val MAX_TIMEOUT_RECONNECT_ATTEMPTS = 3
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

        fun startService(context: Context) {
            val intent = Intent(context, FlipperBleService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FlipperBleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
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
 * Connection state for the BLE service
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
