package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Field styling for the settings form. The design system does not ship a text
 * field, so the token mapping lives here once instead of at each call site.
 */
@Composable
fun vesperFieldColors(): TextFieldColors {
    val colors = Vesper.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        disabledTextColor = colors.textTertiary,
        errorTextColor = colors.textPrimary,
        focusedContainerColor = colors.surface1,
        unfocusedContainerColor = colors.surface1,
        disabledContainerColor = colors.surface1,
        errorContainerColor = colors.surface1,
        cursorColor = colors.gold,
        focusedBorderColor = colors.gold,
        unfocusedBorderColor = colors.hairline,
        disabledBorderColor = colors.hairlineSoft,
        errorBorderColor = colors.riskHigh,
        focusedLabelColor = colors.gold,
        unfocusedLabelColor = colors.textTertiary,
        disabledLabelColor = colors.textTertiary,
        errorLabelColor = colors.riskHigh,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary,
        focusedSupportingTextColor = colors.textTertiary,
        unfocusedSupportingTextColor = colors.textTertiary,
        errorSupportingTextColor = colors.riskHigh
    )
}

/**
 * One switch row. The whole row is the touch target (56dp) and the switch
 * itself is not focusable, so TalkBack announces a single control instead of
 * two nested ones.
 */
@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = Vesper.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = VesperSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.textPrimary else colors.textTertiary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) colors.textSecondary else colors.textTertiary
            )
        }
        Spacer(Modifier.width(VesperSpace.sm))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onGold,
                checkedTrackColor = colors.gold,
                checkedBorderColor = colors.gold,
                uncheckedThumbColor = colors.textTertiary,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = colors.hairline
            )
        )
    }
}

/** A row that opens a picker or a dialog, with the current value on the right. */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = Vesper.colors
    VListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) colors.textSecondary else colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

/** Group label inside a card. Mono, quiet, uppercase. */
@Composable
fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Vesper.colors.textTertiary,
        modifier = modifier
    )
}

/** Helper line under a control. */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Vesper.colors.textSecondary,
        modifier = modifier
    )
}

/**
 * One dialog shell for the screen: a level-3 card on the platform dialog so
 * the scrim, back handling, and width come from the platform, while the
 * surface styling stays in the design system.
 */
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        VCard(
            level = 3,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(VesperSpace.lg)
        ) {
            content()
        }
    }
}

enum class NoticeTone { SUCCESS, ERROR, NEUTRAL }

/** Inline result line. Used for key save and connection feedback. */
@Composable
fun SettingsNotice(
    text: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val tint: Color
    val container: Color
    when (tone) {
        NoticeTone.SUCCESS -> {
            tint = colors.success
            container = colors.riskLowContainer
        }
        NoticeTone.ERROR -> {
            tint = colors.riskHigh
            container = colors.riskHighContainer
        }
        NoticeTone.NEUTRAL -> {
            tint = colors.textSecondary
            container = colors.surface2
        }
    }
    val icon = when (tone) {
        NoticeTone.SUCCESS -> Icons.Rounded.CheckCircle
        NoticeTone.ERROR -> Icons.Rounded.ErrorOutline
        NoticeTone.NEUTRAL -> Icons.Rounded.Info
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = VesperSpace.sm, vertical = VesperSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(VesperSpace.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint
        )
    }
}

/** Single-select chips for short option lists. */
@Composable
fun SettingsChipRow(
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
        contentPadding = PaddingValues(vertical = VesperSpace.xxs)
    ) {
        items(options, key = { it.first }) { option ->
            VChip(
                text = option.second,
                selected = option.first == selectedId,
                onClick = { if (enabled) onSelect(option.first) }
            )
        }
    }
}

// ── Formatting ───────────────────────────────────────────────────────────

private val providerNames = mapOf(
    "nousresearch" to "Nous Research",
    "anthropic" to "Anthropic",
    "openai" to "OpenAI",
    "google" to "Google",
    "meta-llama" to "Meta",
    "mistralai" to "Mistral",
    "x-ai" to "xAI",
    "qwen" to "Qwen",
    "deepseek" to "DeepSeek",
    "cohere" to "Cohere",
    "moonshotai" to "Moonshot",
    "z-ai" to "Z.AI"
)

/** "anthropic/claude-sonnet-4.5" -> "Anthropic". */
fun providerLabel(modelId: String): String {
    val prefix = modelId.substringBefore("/", "")
    val known = providerNames[prefix]
    if (known != null) return known
    if (prefix.isBlank()) return "OpenRouter"
    return prefix.replaceFirstChar { it.uppercase() }
}

fun modelNameFor(models: List<ModelInfo>, modelId: String): String =
    models.find { it.id == modelId }?.displayName ?: modelId.substringAfter("/")

fun formatRemaining(remainingMs: Long): String {
    val minutes = remainingMs / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 -> hours.toString() + "h " + (minutes % 60) + "m"
        minutes > 0 -> minutes.toString() + "m"
        else -> "under a minute"
    }
}

fun formatRetention(days: Int): String = days.toString() + " days"
