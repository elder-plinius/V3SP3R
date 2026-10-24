package com.vesper.flipper.ui.screen.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ble.CliCapabilityLevel
import com.vesper.flipper.ble.CliCapabilityStatus
import com.vesper.flipper.ble.CommandPipelineAutotuneStatus
import com.vesper.flipper.ble.ConnectionCheckLevel
import com.vesper.flipper.ble.ConnectionCheckResult
import com.vesper.flipper.ble.ConnectionDiagnosticsReport
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FirmwareCompatibilityProfile
import com.vesper.flipper.ble.FirmwareTransportMode
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.screen.device.connectionStateLabel
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ops Center is read-only diagnostics. Every row carries a status colour and
 * a sentence that says what to do next.
 */
@Composable
fun PipelineHealthCard(
    connectionState: ConnectionState,
    connectedName: String?,
    cliStatus: CliCapabilityStatus,
    diagnostics: ConnectionDiagnosticsReport,
    autotuneStatus: CommandPipelineAutotuneStatus,
    firmwareCompatibility: FirmwareCompatibilityProfile,
    isRunningDiagnostics: Boolean,
    onRunDiagnostics: () -> Unit,
    onRefreshInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val connected = connectionState is ConnectionState.Connected
    val error = connectionState as? ConnectionState.Error
    val overallLabel = when {
        isRunningDiagnostics -> "Checking"
        connected -> "Live"
        else -> "Offline"
    }
    val overallColor = when {
        isRunningDiagnostics -> colors.warning
        connected -> colors.success
        else -> colors.textTertiary
    }

    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pipeline health",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            VStatusPill(text = overallLabel, color = overallColor)
        }

        Spacer(Modifier.height(VesperSpace.sm))
        HealthRow(
            label = "Connection",
            value = connectionStateLabel(connectionState),
            color = when (connectionState) {
                is ConnectionState.Connected -> colors.success
                is ConnectionState.Connecting -> colors.warning
                is ConnectionState.Scanning -> colors.gold
                is ConnectionState.Error -> colors.danger
                ConnectionState.Disconnected -> colors.textTertiary
            },
            detail = connectedName ?: error?.message
        )
        HealthRow(
            label = "CLI channel",
            value = capabilityLabel(cliStatus.level, cliStatus.supportsCli),
            color = capabilityColor(cliStatus.level, cliStatus.supportsCli)
        )
        HealthRow(
            label = "RPC channel",
            value = capabilityLabel(cliStatus.level, cliStatus.supportsRpc),
            color = capabilityColor(cliStatus.level, cliStatus.supportsRpc)
        )
        HealthRow(
            label = "Firmware transport",
            value = transportModeLabel(firmwareCompatibility.transportMode),
            color = transportColor(firmwareCompatibility.transportMode)
        )
        HealthRow(
            label = "Last diagnostics",
            value = when {
                diagnostics.checks.isEmpty() -> "Not run"
                diagnostics.hasFailures -> diagnostics.failedCount.toString() + " failed"
                diagnostics.hasWarnings -> diagnostics.warningCount.toString() + " warnings"
                else -> "All passed"
            },
            color = when {
                diagnostics.hasFailures -> colors.danger
                diagnostics.hasWarnings -> colors.warning
                diagnostics.checks.isEmpty() -> colors.textTertiary
                else -> colors.success
            }
        )

        if (isRunningDiagnostics) {
            VInlineLoading(text = "Running link diagnostics")
        }

        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = when {
                isRunningDiagnostics -> "Waiting for the link checks to finish."
                !connected -> "Connect a Flipper from the Device tab to see live pipeline health."
                diagnostics.hasFailures -> "A check failed. Reconnect the Flipper, then run diagnostics again."
                diagnostics.checks.isEmpty() -> "Run link diagnostics to confirm the BLE, CLI, and RPC chain."
                else -> "The command chain is healthy. Re-run diagnostics after any failed command."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (diagnostics.hasFailures) colors.riskHigh else colors.textSecondary
        )

        Spacer(Modifier.height(VesperSpace.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            VPrimaryButton(
                text = "Run diagnostics",
                onClick = onRunDiagnostics,
                modifier = Modifier.weight(1f),
                enabled = connected,
                loading = isRunningDiagnostics,
                icon = Icons.Default.BugReport
            )
            VSecondaryButton(
                text = "Refresh data",
                onClick = onRefreshInfo,
                modifier = Modifier.weight(1f),
                enabled = connected,
                icon = Icons.Default.Refresh
            )
        }
    }
}

