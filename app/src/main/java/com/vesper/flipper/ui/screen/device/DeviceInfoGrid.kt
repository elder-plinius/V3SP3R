package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vesper.flipper.domain.model.DeviceInfo
import com.vesper.flipper.domain.model.StorageInfo
import com.vesper.flipper.ui.components.VStatTile
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Device facts as compact tiles. No prose, no progress bars competing with
 * the connection card: four numbers the user actually checks.
 */
@Composable
fun DeviceInfoGrid(
    deviceInfo: DeviceInfo,
    storageInfo: StorageInfo?,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val batteryColor: Color = when {
        deviceInfo.isCharging -> colors.success
        deviceInfo.batteryLevel <= 15 -> colors.danger
        deviceInfo.batteryLevel <= 35 -> colors.warning
        else -> colors.gold
    }

    val internalPercent = storageInfo?.let { storage ->
        if (storage.internalTotal > 0L) {
            (((storage.internalTotal - storage.internalFree).toDouble() / storage.internalTotal.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            null
        }
    }
    val sdPercent = storageInfo?.let { storage ->
        val total = storage.externalTotal ?: 0L
        if (storage.hasSdCard && total > 0L) {
            (((total - (storage.externalFree ?: 0L)).toDouble() / total.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
            VStatTile(
                label = "Firmware",
                value = deviceInfo.firmwareVersion,
                caption = deviceInfo.hardwareVersion,
                accent = colors.gold,
                modifier = Modifier.weight(1f)
            )
            VStatTile(
                label = "Battery",
                value = deviceInfo.batteryLevel.coerceIn(0, 100).toString() + "%",
                caption = if (deviceInfo.isCharging) "Charging" else "On battery",
                accent = batteryColor,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(VesperSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
            VStatTile(
                label = "Internal storage",
                value = internalPercent?.let { it.toString() + "% used" } ?: "Unavailable",
                caption = storageInfo?.takeIf { it.internalTotal > 0L }?.let {
                    formatBytes(it.internalFree) + " free"
                },
                modifier = Modifier.weight(1f)
            )
            VStatTile(
                label = "SD card",
                value = sdPercent?.let { it.toString() + "% used" } ?: "None",
                caption = if (sdPercent != null) {
                    formatBytes(storageInfo?.externalFree ?: 0L) + " free"
                } else {
                    "No card inserted"
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Short human byte size. Kept next to the tile that shows it so the
 * formatting rule lives in one place.
 */
fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> safe.toString() + " B"
        safe < 1024L * 1024L -> (safe / 1024L).toString() + " KB"
        safe < 1024L * 1024L * 1024L -> (safe / (1024L * 1024L)).toString() + " MB"
        else -> {
            val gigabytes = safe.toDouble() / (1024.0 * 1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.1f GB", gigabytes)
        }
    }
}
