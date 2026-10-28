package com.vesper.flipper.ble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Service Manager
 *
 * Provides proper Hilt-injectable access to FlipperBleService.
 * Handles service binding lifecycle and provides the service instance
 * to ViewModels and other components that need BLE access.
 */
@Singleton
class BleServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var _service: FlipperBleService? = null
    // A registration exists before onServiceConnected, and survives a temporary
    // onServiceDisconnected. Keep it separate from the usable service instance.
    private var serviceConnection: ServiceConnection? = null

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<FlipperDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<FlipperDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<FlipperDevice?>(null)
    val connectedDevice: StateFlow<FlipperDevice?> = _connectedDevice.asStateFlow()
    private val _cliCapabilityStatus = MutableStateFlow(CliCapabilityStatus())
    val cliCapabilityStatus: StateFlow<CliCapabilityStatus> = _cliCapabilityStatus.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionStateJob: Job? = null
    private var discoveredDevicesJob: Job? = null
    private var connectedDeviceJob: Job? = null
    private var cliCapabilityJob: Job? = null

    private fun newServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(this@BleServiceManager) {
                if (serviceConnection !== this) return
                val service = (binder as? FlipperBleService.LocalBinder)?.getService()
                if (service == null) {
                    releaseBinding(this)
                    return
                }
                _service = service
                _isServiceBound.value = true
                observeServiceState()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this@BleServiceManager) {
                if (serviceConnection !== this) return
                // Android retains this registration and reconnects it.
                clearServiceState()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(this@BleServiceManager) {
                if (serviceConnection === this) releaseBinding(this)
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(this@BleServiceManager) {
                if (serviceConnection === this) releaseBinding(this)
            }
        }
    }

    init {
        bindService()
    }

    /** Bind for USB permission requests even when foreground start is ineligible. */
    @Synchronized
    fun bindService() {
        // Retry promotion after a permission grant even if we are already bound.
        // A declined foreground start must not discard an existing transport.
        FlipperBleService.startService(context)
        if (serviceConnection != null) return
        val connection = newServiceConnection()
        serviceConnection = connection
        try {
            val intent = Intent(context, FlipperBleService::class.java)
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                releaseBinding(connection)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE service binding denied", e)
            releaseBinding(connection)
        }
    }

    @Synchronized
    fun unbindService() {
        serviceConnection?.let { releaseBinding(it) }
    }

    private fun releaseBinding(connection: ServiceConnection) {
        // Invalidate before unbinding so late callbacks cannot resurrect it or
        // overwrite a subsequent registration. Also release pending/failed binds.
        serviceConnection = null
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // bindService may have failed before Android registered the connection.
        }
        clearServiceState()
    }

    private fun clearServiceState() {
        cancelServiceCollectors()
        _service = null
        _isServiceBound.value = false
        _connectionState.value = ConnectionState.Disconnected
        _discoveredDevices.value = emptyList()
        _connectedDevice.value = null
        _cliCapabilityStatus.value = CliCapabilityStatus()
    }

    /**
     * Get the service instance (may be null if not bound)
     */
    fun getService(): FlipperBleService? = _service

    /**
     * Start scanning for Flipper devices
     */
    fun startScanning() {
        managerScope.launch {
            val service = awaitService() ?: return@launch
            service.startScan()
        }
    }

    /**
     * Stop scanning
     */
    fun stopScanning() {
        _service?.stopScan()
    }

    /**
     * Connect to a specific device
     */
    fun connect(device: FlipperDevice) {
        managerScope.launch {
            val service = awaitService() ?: return@launch
            service.connect(device)
        }
    }

    fun connectUsb() {
        managerScope.launch {
            val service = awaitService() ?: return@launch
            service.connectUsbIfAvailable()
        }
    }

    /**
     * Disconnect from current device
     */
    fun disconnect() {
        _service?.disconnect()
    }

    /**
     * Send raw data to the Flipper
     */
    suspend fun sendRawData(data: ByteArray): Result<ByteArray> {
        val service = _service ?: return Result.failure(Exception("Service not bound"))
        return service.sendRawData(data)
    }

    /**
     * Get current connection state from service
     */
    fun getConnectionStateFlow(): StateFlow<ConnectionState> {
        return _service?.connectionState ?: _connectionState
    }

    /**
     * Get discovered devices from service
     */
    fun getDiscoveredDevicesFlow(): StateFlow<List<FlipperDevice>> {
        return _service?.discoveredDevices ?: _discoveredDevices
    }

    /**
     * Get connected device from service
     */
    fun getConnectedDeviceFlow(): StateFlow<FlipperDevice?> {
        return _service?.connectedDevice ?: _connectedDevice
    }

    private fun observeServiceState() {
        val service = _service ?: return
        cancelServiceCollectors()

        connectionStateJob = managerScope.launch {
            service.connectionState.collect { _connectionState.value = it }
        }
        discoveredDevicesJob = managerScope.launch {
            service.discoveredDevices.collect { _discoveredDevices.value = it }
        }
        connectedDeviceJob = managerScope.launch {
            service.connectedDevice.collect { _connectedDevice.value = it }
        }
        cliCapabilityJob = managerScope.launch {
            service.cliCapabilityStatus.collect { _cliCapabilityStatus.value = it }
        }
    }

    private fun cancelServiceCollectors() {
        connectionStateJob?.cancel()
        discoveredDevicesJob?.cancel()
        connectedDeviceJob?.cancel()
        cliCapabilityJob?.cancel()
        connectionStateJob = null
        discoveredDevicesJob = null
        connectedDeviceJob = null
        cliCapabilityJob = null
    }

    suspend fun awaitService(timeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS): FlipperBleService? {
        bindService()
        _service?.let { return it }

        return withTimeoutOrNull(timeoutMs) {
            isServiceBound.filter { it }.first()
            _service
        }
    }

    suspend fun awaitConnectedService(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): FlipperBleService? {
        val service = awaitService(timeoutMs) ?: return null
        if (service.connectionState.value is ConnectionState.Connected) {
            return service
        }

        return withTimeoutOrNull(timeoutMs) {
            service.connectionState.filter { it is ConnectionState.Connected }.first()
            _service
        }
    }

    suspend fun probeCliCapability(force: Boolean = false): CliCapabilityStatus {
        val service = awaitConnectedService() ?: return CliCapabilityStatus(
            level = CliCapabilityLevel.UNAVAILABLE,
            checkedAtMs = System.currentTimeMillis(),
            details = "Flipper is not connected"
        )
        return service.probeCliCapability(force)
    }

    companion object {
        private const val TAG = "BleServiceManager"
        private const val DEFAULT_BIND_TIMEOUT_MS = 4_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
    }
}
