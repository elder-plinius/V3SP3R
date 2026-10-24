package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VHoldToConfirmButton
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SettingsViewModel

/** Privacy and data: what is kept, for how long, and how to remove it. */
fun LazyListScope.privacySection(viewModel: SettingsViewModel) {
    item(key = "settings.privacy.header") {
        VSectionHeader(title = "Privacy and data", eyebrow = "STAYS ON DEVICE")
    }
    item(key = "settings.privacy.card") {
        PrivacyCard(viewModel)
    }
}

@Composable
private fun PrivacyCard(viewModel: SettingsViewModel) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    VCard(level = 1) {
        SettingsGroupLabel("Audit retention")
        Spacer(Modifier.height(VesperSpace.xxs))
        SettingsHint("How much history the audit log keeps when you prune it.")
        Spacer(Modifier.height(VesperSpace.xs))
        SettingsChipRow(
            options = listOf(7, 14, 30, 60, 90).map { it.toString() to (it.toString() + "d") },
            selectedId = state.auditRetentionDays.toString(),
            onSelect = { viewModel.setAuditRetentionDays(it.toInt()) }
        )

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        VListRow(
            title = "Prune old entries",
            subtitle = "Removes audit entries older than " + formatRetention(state.auditRetentionDays),
            onClick = viewModel::pruneAuditLog,
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        VMetricRow(label = "Key storage", value = "Android keystore")
        VMetricRow(label = "Audit history", value = "This device only")
        VMetricRow(label = "Sent to OpenRouter", value = "Prompts and device data you ask for")

        Spacer(Modifier.height(VesperSpace.sm))

        VQuietButton(
            text = "Clear the audit log",
            onClick = { confirmClear = true }
        )
    }

    if (confirmClear) {
        SettingsDialog(onDismiss = { confirmClear = false }) {
            Text(
                text = "Clear the audit log?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
            Spacer(Modifier.height(VesperSpace.xs))
            Text(
                text = "Every recorded action is removed from this device. The app keeps working, " +
                    "but past commands can no longer be inspected.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(VesperSpace.md))
            VHoldToConfirmButton(
                text = "Hold to clear",
                onConfirm = {
                    viewModel.clearAuditLog()
                    confirmClear = false
                }
            )
            Spacer(Modifier.height(VesperSpace.xs))
            VQuietButton(
                text = "Cancel",
                onClick = { confirmClear = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
