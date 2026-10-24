package com.vesper.flipper.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.audit.AuditTimeline
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.AuditFilterType
import com.vesper.flipper.ui.viewmodel.AuditViewModel

private enum class ClearMode {
    ALL,
    OLDER_THAN_WEEK
}

/**
 * Audit as a timeline, not a debug dump. Search and four filters narrow the
 * view; destructive clearing always asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AuditViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    var showActions by remember { mutableStateOf(false) }
    var clearMode by remember { mutableStateOf<ClearMode?>(null) }
    val hasActiveFilters = state.filterType != AuditFilterType.ALL || state.searchQuery.isNotBlank()
    val replayableTraceIds = remember(state.timeline) {
        state.timeline.map { it.traceId }.toSet()
    }

    VesperScaffold(
        title = "Audit log",
        subtitle = if (state.isLoading) {
            "Loading activity"
        } else {
            state.entries.size.toString() + " events"
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            IconButton(onClick = { showActions = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions"
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Clear all") },
                    onClick = {
                        showActions = false
                        clearMode = ClearMode.ALL
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colors.riskHigh
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear older than 7 days") },
                    onClick = {
                        showActions = false
                        clearMode = ClearMode.OLDER_THAN_WEEK
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            state.replayMessage?.let { message ->
                ReplayMessageCard(
                    message = message,
                    onDismiss = { viewModel.clearReplayMessage() }
                )
            }

            state.pendingReplayApproval?.let { approval ->
                ReplayApprovalCard(
                    approval = approval,
                    onApprove = { viewModel.approveReplay() },
                    onReject = { viewModel.rejectReplay() }
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xs),
                placeholder = { Text("Search by path") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.textTertiary
                    )
                },
                trailingIcon = if (state.searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = colors.textSecondary
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = VesperSpace.gutter),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuditFilterType.values().forEach { filter ->
                    VChip(
                        text = filterLabel(filter),
                        selected = state.filterType == filter,
                        onClick = { viewModel.setFilter(filter) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.isLoading -> VSkeletonList(rows = 6)

                    state.entries.isEmpty() && hasActiveFilters -> CenteredAuditState(
                        title = "No entries match",
                        message = "Try a different filter or clear the path search.",
                        actionLabel = "Clear filters",
                        onAction = { viewModel.clearFilters() }
                    )

                    state.entries.isEmpty() -> CenteredAuditState(
                        title = "No activity yet",
                        message = "Commands, approvals, and connection events appear here as you use Vesper.",
                        actionLabel = null,
                        onAction = null
                    )

                    else -> AuditTimeline(
                        entries = state.entries,
                        replayableTraceIds = replayableTraceIds,
                        replayingTraceId = state.replayingTraceId,
                        onReplay = { traceId -> viewModel.replayTrace(traceId) }
                    )
                }
            }
        }
    }

    clearMode?.let { mode ->
        ClearAuditDialog(
            mode = mode,
            onDismiss = { clearMode = null },
            onConfirm = {
                when (mode) {
                    ClearMode.ALL -> viewModel.clearAuditLog()
                    ClearMode.OLDER_THAN_WEEK -> viewModel.clearOldEntries(7)
                }
                clearMode = null
            }
        )
    }
}

@Composable
private fun ReplayMessageCard(message: String, onDismiss: () -> Unit) {
    val colors = Vesper.colors
    VCard(
        level = 1,
        modifier = Modifier.padding(horizontal = VesperSpace.md, vertical = VesperSpace.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = colors.info,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss replay message",
                    tint = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ReplayApprovalCard(
    approval: PendingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val colors = Vesper.colors
    VCard(
        level = 3,
        modifier = Modifier.padding(horizontal = VesperSpace.md, vertical = VesperSpace.xs)
    ) {
        Text(
            text = "Replay needs approval",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Text(
            text = approval.command.action.name.replace('_', ' ').lowercase(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(VesperSpace.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            VPrimaryButton(
                text = "Approve",
                onClick = onApprove,
                modifier = Modifier.weight(1f)
            )
            VSecondaryButton(
                text = "Reject",
                onClick = onReject,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CenteredAuditState(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        VEmptyState(
            title = title,
            message = message,
            icon = Icons.Default.History,
            actionLabel = actionLabel,
            onAction = onAction
        )
    }
}

@Composable
private fun ClearAuditDialog(
    mode: ClearMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = Vesper.colors
    val clearAll = mode == ClearMode.ALL
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = colors.riskHigh
            )
        },
        title = {
            Text(
                text = if (clearAll) "Clear the audit log?" else "Clear older entries?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = if (clearAll) {
                    "This permanently removes every audit event from this device. It cannot be undone."
                } else {
                    "This permanently removes every event older than 7 days. It cannot be undone."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (clearAll) "Clear all" else "Clear older",
                    color = colors.riskHigh
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

private fun filterLabel(filter: AuditFilterType): String = when (filter) {
    AuditFilterType.ALL -> "All"
    AuditFilterType.WRITES -> "Writes"
    AuditFilterType.HIGH_RISK -> "High risk"
    AuditFilterType.DENIED -> "Denied"
}
