package com.vesper.flipper.ui.screen.oracle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.AnalysisType
import com.vesper.flipper.domain.model.SignalCapture
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.screen.signals.signalSubtitle
import com.vesper.flipper.ui.screen.signals.signalTypeIcon
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.OracleStep
import com.vesper.flipper.ui.viewmodel.SpectralOracleUiState

/** Three-part progress rail across the top of the Oracle. */
@Composable
fun OracleStepIndicator(
    step: OracleStep,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val labels = listOf("Capture", "Analysis", "Result")
    val activeIndex = when (step) {
        OracleStep.PICK_CAPTURE -> 0
        OracleStep.PICK_TYPE, OracleStep.RUNNING -> 1
        OracleStep.RESULT -> 2
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
    ) {
        labels.forEachIndexed { index, label ->
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (index <= activeIndex) colors.gold else colors.hairline)
                )
                Spacer(Modifier.height(VesperSpace.xxs))
                Text(
                    text = "${index + 1} $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == activeIndex) colors.textPrimary else colors.textTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Step 1: choose the Sub-GHz capture to analyze. */
@Composable
fun OraclePickCaptureStep(
    state: SpectralOracleUiState,
    onSelect: (SignalCapture) -> Unit,
    onRefresh: () -> Unit,
    onGoToDevice: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val colors = Vesper.colors

    Column(modifier = modifier.fillMaxSize()) {
        VSectionHeader(
            title = "Pick a capture",
            eyebrow = "SUB-GHZ",
            modifier = Modifier.padding(horizontal = VesperSpace.gutter)
        )
        Text(
            text = "The Oracle analyzes Sub-GHz captures stored on the Flipper.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = VesperSpace.gutter)
        )

        when {
            state.isLoadingCaptures -> {
                Spacer(Modifier.height(VesperSpace.md))
                VSkeletonList(rows = 6)
            }

            state.captures.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = VesperSpace.gutter),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isDeviceConnected) {
                        VEmptyState(
                            title = "No captures yet",
                            message = "Record on the Flipper, then refresh.",
                            icon = Icons.Rounded.Sensors,
                            actionLabel = "Refresh",
                            onAction = onRefresh
                        )
                    } else {
                        VEmptyState(
                            title = "No Flipper connected",
                            message = "Connect in Device to load the Sub-GHz captures.",
                            icon = Icons.Rounded.Sensors,
                            actionLabel = "Go to Device",
                            onAction = onGoToDevice,
                            secondaryLabel = "Refresh",
                            onSecondary = onRefresh
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = VesperSpace.gutter,
                        end = VesperSpace.gutter,
                        top = VesperSpace.xs,
                        bottom = VesperSpace.md
                    ),
                    verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                ) {
                    items(state.captures, key = { it.path }) { signal ->
                        VCard(level = 2, onClick = { onSelect(signal) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = signalTypeIcon(signal.type),
                                    contentDescription = null,
                                    tint = colors.gold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(VesperSpace.xs))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = signal.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = signalSubtitle(signal),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.textTertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Step 2: choose what the Oracle should look for. */
@Composable
fun OraclePickTypeStep(
    state: SpectralOracleUiState,
    onSelectType: (AnalysisType) -> Unit,
    onAnalyze: () -> Unit,
    onBackToCapture: () -> Unit,
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val capture = state.selected
    val missingKey = state.hasApiKey == false

    Column(modifier = modifier.fillMaxSize()) {
        VSectionHeader(
            title = "Analysis type",
            actionLabel = "Change capture",
            onAction = onBackToCapture,
            modifier = Modifier.padding(horizontal = VesperSpace.gutter)
        )

        if (capture != null) {
            VCard(
                level = 1,
                modifier = Modifier.padding(horizontal = VesperSpace.gutter)
            ) {
                Text(
                    text = capture.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = signalSubtitle(capture),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                top = VesperSpace.sm,
                bottom = VesperSpace.md
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            items(AnalysisType.entries, key = { it.name }) { type ->
                AnalysisTypeRow(
                    type = type,
                    selected = type == state.analysisType,
                    onClick = { onSelectType(type) }
                )
            }
        }

        if (missingKey) {
            VCard(
                level = 1,
                modifier = Modifier.padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xs)
            ) {
                Text(
                    text = "An OpenRouter API key is required",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "Add one in Settings, then run the analysis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(VesperSpace.xs))
                VSecondaryButton(
                    text = "Open Settings",
                    onClick = onGoToSettings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        VPrimaryButton(
            text = "Run analysis",
            onClick = onAnalyze,
            enabled = capture != null && !missingKey,
            modifier = Modifier
                .padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xs)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun AnalysisTypeRow(
    type: AnalysisType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Vesper.colors
    VCard(level = if (selected) 3 else 2, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = analysisTypeIcon(type),
                contentDescription = null,
                tint = if (selected) colors.gold else colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = analysisTypeLabel(type),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = analysisTypeDescription(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Spacer(Modifier.width(VesperSpace.xs))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = colors.gold,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Step 3: the request is in flight and can be cancelled. */
@Composable
fun OracleRunningStep(
    state: SpectralOracleUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val stage = state.stage

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VesperSpace.gutter)
    ) {
        Spacer(Modifier.height(VesperSpace.lg))
        Text(
            text = "Analyzing",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(VesperSpace.xxs))
        Text(
            text = stage?.label ?: "Working",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(VesperSpace.sm))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = colors.gold,
            trackColor = colors.surface2
        )
        Spacer(Modifier.height(VesperSpace.lg))
        VSkeletonList(rows = 4)
        Spacer(Modifier.height(VesperSpace.lg))
        VSecondaryButton(
            text = "Cancel analysis",
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(VesperSpace.xxl))
    }
}
