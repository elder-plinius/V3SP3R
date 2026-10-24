package com.vesper.flipper.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.data.database.ChatSessionSummary
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Saved conversations. Relative dates, a current-thread marker, and an explicit
 * confirmation before anything is deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearConversation: () -> Unit,
    onNewThread: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sessionToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface3
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                top = VesperSpace.xs,
                bottom = VesperSpace.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            item(key = "history-header") {
                HistoryHeader(onNewThread = onNewThread)
            }

            if (sessions.isEmpty()) {
                item(key = "history-empty") {
                    VEmptyState(
                        title = "No saved chats",
                        message = "Conversations are saved on this phone as you chat. Start one and it will appear here.",
                        icon = Icons.Rounded.History
                    )
                }
            } else {
                items(
                    items = sessions,
                    key = { session -> session.sessionId },
                    contentType = { "session" }
                ) { session ->
                    SessionRow(
                        session = session,
                        isCurrent = session.sessionId == currentSessionId,
                        onSelect = { onSelectSession(session.sessionId) },
                        onDelete = { sessionToDelete = session.sessionId }
                    )
                }

                item(key = "history-clear") {
                    Spacer(modifier = Modifier.height(VesperSpace.sm))
                    VHairline()
                    VListRow(
                        title = "Clear current chat",
                        subtitle = "Deletes this conversation and starts a new one",
                        icon = Icons.Rounded.DeleteSweep,
                        destructive = true,
                        onClick = { confirmClear = true }
                    )
                }
            }
        }
    }

    sessionToDelete?.let { sessionId ->
        ConfirmDialog(
            title = "Delete this conversation?",
            message = "It will be removed from chat history. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                sessionToDelete = null
                onDeleteSession(sessionId)
            },
            onDismiss = { sessionToDelete = null }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this chat?",
            message = "Every message in this conversation will be deleted from chat history. This cannot be undone.",
            confirmLabel = "Clear chat",
            onConfirm = {
                confirmClear = false
                onClearConversation()
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun HistoryHeader(onNewThread: () -> Unit) {
    val colors = Vesper.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VesperSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Chat history",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "Saved on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        FilledIconButton(
            onClick = onNewThread,
            modifier = Modifier.size(VesperSpace.touchTarget),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.wine,
                contentColor = colors.onWine
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Start a new chat",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = Vesper.colors
    val relativeDate = formatRelativeTime(session.lastTimestamp)
    VCard(
        level = if (isCurrent) 2 else 1,
        onClick = onSelect,
        contentPadding = PaddingValues(
            start = VesperSpace.sm,
            end = 0.dp,
            top = VesperSpace.xxs,
            bottom = VesperSpace.xxs
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isCurrent) colors.gold else colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relativeDate,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = session.messageCount.toString() + " messages",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
            if (isCurrent) {
                VStatusPill(text = "Current", color = colors.gold)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(VesperSpace.touchTarget)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete conversation from " + relativeDate,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = colors.riskHigh
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
            ) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.riskHigh
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textSecondary
                )
            }
        }
    )
}
