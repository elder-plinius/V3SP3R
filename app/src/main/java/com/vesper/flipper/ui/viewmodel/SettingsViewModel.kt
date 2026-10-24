package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.model.ProtectedPaths
import com.vesper.flipper.domain.service.AuditService
import com.vesper.flipper.domain.service.PermissionService
import com.vesper.flipper.security.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Settings state.
 *
 * The OpenRouter key is deliberately absent from this object. The screen only
 * ever receives [apiKeyHint] (a masked tail) and the editable draft held in
 * [ApiKeyUiState]; the plaintext key never enters the UI state, a log line, or
 * a crash report.
 */
data class SettingsState(
    val hasApiKey: Boolean = false,
    val apiKeyHint: String = "",
    val selectedModel: String = SettingsStore.DEFAULT_MODEL,
    val aiMaxIterations: Int = SettingsStore.DEFAULT_AI_MAX_ITERATIONS,
    val autoConnect: Boolean = true,
    val defaultProjectPath: String = "/ext/apps_data/vesper",
    val permissionDuration: Long = Permission.DURATION_15_MINUTES,
    val hapticFeedback: Boolean = true,
    val darkMode: Boolean = true,
    val autoApproveMedium: Boolean = false,
    val autoApproveHigh: Boolean = false,
    val auditRetentionDays: Int = 30,
    val activePermissions: List<Permission> = emptyList(),
    // TTS (via OpenRouter — uses the same API key)
    val ttsEnabled: Boolean = false,
    val ttsVoiceId: String = SettingsStore.DEFAULT_TTS_VOICE,
    val ttsAutoSpeak: Boolean = false,
    // Smart glasses
    val glassesEnabled: Boolean = false,
    val glassesBridgeUrl: String = "",
    val glassesAutoSend: Boolean = true,
    val glassesAutoConnect: Boolean = false,
    val glassesSailorMouth: Boolean = false,
    val glassesMuted: Boolean = false
)

/** Editable API key field state. The draft is cleared after a successful save. */
data class ApiKeyUiState(
    val draft: String = "",
    val revealed: Boolean = false,
    val formatError: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val testing: Boolean = false,
    val testResult: KeyTestResult? = null
)

sealed interface KeyTestResult {
    data class Success(val message: String) : KeyTestResult
    data class Failure(val message: String) : KeyTestResult
}

