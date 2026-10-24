package com.vesper.flipper.ui.components.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vesper.flipper.ui.components.VHoldToConfirmButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Hold-to-confirm wiring for destructive approvals.
 *
 * VHoldToConfirmButton already handles the press-and-hold gesture, haptics and
 * TalkBack activation (activating starts the same countdown). This wrapper adds
 * the visible instruction and keeps the copy next to the control.
 */
@Composable
fun ApprovalHoldToConfirm(
    text: String,
    onConfirm: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    holdMillis: Long = 1500L
) {
    val colors = Vesper.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
    ) {
        VHoldToConfirmButton(
            text = text,
            onConfirm = onConfirm,
            enabled = enabled,
            holdMillis = holdMillis
        )
        Text(
            text = "Hold for " + formatHoldSeconds(holdMillis) + " seconds. Release early to cancel.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
    }
}

private fun formatHoldSeconds(holdMillis: Long): String {
    return if (holdMillis % 1000L == 0L) {
        (holdMillis / 1000L).toString()
    } else {
        (holdMillis / 1000f).toString()
    }
}
