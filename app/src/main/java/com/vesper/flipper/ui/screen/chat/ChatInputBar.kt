package com.vesper.flipper.ui.screen.chat

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vesper.flipper.domain.model.ImageAttachment
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.ChatVoiceState

/**
 * Composer. Every icon-only control is a 48dp target, images carry a real
 * content description, and the send slot becomes Stop while a turn runs.
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    canStop: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    pendingImages: List<ImageAttachment>,
    isProcessingImage: Boolean,
    onRemoveImage: (String) -> Unit,
    onAttach: () -> Unit,
    showAttachMenu: Boolean,
    onDismissAttachMenu: () -> Unit,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    voiceState: ChatVoiceState,
    voicePartialText: String,
    voiceError: String?,
    onVoiceInput: () -> Unit,
    onStopVoice: () -> Unit,
    glassesConnected: Boolean,
    glassesMuted: Boolean,
    onToggleGlassesMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val isListening = voiceState == ChatVoiceState.LISTENING
    val isProcessingVoice = voiceState == ChatVoiceState.PROCESSING
    val hasContent = value.isNotBlank() || pendingImages.isNotEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.canvas
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            VHairline()

            AnimatedVisibility(
                visible = isListening || isProcessingVoice,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                VoiceIndicator(
                    isListening = isListening,
                    partialText = voicePartialText,
                    onStop = onStopVoice
                )
            }

            AnimatedVisibility(
                visible = pendingImages.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = VesperSpace.md, vertical = VesperSpace.xs),
                    horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                ) {
                    pendingImages.forEachIndexed { index, image ->
                        PendingImageChip(
                            image = image,
                            index = index,
                            total = pendingImages.size,
                            onRemove = { onRemoveImage(image.id) }
                        )
                    }
                }
            }

            if (voiceError != null) {
                Text(
                    text = voiceError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.riskHigh,
                    modifier = Modifier.padding(horizontal = VesperSpace.md, vertical = VesperSpace.xxs)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VesperSpace.md, vertical = VesperSpace.xs),
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    IconButton(
                        onClick = onAttach,
                        enabled = enabled && !isProcessingImage && !isListening,
                        modifier = Modifier.size(VesperSpace.touchTarget)
                    ) {
                        if (isProcessingImage) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "Processing attachment" },
                                strokeWidth = 2.dp,
                                color = colors.gold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = "Attach a photo or video",
                                tint = colors.rose,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = onDismissAttachMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text("Choose from gallery") },
                            onClick = onPickFromGallery,
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Take a photo") },
                            onClick = onTakePhoto,
                            leadingIcon = { Icon(Icons.Rounded.CameraAlt, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Record a video") },
                            onClick = onRecordVideo,
                            leadingIcon = { Icon(Icons.Rounded.Videocam, contentDescription = null) }
                        )
                    }
                }

                if (glassesConnected) {
                    IconButton(
                        onClick = onToggleGlassesMute,
                        modifier = Modifier.size(VesperSpace.touchTarget)
                    ) {
                        Icon(
                            imageVector = if (glassesMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (glassesMuted) "Unmute glasses microphone" else "Mute glasses microphone",
                            tint = if (glassesMuted) colors.textTertiary else colors.gold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (isListening) onStopVoice() else onVoiceInput()
                        },
                        enabled = enabled && !isProcessingImage,
                        modifier = Modifier.size(VesperSpace.touchTarget)
                    ) {
                        if (isProcessingVoice) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "Processing voice input" },
                                strokeWidth = 2.dp,
                                color = colors.gold
                            )
                        } else {
                            Icon(
                                imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = if (isListening) "Stop voice input" else "Start voice input",
                                tint = if (isListening) colors.riskHigh else colors.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = VesperSpace.xxs),
                    placeholder = {
                        Text(
                            text = when {
                                isListening -> "Listening"
                                pendingImages.isNotEmpty() -> "Add a message"
                                else -> "Ask about your Flipper"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary
                        )
                    },
                    enabled = enabled && !isListening,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (hasContent) onSend() }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.gold,
                        unfocusedBorderColor = colors.hairline,
                        disabledBorderColor = colors.hairlineSoft,
                        focusedContainerColor = colors.surface2,
                        unfocusedContainerColor = colors.surface2,
                        disabledContainerColor = colors.surface1,
                        cursorColor = colors.gold,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        disabledTextColor = colors.textTertiary
                    ),
                    maxLines = 4
                )

                if (canStop) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(VesperSpace.touchTarget),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.surface3,
                            contentColor = colors.riskHigh
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop response",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && hasContent && !isListening,
                        modifier = Modifier.size(VesperSpace.touchTarget),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.wine,
                            contentColor = colors.onWine,
                            disabledContainerColor = colors.surface2,
                            disabledContentColor = colors.textTertiary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = "Sending message" },
                                strokeWidth = 2.dp,
                                color = colors.onWine
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Send,
                                contentDescription = "Send message",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceIndicator(
    isListening: Boolean,
    partialText: String,
    onStop: () -> Unit
) {
    val colors = Vesper.colors
    Surface(color = colors.surface2) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = VesperSpace.md, top = VesperSpace.xs, bottom = VesperSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isListening) Icons.Rounded.Mic else Icons.Rounded.HourglassTop,
                contentDescription = null,
                tint = colors.gold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isListening) "Listening" else "Processing speech",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.gold
                )
                if (partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(VesperSpace.touchTarget)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel voice input",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PendingImageChip(
    image: ImageAttachment,
    index: Int,
    total: Int,
    onRemove: () -> Unit
) {
    val colors = Vesper.colors
    val context = LocalContext.current
    val model: Any? = remember(image.id) {
        image.localUri ?: runCatching {
            Base64.decode(image.base64Data, Base64.NO_WRAP)
        }.getOrNull()
    }
    val description = "Attached image " + (index + 1) + " of " + total

    Box(modifier = Modifier.size(76.dp)) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .build(),
                contentDescription = description,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface3),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = "Attached image unavailable",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 48dp removal target; the visible disc stays small inside it.
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(VesperSpace.touchTarget)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.riskHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove attached image " + (index + 1),
                    tint = colors.onWine,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
