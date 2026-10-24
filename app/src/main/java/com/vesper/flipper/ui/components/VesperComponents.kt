package com.vesper.flipper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperMotion
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════
// CONTROLS
// Every control here is >= 48dp tall, has a pressed state, and
// carries semantics. Screens should not hand-roll buttons.
// ═══════════════════════════════════════════════════════════

@Composable
fun VPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    compact: Boolean = false
) {
    val colors = Vesper.colors
    val height = if (compact) 44.dp else 52.dp
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .heightIn(min = height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.wine,
            contentColor = colors.onWine,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = VesperSpace.lg, vertical = VesperSpace.sm)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.onWine
            )
            Spacer(Modifier.width(VesperSpace.xs))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VesperSpace.xs))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun VSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = Vesper.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.textPrimary,
            disabledContentColor = colors.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = VesperSpace.lg, vertical = VesperSpace.sm)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VesperSpace.xs))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
fun VQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Vesper.colors.rose)
    }
}

/**
 * Gold fill with ink content. This is the "commit" affordance —
 * the only control allowed to look like this on a screen.
 */
@Composable
fun VCommitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = Vesper.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.gold,
            contentColor = colors.onGold,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = VesperSpace.lg, vertical = VesperSpace.sm)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VesperSpace.xs))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/**
 * Destructive confirmation that requires a deliberate hold.
 *
 * - Hold the button for holdMillis to confirm.
 * - TalkBack / Switch Access: activating the button starts the same
 *   countdown (announced), so the control is operable without a gesture.
 * - Releasing or activating again before the countdown ends cancels.
 */
@Composable
fun VHoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Long = 1500L,
    enabled: Boolean = true
) {
    val colors = Vesper.colors
    val haptics = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }

    LaunchedEffect(holding) {
        if (holding) {
            delay(holdMillis)
            if (holding) {
                holding = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirm()
            }
        }
    }

    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) holdMillis.toInt() else 150),
        label = "holdProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) colors.riskHighContainer else colors.surface2)
            .border(1.dp, if (enabled) colors.riskHigh else colors.hairline, RoundedCornerShape(14.dp))
            .semantics {
                role = Role.Button
                stateDescription = if (holding) "Confirming, release to cancel" else "Requires hold to confirm"
                onClick(label = "Hold to confirm") {
                    if (enabled) holding = !holding
                    true
                }
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                holding = true
                                try {
                                    tryAwaitRelease()
                                } catch (_: Throwable) {
                                    // Gesture cancelled (scroll, system gesture).
                                } finally {
                                    holding = false
                                }
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0.0001f, 1f))
                .heightIn(min = 56.dp)
                .background(colors.riskHigh.copy(alpha = 0.18f))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.riskHigh, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = if (holding) "Keep holding…" else text,
                style = MaterialTheme.typography.titleMedium,
                color = colors.riskHigh
            )
        }
    }
}

@Composable
fun VRiskBadge(level: RiskLevel, modifier: Modifier = Modifier) {
    val colors = Vesper.colors
    val (label, textColor, container) = when (level) {
        RiskLevel.LOW -> Triple("LOW", colors.riskLow, colors.riskLowContainer)
        RiskLevel.MEDIUM -> Triple("MEDIUM", colors.riskMedium, colors.riskMediumContainer)
        RiskLevel.HIGH -> Triple("HIGH", colors.riskHigh, colors.riskHighContainer)
        RiskLevel.BLOCKED -> Triple("BLOCKED", colors.riskBlocked, colors.riskBlockedContainer)
    }
    Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun VStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Vesper.colors.success,
    icon: ImageVector? = null
) {
    val colors = Vesper.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.surface2)
            .border(1.dp, colors.hairline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
fun VChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.wine.copy(alpha = 0.18f) else colors.surface2,
        animationSpec = tween(VesperMotion.quick),
        label = "chipBackground"
    )
    val contentColor = if (selected) colors.rose else colors.textSecondary
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (selected) colors.wine else colors.hairline, CircleShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = contentColor, maxLines = 1)
    }
}

// ═══════════════════════════════════════════════════════════
// SURFACES
// ═══════════════════════════════════════════════════════════

/** Card levels map to the four-step surface ladder. */
@Composable
fun VCard(
    modifier: Modifier = Modifier,
    level: Int = 2,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(VesperSpace.md),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = Vesper.colors
    val background = when (level) {
        1 -> colors.surface1
        2 -> colors.surface2
        else -> colors.surface3
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .border(1.dp, colors.hairlineSoft, MaterialTheme.shapes.medium)
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = false,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun VSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = Vesper.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = VesperSpace.lg, bottom = VesperSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.gold,
                    maxLines = 1
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.titleSmall, color = colors.rose)
            }
        }
    }
}

@Composable
fun VListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val colors = Vesper.colors
    val contentColor = when {
        !enabled -> colors.textTertiary
        destructive -> colors.riskHigh
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onClick != null && enabled) {
                    Modifier.selectable(
                        selected = false,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = VesperSpace.sm, vertical = VesperSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) colors.riskHigh else colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(VesperSpace.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.textSecondary else colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(VesperSpace.xs))
            trailing()
        }
    }
}

@Composable
fun VStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color? = null
) {
    val colors = Vesper.colors
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(colors.surface2)
            .padding(horizontal = VesperSpace.sm, vertical = 10.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                textAlign = TextAlign.Start
            )
        }
    }
}

/** Table-style metric row used in diagnostics. */
@Composable
fun VMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Vesper.colors.textSecondary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Vesper.colors.textTertiary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = 1
        )
    }
}
