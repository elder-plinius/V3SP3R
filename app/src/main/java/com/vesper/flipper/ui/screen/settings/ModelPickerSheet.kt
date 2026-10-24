package com.vesper.flipper.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import kotlinx.coroutines.launch

/**
 * Searchable model picker. Each row carries the provider, so two models with
 * the same family name from different labs stay distinguishable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedModel: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.gutter)
                .padding(bottom = VesperSpace.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Choose a model",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = models.size.toString() + " in the catalog",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textTertiary
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.gold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh the model catalog",
                            tint = colors.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.size(VesperSpace.sm))

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search models or providers") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.textTertiary
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear the search",
                                tint = colors.textSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.medium,
                colors = vesperFieldColors()
            )

            Spacer(Modifier.size(VesperSpace.xs))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                if (models.isEmpty()) {
                    item(key = "settings.models.empty") {
                        VEmptyState(
                            title = "No model matches",
                            message = "Try a provider such as Anthropic, or clear the search.",
                            actionLabel = "Clear search",
                            onAction = { onQueryChange("") }
                        )
                    }
                } else {
                    items(models, key = { it.id }) { model ->
                        Column {
                            VListRow(
                                title = model.displayName,
                                subtitle = model.id,
                                onClick = {
                                    onSelect(model.id)
                                    closeSheet()
                                },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProviderChip(providerLabel(model.id))
                                        Spacer(Modifier.width(VesperSpace.xs))
                                        if (model.id == selectedModel) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Selected",
                                                tint = colors.gold,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )
                            VHairline()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(text: String) {
    val colors = Vesper.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surface3)
            .border(1.dp, colors.hairline, CircleShape)
            .padding(horizontal = VesperSpace.xs, vertical = 3.dp)
    )
}
