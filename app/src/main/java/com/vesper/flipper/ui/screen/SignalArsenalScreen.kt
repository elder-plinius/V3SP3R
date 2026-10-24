package com.vesper.flipper.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.signals.ArsenalSearchField
import com.vesper.flipper.ui.screen.signals.ArsenalTypeFilters
import com.vesper.flipper.ui.screen.signals.CaptureDetailPane
import com.vesper.flipper.ui.screen.signals.CaptureListPane
import com.vesper.flipper.ui.screen.signals.EmptyDetailPane
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ArsenalErrorSource
import com.vesper.flipper.ui.viewmodel.SignalArsenalUiState
import com.vesper.flipper.ui.viewmodel.SignalArsenalViewModel

private val TwoPaneBreakpoint = 600.dp

/**
 * Signal library.
 *
 * Captures are filtered by type and search, then opened in a detail pane
 * that stays beside the list on wide windows and replaces it on phones.
 */
@Composable
fun SignalArsenalScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToDevice: () -> Unit = {},
    viewModel: SignalArsenalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val colors = Vesper.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    VesperScaffold(
        title = "Signal Arsenal",
        subtitle = arsenalSubtitle(state),
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textSecondary
                )
            }
        },
        actions = {
            IconButton(
                onClick = viewModel::refresh,
                modifier = Modifier.semantics {
                    contentDescription =
                        if (state.isRefreshing) "Refreshing captures" else "Refresh captures"
                }
            ) {
                if (state.isRefreshing) {
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
            VErrorBanner(
                message = state.errorMessage.orEmpty(),
                onRetry = when (state.errorSource) {
                    ArsenalErrorSource.LOAD -> viewModel::refresh
                    ArsenalErrorSource.REPLAY -> viewModel::replaySelected
                    ArsenalErrorSource.DELETE -> viewModel::deleteSelected
                },
                onDismiss = viewModel::clearError,
                visible = state.errorMessage != null
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                val twoPane = maxWidth >= TwoPaneBreakpoint
                BackHandler(enabled = !twoPane && state.selected != null) {
                    viewModel.clearSelection()
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Search and filters belong to the list side of the flow.
                    if (twoPane || state.selected == null) {
                        ArsenalSearchField(
                            query = state.query,
                            onQueryChange = viewModel::updateQuery,
                            modifier = Modifier.padding(
                                horizontal = VesperSpace.gutter,
                                vertical = VesperSpace.xs
                            )
                        )
                        ArsenalTypeFilters(
                            selected = state.selectedType,
                            counts = state.counts,
                            onSelect = viewModel::selectType
                        )
                    }

                    if (twoPane) Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        CaptureListPane(
                            state = state,
                            onSelect = viewModel::selectCapture,
                            onRefresh = viewModel::refresh,
                            onGoToDevice = onNavigateToDevice,
                            listState = listState,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(colors.hairline)
                        )
                        if (state.selected != null) {
                            CaptureDetailPane(
                                state = state,
                                closeDescription = "Close detail",
                                onClose = viewModel::clearSelection,
                                onReplay = viewModel::replaySelected,
                                onDelete = viewModel::deleteSelected,
                                onGoToDevice = onNavigateToDevice,
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxHeight()
                            )
                        } else {
                            EmptyDetailPane(
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxHeight()
                            )
                        }
                    } else if (state.selected != null) {
                        CaptureDetailPane(
                            state = state,
                            closeDescription = "Back to captures",
                            onClose = viewModel::clearSelection,
                            onReplay = viewModel::replaySelected,
                            onDelete = viewModel::deleteSelected,
                            onGoToDevice = onNavigateToDevice,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        CaptureListPane(
                            state = state,
                            onSelect = viewModel::selectCapture,
                            onRefresh = viewModel::refresh,
                            onGoToDevice = onNavigateToDevice,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private fun arsenalSubtitle(state: SignalArsenalUiState): String {
    if (state.isLoading) return "Loading captures"
    val count = state.allCaptures.size
    val noun = if (count == 1) "capture" else "captures"
    return "$count ${state.selectedType.displayName} $noun"
}
