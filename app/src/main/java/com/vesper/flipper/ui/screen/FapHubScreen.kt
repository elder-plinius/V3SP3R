@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vesper.flipper.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.ui.theme.*
import com.vesper.flipper.ui.viewmodel.FapHubViewModel
import com.vesper.flipper.ui.viewmodel.FapHubViewModel.HubTab
import com.vesper.flipper.ui.viewmodel.SortOption

@Composable
fun FapHubScreen(
    viewModel: FapHubViewModel = hiltViewModel()
) {
    val activeTab by viewModel.activeTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val displayedApps by viewModel.displayedApps.collectAsState()
    val installStatus by viewModel.installStatus.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val categoryCounts by viewModel.categoryCounts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedApp by viewModel.selectedApp.collectAsState()

    // Resources state
    val resourceSearch by viewModel.resourceSearchQuery.collectAsState()
    val selectedResourceType by viewModel.selectedResourceType.collectAsState()
    val displayedResources by viewModel.displayedResources.collectAsState()
    val resourceTypeCounts by viewModel.resourceTypeCounts.collectAsState()
    val selectedRepo by viewModel.selectedRepo.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D0D0D), Color(0xFF1A1A2E), Color(0xFF0D0D0D))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FapHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(color = VesperOrange.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                            Text("ARSENAL", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = VesperOrange, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                    Text("Apps & public resource libraries • ${installedApps.size} installed", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Color(0xFFFF6B00))
                }
            }

            // Tab Row
            TabRow(
                selectedTabIndex = if (activeTab == HubTab.APPS) 0 else 1,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[if (activeTab == HubTab.APPS) 0 else 1]),
                        color = VesperOrange
                    )
                }
            ) {
                Tab(
                    selected = activeTab == HubTab.APPS,
                    onClick = { viewModel.setTab(HubTab.APPS) },
                    text = { Text("Apps") },
                    icon = { Icon(Icons.Default.Apps, null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = activeTab == HubTab.RESOURCES,
                    onClick = { viewModel.setTab(HubTab.RESOURCES) },
                    text = { Text("Resources") },
                    icon = { Icon(Icons.Default.LibraryBooks, null, modifier = Modifier.size(18.dp)) }
                )
            }

            // Content
            when (activeTab) {
                HubTab.APPS -> AppsTabContent(
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.updateSearchQuery(it) },
                    selectedCategory = selectedCategory,
                    onCategorySelect = { viewModel.selectCategory(it) },
                    categoryCounts = categoryCounts,
                    sortBy = sortBy,
                    showSortMenu = showSortMenu,
                    onShowSortMenu = { showSortMenu = it },
                    onSortChange = { viewModel.setSortOption(it) },
                    apps = displayedApps,
                    installStatus = installStatus,
                    isLoading = isLoading,
                    onInstall = { viewModel.installApp(it) },
                    onUninstall = { viewModel.uninstallApp(it) },
                    onAppClick = { viewModel.selectApp(it) }
                )
                HubTab.RESOURCES -> ResourcesTabContent(
                    searchQuery = resourceSearch,
                    onSearchChange = { viewModel.updateResourceSearch(it) },
                    selectedType = selectedResourceType,
                    onTypeSelect = { viewModel.selectResourceType(it) },
                    typeCounts = resourceTypeCounts,
                    repos = displayedResources,
                    onRepoClick = { viewModel.selectRepo(it) }
                )
            }
        }

        // Error Snackbar
        error?.let { errorMessage ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") } }
            ) { Text(errorMessage) }
        }

        // App Detail Sheet
        selectedApp?.let { app ->
            AppDetailSheet(app = app, installStatus = installStatus[app.id], onInstall = { viewModel.installApp(app) }, onUninstall = { viewModel.uninstallApp(app) }, onDismiss = { viewModel.selectApp(null) })
        }

        // Repo Detail Sheet
        selectedRepo?.let { repo ->
            RepoDetailSheet(repo = repo, onDismiss = { viewModel.selectRepo(null) })
        }
    }
}

