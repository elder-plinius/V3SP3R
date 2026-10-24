package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.domain.model.FileEntry
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.files.BreadcrumbBar
import com.vesper.flipper.ui.screen.files.CreateEntryDialog
import com.vesper.flipper.ui.screen.files.DeleteEntryDialog
import com.vesper.flipper.ui.screen.files.FilePreviewSheet
import com.vesper.flipper.ui.screen.files.formatFileDate
import com.vesper.flipper.ui.screen.files.formatFileSize
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.FileBrowserViewModel

/**
 * File browser state machine: disconnected, loading, error, empty, and a
 * real list. "Empty folder" is only ever shown for a connected, loaded,
 * genuinely empty directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToDevice: () -> Unit = onNavigateBack,
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    val state by viewModel.state.collectAsState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(state.actionSucceeded) {
        if (state.actionSucceeded) {
            showCreateDialog = false
            deleteTarget = null
            viewModel.clearActionState()
        }
    }

    VesperScaffold(
        title = "Files",
        subtitle = if (state.isConnected) state.currentPath else "No Flipper connected",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            if (state.isConnected) {
                IconButton(
                    onClick = {
                        viewModel.clearActionState()
                        showCreateDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create file or folder"
                    )
                }
            }
            IconButton(
                onClick = { viewModel.refresh() },
                enabled = !state.isLoading && state.isConnected
            ) {
                if (state.isLoading && state.entries.isNotEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.gold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh folder"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isConnected) {
                BreadcrumbBar(
                    currentPath = state.currentPath,
                    onNavigate = viewModel::navigateToPath
                )
            }

            if (state.error != null) {
                VErrorBanner(
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.refresh() },
                    onDismiss = { viewModel.clearError() },
                    visible = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    !state.isConnected -> CenteredEmptyState(
                        title = "No Flipper connected",
                        message = "Power on the Flipper, turn on Bluetooth, then connect it from the Device tab.",
                        icon = Icons.Default.BluetoothDisabled,
                        actionLabel = "Connect a Flipper",
                        onAction = onNavigateToDevice
                    )

                    state.entries.isEmpty() && state.error == null &&
                        (!state.hasLoaded || state.isLoading) -> {
                        VSkeletonList(rows = 7, modifier = Modifier.fillMaxWidth())
                    }

                    state.entries.isEmpty() && state.error == null -> CenteredEmptyState(
                        title = "This folder is empty",
                        message = "Nothing is stored in " + state.currentPath + ".",
                        icon = Icons.Default.FolderOff,
                        actionLabel = "New folder",
                        onAction = {
                            viewModel.clearActionState()
                            showCreateDialog = true
                        },
                        secondaryLabel = "Refresh",
                        onSecondary = { viewModel.refresh() }
                    )

                    state.entries.isEmpty() -> {
                        Text(
                            text = "This folder could not be listed. Use Retry above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(VesperSpace.gutter)
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = VesperSpace.gutter,
                                end = VesperSpace.gutter,
                                top = VesperSpace.xs,
                                bottom = VesperSpace.xxl
                            ),
                            verticalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
                        ) {
                            items(items = state.entries, key = { it.path }) { entry ->
                                FileEntryRow(
                                    entry = entry,
                                    enabled = !state.isActionInProgress,
                                    onClick = { viewModel.onEntryClick(entry) },
                                    onDelete = {
                                        viewModel.clearActionState()
                                        deleteTarget = entry
                                    }
                                )
                            }
                            item(key = "file-count") {
                                Text(
                                    text = state.entries.size.toString() + " items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(
                                        start = VesperSpace.sm,
                                        top = VesperSpace.sm
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.selectedFile?.let { file ->
        FilePreviewSheet(
            file = file,
            preview = state.preview,
            isLoading = state.isPreviewLoading,
            error = state.previewError,
            onRetry = { viewModel.retryPreview() },
            onDismiss = { viewModel.clearSelection() },
            onDelete = {
                viewModel.clearSelection()
                viewModel.clearActionState()
                deleteTarget = file
            }
        )
    }

    if (showCreateDialog) {
        CreateEntryDialog(
            currentPath = state.currentPath,
            isBusy = state.isActionInProgress,
            errorMessage = state.actionError,
            onClearError = { viewModel.clearActionState() },
            onDismiss = {
                showCreateDialog = false
                viewModel.clearActionState()
            },
            onCreateFolder = { name -> viewModel.createDirectory(name) },
            onCreateFile = { name, content -> viewModel.writeFile(name, content) }
        )
    }

    deleteTarget?.let { entry ->
        DeleteEntryDialog(
            entry = entry,
            isBusy = state.isActionInProgress,
            errorMessage = state.actionError,
            onDismiss = {
                deleteTarget = null
                viewModel.clearActionState()
            },
            onConfirm = { viewModel.deleteEntry(entry) }
        )
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = Vesper.colors
    VListRow(
        title = entry.name,
        subtitle = if (entry.isDirectory) {
            "Folder"
        } else {
            formatFileSize(entry.size) + " · " + formatFileDate(entry.modifiedTimestamp)
        },
        icon = fileIcon(entry),
        trailing = {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete " + entry.name,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        onClick = onClick,
        enabled = enabled
    )
}

@Composable
private fun CenteredEmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        VEmptyState(
            title = title,
            message = message,
            icon = icon,
            actionLabel = actionLabel,
            onAction = onAction,
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary
        )
    }
}

private fun fileIcon(entry: FileEntry): ImageVector = when {
    entry.isDirectory -> Icons.Default.Folder
    entry.name.endsWith(".sub", ignoreCase = true) -> Icons.Default.Sensors
    entry.name.endsWith(".ir", ignoreCase = true) -> Icons.Default.SettingsRemote
    entry.name.endsWith(".nfc", ignoreCase = true) -> Icons.Default.Nfc
    entry.name.endsWith(".rfid", ignoreCase = true) -> Icons.Default.Contactless
    entry.name.endsWith(".fap", ignoreCase = true) -> Icons.Default.Apps
    entry.name.endsWith(".txt", ignoreCase = true) ||
        entry.name.endsWith(".log", ignoreCase = true) || entry.name.endsWith(".json", ignoreCase = true) ->
        Icons.Default.Description

    else -> Icons.Default.InsertDriveFile
}
