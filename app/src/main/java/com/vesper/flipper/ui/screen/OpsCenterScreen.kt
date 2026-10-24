package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.device.CommandAutomationCard
import com.vesper.flipper.ui.screen.ops.AutotuneCard
import com.vesper.flipper.ui.screen.ops.DiagnosticsChecksCard
import com.vesper.flipper.ui.screen.ops.FirmwareCompatibilityCard
import com.vesper.flipper.ui.screen.ops.PipelineHealthCard
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.DeviceViewModel

/**
 * Diagnostics-only sibling of the Device tab. Runbooks come from the shared
 * CommandAutomationCard so there is exactly one implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpsCenterScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: DeviceViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val cliStatus by viewModel.cliCapabilityStatus.collectAsState()
    val diagnostics by viewModel.connectionDiagnostics.collectAsState()
    val autotuneStatus by viewModel.autotuneStatus.collectAsState()
    val firmwareCompatibility by viewModel.firmwareCompatibility.collectAsState()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsState()

    VesperScaffold(
        title = "Ops Center",
        subtitle = "Pipeline health and link checks",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            IconButton(
                onClick = { viewModel.runConnectionDiagnostics() },
                enabled = !isRunningDiagnostics
            ) {
                if (isRunningDiagnostics) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.gold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Run link diagnostics"
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                top = VesperSpace.md,
                bottom = VesperSpace.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.md)
        ) {
            item(key = "pipeline") {
                PipelineHealthCard(
                    connectionState = connectionState,
                    connectedName = connectedDevice?.name,
                    cliStatus = cliStatus,
                    diagnostics = diagnostics,
                    autotuneStatus = autotuneStatus,
                    firmwareCompatibility = firmwareCompatibility,
                    isRunningDiagnostics = isRunningDiagnostics,
                    onRunDiagnostics = { viewModel.runConnectionDiagnostics() },
                    onRefreshInfo = { viewModel.refreshDeviceInfo() }
                )
            }

            item(key = "runbooks") {
                CommandAutomationCard(
                    viewModel = viewModel,
                    showMacros = false
                )
            }

            item(key = "autotune") {
                AutotuneCard(autotuneStatus = autotuneStatus)
            }

            item(key = "firmware") {
                FirmwareCompatibilityCard(firmwareCompatibility = firmwareCompatibility)
            }

            item(key = "checks") {
                DiagnosticsChecksCard(
                    diagnostics = diagnostics,
                    isRunningDiagnostics = isRunningDiagnostics,
                    onRunDiagnostics = { viewModel.runConnectionDiagnostics() }
                )
            }
        }
    }
}
