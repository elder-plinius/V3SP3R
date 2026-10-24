package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperDevice
import com.vesper.flipper.domain.model.DeviceInfo
import com.vesper.flipper.domain.model.FlipperRemoteButton
import com.vesper.flipper.domain.model.StorageInfo
import com.vesper.flipper.glasses.BridgeState
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.DeviceViewModel

/**
 * The device tab, top to bottom: connection, the numbers, the remote,
 * optional glasses, automation, then the exits to Files and Settings.
 */
@Composable
fun DeviceOverview(
    viewModel: DeviceViewModel,
    connectionState: ConnectionState,
    connectedDevice: FlipperDevice?,
    deviceInfo: DeviceInfo?,
    storageInfo: StorageInfo?,
    isRefreshing: Boolean,
    isSendingRemoteInput: Boolean,
    remoteInputStatus: String?,
    permissionsGranted: Boolean,
    glassesEnabled: Boolean,
    glassesState: BridgeState,
    glassesUrl: String,
    showConnectionError: Boolean,
    onDismissConnectionError: () -> Unit,
    onRetryConnection: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectUsb: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefreshInfo: () -> Unit,
    onSendRemoteButton: (FlipperRemoteButton, Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val isConnected = connectionState is ConnectionState.Connected
    val error = connectionState as? ConnectionState.Error

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = VesperSpace.gutter,
            end = VesperSpace.gutter,
            top = VesperSpace.md,
            bottom = VesperSpace.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.md)
    ) {
        if (showConnectionError && error != null) {
            item(key = "connection-error") {
                VErrorBanner(
                    message = error.message,
                    onRetry = onRetryConnection,
                    onDismiss = onDismissConnectionError,
                    visible = true
                )
            }
        }

        item(key = "connection") {
            ConnectionCard(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                deviceInfo = deviceInfo,
                permissionsGranted = permissionsGranted,
                isRefreshing = isRefreshing,
                onScan = onScan,
                onStopScan = onStopScan,
                onDisconnect = onDisconnect,
                onConnectUsb = onConnectUsb,
                onRequestPermissions = onRequestPermissions
            )
        }

        if (isConnected) {
            if (deviceInfo == null) {
                if (isRefreshing) {
                    item(key = "info-loading") {
                        VSkeletonList(rows = 4)
                    }
                } else {
                    item(key = "info-empty") {
                        VEmptyState(
                            title = "Device info unavailable",
                            message = "The link is up, but the Flipper did not answer the info request.",
                            actionLabel = "Try again",
                            onAction = onRefreshInfo
                        )
                    }
                }
            } else {
                item(key = "info") {
                    DeviceInfoGrid(deviceInfo = deviceInfo, storageInfo = storageInfo)
                }
            }

            item(key = "remote") {
                RemoteControlCard(
                    isSendingRemoteInput = isSendingRemoteInput,
                    statusText = remoteInputStatus,
                    onSendButton = onSendRemoteButton
                )
            }
        }

        if (glassesEnabled) {
            item(key = "glasses") {
                GlassesBridgeCard(
                    state = glassesState,
                    bridgeUrl = glassesUrl,
                    onConnect = onConnectGlasses,
                    onDisconnect = onDisconnectGlasses
                )
            }
        }

        item(key = "automation") {
            CommandAutomationCard(
                viewModel = viewModel,
                onOpenDiagnostics = onOpenDiagnostics
            )
        }

        item(key = "links") {
            DeviceLinksCard(
                isConnected = isConnected,
                storageInfo = storageInfo,
                onNavigateToFiles = onNavigateToFiles,
                onNavigateToSettings = onNavigateToSettings
            )
        }

        if (!isConnected) {
            item(key = "offline-hint") {
                Text(
                    text = "Files, remote control, and automation unlock as soon as the Flipper is connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = VesperSpace.xs)
                )
            }
        }
    }
}

@Composable
private fun DeviceLinksCard(
    isConnected: Boolean,
    storageInfo: StorageInfo?,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val colors = Vesper.colors
    VCard(level = 1, contentPadding = PaddingValues(vertical = VesperSpace.xxs)) {
        Text(
            text = "Tools",
            style = MaterialTheme.typography.labelLarge,
            color = colors.gold,
            modifier = Modifier.padding(horizontal = VesperSpace.sm, vertical = VesperSpace.xs)
        )
        VHairline()
        VListRow(
            title = "Files",
            subtitle = if (isConnected && storageInfo != null && storageInfo.internalTotal > 0L) {
                formatBytes(storageInfo.internalFree) + " free on internal storage"
            } else if (isConnected) {
                "Browse the Flipper storage"
            } else {
                "Connect a Flipper to browse storage"
            },
            icon = Icons.Default.Folder,
            trailing = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onNavigateToFiles,
            enabled = true
        )
        VHairline()
        VListRow(
            title = "Settings",
            subtitle = "API key, model, theme, and permissions",
            icon = Icons.Default.Settings,
            trailing = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onNavigateToSettings
        )
    }
}

@Composable
private fun GlassesBridgeCard(
    state: BridgeState,
    bridgeUrl: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val colors = Vesper.colors
    val connected = state is BridgeState.Connected
    val connecting = state is BridgeState.Connecting || state is BridgeState.Reconnecting
    val failed = state is BridgeState.Error
    val (label, tint) = when (state) {
        is BridgeState.Connected -> "Connected" to colors.success
        is BridgeState.Connecting -> "Connecting" to colors.warning
        is BridgeState.Reconnecting -> "Reconnecting" to colors.warning
        is BridgeState.Error -> "Action needed" to colors.danger
        is BridgeState.Disconnected -> "Not connected" to colors.textTertiary
    }

    VCard(level = 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.DevicesOther,
                contentDescription = null,
                tint = colors.gold,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Smart glasses bridge",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(VesperSpace.xxs))
                VStatusPill(text = label, color = tint)
            }
        }

        if (bridgeUrl.isNotBlank()) {
            Text(
                text = bridgeUrl,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = VesperSpace.xs)
            )
        }

        when {
            connecting -> {
                VInlineLoading(
                    text = when (state) {
                        is BridgeState.Reconnecting -> "Reconnecting, attempt " + state.attempt + " of " + state.maxAttempts
                        else -> "Connecting to the bridge"
                    }
                )
            }

            connected -> {
                Spacer(Modifier.height(VesperSpace.sm))
                VSecondaryButton(
                    text = "Disconnect bridge",
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.LinkOff
                )
            }

            else -> {
                if (bridgeUrl.isBlank()) {
                    Text(
                        text = "Set the bridge URL in Settings to connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = VesperSpace.xs)
                    )
                }
                Spacer(Modifier.height(VesperSpace.sm))
                VPrimaryButton(
                    text = if (failed) "Retry connection" else "Connect bridge",
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = bridgeUrl.isNotBlank(),
                    icon = Icons.Default.Cable
                )
            }
        }

        if (state is BridgeState.Error) {
            Spacer(Modifier.height(VesperSpace.xs))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.riskHigh
            )
        }
    }
}
