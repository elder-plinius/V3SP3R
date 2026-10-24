package com.vesper.flipper.ui.components.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.PendingApproval
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.ui.components.UnifiedDiffViewer
import com.vesper.flipper.ui.components.VCommitButton
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VRiskBadge
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.coroutines.delay

/**
 * Scrollable approval body used inside the modal sheet.
 *
 * Everything lives in one vertical scroll so the action, diff and buttons all
 * remain reachable at 200 percent font scale. HIGH risk requires a deliberate
 * hold; BLOCKED paths cannot be approved from here at all.
 *
 * The countdown state is confined to the two small composables that display it,
 * so a 500ms tick never recomposes the diff.
 */
@Composable
fun ApprovalSheetContent(
    approval: PendingApproval,
    decisionInFlight: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val risk = approval.riskAssessment.level

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VesperSpace.lg)
            .padding(bottom = VesperSpace.xl),
        verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VRiskBadge(level = risk)
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = "Assessed by Vesper, not the model",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
        }

        Text(
            text = titleFor(risk),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() }
        )

        SectionLabel("Action")
        Text(
            text = commandName(approval.command.action),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )

        targetOf(approval)?.let { target ->
            SectionLabel("Target")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.surface2
            ) {
                Text(
                    text = target,
                    modifier = Modifier.padding(horizontal = VesperSpace.sm, vertical = VesperSpace.xs),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        SectionLabel("Why")
        Text(
            text = approval.riskAssessment.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        approval.riskAssessment.blockedReason?.let { blockedReason ->
            Text(
                text = blockedReason,
                style = MaterialTheme.typography.bodySmall,
                color = colors.riskBlocked
            )
        }

        SectionLabel("Model justification")
        Text(
            text = approval.command.justification,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Text(
            text = "Written by the model. It can be wrong or manipulated by file contents.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )

        approval.diff?.let { diff ->
            SectionLabel("Changes")
            UnifiedDiffViewer(
                unifiedDiff = diff.unifiedDiff,
                modifier = Modifier.heightIn(max = 240.dp)
            )
        }

        ApprovalExpiryBar(
            windowStart = approval.timestamp,
            expiresAt = approval.expiresAt
        )

        Spacer(Modifier.height(VesperSpace.xs))

        ApprovalActions(
            risk = risk,
            expiresAt = approval.expiresAt,
            decisionInFlight = decisionInFlight,
            onApprove = onApprove,
            onReject = onReject
        )

        if (decisionInFlight) {
            VInlineLoading(text = "Submitting your decision")
        }
    }
}

@Composable
private fun ApprovalActions(
    risk: RiskLevel,
    expiresAt: Long,
    decisionInFlight: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val colors = Vesper.colors
    val expired = rememberApprovalNow(expiresAt) >= expiresAt

    when {
        risk == RiskLevel.BLOCKED -> {
            Text(
                text = "Approval is unavailable for protected paths.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.riskBlocked
            )
            VSecondaryButton(
                text = "Reject",
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                enabled = !decisionInFlight
            )
        }

        risk == RiskLevel.HIGH -> {
            ApprovalHoldToConfirm(
                text = when {
                    expired -> "Approval expired"
                    decisionInFlight -> "Submitting"
                    else -> "Hold to approve"
                },
                onConfirm = onApprove,
                enabled = !expired && !decisionInFlight
            )
            VSecondaryButton(
                text = "Reject",
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                enabled = !decisionInFlight
            )
        }

        else -> {
            VCommitButton(
                text = if (expired) "Approval expired" else "Approve action",
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
                enabled = !expired && !decisionInFlight
            )
            VSecondaryButton(
                text = "Reject",
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                enabled = !decisionInFlight
            )
        }
    }
}

/**
 * Countdown for the approval window. The bar empties as the window closes and
 * turns the risk color when nothing is left to approve.
 */
@Composable
fun ApprovalExpiryBar(
    windowStart: Long,
    expiresAt: Long
) {
    val colors = Vesper.colors
    val now = rememberApprovalNow(expiresAt)
    val windowTotal = (expiresAt - windowStart).coerceAtLeast(1L)
    val remaining = (expiresAt - now).coerceAtLeast(0L)
    val fraction = (remaining.toFloat() / windowTotal.toFloat()).coerceIn(0f, 1f)
    val expired = remaining <= 0L
    val accent = if (expired) colors.riskHigh else colors.gold

    Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expired) "Approval window expired" else "Approval window",
                style = MaterialTheme.typography.labelSmall,
                color = if (expired) colors.riskHigh else colors.textTertiary
            )
            Text(
                text = if (expired) "Expired" else "Expires in " + formatRemaining(remaining),
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
        // A token-drawn bar rather than LinearProgressIndicator: M3 1.2.1
        // hard-codes that component's width, which breaks a full-bleed countdown.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(colors.surface2)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

/**
 * Ticks while the window is open and then stops, so a stale sheet does not keep
 * a timer alive.
 */
@Composable
private fun rememberApprovalNow(expiresAt: Long): Long {
    var now by remember(expiresAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= expiresAt) break
            delay(500L)
        }
    }
    return now
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Vesper.colors.textTertiary,
        modifier = Modifier.padding(top = VesperSpace.xxs)
    )
}

