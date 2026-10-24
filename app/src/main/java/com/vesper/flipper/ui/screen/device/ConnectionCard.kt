package com.vesper.flipper.ui.screen.device

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperDevice
import com.vesper.flipper.domain.model.DeviceInfo
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * The single source of truth for "what is my connection doing".
 * Device name, state, battery, firmware and the one primary action live here.
 */
@Composable
fun ConnectionCard(
    connectionState: ConnectionState,
    connectedDevice: FlipperDevice?,
    deviceInfo: DeviceInfo?,
    permissionsGranted: Boolean,
    isRefreshing: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectUsb: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val isConnected = connectionState is ConnectionState.Connected
    val isScanning = connectionState is ConnectionState.Scanning
    val isConnecting = connectionState is ConnectionState.Connecting
    val deviceName = when (connectionState) {
        is ConnectionState.Connected -> connectedDevice?.name ?: connectionState.deviceName
        is ConnectionState.Connecting -> connectionState.deviceName
        else -> connectedDevice?.name ?: "No Flipper connected"
    }

    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.surface3),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = statusTint(connectionState),
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(VesperSpace.xxs))
                BleStatusPill(connectionState)
            }
            if (isConnected && deviceInfo != null) {
                Spacer(Modifier.width(VesperSpace.sm))
                BatteryRing(level = deviceInfo.batteryLevel, isCharging = deviceInfo.isCharging)
            } else if (isRefreshing || isConnecting || (isConnected && deviceInfo == null)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colors.gold,
                    strokeWidth = 2.dp
                )
            }
        }

        if (isConnected) {
            Spacer(Modifier.height(VesperSpace.sm))
            VHairline()
            Spacer(Modifier.height(VesperSpace.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Firmware",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
                Text(
                    text = deviceInfo?.firmwareVersion ?: "Unknown",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!connectedDevice?.address.isNullOrBlank()) {
                Text(
                    text = connectedDevice?.address.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.md))

        when (connectionState) {
            is ConnectionState.Scanning -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.gold,
                    trackColor = colors.hairline
                )
                Spacer(Modifier.height(VesperSpace.sm))
                VPrimaryButton(
                    text = "Stop scanning",
                    onClick = onStopScan,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.BluetoothDisabled
                )
            }

            is ConnectionState.Connecting -> {
                VInlineLoading(text = "Connecting to " + connectionState.deviceName)
                VPrimaryButton(
                    text = "Connecting",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    loading = true
                )
            }

            is ConnectionState.Connected -> {
                VSecondaryButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.LinkOff
                )
            }

            else -> {
                ConnectionRequirements(
                    permissionsGranted = permissionsGranted,
                    error = (connectionState as? ConnectionState.Error)?.message
                )
                Spacer(Modifier.height(VesperSpace.sm))
                if (permissionsGranted) {
                    VPrimaryButton(
                        text = "Scan for Flipper",
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.BluetoothSearching
                    )
                } else {
                    VPrimaryButton(
                        text = "Grant access",
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Key
                    )
                }
                Spacer(Modifier.height(VesperSpace.xs))
                VSecondaryButton(
                    text = "Connect over USB",
                    onClick = onConnectUsb,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Usb
                )
            }
        }
    }
}

@Composable
private fun ConnectionRequirements(
    permissionsGranted: Boolean,
    error: String?
) {
    val colors = Vesper.colors
    Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.xxs)) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.riskHigh
            )
            Spacer(Modifier.height(VesperSpace.xxs))
        }
        Text(
            text = "Two things are needed to connect",
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary
        )
        Text(
            text = "1. Turn on Bluetooth on the Flipper: Settings > Bluetooth.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Text(
            text = "2. Allow Nearby devices access for Vesper on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionsGranted) colors.textSecondary else colors.rose
        )
    }
}

@Composable
fun BleStatusPill(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val label: String
    val color: Color
    when (connectionState) {
        is ConnectionState.Connected -> {
            label = "Connected"
            color = colors.success
        }

        is ConnectionState.Connecting -> {
            label = "Connecting"
            color = colors.warning
        }

        is ConnectionState.Scanning -> {
            label = "Scanning"
            color = colors.gold
        }

        is ConnectionState.Error -> {
            label = "Action needed"
            color = colors.danger
        }

        ConnectionState.Disconnected -> {
            label = "Disconnected"
            color = colors.textTertiary
        }
    }
    VStatusPill(text = label, modifier = modifier, color = color)
}

fun connectionStateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Connecting -> "Connecting"
    is ConnectionState.Scanning -> "Scanning"
    is ConnectionState.Error -> "Not connected"
    ConnectionState.Disconnected -> "Disconnected"
}

@Composable
private fun statusTint(state: ConnectionState): Color {
    val colors = Vesper.colors
    return when (state) {
        is ConnectionState.Connected -> colors.success
        is ConnectionState.Connecting -> colors.warning
        is ConnectionState.Scanning -> colors.gold
        is ConnectionState.Error -> colors.danger
        ConnectionState.Disconnected -> colors.textTertiary
    }
}

@Composable
private fun BatteryRing(level: Int, isCharging: Boolean) {
    val colors = Vesper.colors
    val clamped = level.coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = Vesper.motion.standard<Float>(),
        label = "batteryLevel"
    )
    val ringColor = when {
        isCharging -> colors.success
        clamped <= 15 -> colors.danger
        clamped <= 35 -> colors.warning
        else -> colors.gold
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .semantics {
                contentDescription = if (isCharging) {
                    "Battery " + clamped + " percent, charging"
                } else {
                    "Battery " + clamped + " percent"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.hairline,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = clamped.toString() + "%",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            if (isCharging) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = ringColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
