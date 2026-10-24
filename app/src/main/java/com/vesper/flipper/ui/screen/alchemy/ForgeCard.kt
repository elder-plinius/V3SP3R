package com.vesper.flipper.ui.screen.alchemy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

private val suggestions = listOf(
    "A BadUSB script that opens Notepad and types a note",
    "A Sub-GHz file for a 433 MHz garage remote",
    "An IR remote with power and volume keys"
)

/** Natural-language input for the Forge. */
@Composable
fun ForgeCard(
    input: String,
    isForging: Boolean,
    error: String?,
    onInputChange: (String) -> Unit,
    onForge: () -> Unit,
    onDismissError: () -> Unit
) {
    val colors = Vesper.colors
    val focusManager = LocalFocusManager.current

    VCard(level = 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = colors.gold,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(VesperSpace.xs))
            Text(
                text = "DESCRIBE A PAYLOAD",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary
            )
        }

        Spacer(Modifier.height(VesperSpace.xs))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What should it do, and on what target?") },
            minLines = 3,
            maxLines = 6,
            enabled = !isForging,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    focusManager.clearFocus()
                    if (input.isNotBlank()) onForge()
                }
            ),
            shape = MaterialTheme.shapes.medium,
            colors = forgeFieldColors()
        )

        Spacer(Modifier.height(VesperSpace.xs))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
        ) {
            items(suggestions, key = { it }) { suggestion ->
                VQuietButton(
                    text = suggestion,
                    onClick = { onInputChange(suggestion) },
                    enabled = !isForging
                )
            }
        }

        Spacer(Modifier.height(VesperSpace.xs))

        VPrimaryButton(
            text = if (isForging) "Generating" else "Generate blueprint",
            onClick = {
                focusManager.clearFocus()
                onForge()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = input.isNotBlank(),
            loading = isForging,
            icon = Icons.Rounded.AutoAwesome
        )

        if (error != null) {
            Spacer(Modifier.height(VesperSpace.xs))
            VErrorBanner(
                message = error,
                onRetry = onForge,
                onDismiss = onDismissError,
                visible = true
            )
        }
    }
}

@Composable
private fun forgeFieldColors(): TextFieldColors {
    val colors = Vesper.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        disabledTextColor = colors.textTertiary,
        errorTextColor = colors.textPrimary,
        focusedContainerColor = colors.surface1,
        unfocusedContainerColor = colors.surface1,
        disabledContainerColor = colors.surface1,
        cursorColor = colors.gold,
        focusedBorderColor = colors.gold,
        unfocusedBorderColor = colors.hairline,
        disabledBorderColor = colors.hairlineSoft,
        focusedLabelColor = colors.gold,
        unfocusedLabelColor = colors.textTertiary,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary,
        disabledPlaceholderColor = colors.textTertiary,
        focusedSupportingTextColor = colors.textTertiary,
        unfocusedSupportingTextColor = colors.textTertiary
    )
}
