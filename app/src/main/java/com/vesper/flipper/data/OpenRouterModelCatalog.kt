package com.vesper.flipper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches OpenRouter's live model catalog and selects one latest model
 * for each major manufacturer/provider used by the app.
 */
@Singleton
class OpenRouterModelCatalog @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Last catalog that was successfully fetched this process; avoids a network round-trip per Settings open. */
    @Volatile
    private var cachedCatalog: List<ModelInfo>? = null

    /**
     * Offline-safe accessor: returns the live catalog when reachable, the last
     * cached catalog, or [SettingsStore.FALLBACK_MODELS]. Never returns an
     * empty list, so the model picker always has something selectable.
     */
    suspend fun loadModels(): List<ModelInfo> =
        fetchLatestByManufacturer()
            .onSuccess { cachedCatalog = it }
            .getOrElse { cachedCatalog ?: SettingsStore.FALLBACK_MODELS }

    suspend fun fetchLatestByManufacturer(): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(MODELS_URL)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("OpenRouter models API error: ${response.code}")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty model catalog response"))

                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"]?.jsonArray
                    ?: return@withContext Result.failure(IOException("Invalid model catalog payload"))

                val remoteModels = data.mapNotNull { element ->
                    // A single malformed entry must not fail the whole catalog.
                    val modelObj = element as? JsonObject ?: return@mapNotNull null
                    val id = modelObj.string("id") ?: return@mapNotNull null
                    RemoteModel(
                        id = id,
                        name = modelObj.string("name") ?: id.substringAfter("/"),
                        created = modelObj.long("created") ?: 0L
                    )
                }

                val selectedByProvider = MAJOR_MANUFACTURERS.mapNotNull { manufacturer ->
                    val candidates = remoteModels.filter { providerFromId(it.id) == manufacturer.providerId }
                    if (candidates.isEmpty()) return@mapNotNull null

                    val stableCandidates = candidates.filterNot { model ->
                        model.id.contains(":free", ignoreCase = true) ||
                                model.id.contains(":beta", ignoreCase = true) ||
                                model.id.contains(":preview", ignoreCase = true)
                    }

                    val latest = (if (stableCandidates.isNotEmpty()) stableCandidates else candidates)
                        .maxByOrNull { it.created }
                        ?: return@mapNotNull null

                    ModelInfo(
                        id = latest.id,
                        displayName = latest.name,
                        description = "Latest ${manufacturer.displayName}"
                    )
                }

                if (selectedByProvider.isEmpty()) {
                    return@withContext Result.success(SettingsStore.FALLBACK_MODELS)
                }


                val selectedByProviderId = selectedByProvider.associateBy { providerFromId(it.id) }.toMutableMap()
                SettingsStore.FALLBACK_MODELS.forEach { fallback ->
                    selectedByProviderId.putIfAbsent(providerFromId(fallback.id), fallback)
                }

                val ordered = MAJOR_MANUFACTURERS.mapNotNull { manufacturer ->
                    selectedByProviderId[manufacturer.providerId]
                }.ifEmpty { SettingsStore.FALLBACK_MODELS }

                Result.success(ordered)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun providerFromId(modelId: String): String = modelId.substringBefore("/")

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        val content = if (value.isString) value.content else value.toString()
        return content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.longOrNull
    }

    companion object {
        private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

        private data class Manufacturer(
            val providerId: String,
            val displayName: String
        )

        private data class RemoteModel(
            val id: String,
            val name: String,
            val created: Long
        )

        private val MAJOR_MANUFACTURERS = listOf(
            Manufacturer("nousresearch", "Nous Research"),
            Manufacturer("anthropic", "Anthropic"),
            Manufacturer("openai", "OpenAI"),
            Manufacturer("google", "Google"),
            Manufacturer("meta-llama", "Meta"),
            Manufacturer("mistralai", "Mistral"),
            Manufacturer("x-ai", "xAI"),
            Manufacturer("qwen", "Qwen"),
            Manufacturer("deepseek", "DeepSeek"),
            Manufacturer("cohere", "Cohere"),
            Manufacturer("moonshotai", "Moonshot"),
            Manufacturer("z-ai", "Z.ai")
        )
    }
}
