package com.vesper.flipper.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.domain.model.LootCard
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VHoldToConfirmButton
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VQuietButton
import com.vesper.flipper.ui.components.VSectionHeader
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.alchemy.AlchemyDialog
import com.vesper.flipper.ui.screen.alchemy.BlueprintResultCard
import com.vesper.flipper.ui.screen.alchemy.CodeSheet
import com.vesper.flipper.ui.screen.alchemy.ForgeCard
import com.vesper.flipper.ui.screen.alchemy.VaultEmptyState
import com.vesper.flipper.ui.screen.alchemy.VaultRow
import com.vesper.flipper.ui.screen.alchemy.VaultToolbar
import com.vesper.flipper.ui.screen.alchemy.copyToClipboard
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.AlchemyLabViewModel
import com.vesper.flipper.ui.viewmodel.EditTarget
import kotlinx.coroutines.launch

/**
 * Forge (Alchemy Lab).
 *
 * One surface, two halves: generate a blueprint from a sentence, then manage
 * what is already saved on the device. Both halves open the same validated
 * code sheet.
 */
@Composable
fun AlchemyLabScreen(
    onNavigateToPayloadLab: () -> Unit = {},
    viewModel: AlchemyLabViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val forgeInput by viewModel.forgeInput.collectAsState()
    val isForging by viewModel.isForging.collectAsState()
    val blueprint by viewModel.currentBlueprint.collectAsState()
    val validation by viewModel.forgeValidation.collectAsState()
    val forgeError by viewModel.forgeError.collectAsState()
    val deployError by viewModel.deployError.collectAsState()

    val vaultQuery by viewModel.vaultQuery.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val loot by viewModel.filteredLoot.collectAsState()
    val stats by viewModel.vaultStats.collectAsState()
    val isLoadingVault by viewModel.isLoadingVault.collectAsState()
    val hasLoadedVault by viewModel.hasLoadedVault.collectAsState()

    val editTarget by viewModel.editTarget.collectAsState()
    val editContent by viewModel.editContent.collectAsState()
    val editValidation by viewModel.editValidation.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val sheetError by viewModel.sheetError.collectAsState()

    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<LootCard?>(null) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        viewModel.clearMessage()
    }

    VesperScaffold(
        title = "Forge",
        subtitle = "Describe it, validate it, deploy it",
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = VesperSpace.gutter,
                end = VesperSpace.gutter,
                bottom = VesperSpace.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(VesperSpace.sm)
        ) {
            item(key = "forge.header") {
                VSectionHeader(title = "New blueprint", eyebrow = "ALCHEMY")
            }

            item(key = "forge.input") {
                ForgeCard(
                    input = forgeInput,
                    isForging = isForging,
                    error = forgeError,
                    onInputChange = viewModel::updateForgeInput,
                    onForge = viewModel::forge,
                    onDismissError = viewModel::clearForgeError
                )
            }

            item(key = "forge.result") {
                AnimatedVisibility(visible = blueprint != null) {
                    val current = blueprint
                    if (current != null) {
                        BlueprintResultCard(
                            blueprint = current,
                            validation = validation,
                            deployError = deployError,
                            onDeploy = viewModel::deployBlueprint,
                            onEdit = viewModel::openBlueprintEditor,
                            onDismiss = viewModel::clearBlueprint,
                            onDismissDeployError = viewModel::clearDeployError
                        )
                    }
                }
            }

            item(key = "forge.payloadlab") {
                VCard(level = 1, contentPadding = PaddingValues(vertical = VesperSpace.xxs)) {
                    VListRow(
                        title = "Open Payload Lab",
                        subtitle = "BadUSB and Evil Portal generator with validation",
                        icon = Icons.Rounded.Science,
                        onClick = onNavigateToPayloadLab,
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.padding(end = VesperSpace.xs)
                            )
                        }
                    )
                }
            }

            item(key = "vault.header") {
                VSectionHeader(
                    title = "Vault",
                    eyebrow = "ON THE FLIPPER",
                    actionLabel = "Rescan",
                    onAction = viewModel::loadVault
                )
            }

            item(key = "vault.toolbar") {
                VaultToolbar(
                    query = vaultQuery,
                    onQueryChange = viewModel::updateVaultQuery,
                    selectedFilter = selectedFilter,
                    onFilterChange = viewModel::setFilter,
                    stats = stats,
                    resultCount = loot.size,
                    totalCount = stats.values.sum()
                )
            }

            if (isLoadingVault && !hasLoadedVault) {
                item(key = "vault.loading") {
                    VSkeletonList(rows = 5)
                }
            } else if (loot.isEmpty()) {
                item(key = "vault.empty") {
                    VaultEmptyState(
                        hasLoaded = hasLoadedVault,
                        query = vaultQuery,
                        filter = selectedFilter,
                        onClearFilters = {
                            viewModel.updateVaultQuery("")
                            viewModel.setFilter(null)
                        },
                        onRescan = viewModel::loadVault
                    )
                }
            } else {
                items(loot, key = { it.id }) { item ->
                    VaultRow(
                        loot = item,
                        onOpen = { viewModel.openInWorkbench(item) },
                        onDeleteRequest = { deleteTarget = item }
                    )
                }
            }
        }
    }

    val target = editTarget
    if (target != null) {
        val sheetTitle = when (target) {
            is EditTarget.Vault -> target.loot.fileName
            EditTarget.Blueprint -> "Blueprint code"
        }
        val sheetSubtitle = when (target) {
            is EditTarget.Vault -> "Saved on the Flipper"
            EditTarget.Blueprint -> (blueprint?.payloadType?.displayName ?: "Payload") + " - validates before deploy"
        }
        CodeSheet(
            title = sheetTitle,
            subtitle = sheetSubtitle,
            content = editContent,
            validation = editValidation,
            isSaving = isSaving,
            error = sheetError,
            canDuplicate = target is EditTarget.Vault,
            onContentChange = viewModel::updateEditContent,
            onSave = viewModel::saveWorkbench,
            onCopy = {
                copyToClipboard(context, sheetTitle, editContent)
                scope.launch { snackbarHostState.showSnackbar("Code copied") }
            },
            onDuplicate = {
                (target as? EditTarget.Vault)?.let {
                    viewModel.duplicateLoot(it.loot)
                    viewModel.closeWorkbench()
                }
            },
            onDismiss = viewModel::closeWorkbench
        )
    }

    val doomed = deleteTarget
    if (doomed != null) {
        AlchemyDialog(onDismiss = { deleteTarget = null }) {
            Text(
                text = "Delete this file?",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary
            )
            Text(
                text = doomed.fileName + " is removed from " + doomed.path.substringBeforeLast("/") +
                    " on the Flipper. This cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = VesperSpace.xs)
            )
            VHoldToConfirmButton(
                text = "Hold to delete",
                onConfirm = {
                    viewModel.deleteLoot(doomed)
                    deleteTarget = null
                },
                modifier = Modifier.padding(top = VesperSpace.md)
            )
            VQuietButton(
                text = "Cancel",
                onClick = { deleteTarget = null },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
