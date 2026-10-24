package com.vesper.flipper.ui.screen.signals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.SignalType
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Search field for the capture list. */
@Composable
fun ArsenalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Vesper.colors
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = VesperSpace.touchTarget),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                text = "Search captures",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textTertiary
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.wine,
            unfocusedBorderColor = colors.hairline,
            focusedContainerColor = colors.surface2,
            unfocusedContainerColor = colors.surface2,
            cursorColor = colors.rose,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary
        )
    )
}

/** Type filter chips with per-type capture counts. */
@Composable
fun ArsenalTypeFilters(
    selected: SignalType,
    counts: Map<SignalType, Int>,
    onSelect: (SignalType) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VesperSpace.xs),
        contentPadding = PaddingValues(horizontal = VesperSpace.gutter)
    ) {
        items(SignalType.entries, key = { it.name }) { type ->
            val count = counts[type] ?: 0
            VChip(
                text = if (count > 0) "${type.displayName} $count" else type.displayName,
                selected = type == selected,
                onClick = { onSelect(type) },
                modifier = Modifier.heightIn(min = VesperSpace.touchTarget)
            )
        }
    }
}
