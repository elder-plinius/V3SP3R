package com.vesper.flipper.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Provider-scoped live catalog. Never fill a failed/empty catalog with another provider's IDs. */
@Singleton
class OpenRouterModelCatalog @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Retained for existing OpenRouter callers; new UI must pass its atomic config. */
    suspend fun fetchLatestByManufacturer(): Result<List<ModelInfo>> =
        fetchModels(AiConnectionConfig(AiProvider.OPENROUTER, null, AiProvider.OPENROUTER.defaultModel))

    suspend fun fetchModels(config: AiConnectionConfig): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        if (config.provider != AiProvider.OPENROUTER || !config.apiKey.isNullOrEmpty()) {
            apiKeyValidationError(config.apiKey)?.let {
                return@withContext Result.failure(IllegalArgumentException(it))
            }
        }
        try {
            val builder = Request.Builder().url("${config.provider.baseUrl}/models")
                .header("Accept", "application/json")
            if (!config.apiKey.isNullOrEmpty()) builder.header("Authorization", "Bearer ${config.apiKey}")
            val response = client.newCall(builder.build()).awaitProviderResponse()
            if (!response.isSuccessful) {
                val advice = when (response.code) {
                    401 -> "Check this provider's API key."
                    403 -> "Check account and key permissions."
                    429 -> "Rate limited; retry later."
                    in 300..399 -> "Redirect refused to protect credentials."
                    else -> "Retry later or paste an exact model ID from this provider's catalog."
                }
                return@withContext Result.failure(IOException("${config.provider.displayName} models API error ${response.code}. $advice"))
            }
            val root = json.parseToJsonElement(response.body ?: throw IOException()).jsonObject
            val models = root["data"] as? JsonArray
                ?: return@withContext Result.failure(IOException("Invalid model catalog response. Paste an exact model ID instead."))
            Result.success(models.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val idValue = item["id"] as? JsonPrimitive ?: return@mapNotNull null
                if (!idValue.isString) return@mapNotNull null
                val id = idValue.content
                if (modelIdValidationError(id) != null) return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
                ModelInfo(id, name, config.provider.displayName)
            }.distinctBy { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(IOException("Unable to load ${config.provider.displayName} models. Check connectivity and API access, or paste an exact model ID."))
        }
    }
}
