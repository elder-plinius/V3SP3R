package com.vesper.flipper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vesper_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // OpenRouter API Key
    private val API_KEY = stringPreferencesKey("openrouter_api_key")

    val apiKey: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[API_KEY]
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = key
        }
    }

    // Selected Model
    private val SELECTED_MODEL = stringPreferencesKey("selected_model")

    val selectedModel: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_MODEL] ?: DEFAULT_MODEL
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_MODEL] = model
        }
    }

    // Last Connected Device
    private val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
    private val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
    private val LAST_CHAT_SESSION_ID = stringPreferencesKey("last_chat_session_id")

    val lastDeviceAddress: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_DEVICE_ADDRESS]
    }

    val lastDeviceName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_DEVICE_NAME]
    }

    suspend fun setLastDevice(address: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_DEVICE_ADDRESS] = address
            preferences[LAST_DEVICE_NAME] = name
        }
    }

    val lastChatSessionId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_CHAT_SESSION_ID]
    }

    suspend fun setLastChatSessionId(sessionId: String?) {
        context.dataStore.edit { preferences ->
            if (sessionId.isNullOrBlank()) {
                preferences.remove(LAST_CHAT_SESSION_ID)
            } else {
                preferences[LAST_CHAT_SESSION_ID] = sessionId
            }
        }
    }

    // Auto-connect setting
    private val AUTO_CONNECT = booleanPreferencesKey("auto_connect")

    val autoConnect: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CONNECT] ?: true
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CONNECT] = enabled
        }
    }

    // Default project path on Flipper
    private val DEFAULT_PROJECT_PATH = stringPreferencesKey("default_project_path")

    val defaultProjectPath: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_PROJECT_PATH] ?: "/ext/apps_data/vesper"
    }

    suspend fun setDefaultProjectPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_PROJECT_PATH] = path
        }
    }

    // Permission auto-grant duration (milliseconds)
    private val PERMISSION_DURATION = longPreferencesKey("permission_duration")

    val permissionDuration: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PERMISSION_DURATION] ?: 15 * 60 * 1000L // 15 minutes default
    }

    suspend fun setPermissionDuration(durationMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[PERMISSION_DURATION] = durationMs
        }
    }

    // Auto-approve by risk tier
    private val AUTO_APPROVE_MEDIUM = booleanPreferencesKey("auto_approve_medium")
    private val AUTO_APPROVE_HIGH = booleanPreferencesKey("auto_approve_high")

    val autoApproveMedium: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_APPROVE_MEDIUM] ?: false
    }

    suspend fun setAutoApproveMedium(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_APPROVE_MEDIUM] = enabled
        }
    }

    val autoApproveHigh: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_APPROVE_HIGH] ?: false
    }

    suspend fun setAutoApproveHigh(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_APPROVE_HIGH] = enabled
        }
    }

    // Haptic feedback
    private val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")

    val hapticFeedback: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HAPTIC_FEEDBACK] ?: true
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAPTIC_FEEDBACK] = enabled
        }
    }

    // Dark mode
    private val DARK_MODE = booleanPreferencesKey("dark_mode")

    val darkMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DARK_MODE] ?: true
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DARK_MODE] = enabled
        }
    }

    // Audit log retention (days)
    private val AUDIT_RETENTION_DAYS = intPreferencesKey("audit_retention_days")

    val auditRetentionDays: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AUDIT_RETENTION_DAYS] ?: 30
    }

    suspend fun setAuditRetentionDays(days: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUDIT_RETENTION_DAYS] = days
        }
    }

    // AI agent max model/tool loop iterations
    private val AI_MAX_ITERATIONS = intPreferencesKey("ai_max_iterations")

    val aiMaxIterations: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[AI_MAX_ITERATIONS] ?: DEFAULT_AI_MAX_ITERATIONS)
            .coerceIn(MIN_AI_MAX_ITERATIONS, MAX_AI_MAX_ITERATIONS)
    }

    suspend fun setAiMaxIterations(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[AI_MAX_ITERATIONS] = value.coerceIn(MIN_AI_MAX_ITERATIONS, MAX_AI_MAX_ITERATIONS)
        }
    }

    // TTS (routed through OpenRouter — no separate key needed)
    private val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
    private val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
    private val TTS_AUTO_SPEAK = booleanPreferencesKey("tts_auto_speak")

    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TTS_ENABLED] ?: false
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TTS_ENABLED] = enabled
        }
    }

    val ttsVoiceId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TTS_VOICE_ID] ?: DEFAULT_TTS_VOICE
    }

    suspend fun setTtsVoiceId(voiceId: String) {
        context.dataStore.edit { preferences ->
            preferences[TTS_VOICE_ID] = voiceId
        }
    }

    val ttsAutoSpeak: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TTS_AUTO_SPEAK] ?: false
    }

    suspend fun setTtsAutoSpeak(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TTS_AUTO_SPEAK] = enabled
        }
    }

    companion object {
        // Default to the largest Hermes 4 model on OpenRouter.
        const val DEFAULT_MODEL = "nousresearch/hermes-4-405b"
        // Shimmer: soft, warm female — default TTS voice (OpenAI via OpenRouter)
        const val DEFAULT_TTS_VOICE = "shimmer"
        const val DEFAULT_AI_MAX_ITERATIONS = 10
        const val MIN_AI_MAX_ITERATIONS = 4
        const val MAX_AI_MAX_ITERATIONS = 20

        // Used when fetching live catalog fails (offline/rate-limited).
        val FALLBACK_MODELS = listOf(
            ModelInfo("nousresearch/hermes-4-405b", "Hermes 4 405B", "Largest Hermes 4"),
            ModelInfo("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", "Latest Anthropic"),
            ModelInfo("openai/gpt-oss-120b", "GPT-OSS 120B", "Latest OpenAI"),
            ModelInfo("google/gemini-2.5-flash-image-preview", "Gemini 2.5 Flash Image Preview", "Latest Google"),
            ModelInfo("meta-llama/llama-3.3-8b-instruct", "Llama 3.3 8B Instruct", "Latest Meta"),
            ModelInfo("mistralai/devstral-small", "Devstral Small", "Latest Mistral"),
            ModelInfo("x-ai/grok-4-fast", "Grok 4 Fast", "Latest xAI"),
            ModelInfo("qwen/qwen3-coder", "Qwen3 Coder", "Latest Qwen"),
            ModelInfo("deepseek/deepseek-r1-0528", "DeepSeek R1 0528", "Latest DeepSeek"),
            ModelInfo("cohere/command-a", "Command A", "Latest Cohere"),
            ModelInfo("moonshotai/kimi-k2", "Kimi K2", "Latest Moonshot"),
            ModelInfo("z-ai/glm-4.5", "GLM 4.5", "Latest Z.ai")
        )

        fun getModelDisplayName(
            modelId: String,
            availableModels: List<ModelInfo> = FALLBACK_MODELS
        ): String {
            return availableModels.find { it.id == modelId }?.displayName ?: modelId
        }
    }
}

/**
 * Model information for display in settings
 */
data class ModelInfo(
    val id: String,           // OpenRouter model ID
    val displayName: String,  // User-friendly name
    val description: String   // Short description
)
