package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything read-only lives here: transport capability, retry tuning,
 * latency, confidence, and the last link checks. The main screen stays
 * about one device and one action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    connectionState: ConnectionState,
    cliStatus: CliCapabilityStatus,
    diagnostics: ConnectionDiagnosticsReport,
    autotuneStatus: CommandPipelineAutotuneStatus,
    firmwareCompatibility: FirmwareCompatibilityProfile,
    isRunningDiagnostics: Boolean,
    onRunDiagnostics: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val connected = connectionState is ConnectionState.Connected
    val cliColor = when {
        cliStatus.level == CliCapabilityLevel.READY && cliStatus.supportsCli -> colors.success
        cliStatus.level == CliCapabilityLevel.READY -> colors.warning
        cliStatus.level == CliCapabilityLevel.UNAVAILABLE -> colors.danger
        else -> colors.textTertiary
    }
    val rpcColor = when {
        cliStatus.level == CliCapabilityLevel.READY && cliStatus.supportsRpc -> colors.success
        cliStatus.level == CliCapabilityLevel.READY -> colors.warning
        cliStatus.level == CliCapabilityLevel.UNAVAILABLE -> colors.danger
        else -> colors.textTertiary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(VesperSpace.sm))
                Column {
                    Text(
                        text = "Diagnostics",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (connected) "Live transport and retry telemetry." else "Connect a Flipper to collect live telemetry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            DiagnosticsSection(title = "Transport")
            VMetricRow(label = "Connection", value = connectionStateLabel(connectionState))
            VMetricRow(
                label = "CLI channel",
                value = capabilityLabel(cliStatus.level, cliStatus.supportsCli),
                valueColor = cliColor
            )
            VMetricRow(
                label = "RPC channel",
                value = capabilityLabel(cliStatus.level, cliStatus.supportsRpc),
                valueColor = rpcColor
            )
            VMetricRow(label = "Firmware family", value = firmwareCompatibility.label)
            VMetricRow(
                label = "Transport mode",
                value = transportModeLabel(firmwareCompatibility.transportMode)
            )
            VMetricRow(
                label = "Confidence",
                value = (firmwareCompatibility.confidence * 100f).toInt().toString() + "%",
                valueColor = colors.gold
            )
            VMetricRow(
                label = "Last probe",
                value = if (cliStatus.checkedAtMs > 0L) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cliStatus.checkedAtMs))
                } else {
                    "Not checked"
                }
            )
            if (cliStatus.details.isNotBlank()) {
                Text(
                    text = cliStatus.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }

            DiagnosticsSection(title = "Connection autotuner")
            VMetricRow(label = "Profile", value = autotuneStatus.profileLabel)
            VMetricRow(
                label = "Success rate",
                value = (autotuneStatus.successRate * 100f).toInt().toString() + "%",
                valueColor = if (autotuneStatus.successRate >= 0.9f) colors.success else colors.warning
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
                label = "Busy signals",
                value = autotuneStatus.busySignals.toString(),
                valueColor = if (autotuneStatus.busySignals > 0) colors.warning else colors.textSecondary
            )
            VMetricRow(
                label = "Disconnects",
                value = autotuneStatus.disconnectSignals.toString(),
                valueColor = if (autotuneStatus.disconnectSignals > 0) colors.danger else colors.textSecondary
            )

            DiagnosticsSection(title = "Link checks")
            if (isRunningDiagnostics) {
                VInlineLoading(text = "Running link diagnostics")
            }
            if (diagnostics.hasFailures) {
                VErrorBanner(
                    message = diagnostics.summary,
                    onRetry = onRunDiagnostics,
                    visible = true
                )
            } else if (diagnostics.checks.isNotEmpty()) {
                Text(
                    text = diagnostics.summary + " (" + diagnostics.durationMs + " ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }

            if (diagnostics.checks.isEmpty() && !isRunningDiagnostics) {
                VEmptyState(
                    title = "No link checks yet",
                    message = "Run a check to confirm the BLE link, CLI channel, and RPC channel are responding.",
                    icon = Icons.Default.BugReport,
                    actionLabel = "Run link diagnostics",
                    onAction = onRunDiagnostics
                )
            } else {
                diagnostics.checks.forEach { check ->
                    VHairline()
                    ConnectionCheckRow(check = check)
                }
            }

            Spacer(Modifier.height(VesperSpace.xs))
            VPrimaryButton(
                text = "Run link diagnostics",
                onClick = onRunDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                enabled = connected,
                loading = isRunningDiagnostics,
                icon = Icons.Default.BugReport
            )
            VQuietButton(text = "Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String) {
    val colors = Vesper.colors
    Spacer(Modifier.height(VesperSpace.xs))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = colors.gold
    )
    VHairline()
}

@Composable
private fun ConnectionCheckRow(check: ConnectionCheckResult) {
    val colors = Vesper.colors
    val levelColor: Color = when (check.level) {
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
                .background(levelColor)
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
            color = levelColor
        )
    }
}

private fun capabilityLabel(level: CliCapabilityLevel, supported: Boolean): String = when {
    level == CliCapabilityLevel.READY && supported -> "Ready"
    level == CliCapabilityLevel.READY -> "Limited"
    level == CliCapabilityLevel.UNAVAILABLE -> "Unavailable"
    else -> "Not checked"
}

private fun transportModeLabel(mode: FirmwareTransportMode): String = when (mode) {
    FirmwareTransportMode.CLI_AND_RPC -> "CLI and RPC"
    FirmwareTransportMode.CLI_ONLY -> "CLI only"
    FirmwareTransportMode.RPC_ONLY -> "RPC only"
    FirmwareTransportMode.PROBING -> "Probing"
    FirmwareTransportMode.UNAVAILABLE -> "Unavailable"
}
