package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class FapHubViewModel @Inject constructor(
    private val flipperFileSystem: FlipperFileSystem
) : ViewModel() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

    /** Apps after search, category, install state, and sort. One filter chain. */
    val displayedApps: StateFlow<List<FapApp>> = combine(
        _searchQuery, _selectedCategory, _sortBy, _installedApps
    ) { query, category, sort, installed ->
        val trimmed = query.trim()
        val filtered = FapHubCatalog.allApps.asSequence()
            .filter { category == null || it.category == category }
            .filter { app ->
                trimmed.isEmpty() ||
                    app.name.contains(trimmed, ignoreCase = true) ||
                    app.description.contains(trimmed, ignoreCase = true) ||
                    app.author.contains(trimmed, ignoreCase = true)
            }
            .map { app -> app.copy(isInstalled = installed.contains(app.id)) }
            .toList()

        when (sort) {
            SortOption.DOWNLOADS -> filtered.sortedByDescending { it.downloads }
            SortOption.RATING -> filtered.sortedByDescending { it.rating }
            SortOption.NAME -> filtered.sortedBy { it.name }
            SortOption.UPDATED -> filtered.sortedByDescending { it.updatedAt }
            SortOption.CATEGORY -> filtered.sortedBy { it.category.displayName }
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
                // Resolve a direct .fap URL from the catalog download URL
                val sourceUrl = app.downloadUrl
                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(0.1f))

                // Try to find a direct .fap binary. The catalog URLs point to
                // lab.flipper.net pages, so we attempt to scrape for a .fap link.
                // If the URL already ends in .fap, use it directly.
                val binaryUrl = if (sourceUrl.endsWith(".fap", ignoreCase = true)) {
                    sourceUrl
                } else {
                    resolveFapUrl(sourceUrl, app.id)
                }

                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(0.3f))

                // Download the .fap binary
                val bytes = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(binaryUrl)
                        .header("User-Agent", "V3SP3R-FapHub/1.0")
                        .header("Accept", "application/octet-stream,*/*")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                        response.body?.bytes() ?: throw java.io.IOException("Empty response")
                    }
                }

                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(0.7f))

                // Validate — reject HTML responses (means no direct download available)
                val preview = bytes.take(48).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase()
                if (preview.startsWith("<!doctype") || preview.startsWith("<html") || preview.startsWith("<head")) {
                    throw java.io.IOException("No direct .fap download available — use the Flipper companion app or qFlipper to install ${app.name}")
                }
                if (bytes.isEmpty()) throw java.io.IOException("Downloaded file is empty")

                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Downloading(0.9f))

                // Write to Flipper storage — works on any firmware (OFW, Momentum, Unleashed, Xtreme, RogueMaster)
                // All firmwares use /ext/apps/{category}/ structure
                val installDir = "/ext/apps/${app.category.name.lowercase()}"
                flipperFileSystem.createDirectory(installDir) // ignore if exists
                val targetPath = "$installDir/${app.id}.fap"

                _installStatus.value = _installStatus.value + (app.id to InstallStatus.Installing)
                flipperFileSystem.writeFileBytes(targetPath, bytes).getOrThrow()

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

    private suspend fun resolveFapUrl(pageUrl: String, appIdHint: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", "V3SP3R-FapHub/1.0")
            .header("Accept", "text/html")
            .build()
        val html = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} fetching $pageUrl")
            response.body?.string() ?: throw java.io.IOException("Empty page")
        }
        // Find .fap URLs in the page
        val fapRegex = Regex("""href=["']([^"']*\.fap[^"']*)["']""", RegexOption.IGNORE_CASE)
        val candidates = fapRegex.findAll(html).map { it.groupValues[1] }.toList()
        if (candidates.isEmpty()) {
            // Fall back to using the original URL — download will likely fail with HTML check
            pageUrl
        } else {
            candidates.firstOrNull { it.contains(appIdHint, ignoreCase = true) } ?: candidates.first()
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
    DOWNLOADS("Most popular"),
    RATING("Highest rated"),
    NAME("Alphabetical"),
    UPDATED("Recently updated"),
    CATEGORY("By category")
}
