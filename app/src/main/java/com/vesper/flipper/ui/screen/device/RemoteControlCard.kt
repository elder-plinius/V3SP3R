package com.vesper.flipper.ui.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FlipperRemoteButton
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Physical control for the connected Flipper. Every key is 56dp, every key
 * gives haptic feedback, and the label is the only thing read out loud.
 */
@Composable
fun RemoteControlCard(
    isSendingRemoteInput: Boolean,
    statusText: String?,
    onSendButton: (FlipperRemoteButton, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val haptics = LocalHapticFeedback.current
    val enabled = !isSendingRemoteInput

    fun send(button: FlipperRemoteButton, longPress: Boolean = false) {
        haptics.performHapticFeedback(
            if (longPress) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
        )
        onSendButton(button, longPress)
    }

    VCard(modifier = modifier, level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surface3),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Gamepad,
                    contentDescription = null,
                    tint = colors.gold,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Remote control",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "Send a key press without touching the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.md))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            RemoteKey(
                icon = Icons.Default.KeyboardArrowUp,
                label = "Up",
                enabled = enabled,
                onClick = { send(FlipperRemoteButton.UP) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
                RemoteKey(
                    icon = Icons.Default.KeyboardArrowLeft,
                    label = "Left",
                    enabled = enabled,
                    onClick = { send(FlipperRemoteButton.LEFT) }
                )
                RemoteKey(
                    label = "OK",
                    enabled = enabled,
                    emphasized = true,
                    onClick = { send(FlipperRemoteButton.OK) }
                )
                RemoteKey(
                    icon = Icons.Default.KeyboardArrowRight,
                    label = "Right",
                    enabled = enabled,
                    onClick = { send(FlipperRemoteButton.RIGHT) }
                )
            }
            RemoteKey(
                icon = Icons.Default.KeyboardArrowDown,
                label = "Down",
                enabled = enabled,
                onClick = { send(FlipperRemoteButton.DOWN) }
            )
        }

        Spacer(Modifier.height(VesperSpace.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)
        ) {
            RemoteWideKey(
                icon = Icons.Default.ArrowBack,
                label = "Back",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { send(FlipperRemoteButton.BACK) }
            )
            RemoteWideKey(
                icon = Icons.Default.Lock,
                label = "Lock",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { send(FlipperRemoteButton.BACK, longPress = true) }
            )
        }

        Text(
            text = "Lock sends a long press on Back, the same gesture the Flipper uses to lock.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = VesperSpace.xs)
        )

        if (isSendingRemoteInput) {
            Spacer(Modifier.height(VesperSpace.sm))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = colors.gold,
                trackColor = colors.hairline
            )
        }

        if (!statusText.isNullOrBlank()) {
            Spacer(Modifier.height(VesperSpace.sm))
            VHairline()
            Spacer(Modifier.height(VesperSpace.xs))
            val failed = statusText.contains("failed", ignoreCase = true) ||
                statusText.contains("Connect a Flipper", ignoreCase = true)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) colors.riskHigh else colors.textSecondary
            )
        }
    }
}

@Composable
private fun RemoteKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasized: Boolean = false
) {
    val colors = Vesper.colors
    val background = if (emphasized) colors.wine else colors.surface3
    val content = if (emphasized) colors.onWine else colors.textPrimary
    Box(
        modifier = modifier
            .size(56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (emphasized) colors.wine else colors.hairline, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = content,
                modifier = Modifier.size(26.dp)
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = content
            )
        }
    }
}

@Composable
private fun RemoteWideKey(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface3)
            .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = VesperSpace.sm),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
        }
    }
}
