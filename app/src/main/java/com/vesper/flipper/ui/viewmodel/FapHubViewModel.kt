package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FapHubViewModel @Inject constructor(
    private val flipperFileSystem: FlipperFileSystem
) : ViewModel() {

    // ═══════════════════════════════════════════════════════
    // TAB STATE (Apps vs Resources)
    // ═══════════════════════════════════════════════════════

    enum class HubTab { APPS, RESOURCES }

    private val _activeTab = MutableStateFlow(HubTab.APPS)
    val activeTab: StateFlow<HubTab> = _activeTab.asStateFlow()

    fun setTab(tab: HubTab) { _activeTab.value = tab }

    // ═══════════════════════════════════════════════════════
    // APPS TAB STATE (existing)
    // ═══════════════════════════════════════════════════════

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<FapCategory?>(null)
    val selectedCategory: StateFlow<FapCategory?> = _selectedCategory.asStateFlow()

    private val _sortBy = MutableStateFlow(SortOption.DOWNLOADS)
    val sortBy: StateFlow<SortOption> = _sortBy.asStateFlow()

    private val _installedApps = MutableStateFlow<Set<String>>(emptySet())
    val installedApps: StateFlow<Set<String>> = _installedApps.asStateFlow()

    private val _installStatus = MutableStateFlow<Map<String, InstallStatus>>(emptyMap())
    val installStatus: StateFlow<Map<String, InstallStatus>> = _installStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedApp = MutableStateFlow<FapApp?>(null)
    val selectedApp: StateFlow<FapApp?> = _selectedApp.asStateFlow()

    val displayedApps: StateFlow<List<FapApp>> = combine(
        _searchQuery, _selectedCategory, _sortBy, _installedApps
    ) { query, category, sort, installed ->
        var apps = FapHubCatalog.allApps
        if (category != null) apps = apps.filter { it.category == category }
        if (query.isNotEmpty()) {
            apps = FapHubCatalog.searchApps(query).let { results ->
                if (category != null) results.filter { it.category == category } else results
            }
        }
        apps = apps.map { it.copy(isInstalled = installed.contains(it.id)) }
        when (sort) {
            SortOption.DOWNLOADS -> apps.sortedByDescending { it.downloads }
            SortOption.RATING -> apps.sortedByDescending { it.rating }
            SortOption.NAME -> apps.sortedBy { it.name }
            SortOption.UPDATED -> apps.sortedByDescending { it.updatedAt }
            SortOption.CATEGORY -> apps.sortedBy { it.category.displayName }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FapHubCatalog.allApps)

    val categoryCounts: StateFlow<Map<FapCategory, Int>> = flow {
        emit(FapCategory.entries.associateWith { FapHubCatalog.getAppsByCategory(it).size })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ═══════════════════════════════════════════════════════
    // RESOURCES TAB STATE (new)
    // ═══════════════════════════════════════════════════════

    private val _resourceSearchQuery = MutableStateFlow("")
    val resourceSearchQuery: StateFlow<String> = _resourceSearchQuery.asStateFlow()

    private val _selectedResourceType = MutableStateFlow<FlipperResourceType?>(null)
    val selectedResourceType: StateFlow<FlipperResourceType?> = _selectedResourceType.asStateFlow()

    private val _selectedRepo = MutableStateFlow<FlipperResourceRepo?>(null)
    val selectedRepo: StateFlow<FlipperResourceRepo?> = _selectedRepo.asStateFlow()

    val displayedResources: StateFlow<List<FlipperResourceRepo>> = combine(
        _resourceSearchQuery, _selectedResourceType
    ) { query, type ->
        var repos = FlipperResourceLibrary.repositories
        if (type != null) repos = repos.filter { it.resourceType == type }
        if (query.isNotEmpty()) {
            repos = FlipperResourceLibrary.search(query).let { results ->
                if (type != null) results.filter { it.resourceType == type } else results
            }
        }
        repos.sortedByDescending { it.stars }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FlipperResourceLibrary.repositories)

    val resourceTypeCounts: StateFlow<Map<FlipperResourceType, Int>> = flow {
        emit(FlipperResourceType.entries.associateWith { type ->
            FlipperResourceLibrary.getByType(type).size
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ═══════════════════════════════════════════════════════

    init {
        loadInstalledApps()
    }

    // Apps Tab Actions
    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun selectCategory(category: FapCategory?) { _selectedCategory.value = category }
    fun setSortOption(option: SortOption) { _sortBy.value = option }
    fun selectApp(app: FapApp?) { _selectedApp.value = app }
    fun clearError() { _error.value = null }

    // Resources Tab Actions
    fun updateResourceSearch(query: String) { _resourceSearchQuery.value = query }
    fun selectResourceType(type: FlipperResourceType?) { _selectedResourceType.value = type }
    fun selectRepo(repo: FlipperResourceRepo?) { _selectedRepo.value = repo }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val rootResult = flipperFileSystem.listDirectory("/ext/apps")
                if (rootResult.isSuccess) {
                    val rootEntries = rootResult.getOrNull() ?: emptyList()
                    val fapEntries = rootEntries.filter { !it.isDirectory && it.name.endsWith(".fap") }
                    val dirEntries = rootEntries.filter { it.isDirectory }
                    val nestedFaps = mutableListOf<String>()
                    for (dir in dirEntries) {
                        val nestedResult = flipperFileSystem.listDirectory(dir.path)
                        if (nestedResult.isSuccess) {
                            nestedFaps.addAll(nestedResult.getOrNull().orEmpty().filter { !it.isDirectory && it.name.endsWith(".fap") }.map { it.name })
                        }
                    }
                    _installedApps.value = (fapEntries.map { it.name } + nestedFaps).map { it.removeSuffix(".fap") }.toSet()
                }
            } catch (_: Exception) { }
            finally { _isLoading.value = false }
        }
    }

    fun installApp(app: FapApp) {
        viewModelScope.launch {
            _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(0f))
            try {
                for (progress in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                    _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(progress))
                    kotlinx.coroutines.delay(200)
                }
                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Installing)
                kotlinx.coroutines.delay(500)
                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Success)
                _installedApps.value = _installedApps.value + app.id
                kotlinx.coroutines.delay(2000)
                _installStatus.value = _installStatus.value - app.id
            } catch (e: Exception) {
                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Error(e.message ?: "Unknown error"))
                _error.value = "Failed to install ${app.name}: ${e.message}"
            }
        }
    }

    fun uninstallApp(app: FapApp) {
        viewModelScope.launch {
            try {
                listOf("/ext/apps/${app.id}.fap", "/ext/apps/${app.category.name.lowercase()}/${app.id}.fap").forEach {
                    flipperFileSystem.deleteFile(it)
                }
                _installedApps.value = _installedApps.value - app.id
            } catch (e: Exception) {
                _error.value = "Failed to uninstall ${app.name}: ${e.message}"
            }
        }
    }

    fun refresh() { loadInstalledApps() }
}

enum class SortOption(val displayName: String) {
    DOWNLOADS("Most Popular"),
    RATING("Highest Rated"),
    NAME("Alphabetical"),
    UPDATED("Recently Updated"),
    CATEGORY("By Category")
}
