package com.vesper.flipper.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit provider identity; credentials and model IDs belong to this namespace. */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val catalogUrl: String,
    val defaultModel: String
) {
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/models", "nousresearch/hermes-4-405b"),
    ORCAROUTER("orcarouter", "OrcaRouter", "https://api.orcarouter.ai/v1", "https://www.orcarouter.ai/console/catalog", ""),
    // Enum name retained for source compatibility; this is the explicitly confirmed abliteration.ai service.
    ABLITERATED("abliteration", "Abliteration.ai", "https://api.abliteration.ai/v1", "https://docs.abliteration.ai/models", "");

    companion object {
        fun fromId(id: String?): AiProvider = entries.find { it.id == id || it.name.lowercase() == id } ?: OPENROUTER
    }
}

/** An immutable connection snapshot. Never include credentials in diagnostics. */
data class AiConnectionConfig(val provider: AiProvider, val apiKey: String?, val model: String) {
    fun validationError(): String? = apiKeyValidationError(apiKey) ?: modelIdValidationError(model)
    override fun toString(): String = "AiConnectionConfig(provider=$provider, apiKey=<redacted>, model=$model)"
}

fun apiKeyValidationError(key: String?): String? = when {
    key.isNullOrEmpty() -> "Enter an API key for the selected provider."
    !Regex("[A-Za-z0-9._~+/-]+=*").matches(key) ->
        "API key must be an unchanged bearer token without whitespace. Copy it again from the provider."
    else -> null
}

/** Reject malformed pasted IDs; never trim, lowercase, or reinterpret an identifier. */
fun modelIdValidationError(model: String): String? = when {
    model.isBlank() -> "Enter the exact model ID from the provider's model catalog."
    model.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } ->
        "Model IDs cannot contain whitespace or control characters. Paste the exact catalog ID."
    model.contains("://") || model.any { it == '\"' || it == '\'' || it == '\\' } ->
        "Enter a model ID, not a URL or a quoted label."
    else -> null
}

internal data class ProviderHttpResponse(val code: Int, val body: String?, val retryAfter: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Read and close the response on OkHttp's worker; cancellation also interrupts the body read. */
internal suspend fun Call.awaitProviderResponse(): ProviderHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use { ProviderHttpResponse(it.code, it.body?.string(), it.header("Retry-After")) }
                if (continuation.isActive) continuation.resume(value)
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
