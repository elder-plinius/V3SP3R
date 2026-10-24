package com.vesper.flipper.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.AgentProgress
import com.vesper.flipper.domain.model.AgentProgressStage
import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.MessageRole
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.coroutines.launch

/**
 * The transcript.
 *
 * Auto-follow is latched from scroll position only while the item count is
 * stable, so adding a message does not get mistaken for the user scrolling
 * away. When the reader is above the fold the list stops moving and offers a
 * "Latest" pill instead of yanking the viewport.
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    sessionId: String,
    isLoading: Boolean,
    canStop: Boolean,
    progress: AgentProgress?,
    pendingApproval: PendingApproval?,
    ttsSpeakingId: String?,
    ttsLoading: Boolean,
    showRetryOnLast: Boolean,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxBubbleWidth = maxWidth * 0.88f
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        var autoFollow by rememberSaveable { mutableStateOf(true) }
        var lastKnownTotal by remember { mutableIntStateOf(0) }

        LaunchedEffect(listState) {
            // Only scroll position updates (count unchanged) may flip the latch.
            // Layout changes caused by new messages keep the previous intent.
            androidx.compose.runtime.snapshotFlow {
                listState.layoutInfo.totalItemsCount to
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }.collect { (total, lastIndex) ->
                if (total == lastKnownTotal) {
                    autoFollow = total == 0 || (lastIndex ?: -1) >= total - 1
                }
                lastKnownTotal = total
            }
        }

        // Switching conversations should start at the latest message even if
        // the reader had scrolled up in the previous one.
        LaunchedEffect(sessionId) {
            if (messages.isNotEmpty()) {
                autoFollow = true
                val target = if (isLoading) messages.size else messages.lastIndex
                if (target >= 0) listState.scrollToItem(target)
            }
        }

        LaunchedEffect(messages.size, isLoading) {
            if (messages.isEmpty()) return@LaunchedEffect
            val lastIsUser = messages.lastOrNull()?.role == MessageRole.USER
            if (!autoFollow && !lastIsUser) return@LaunchedEffect
            val target = if (isLoading) messages.size else messages.lastIndex
            if (target >= 0) {
                listState.animateScrollToItem(target)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                top = VesperSpace.md,
                bottom = VesperSpace.lg
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            itemsIndexed(
                items = messages,
                key = { _, message -> message.id },
                contentType = { _, message -> message.role }
            ) { index, message ->
                val previous = messages.getOrNull(index - 1)
                ChatMessageItem(
                    message = message,
                    showGroupHeader = isGroupBoundary(previous, message),
                    isLast = index == messages.lastIndex,
                    isLoading = isLoading,
                    maxBubbleWidth = maxBubbleWidth,
                    ttsSpeakingId = ttsSpeakingId,
                    ttsLoading = ttsLoading,
                    pendingApproval = pendingApproval,
                    showRetry = showRetryOnLast && index == messages.lastIndex,
                    onRetry = onRetry,
                    onSpeak = onSpeak,
                    onStopSpeaking = onStopSpeaking
                )
            }

            if (isLoading) {
                item(key = "chat-thinking-row", contentType = "thinking") {
                    ChatThinkingRow(
                        progress = progress,
                        canStop = canStop,
                        onStop = onStop
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !autoFollow && messages.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(VesperSpace.md),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FilledTonalButton(
                onClick = {
                    autoFollow = true
                    scope.launch {
                        val target = if (isLoading) messages.size else messages.lastIndex
                        if (target >= 0) listState.animateScrollToItem(target)
                    }
                },
                modifier = Modifier
                    .heightIn(min = VesperSpace.touchTarget)
                    .semantics { contentDescription = "Jump to latest message" },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Vesper.colors.surface3,
                    contentColor = Vesper.colors.textPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Text("Latest", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Compact running row. Announces progress politely for TalkBack and keeps a
 * Stop control within reach without leaving the transcript.
 */
@Composable
private fun ChatThinkingRow(
    progress: AgentProgress?,
    canStop: Boolean,
    onStop: () -> Unit
) {
    val colors = Vesper.colors
    val label = progress?.detail?.takeIf { it.isNotBlank() } ?: when (progress?.stage) {
        AgentProgressStage.MODEL_REQUEST -> "Contacting the model"
        AgentProgressStage.TOOL_PLANNED -> "Preparing the next tool call"
        AgentProgressStage.TOOL_EXECUTING -> "Running the tool on your Flipper"
        AgentProgressStage.TOOL_COMPLETED -> "Reading the result"
        AgentProgressStage.WAITING_APPROVAL -> "Waiting for your approval"
        null -> "Working"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistantAvatar()
        Spacer(Modifier.width(VesperSpace.xs))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface2,
            border = BorderStroke(1.dp, colors.hairlineSoft)
        ) {
            Row(
                modifier = Modifier.padding(
                    start = VesperSpace.sm,
                    end = VesperSpace.xs,
                    top = VesperSpace.xs,
                    bottom = VesperSpace.xs
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.gold
                )
                Spacer(Modifier.width(VesperSpace.xs))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (canStop) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.rose
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown after the user stops a turn. The last user message is still in the
 * transcript, so Retry resubmits it instead of starting over.
 */
@Composable
fun StoppedTurnBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.hairlineSoft)
    ) {
        Row(
            modifier = Modifier.padding(start = VesperSpace.sm, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.StopCircle,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Response stopped",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "Your message was kept. Retry when you are ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.rose
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(VesperSpace.touchTarget)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dismiss stopped response",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Persistent entry point back to a dismissed approval sheet. Dismissing the
 * sheet never rejects the command, so the decision must stay reachable.
 */
@Composable
fun ApprovalReminder(
    onReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    VCard(
        modifier = modifier,
        level = 2,
        onClick = onReview,
        contentPadding = PaddingValues(
            horizontal = VesperSpace.md,
            vertical = VesperSpace.sm
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = colors.riskMedium,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Approval needed",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "A command is waiting for your decision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Text(
                text = "Review",
                style = MaterialTheme.typography.titleSmall,
                color = colors.rose
            )
        }
    }
}