@Composable
fun AutotuneCard(
    autotuneStatus: CommandPipelineAutotuneStatus,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val healthy = autotuneStatus.successRate >= 0.9f
    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Connection autotuner",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            VStatusPill(
                text = autotuneStatus.profileLabel,
                color = if (healthy) colors.success else colors.warning
            )
        }
        Spacer(Modifier.height(VesperSpace.sm))
        VMetricRow(
            label = "Success rate",
            value = (autotuneStatus.successRate * 100f).toInt().toString() + "%",
            valueColor = if (healthy) colors.success else colors.warning
        )
        VMetricRow(
            label = "Average latency",
            value = autotuneStatus.averageLatencyMs.toString() + " ms",
            valueColor = colors.textPrimary
        )
        VMetricRow(label = "CLI retries", value = autotuneStatus.cliRetryAttempts.toString() + " per command")
        VMetricRow(label = "RPC retries", value = autotuneStatus.rpcRetryAttempts.toString() + " per command")
        VMetricRow(
            label = "Write retries",
            value = autotuneStatus.fileWriteRetryAttempts.toString() + " per write"
        )
        VMetricRow(label = "Samples", value = autotuneStatus.sampleSize.toString())
        VMetricRow(
            label = "Busy responses",
            value = autotuneStatus.busySignals.toString(),
            valueColor = if (autotuneStatus.busySignals > 0) colors.warning else colors.textSecondary
        )
        VMetricRow(
            label = "Disconnects",
            value = autotuneStatus.disconnectSignals.toString(),
            valueColor = if (autotuneStatus.disconnectSignals > 0) colors.danger else colors.textSecondary
        )
        VMetricRow(label = "Updated", value = formatClock(autotuneStatus.lastUpdatedMs))
        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = when {
                autotuneStatus.sampleSize == 0 ->
                    "No samples yet. Run a command from Chat to let the pipeline tune its retries."

                !healthy ->
                    "Success rate is below 90%. Reconnect the Flipper and retry the last command."

                else ->
                    "The pipeline is adapting retries automatically. Nothing to change here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }
}

@Composable
fun FirmwareCompatibilityCard(
    firmwareCompatibility: FirmwareCompatibilityProfile,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val mode = firmwareCompatibility.transportMode
    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Firmware compatibility",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            VStatusPill(
                text = firmwareCompatibility.label,
                color = transportColor(mode)
            )
        }
        Spacer(Modifier.height(VesperSpace.sm))
        VMetricRow(label = "Transport", value = transportModeLabel(mode), valueColor = transportColor(mode))
        VMetricRow(
            label = "CLI",
            value = if (firmwareCompatibility.supportsCli) "Supported" else "Not detected",
            valueColor = if (firmwareCompatibility.supportsCli) colors.success else colors.textSecondary
        )
        VMetricRow(
            label = "RPC",
            value = if (firmwareCompatibility.supportsRpc) "Supported" else "Not detected",
            valueColor = if (firmwareCompatibility.supportsRpc) colors.success else colors.textSecondary
        )
        VMetricRow(
            label = "RPC app bridge",
            value = if (firmwareCompatibility.supportsRpcAppBridge) "Supported" else "Not detected",
            valueColor = if (firmwareCompatibility.supportsRpcAppBridge) colors.success else colors.textSecondary
        )
        VMetricRow(
            label = "Confidence",
            value = (firmwareCompatibility.confidence * 100f).toInt().toString() + "%",
            valueColor = colors.gold
        )
        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = firmwareCompatibility.notes,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = when (mode) {
                FirmwareTransportMode.CLI_AND_RPC ->
                    "Both transports respond. No action needed."

                FirmwareTransportMode.CLI_ONLY ->
                    "Only direct CLI responds. Commands that require the RPC app bridge will be limited."

                FirmwareTransportMode.RPC_ONLY ->
                    "Only the RPC bridge responds. Direct CLI commands will be limited."

                FirmwareTransportMode.PROBING ->
                    "Probing the transport. This resolves without action."

                FirmwareTransportMode.UNAVAILABLE ->
                    "No transport responded. Reconnect the Flipper and check Bluetooth."
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (mode) {
                FirmwareTransportMode.UNAVAILABLE -> colors.riskHigh
                FirmwareTransportMode.PROBING -> colors.warning
                else -> colors.textSecondary
            }
        )
    }
}

