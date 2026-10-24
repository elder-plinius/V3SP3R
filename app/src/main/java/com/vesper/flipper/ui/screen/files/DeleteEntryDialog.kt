package com.vesper.flipper.ui.screen.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FileEntry
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/**
 * Deletion always names the exact path and type first. A failed delete keeps
 * the dialog open so the error is read in context.
 */
@Composable
fun DeleteEntryDialog(
    entry: FileEntry,
    isBusy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = Vesper.colors
    val isFolder = entry.isDirectory

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        containerColor = colors.surface3,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = colors.riskHigh
            )
        },
        title = {
            Text(
                text = if (isFolder) "Delete this folder?" else "Delete this file?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
                Text(
                    text = if (isFolder) {
                        "The folder and everything inside it are removed from the Flipper. This cannot be undone."
                    } else {
                        "The file is removed from the Flipper. This cannot be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Text(
                    text = "Path",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
                Text(
                    text = if (isFolder) "Folder" else "File · " + formatFileSize(entry.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.riskHigh
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isBusy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.riskHigh
                    )
                } else {
                    Text("Delete", color = colors.riskHigh)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}
