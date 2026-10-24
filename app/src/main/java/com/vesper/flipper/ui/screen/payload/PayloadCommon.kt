package com.vesper.flipper.ui.screen.payload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Copies generated code. Kept local so the payload package has no UI imports. */
fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Mono group label used inside the payload cards. */
@Composable
fun PayloadGroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Vesper.colors.textTertiary
    )
}

/** "What this does" explainer. Every tab leads with one. */
@Composable
fun ExplainerCard(title: String, body: String, points: List<String>) {
    val colors = Vesper.colors
    VCard(level = 1) {
        PayloadGroupLabel("WHAT THIS DOES")
        Spacer(Modifier.height(VesperSpace.xxs))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = VesperSpace.xxs)
        )
        points.forEach { point ->
            Row(modifier = Modifier.padding(top = VesperSpace.xxs)) {
                Text(
                    text = "-",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Text(
                    text = point,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

enum class PayloadNoticeTone { SUCCESS, WARNING, INFO }

/** Inline result line for save and generation feedback. */
@Composable
fun PayloadNotice(
    text: String,
    tone: PayloadNoticeTone,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val tint: Color
    val container: Color
    when (tone) {
        PayloadNoticeTone.SUCCESS -> {
            tint = colors.success
            container = colors.riskLowContainer
        }
        PayloadNoticeTone.WARNING -> {
            tint = colors.warning
            container = colors.riskMediumContainer
        }
        PayloadNoticeTone.INFO -> {
            tint = colors.textSecondary
            container = colors.surface2
        }
    }
    val icon = when (tone) {
        PayloadNoticeTone.SUCCESS -> Icons.Rounded.CheckCircle
        PayloadNoticeTone.WARNING -> Icons.Rounded.Warning
        PayloadNoticeTone.INFO -> Icons.Rounded.Info
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
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * Target file name with validation.
 *
 * The ViewModel sanitises the value to letters, numbers, dashes and
 * underscores, so the only reachable error states are blank, too long, or a
 * leading separator.
 */
fun validateFileName(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Name the file before saving"
        trimmed.length > 40 -> "Keep the name under 40 characters"
        trimmed.startsWith("_") || trimmed.startsWith("-") ->
            "The name cannot start with a separator"
        else -> null
    }
}

@Composable
fun SaveFileBlock(
    name: String,
    onNameChange: (String) -> Unit,
    hint: String,
    isSaving: Boolean,
    canSave: Boolean,
    onSave: () -> Unit
) {
    val colors = Vesper.colors
    val focusManager = LocalFocusManager.current
    val error = validateFileName(name)

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("File name") },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                if (error == null && canSave) onSave()
            }
        ),
        shape = MaterialTheme.shapes.medium,
        colors = payloadFieldColors()
    )

    Spacer(Modifier.height(VesperSpace.xs))

    VPrimaryButton(
        text = "Save to Flipper",
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = canSave && error == null,
        loading = isSaving
    )

    Text(
        text = "Saving writes the file straight to the Flipper, with no approval step.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textTertiary,
        modifier = Modifier.padding(top = VesperSpace.xxs)
    )
}

@Composable
fun payloadFieldColors(): TextFieldColors {
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
        errorLabelColor = colors.riskHigh,
        focusedLabelColor = colors.gold,
        unfocusedLabelColor = colors.textTertiary,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary,
        focusedSupportingTextColor = colors.textTertiary,
        unfocusedSupportingTextColor = colors.textTertiary,
        errorSupportingTextColor = colors.riskHigh
    )
}

/** Multi-line prompt field with the payload palette. */
@Composable
fun PayloadPromptField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = 3,
        maxLines = 6,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = payloadFieldColors()
    )
}