/** A protected path from [ProtectedPaths] plus its live unlock state. */
data class LockedPath(
    val path: String,
    val label: String,
    val detail: String,
    val unlocked: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterModelCatalog: OpenRouterModelCatalog,
    private val permissionService: PermissionService,
    private val auditService: AuditService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _keyUi = MutableStateFlow(ApiKeyUiState())
    val keyUi: StateFlow<ApiKeyUiState> = _keyUi.asStateFlow()

    private val _availableModels = MutableStateFlow(SettingsStore.FALLBACK_MODELS)
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _modelQuery = MutableStateFlow("")
    val modelQuery: StateFlow<String> = _modelQuery.asStateFlow()

    private val _lockedPaths = MutableStateFlow(defaultLockedPaths())
    val lockedPaths: StateFlow<List<LockedPath>> = _lockedPaths.asStateFlow()

    private val _isClearingData = MutableStateFlow(false)
    val isClearingData: StateFlow<Boolean> = _isClearingData.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Models matching the picker search. */
    val filteredModels: StateFlow<List<ModelInfo>> = combine(
        _availableModels,
        _modelQuery
    ) { models, query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            models
        } else {
            models.filter { model ->
                model.displayName.contains(trimmed, ignoreCase = true) ||
                    model.id.contains(trimmed, ignoreCase = true) ||
                    model.description.contains(trimmed, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.FALLBACK_MODELS)

    /**
     * Check-only HTTP client. It sends the credential once, in an
     * Authorization header, and never logs the request.
     */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        loadSettings()
        observePermissions()
        refreshAvailableModels()
        refreshLockedPaths()
    }

    // ── Settings plumbing ────────────────────────────────────────────────

    /**
     * Mirrors one preferences flow into [SettingsState]. One coroutine per
     * setting keeps the mapping obvious and avoids a nine-deep combine.
     */
    private fun <T> bind(flow: Flow<T>, apply: (SettingsState, T) -> SettingsState) {
        viewModelScope.launch {
            flow.collect { value -> _state.update { current -> apply(current, value) } }
        }
    }

    private fun loadSettings() {
        bind(settingsStore.apiKey) { state, key ->
            state.copy(hasApiKey = !key.isNullOrBlank(), apiKeyHint = maskKey(key))
        }
        bind(settingsStore.selectedModel) { state, value -> state.copy(selectedModel = value) }
        bind(settingsStore.aiMaxIterations) { state, value -> state.copy(aiMaxIterations = value) }
        bind(settingsStore.autoConnect) { state, value -> state.copy(autoConnect = value) }
        bind(settingsStore.defaultProjectPath) { state, value -> state.copy(defaultProjectPath = value) }
        bind(settingsStore.permissionDuration) { state, value -> state.copy(permissionDuration = value) }
        bind(settingsStore.hapticFeedback) { state, value -> state.copy(hapticFeedback = value) }
        bind(settingsStore.darkMode) { state, value -> state.copy(darkMode = value) }
        bind(settingsStore.autoApproveMedium) { state, value -> state.copy(autoApproveMedium = value) }
        bind(settingsStore.autoApproveHigh) { state, value -> state.copy(autoApproveHigh = value) }
        bind(settingsStore.auditRetentionDays) { state, value -> state.copy(auditRetentionDays = value) }
        bind(settingsStore.ttsEnabled) { state, value -> state.copy(ttsEnabled = value) }
        bind(settingsStore.ttsVoiceId) { state, value -> state.copy(ttsVoiceId = value) }
        bind(settingsStore.ttsAutoSpeak) { state, value -> state.copy(ttsAutoSpeak = value) }
        bind(settingsStore.glassesEnabled) { state, value -> state.copy(glassesEnabled = value) }
        bind(settingsStore.glassesBridgeUrl) { state, value -> state.copy(glassesBridgeUrl = value.orEmpty()) }
        bind(settingsStore.glassesAutoSend) { state, value -> state.copy(glassesAutoSend = value) }
        bind(settingsStore.glassesAutoConnect) { state, value -> state.copy(glassesAutoConnect = value) }
        bind(settingsStore.glassesSailorMouth) { state, value -> state.copy(glassesSailorMouth = value) }
        bind(settingsStore.glassesMuted) { state, value -> state.copy(glassesMuted = value) }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            permissionService.activePermissions.collect { permissions ->
                _state.update { it.copy(activePermissions = permissions) }
            }
        }
    }

    // ── API key ──────────────────────────────────────────────────────────

    fun updateKeyDraft(value: String) {
        _keyUi.update {
            it.copy(draft = value, formatError = null, saved = false, testResult = null)
        }
    }

    fun toggleKeyReveal() {
        _keyUi.update { it.copy(revealed = !it.revealed) }
    }

    /**
     * Persists the draft. Nothing is written while typing: the store is only
     * touched on Done, focus loss, or an explicit save.
     */
    fun saveApiKey() {
        val draft = _keyUi.value.draft.trim()
        val error = validateKey(draft)
        if (error != null) {
            _keyUi.update { it.copy(formatError = error) }
            return
        }
        if (_keyUi.value.saving) return

        viewModelScope.launch {
            _keyUi.update { it.copy(saving = true, formatError = null, testResult = null) }
            val result = runCatching { settingsStore.setApiKey(draft) }
            _keyUi.update {
                it.copy(
                    saving = false,
                    saved = result.isSuccess,
                    draft = if (result.isSuccess) "" else it.draft,
                    formatError = if (result.isSuccess) null else "The key could not be saved. Try again."
                )
            }
            if (result.isSuccess) {
                delay(4_000)
                _keyUi.update { it.copy(saved = false) }
            }
        }
    }

    /** Checks the draft when there is one, otherwise the saved key. */
    fun testConnection() {
        if (_keyUi.value.testing) return
        val draft = _keyUi.value.draft.trim()

        viewModelScope.launch {
            _keyUi.update { it.copy(testing = true, testResult = null) }
            val key = draft.ifBlank {
                runCatching { settingsStore.apiKey.first() }.getOrNull().orEmpty()
            }

            val formatError = validateKey(key)
            if (formatError != null) {
                _keyUi.update {
                    it.copy(testing = false, testResult = KeyTestResult.Failure(formatError))
                }
                return@launch
            }

            val result = probeKey(key)
            _keyUi.update {
                it.copy(
                    testing = false,
                    testResult = result.fold(
                        onSuccess = {
                            KeyTestResult.Success(
                                if (draft.isNotBlank()) {
                                    "Key accepted. Save it to start using it."
                                } else {
                                    "Connected. OpenRouter accepted the saved key."
                                }
                            )
                        },
                        onFailure = { failure ->
                            KeyTestResult.Failure(failure.message ?: "The connection check failed.")
                        }
                    )
                )
            }
        }
    }

    /** Removes the stored key. The draft is left alone so a typo stays recoverable. */
    fun clearApiKey() {
        viewModelScope.launch {
            runCatching { settingsStore.setApiKey("") }
            _keyUi.update { it.copy(testResult = null, saved = false) }
            _message.value = "Saved key removed"
        }
    }

    private fun validateKey(key: String): String? = when {
        key.isBlank() -> "Enter an OpenRouter key first"
        !key.startsWith("sk-or-") -> "OpenRouter keys start with sk-or-"
        key.length < 20 -> "That key is too short to be complete"
        !InputValidator.isValidApiKey(key) -> "That key has characters OpenRouter does not use"
        else -> null
    }

    /**
     * Confirms the credential against OpenRouter's key endpoint.
     *
     * The response body is never read: only the status code matters, so no
     * account metadata is retained.
     */
    private suspend fun probeKey(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(KEY_ENDPOINT)
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Accept", "application/json")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)
                    response.code == 401 || response.code == 403 ->
                        Result.failure(IOException("OpenRouter rejected this key. Check that it was copied in full."))
                    response.code == 429 ->
                        Result.failure(IOException("OpenRouter is rate-limiting checks. Try again in a minute."))
                    else -> Result.failure(IOException("OpenRouter answered with HTTP " + response.code + "."))
                }
            }
        } catch (e: UnknownHostException) {
            Result.failure(IOException("No network. Check the connection and try again."))
        } catch (e: SocketTimeoutException) {
            Result.failure(IOException("OpenRouter did not answer in time. Try again."))
        } catch (e: Exception) {
            Result.failure(IOException("Could not reach OpenRouter. Check the connection."))
        }
    }

    // ── Models ───────────────────────────────────────────────────────────

    fun setModelQuery(query: String) {
        _modelQuery.value = query
    }

    fun setSelectedModel(model: String) {
        viewModelScope.launch {
            settingsStore.setSelectedModel(model)
            _message.value = "Model updated"
        }
    }

    fun setAiMaxIterations(value: Int) {
        viewModelScope.launch { settingsStore.setAiMaxIterations(value) }
    }

    fun refreshAvailableModels() {
        if (_isRefreshingModels.value) return

        viewModelScope.launch {
            _isRefreshingModels.value = true
            openRouterModelCatalog.fetchLatestByManufacturer()
                .onSuccess { models ->
                    val merged = (
                        models + SettingsStore.FALLBACK_MODELS.filter { it.id == SettingsStore.DEFAULT_MODEL }
                        ).distinctBy { it.id }
                    _availableModels.value = merged
                }
                .onFailure {
                    _availableModels.value = SettingsStore.FALLBACK_MODELS
                }
            _isRefreshingModels.value = false
        }
    }

    fun getModelDisplayName(modelId: String): String {
        val displayList = (_availableModels.value + SettingsStore.FALLBACK_MODELS).distinctBy { it.id }
        return SettingsStore.getModelDisplayName(modelId, displayList)
    }

    // ── Device ───────────────────────────────────────────────────────────

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoConnect(enabled) }
    }

    fun setDefaultProjectPath(path: String) {
        viewModelScope.launch { settingsStore.setDefaultProjectPath(path) }
    }

    fun setPermissionDuration(durationMs: Long) {
        viewModelScope.launch { settingsStore.setPermissionDuration(durationMs) }
    }

    fun grantProjectPermission() {
        val path = _state.value.defaultProjectPath.trim()
        if (path.isBlank()) {
            _message.value = "Set a project path first"
            return
        }
        permissionService.grantProjectScope(path)
        _message.value = "Project scope granted to " + path.trimEnd('/') + "/"
    }

    fun revokePermission(permissionId: String) {
        permissionService.revokePermission(permissionId)
    }

    fun revokeAllPermissions() {
        permissionService.revokeAll()
        refreshLockedPaths()
        _message.value = "All permissions revoked"
    }

    // ── Auto-approve ─────────────────────────────────────────────────────

    fun setAutoApproveMedium(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoApproveMedium(enabled) }
    }

    fun setAutoApproveHigh(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoApproveHigh(enabled) }
    }

    // ── Locked paths ─────────────────────────────────────────────────────

    fun refreshLockedPaths() {
        _lockedPaths.value = defaultLockedPaths().map { path ->
            path.copy(unlocked = permissionService.isProtectedPathUnlocked(path.path))
        }
    }

    fun unlockPath(path: String, durationMs: Long) {
        permissionService.unlockProtectedPath(path, durationMs)
        refreshLockedPaths()
        _message.value = path + " unlocked for " + formatDurationShort(durationMs)
    }

    fun lockPath(path: String) {
        permissionService.lockProtectedPath(path)
        refreshLockedPaths()
        _message.value = path + " locked"
    }

    // ── Experience ───────────────────────────────────────────────────────

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDarkMode(enabled) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHapticFeedback(enabled) }
    }

    // ── TTS ──────────────────────────────────────────────────────────────

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setTtsEnabled(enabled) }
    }

    fun setTtsVoiceId(voiceId: String) {
        viewModelScope.launch { settingsStore.setTtsVoiceId(voiceId) }
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setTtsAutoSpeak(enabled) }
    }

    // ── Privacy and data ─────────────────────────────────────────────────

    fun setAuditRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsStore.setAuditRetentionDays(days)
            _message.value = "Audit entries older than " + days + " days will be removed"
        }
    }

    /** Drops entries older than the configured retention window. */
    fun pruneAuditLog() {
        if (_isClearingData.value) return
        val days = _state.value.auditRetentionDays
        viewModelScope.launch {
            _isClearingData.value = true
            val result = runCatching { auditService.clearOlderThan(days) }
            _message.value = if (result.isSuccess) {
                "Removed entries older than " + days + " days"
            } else {
                "The log could not be pruned"
            }
            _isClearingData.value = false
        }
    }

    fun clearAuditLog() {
        if (_isClearingData.value) return
        viewModelScope.launch {
            _isClearingData.value = true
            val result = runCatching { auditService.clearAll() }
            _message.value = if (result.isSuccess) "Audit log cleared" else "The audit log could not be cleared"
            _isClearingData.value = false
        }
    }

    // ── Smart glasses ────────────────────────────────────────────────────

    fun setGlassesEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGlassesEnabled(enabled) }
    }

    fun setGlassesBridgeUrl(url: String) {
        viewModelScope.launch { settingsStore.setGlassesBridgeUrl(url.ifBlank { null }) }
    }

    fun setGlassesAutoSend(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGlassesAutoSend(enabled) }
    }

    fun setGlassesAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGlassesAutoConnect(enabled) }
    }

    fun setGlassesSailorMouth(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGlassesSailorMouth(enabled) }
    }

    fun setGlassesMuted(muted: Boolean) {
        viewModelScope.launch { settingsStore.setGlassesMuted(muted) }
    }

    // ── Messages ─────────────────────────────────────────────────────────

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private const val KEY_ENDPOINT = "https://openrouter.ai/api/v1/key"

        /**
         * A masked tail for display. Short values are hidden entirely rather
         * than being partially revealed.
         */
        fun maskKey(key: String?): String {
            if (key.isNullOrBlank()) return ""
            val trimmed = key.trim()
            return if (trimmed.length <= 10) "sk-…" else "sk-…" + trimmed.takeLast(4)
        }

        fun formatDurationShort(durationMs: Long): String = when (durationMs) {
            Permission.DURATION_5_MINUTES -> "5 minutes"
            Permission.DURATION_15_MINUTES -> "15 minutes"
            Permission.DURATION_1_HOUR -> "1 hour"
            Permission.DURATION_SESSION -> "24 hours"
            else -> (durationMs / 60_000).toString() + " minutes"
        }

        /** Every path pattern the executor refuses without an explicit unlock. */
        fun defaultLockedPaths(): List<LockedPath> {
            val paths = ProtectedPaths.SYSTEM_PATHS + ProtectedPaths.FIRMWARE_PATHS
            return paths.distinct().map { path ->
                LockedPath(
                    path = path,
                    label = labelFor(path),
                    detail = detailFor(path),
                    unlocked = false
                )
            }
        }

        private fun labelFor(path: String): String = when (path) {
            "/int/" -> "Internal storage"
            "/int/.region" -> "Region file"
            "/int/manifest.txt" -> "Firmware manifest"
            "/ext/.region" -> "SD region file"
            "/int/update/" -> "Firmware staging"
            "/ext/update/" -> "SD update staging"
            else -> path
        }

        private fun detailFor(path: String): String = when (path) {
            "/int/" -> "Firmware and system files"
            "/int/.region" -> "Device region settings"
            "/int/manifest.txt" -> "Installed firmware manifest"
            "/ext/.region" -> "Region settings on the SD card"
            "/int/update/" -> "Update files on internal storage"
            "/ext/update/" -> "Update files on the SD card"
            else -> "Protected by the executor"
        }
    }
}
