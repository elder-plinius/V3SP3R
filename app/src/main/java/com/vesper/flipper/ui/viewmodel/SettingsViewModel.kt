package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.LlmProvider
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.service.PermissionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val llmProvider: LlmProvider = LlmProvider.OPEN_ROUTER,
    val apiKey: String = "",
    val miniMaxApiKey: String = "",
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
    // TTS (via OpenRouter — uses same API key)
    val ttsEnabled: Boolean = false,
    val ttsVoiceId: String = SettingsStore.DEFAULT_TTS_VOICE,
    val ttsAutoSpeak: Boolean = false,
    // Smart Glasses
    val glassesEnabled: Boolean = false,
    val glassesBridgeUrl: String = "",
    val glassesAutoSend: Boolean = true,
    val glassesAutoConnect: Boolean = false,
    val glassesSailorMouth: Boolean = false,
    val glassesMuted: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterModelCatalog: OpenRouterModelCatalog,
    private val permissionService: PermissionService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _availableModels = MutableStateFlow(SettingsStore.FALLBACK_MODELS)
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    init {
        loadSettings()
        observePermissions()
        refreshAvailableModels()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsStore.apiKey,
                settingsStore.selectedModel,
                settingsStore.aiMaxIterations,
                settingsStore.autoConnect,
                settingsStore.defaultProjectPath,
                settingsStore.permissionDuration,
                settingsStore.hapticFeedback,
                settingsStore.darkMode,
                settingsStore.auditRetentionDays
            ) { values ->
                SettingsState(
                    apiKey = (values[0] as? String) ?: "",
                    selectedModel = values[1] as String,
                    aiMaxIterations = values[2] as Int,
                    autoConnect = values[3] as Boolean,
                    defaultProjectPath = values[4] as String,
                    permissionDuration = values[5] as Long,
                    hapticFeedback = values[6] as Boolean,
                    darkMode = values[7] as Boolean,
                    auditRetentionDays = values[8] as Int
                )
            }.combine(
                combine(
                    settingsStore.autoApproveMedium,
                    settingsStore.autoApproveHigh,
                    settingsStore.ttsEnabled,
                    settingsStore.ttsVoiceId,
                    settingsStore.ttsAutoSpeak,
                    settingsStore.miniMaxApiKey,
                    settingsStore.llmProvider
                ) { values ->
                    TtsSettingsBundle(
                        autoApproveMedium = values[0] as Boolean,
                        autoApproveHigh = values[1] as Boolean,
                        ttsEnabled = values[2] as Boolean,
                        ttsVoiceId = values[3] as String,
                        ttsAutoSpeak = values[4] as Boolean,
                        miniMaxApiKey = (values[5] as? String) ?: "",
                        llmProvider = values[6] as LlmProvider
                    )
                }
            ) { base, tts ->
                base.copy(
                    autoApproveMedium = tts.autoApproveMedium,
                    autoApproveHigh = tts.autoApproveHigh,
                    ttsEnabled = tts.ttsEnabled,
                    ttsVoiceId = tts.ttsVoiceId,
                    ttsAutoSpeak = tts.ttsAutoSpeak,
                    miniMaxApiKey = tts.miniMaxApiKey,
                    llmProvider = tts.llmProvider
                )
            }.combine(
                combine(
                    settingsStore.glassesEnabled,
                    settingsStore.glassesBridgeUrl,
                    settingsStore.glassesAutoSend,
                    settingsStore.glassesAutoConnect,
                    settingsStore.glassesSailorMouth,
                    settingsStore.glassesMuted
                ) { values ->
                    GlassesSettingsBundle(
                        glassesEnabled = values[0] as Boolean,
                        glassesBridgeUrl = (values[1] as? String) ?: "",
                        glassesAutoSend = values[2] as Boolean,
                        glassesAutoConnect = values[3] as Boolean,
                        glassesSailorMouth = values[4] as Boolean,
                        glassesMuted = values[5] as Boolean
                    )
                }
            ) { base, glasses ->
                base.copy(
                    glassesEnabled = glasses.glassesEnabled,
                    glassesBridgeUrl = glasses.glassesBridgeUrl,
                    glassesAutoSend = glasses.glassesAutoSend,
                    glassesAutoConnect = glasses.glassesAutoConnect,
                    glassesSailorMouth = glasses.glassesSailorMouth,
                    glassesMuted = glasses.glassesMuted
                )
            }.collect { settings ->
                _state.update { it.copy(
                    llmProvider = settings.llmProvider,
                    apiKey = settings.apiKey,
                    miniMaxApiKey = settings.miniMaxApiKey,
                    selectedModel = settings.selectedModel,
                    aiMaxIterations = settings.aiMaxIterations,
                    autoConnect = settings.autoConnect,
                    defaultProjectPath = settings.defaultProjectPath,
                    permissionDuration = settings.permissionDuration,
                    autoApproveMedium = settings.autoApproveMedium,
                    autoApproveHigh = settings.autoApproveHigh,
                    hapticFeedback = settings.hapticFeedback,
                    darkMode = settings.darkMode,
                    auditRetentionDays = settings.auditRetentionDays,
                    ttsEnabled = settings.ttsEnabled,
                    ttsVoiceId = settings.ttsVoiceId,
                    ttsAutoSpeak = settings.ttsAutoSpeak,
                    glassesEnabled = settings.glassesEnabled,
                    glassesBridgeUrl = settings.glassesBridgeUrl,
                    glassesAutoSend = settings.glassesAutoSend,
                    glassesAutoConnect = settings.glassesAutoConnect,
                    glassesSailorMouth = settings.glassesSailorMouth,
                    glassesMuted = settings.glassesMuted
                )}
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            permissionService.activePermissions.collect { permissions ->
                _state.update { it.copy(activePermissions = permissions) }
            }
        }
    }

    fun setLlmProvider(provider: LlmProvider) {
        viewModelScope.launch {
            settingsStore.setLlmProvider(provider)
            // Switch model to the default for the new provider
            val defaultModel = when (provider) {
                LlmProvider.MINIMAX -> SettingsStore.DEFAULT_MINIMAX_MODEL
                LlmProvider.OPEN_ROUTER -> SettingsStore.DEFAULT_MODEL
            }
            settingsStore.setSelectedModel(defaultModel)
            _state.update { it.copy(llmProvider = provider, selectedModel = defaultModel) }
            // Refresh model list for the new provider
            _availableModels.value = when (provider) {
                LlmProvider.MINIMAX -> SettingsStore.MINIMAX_MODELS
                LlmProvider.OPEN_ROUTER -> SettingsStore.FALLBACK_MODELS
            }
            if (provider == LlmProvider.OPEN_ROUTER) {
                refreshAvailableModels()
            }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            settingsStore.setApiKey(key)
            _state.update { it.copy(apiKey = key) }
        }
    }

    fun setMiniMaxApiKey(key: String) {
        viewModelScope.launch {
            settingsStore.setMiniMaxApiKey(key)
            _state.update { it.copy(miniMaxApiKey = key) }
        }
    }

    fun setSelectedModel(model: String) {
        viewModelScope.launch {
            settingsStore.setSelectedModel(model)
            _state.update { it.copy(selectedModel = model) }
        }
    }

    fun setAiMaxIterations(value: Int) {
        viewModelScope.launch {
            settingsStore.setAiMaxIterations(value)
            _state.update { it.copy(aiMaxIterations = value) }
        }
    }

    fun refreshAvailableModels() {
        if (_isRefreshingModels.value) return

        viewModelScope.launch {
            // MiniMax models are static — no catalog fetch needed
            if (_state.value.llmProvider == LlmProvider.MINIMAX) {
                _availableModels.value = SettingsStore.MINIMAX_MODELS
                return@launch
            }

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

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoConnect(enabled)
            _state.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setDefaultProjectPath(path: String) {
        viewModelScope.launch {
            settingsStore.setDefaultProjectPath(path)
            _state.update { it.copy(defaultProjectPath = path) }
        }
    }

    fun setPermissionDuration(durationMs: Long) {
        viewModelScope.launch {
            settingsStore.setPermissionDuration(durationMs)
            _state.update { it.copy(permissionDuration = durationMs) }
        }
    }

    fun setAutoApproveMedium(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveMedium(enabled)
            _state.update { it.copy(autoApproveMedium = enabled) }
        }
    }

    fun setAutoApproveHigh(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveHigh(enabled)
            _state.update { it.copy(autoApproveHigh = enabled) }
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setHapticFeedback(enabled)
            _state.update { it.copy(hapticFeedback = enabled) }
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDarkMode(enabled)
            _state.update { it.copy(darkMode = enabled) }
        }
    }

    fun setAuditRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsStore.setAuditRetentionDays(days)
            _state.update { it.copy(auditRetentionDays = days) }
        }
    }

    fun revokePermission(permissionId: String) {
        permissionService.revokePermission(permissionId)
    }

    fun revokeAllPermissions() {
        permissionService.revokeAll()
    }

    fun grantProjectPermission() {
        val path = _state.value.defaultProjectPath
        permissionService.grantProjectScope(path)
    }

    // TTS settings
    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsEnabled(enabled)
            _state.update { it.copy(ttsEnabled = enabled) }
        }
    }

    fun setTtsVoiceId(voiceId: String) {
        viewModelScope.launch {
            settingsStore.setTtsVoiceId(voiceId)
            _state.update { it.copy(ttsVoiceId = voiceId) }
        }
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsAutoSpeak(enabled)
            _state.update { it.copy(ttsAutoSpeak = enabled) }
        }
    }

    // Smart Glasses settings
    fun setGlassesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesEnabled(enabled)
            _state.update { it.copy(glassesEnabled = enabled) }
        }
    }

    fun setGlassesBridgeUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setGlassesBridgeUrl(url.ifBlank { null })
            _state.update { it.copy(glassesBridgeUrl = url) }
        }
    }

    fun setGlassesAutoSend(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoSend(enabled)
            _state.update { it.copy(glassesAutoSend = enabled) }
        }
    }

    fun setGlassesAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoConnect(enabled)
            _state.update { it.copy(glassesAutoConnect = enabled) }
        }
    }

    fun setGlassesSailorMouth(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesSailorMouth(enabled)
            _state.update { it.copy(glassesSailorMouth = enabled) }
        }
    }

    fun setGlassesMuted(muted: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesMuted(muted)
            _state.update { it.copy(glassesMuted = muted) }
        }
    }
}

private data class TtsSettingsBundle(
    val autoApproveMedium: Boolean,
    val autoApproveHigh: Boolean,
    val ttsEnabled: Boolean,
    val ttsVoiceId: String,
    val ttsAutoSpeak: Boolean,
    val miniMaxApiKey: String,
    val llmProvider: LlmProvider
)

private data class GlassesSettingsBundle(
    val glassesEnabled: Boolean,
    val glassesBridgeUrl: String,
    val glassesAutoSend: Boolean,
    val glassesAutoConnect: Boolean,
    val glassesSailorMouth: Boolean,
    val glassesMuted: Boolean
)
