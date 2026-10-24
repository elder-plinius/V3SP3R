package com.vesper.flipper.ui.screen.chat

import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.ImageAttachment
import com.vesper.flipper.domain.model.MessageRole
import com.vesper.flipper.domain.model.MessageStatus
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * One message bubble.
 *
 * Width is capped at 88 percent of the viewport by the caller instead of the
 * old fixed 300dp. Timestamps are drawn by the list only on group boundaries,
 * and the whole bubble copies on long press.
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    showGroupHeader: Boolean,
    isLast: Boolean,
    isLoading: Boolean,
    maxBubbleWidth: Dp,
    ttsSpeakingId: String?,
    ttsLoading: Boolean,
    pendingApproval: PendingApproval?,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    val isAssistant = message.role == MessageRole.ASSISTANT
    val isTool = message.role == MessageRole.TOOL

    val approvedRisk = pendingApproval
        ?.takeIf { it.id == message.metadata?.pendingApprovalId }
        ?.riskAssessment?.level

    val isSpeaking = ttsSpeakingId == message.id
    val showSpeak = isAssistant && message.toolCalls.isNullOrEmpty() && message.content.isNotBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        if (showGroupHeader) {
            Text(
                text = formatMessageTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isUser) 0.dp else 40.dp,
                        bottom = VesperSpace.xxs
                    )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                if (isTool) ToolAvatar() else AssistantAvatar()
                Spacer(Modifier.width(VesperSpace.xs))
            }

            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                val bubbleShape = if (isUser) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
                } else {
                    RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
                }
                Surface(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .then(
                            if (message.content.isNotBlank()) {
                                Modifier.pointerInput(message.id, message.content) {
                                    detectTapGestures(
                                        onLongPress = {
                                            copyToClipboard(context, message.content, "Message")
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        ),
                    shape = bubbleShape,
                    color = when {
                        isUser -> colors.wine
                        isTool -> colors.surface1
                        else -> colors.surface2
                    },
                    border = if (isTool) BorderStroke(1.dp, colors.hairlineSoft) else null
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                    ) {
                        val attachments = message.imageAttachments
                        if (!attachments.isNullOrEmpty()) {
                            MessageAttachments(attachments = attachments)
                        }

                        if (message.content.isNotBlank()) {
                            val announce = message.status == MessageStatus.STREAMING ||
                                (isLast && isLoading && isAssistant)
                            MessageBody(
                                content = message.content,
                                isUser = isUser,
                                announce = announce
                            )
                        }

                        message.toolCalls?.forEach { toolCall ->
                            ToolCallCard(
                                toolCall = toolCall,
                                approvedRisk = approvedRisk
                            )
                        }

                        message.toolResults?.forEach { toolResult ->
                            ToolResultBlock(toolResult = toolResult)
                        }
                    }
                }

                if (message.status == MessageStatus.AWAITING_APPROVAL) {
                    VStatusPill(
                        text = "Awaiting approval",
                        color = colors.riskMedium,
                        icon = Icons.Rounded.Warning,
                        modifier = Modifier.padding(top = VesperSpace.xxs)
                    )
                }

                if ((isUser || isAssistant) && message.content.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
                    ) {
                        IconButton(
                            onClick = { copyToClipboard(context, message.content, "Message") },
                            modifier = Modifier.size(VesperSpace.touchTarget)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy message",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (showSpeak) {
                            IconButton(
                                onClick = {
                                    if (isSpeaking) {
                                        onStopSpeaking()
                                    } else {
                                        onSpeak(message.id, message.content)
                                    }
                                },
                                modifier = Modifier.size(VesperSpace.touchTarget)
                            ) {
                                if (ttsLoading && isSpeaking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .semantics { contentDescription = "Loading audio" },
                                        strokeWidth = 2.dp,
                                        color = colors.gold
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isSpeaking) Icons.Rounded.Stop else Icons.Rounded.VolumeUp,
                                        contentDescription = if (isSpeaking) "Stop reading message" else "Read message aloud",
                                        tint = if (isSpeaking) colors.gold else colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (showRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = colors.riskHigh,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(VesperSpace.xxs))
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.riskHigh
                        )
                    }
                }
            }

            if (isUser) {
                Spacer(Modifier.width(VesperSpace.xs))
                UserAvatar()
            }
        }
    }
}

@Composable
private fun MessageBody(
    content: String,
    isUser: Boolean,
    announce: Boolean
) {
    val colors = Vesper.colors
    val segments = remember(content) { splitMessageBody(content) }
    val textColor = if (isUser) colors.onWine else colors.textPrimary
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (announce) {
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            Modifier
        }
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Prose -> Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
                is MessageSegment.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isUser) colors.winePressed else colors.surface1
                ) {
                    Text(
                        text = segment.text,
                        modifier = Modifier.padding(VesperSpace.xs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (isUser) colors.onWine else colors.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageAttachments(attachments: List<ImageAttachment>) {
    val colors = Vesper.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
    ) {
        attachments.forEachIndexed { index, attachment ->
            // Base64 decode is expensive; never do it on every recomposition.
            val model: Any? = remember(attachment.id) {
                attachment.localUri ?: runCatching {
                    Base64.decode(attachment.base64Data, Base64.NO_WRAP)
                }.getOrNull()
            }
            val description = attachmentDescription(index, attachments.size, attachment)
            if (model != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(model)
                        .crossfade(true)
                        .build(),
                    contentDescription = description,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface1)
                        .semantics { contentDescription = "Attached image unavailable" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun AssistantAvatar(size: Dp = 32.dp) {
    val colors = Vesper.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.wine),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "V",
            fontSize = (size.value * 0.5f).sp,
            fontFamily = FontFamily.Serif,
            color = colors.gold
        )
    }
}

@Composable
private fun ToolAvatar() {
    val colors = Vesper.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.surface3),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Terminal,
            contentDescription = null,
            tint = colors.gold,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun UserAvatar() {
    val colors = Vesper.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface3),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

private sealed interface MessageSegment {
    data class Prose(val text: String) : MessageSegment
    data class Code(val text: String) : MessageSegment
}

/**
 * Splits fenced code out of a message so commands and paths render in the
 * mono face while prose stays in the reading face.
 */
private fun splitMessageBody(content: String): List<MessageSegment> {
    val out = mutableListOf<MessageSegment>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushProse() {
        if (prose.isNotBlank()) out += MessageSegment.Prose(prose.toString().trimEnd())
        prose.clear()
    }

    fun flushCode() {
        if (code.isNotBlank()) out += MessageSegment.Code(code.toString().trimEnd())
        code.clear()
    }

    content.lineSequence().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                flushCode()
            } else {
                flushProse()
            }
            inCode = !inCode
        } else if (inCode) {
            code.appendLine(line)
        } else {
            prose.appendLine(line)
        }
    }

    // An unterminated fence still reads better as code than as prose.
    if (inCode) flushCode() else flushProse()
    if (out.isEmpty()) out += MessageSegment.Prose(content)
    return out
}

private fun attachmentDescription(index: Int, total: Int, attachment: ImageAttachment): String {
    val dimensions = if (attachment.width != null && attachment.height != null) {
        ", " + attachment.width + " by " + attachment.height + " pixels"
    } else {
        ""
    }
    return "Attached image " + (index + 1) + " of " + total + dimensions
}
