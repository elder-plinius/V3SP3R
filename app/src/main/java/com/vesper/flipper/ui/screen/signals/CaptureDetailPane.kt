package com.vesper.flipper.ui.screen.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VHoldToConfirmButton
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ReplayState
import com.vesper.flipper.ui.viewmodel.SignalArsenalUiState

/** Detail pane for the selected capture: waveform, metadata, replay, removal. */
@Composable
fun CaptureDetailPane(
    state: SignalArsenalUiState,
    closeDescription: String,
    onClose: () -> Unit,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
    onGoToDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = state.selected ?: return
    val colors = Vesper.colors
    val signal = capture.signal
    val replaying = state.replay is ReplayState.Sending
    val sentMessage = (state.replay as? ReplayState.Sent)?.message
    val meta = listOfNotNull(
        formatDuration(capture.durationMs)?.let { "Duration $it" },
        capture.sampleCount?.let { "$it samples" }
    ).joinToString(" · ")

    Column(
        modifier = modifier
            .background(colors.surface1)
            .verticalScroll(rememberScrollState())
            .padding(VesperSpace.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = signal.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(VesperSpace.xxs))
                Text(
                    text = "${signal.type.displayName} capture",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = closeDescription,
                    tint = colors.textSecondary
                )
            }
        }

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        VSectionHeader(title = "Waveform")
        if (state.waveformLoading) {
            WaveformLoading(modifier = Modifier.fillMaxWidth())
        } else {
            WaveformPreview(waveform = state.waveform, modifier = Modifier.fillMaxWidth())
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(VesperSpace.xs))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }

        VSectionHeader(title = "Details")
        VCard(level = 2) {
            signalDetails(signal).forEach { detail ->
                VMetricRow(label = detail.label, value = detail.value)
            }
        }

        VSectionHeader(title = "Replay")
        if (sentMessage != null) {
            VStatusPill(text = sentMessage, color = colors.success)
            Spacer(Modifier.height(VesperSpace.xs))
        }
        Text(
            text = "Transmitting uses the Flipper radio. Replay only on hardware you own.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
        Spacer(Modifier.height(VesperSpace.sm))

        if (state.isDeviceConnected) {
            VPrimaryButton(
                text = if (replaying) "Replaying" else "Replay capture",
                onClick = onReplay,
                enabled = !replaying,
                loading = replaying,
                icon = Icons.Rounded.PlayArrow,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            VStatusPill(text = "No device", color = colors.riskMedium)
            Spacer(Modifier.height(VesperSpace.xs))
            Text(
                text = "Connect in Device to transmit this capture.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.sm))
            VSecondaryButton(
                text = "Go to Device",
                onClick = onGoToDevice,
                modifier = Modifier.fillMaxWidth()
            )
        }

        VSectionHeader(title = "Remove")
        Text(
            text = "Deletes the capture file from the Flipper.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(VesperSpace.sm))
        VHoldToConfirmButton(
            text = "Hold to delete capture",
            onConfirm = onDelete,
            enabled = !replaying
        )
        Text(
            text = "Vesper cannot restore a deleted capture.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = VesperSpace.xs)
        )

        Spacer(Modifier.height(VesperSpace.xxl))
    }
}

/** Placeholder for the detail pane before a capture is picked. */
@Composable
fun EmptyDetailPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        VEmptyState(
            title = "Nothing selected",
            message = "Pick a capture to see its waveform and details.",
            icon = Icons.Rounded.GraphicEq
        )
    }
}
