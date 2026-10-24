package com.vesper.flipper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Empty state with a single primary action. Every screen that can have
 * no content must use this instead of a blank box or a raw string.
 */
@Composable
fun VEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val colors = Vesper.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VesperSpace.xl, vertical = VesperSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = colors.gold, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(VesperSpace.xs))
            VPrimaryButton(text = actionLabel, onClick = onAction, compact = true)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel, color = colors.rose, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Inline, dismissible error surface with retry. */
@Composable
fun VErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    visible: Boolean = true
) {
    val colors = Vesper.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.md, vertical = VesperSpace.xs)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.riskHighContainer)
                .padding(horizontal = VesperSpace.md, vertical = VesperSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.riskHigh,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = colors.riskHigh, modifier = Modifier.size(18.dp))
                    Text("Retry", color = colors.riskHigh, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = colors.textSecondary, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** Skeleton placeholder used while a first page loads. */
@Composable
fun VSkeletonList(
    rows: Int = 4,
    modifier: Modifier = Modifier
) {
    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    val colors = Vesper.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VesperSpace.md),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
    ) {
        repeat(rows) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index % 3 == 0) 0.72f else 1f)
                    .height(if (index % 2 == 0) 18.dp else 14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface2.copy(alpha = alpha.value))
            )
        }
    }
}

/** Small inline loading row used inside lists and sheets. */
@Composable
fun VInlineLoading(text: String = "Connecting…", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(VesperSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Vesper.colors.gold
        )
        Spacer(Modifier.size(VesperSpace.xs))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Vesper.colors.textSecondary)
    }
}
