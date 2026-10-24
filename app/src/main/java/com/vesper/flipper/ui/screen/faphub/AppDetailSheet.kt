package com.vesper.flipper.ui.screen.faphub

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FapApp
import com.vesper.flipper.domain.model.InstallStatus
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatTile
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Detail sheet: what the app is, where it comes from, and the install action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(
    app: FapApp,
    status: InstallStatus?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = status is InstallStatus.Downloading || status is InstallStatus.Installing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = app.category.icon(),
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(VesperSpace.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "by " + app.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.md))

            Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
                VStatTile(
                    label = "Downloads",
                    value = formatDownloads(app.downloads),
                    modifier = Modifier.weight(1f)
                )
                VStatTile(
                    label = "Rating",
                    value = formatRating(app.rating),
                    modifier = Modifier.weight(1f)
                )
                VStatTile(
                    label = "Version",
                    value = "v" + app.version,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(VesperSpace.md))

            Text(
                text = app.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            Spacer(Modifier.height(VesperSpace.md))

            VMetricRow(label = "Category", value = app.category.displayName)
            VMetricRow(label = "Target firmware", value = app.targetFirmware)
            VMetricRow(label = "SDK", value = app.sdkVersion)
            VMetricRow(label = "Size", value = formatBytes(app.size))
            VMetricRow(
                label = "State",
                value = when {
                    app.isInstalled -> "Installed"
                    status is InstallStatus.Error -> "Install failed"
                    else -> "Not installed"
                },
                valueColor = when {
                    app.isInstalled -> colors.success
                    status is InstallStatus.Error -> colors.riskHigh
                    else -> colors.textSecondary
                }
            )

            VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

            VListRow(
                title = "Catalog page",
                subtitle = app.downloadUrl,
                icon = Icons.Rounded.OpenInNew,
                onClick = { onOpenUrl(app.downloadUrl) }
            )

            if (status is InstallStatus.Error) {
                Spacer(Modifier.height(VesperSpace.xs))
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.riskHigh
                )
            }

            Spacer(Modifier.height(VesperSpace.md))

            when (status) {
                is InstallStatus.Downloading -> StatusLine("Downloading " + (status.progress * 100).toInt() + "%")
                is InstallStatus.Installing -> StatusLine("Installing")
                else -> Unit
            }

            VPrimaryButton(
                text = when {
                    busy -> "Working"
                    app.isInstalled -> "Reinstall"
                    else -> "Install"
                },
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
                enabled = !busy
            )

            if (app.isInstalled) {
                Spacer(Modifier.height(VesperSpace.xs))
                VSecondaryButton(
                    text = "Uninstall",
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
                Text(
                    text = "Removes the .fap from the Flipper. Settings and data under /ext/apps_data stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = VesperSpace.xxs)
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Vesper.colors.textTertiary,
        modifier = Modifier.padding(bottom = VesperSpace.xs)
    )
}
