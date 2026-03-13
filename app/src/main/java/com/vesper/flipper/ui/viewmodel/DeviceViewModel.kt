package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.CliCapabilityStatus
import com.vesper.flipper.ble.CommandPipelineAutotuneStatus
import com.vesper.flipper.ble.ConnectionCheckLevel
import com.vesper.flipper.ble.ConnectionCheckResult
import com.vesper.flipper.ble.ConnectionDiagnosticsReport
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FirmwareCompatibilityProfile
import com.vesper.flipper.ble.FlipperDevice
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.DeviceInfo
import com.vesper.flipper.domain.model.FlipperRemoteButton
import com.vesper.flipper.domain.model.StorageInfo
import com.vesper.flipper.glasses.BridgeState
import com.vesper.flipper.glasses.GlassesIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val fileSystem: FlipperFileSystem,
    private val bleServiceManager: BleServiceManager,
    private val glassesIntegration: GlassesIntegration
) : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<FlipperDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<FlipperDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<FlipperDevice?>(null)
    val connectedDevice: StateFlow<FlipperDevice?> = _connectedDevice.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()
    private val _cliCapabilityStatus = MutableStateFlow(CliCapabilityStatus())
    val cliCapabilityStatus: StateFlow<CliCapabilityStatus> = _cliCapabilityStatus.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _connectionDiagnostics = MutableStateFlow(ConnectionDiagnosticsReport.idle())
    val connectionDiagnostics: StateFlow<ConnectionDiagnosticsReport> = _connectionDiagnostics.asStateFlow()
    private val _isRunningDiagnostics = MutableStateFlow(false)
    val isRunningDiagnostics: StateFlow<Boolean> = _isRunningDiagnostics.asStateFlow()
    private val _autotuneStatus = MutableStateFlow(CommandPipelineAutotuneStatus())
    val autotuneStatus: StateFlow<CommandPipelineAutotuneStatus> = _autotuneStatus.asStateFlow()
    val firmwareCompatibility: StateFlow<FirmwareCompatibilityProfile> = fileSystem.firmwareCompatibility
    private val _isSendingRemoteInput = MutableStateFlow(false)
    val isSendingRemoteInput: StateFlow<Boolean> = _isSendingRemoteInput.asStateFlow()
    private val _remoteInputStatus = MutableStateFlow<String?>(null)
    val remoteInputStatus: StateFlow<String?> = _remoteInputStatus.asStateFlow()
    // Glasses bridge state
    val glassesBridgeState: StateFlow<BridgeState> = glassesIntegration.bridgeState

    private val _glassesEnabled = MutableStateFlow(false)
    val glassesEnabled: StateFlow<Boolean> = _glassesEnabled.asStateFlow()

    private val _glassesBridgeUrl = MutableStateFlow("")
    val glassesBridgeUrl: StateFlow<String> = _glassesBridgeUrl.asStateFlow()

    private val refreshMutex = Mutex()
    private val remoteInputMutex = Mutex()

    init {
        bleServiceManager.bindService()
        observeServiceState()
        viewModelScope.launch {
            fileSystem.autotuneStatus.collect { status ->
                _autotuneStatus.value = status
            }
        }
        // Observe glasses settings
        viewModelScope.launch {
            settingsStore.glassesEnabled.collect { enabled ->
                _glassesEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsStore.glassesBridgeUrl.collect { url ->
                _glassesBridgeUrl.value = url ?: ""
            }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            bleServiceManager.connectionState.collect { state ->
                _connectionState.value = state
                if (state is ConnectionState.Connected) {
                    refreshDeviceInfo()
                } else {
                    clearConnectionTransientState()
                }
            }
        }

        viewModelScope.launch {
            bleServiceManager.discoveredDevices.collect { devices ->
                _discoveredDevices.value = devices
            }
        }

        viewModelScope.launch {
            bleServiceManager.connectedDevice.collect { device ->
                _connectedDevice.value = device
                device?.let {
                    viewModelScope.launch {
                        settingsStore.setLastDevice(it.address, it.name)
                    }
                }
            }
        }

        viewModelScope.launch {
            bleServiceManager.cliCapabilityStatus.collect { status ->
                _cliCapabilityStatus.value = status
            }
        }
    }

    private fun clearConnectionTransientState() {
        _isRunningDiagnostics.value = false
        _isSendingRemoteInput.value = false
        _remoteInputStatus.value = null
        _connectionDiagnostics.value = ConnectionDiagnosticsReport.idle()
    }

    fun startScan() {
        bleServiceManager.startScanning()
    }

    fun stopScan() {
        bleServiceManager.stopScanning()
    }

    fun connectToDevice(device: FlipperDevice) {
        bleServiceManager.connect(device)
    }

    fun connectUsb() {
        bleServiceManager.connectUsb()
    }

    fun disconnect() {
        bleServiceManager.disconnect()
        _deviceInfo.value = null
        _storageInfo.value = null
        _cliCapabilityStatus.value = CliCapabilityStatus()
        _connectionDiagnostics.value = ConnectionDiagnosticsReport.idle()
        _isRunningDiagnostics.value = false
        _isSendingRemoteInput.value = false
        _remoteInputStatus.value = null
    }

    fun refreshDeviceInfo() {
        if (_connectionState.value !is ConnectionState.Connected) return
        if (_isRefreshing.value) return

        viewModelScope.launch {
            refreshMutex.withLock {
                _isRefreshing.value = true
                try {
                    // Do NOT auto-probe the automation channel here.
                    // CLI/RPC probing only runs when the user explicitly requests
                    // diagnostics or when a command execution actually needs it.
                    fileSystem.getDeviceInfo().onSuccess { info ->
                        _deviceInfo.value = info
                    }
                    fileSystem.getStorageInfo().onSuccess { info ->
                        _storageInfo.value = info
                    }
                } finally {
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun runConnectionDiagnostics() {
        if (_isRunningDiagnostics.value) return

        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            try {
                val report = bleServiceManager.awaitService()?.runConnectionDiagnostics() ?: run {
                    val now = System.currentTimeMillis()
                    ConnectionDiagnosticsReport(
                        startedAtMs = now,
                        completedAtMs = now,
                        checks = listOf(
                            ConnectionCheckResult(
                                name = "Device Service",
                                level = ConnectionCheckLevel.FAIL,
                                detail = "BLE service is unavailable."
                            )
                        )
                    )
                }
                _connectionDiagnostics.value = report
                _cliCapabilityStatus.value = bleServiceManager.cliCapabilityStatus.value
            } finally {
                _isRunningDiagnostics.value = false
            }
        }
    }

    fun sendRemoteButton(button: FlipperRemoteButton, longPress: Boolean = false) {
        if (_connectionState.value !is ConnectionState.Connected) {
            _remoteInputStatus.value = "Connect a Flipper first."
            return
        }

        viewModelScope.launch {
            if (!remoteInputMutex.tryLock()) {
                _remoteInputStatus.value = "Previous button input is still in flight..."
                return@launch
            }
            try {
                sendRemoteButtonLocked(button, longPress)
            } finally {
                remoteInputMutex.unlock()
            }
        }
    }

    suspend fun sendRemoteButtonAwait(button: FlipperRemoteButton, longPress: Boolean = false): Result<String> {
        if (_connectionState.value !is ConnectionState.Connected) {
            val error = IllegalStateException("Connect a Flipper first.")
            _remoteInputStatus.value = error.message
            return Result.failure(error)
        }

        return remoteInputMutex.withLock {
            sendRemoteButtonLocked(button, longPress)
        }
    }

    private suspend fun sendRemoteButtonLocked(
        button: FlipperRemoteButton,
        longPress: Boolean
    ): Result<String> {
        _isSendingRemoteInput.value = true
        val pressLabel = if (longPress) "hold ${button.label}" else button.label
        _remoteInputStatus.value = "Sending $pressLabel..."
        return try {
            fileSystem.sendRemoteButton(button, longPress).also { result ->
                _remoteInputStatus.value = result.fold(
                    onSuccess = { details ->
                        details.ifBlank { "Sent ${button.label}." }
                    },
                    onFailure = {
                        "Remote input failed: ${it.message ?: "Unknown error"}"
                    }
                )
            }
        } finally {
            _isSendingRemoteInput.value = false
        }
    }

    // ==================== Glasses Bridge ====================

    fun connectGlassesBridge() {
        val url = _glassesBridgeUrl.value
        if (url.isBlank()) return
        glassesIntegration.connect(url)
    }

    fun disconnectGlassesBridge() {
        glassesIntegration.disconnect()
    }

}
