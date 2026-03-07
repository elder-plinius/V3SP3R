package com.vesper.flipper.ai

import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.security.InputValidator
import com.vesper.flipper.security.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenRouter API client for AI model interaction.
 * Handles tool-calling with the execute_command interface.
 * Includes rate limiting, retry logic, and response validation.
 */
@Singleton
class OpenRouterClient @Inject constructor(
    private val settingsStore: SettingsStore
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Rate limiter: 30 requests per minute
    private val rateLimiter = RateLimiter(maxRequests = 30, windowMs = 60_000)
    private val toolUnsupportedModels = ConcurrentHashMap<String, Long>()

    // Retry configuration
    private object RetryConfig {
        const val MAX_RETRIES = 2
        const val INITIAL_DELAY_MS = 700L
        const val MAX_DELAY_MS = 10000L
        const val BACKOFF_MULTIPLIER = 2.0
    }

    // Use centralized prompt system for consistency and maintainability
    private val systemPrompt = VesperPrompts.SYSTEM_PROMPT

    /**
     * Send a chat completion request with tool calling.
     * Includes rate limiting, retry logic with exponential backoff, and response validation.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        // Check rate limit
        if (!rateLimiter.tryAcquire()) {
            val waitTime = rateLimiter.timeUntilReset() / 1000
            return@withContext ChatCompletionResult.Error(
                "Rate limit exceeded. Please wait ${waitTime}s before trying again."
            )
        }

        val apiKey = settingsStore.apiKey.first()
            ?: return@withContext ChatCompletionResult.Error("OpenRouter API key not configured")

        // Validate API key format
        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext ChatCompletionResult.Error("Invalid API key format")
        }

        val model = settingsStore.selectedModel.first()

        val compactMessages = trimConversationForRequest(messages)
        val requestMessages = buildList {
            add(OpenRouterMessage.text(role = "system", content = systemPrompt))
            addAll(sanitizeAndBuildRequestMessages(compactMessages))
        }

        val candidateModels = buildToolModelCandidates(model)
        var lastError: ChatCompletionResult.Error? = null
        val attemptedModels = mutableListOf<String>()
        val unsupportedModels = mutableListOf<String>()

        for (candidateModel in candidateModels) {
            attemptedModels.add(candidateModel)

            if (isToolModelTemporarilyBlocked(candidateModel)) {
                unsupportedModels.add(candidateModel)
                continue
            }

            val result = executeWithRetry(
                buildToolCallingRequest(
                    apiKey = apiKey,
                    model = candidateModel,
                    messages = requestMessages
                )
            )

            when (result) {
                is ChatCompletionResult.Success -> {
                    toolUnsupportedModels.remove(candidateModel)
                    return@withContext result
                }

                is ChatCompletionResult.Error -> {
                    lastError = result

                    if (isToolResultPairingError(result.message)) {
                        return@withContext ChatCompletionResult.Error(
                            "Tool-call history is out of sync for this conversation. Start a new chat session and retry."
                        )
                    }

                    if (isToolUseUnsupportedError(result.message)) {
                        markToolModelUnsupported(candidateModel)
                        unsupportedModels.add(candidateModel)
                        continue
                    }

                    if (isModelAvailabilityError(result.message)) {
                        continue
                    }

                    return@withContext result
                }
            }
        }

        val uniqueUnsupported = unsupportedModels.distinct()
        if (uniqueUnsupported.isNotEmpty()) {
            val attemptedPreview = attemptedModels.distinct().take(MAX_ERROR_MODEL_LIST).joinToString(", ")
            return@withContext ChatCompletionResult.Error(
                "No tool-use endpoints were available for the configured model set. " +
                        "Tried: $attemptedPreview. Select a tool-capable model in Settings."
            )
        }

        lastError ?: ChatCompletionResult.Error("Unable to find a working model for tool execution.")
    }

    private fun buildToolCallingRequest(
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>
    ): Request {
        val request = OpenRouterRequest(
            model = model,
            messages = messages,
            tools = listOf(EXECUTE_COMMAND_TOOL),
            toolChoice = "auto",
            maxTokens = TOOL_CALL_RESPONSE_MAX_TOKENS
        )

        val requestBody = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())

        return Request.Builder()
            .url(OPENROUTER_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://vesper.flipper.app")
            .addHeader("X-Title", "Vesper Flipper Control")
            .post(requestBody)
            .build()
    }

    /**
     * Execute HTTP request with exponential backoff retry for transient failures.
     */
    private suspend fun executeWithRetry(request: Request): ChatCompletionResult {
        var lastException: Exception? = null
        var delayMs = RetryConfig.INITIAL_DELAY_MS

        repeat(RetryConfig.MAX_RETRIES) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()

                    // Check for rate limit from server
                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 60
                        delay(retryAfter * 1000)
                        return@repeat // Retry
                    }

                    // Check for server errors (5xx) - these are retryable
                    if (response.code in 500..599) {
                        lastException = IOException("Server error: ${response.code}")
                        delay(delayMs)
                        delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                            .coerceAtMost(RetryConfig.MAX_DELAY_MS)
                        return@repeat // Retry
                    }

                    // Client errors (4xx except 429) are not retryable
                    if (!response.isSuccessful) {
                        return ChatCompletionResult.Error(
                            "API error: ${response.code} - ${responseBody ?: "Unknown error"}"
                        )
                    }

                    if (responseBody == null) {
                        return ChatCompletionResult.Error("Empty response from API")
                    }

                    // Validate response is valid JSON
                    if (!InputValidator.isValidJson(responseBody)) {
                        return ChatCompletionResult.Error("Invalid JSON response from API")
                    }

                    return parseResponse(responseBody)
                }

            } catch (e: SocketTimeoutException) {
                lastException = e
                delay(delayMs)
                delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(RetryConfig.MAX_DELAY_MS)
            } catch (e: IOException) {
                if (isDnsResolutionFailure(e)) {
                    return ChatCompletionResult.Error(DNS_RESOLUTION_ERROR_MESSAGE)
                }
                lastException = e
                delay(delayMs)
                delayMs = (delayMs * RetryConfig.BACKOFF_MULTIPLIER).toLong()
                    .coerceAtMost(RetryConfig.MAX_DELAY_MS)
            } catch (e: Exception) {
                // Non-retryable exception
                return ChatCompletionResult.Error("Request failed: ${e.message}")
            }
        }

        return ChatCompletionResult.Error(
            "Request failed after ${RetryConfig.MAX_RETRIES} attempts: ${lastException?.message}"
        )
    }

    /**
     * Parse and validate the API response.
     */
    private fun parseResponse(responseBody: String): ChatCompletionResult {
        return try {
            val apiResponse = json.decodeFromString<OpenRouterResponse>(responseBody)

            // Validate response structure
            if (apiResponse.id.isBlank()) {
                return ChatCompletionResult.Error("Invalid response: missing ID")
            }

            val choice = apiResponse.choices.firstOrNull()
                ?: return ChatCompletionResult.Error("No choices in response")

            val message = choice.message

            // Validate tool calls if present
            val toolCalls = message.toolCalls?.mapNotNull { tc ->
                // Validate tool call structure
                if (tc.id.isBlank() || tc.function.name.isBlank()) {
                    null // Skip invalid tool calls
                } else {
                    ToolCall(
                        id = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                }
            }?.take(MAX_TOOL_CALLS_PER_RESPONSE)

            ChatCompletionResult.Success(
                content = message.content ?: "",
                toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
                model = apiResponse.model,
                tokensUsed = apiResponse.usage?.totalTokens
            )
        } catch (e: Exception) {
            ChatCompletionResult.Error("Failed to parse response: ${e.message}")
        }
    }

    private fun sanitizeAndBuildRequestMessages(messages: List<ChatMessage>): List<OpenRouterMessage> {
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
                    if (openToolCallIds.isNotEmpty()) {
                        // Provider APIs reject unresolved tool-call chains; drop stale assistant tool calls.
                        dropUnresolvedToolCalls()
                    }
                    if (!msg.imageAttachments.isNullOrEmpty()) {
                        val images = msg.imageAttachments.map { attachment ->
                            ImageContent(
                                base64Data = attachment.base64Data,
                                mimeType = attachment.mimeType
                            )
                        }
                        requestMessages.add(
                            OpenRouterMessage.multimodal(
                                role = "user",
                                text = msg.content,
                                images = images
                            )
                        )
                    } else {
                        requestMessages.add(OpenRouterMessage.text(role = "user", content = msg.content))
                    }
                }

                MessageRole.ASSISTANT -> {
                    val sanitizedToolCalls = msg.toolCalls
                        ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                        ?.take(MAX_TOOL_CALLS_PER_RESPONSE)

                    if (!sanitizedToolCalls.isNullOrEmpty()) {
                        if (openToolCallIds.isNotEmpty()) {
                            dropUnresolvedToolCalls()
                        }
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
                        if (openToolCallIds.isNotEmpty()) {
                            dropUnresolvedToolCalls()
                        }
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

        if (openToolCallIds.isNotEmpty()) {
            dropUnresolvedToolCalls()
        }

        return requestMessages
    }

    /**
     * Parse tool call arguments into ExecuteCommand
     */
    fun parseCommand(arguments: String): ExecuteCommand? {
        return parseCommandDetailed(arguments).command
    }

    data class ParsedCommand(
        val command: ExecuteCommand? = null,
        val error: String? = null
    )

    /**
     * Parse tool call arguments and include a diagnostic error on failure.
     */
    fun parseCommandDetailed(arguments: String): ParsedCommand {
        val trimmedArguments = arguments.trim()
        if (trimmedArguments.isEmpty()) {
            return ParsedCommand(
                error = "Tool arguments were empty. Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
        }

        // Fast-path: strict decoding when the model follows schema perfectly.
        runCatching {
            return ParsedCommand(command = json.decodeFromString<ExecuteCommand>(trimmedArguments))
        }

        // Fallback: tolerate missing non-critical fields and args shape variations.
        return runCatching {
            val rootElement = json.parseToJsonElement(trimmedArguments)
            val root = rootElement as? JsonObject ?: return ParsedCommand(
                error = "Tool arguments must be a JSON object, got ${describeJsonType(rootElement)}. " +
                        "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
            val actionValue = root["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (actionValue.isEmpty()) {
                return ParsedCommand(
                    error = "Missing required field \"action\". " +
                            "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
                )
            }
            val action = parseCommandAction(actionValue) ?: return ParsedCommand(
                error = "Unsupported action \"$actionValue\". " +
                        "Supported actions: ${SUPPORTED_ACTIONS.joinToString(", ")}"
            )

            val argsElement = root["args"] ?: root["parameters"]
            val argsObject = when (argsElement) {
                null -> JsonObject(emptyMap())
                is JsonObject -> argsElement
                is JsonPrimitive -> {
                    val inner = argsElement.contentOrNull.orEmpty()
                    if (inner.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        val nested = runCatching { json.parseToJsonElement(inner) }.getOrElse {
                            return ParsedCommand(
                                error = "Field \"args\" must be a JSON object. " +
                                        "Could not parse args string: ${sanitizeErrorMessage(it.message)}"
                            )
                        }
                        nested as? JsonObject ?: return ParsedCommand(
                            error = "Field \"args\" must be a JSON object, got ${describeJsonType(nested)}."
                        )
                    }
                }
                else -> return ParsedCommand(
                    error = "Field \"args\" must be a JSON object, got ${describeJsonType(argsElement)}."
                )
            }

            fun stringArg(primary: String, vararg aliases: String): String? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    argsObject[key]?.jsonPrimitive?.contentOrNull
                        ?: root[key]?.jsonPrimitive?.contentOrNull
                }
            }

            fun booleanArg(primary: String, vararg aliases: String): Boolean? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    val value = argsObject[key]?.jsonPrimitive
                        ?: root[key]?.jsonPrimitive
                        ?: return@firstNotNullOfOrNull null
                    value.booleanOrNull ?: when (value.contentOrNull?.lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> null
                    }
                }
            }

            val args = CommandArgs(
                command = stringArg("command", "cmd", "query", "app_id", "appId", "app", "package", "name"),
                path = stringArg("path", "file_path", "filepath"),
                destinationPath = stringArg("destination_path", "destinationPath", "dest", "destination"),
                content = stringArg("content", "text", "data", "download_url", "downloadUrl", "url"),
                newName = stringArg("new_name", "newName"),
                recursive = booleanArg("recursive", "is_recursive") ?: false,
                artifactType = stringArg("artifact_type", "artifactType"),
                artifactData = stringArg("artifact_data", "artifactData", "data_base64"),
                prompt = stringArg("prompt", "description", "forge_prompt"),
                resourceType = stringArg("resource_type", "resourceType", "type"),
                runbookId = stringArg("runbook_id", "runbookId", "runbook"),
                payloadType = stringArg("payload_type", "payloadType"),
                filter = stringArg("filter", "vault_filter")
            )

            val missingArgs = missingRequiredArgs(action, args)
            if (missingArgs.isNotEmpty()) {
                return ParsedCommand(
                    error = "Action \"${action.name.lowercase()}\" is missing required argument(s): " +
                            "${missingArgs.joinToString(", ")}. Example: ${requiredArgsExample(action)}"
                )
            }

            ParsedCommand(
                command = ExecuteCommand(
                    action = action,
                    args = args,
                    justification = root["justification"]?.jsonPrimitive?.contentOrNull
                        ?: "Tool call requested by AI",
                    expectedEffect = root["expected_effect"]?.jsonPrimitive?.contentOrNull
                        ?: "Execute requested operation safely."
                )
            )
        }.getOrElse {
            ParsedCommand(
                error = "Could not parse tool arguments JSON: ${sanitizeErrorMessage(it.message)}. " +
                        "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
        }
    }

    private fun parseCommandAction(value: String): CommandAction? {
        val normalized = value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        return when (normalized) {
            "list_directory" -> CommandAction.LIST_DIRECTORY
            "read_file" -> CommandAction.READ_FILE
            "write_file" -> CommandAction.WRITE_FILE
            "create_directory" -> CommandAction.CREATE_DIRECTORY
            "delete" -> CommandAction.DELETE
            "move" -> CommandAction.MOVE
            "rename" -> CommandAction.RENAME
            "copy" -> CommandAction.COPY
            "get_device_info" -> CommandAction.GET_DEVICE_INFO
            "get_storage_info" -> CommandAction.GET_STORAGE_INFO
            "search_faphub", "faphub_search", "find_faphub" -> CommandAction.SEARCH_FAPHUB
            "install_faphub_app", "install_faphub", "faphub_install" -> CommandAction.INSTALL_FAPHUB_APP
            "push_artifact" -> CommandAction.PUSH_ARTIFACT
            "execute_cli", "execute_command", "run_command", "cli_command", "command", "send_command" ->
                CommandAction.EXECUTE_CLI
            "forge_payload", "forge", "craft_payload", "create_payload" -> CommandAction.FORGE_PAYLOAD
            "search_resources", "browse_resources", "find_resources" -> CommandAction.SEARCH_RESOURCES
            "list_vault", "vault", "scan_vault", "inventory" -> CommandAction.LIST_VAULT
            "run_runbook", "runbook", "diagnostic" -> CommandAction.RUN_RUNBOOK
            else -> null
        }
    }

    private fun describeJsonType(element: JsonElement): String {
        return when {
            element is JsonObject -> "object"
            element is JsonArray -> "array"
            element == JsonNull -> "null"
            element is JsonPrimitive && element.isString -> "string"
            element is JsonPrimitive -> "primitive"
            else -> "unknown"
        }
    }

    private fun sanitizeErrorMessage(message: String?): String {
        return message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.take(180)
            ?.takeIf { it.isNotEmpty() }
            ?: "invalid format"
    }

    private fun missingRequiredArgs(action: CommandAction, args: CommandArgs): List<String> {
        return when (action) {
            CommandAction.LIST_DIRECTORY -> emptyList()
            CommandAction.READ_FILE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.WRITE_FILE -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.content.isNullOrBlank()) "content" else null
            )
            CommandAction.CREATE_DIRECTORY -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.DELETE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.MOVE -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.destinationPath.isNullOrBlank()) "destination_path" else null
            )
            CommandAction.RENAME -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.newName.isNullOrBlank()) "new_name" else null
            )
            CommandAction.COPY -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.destinationPath.isNullOrBlank()) "destination_path" else null
            )
            CommandAction.GET_DEVICE_INFO -> emptyList()
            CommandAction.GET_STORAGE_INFO -> emptyList()
            CommandAction.SEARCH_FAPHUB -> listOfNotNull(if (args.command.isNullOrBlank()) "query" else null)
            CommandAction.INSTALL_FAPHUB_APP -> listOfNotNull(if (args.command.isNullOrBlank()) "app_id" else null)
            CommandAction.PUSH_ARTIFACT -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.artifactData.isNullOrBlank()) "artifact_data" else null
            )
            CommandAction.EXECUTE_CLI -> {
                if (args.command.isNullOrBlank() && args.content.isNullOrBlank()) {
                    listOf("command")
                } else {
                    emptyList()
                }
            }
            CommandAction.FORGE_PAYLOAD -> {
                if (args.prompt.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("prompt")
                } else {
                    emptyList()
                }
            }
            CommandAction.SEARCH_RESOURCES -> emptyList()  // query and resource_type are optional
            CommandAction.LIST_VAULT -> emptyList()  // filter is optional
            CommandAction.RUN_RUNBOOK -> {
                if (args.runbookId.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("runbook_id")
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun requiredArgsExample(action: CommandAction): String {
        return when (action) {
            CommandAction.LIST_DIRECTORY -> """{"action":"list_directory","args":{"path":"/ext"}}"""
            CommandAction.READ_FILE -> """{"action":"read_file","args":{"path":"/ext/file.txt"}}"""
            CommandAction.WRITE_FILE ->
                """{"action":"write_file","args":{"path":"/ext/file.txt","content":"..."}}"""
            CommandAction.CREATE_DIRECTORY ->
                """{"action":"create_directory","args":{"path":"/ext/new_dir"}}"""
            CommandAction.DELETE ->
                """{"action":"delete","args":{"path":"/ext/file.txt","recursive":false}}"""
            CommandAction.MOVE ->
                """{"action":"move","args":{"path":"/ext/src.txt","destination_path":"/ext/dst.txt"}}"""
            CommandAction.RENAME ->
                """{"action":"rename","args":{"path":"/ext/src.txt","new_name":"dst.txt"}}"""
            CommandAction.COPY ->
                """{"action":"copy","args":{"path":"/ext/src.txt","destination_path":"/ext/dst.txt"}}"""
            CommandAction.GET_DEVICE_INFO -> """{"action":"get_device_info","args":{}}"""
            CommandAction.GET_STORAGE_INFO -> """{"action":"get_storage_info","args":{}}"""
            CommandAction.SEARCH_FAPHUB ->
                """{"action":"search_faphub","args":{"query":"wifi marauder"}}"""
            CommandAction.INSTALL_FAPHUB_APP ->
                """{"action":"install_faphub_app","args":{"app_id":"wifi_marauder","download_url":"https://.../app.fap"}}"""
            CommandAction.PUSH_ARTIFACT ->
                """{"action":"push_artifact","args":{"path":"/ext/file.bin","artifact_data":"<base64>"}}"""
            CommandAction.EXECUTE_CLI ->
                """{"action":"execute_cli","args":{"command":"storage list /ext"}}"""
            CommandAction.FORGE_PAYLOAD ->
                """{"action":"forge_payload","args":{"prompt":"Create a BadUSB script that opens notepad","payload_type":"BAD_USB"}}"""
            CommandAction.SEARCH_RESOURCES ->
                """{"action":"search_resources","args":{"command":"infrared remotes","resource_type":"IR_REMOTE"}}"""
            CommandAction.LIST_VAULT ->
                """{"action":"list_vault","args":{"filter":"SUB_GHZ"}}"""
            CommandAction.RUN_RUNBOOK ->
                """{"action":"run_runbook","args":{"runbook_id":"link_health"}}"""
        }
    }

    /**
     * Format command result for tool response
     */
    fun formatResult(result: CommandResult): String {
        return json.encodeToString(result)
    }

    /**
     * Simple message sending without tool calling.
     * Used for AI features like payload generation, analysis, etc.
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        customSystemPrompt: String? = null
    ): Result<String> {
        val messages = buildList {
            addAll(conversationHistory)
            add(
                ChatMessage(
                    role = MessageRole.USER,
                    content = message
                )
            )
        }
        return sendMessagesWithoutTools(messages, customSystemPrompt)
    }

    /**
     * Message sending without tools, keeping multimodal/user history support.
     */
    suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        // Check rate limit
        if (!rateLimiter.tryAcquire()) {
            return@withContext Result.failure(Exception("Rate limit exceeded"))
        }

        val apiKey = settingsStore.apiKey.first()
            ?: return@withContext Result.failure(Exception("API key not configured"))
        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext Result.failure(Exception("Invalid API key format"))
        }

        val model = settingsStore.selectedModel.first()

        val compactMessages = trimConversationForRequest(messages)
        val requestMessages = buildList {
            add(OpenRouterMessage.text(
                role = "system",
                content = customSystemPrompt ?: systemPrompt
            ))
            addAll(sanitizeAndBuildRequestMessages(compactMessages))
        }

        val request = OpenRouterRequest(
            model = model,
            messages = requestMessages,
            tools = null,  // No tool calling for simple messages
            toolChoice = null,
            maxTokens = DEFAULT_RESPONSE_MAX_TOKENS
        )

        val requestBody = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(OPENROUTER_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://vesper.flipper.app")
            .addHeader("X-Title", "Vesper Flipper Control")
            .post(requestBody)
            .build()

        when (val result = executeWithRetry(httpRequest)) {
            is ChatCompletionResult.Success -> Result.success(result.content)
            is ChatCompletionResult.Error -> Result.failure(Exception(result.message))
        }
    }

    private fun isDnsResolutionFailure(error: Throwable): Boolean {
        if (error is UnknownHostException) return true
        val message = error.message.orEmpty()
        return message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated with hostname", ignoreCase = true)
    }

    private fun isToolUseUnsupportedError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("no endpoints found that support tool use") ||
                normalized.contains("tool use is not supported") ||
                normalized.contains("does not support tool use") ||
                normalized.contains("function calling is not supported") ||
                normalized.contains("tool calling is not supported") ||
                normalized.contains("endpoint does not support tools")
    }

    private fun isToolResultPairingError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("tool use block must have a corresponding tool use block in previous message") ||
                normalized.contains("tool_result blocks") ||
                normalized.contains("tool_use blocks") ||
                normalized.contains("tool use ids were found without tool_result blocks")
    }

    private fun isModelAvailabilityError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("model not found") ||
                normalized.contains("provider not found") ||
                normalized.contains("not available for your account") ||
                normalized.contains("you are not allowed to use this model") ||
                normalized.contains("access denied")
    }

    private fun buildToolModelCandidates(selectedModel: String): List<String> {
        val fallbackModels = TOOL_USE_FALLBACK_MODELS + SettingsStore.FALLBACK_MODELS.map { it.id }
        return (listOf(selectedModel) + fallbackModels)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { modelId ->
                KNOWN_NON_TOOL_MODELS.any { blocked ->
                    modelId.equals(blocked, ignoreCase = true)
                }
            }
            .distinct()
            .take(MAX_TOOL_MODEL_CANDIDATES)
    }

    private fun isToolModelTemporarilyBlocked(model: String): Boolean {
        val blockedAt = toolUnsupportedModels[model] ?: return false
        val now = System.currentTimeMillis()
        if (now - blockedAt >= TOOL_UNSUPPORTED_CACHE_MS) {
            toolUnsupportedModels.remove(model)
            return false
        }
        return true
    }

    private fun markToolModelUnsupported(model: String) {
        toolUnsupportedModels[model] = System.currentTimeMillis()
    }

    private fun trimConversationForRequest(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= MAX_CONTEXT_MESSAGES) return messages
        return messages.takeLast(MAX_CONTEXT_MESSAGES)
    }

    companion object {
        private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val DNS_RESOLUTION_ERROR_MESSAGE =
            "Cannot resolve openrouter.ai (DNS/network issue). Verify internet access, disable broken Private DNS/VPN, then retry."
        private const val EXPECTED_TOOL_ARGUMENTS_FORMAT =
            """{"action":"<action>","args":{...},"justification":"...","expected_effect":"..."}"""
        private const val MAX_TOOL_MODEL_CANDIDATES = 10
        private const val MAX_ERROR_MODEL_LIST = 6
        private const val MAX_TOOL_CALLS_PER_RESPONSE = 1
        private const val TOOL_UNSUPPORTED_CACHE_MS = 5 * 60 * 1000L
        private const val MAX_CONTEXT_MESSAGES = 24
        private const val TOOL_CALL_RESPONSE_MAX_TOKENS = 4096
        private const val DEFAULT_RESPONSE_MAX_TOKENS = 720

        private val SUPPORTED_ACTIONS = listOf(
            "list_directory",
            "read_file",
            "write_file",
            "create_directory",
            "delete",
            "move",
            "rename",
            "copy",
            "get_device_info",
            "get_storage_info",
            "search_faphub",
            "install_faphub_app",
            "push_artifact",
            "execute_cli",
            "forge_payload",
            "search_resources",
            "list_vault",
            "run_runbook"
        )

        private val TOOL_USE_FALLBACK_MODELS = listOf(
            "nousresearch/hermes-4-405b",
            "anthropic/claude-sonnet-4.5",
            "openai/gpt-oss-120b",
            "x-ai/grok-4-fast",
            "cohere/command-a"
        )

        private val KNOWN_NON_TOOL_MODELS = setOf(
            "google/gemini-2.5-flash-image-preview"
        )

        private val EXECUTE_COMMAND_TOOL = OpenRouterTool(
            type = "function",
            function = OpenRouterToolFunction(
                name = "execute_command",
                description = "Execute a Flipper operation. Supports file ops, device/storage queries, FapHub search/install, artifact pushes, execute_cli for direct Flipper CLI commands, forge_payload for AI payload crafting, search_resources for browsing public Flipper resource repos, list_vault for scanning the user's payload inventory, and run_runbook for diagnostic sequences.",
                parameters = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "action" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "enum" to JsonArray(listOf(
                                JsonPrimitive("list_directory"),
                                JsonPrimitive("read_file"),
                                JsonPrimitive("write_file"),
                                JsonPrimitive("create_directory"),
                                JsonPrimitive("delete"),
                                JsonPrimitive("move"),
                                JsonPrimitive("rename"),
                                JsonPrimitive("copy"),
                                JsonPrimitive("get_device_info"),
                                JsonPrimitive("get_storage_info"),
                                JsonPrimitive("search_faphub"),
                                JsonPrimitive("install_faphub_app"),
                                JsonPrimitive("push_artifact"),
                                JsonPrimitive("execute_cli"),
                                JsonPrimitive("forge_payload"),
                                JsonPrimitive("search_resources"),
                                JsonPrimitive("list_vault"),
                                JsonPrimitive("run_runbook")
                            )),
                            "description" to JsonPrimitive("The action to perform on the Flipper Zero")
                        )),
                        "args" to JsonObject(mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(mapOf(
                                "command" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive(
                                        "Primary text argument. For execute_cli: raw CLI command; for search_faphub/search_resources: query; for install_faphub_app: app id; for run_runbook: runbook id (link_health, input_smoke_test, recover_scan)."
                                    )
                                )),
                                "query" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Alias for search query")
                                )),
                                "app_id" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Alias for install_faphub_app app id/name")
                                )),
                                "path" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("File or directory path on Flipper")
                                )),
                                "destination_path" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Destination path for move/copy")
                                )),
                                "content" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Content to write to file. For install_faphub_app this may be a direct .fap download URL.")
                                )),
                                "download_url" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Optional direct .fap URL override for install_faphub_app")
                                )),
                                "new_name" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("New name for rename operation")
                                )),
                                "recursive" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("boolean"),
                                    "description" to JsonPrimitive("Whether to delete recursively")
                                )),
                                "artifact_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Type of artifact: fap, config, data")
                                )),
                                "artifact_data" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Base64-encoded artifact data")
                                )),
                                "prompt" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Natural language prompt for forge_payload. Describe what you want to create.")
                                )),
                                "payload_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Payload type for forge_payload: SUB_GHZ, INFRARED, NFC, RFID, BAD_USB, IBUTTON")
                                )),
                                "resource_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Resource type filter for search_resources: IR_REMOTE, SUB_GHZ, BAD_USB, NFC_FILES, EVIL_PORTAL, MUSIC, ANIMATIONS, GPIO_TOOLS")
                                )),
                                "runbook_id" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Runbook identifier for run_runbook: link_health, input_smoke_test, recover_scan")
                                )),
                                "filter" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Type filter for list_vault: SUB_GHZ, INFRARED, NFC, RFID, BAD_USB, IBUTTON")
                                ))
                            ))
                        )),
                        "justification" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Why this action is being taken")
                        )),
                        "expected_effect" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("What you expect this action to accomplish")
                        ))
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("action"),
                        JsonPrimitive("args"),
                        JsonPrimitive("justification"),
                        JsonPrimitive("expected_effect")
                    ))
                ))
            )
        )
    }
}

