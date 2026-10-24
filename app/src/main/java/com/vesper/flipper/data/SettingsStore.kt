package com.vesper.flipper.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.vesper.flipper.security.KeystoreSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vesper_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreSecretStore: KeystoreSecretStore
) {

    // ── OpenRouter API key ────────────────────────────────────────────────
    // The key is encrypted with an AES-256/GCM key held by the Android
    // Keystore before it is written to DataStore. KeystoreSecretStore owns the
    // crypto; this class owns the migration and the graceful degradation.

    /** Pre-encryption storage slot. Read once during migration, then removed. */
    private val LEGACY_API_KEY = stringPreferencesKey("openrouter_api_key")

    /** `v1:<iv>:<ciphertext>` envelope produced by [KeystoreSecretStore]. */
    private val ENCRYPTED_API_KEY = stringPreferencesKey("openrouter_api_key_enc")

    private val apiKeyMigrationMutex = Mutex()

    @Volatile
    private var apiKeyMigrationComplete = false

    /**
     * Decrypted OpenRouter API key, or null when unset/unreadable.
     *
     * Public contract is unchanged for the UI: a Flow of the plaintext key.
     * Decryption failures degrade to "no key configured" rather than crashing.
     */
    val apiKey: Flow<String?> = context.dataStore.data
        .onStart { migrateLegacyApiKeyIfNeeded() }
        .catch { throwable ->
            Log.w(TAG, "Settings DataStore read failed (${throwable.javaClass.simpleName}); using empty preferences")
            emit(emptyPreferences())
        }
        .map { preferences ->
            val encrypted = preferences[ENCRYPTED_API_KEY]
            if (!encrypted.isNullOrBlank()) {
                keystoreSecretStore.decrypt(encrypted)
            } else {
                preferences[LEGACY_API_KEY]?.takeIf { it.isNotBlank() }
            }
        }

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        migrateLegacyApiKeyIfNeeded()

        if (trimmed.isEmpty()) {
            context.dataStore.edit { preferences ->
                preferences.remove(ENCRYPTED_API_KEY)
                preferences.remove(LEGACY_API_KEY)
            }
            return
        }

        val encrypted = keystoreSecretStore.encrypt(trimmed)
        context.dataStore.edit { preferences ->
            if (encrypted != null) {
                preferences[ENCRYPTED_API_KEY] = encrypted
                preferences.remove(LEGACY_API_KEY)
            } else {
                // Keystore unavailable (locked/invalidated key, broken OEM
                // keystore). Stay functional: keep the key in the legacy slot
                // rather than losing the user's configuration.
                preferences[LEGACY_API_KEY] = trimmed
                preferences.remove(ENCRYPTED_API_KEY)
            }
        }
    }

    /** True when a usable key is configured. Avoids collecting [apiKey] at call sites. */
    suspend fun hasApiKey(): Boolean = apiKey.first()?.isNotBlank() == true

    /**
     * One-time migration of the plaintext key written by older app versions.
     *
     * Runs before the first emission and is idempotent: once complete the flag
     * short-circuits every later collector. If encryption is unavailable the
     * plaintext value is left where it is so the user is not logged out.
     */
    private suspend fun migrateLegacyApiKeyIfNeeded() {
        if (apiKeyMigrationComplete) return

        apiKeyMigrationMutex.withLock {
            if (apiKeyMigrationComplete) return

            val preferences = runCatching { context.dataStore.data.first() }.getOrNull()
            if (preferences == null) {
                // Transient read failure: retry on the next collection.
                return
            }

            val legacyKey = preferences[LEGACY_API_KEY]?.takeIf { it.isNotBlank() }
            val existingCiphertext = preferences[ENCRYPTED_API_KEY]?.takeIf { it.isNotBlank() }
            val staleCiphertext = existingCiphertext != null &&
                keystoreSecretStore.decrypt(existingCiphertext) == null

            when {
                legacyKey != null -> {
                    val envelope = keystoreSecretStore.encrypt(legacyKey)
                    context.dataStore.edit { editable ->
                        if (envelope != null) {
                            editable[ENCRYPTED_API_KEY] = envelope
                            editable.remove(LEGACY_API_KEY)
                        } else if (staleCiphertext) {
                            editable.remove(ENCRYPTED_API_KEY)
                        }
                    }
                    if (envelope != null) {
                        Log.i(TAG, "API key migrated to Keystore-backed storage")
                    } else {
                        Log.w(TAG, "Keystore unavailable; API key remains in plaintext storage")
                    }
                }

                staleCiphertext -> {
                    // Key was lost (factory reset / keystore wipe): drop the
                    // undecryptable blob so the UI shows "not configured".
                    context.dataStore.edit { editable -> editable.remove(ENCRYPTED_API_KEY) }
                    Log.w(TAG, "Stored API key could not be decrypted; cleared")
                }
            }

            apiKeyMigrationComplete = true
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

    // Smart Glasses Bridge
    private val GLASSES_ENABLED = booleanPreferencesKey("glasses_enabled")
    private val GLASSES_BRIDGE_URL = stringPreferencesKey("glasses_bridge_url")
    private val GLASSES_AUTO_SEND = booleanPreferencesKey("glasses_auto_send")
    private val GLASSES_AUTO_CONNECT = booleanPreferencesKey("glasses_auto_connect")
    private val GLASSES_SAILOR_MOUTH = booleanPreferencesKey("glasses_sailor_mouth")
    private val GLASSES_MUTED = booleanPreferencesKey("glasses_muted")

    val glassesEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_ENABLED] ?: false
    }

    suspend fun setGlassesEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GLASSES_ENABLED] = enabled
        }
    }

    val glassesBridgeUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_BRIDGE_URL]
    }

    suspend fun setGlassesBridgeUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(GLASSES_BRIDGE_URL)
            } else {
                preferences[GLASSES_BRIDGE_URL] = url
            }
        }
    }

    val glassesAutoSend: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_AUTO_SEND] ?: true
    }

    suspend fun setGlassesAutoSend(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GLASSES_AUTO_SEND] = enabled
        }
    }

    val glassesAutoConnect: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_AUTO_CONNECT] ?: false
    }

    suspend fun setGlassesAutoConnect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GLASSES_AUTO_CONNECT] = enabled
        }
    }

    val glassesSailorMouth: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_SAILOR_MOUTH] ?: false
    }

    suspend fun setGlassesSailorMouth(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GLASSES_SAILOR_MOUTH] = enabled
        }
    }

    val glassesMuted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GLASSES_MUTED] ?: false
    }

    suspend fun setGlassesMuted(muted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GLASSES_MUTED] = muted
        }
    }

    companion object {
        private const val TAG = "SettingsStore"

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
