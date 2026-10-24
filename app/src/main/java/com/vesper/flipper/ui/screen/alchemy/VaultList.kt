package com.vesper.flipper.ui.screen.alchemy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.vesper.flipper.domain.model.LootCard
import com.vesper.flipper.domain.model.PayloadType
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Search, filter, and a count for the vault. */
@Composable
fun VaultToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: PayloadType?,
    onFilterChange: (PayloadType?) -> Unit,
    stats: Map<PayloadType, Int>,
    resultCount: Int,
    totalCount: Int
) {
    val colors = Vesper.colors
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search the vault") },
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
        colors = vaultFieldColors()
    )

    Spacer(Modifier.size(VesperSpace.xs))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = resultCount.toString() + " of " + totalCount + " files",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary
        )
        Spacer(Modifier.weight(1f))
        LazyRow(
            modifier = Modifier.padding(top = VesperSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)
        ) {
            item(key = "filter.all") {
                VChip(
                    text = "All",
                    selected = selectedFilter == null,
                    onClick = {
                        focusManager.clearFocus()
                        onFilterChange(null)
                    }
                )
            }
            val types = stats.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .map { it.key }
            items(types, key = { it.name }) { type ->
                VChip(
                    text = type.displayName + " " + (stats[type] ?: 0),
                    selected = selectedFilter == type,
                    onClick = {
                        focusManager.clearFocus()
                        onFilterChange(type)
                    }
                )
            }
        }
    }
}

/** One saved file. Delete asks for a deliberate hold before it runs. */
@Composable
fun VaultRow(
    loot: LootCard,
    onOpen: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val colors = Vesper.colors
    VListRow(
        title = loot.name,
        subtitle = loot.payloadType.displayName + " - " + formatDate(loot.capturedAt) + " - " + formatSize(loot.size),
        icon = loot.payloadType.icon(),
        onClick = onOpen,
        trailing = {
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete " + loot.fileName,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

/** Empty states that explain how the vault fills up. */
@Composable
fun VaultEmptyState(
    hasLoaded: Boolean,
    query: String,
    filter: PayloadType?,
    onClearFilters: () -> Unit,
    onRescan: () -> Unit
) {
    val filtered = query.isNotBlank() || filter != null
    when {
        filtered -> VEmptyState(
            title = "No files match",
            message = "Nothing in the vault matches this search" +
                if (filter != null) " inside " + filter.displayName else ".",
            icon = Icons.Rounded.Search,
            actionLabel = "Clear search and filter",
            onAction = onClearFilters,
            secondaryLabel = "Scan the Flipper again",
            onSecondary = onRescan
        )

        !hasLoaded -> VEmptyState(
            title = "The vault is unscanned",
            message = "Connect the Flipper and scan to list what is already saved on it. " +
                "You can also describe a payload above and deploy it.",
            icon = Icons.Rounded.FolderOpen,
            actionLabel = "Scan the Flipper",
            onAction = onRescan
        )

        else -> VEmptyState(
            title = "Nothing saved yet",
            message = "Deploy a blueprint from the Forge above, or drop files into " +
                "subghz, infrared, nfc, lfrfid, badusb, or ibutton on the Flipper. " +
                "Everything saved there appears here.",
            icon = Icons.Rounded.FolderOpen,
            actionLabel = "Scan the Flipper",
            onAction = onRescan
        )
    }
}

@Composable
private fun vaultFieldColors(): TextFieldColors {
    val colors = Vesper.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedContainerColor = colors.surface1,
        unfocusedContainerColor = colors.surface1,
        cursorColor = colors.gold,
        focusedBorderColor = colors.gold,
        unfocusedBorderColor = colors.hairline,
        focusedPlaceholderColor = colors.textTertiary,
        unfocusedPlaceholderColor = colors.textTertiary
    )
}