// ═══════════════════════════════════════════════════════════
// APPS TAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun AppsTabContent(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: FapCategory?,
    onCategorySelect: (FapCategory?) -> Unit,
    categoryCounts: Map<FapCategory, Int>,
    sortBy: SortOption,
    showSortMenu: Boolean,
    onShowSortMenu: (Boolean) -> Unit,
    onSortChange: (SortOption) -> Unit,
    apps: List<FapApp>,
    installStatus: Map<String, InstallStatus>,
    isLoading: Boolean,
    onInstall: (FapApp) -> Unit,
    onUninstall: (FapApp) -> Unit,
    onAppClick: (FapApp) -> Unit
) {
    Column {
        OutlinedTextField(
            value = searchQuery, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search apps...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF333333), focusedBorderColor = Color(0xFFFF6B00)),
            shape = RoundedCornerShape(12.dp)
        )

        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item {
                FilterChip(selected = selectedCategory == null, onClick = { onCategorySelect(null) }, label = { Text("All") },
                    leadingIcon = if (selectedCategory == null) {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }} else null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFF6B00), selectedLabelColor = Color.White))
            }
            items(FapCategory.entries) { cat ->
                FilterChip(selected = selectedCategory == cat, onClick = { onCategorySelect(cat) },
                    label = { Text("${cat.icon} ${cat.displayName} (${categoryCounts[cat] ?: 0})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFF6B00), selectedLabelColor = Color.White))
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${apps.size} apps", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Box {
                TextButton(onClick = { onShowSortMenu(true) }) {
                    Icon(Icons.Default.Sort, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text(sortBy.displayName)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { onShowSortMenu(false) }) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = { onSortChange(option); onShowSortMenu(false) },
                            leadingIcon = if (sortBy == option) {{ Icon(Icons.Default.Check, null) }} else null)
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFF6B00)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(apps, key = { it.id }) { app ->
                    FapAppCard(app = app, installStatus = installStatus[app.id], onInstall = { onInstall(app) }, onUninstall = { onUninstall(app) }, onClick = { onAppClick(app) })
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// RESOURCES TAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun ResourcesTabContent(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedType: FlipperResourceType?,
    onTypeSelect: (FlipperResourceType?) -> Unit,
    typeCounts: Map<FlipperResourceType, Int>,
    repos: List<FlipperResourceRepo>,
    onRepoClick: (FlipperResourceRepo) -> Unit
) {
    Column {
        OutlinedTextField(
            value = searchQuery, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search resources...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF333333), focusedBorderColor = VesperOrange),
            shape = RoundedCornerShape(12.dp)
        )

        LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item {
                FilterChip(selected = selectedType == null, onClick = { onTypeSelect(null) }, label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = VesperOrange, selectedLabelColor = Color.White))
            }
            items(FlipperResourceType.entries) { type ->
                FilterChip(
                    selected = selectedType == type, onClick = { onTypeSelect(type) },
                    label = { Text("${type.icon} ${type.displayName}") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(type.color), selectedLabelColor = Color.White)
                )
            }
        }

        Text("${repos.size} repositories", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(repos, key = { it.id }) { repo ->
                ResourceRepoCard(repo = repo, onClick = { onRepoClick(repo) })
            }
        }
    }
}