@Composable
fun DiagnosticsChecksCard(
    diagnostics: ConnectionDiagnosticsReport,
    isRunningDiagnostics: Boolean,
    onRunDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Link checks",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (diagnostics.checks.isNotEmpty()) {
                VStatusPill(
                    text = diagnostics.durationMs.toString() + " ms",
                    color = if (diagnostics.hasFailures) colors.danger else colors.success
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.sm))
        when {
            isRunningDiagnostics -> VInlineLoading(text = "Running link diagnostics")

            diagnostics.checks.isEmpty() -> VEmptyState(
                title = "No link checks yet",
                message = "Run a check to confirm the BLE link, CLI channel, and RPC channel are responding.",
                icon = Icons.Default.BugReport,
                actionLabel = "Run diagnostics",
                onAction = onRunDiagnostics
            )

            else -> {
                if (diagnostics.hasFailures) {
                    VErrorBanner(
                        message = diagnostics.summary,
                        onRetry = onRunDiagnostics,
                        visible = true
                    )
                } else {
                    Text(
                        text = diagnostics.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.height(VesperSpace.xs))
                diagnostics.checks.forEach { check ->
                    VHairline()
                    CheckRow(check = check)
                }
            }
        }

        if (diagnostics.checks.isNotEmpty()) {
            Spacer(Modifier.height(VesperSpace.sm))
            VPrimaryButton(
                text = "Run diagnostics again",
                onClick = onRunDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunningDiagnostics,
                loading = isRunningDiagnostics,
                icon = Icons.Default.CheckCircle
            )
        }
    }
}

@Composable
private fun HealthRow(
    label: String,
    value: String,
    color: Color,
    detail: String? = null
) {
    val colors = Vesper.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(VesperSpace.xs))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun CheckRow(check: ConnectionCheckResult) {
    val colors = Vesper.colors
    val color = when (check.level) {
        ConnectionCheckLevel.PASS -> colors.success
        ConnectionCheckLevel.WARN -> colors.warning
        ConnectionCheckLevel.FAIL -> colors.danger
        ConnectionCheckLevel.SKIPPED -> colors.textTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = VesperSpace.xxs),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(VesperSpace.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = check.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
            Text(
                text = check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Text(
            text = if (check.elapsedMs > 0L) check.elapsedMs.toString() + " ms" else check.level.name.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun capabilityLabel(level: CliCapabilityLevel, supported: Boolean): String = when {
    level == CliCapabilityLevel.READY && supported -> "Ready"
    level == CliCapabilityLevel.READY -> "Limited"
    level == CliCapabilityLevel.UNAVAILABLE -> "Unavailable"
    else -> "Not checked"
}

@Composable
private fun capabilityColor(level: CliCapabilityLevel, supported: Boolean): Color {
    val colors = Vesper.colors
    return when {
        level == CliCapabilityLevel.READY && supported -> colors.success
        level == CliCapabilityLevel.READY -> colors.warning
        level == CliCapabilityLevel.UNAVAILABLE -> colors.danger
        else -> colors.textTertiary
    }
}

private fun transportModeLabel(mode: FirmwareTransportMode): String = when (mode) {
    FirmwareTransportMode.CLI_AND_RPC -> "CLI and RPC"
    FirmwareTransportMode.CLI_ONLY -> "CLI only"
    FirmwareTransportMode.RPC_ONLY -> "RPC only"
    FirmwareTransportMode.PROBING -> "Probing"
    FirmwareTransportMode.UNAVAILABLE -> "Unavailable"
}

@Composable
private fun transportColor(mode: FirmwareTransportMode): Color = when (mode) {
    FirmwareTransportMode.CLI_AND_RPC -> Vesper.colors.success
    FirmwareTransportMode.CLI_ONLY -> Vesper.colors.warning
    FirmwareTransportMode.RPC_ONLY -> Vesper.colors.warning
    FirmwareTransportMode.PROBING -> Vesper.colors.info
    FirmwareTransportMode.UNAVAILABLE -> Vesper.colors.danger
}

private fun formatClock(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Never"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
}
