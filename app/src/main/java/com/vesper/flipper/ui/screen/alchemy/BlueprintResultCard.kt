package com.vesper.flipper.ui.screen.alchemy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.executor.ForgeValidator
import com.vesper.flipper.domain.model.ForgeBlueprint
import com.vesper.flipper.domain.model.ForgeStatus
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VRiskBadge
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.components.VStatusPill
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.coroutines.delay

/**
 * The generated blueprint: what it is, whether it validates, and what can be
 * done with it. Deploying is the only persistence path the app has (there is
 * no on-device blueprint store), so it is the primary action.
 */
@Composable
fun BlueprintResultCard(
    blueprint: ForgeBlueprint,
    validation: ForgeValidator.ValidationResult?,
    deployError: String?,
    onDeploy: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    onDismissDeployError: () -> Unit
) {
    val colors = Vesper.colors
    val context = LocalContext.current
    var expanded by remember(blueprint.id) { mutableStateOf(false) }
    var copied by remember(blueprint.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }

    val check = validation
    val isDeploying = blueprint.status == ForgeStatus.FORGING
    val isDeployed = blueprint.status == ForgeStatus.FORGED
    val blocked = check != null && !check.valid

    VCard(level = 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = blueprint.payloadType.icon(),
                contentDescription = null,
                tint = colors.gold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blueprint.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = blueprint.payloadType.displayName + " - " + formatDate(blueprint.craftedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dismiss this blueprint",
                    tint = colors.textTertiary
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.xs))

        Row(verticalAlignment = Alignment.CenterVertically) {
            VRiskBadge(level = blueprint.payloadType.riskLevel())
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = blueprint.payloadType.riskReason(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(VesperSpace.xs))

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                check == null -> VStatusPill(text = "Not validated", color = colors.textTertiary)
                check.valid && check.warnings.isEmpty() ->
                    VStatusPill(text = "Validation passed", color = colors.success, icon = Icons.Rounded.Check)
                check.valid ->
                    VStatusPill(text = "Passed with warnings", color = colors.warning, icon = Icons.Rounded.Warning)
                else -> VStatusPill(text = "Validation failed", color = colors.riskHigh, icon = Icons.Rounded.Warning)
            }
            Spacer(Modifier.weight(1f))
            if (isDeployed) {
                VStatusPill(text = "Saved to Flipper", color = colors.success, icon = Icons.Rounded.Check)
            }
        }

        if (check != null) {
            val notes = check.errors + check.warnings
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(VesperSpace.xs))
                notes.take(3).forEach { note ->
                    Text(
                        text = "- " + note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (check.valid) colors.warning else colors.riskHigh,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = blueprint.flipperPath,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = lineCount(blueprint.generatedCode).toString() + " lines",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide the code" else "Show the code",
                    tint = colors.textSecondary,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                CodeBlock(code = blueprint.generatedCode, maxHeight = 260.dp)
            }
        }

        if (deployError != null) {
            Spacer(Modifier.height(VesperSpace.sm))
            VErrorBanner(
                message = deployError,
                onRetry = onDeploy,
                onDismiss = onDismissDeployError,
                visible = true
            )
        }

        Spacer(Modifier.height(VesperSpace.sm))

        VPrimaryButton(
            text = when {
                isDeploying -> "Deploying"
                isDeployed -> "Deploy again"
                blueprint.status == ForgeStatus.FAILED -> "Retry deploy"
                else -> "Deploy to Flipper"
            },
            onClick = onDeploy,
            modifier = Modifier.fillMaxWidth(),
            enabled = !blocked,
            loading = isDeploying
        )

        Text(
            text = "Writes the file straight to the device. Locked paths still need an unlock in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = VesperSpace.xxs)
        )

        Spacer(Modifier.height(VesperSpace.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VSecondaryButton(
                text = if (copied) "Copied" else "Copy code",
                onClick = {
                    copyToClipboard(context, blueprint.title, blueprint.generatedCode)
                    copied = true
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ContentCopy
            )
            VQuietButton(
                text = "Edit code",
                onClick = onEdit,
                modifier = Modifier.weight(1f)
            )
        }

        if (blocked) {
            Spacer(Modifier.height(VesperSpace.xxs))
            Text(
                text = "Deploy stays disabled until the code validates.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.riskHigh
            )
        }
    }
}