@Composable
private fun ResourceRepoCard(
    repo: FlipperResourceRepo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Type Icon
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(
                        Brush.linearGradient(listOf(Color(repo.resourceType.color), Color(repo.resourceType.color).copy(alpha = 0.5f)))
                    ),
                    contentAlignment = Alignment.Center
                ) { Text(repo.resourceType.icon, style = MaterialTheme.typography.headlineSmall) }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("by ${repo.author}", style = MaterialTheme.typography.labelSmall, color = VesperOrange)
                        Surface(color = Color(repo.resourceType.color).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                            Text(repo.resourceType.displayName, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(repo.resourceType.color), fontSize = 10.sp)
                        }
                    }
                }

                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(repo.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp), tint = Color(0xFFFFD700))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(formatStars(repo.stars), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${repo.fileCount} files", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }

            // Tags
            if (repo.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(repo.tags.take(5)) { tag ->
                        Surface(color = Color(0xFF2A2A3E), shape = RoundedCornerShape(4.dp)) {
                            Text(tag, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// REPO DETAIL SHEET
// ═══════════════════════════════════════════════════════════

@Composable
private fun RepoDetailSheet(
    repo: FlipperResourceRepo,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(
                        Brush.linearGradient(listOf(Color(repo.resourceType.color), Color(repo.resourceType.color).copy(alpha = 0.5f)))
                    ),
                    contentAlignment = Alignment.Center
                ) { Text(repo.resourceType.icon, style = MaterialTheme.typography.headlineLarge) }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("by ${repo.author}", style = MaterialTheme.typography.bodyMedium, color = VesperOrange)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp), tint = Color(0xFFFFD700))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(formatStars(repo.stars), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${repo.fileCount} files", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(repo.description, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Type", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Text("${repo.resourceType.icon} ${repo.resourceType.displayName}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Flipper Path", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Text(repo.resourceType.flipperDir, style = MaterialTheme.typography.bodyMedium, color = VesperAccent)
                }
            }

            if (repo.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Tags", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repo.tags) { tag ->
                        Surface(color = Color(0xFF2A2A3E), shape = RoundedCornerShape(6.dp)) {
                            Text(tag, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Browse on GitHub button (display only — no URL launch)
            Button(
                onClick = { /* Would open browser — repo.repoUrl */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VesperOrange),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Browse Repository", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// EXISTING APP COMPONENTS (kept from original)
// ═══════════════════════════════════════════════════════════

@Composable
private fun FapAppCard(app: FapApp, installStatus: InstallStatus?, onInstall: () -> Unit, onUninstall: () -> Unit, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(getCategoryColor(app.category), getCategoryColor(app.category).copy(alpha = 0.6f)))), contentAlignment = Alignment.Center) {
                Text(app.category.icon, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (app.isInstalled) { Spacer(modifier = Modifier.width(8.dp)); Box(modifier = Modifier.background(Color(0xFF00C853), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("Installed", style = MaterialTheme.typography.labelSmall, color = Color.White) } }
                }
                Text(app.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("by ${app.author}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF6B00))
                    Text("v${app.version}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, modifier = Modifier.size(12.dp), tint = Color.Gray); Spacer(modifier = Modifier.width(2.dp)); Text(formatDownloads(app.downloads), style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Star, null, modifier = Modifier.size(12.dp), tint = Color(0xFFFFD700)); Spacer(modifier = Modifier.width(2.dp)); Text(String.format(java.util.Locale.US, "%.1f", app.rating), style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            InstallButton(isInstalled = app.isInstalled, status = installStatus, onInstall = onInstall, onUninstall = onUninstall)
        }
    }
}

@Composable
private fun InstallButton(isInstalled: Boolean, status: InstallStatus?, onInstall: () -> Unit, onUninstall: () -> Unit) {
    when (status) {
        is InstallStatus.Downloading -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(progress = status.progress, modifier = Modifier.size(40.dp), color = Color(0xFFFF6B00), strokeWidth = 3.dp); Text("${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.White) }
        is InstallStatus.Installing -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Color(0xFFFF6B00), strokeWidth = 3.dp) }
        is InstallStatus.Success -> Icon(Icons.Default.CheckCircle, "Installed", tint = Color(0xFF00C853), modifier = Modifier.size(40.dp))
        is InstallStatus.Error -> Icon(Icons.Default.Error, "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(40.dp))
        else -> {
            if (isInstalled) OutlinedButton(onClick = onUninstall, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) }
            else Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Install") }
        }
    }
}

@Composable
private fun AppDetailSheet(app: FapApp, installStatus: InstallStatus?, onInstall: () -> Unit, onUninstall: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(getCategoryColor(app.category), getCategoryColor(app.category).copy(alpha = 0.6f)))), contentAlignment = Alignment.Center) { Text(app.category.icon, style = MaterialTheme.typography.headlineLarge) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("by ${app.author}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF6B00))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp), tint = Color.Gray); Spacer(modifier = Modifier.width(4.dp)); Text(formatDownloads(app.downloads), style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp), tint = Color.Gray); Spacer(modifier = Modifier.width(4.dp)); Text(String.format(java.util.Locale.US, "%.1f", app.rating), style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Code, null, modifier = Modifier.size(14.dp), tint = Color.Gray); Spacer(modifier = Modifier.width(4.dp)); Text("v${app.version}", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(app.description, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Category", style = MaterialTheme.typography.labelMedium, color = Color.Gray); Text("${app.category.icon} ${app.category.displayName}", style = MaterialTheme.typography.bodyMedium, color = Color.White) }
                Column(horizontalAlignment = Alignment.End) { Text("Target Firmware", style = MaterialTheme.typography.labelMedium, color = Color.Gray); Text(app.targetFirmware, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = if (app.isInstalled) onUninstall else onInstall, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (app.isInstalled) Color(0xFFFF5252) else Color(0xFFFF6B00)), contentPadding = PaddingValues(vertical = 16.dp), enabled = installStatus == null || installStatus is InstallStatus.Error) {
                when (installStatus) {
                    is InstallStatus.Downloading -> { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text("Downloading ${(installStatus.progress * 100).toInt()}%") }
                    is InstallStatus.Installing -> { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text("Installing...") }
                    is InstallStatus.Success -> { Icon(Icons.Default.CheckCircle, null); Spacer(modifier = Modifier.width(8.dp)); Text("Installed!") }
                    else -> { Icon(if (app.isInstalled) Icons.Default.Delete else Icons.Default.Download, null); Spacer(modifier = Modifier.width(8.dp)); Text(if (app.isInstalled) "Uninstall" else "Install") }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun getCategoryColor(category: FapCategory): Color = when (category) {
    FapCategory.GAMES -> Color(0xFF9C27B0); FapCategory.TOOLS -> Color(0xFF2196F3); FapCategory.NFC -> Color(0xFF4CAF50)
    FapCategory.SUBGHZ -> Color(0xFFFF9800); FapCategory.INFRARED -> Color(0xFFF44336); FapCategory.GPIO -> Color(0xFFFFEB3B)
    FapCategory.BLUETOOTH -> Color(0xFF2196F3); FapCategory.USB -> Color(0xFF607D8B); FapCategory.MEDIA -> Color(0xFFE91E63); FapCategory.MISC -> Color(0xFF795548)
}

private fun formatDownloads(count: Int): String = when { count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f); count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000f); else -> count.toString() }
private fun formatStars(count: Int): String = when { count >= 1000 -> String.format(java.util.Locale.US, "%.1fK", count / 1000f); else -> count.toString() }
