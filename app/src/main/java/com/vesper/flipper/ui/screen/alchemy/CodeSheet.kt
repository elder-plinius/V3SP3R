package com.vesper.flipper.ui.screen.alchemy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.executor.ForgeValidator
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Code editor for a blueprint or a saved file.
 *
 * Validation runs on every edit, so the save button is never available for
 * content the Flipper would reject.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeSheet(
    title: String,
    subtitle: String,
    content: String,
    validation: ForgeValidator.ValidationResult?,
    isSaving: Boolean,
    error: String?,
    canDuplicate: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val invalid = validation != null && !validation.valid
    val canSave = content.isNotBlank() && !invalid && !isSaving

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close the editor",
                        tint = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.xs))

            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.labelMedium,
                isError = invalid,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii
                ),
                shape = MaterialTheme.shapes.medium,
                colors = codeFieldColors()
            )

            if (validation != null) {
                val notes = validation.errors + validation.warnings
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(VesperSpace.xs))
                    notes.take(3).forEach { note ->
                        Text(
                            text = "- " + note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (validation.valid) colors.warning else colors.riskHigh,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (notes.size > 3) {
                        Text(
                            text = "+" + (notes.size - 3) + " more",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(VesperSpace.xs))
                VErrorBanner(message = error, visible = true)
            }

            Spacer(Modifier.height(VesperSpace.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VPrimaryButton(
                    text = "Save",
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                    loading = isSaving
                )
                VSecondaryButton(
                    text = "Copy",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    enabled = content.isNotBlank()
                )
                if (canDuplicate) {
                    VQuietButton(text = "Duplicate", onClick = onDuplicate)
                }
            }

            if (invalid) {
                Text(
                    text = "Save stays disabled until the file validates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.riskHigh,
                    modifier = Modifier.padding(top = VesperSpace.xxs)
                )
            }
        }
    }
}

@Composable
private fun codeFieldColors(): TextFieldColors {
    val colors = Vesper.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        errorTextColor = colors.textPrimary,
        focusedContainerColor = colors.surface1,
        unfocusedContainerColor = colors.surface1,
        errorContainerColor = colors.surface1,
        cursorColor = colors.gold,
        focusedBorderColor = colors.gold,
        unfocusedBorderColor = colors.hairline,
        errorBorderColor = colors.riskHigh,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary
    )
}
