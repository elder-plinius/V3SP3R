package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.domain.model.FlipperRemoteButton
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.DeviceViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class MacroStep(
    val button: FlipperRemoteButton,
    val delayMs: Long,
    val longPress: Boolean
)

private enum class RunbookId(val title: String, val description: String) {
    LINK_HEALTH(
        title = "Link health sweep",
        description = "Refresh device info, then run the full link diagnostics."
    ),
    INPUT_SMOKE(
        title = "Input smoke test",
        description = "Send Back, Up, Down, and OK to verify the remote input path."
    ),
    RECOVER_SCAN(
        title = "Recover and scan",
        description = "Drop the current link and start a fresh scan."
    )
}

/**
 * The one runbook and macro surface in the app. Device owns it; Ops reuses
 * the runbook half without duplicating the implementation.
 */
@Composable
fun CommandAutomationCard(
    viewModel: DeviceViewModel,
    modifier: Modifier = Modifier,
    showMacros: Boolean = true,
    onOpenDiagnostics: (() -> Unit)? = null
) {
    val colors = Vesper.colors
    val connectionState by viewModel.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    var activeRunbook by remember { mutableStateOf<RunbookId?>(null) }
    var lastRunbook by remember { mutableStateOf<RunbookId?>(null) }
    var runbookMessage by remember { mutableStateOf<String?>(null) }
    var runbookFailed by remember { mutableStateOf(false) }
    var runbookJob by remember { mutableStateOf<Job?>(null) }

    var macroName by remember { mutableStateOf("macro_1") }
    var longPressMode by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var lastRecordedAtMs by remember { mutableStateOf(0L) }
    val macroSteps = remember { mutableStateListOf<MacroStep>() }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    var isReplaying by remember { mutableStateOf(false) }
    var replayMessage by remember { mutableStateOf<String?>(null) }
    var replayFailed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runbookJob?.cancel()
            replayJob?.cancel()
        }
    }

    fun startRunbook(runbook: RunbookId) {
        if (activeRunbook != null) return
        activeRunbook = runbook
        lastRunbook = runbook
        runbookMessage = null
        runbookFailed = false
        runbookJob = scope.launch {
            try {
                when (runbook) {
                    RunbookId.LINK_HEALTH -> {
                        runbookMessage = "Refreshing device info"
                        viewModel.refreshDeviceInfo()
                        delay(250)
                        runbookMessage = "Running link diagnostics"
                        viewModel.runConnectionDiagnostics()
                        runbookMessage = "Health sweep started. Results land in Diagnostics."
                    }

                    RunbookId.INPUT_SMOKE -> {
                        if (connectionState !is ConnectionState.Connected) {
                            runbookFailed = true
                            runbookMessage = "Connect a Flipper before running the input smoke test."
                            return@launch
                        }
                        val sequence = listOf(
                            FlipperRemoteButton.BACK,
                            FlipperRemoteButton.UP,
                            FlipperRemoteButton.DOWN,
                            FlipperRemoteButton.OK
                        )
                        sequence.forEachIndexed { index, button ->
                            runbookMessage = "Sending " + button.label + " (" + (index + 1) + " of " + sequence.size + ")"
                            val result = viewModel.sendRemoteButtonAwait(button, false)
                            if (result.isFailure) {
                                runbookFailed = true
                                runbookMessage = "Input smoke test failed on " + button.label + ": " +
                                    (result.exceptionOrNull()?.message ?: "unknown error")
                                return@launch
                            }
                            delay(220)
                        }
                        runbookMessage = "Input smoke test finished: Back, Up, Down, OK."
                    }

                    RunbookId.RECOVER_SCAN -> {
                        runbookMessage = "Disconnecting"
                        viewModel.disconnect()
                        delay(350)
                        viewModel.startScan()
                        runbookMessage = "Disconnected. Scanning for nearby Flippers."
                    }
                }
            } finally {
                activeRunbook = null
            }
        }
    }

    fun startReplay() {
        if (macroSteps.isEmpty()) {
            replayFailed = true
            replayMessage = "Record at least one step before replaying."
            return
        }
        if (connectionState !is ConnectionState.Connected) {
            replayFailed = true
            replayMessage = "Connect a Flipper before replaying."
            return
        }
        replayJob?.cancel()
        replayJob = scope.launch {
            isReplaying = true
            replayFailed = false
            try {
                replayMessage = "Replaying " + macroName.ifBlank { "macro" }
                macroSteps.forEachIndexed { index, step ->
                    if (!isActive) return@launch
                    if (step.delayMs > 0L) delay(step.delayMs)
                    replayMessage = "Step " + (index + 1) + " of " + macroSteps.size + ": " + step.button.label
                    val result = viewModel.sendRemoteButtonAwait(step.button, step.longPress)
                    if (result.isFailure) {
                        replayFailed = true
                        replayMessage = "Replay failed at step " + (index + 1) + ": " +
                            (result.exceptionOrNull()?.message ?: "unknown error")
                        return@launch
                    }
                    delay(160)
                }
                replayMessage = "Replay finished (" + macroSteps.size + " steps)."
            } finally {
                isReplaying = false
            }
        }
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        isReplaying = false
        replayFailed = false
        replayMessage = "Replay stopped."
    }

    fun recordStep(button: FlipperRemoteButton) {
        if (isRecording) {
            val now = System.currentTimeMillis()
            val delta = if (lastRecordedAtMs == 0L) 0L else (now - lastRecordedAtMs).coerceAtMost(5_000L)
            macroSteps.add(MacroStep(button = button, delayMs = delta, longPress = longPressMode))
            lastRecordedAtMs = now
        }
        scope.launch {
            viewModel.sendRemoteButtonAwait(button, longPressMode)
        }
    }

    val automationLabel = when {
        activeRunbook != null -> "Running"
        connectionState is ConnectionState.Connected -> "Ready"
        else -> "Offline"
    }
    val automationColor = when {
        activeRunbook != null -> colors.warning
        connectionState is ConnectionState.Connected -> colors.success
        else -> colors.textTertiary
    }

    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Engineering,
                contentDescription = null,
                tint = colors.gold,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Automation",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "Runbooks for recovery, plus recorded input sequences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            VStatusPill(text = automationLabel, color = automationColor)
        }

        Spacer(Modifier.height(VesperSpace.sm))
        Text(
            text = "Runbooks",
            style = MaterialTheme.typography.labelLarge,
            color = colors.gold
        )
        VHairline()

        RunbookId.values().forEach { runbook ->
            VListRow(
                title = runbook.title,
                subtitle = runbook.description,
                trailing = {
                    when {
                        activeRunbook == runbook -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.gold,
                            strokeWidth = 2.dp
                        )

                        else -> VQuietButton(
                            text = "Run",
                            onClick = { startRunbook(runbook) },
                            enabled = activeRunbook == null
                        )
                    }
                },
                onClick = { startRunbook(runbook) },
                enabled = activeRunbook == null || activeRunbook == runbook
            )
            VHairline()
        }

        if (activeRunbook != null) {
            Spacer(Modifier.height(VesperSpace.xs))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.gold,
                trackColor = colors.hairline
            )
            VInlineLoading(text = runbookMessage ?: "Running runbook")
        } else if (runbookMessage != null) {
            Spacer(Modifier.height(VesperSpace.xs))
            if (runbookFailed) {
                VErrorBanner(
                    message = runbookMessage.orEmpty(),
                    onRetry = lastRunbook?.let { previous -> { startRunbook(previous) } },
                    onDismiss = {
                        runbookMessage = null
                        runbookFailed = false
                    },
                    visible = true
                )
            } else {
                Text(
                    text = runbookMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.success
                )
            }
        }

        if (onOpenDiagnostics != null) {
            VQuietButton(
                text = "Open diagnostics",
                onClick = { onOpenDiagnostics?.invoke() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (showMacros) {
            Spacer(Modifier.height(VesperSpace.sm))
            VHairline()
            Spacer(Modifier.height(VesperSpace.sm))
            Text(
                text = "Macro recorder",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Text(
                text = "Record a short sequence, then replay it on the same Flipper.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.sm))

            OutlinedTextField(
                value = macroName,
                onValueChange = { macroName = it },
                label = { Text("Macro name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(VesperSpace.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Long press each step",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Hold the key instead of tapping it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Switch(checked = longPressMode, onCheckedChange = { longPressMode = it })
            }

            Spacer(Modifier.height(VesperSpace.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
            ) {
                VPrimaryButton(
                    text = if (isRecording) "Stop recording" else "Record",
                    onClick = {
                        if (isRecording) {
                            isRecording = false
                            replayFailed = false
                            replayMessage = "Recorded " + macroSteps.size + " steps."
                        } else {
                            macroSteps.clear()
                            lastRecordedAtMs = 0L
                            isRecording = true
                            replayFailed = false
                            replayMessage = "Recording. Tap keys below to add steps."
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                VSecondaryButton(
                    text = if (isReplaying) "Stop replay" else "Replay",
                    onClick = { if (isReplaying) stopReplay() else startReplay() },
                    modifier = Modifier.weight(1f),
                    enabled = !isRecording
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
            ) {
                VQuietButton(
                    text = "Clear steps",
                    onClick = {
                        macroSteps.clear()
                        lastRecordedAtMs = 0L
                        replayFailed = false
                        replayMessage = if (isRecording) {
                            "Steps cleared. Recording continues."
                        } else {
                            "Steps cleared."
                        }
                    },
                    enabled = macroSteps.isNotEmpty() && !isReplaying,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = macroSteps.size.toString() + " steps",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textTertiary
                )
            }

            Spacer(Modifier.height(VesperSpace.xs))
            if (replayMessage != null) {
                if (replayFailed) {
                    VErrorBanner(
                        message = replayMessage.orEmpty(),
                        onDismiss = {
                            replayMessage = null
                            replayFailed = false
                        },
                        visible = true
                    )
                } else {
                    Text(
                        text = replayMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (isRecording) {
                Spacer(Modifier.height(VesperSpace.sm))
                VHairline()
                Spacer(Modifier.height(VesperSpace.xs))
                Text(
                    text = "Tap keys to record",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.gold
                )
                MacroKeyRow(
                    buttons = listOf(FlipperRemoteButton.UP),
                    onRecord = { button -> recordStep(button) }
                )
                MacroKeyRow(
                    buttons = listOf(
                        FlipperRemoteButton.LEFT,
                        FlipperRemoteButton.OK,
                        FlipperRemoteButton.RIGHT
                    ),
                    onRecord = { button -> recordStep(button) }
                )
                MacroKeyRow(
                    buttons = listOf(FlipperRemoteButton.BACK, FlipperRemoteButton.DOWN),
                    onRecord = { button -> recordStep(button) }
                )
            }

            if (macroSteps.isNotEmpty()) {
                Spacer(Modifier.height(VesperSpace.sm))
                VHairline()
                Spacer(Modifier.height(VesperSpace.xs))
                Text(
                    text = "Sequence",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.gold
                )
                macroSteps.take(8).forEachIndexed { index, step ->
                    Text(
                        text = (index + 1).toString() + ". +" + step.delayMs + " ms · " +
                            step.button.label + if (step.longPress) " (hold)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                if (macroSteps.size > 8) {
                    Text(
                        text = "+" + (macroSteps.size - 8) + " more steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroKeyRow(
    buttons: List<FlipperRemoteButton>,
    onRecord: (FlipperRemoteButton) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = VesperSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
    ) {
        buttons.forEach { button ->
            VSecondaryButton(
                text = button.label,
                onClick = { onRecord(button) },
                modifier = Modifier.weight(1f)
            )
        }
        if (buttons.size == 1) {
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}
