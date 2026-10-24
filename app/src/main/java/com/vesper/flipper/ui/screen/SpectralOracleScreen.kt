package com.vesper.flipper.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.oracle.OraclePickCaptureStep
import com.vesper.flipper.ui.screen.oracle.OraclePickTypeStep
import com.vesper.flipper.ui.screen.oracle.OracleRawDialog
import com.vesper.flipper.ui.screen.oracle.OracleResultPane
import com.vesper.flipper.ui.screen.oracle.OracleRunningStep
import com.vesper.flipper.ui.screen.oracle.OracleStepIndicator
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.OracleStep
import com.vesper.flipper.ui.viewmodel.SpectralOracleUiState
import com.vesper.flipper.ui.viewmodel.SpectralOracleViewModel

/**
 * Signal intelligence flow: pick a capture, pick an analysis, run it, read
 * the report. Each step is its own pane, and the running step can be
 * cancelled from the button, the back arrow, or the system back gesture.
 */
@Composable
fun SpectralOracleScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToDevice: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: SpectralOracleViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val colors = Vesper.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val captureListState = rememberLazyListState()
    var showRawResponse by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshApiKeyStatus()
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    BackHandler(enabled = state.isAnalyzing || state.step != OracleStep.PICK_CAPTURE) {
        if (state.isAnalyzing) {
            viewModel.cancelAnalysis()
        } else {
            viewModel.previousStep()
        }
    }

    val bannerMessage = state.errorMessage ?: state.loadError
    val bannerRetry: (() -> Unit)? = when {
        state.errorMessage != null && state.hasApiKey == false -> null
        state.errorMessage != null -> viewModel::analyze
        else -> viewModel::loadCaptures
    }

    VesperScaffold(
        title = "Spectral Oracle",
        subtitle = oracleSubtitle(state),
        navigationIcon = {
            IconButton(
                onClick = {
                    when {
                        state.isAnalyzing -> viewModel.cancelAnalysis()
                        state.step == OracleStep.PICK_CAPTURE -> onNavigateBack()
                        else -> viewModel.previousStep()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = if (state.isAnalyzing) "Cancel analysis" else "Back",
                    tint = colors.textSecondary
                )
            }
        },
        actions = {
            if (state.step == OracleStep.PICK_CAPTURE) {
                IconButton(
                    onClick = viewModel::loadCaptures,
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (state.isLoadingCaptures) "Refreshing captures" else "Refresh captures"
                    }
                ) {
                    if (state.isLoadingCaptures) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.gold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    containerColor = colors.surface3,
                    contentColor = colors.textPrimary,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Static replacement for the old rotating backdrop: a single
            // wine-to-gold rule that carries the Oracle accent.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.wine.copy(alpha = 0.55f),
                                colors.gold.copy(alpha = 0.45f),
                                colors.wine.copy(alpha = 0.55f)
                            )
                        )
                    )
            )

            OracleStepIndicator(
                step = state.step,
                modifier = Modifier.padding(
                    horizontal = VesperSpace.gutter,
                    vertical = VesperSpace.xs
                )
            )

            VErrorBanner(
                message = bannerMessage.orEmpty(),
                onRetry = bannerRetry,
                onDismiss = viewModel::clearError,
                visible = bannerMessage != null
            )

            when (state.step) {
                OracleStep.PICK_CAPTURE -> OraclePickCaptureStep(
                    state = state,
                    onSelect = viewModel::selectCapture,
                    onRefresh = viewModel::loadCaptures,
                    onGoToDevice = onNavigateToDevice,
                    listState = captureListState,
                    modifier = Modifier.weight(1f)
                )

                OracleStep.PICK_TYPE -> OraclePickTypeStep(
                    state = state,
                    onSelectType = viewModel::setAnalysisType,
                    onAnalyze = viewModel::analyze,
                    onBackToCapture = viewModel::previousStep,
                    onGoToSettings = onNavigateToSettings,
                    modifier = Modifier.weight(1f)
                )

                OracleStep.RUNNING -> OracleRunningStep(
                    state = state,
                    onCancel = viewModel::cancelAnalysis,
                    modifier = Modifier.weight(1f)
                )

                OracleStep.RESULT -> {
                    val result = state.result
                    if (result != null) {
                        OracleResultPane(
                            result = result,
                            onShowRaw = { showRawResponse = true },
                            onRunAgain = viewModel::analyze,
                            onNewCapture = viewModel::startOver,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            VEmptyState(
                                title = "No result",
                                message = "The report is missing. Run the analysis again.",
                                actionLabel = "Run analysis",
                                onAction = viewModel::analyze
                            )
                        }
                    }
                }
            }
        }
    }

    val result = state.result
    if (showRawResponse && result != null) {
        OracleRawDialog(
            response = result.rawResponse,
            onDismiss = { showRawResponse = false }
        )
    }
}

private fun oracleSubtitle(state: SpectralOracleUiState): String = when (state.step) {
    OracleStep.PICK_CAPTURE -> "Step 1 of 3: pick a capture"
    OracleStep.PICK_TYPE -> "Step 2 of 3: choose the analysis"
    OracleStep.RUNNING -> "Step 3 of 3: running the analysis"
    OracleStep.RESULT -> "Step 3 of 3: result"
}
