package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.device.DeviceOverview
import com.vesper.flipper.ui.screen.device.DiagnosticsSheet
import com.vesper.flipper.ui.screen.device.ScanSheet
import com.vesper.flipper.ui.screen.device.rememberBlePermissionState
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.viewmodel.DeviceViewModel

/**
 * Thin shell around the device feature. All layout lives in
 * ui/screen/device; this file only owns state wiring and the two sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel = hiltViewModel(),
    onNavigateToFiles: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null
) {
    val colors = Vesper.colors
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val cliCapabilityStatus by viewModel.cliCapabilityStatus.collectAsState()
    val connectionDiagnostics by viewModel.connectionDiagnostics.collectAsState()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsState()
    val autotuneStatus by viewModel.autotuneStatus.collectAsState()
    val firmwareCompatibility by viewModel.firmwareCompatibility.collectAsState()
    val isSendingRemoteInput by viewModel.isSendingRemoteInput.collectAsState()
    val remoteInputStatus by viewModel.remoteInputStatus.collectAsState()
    val glassesEnabled by viewModel.glassesEnabled.collectAsState()
    val glassesBridgeState by viewModel.glassesBridgeState.collectAsState()
    val glassesBridgeUrl by viewModel.glassesBridgeUrl.collectAsState()

    val permissionState = rememberBlePermissionState()
    var showScanSheet by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticsSheet by rememberSaveable { mutableStateOf(false) }
    var dismissedError by rememberSaveable { mutableStateOf<String?>(null) }

    val connectionError = connectionState as? ConnectionState.Error

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            showScanSheet = false
        }
        if (connectionError == null) {
            dismissedError = null
        }
    }

    val currentConnection = connectionState
    val subtitle = when (currentConnection) {
        is ConnectionState.Connected -> connectedDevice?.name ?: "Connected"
        is ConnectionState.Connecting -> "Connecting to " + currentConnection.deviceName
        is ConnectionState.Scanning -> "Scanning for nearby Flippers"
        is ConnectionState.Error -> "Connection needs attention"
        ConnectionState.Disconnected -> if (discoveredDevices.isEmpty()) {
            "Not connected"
        } else {
            discoveredDevices.size.toString() + " nearby"
        }
    }

    VesperScaffold(
        title = "Device",
        subtitle = subtitle,
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = { onNavigateBack?.invoke() }) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { showDiagnosticsSheet = true }) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Diagnostics"
                )
            }
            if (connectionState is ConnectionState.Connected) {
                IconButton(
                    onClick = { viewModel.refreshDeviceInfo() },
                    enabled = !isRefreshing
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.gold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh device info"
                        )
                    }
                }
            }
        }
    ) { padding ->
        DeviceOverview(
            viewModel = viewModel,
            connectionState = connectionState,
            connectedDevice = connectedDevice,
            deviceInfo = deviceInfo,
            storageInfo = storageInfo,
            isRefreshing = isRefreshing,
            isSendingRemoteInput = isSendingRemoteInput,
            remoteInputStatus = remoteInputStatus,
            permissionsGranted = permissionState.granted,
            glassesEnabled = glassesEnabled,
            glassesState = glassesBridgeState,
            glassesUrl = glassesBridgeUrl,
            showConnectionError = connectionError != null && dismissedError != connectionError.message,
            onDismissConnectionError = { dismissedError = connectionError?.message },
            onRetryConnection = {
                dismissedError = null
                viewModel.startScan()
                showScanSheet = true
            },
            onScan = { showScanSheet = true },
            onStopScan = { viewModel.stopScan() },
            onConnectUsb = { viewModel.connectUsb() },
            onDisconnect = { viewModel.disconnect() },
            onRequestPermissions = permissionState.request,
            onRefreshInfo = { viewModel.refreshDeviceInfo() },
            onSendRemoteButton = { button, longPress -> viewModel.sendRemoteButton(button, longPress) },
            onOpenDiagnostics = { showDiagnosticsSheet = true },
            onNavigateToFiles = onNavigateToFiles,
            onNavigateToSettings = onNavigateToSettings,
            onConnectGlasses = { viewModel.connectGlassesBridge() },
            onDisconnectGlasses = { viewModel.disconnectGlassesBridge() },
            modifier = Modifier.padding(padding)
        )
    }

    if (showScanSheet) {
        ScanSheet(
            connectionState = connectionState,
            devices = discoveredDevices,
            permissionsGranted = permissionState.granted,
            onDismiss = { showScanSheet = false },
            onStartScan = { viewModel.startScan() },
            onStopScan = { viewModel.stopScan() },
            onConnect = { device ->
                viewModel.connectToDevice(device)
                showScanSheet = false
            },
            onConnectUsb = {
                viewModel.connectUsb()
                showScanSheet = false
            },
            onRequestPermissions = permissionState.request
        )
    }

    if (showDiagnosticsSheet) {
        DiagnosticsSheet(
            connectionState = connectionState,
            cliStatus = cliCapabilityStatus,
            diagnostics = connectionDiagnostics,
            autotuneStatus = autotuneStatus,
            firmwareCompatibility = firmwareCompatibility,
            isRunningDiagnostics = isRunningDiagnostics,
            onRunDiagnostics = { viewModel.runConnectionDiagnostics() },
            onDismiss = { showDiagnosticsSheet = false }
        )
    }
}
