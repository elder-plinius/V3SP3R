package com.vesper.flipper.ui.screen.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

private enum class CreateMode {
    FOLDER,
    FILE
}

/**
 * Create a folder or write a small text file inside the current directory.
 * Validation stays inline; the dialog never closes on a failed write.
 */
@Composable
fun CreateEntryDialog(
    currentPath: String,
    isBusy: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String, String) -> Unit
) {
    val colors = Vesper.colors
    var mode by remember { mutableStateOf(CreateMode.FOLDER) }
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmed = name.trim()
        val problem = when {
            trimmed.isBlank() -> "Enter a name."
            trimmed == "." || trimmed == ".." -> "Choose a different name."
            trimmed.any { it == '/' || it == '\\' } -> "Names cannot contain slashes."
            trimmed.any { it.code < 32 } -> "Remove control characters from the name."
            trimmed.length > 64 -> "Keep the name under 64 characters."
            else -> null
        }
        validationError = problem
        if (problem != null) return
        if (mode == CreateMode.FOLDER) {
            onCreateFolder(trimmed)
        } else {
            onCreateFile(trimmed, content)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        containerColor = colors.surface3,
        title = {
            Text(
                text = if (mode == CreateMode.FOLDER) "New folder" else "New file",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs)) {
                    VChip(
                        text = "Folder",
                        selected = mode == CreateMode.FOLDER,
                        onClick = {
                            mode = CreateMode.FOLDER
                            validationError = null
                            onClearError()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    VChip(
                        text = "File",
                        selected = mode == CreateMode.FILE,
                        onClick = {
                            mode = CreateMode.FILE
                            validationError = null
                            onClearError()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                        onClearError()
                    },
                    label = {
                        Text(if (mode == CreateMode.FOLDER) "Folder name" else "File name")
                    },
                    singleLine = true,
                    isError = validationError != null || errorMessage != null,
                    supportingText = {
                        val message = validationError ?: errorMessage
                        if (message != null) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.riskHigh
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mode == CreateMode.FILE) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            onClearError()
                        },
                        label = { Text("Contents") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                    )
                    Text(
                        text = "Up to 64 KB of UTF-8 text. Writing replaces the whole file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !isBusy) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.gold
                    )
                } else {
                    Text(
                        text = if (mode == CreateMode.FOLDER) "Create folder" else "Write file",
                        color = colors.gold
                    )
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
