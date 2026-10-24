package com.vesper.flipper.ui.screen.oracle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Raw model output, kept out of the report so the report stays readable. */
@Composable
fun OracleRawDialog(
    response: String,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        title = {
            Text(
                text = "Raw response",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = response.ifBlank { "No response was stored." },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.rose
                )
            }
        }
    )
}