private fun titleFor(risk: RiskLevel): String = when (risk) {
    RiskLevel.HIGH -> "Confirm a destructive action"
    RiskLevel.MEDIUM -> "Approve a change"
    RiskLevel.BLOCKED -> "This action is blocked"
    RiskLevel.LOW -> "Approve an action"
}

private fun targetOf(approval: PendingApproval): String? {
    val args = approval.command.args
    return args.path
        ?: args.destinationPath
        ?: args.command
        ?: args.appName
        ?: args.signalName
        ?: approval.riskAssessment.affectedPaths.firstOrNull()
}

private fun commandName(action: CommandAction): String = when (action) {
    CommandAction.LIST_DIRECTORY -> "List a directory"
    CommandAction.READ_FILE -> "Read a file"
    CommandAction.WRITE_FILE -> "Write a file"
    CommandAction.CREATE_DIRECTORY -> "Create a directory"
    CommandAction.DELETE -> "Delete an item"
    CommandAction.MOVE -> "Move an item"
    CommandAction.RENAME -> "Rename an item"
    CommandAction.COPY -> "Copy an item"
    CommandAction.GET_DEVICE_INFO -> "Read device information"
    CommandAction.GET_STORAGE_INFO -> "Read storage information"
    CommandAction.SEARCH_FAPHUB -> "Search FapHub"
    CommandAction.INSTALL_FAPHUB_APP -> "Install an app"
    CommandAction.PUSH_ARTIFACT -> "Push an artifact"
    CommandAction.EXECUTE_CLI -> "Run a CLI command"
    CommandAction.FORGE_PAYLOAD -> "Generate a payload"
    CommandAction.SEARCH_RESOURCES -> "Search resources"
    CommandAction.LIST_VAULT -> "List the vault"
    CommandAction.RUN_RUNBOOK -> "Run a runbook"
    CommandAction.LAUNCH_APP -> "Launch an app"
    CommandAction.SUBGHZ_TRANSMIT -> "Transmit a Sub-GHz signal"
    CommandAction.IR_TRANSMIT -> "Transmit an infrared signal"
    CommandAction.NFC_EMULATE -> "Emulate an NFC tag"
    CommandAction.RFID_EMULATE -> "Emulate an RFID tag"
    CommandAction.IBUTTON_EMULATE -> "Emulate an iButton"
    CommandAction.BADUSB_EXECUTE -> "Run a BadUSB script"
    CommandAction.BLE_SPAM -> "Broadcast BLE advertisements"
    CommandAction.LED_CONTROL -> "Change the LED"
    CommandAction.VIBRO_CONTROL -> "Vibrate the Flipper"
    CommandAction.BROWSE_REPO -> "Browse a repository"
    CommandAction.DOWNLOAD_RESOURCE -> "Download a resource"
    CommandAction.GITHUB_SEARCH -> "Search GitHub"
    CommandAction.REQUEST_PHOTO -> "Capture a photo from the glasses"
}

private fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1000L).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return minutes.toString() + ":" + seconds.toString().padStart(2, '0')
}
