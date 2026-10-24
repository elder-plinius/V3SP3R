package com.vesper.flipper.ui.screen.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FileEntry
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VInlineLoading
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.components.VSecondaryButton
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.FilePreview

/**
 * Read-only preview. Text is capped in the ViewModel; binary files get a
 * hex dump and metadata instead of a garbled string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
    file: FileEntry,
    preview: FilePreview?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val extension = file.name.substringAfterLast('.', "")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = file.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = colors.textSecondary
                    )
                }
            }

            VCard(level = 1, contentPadding = PaddingValues(VesperSpace.sm)) {
                VMetricRow(label = "Size", value = formatFileSize(file.size))
                VMetricRow(label = "Modified", value = formatFileDate(file.modifiedTimestamp))
                VMetricRow(
                    label = "Type",
                    value = if (extension.isBlank()) "File" else extension.uppercase() + " file"
                )
                if (preview != null && preview !is FilePreview.TooLarge) {
                    VMetricRow(
                        label = "Bytes read",
                        value = formatFileSize(preview.bytesRead.toLong()) + " of " + formatFileSize(preview.totalBytes)
                    )
                }
            }

            VHairline()

            when {
                isLoading -> VInlineLoading(text = "Reading file")
                error != null -> VErrorBanner(
                    message = error,
                    onRetry = onRetry,
                    visible = true
                )

                preview == null -> VEmptyState(
                    title = "Nothing to preview",
                    message = "This file could not be read. Try again or delete it from the list.",
                    actionLabel = "Try again",
                    onAction = onRetry
                )

                preview is FilePreview.Text -> {
                    if (preview.content.isEmpty()) {
                        Text(
                            text = "This file is empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(colors.surface1)
                                .padding(VesperSpace.sm)
                        ) {
                            Text(
                                text = preview.content,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                        }
                        if (preview.truncated) {
                            Text(
                                text = "Showing the first 64 KB of " +
                                    formatFileSize(preview.totalBytes) +
                                    ". Open the file elsewhere to see the rest.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.warning
                            )
                        }
                    }
                }

                preview is FilePreview.TooLarge -> {
                    Text(
                        text = "This file is " + formatFileSize(preview.totalBytes) + ".",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Vesper reads up to 256 KB for preview so the Bluetooth link is not tied up. " +
                            "The full file stays on the Flipper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }

                preview is FilePreview.Binary -> {
                    Text(
                        text = preview.detectedType + " · " + formatFileSize(preview.totalBytes),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Binary content is shown as a hex dump. Text editing is not available for this file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    if (preview.hex.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(colors.surface1)
                                .padding(VesperSpace.sm)
                        ) {
                            Text(
                                text = preview.hex,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                        }
                    }
                    if (preview.truncated) {
                        Text(
                            text = "Showing the first 4 KB of " + formatFileSize(preview.totalBytes) + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning
                        )
                    }
                }
            }

            Spacer(Modifier.height(VesperSpace.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = colors.riskHigh,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(VesperSpace.xxs))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.riskHigh
                    )
                }
                VSecondaryButton(
                    text = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