sealed class ChatCompletionResult {
    data class Success(
        val content: String,
        val toolCalls: List<ToolCall>?,
        val model: String,
        val tokensUsed: Int?
    ) : ChatCompletionResult()

    data class Error(val message: String) : ChatCompletionResult()
}

// OpenRouter API models

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val tools: List<OpenRouterTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenRouterToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
) {
    companion object {
        /**
         * Create a message with simple text content
         */
        fun text(role: String, content: String): OpenRouterMessage {
            return OpenRouterMessage(role = role, content = JsonPrimitive(content))
        }

        /**
         * Create a multimodal message with text and images
         */
        fun multimodal(
            role: String,
            text: String,
            images: List<ImageContent>
        ): OpenRouterMessage {
            val contentParts = buildList {
                // Add text part first
                if (text.isNotEmpty()) {
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(text)
                    )))
                }
                // Add image parts
                images.forEach { image ->
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf(
                            "url" to JsonPrimitive("data:${image.mimeType};base64,${image.base64Data}"),
                            "detail" to JsonPrimitive(image.detail)
                        ))
                    )))
                }
            }
            return OpenRouterMessage(role = role, content = JsonArray(contentParts))
        }
    }
}

/**
 * Image content for multimodal messages
 */
data class ImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "auto" // "low", "high", or "auto"
)

@Serializable
data class OpenRouterTool(
    val type: String,
    val function: OpenRouterToolFunction
)

@Serializable
data class OpenRouterToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class OpenRouterToolCall(
    val id: String,
    val type: String,
    val function: OpenRouterFunction
)

@Serializable
data class OpenRouterFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenRouterResponse(
    val id: String,
    val model: String,
    val choices: List<OpenRouterChoice>,
    val usage: OpenRouterUsage? = null
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String?
)

@Serializable
data class OpenRouterResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenRouterToolCall>? = null
)

@Serializable
data class OpenRouterUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)
