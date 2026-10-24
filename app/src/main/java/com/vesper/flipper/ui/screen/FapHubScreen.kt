package com.vesper.flipper.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vesper.flipper.ui.components.VHairline
import com.vesper.flipper.ui.components.VesperScaffold
import com.vesper.flipper.ui.screen.faphub.AppDetailSheet
import com.vesper.flipper.ui.screen.faphub.AppsTab
import com.vesper.flipper.ui.screen.faphub.RepoDetailSheet
import com.vesper.flipper.ui.screen.faphub.ResourcesTab
import com.vesper.flipper.ui.theme.Vesper
import com.vesper.flipper.ui.viewmodel.FapHubViewModel
import com.vesper.flipper.ui.viewmodel.FapHubViewModel.HubTab

/**
 * Library (FapHub).
 *
 * Apps and resource repositories in one place, sharing a search pattern and
 * the same table of contents: category chips, a sort or count line, then a
 * list with real empty, loading, and error states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FapHubScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: FapHubViewModel = hiltViewModel()
) {
    val colors = Vesper.colors
    val uriHandler = LocalUriHandler.current

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

    val resourceSearch by viewModel.resourceSearchQuery.collectAsState()
    val selectedResourceType by viewModel.selectedResourceType.collectAsState()
    val displayedResources by viewModel.displayedResources.collectAsState()
    val resourceTypeCounts by viewModel.resourceTypeCounts.collectAsState()
    val selectedRepo by viewModel.selectedRepo.collectAsState()

    VesperScaffold(
        title = "Library",
        subtitle = installedApps.size.toString() + " apps installed",
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = viewModel::refresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh installed apps"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = if (activeTab == HubTab.APPS) 0 else 1,
                containerColor = colors.surface1,
                contentColor = colors.gold,
                divider = { VHairline() },
                indicator = { positions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(
                            positions[if (activeTab == HubTab.APPS) 0 else 1]
                        ),
                        color = colors.gold
                    )
                }
            ) {
                Tab(
                    selected = activeTab == HubTab.APPS,
                    onClick = { viewModel.setTab(HubTab.APPS) },
                    text = { Text("Apps") },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Widgets,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    selectedContentColor = colors.gold,
                    unselectedContentColor = colors.textSecondary
                )
                Tab(
                    selected = activeTab == HubTab.RESOURCES,
                    onClick = { viewModel.setTab(HubTab.RESOURCES) },
                    text = { Text("Resources") },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    selectedContentColor = colors.gold,
                    unselectedContentColor = colors.textSecondary
                )
            }

            when (activeTab) {
                HubTab.APPS -> AppsTab(
                    searchQuery = searchQuery,
                    onSearchChange = viewModel::updateSearchQuery,
                    selectedCategory = selectedCategory,
                    onCategorySelect = viewModel::selectCategory,
                    categoryCounts = categoryCounts,
                    sortBy = sortBy,
                    onSortChange = viewModel::setSortOption,
                    apps = displayedApps,
                    installStatus = installStatus,
                    isLoading = isLoading,
                    error = error,
                    onDismissError = viewModel::clearError,
                    onRetry = viewModel::refresh,
                    onInstall = viewModel::installApp,
                    onOpen = viewModel::selectApp
                )

                HubTab.RESOURCES -> ResourcesTab(
                    searchQuery = resourceSearch,
                    onSearchChange = viewModel::updateResourceSearch,
                    selectedType = selectedResourceType,
                    onTypeSelect = viewModel::selectResourceType,
                    typeCounts = resourceTypeCounts,
                    repos = displayedResources,
                    onOpen = viewModel::selectRepo
                )
            }
        }
    }

    val app = selectedApp
    if (app != null) {
        // Read the live copy so the sheet flips to "Installed" as soon as the
        // install finishes; the selected object is a snapshot from tap time.
        val live = displayedApps.firstOrNull { it.id == app.id } ?: app
        AppDetailSheet(
            app = live,
            status = installStatus[live.id],
            onInstall = { viewModel.installApp(live) },
            onUninstall = { viewModel.uninstallApp(live) },
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            onDismiss = { viewModel.selectApp(null) }
        )
    }

    val repo = selectedRepo
    if (repo != null) {
        RepoDetailSheet(
            repo = repo,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            onDismiss = { viewModel.selectRepo(null) }
        )
    }
}
