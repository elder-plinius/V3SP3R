package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VHoldToConfirmButton
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.LockedPath
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/**
 * Auto-approve tiers and locked paths.
 *
 * The copy describes what the executor actually does: MEDIUM with auto-approve
 * skips the diff sheet, HIGH skips the confirmation sheet, and BLOCKED paths
 * are never auto-approved.
 */
fun LazyListScope.safetyCards(viewModel: SettingsViewModel) {
    item(key = "settings.device.autoapprove") {
        AutoApproveCard(viewModel)
    }
    item(key = "settings.device.locked") {
        LockedPathsCard(viewModel)
    }
}

@Composable
private fun AutoApproveCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    var confirmHigh by remember { mutableStateOf(false) }

    VCard(level = 1) {
        SettingsGroupLabel("Auto-approve")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsHint("Every action is still risk-classified before it runs. These skip the approval step only.")

        Spacer(Modifier.height(VesperSpace.sm))

        SettingsToggle(
            title = "Medium risk",
            subtitle = "File writes inside a folder you have granted",
            checked = state.autoApproveMedium,
            onCheckedChange = viewModel::setAutoApproveMedium
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.xs))

        SettingsToggle(
            title = "High risk",
            subtitle = "Deletes, moves, overwrites and writes outside your folder",
            checked = state.autoApproveHigh,
            onCheckedChange = { enabled ->
                if (enabled) confirmHigh = true else viewModel.setAutoApproveHigh(false)
            }
        )

        if (state.autoApproveMedium || state.autoApproveHigh) {
            Spacer(Modifier.height(VesperSpace.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.riskHighContainer)
                    .padding(VesperSpace.sm),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = colors.riskHigh,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(VesperSpace.xs))
                Column {
                    Text(
                        text = "What runs without asking",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.riskHigh
                    )
                    if (state.autoApproveMedium) {
                        Text(
                            text = "Writes inside granted folders apply without showing the diff first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                    if (state.autoApproveHigh) {
                        Text(
                            text = "Delete, move, rename and overwrite apply immediately.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                    Text(
                        text = "Locked paths stay blocked either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }

    if (confirmHigh) {
        SettingsDialog(onDismiss = { confirmHigh = false }) {
            Text(
                text = "Let high-risk actions run?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
            Spacer(Modifier.height(VesperSpace.xs))
            Text(
                text = "Delete, move, rename, overwrite and writes outside your folder " +
                    "will apply with no confirmation. The audit log still records each one.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.md))
            VHoldToConfirmButton(
                text = "Hold to enable high risk",
                onConfirm = {
                    viewModel.setAutoApproveHigh(true)
                    confirmHigh = false
                }
            )
            Spacer(Modifier.height(VesperSpace.xs))
            VQuietButton(
                text = "Cancel",
                onClick = { confirmHigh = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LockedPathsCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val lockedPaths by viewModel.lockedPaths.collectAsState()
    var editingPath by remember { mutableStateOf<LockedPath?>(null) }

    VCard(level = 1) {
        SettingsGroupLabel("Locked paths")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsHint("The executor refuses these paths until you unlock one for a fixed window.")

        Spacer(Modifier.height(VesperSpace.xs))

        lockedPaths.forEachIndexed { index, path ->
            VListRow(
                title = path.label,
                subtitle = if (path.unlocked) path.path + " - unlocked" else path.path,
                icon = if (path.unlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                onClick = { editingPath = path },
                trailing = {
                    Text(
                        text = if (path.unlocked) "Unlocked" else "Locked",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (path.unlocked) colors.warning else colors.riskBlocked,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            if (index < lockedPaths.lastIndex) VHairline()
        }
    }

    val current = editingPath
    if (current != null) {
        LockedPathDialog(
            path = current,
            onUnlock = { duration ->
                viewModel.unlockPath(current.path, duration)
                editingPath = null
            },
            onLock = {
                viewModel.lockPath(current.path)
                editingPath = null
            },
            onDismiss = { editingPath = null }
        )
    }
}

@Composable
private fun LockedPathDialog(
    path: LockedPath,
    onUnlock: (Long) -> Unit,
    onLock: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val durations = listOf(
        Permission.DURATION_15_MINUTES to "15 min",
        Permission.DURATION_1_HOUR to "1 hour",
        Permission.DURATION_SESSION to "24 hours"
    )
    var selectedDuration by remember { mutableStateOf(Permission.DURATION_1_HOUR) }

    SettingsDialog(onDismiss = onDismiss) {
        Text(
            text = path.label,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = path.detail + " (" + path.path + ")",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(VesperSpace.sm))

        if (path.unlocked) {
            Text(
                text = "Unlocked for now. Locking removes the exception immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning
            )
            Spacer(Modifier.height(VesperSpace.md))
            VPrimaryButton(text = "Lock now", onClick = onLock)
        } else {
            Text(
                text = "Choose how long the exception lasts. It reverts on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.xs))
            SettingsChipRow(
                options = durations.map { it.first.toString() to it.second },
                selectedId = selectedDuration.toString(),
                onSelect = { selectedDuration = it.toLong() }
            )
            Spacer(Modifier.height(VesperSpace.md))
            VPrimaryButton(text = "Unlock", onClick = { onUnlock(selectedDuration) })
        }

        Spacer(Modifier.height(VesperSpace.xs))
        VQuietButton(
            text = "Cancel",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
