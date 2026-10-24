package com.vesper.flipper.ui.screen.signals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ArsenalCapture
import com.vesper.flipper.ui.viewmodel.SignalArsenalUiState

/**
 * Capture list with skeleton, disconnected, empty, and error states.
 * The list itself owns the scroll so the detail pane can scroll apart.
 */
@Composable
fun CaptureListPane(
    state: SignalArsenalUiState,
    onSelect: (ArsenalCapture) -> Unit,
    onRefresh: () -> Unit,
    onGoToDevice: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    when {
        state.isLoading -> {
            VSkeletonList(rows = 7, modifier = modifier)
        }

        state.captures.isEmpty() -> {
            CaptureEmptyState(
                state = state,
                onRefresh = onRefresh,
                onGoToDevice = onGoToDevice,
                modifier = modifier
            )
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = VesperSpace.gutter,
                    end = VesperSpace.gutter,
                    top = VesperSpace.xs,
                    bottom = VesperSpace.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
            ) {
                items(state.captures, key = { it.signal.path }) { capture ->
                    CaptureRow(
                        capture = capture,
                        selected = state.selected?.signal?.path == capture.signal.path,
                        onClick = { onSelect(capture) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureEmptyState(
    state: SignalArsenalUiState,
    onRefresh: () -> Unit,
    onGoToDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = signalTypeIcon(state.selectedType)

    val title: String
    val message: String
    val actionLabel: String
    val onAction: () -> Unit
    val secondaryLabel: String?
    val onSecondary: (() -> Unit)?

    when {
        state.errorMessage != null -> {
            title = "Captures unavailable"
            message = "Retry when the Flipper is connected."
            actionLabel = "Retry"
            onAction = onRefresh
            secondaryLabel = null
            onSecondary = null
        }

        !state.isDeviceConnected -> {
            title = "No Flipper connected"
            message = "Connect in Device to browse the captures stored on the Flipper."
            actionLabel = "Go to Device"
            onAction = onGoToDevice
            secondaryLabel = "Refresh"
            onSecondary = onRefresh
        }

        else -> {
            title = "No captures yet"
            message = "Record on the Flipper, then refresh."
            actionLabel = "Refresh"
            onAction = onRefresh
            secondaryLabel = null
            onSecondary = null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        VEmptyState(
            title = title,
            message = message,
            icon = icon,
            actionLabel = actionLabel,
            onAction = onAction,
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary
        )
    }
}

@Composable
private fun CaptureRow(
    capture: ArsenalCapture,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Vesper.colors
    val signal = capture.signal
    val meta = listOfNotNull(
        formatCaptureDate(capture.modifiedAt),
        formatDuration(capture.durationMs)
    ).joinToString(" · ")

    VCard(
        level = if (selected) 3 else 2,
        onClick = onClick,
        contentPadding = PaddingValues(
            horizontal = VesperSpace.sm,
            vertical = VesperSpace.sm
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = signalTypeIcon(signal.type),
                contentDescription = null,
                tint = if (selected) colors.gold else colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = signal.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(VesperSpace.xxs))
        Text(
            text = signalSubtitle(signal),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(VesperSpace.xxs))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
