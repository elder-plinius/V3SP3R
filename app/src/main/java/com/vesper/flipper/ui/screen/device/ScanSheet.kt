package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperDevice
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Scan is a focused moment, not a permanent section. The sheet sorts by
 * signal, keeps bonded Flippers visible when they are silent, and lets the
 * user cancel without leaving the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    connectionState: ConnectionState,
    devices: List<FlipperDevice>,
    permissionsGranted: Boolean,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (FlipperDevice) -> Unit,
    onConnectUsb: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isScanning = connectionState is ConnectionState.Scanning
    val connectingName = (connectionState as? ConnectionState.Connecting)?.deviceName
    val sortedDevices = remember(devices) {
        devices.sortedWith(
            compareByDescending<FlipperDevice> { signalRank(it.rssi) }
                .thenBy { it.name.lowercase() }
        )
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted &&
            connectionState !is ConnectionState.Scanning &&
            connectionState !is ConnectionState.Connected
        ) {
            onStartScan()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            Text(
                text = "Nearby Flippers",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
            Text(
                text = "Sorted by signal strength. Bonded Flippers stay listed even when they are not advertising.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )

            if (!permissionsGranted) {
                VEmptyState(
                    title = "Bluetooth access is off",
                    message = "Vesper needs Nearby devices access to find your Flipper. Grant it, then scan again.",
                    icon = Icons.Default.Bluetooth,
                    actionLabel = "Grant access",
                    onAction = onRequestPermissions,
                    secondaryLabel = "Connect over USB",
                    onSecondary = onConnectUsb
                )
                return@Column
            }

            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.gold,
                    trackColor = colors.hairline
                )
            }

            if (sortedDevices.isEmpty()) {
                VEmptyState(
                    title = if (isScanning) "Looking for nearby Flippers" else "No Flippers found",
                    message = if (isScanning) {
                        "Keep the Flipper awake and within a few metres."
                    } else {
                        "Check that the Flipper is on and its Bluetooth is enabled, then scan again."
                    },
                    icon = Icons.Default.BluetoothSearching,
                    actionLabel = if (isScanning) "Stop scanning" else "Scan again",
                    onAction = if (isScanning) onStopScan else onStartScan,
                    secondaryLabel = "Connect over USB",
                    onSecondary = onConnectUsb
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
                ) {
                    items(items = sortedDevices, key = { it.address }) { device ->
                        ScanDeviceRow(
                            device = device,
                            isConnecting = connectingName == device.name,
                            enabled = connectingName == null,
                            onClick = { onConnect(device) }
                        )
                    }
                }
                if (isScanning) {
                    VInlineLoading(text = "Scanning for more Flippers")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                ) {
                    VPrimaryButton(
                        text = if (isScanning) "Stop scanning" else "Scan again",
                        onClick = if (isScanning) onStopScan else onStartScan,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.BluetoothSearching
                    )
                    VSecondaryButton(
                        text = "USB",
                        onClick = onConnectUsb,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Usb
                    )
                }
                Text(
                    text = "If your Flipper does not appear, turn its Bluetooth off and on, then scan again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }
        }
    }
}

@Composable
private fun ScanDeviceRow(
    device: FlipperDevice,
    isConnecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = Vesper.colors
    val knownSignal = hasKnownSignal(device.rssi)
    VListRow(
        title = device.name,
        subtitle = if (knownSignal) {
            device.rssi.toString() + " dBm · " +
                if (device.isConfirmedFlipper) "Flipper signature" else "Unverified device"
        } else {
            "Bonded device · signal not advertised"
        },
        icon = Icons.Default.Bluetooth,
        trailing = {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colors.gold,
                    strokeWidth = 2.dp
                )
            } else {
                SignalBars(rssi = device.rssi)
            }
        },
        onClick = onClick,
        enabled = enabled
    )
}

@Composable
private fun SignalBars(rssi: Int) {
    val colors = Vesper.colors
    val bars = rssiBars(rssi)
    val description = if (hasKnownSignal(rssi)) {
        bars.toString() + " of 4 bars, " + rssi + " dBm"
    } else {
        "Signal strength unknown"
    }
    Row(
        modifier = Modifier
            .semantics { contentDescription = description }
            .width(24.dp)
            .height(18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5 + index * 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < bars) colors.gold else colors.hairline)
            )
        }
    }
}

private fun signalRank(rssi: Int): Int = if (hasKnownSignal(rssi)) rssi else Int.MIN_VALUE

private fun hasKnownSignal(rssi: Int): Boolean = rssi < 0 && rssi != Int.MIN_VALUE

private fun rssiBars(rssi: Int): Int = when {
    !hasKnownSignal(rssi) -> 0
    rssi >= -50 -> 4
    rssi >= -60 -> 3
    rssi >= -70 -> 2
    rssi >= -85 -> 1
    else -> 0
}
