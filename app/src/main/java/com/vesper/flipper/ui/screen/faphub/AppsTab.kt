package com.vesper.flipper.ui.screen.faphub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FapApp
import com.vesper.flipper.domain.model.FapCategory
import com.vesper.flipper.domain.model.InstallStatus
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VErrorBanner
import com.vesper.flipper.ui.components.VPrimaryButton
import com.vesper.flipper.ui.components.VSkeletonList
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace
import com.vesper.flipper.ui.viewmodel.SortOption

/**
 * Apps tab: search, categories, sort, and the install surface.
 * Empty, loading, and error each get a real state instead of a blank list.
 */
@Composable
fun AppsTab(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: FapCategory?,
    onCategorySelect: (FapCategory?) -> Unit,
    categoryCounts: Map<FapCategory, Int>,
    sortBy: SortOption,
    onSortChange: (SortOption) -> Unit,
    apps: List<FapApp>,
    installStatus: Map<String, InstallStatus>,
    isLoading: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (FapApp) -> Unit,
    onOpen: (FapApp) -> Unit
) {
    val colors = Vesper.colors
    var sortOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        FapHubSearchField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = "Search apps by name, author, or use",
            modifier = Modifier.padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xs)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs),
            contentPadding = PaddingValues(horizontal = VesperSpace.gutter)
        ) {
            item(key = "category.all") {
                VChip(
                    text = "All",
                    selected = selectedCategory == null,
                    onClick = { onCategorySelect(null) }
                )
            }
            items(FapCategory.entries, key = { it.name }) { category ->
                VChip(
                    text = category.displayName + " " + (categoryCounts[category] ?: 0),
                    selected = selectedCategory == category,
                    onClick = { onCategorySelect(category) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.gutter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = apps.size.toString() + if (apps.size == 1) " app" else " apps",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary
            )
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Sort,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(VesperSpace.xxs))
                    Text(
                        text = sortBy.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textSecondary
                    )
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (option == sortBy) colors.gold else colors.textPrimary
                                )
                            },
                            onClick = {
                                onSortChange(option)
                                sortOpen = false
                            },
                            leadingIcon = {
                                if (option == sortBy) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = colors.gold,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }
        }

        if (error != null) {
            val failedApp = apps.firstOrNull { installStatus[it.id] is InstallStatus.Error }
            VErrorBanner(
                message = error,
                onRetry = if (failedApp != null) {
                    { onInstall(failedApp) }
                } else {
                    onRetry
                },
                onDismiss = onDismissError,
                visible = true
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading && apps.isEmpty() -> VSkeletonList(rows = 6)

                apps.isEmpty() -> {
                    val filtered = searchQuery.isNotBlank() || selectedCategory != null
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        VEmptyState(
                            title = if (filtered) "No app matches" else "The catalog is empty",
                            message = if (filtered) {
                                "Nothing in the catalog matches this search and category."
                            } else {
                                "The app list could not be loaded. Refresh to try again."
                            },
                            actionLabel = if (filtered) "Clear filters" else "Refresh the catalog",
                            onAction = if (filtered) {
                                {
                                    onSearchChange("")
                                    onCategorySelect(null)
                                }
                            } else {
                                onRetry
                            },
                            secondaryLabel = if (filtered) "Refresh the catalog" else null,
                            onSecondary = if (filtered) onRetry else null
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = VesperSpace.gutter,
                        end = VesperSpace.gutter,
                        top = VesperSpace.xxs,
                        bottom = VesperSpace.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
                ) {
                    items(apps, key = { it.id }) { app ->
                        AppRow(
                            app = app,
                            status = installStatus[app.id],
                            onInstall = { onInstall(app) },
                            onOpen = { onOpen(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: FapApp,
    status: InstallStatus?,
    onInstall: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = Vesper.colors
    VCard(level = 1, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface3),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = app.category.icon(),
                    contentDescription = null,
                    tint = colors.gold,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "by " + app.author + " - v" + app.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.category.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(VesperSpace.xs))
            InstallControl(
                status = status,
                isInstalled = app.isInstalled,
                onInstall = onInstall
            )
        }
    }
}

@Composable
private fun InstallControl(
    status: InstallStatus?,
    isInstalled: Boolean,
    onInstall: () -> Unit
) {
    val colors = Vesper.colors
    when (status) {
        is InstallStatus.Downloading -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = status.progress,
                modifier = Modifier.size(36.dp),
                color = colors.gold,
                strokeWidth = 2.dp
            )
            Text(
                text = (status.progress * 100).toInt().toString() + "%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }

        is InstallStatus.Installing -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = colors.gold,
                strokeWidth = 2.dp
            )
        }

        is InstallStatus.Success -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Installed",
                tint = colors.success,
                modifier = Modifier.size(26.dp)
            )
        }

        is InstallStatus.Error -> IconButton(onClick = onInstall) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Install failed. Tap to retry",
                tint = colors.riskHigh,
                modifier = Modifier.size(24.dp)
            )
        }

        else -> if (isInstalled) {
            Row(
                modifier = Modifier.height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(VesperSpace.xxs))
                Text(
                    text = "Installed",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.success
                )
            }
        } else {
            VPrimaryButton(
                text = "Install",
                onClick = onInstall,
                compact = false
            )
        }
    }
}
