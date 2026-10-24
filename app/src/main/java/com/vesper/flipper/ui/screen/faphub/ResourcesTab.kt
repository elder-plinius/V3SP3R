package com.vesper.flipper.ui.screen.faphub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.FlipperResourceRepo
import com.vesper.flipper.domain.model.FlipperResourceType
import com.vesper.flipper.ui.components.VCard
import com.vesper.flipper.ui.components.VChip
import com.vesper.flipper.ui.components.VEmptyState
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VListRow
import com.vesper.flipper.ui.components.VMetricRow
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.theme.VesperSpace

/** Curated public repositories, grouped by what they contain. */
@Composable
fun ResourcesTab(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedType: FlipperResourceType?,
    onTypeSelect: (FlipperResourceType?) -> Unit,
    typeCounts: Map<FlipperResourceType, Int>,
    repos: List<FlipperResourceRepo>,
    onOpen: (FlipperResourceRepo) -> Unit
) {
    val colors = Vesper.colors

    Column(modifier = Modifier.fillMaxSize()) {
        FapHubSearchField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = "Search repositories and tags",
            modifier = Modifier.padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xs)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs),
            contentPadding = PaddingValues(horizontal = VesperSpace.gutter)
        ) {
            item(key = "resource.all") {
                VChip(
                    text = "All",
                    selected = selectedType == null,
                    onClick = { onTypeSelect(null) }
                )
            }
            items(FlipperResourceType.entries, key = { it.name }) { type ->
                VChip(
                    text = type.displayName + " " + (typeCounts[type] ?: 0),
                    selected = selectedType == type,
                    onClick = { onTypeSelect(type) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesperSpace.gutter, vertical = VesperSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = repos.size.toString() + if (repos.size == 1) " repository" else " repositories",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary
            )
        }

        if (repos.isEmpty()) {
            VEmptyState(
                title = "No repository matches",
                message = "Nothing here matches this search and type.",
                actionLabel = "Clear filters",
                onAction = {
                    onSearchChange("")
                    onTypeSelect(null)
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = VesperSpace.gutter,
                    end = VesperSpace.gutter,
                    top = VesperSpace.xxs,
                    bottom = VesperSpace.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(VesperSpace.xs)
            ) {
                items(repos, key = { it.id }) { repo ->
                    RepoRow(repo = repo, onOpen = { onOpen(repo) })
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: FlipperResourceRepo, onOpen: () -> Unit) {
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
                    imageVector = repo.resourceType.icon(),
                    contentDescription = null,
                    tint = colors.gold,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(VesperSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "by " + repo.author + " - " + formatStars(repo.stars) + " stars",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = repo.resourceType.displayName + " - " + repo.fileCount + " files",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(VesperSpace.xs))
        Text(
            text = repo.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailSheet(
    repo: FlipperResourceRepo,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = Vesper.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface3),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = repo.resourceType.icon(),
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(VesperSpace.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "by " + repo.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(VesperSpace.md))

            Text(
                text = repo.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            if (repo.tags.isNotEmpty()) {
                Spacer(Modifier.height(VesperSpace.sm))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(VesperSpace.xxs)) {
                    items(repo.tags, key = { it }) { tag ->
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surface3)
                                .border(1.dp, colors.hairline, CircleShape)
                                .padding(horizontal = VesperSpace.xs, vertical = 4.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(VesperSpace.md))

            VMetricRow(label = "Type", value = repo.resourceType.displayName)
            VMetricRow(label = "Files", value = repo.fileCount.toString())
            VMetricRow(label = "Stars", value = formatStars(repo.stars))
            VMetricRow(label = "Updated", value = repo.lastUpdated.ifBlank { "Unknown" })
            VMetricRow(label = "Flipper folder", value = repo.resourceType.flipperDir)

            VHairline(modifier = Modifier.padding(vertical = VesperSpace.sm))

            VListRow(
                title = "Open on GitHub",
                subtitle = repo.repoUrl,
                icon = Icons.Rounded.OpenInNew,
                onClick = { onOpenUrl(repo.repoUrl) }
            )

            Text(
                text = "Files live in the repository. Use the Files tab to copy what you need to the Flipper.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = VesperSpace.xs)
            )
        }
    }
}
