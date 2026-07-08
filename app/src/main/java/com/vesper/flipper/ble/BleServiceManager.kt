package com.vesper.flipper.ble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
    private var _service: FlipperBleService? = null
    private var isBinding = false

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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? FlipperBleService.LocalBinder
            _service = localBinder?.getService()
            _isServiceBound.value = true
            isBinding = false
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cancelServiceCollectors()
            _service = null
            _isServiceBound.value = false
            isBinding = false
            _connectionState.value = ConnectionState.Disconnected
            _discoveredDevices.value = emptyList()
            _connectedDevice.value = null
            _cliCapabilityStatus.value = CliCapabilityStatus()
        }
    }

    init {
        bindService()
    }

    /**
     * Start and bind to the BLE service
     */
    fun bindService() {
        if (_isServiceBound.value || isBinding) return
        isBinding = true
        FlipperBleService.startService(context)
        Intent(context, FlipperBleService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Unbind from the service
     */
    fun unbindService() {
        if (_isServiceBound.value) {
            cancelServiceCollectors()
            context.unbindService(serviceConnection)
            _isServiceBound.value = false
        }
        _service = null
        isBinding = false
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

    fun connectDesktopBridge() {
        managerScope.launch {
            val service = awaitService() ?: return@launch
            service.connectDesktopBridgeIfAvailable()
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
        private const val DEFAULT_BIND_TIMEOUT_MS = 4_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
    }
}
