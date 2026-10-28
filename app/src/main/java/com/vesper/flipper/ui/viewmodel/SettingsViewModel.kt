package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.modelIdValidationError
import com.vesper.flipper.data.apiKeyValidationError
import com.vesper.flipper.data.AiProvider
import com.vesper.flipper.data.AiConnectionConfig
import com.vesper.flipper.ai.OpenRouterClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.service.PermissionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val provider: AiProvider = AiProvider.OPENROUTER,
    val providerReady: Boolean = false,
    val providerMessage: String? = null,
    val providerMessageIsError: Boolean = false,
    val catalogMessage: String? = null,
    val isTestingProvider: Boolean = false,
    val isSavingKey: Boolean = false,
    val isSavingProviderSettings: Boolean = false,
    val keySaveMessage: String? = null,
    val keySaveIsError: Boolean = false,
    val apiKey: String = "",
    val selectedModel: String = SettingsStore.DEFAULT_MODEL,
    val deviceAssistNotesDraft: String = "",
    val deviceAssistNotesSaved: String = "",
    val deviceAssistNotesReady: Boolean = false,
    val isSavingDeviceAssistNotes: Boolean = false,
    val deviceAssistNotesMessage: String? = null,
    val deviceAssistNotesMessageIsError: Boolean = false,
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
) {
    // Failed writes leave drafts dirty; only successful status belongs to the saved fields.
    fun visibleProviderMessage(dirty: Boolean): String? =
        providerMessage.takeIf { !dirty || providerMessageIsError }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterModelCatalog: OpenRouterModelCatalog,
    private val permissionService: PermissionService,
    private val aiClient: OpenRouterClient
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private var catalogJob: Job? = null
    private var testJob: Job? = null
    private var configGeneration = 0L
    private var activeConfig: AiConnectionConfig? = null
    private var providerEpoch = 0L
    private var keyDraftRevision = 0L
    private var draftRevision = 0L
    private var pendingSettingsWrites = 0
    private var providerSuccessConfig: AiConnectionConfig? = null
    private var deviceAssistRevision = 0L
    private var deviceAssistDraftEdited = false

    init {
        loadSettings()
        observePermissions()
        observeProviderSettings()
        observeDeviceAssistNotes()
    }

    private fun observeDeviceAssistNotes() {
        viewModelScope.launch {
            settingsStore.deviceAssistNotes.collect { saved ->
                _state.update { current ->
                    current.copy(
                        deviceAssistNotesSaved = saved,
                        deviceAssistNotesDraft = if (!current.deviceAssistNotesReady ||
                            !deviceAssistDraftEdited
                        ) saved else current.deviceAssistNotesDraft,
                        deviceAssistNotesReady = true
                    )
                }
            }
        }
    }

    fun editDeviceAssistNotes(notes: String) {
        if (!_state.value.deviceAssistNotesReady || _state.value.isSavingDeviceAssistNotes) return
        deviceAssistRevision++
        deviceAssistDraftEdited = true
        _state.update { it.copy(deviceAssistNotesDraft = notes,
            deviceAssistNotesMessage = null, deviceAssistNotesMessageIsError = false) }
    }

    fun saveDeviceAssistNotes() = writeDeviceAssistNotes(reset = false)

    fun resetDeviceAssistNotes() = writeDeviceAssistNotes(reset = true)

    private fun writeDeviceAssistNotes(reset: Boolean) {
        val current = _state.value
        if (!current.deviceAssistNotesReady || current.isSavingDeviceAssistNotes) return
        val notes = if (reset) "" else current.deviceAssistNotesDraft
        if (!reset && notes == current.deviceAssistNotesSaved) return
        if (reset && notes.isEmpty() && current.deviceAssistNotesSaved.isEmpty()) {
            deviceAssistRevision++
            deviceAssistDraftEdited = false
            _state.update { it.copy(deviceAssistNotesDraft = "",
                deviceAssistNotesMessage = "Unsaved Flipper Assist Notes cleared.",
                deviceAssistNotesMessageIsError = false) }
            return
        }
        if (notes.toByteArray(Charsets.UTF_8).size > SettingsStore.MAX_DEVICE_ASSIST_NOTES_BYTES) {
            _state.update { it.copy(deviceAssistNotesMessage =
                "Flipper Assist Notes exceed ${SettingsStore.MAX_DEVICE_ASSIST_NOTES_BYTES} UTF-8 bytes.",
                deviceAssistNotesMessageIsError = true) }
            return
        }
        val revision = deviceAssistRevision
        val expectedSaved = current.deviceAssistNotesSaved
        _state.update { it.copy(isSavingDeviceAssistNotes = true, deviceAssistNotesMessage = null,
            deviceAssistNotesMessageIsError = false) }
        viewModelScope.launch {
            try {
                val wrote = settingsStore.saveDeviceAssistNotesIfUnchanged(notes, expectedSaved)
                val saved = settingsStore.deviceAssistNotes.first()
                if (revision == deviceAssistRevision) {
                    if (!wrote || saved != notes) {
                        _state.update { it.copy(deviceAssistNotesSaved = saved,
                            deviceAssistNotesMessage = "Flipper Assist Notes changed elsewhere. Your draft was kept; review before saving again.",
                            deviceAssistNotesMessageIsError = true) }
                    } else {
                        deviceAssistDraftEdited = false
                        _state.update { it.copy(deviceAssistNotesSaved = saved,
                            deviceAssistNotesDraft = if (reset) "" else it.deviceAssistNotesDraft,
                            deviceAssistNotesMessage = if (reset) "Flipper Assist Notes reset."
                                else "Flipper Assist Notes saved.",
                            deviceAssistNotesMessageIsError = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (revision == deviceAssistRevision) {
                    _state.update { it.copy(deviceAssistNotesMessage =
                        "Could not ${if (reset) "reset" else "save"} Flipper Assist Notes. Please retry.",
                        deviceAssistNotesMessageIsError = true) }
                }
            }
        }.invokeOnCompletion {
            _state.update { it.copy(isSavingDeviceAssistNotes = false) }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsStore.aiMaxIterations,
                settingsStore.autoConnect,
                settingsStore.defaultProjectPath,
                settingsStore.permissionDuration,
                settingsStore.hapticFeedback,
                settingsStore.darkMode,
                settingsStore.auditRetentionDays
            ) { values ->
                SettingsState(
                    aiMaxIterations = values[0] as Int,
                    autoConnect = values[1] as Boolean,
                    defaultProjectPath = values[2] as String,
                    permissionDuration = values[3] as Long,
                    hapticFeedback = values[4] as Boolean,
                    darkMode = values[5] as Boolean,
                    auditRetentionDays = values[6] as Int
                )
            }.combine(
                combine(
                    settingsStore.autoApproveMedium,
                    settingsStore.autoApproveHigh,
                    settingsStore.ttsEnabled,
                    settingsStore.ttsVoiceId,
                    settingsStore.ttsAutoSpeak
                ) { values ->
                    TtsSettingsBundle(
                        autoApproveMedium = values[0] as Boolean,
                        autoApproveHigh = values[1] as Boolean,
                        ttsEnabled = values[2] as Boolean,
                        ttsVoiceId = values[3] as String,
                        ttsAutoSpeak = values[4] as Boolean
                    )
                }
            ) { base, tts ->
                base.copy(
                    autoApproveMedium = tts.autoApproveMedium,
                    autoApproveHigh = tts.autoApproveHigh,
                    ttsEnabled = tts.ttsEnabled,
                    ttsVoiceId = tts.ttsVoiceId,
                    ttsAutoSpeak = tts.ttsAutoSpeak
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

    private fun cancelProviderRequests() {
        configGeneration++
        catalogJob?.cancel()
        testJob?.cancel()
        _isRefreshingModels.value = false
        _availableModels.value = emptyList()
    }

    private fun observeProviderSettings() {
        viewModelScope.launch {
            settingsStore.aiConnectionConfig.distinctUntilChanged().collect { config ->
                val sameProvider = _state.value.provider == config.provider
                if (!sameProvider) providerEpoch++
                cancelProviderRequests()
                activeConfig = config
                _state.update { it.copy(
                    provider = config.provider,
                    apiKey = config.apiKey.orEmpty(),
                    selectedModel = config.model,
                    providerReady = true,
                    // A queued storage emission must not erase a write error or its confirmation.
                    providerMessage = it.providerMessage.takeIf { _ ->
                        sameProvider && (it.providerMessageIsError || providerSuccessConfig == config)
                    },
                    providerMessageIsError = sameProvider && it.providerMessageIsError,
                    keySaveMessage = it.keySaveMessage.takeIf { sameProvider },
                    keySaveIsError = sameProvider && it.keySaveIsError,
                    catalogMessage = null,
                    isTestingProvider = false
                ) }
                if (providerSuccessConfig != config) providerSuccessConfig = null
            }
        }
    }

    fun setProvider(provider: AiProvider) {
        if (!_state.value.providerReady || provider == _state.value.provider) return
        providerEpoch++
        providerSuccessConfig = null
        cancelProviderRequests()
        activeConfig = null
        _state.update { it.copy(
            provider = provider, apiKey = "", selectedModel = "", providerReady = false,
            providerMessage = null, catalogMessage = null, isTestingProvider = false,
            keySaveMessage = null, keySaveIsError = false
        ) }
        viewModelScope.launch {
            try {
                settingsStore.setProvider(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val actual = settingsStore.aiConnectionConfig.first()
                activeConfig = actual
                _state.update { it.copy(
                    provider = actual.provider, apiKey = actual.apiKey.orEmpty(),
                    selectedModel = actual.model, providerReady = true,
                    providerMessage = "Could not save provider selection. Please retry.",
                    providerMessageIsError = true
                ) }
            }
        }
    }

    /** A null key is the explicit removal action; blank keys remain invalid saves. */
    fun saveProviderSettings(key: String?, model: String) {
        if (!_state.value.providerReady || _state.value.isSavingKey || _state.value.isTestingProvider ||
            (key != null && pendingSettingsWrites > 0)) return
        val removingKey = key == null
        val provider = _state.value.provider
        val error = if (removingKey) null else
            apiKeyValidationError(key) ?: modelIdValidationError(model).takeIf { model.isNotEmpty() }
        if (error != null) {
            _state.update { it.copy(providerMessage = error, providerMessageIsError = true) }
            return
        }
        val epoch = providerEpoch
        val revision = draftRevision
        pendingSettingsWrites++
        _state.update { it.copy(isSavingProviderSettings = true, providerMessage = null,
            keySaveMessage = null, keySaveIsError = false) }
        viewModelScope.launch {
            try {
                // Key-only removal cannot overwrite a model saved before state observation.
                if (removingKey) settingsStore.setApiKey(null, provider)
                else settingsStore.saveProviderConfig(AiConnectionConfig(provider, key, model))
                val saved = settingsStore.aiConnectionConfig.first()
                if (epoch == providerEpoch && revision == draftRevision) {
                    providerSuccessConfig = saved
                    _state.update { it.copy(
                        providerMessage = if (removingKey) "Saved key removed for ${provider.displayName}."
                            else "Saved for ${provider.displayName}.",
                        providerMessageIsError = false
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == providerEpoch && revision == draftRevision) {
                    _state.update { it.copy(
                        providerMessage = if (removingKey) "Could not remove saved key. Please retry."
                            else "Could not save provider settings. Please retry.",
                        providerMessageIsError = true
                    ) }
                }
            }
        }.invokeOnCompletion {
            // Also runs if the scope is cancelled before the coroutine body starts.
            pendingSettingsWrites--
            _state.update { it.copy(isSavingProviderSettings = pendingSettingsWrites > 0) }
        }
    }

    fun testProviderConnection() {
        val config = activeConfig ?: return
        if (_state.value.isTestingProvider || _state.value.isSavingKey || pendingSettingsWrites > 0) return
        config.validationError()?.let { error ->
            _state.update { it.copy(providerMessage = error, providerMessageIsError = true) }
            return
        }
        val generation = configGeneration
        _state.update { it.copy(isTestingProvider = true, providerMessage = null) }
        testJob = viewModelScope.launch {
            try {
                val result = aiClient.testConnection(config)
                if (generation == configGeneration) {
                    providerSuccessConfig = config.takeIf { result.isSuccess }
                    _state.update { it.copy(
                        providerMessage = result.getOrElse { error -> error.message ?: "Provider test failed." },
                        providerMessageIsError = result.isFailure
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == configGeneration) {
                    _state.update { it.copy(providerMessage = "Provider test failed. Please retry.", providerMessageIsError = true) }
                }
            } finally {
                if (generation == configGeneration) {
                    _state.update { it.copy(isTestingProvider = false) }
                }
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

    fun setApiKey(key: String) = saveApiKey(_state.value.provider, key)

    /** Explicit key-only write: never reads or writes a model draft. */
    fun saveApiKey(provider: AiProvider, key: String) {
        if (!_state.value.providerReady || provider != _state.value.provider ||
            _state.value.isSavingKey || pendingSettingsWrites > 0 || _state.value.isTestingProvider) return
        apiKeyValidationError(key)?.let { error ->
            _state.update { it.copy(providerMessage = error, providerMessageIsError = true,
                keySaveMessage = error, keySaveIsError = true) }
            return
        }
        val epoch = providerEpoch
        val revision = keyDraftRevision
        val edits = draftRevision
        _state.update { it.copy(isSavingKey = true, keySaveMessage = null,
            keySaveIsError = false, providerMessage = null) }
        viewModelScope.launch {
            try {
                settingsStore.setApiKey(key, provider)
                val saved = settingsStore.aiConnectionConfig.first()
                if (epoch == providerEpoch && revision == keyDraftRevision) {
                    providerSuccessConfig = saved
                    _state.update { it.copy(keySaveMessage = "Key saved on this device.",
                        keySaveIsError = false,
                        providerMessage = "Key saved on this device.".takeIf { edits == draftRevision },
                        providerMessageIsError = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == providerEpoch && revision == keyDraftRevision) {
                    _state.update { it.copy(keySaveMessage = "Could not save key. Your edits are still here; please retry.",
                        keySaveIsError = true,
                        providerMessage = "Could not save key. Your edits are still here; please retry.",
                        providerMessageIsError = true) }
                }
            }
        }.invokeOnCompletion {
            _state.update { it.copy(isSavingKey = false) }
        }
    }

    /** Invalidate request feedback even if an edit is later undone to the saved value. */
    fun providerDraftEdited(provider: AiProvider, keyChanged: Boolean) {
        if (provider != _state.value.provider) return
        draftRevision++
        providerSuccessConfig = null
        if (keyChanged) keyDraftRevision++
        testJob?.cancel()
        configGeneration++
        catalogJob?.cancel()
        _isRefreshingModels.value = false
        _state.update { it.copy(providerMessage = null, providerMessageIsError = false,
            isTestingProvider = false,
            keySaveMessage = if (keyChanged) null else it.keySaveMessage,
            keySaveIsError = !keyChanged && it.keySaveIsError) }
    }

    fun setSelectedModel(model: String) {
        if (!_state.value.providerReady || _state.value.isSavingKey || modelIdValidationError(model) != null) return
        val provider = _state.value.provider
        viewModelScope.launch {
            settingsStore.setSelectedModel(model, provider)
        }
    }

    fun setAiMaxIterations(value: Int) {
        viewModelScope.launch {
            settingsStore.setAiMaxIterations(value)
            _state.update { it.copy(aiMaxIterations = value) }
        }
    }

    fun refreshAvailableModels() {
        val config = activeConfig ?: return
        if (_isRefreshingModels.value || _state.value.isSavingKey || pendingSettingsWrites > 0) return
        val generation = configGeneration
        _isRefreshingModels.value = true
        _state.update { it.copy(catalogMessage = null) }
        catalogJob = viewModelScope.launch {
            try {
                val result = openRouterModelCatalog.fetchModels(config)
                if (generation == configGeneration) {
                    result.onSuccess { models ->
                        _availableModels.value = models
                        _state.update { it.copy(catalogMessage = "${models.size} models loaded from ${config.provider.displayName}.") }
                    }.onFailure { error ->
                        _state.update { it.copy(catalogMessage = error.message ?: "Catalog unavailable. You can still paste an exact model ID.") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == configGeneration) {
                    _state.update { it.copy(catalogMessage = "Catalog unavailable. You can still paste an exact model ID.") }
                }
            } finally {
                if (generation == configGeneration) _isRefreshingModels.value = false
            }
        }
    }

    fun getModelDisplayName(modelId: String): String =
        SettingsStore.getModelDisplayName(modelId, _availableModels.value)

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
    val ttsAutoSpeak: Boolean
)

private data class GlassesSettingsBundle(
    val glassesEnabled: Boolean,
    val glassesBridgeUrl: String,
    val glassesAutoSend: Boolean,
    val glassesAutoConnect: Boolean,
    val glassesSailorMouth: Boolean,
    val glassesMuted: Boolean
)
