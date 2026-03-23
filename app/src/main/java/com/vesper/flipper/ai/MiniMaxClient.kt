package com.vesper.flipper.ai

import android.util.Log
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.security.InputValidator
import com.vesper.flipper.security.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MiniMax API client for direct AI model interaction.
 * Uses the OpenAI-compatible chat completions endpoint at api.minimax.io.
 * Supports tool-calling with the same execute_command interface as OpenRouter.
 *
 * Key differences from OpenRouter:
 * - Direct MiniMax endpoint (no router layer)
 * - Temperature clamped to (0.0, 1.0] range
 * - Thinking tags (<think>...</think>) stripped from responses
 * - Models: MiniMax-M2.7, MiniMax-M2.7-highspeed, MiniMax-M2.5, MiniMax-M2.5-highspeed
 */
@Singleton
class MiniMaxClient @Inject constructor(
    private val settingsStore: SettingsStore
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val rateLimiter = RateLimiter(maxRequests = 30, windowMs = 60_000)

    private object RetryConfig {
        const val MAX_RETRIES = 2
        const val INITIAL_DELAY_MS = 700L
        const val MAX_DELAY_MS = 10000L
        const val BACKOFF_MULTIPLIER = 2.0
    }

    private val baseSystemPrompt = VesperPrompts.SYSTEM_PROMPT

    /**
     * Send a chat completion request with tool calling via MiniMax API.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) {
            val waitTime = rateLimiter.timeUntilReset() / 1000
            return@withContext ChatCompletionResult.Error(
                "Rate limit exceeded. Please wait ${waitTime}s before trying again."
            )
        }

        val apiKey = settingsStore.miniMaxApiKey.first()
            ?: return@withContext ChatCompletionResult.Error("MiniMax API key not configured")

        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext ChatCompletionResult.Error("Invalid MiniMax API key format")
        }

        val model = settingsStore.selectedModel.first()
        val compactMessages = trimConversation(messages)

        val glassesEnabled = settingsStore.glassesEnabled.first()
        val systemPrompt = if (glassesEnabled) {
            baseSystemPrompt + "\n\n" + VesperPrompts.SMARTGLASSES_ADDENDUM
        } else {
            baseSystemPrompt
        }

        val requestMessages = buildList {
            add(OpenRouterMessage.text(role = "system", content = systemPrompt))
            addAll(buildRequestMessages(compactMessages))
        }

        val tool = if (glassesEnabled) {
            OpenRouterClient.EXECUTE_COMMAND_TOOL
        } else {
            // Reuse OpenRouter's tool-without-glasses builder is not accessible
            // so we just use the full tool — MiniMax handles it fine
            OpenRouterClient.EXECUTE_COMMAND_TOOL
        }

        val request = buildChatRequest(
            apiKey = apiKey,
            model = model,
            messages = requestMessages,
            tools = listOf(tool),
            toolChoice = "auto",
            maxTokens = OpenRouterClient.TOOL_CALL_RESPONSE_MAX_TOKENS
        )

        val result = executeWithRetry(request)
        when (result) {
            is ChatCompletionResult.Success -> result.copy(
                content = stripThinkingTags(result.content)
            )
            is ChatCompletionResult.Error -> result
        }
    }

    /**
     * Simple text-only chat without tool calling.
     */
    suspend fun chatSimple(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) return@withContext null

        val apiKey = settingsStore.miniMaxApiKey.first() ?: return@withContext null
        if (!InputValidator.isValidApiKey(apiKey)) return@withContext null

        val model = settingsStore.selectedModel.first()
        val messages = listOf(
            OpenRouterMessage.text(role = "user", content = prompt)
        )

        val request = buildChatRequest(
            apiKey = apiKey,
            model = model,
            messages = messages,
            tools = null,
            toolChoice = null,
            maxTokens = OpenRouterClient.FORGE_RESPONSE_MAX_TOKENS
        )

        val result = executeWithRetry(request)
        when (result) {
            is ChatCompletionResult.Success ->
                stripThinkingTags(result.content).takeIf { it.isNotBlank() }
            is ChatCompletionResult.Error -> null
        }
    }

    /**
     * Simple message sending without tool calling.
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        customSystemPrompt: String? = null
    ): Result<String> {
        val messages = buildList {
            addAll(conversationHistory)
            add(ChatMessage(role = MessageRole.USER, content = message))
        }
        return sendMessagesWithoutTools(messages, customSystemPrompt)
    }

    /**
     * Message sending without tools, keeping conversation history support.
     */
    suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) {
            return@withContext Result.failure(Exception("Rate limit exceeded"))
        }

        val apiKey = settingsStore.miniMaxApiKey.first()
            ?: return@withContext Result.failure(Exception("MiniMax API key not configured"))
        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext Result.failure(Exception("Invalid MiniMax API key format"))
        }

        val model = settingsStore.selectedModel.first()
        val compactMessages = trimConversation(messages)
        val requestMessages = buildList {
            add(OpenRouterMessage.text(
                role = "system",
                content = customSystemPrompt ?: baseSystemPrompt
            ))
            addAll(buildRequestMessages(compactMessages))
        }

        val request = buildChatRequest(
            apiKey = apiKey,
            model = model,
            messages = requestMessages,
            tools = null,
            toolChoice = null,
            maxTokens = OpenRouterClient.DEFAULT_RESPONSE_MAX_TOKENS
        )

        when (val result = executeWithRetry(request)) {
            is ChatCompletionResult.Success ->
                Result.success(stripThinkingTags(result.content))
            is ChatCompletionResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    private fun buildChatRequest(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        tools: List<OpenRouterTool>?,
        toolChoice: String?,
        maxTokens: Int
    ): Request {
        val requestObj = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        msg.content?.let { put("content", it) }
                        msg.toolCalls?.let { tcs ->
                            putJsonArray("tool_calls") {
                                tcs.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", tc.type)
                                        putJsonObject("function") {
                                            put("name", tc.function.name)
                                            put("arguments", tc.function.arguments)
                                        }
                                    }
                                }
                            }
                        }
                        msg.toolCallId?.let { put("tool_call_id", it) }
                    }
                }
            }
            tools?.let { toolList ->
                putJsonArray("tools") {
                    toolList.forEach { tool ->
                        addJsonObject {
                            put("type", tool.type)
                            putJsonObject("function") {
                                put("name", tool.function.name)
                                put("description", tool.function.description)
                                put("parameters", tool.function.parameters)
                            }
                        }
                    }
                }
            }
            toolChoice?.let { put("tool_choice", it) }
            put("max_tokens", maxTokens)
            // MiniMax temperature: (0.0, 1.0] — use 0.01 as effective zero
            put("temperature", CLAMPED_TEMPERATURE)
        }

        val requestBody = requestObj.toString()
            .toRequestBody("application/json".toMediaType())

        return Request.Builder()
            .url(MINIMAX_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
    }

    private suspend fun executeWithRetry(request: Request): ChatCompletionResult {
        var lastException: Exception? = null
        var delayMs = RetryConfig.INITIAL_DELAY_MS

        repeat(RetryConfig.MAX_RETRIES) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()

                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 60
                        delay(retryAfter * 1000)
                        return@repeat
                    }

                    if (response.code in 500..599) {
                        lastException = IOException("Server error: ${response.code}")
                        delay(delayMs)
                        delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                            .coerceAtMost(RetryConfig.MAX_DELAY_MS)
                        return@repeat
                    }

                    if (!response.isSuccessful) {
                        return ChatCompletionResult.Error(
                            "MiniMax API error: ${response.code} - ${responseBody ?: "Unknown error"}"
                        )
                    }

                    if (responseBody == null) {
                        return ChatCompletionResult.Error("Empty response from MiniMax API")
                    }

                    return parseResponse(responseBody)
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                delay(delayMs)
                delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(RetryConfig.MAX_DELAY_MS)
            } catch (e: IOException) {
                if (e is UnknownHostException ||
                    e.message.orEmpty().contains("Unable to resolve host", ignoreCase = true)
                ) {
                    return ChatCompletionResult.Error(
                        "Cannot resolve api.minimax.io (DNS/network issue). Verify internet access and retry."
                    )
                }
                lastException = e
                delay(delayMs)
                delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(RetryConfig.MAX_DELAY_MS)
            } catch (e: Exception) {
                return ChatCompletionResult.Error("Request failed: ${e.message}")
            }
        }

        return ChatCompletionResult.Error(
            "Request failed after ${RetryConfig.MAX_RETRIES} attempts: ${lastException?.message}"
        )
    }

    private fun parseResponse(responseBody: String): ChatCompletionResult {
        return try {
            val root = try {
                json.parseToJsonElement(responseBody).jsonObject
            } catch (_: Exception) {
                return ChatCompletionResult.Error("Invalid JSON in MiniMax response")
            }

            val errorObj = root["error"]?.jsonObject
            if (errorObj != null) {
                val errorMsg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown API error"
                val errorCode = errorObj["code"]?.jsonPrimitive?.intOrNull
                Log.e(TAG, "MiniMax API error (code=$errorCode): $errorMsg")
                return ChatCompletionResult.Error("MiniMax API error${errorCode?.let { " $it" } ?: ""}: $errorMsg")
            }

            val apiResponse = json.decodeFromString<OpenRouterResponse>(responseBody)

            val choice = apiResponse.choices.firstOrNull()
                ?: return ChatCompletionResult.Error("No choices in MiniMax response")

            val message = choice.message
            val content = message.textContent ?: ""

            val rawToolCalls = message.toolCalls
            val toolCalls = rawToolCalls?.mapNotNull { tc ->
                if (tc.id.isBlank() || tc.function.name.isBlank()) {
                    Log.w(TAG, "Dropping malformed tool call: id='${tc.id}' name='${tc.function.name}'")
                    null
                } else {
                    ToolCall(
                        id = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                }
            }?.take(MAX_TOOL_CALLS_PER_RESPONSE)

            ChatCompletionResult.Success(
                content = content,
                toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
                model = apiResponse.model,
                tokensUsed = apiResponse.usage?.totalTokens
            )
        } catch (e: Exception) {
            ChatCompletionResult.Error("Failed to parse MiniMax response: ${e.message}")
        }
    }

    /**
     * Convert domain ChatMessage list to OpenRouterMessage list for the API request.
     * Handles tool-call chain integrity (matching assistant tool_calls with tool results).
     */
    private fun buildRequestMessages(messages: List<ChatMessage>): List<OpenRouterMessage> {
        val requestMessages = mutableListOf<OpenRouterMessage>()
        val openToolCallIds = linkedSetOf<String>()
        val unresolvedAssistantToolCallIndexes = mutableListOf<Int>()

        fun dropUnresolvedToolCalls() {
            if (openToolCallIds.isEmpty()) return
            unresolvedAssistantToolCallIndexes
                .distinct()
                .sortedDescending()
                .forEach { index ->
                    if (index in requestMessages.indices) {
                        requestMessages.removeAt(index)
                    }
                }
            unresolvedAssistantToolCallIndexes.clear()
            openToolCallIds.clear()
        }

        messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    if (openToolCallIds.isNotEmpty()) dropUnresolvedToolCalls()
                    requestMessages.add(OpenRouterMessage.text(role = "user", content = msg.content))
                }

                MessageRole.ASSISTANT -> {
                    val sanitizedToolCalls = msg.toolCalls
                        ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                        ?.take(MAX_TOOL_CALLS_PER_RESPONSE)

                    if (!sanitizedToolCalls.isNullOrEmpty()) {
                        if (openToolCallIds.isNotEmpty()) dropUnresolvedToolCalls()
                        requestMessages.add(
                            OpenRouterMessage(
                                role = "assistant",
                                content = if (msg.content.isEmpty()) null else JsonPrimitive(msg.content),
                                toolCalls = sanitizedToolCalls.map { tc ->
                                    OpenRouterToolCall(
                                        id = tc.id,
                                        type = "function",
                                        function = OpenRouterFunction(
                                            name = tc.name,
                                            arguments = tc.arguments
                                        )
                                    )
                                }
                            )
                        )
                        unresolvedAssistantToolCallIndexes.add(requestMessages.lastIndex)
                        openToolCallIds.clear()
                        openToolCallIds.addAll(sanitizedToolCalls.map { it.id })
                    } else if (msg.content.isNotEmpty()) {
                        if (openToolCallIds.isNotEmpty()) dropUnresolvedToolCalls()
                        requestMessages.add(OpenRouterMessage.text(role = "assistant", content = msg.content))
                    }
                }

                MessageRole.TOOL -> {
                    val validResults = msg.toolResults
                        .orEmpty()
                        .filter { it.toolCallId.isNotBlank() && openToolCallIds.contains(it.toolCallId) }
                        .take(MAX_TOOL_CALLS_PER_RESPONSE)

                    validResults.forEach { result ->
                        requestMessages.add(
                            OpenRouterMessage(
                                role = "tool",
                                content = JsonPrimitive(result.content),
                                toolCallId = result.toolCallId
                            )
                        )
                        openToolCallIds.remove(result.toolCallId)
                    }
                    if (openToolCallIds.isEmpty()) {
                        unresolvedAssistantToolCallIndexes.clear()
                    }
                }

                MessageRole.SYSTEM -> Unit
            }
        }

        if (openToolCallIds.isNotEmpty()) dropUnresolvedToolCalls()
        return requestMessages
    }

    private fun trimConversation(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= OpenRouterClient.MAX_CONTEXT_MESSAGES) return messages
        return messages.takeLast(OpenRouterClient.MAX_CONTEXT_MESSAGES)
    }

    companion object {
        private const val TAG = "MiniMaxClient"
        private const val MINIMAX_API_URL = "https://api.minimax.io/v1/chat/completions"
        private const val MAX_TOOL_CALLS_PER_RESPONSE = 1
        // MiniMax temperature constraint: (0.0, 1.0] — use 0.01 as effective zero
        private const val CLAMPED_TEMPERATURE = 0.01

        private val THINKING_TAG_REGEX = Regex("<think>[\\s\\S]*?</think>\\s*")

        /**
         * Strip MiniMax thinking tags from response content.
         * Some MiniMax models include <think>...</think> blocks in their output.
         */
        fun stripThinkingTags(content: String): String {
            return content.replace(THINKING_TAG_REGEX, "").trim()
        }
    }
}
