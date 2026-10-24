package com.vesper.flipper.ai

import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.database.ChatDao
import com.vesper.flipper.data.database.ChatSessionSummary
import com.vesper.flipper.data.database.ChatMessageEntity
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.model.ToolResult as ChatToolResult
import com.vesper.flipper.domain.service.AuditService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callback for requesting a photo capture from smart glasses.
 * Implemented by [GlassesIntegration] and registered via [VesperAgent.setPhotoCaptureCallback].
 */
fun interface PhotoCaptureCallback {
    /**
     * Capture a photo from the glasses camera and return a text description.
     * @param prompt Vision analysis instructions (e.g. "Identify the TV brand")
     * @param timeoutMs Max time to wait for the glasses to return a photo
     * @return Text description of the photo, or null if capture failed
     */
    suspend fun capturePhoto(prompt: String, timeoutMs: Long): String?
}

/**
 * Main AI agent orchestrator.
 * Manages conversation flow, tool execution, and state.
 */
@Singleton
class VesperAgent @Inject constructor(
    private val openRouterClient: OpenRouterClient,
    private val commandExecutor: CommandExecutor,
    private val auditService: AuditService,
    private val chatDao: ChatDao,
    private val settingsStore: SettingsStore
) {

    private val _conversationState = MutableStateFlow(ConversationState())
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    // Photo capture via smart glasses — set by GlassesIntegration when connected
    @Volatile
    private var photoCaptureCallback: PhotoCaptureCallback? = null

    fun setPhotoCaptureCallback(callback: PhotoCaptureCallback?) {
        photoCaptureCallback = callback
    }

    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistenceMutex = Mutex()

    /**
     * Serialises conversation turns.
     *
     * Without this, a double-tap on "send" or a retry fired while the previous
     * tool loop is still running would interleave tool calls and corrupt the
     * assistant/tool message pairing the providers require. Turns queue here
     * instead of racing.
     */
    private val agentTurnMutex = Mutex()

    /** Job running the current turn, so the Stop button can cancel it. */
    @Volatile
    private var currentTurnJob: Job? = null
    private val persistenceJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var currentSessionId: String = UUID.randomUUID().toString()

    init {
        persistenceScope.launch {
            restorePersistedConversation()
            observeConversationPersistence()
        }
    }

    /**
     * Start a new conversation session
     */
    fun startNewSession(deviceName: String? = null) {
        currentSessionId = UUID.randomUUID().toString()
        _conversationState.value = ConversationState(sessionId = currentSessionId)
        auditService.startSession(deviceName)
    }

    /**
     * Send a user message and process the response
     * @param userMessage The text content of the message
     * @param imageAttachments Optional list of image attachments for multimodal input
     */
    suspend fun sendMessage(
        userMessage: String,
        imageAttachments: List<ImageAttachment>? = null
    ): ConversationState = runTurn {
        sendMessageLocked(userMessage, imageAttachments)
    }

    private suspend fun sendMessageLocked(
        userMessage: String,
        imageAttachments: List<ImageAttachment>?
    ): ConversationState {
        val messages = _conversationState.value.messages.toMutableList()

        // Add user message with optional images
        val userChatMessage = ChatMessage(
            role = MessageRole.USER,
            content = userMessage,
            imageAttachments = imageAttachments
        )
        messages.add(userChatMessage)

        _conversationState.value = _conversationState.value.copy(
            messages = messages,
            isLoading = true,
            progress = AgentProgress(
                stage = AgentProgressStage.MODEL_REQUEST,
                detail = "Planning next action..."
            ),
            error = null
        )

        // Process with AI — catch unexpected exceptions so the UI never gets
        // stuck in a permanent loading state (e.g. image preprocessing crash).
        return try {
            processAIResponse(messages)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _conversationState.value = _conversationState.value.copy(
                messages = messages,
                isLoading = false,
                progress = null,
                error = "Something went wrong: ${e.message?.take(120) ?: "unknown error"}"
            )
            _conversationState.value
        }
    }

    /**
     * Retry the last AI request by re-submitting the current conversation
     * back to the model. Only strips the final failed assistant turn —
     * earlier successful tool exchanges are preserved to avoid re-triggering
     * side-effecting commands on the Flipper.
     */
    suspend fun retryLastMessage(): ConversationState = runTurn {
        retryLastMessageLocked()
    }

    private suspend fun retryLastMessageLocked(): ConversationState {
        val current = _conversationState.value
        if (current.isLoading) return current

        // Find the safe rollback point: keep everything up to and including the
        // last TOOL message with successful results (a completed exchange), or
        // the last USER message — whichever comes later.
        val messages = current.messages.toMutableList()
        var cutIndex = messages.size
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            when {
                // Stop at a user message — this is the prompt we'll retry from
                msg.role == MessageRole.USER -> { cutIndex = i + 1; break }
                // Stop at a completed tool result — the exchange before this succeeded
                msg.role == MessageRole.TOOL && msg.toolResults?.any { it.success } == true -> {
                    cutIndex = i + 1; break
                }
            }
        }

        // A cancelled turn leaves the user's message unanswered with no error
        // set (markTurnInterrupted clears it). Treat that transcript as
        // resubmittable instead of silently no-oping the Retry button.
        val pendingUserTurn = messages.lastOrNull()?.role == MessageRole.USER

        // Nothing to retry if we'd keep everything or the list is empty
        if (cutIndex >= messages.size || cutIndex == 0) {
            if (current.error != null || pendingUserTurn) {
                // Re-submit current messages as-is: either the previous attempt
                // failed (error set), or the turn was cancelled before the model
                // replied. Both need the same resend.
            } else {
                return current
            }
        } else {
            // Remove messages from the failed turn onwards
            while (messages.size > cutIndex) {
                messages.removeAt(messages.size - 1)
            }
        }

        if (messages.isEmpty()) return current

        _conversationState.value = current.copy(
            messages = messages,
            isLoading = true,
            progress = AgentProgress(
                stage = AgentProgressStage.MODEL_REQUEST,
                detail = "Retrying..."
            ),
            error = null
        )

        return try {
            processAIResponse(messages)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _conversationState.value = _conversationState.value.copy(
                messages = messages,
                isLoading = false,
                progress = null,
                error = "Retry failed: ${e.message?.take(120) ?: "unknown error"}"
            )
            _conversationState.value
        }
    }

    /**
     * Continue after tool approval/rejection
     */
    suspend fun continueAfterApproval(approvalId: String, approved: Boolean): ConversationState =
        runTurn {
            continueAfterApprovalLocked(approvalId, approved)
        }

    /**
     * Cancel the in-flight turn (chat "Stop" button).
     *
     * Safe to call when idle. Cancels the running job, clears the loading /
     * progress / approval state immediately and leaves the transcript at a
     * boundary [retryLastMessage] can resume from.
     */
    suspend fun cancelCurrentTurn() {
        val job = currentTurnJob
        currentTurnJob = null
        job?.cancel(CancellationException("Turn cancelled by user"))
        markTurnInterrupted()
    }

    /**
     * Runs [block] as a single serialised turn, tracking its Job for
     * [cancelCurrentTurn] and guaranteeing a cancelled turn cannot leave the UI
     * stuck on "loading" — the reset happens in a `finally`.
     */
    private suspend fun runTurn(block: suspend () -> ConversationState): ConversationState =
        agentTurnMutex.withLock {
            // A dedicated child Job scopes cancellation to this turn: pressing
            // Stop must not tear down the caller's viewModelScope.
            val turnJob = Job(currentCoroutineContext()[Job])
            currentTurnJob = turnJob
            try {
                withContext(turnJob) { block() }
            } catch (e: CancellationException) {
                markTurnInterrupted()
                if (turnJob.isCancelled) {
                    // User pressed Stop: return the reset state instead of
                    // throwing into the UI layer.
                    return@withLock _conversationState.value
                }
                throw e
            } finally {
                if (currentTurnJob === turnJob) currentTurnJob = null
                if (turnJob.isCancelled || !currentCoroutineContext().isActive) {
                    markTurnInterrupted()
                }
            }
        }

    /** Reset the transient turn state without touching the message history. */
    private fun markTurnInterrupted() {
        val current = _conversationState.value
        if (!current.isLoading && current.progress == null && current.pendingApproval == null) {
            return
        }
        _conversationState.value = current.copy(
            isLoading = false,
            progress = null,
            pendingApproval = null
        )
    }

    private suspend fun continueAfterApprovalLocked(approvalId: String, approved: Boolean): ConversationState {
        val stateBeforeDecision = _conversationState.value
        val effectiveApprovalId = stateBeforeDecision.pendingApproval?.id ?: approvalId

        // Hide approval UI immediately to avoid duplicate taps while command execution is in progress.
        if (stateBeforeDecision.pendingApproval?.id == effectiveApprovalId) {
            _conversationState.value = stateBeforeDecision.copy(
                isLoading = true,
                pendingApproval = null,
                progress = AgentProgress(
                    stage = AgentProgressStage.TOOL_EXECUTING,
                    detail = if (approved) "Applying approved action..." else "Rejecting action..."
                ),
                error = null
            )
        }

        val result = if (approved) {
            commandExecutor.approve(effectiveApprovalId, currentSessionId)
        } else {
            commandExecutor.reject(effectiveApprovalId, currentSessionId)
        }

        val messages = _conversationState.value.messages.toMutableList()

        // Find the pending message and update it
        val pendingIndex = messages.indexOfLast {
            it.metadata?.pendingApprovalId == effectiveApprovalId
        }

        if (pendingIndex >= 0) {
            val pendingMessage = messages[pendingIndex]
            val toolCallId = pendingMessage.toolCalls?.firstOrNull()?.id
            if (toolCallId == null) {
                _conversationState.value = _conversationState.value.copy(
                    messages = messages,
                    isLoading = false,
                    progress = null,
                    error = "Approval flow could not resume: missing tool call context."
                )
                return _conversationState.value
            }

            // Add tool result
            val toolResultMessage = ChatMessage(
                role = MessageRole.TOOL,
                content = "",
                toolResults = listOf(
                    ChatToolResult(
                        toolCallId = toolCallId,
                        content = openRouterClient.formatResult(result),
                        success = result.success
                    )
                )
            )
            messages.add(toolResultMessage)

            _conversationState.value = _conversationState.value.copy(
                messages = messages,
                isLoading = true,
                pendingApproval = null,
                progress = AgentProgress(
                    stage = AgentProgressStage.MODEL_REQUEST,
                    detail = "Summarizing result..."
                )
            )

            // Continue conversation
            return processAIResponse(messages)
        }

        _conversationState.value = _conversationState.value.copy(
            isLoading = false,
            progress = null,
            error = result.error ?: _conversationState.value.error
        )
        return _conversationState.value
    }

    private suspend fun processAIResponse(messages: MutableList<ChatMessage>): ConversationState {
        currentCoroutineContext().ensureActive()

        var currentMessages = messages
        var iterations = 0
        val maxIterations = try {
            settingsStore.aiMaxIterations.first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsStore.DEFAULT_AI_MAX_ITERATIONS
        }

        // Pre-process image attachments: create a separate copy of messages for the
        // API where images are replaced with text descriptions. The original messages
        // (with images intact) are kept for UI display and persistence.
        var apiMessages: MutableList<ChatMessage> = currentMessages.toMutableList()
        val hasImages = currentMessages.any { !it.imageAttachments.isNullOrEmpty() }
        if (hasImages) {
            val apiKey = settingsStore.apiKey.first()
            if (apiKey != null) {
                try {
                    apiMessages = openRouterClient.preprocessImagesAsText(currentMessages, apiKey).toMutableList()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Vision preprocessing failed — create API-only fallback messages
                    // but keep originals (with images) for display.
                    apiMessages = currentMessages.map { msg ->
                        if (msg.imageAttachments.isNullOrEmpty()) msg
                        else {
                            val fallback = "[${msg.imageAttachments.size} image(s) attached but vision analysis failed. " +
                                "Describe what you see to the user or ask them for details.]"
                            msg.copy(
                                content = if (msg.content.isNotBlank()) "$fallback\n\n${msg.content}" else fallback,
                                imageAttachments = null
                            )
                        }
                    }.toMutableList()
                }
            }
        }

        while (iterations < maxIterations) {
            // Cancellation (user left the screen, new session started) must stop
            // the loop before another model call or side effect.
            currentCoroutineContext().ensureActive()
            iterations++

            _conversationState.value = _conversationState.value.copy(
                messages = currentMessages,
                isLoading = true,
                pendingApproval = null,
                progress = AgentProgress(
                    stage = AgentProgressStage.MODEL_REQUEST,
                    detail = "Contacting model (${iterations}/$maxIterations)..."
                ),
                error = null
            )

            // Log AI request
            auditService.log(
                AuditEntry(
                    actionType = AuditActionType.AI_REQUEST,
                    sessionId = currentSessionId,
                    metadata = mapOf("message_count" to currentMessages.size.toString())
                )
            )

            // Send API messages (images replaced with text descriptions) to the model
            val result = openRouterClient.chat(apiMessages, currentSessionId)

            when (result) {
                is ChatCompletionResult.Error -> {
                    _conversationState.value = _conversationState.value.copy(
                        messages = currentMessages,
                        isLoading = false,
                        progress = null,
                        error = result.message
                    )
                    return _conversationState.value
                }

                is ChatCompletionResult.Success -> {
                    // Log AI response
                    auditService.log(
                        AuditEntry(
                            actionType = AuditActionType.AI_RESPONSE,
                            sessionId = currentSessionId,
                            metadata = mapOf(
                                "model" to result.model,
                                "has_tool_calls" to (result.toolCalls != null).toString()
                            )
                        )
                    )

                    if (result.toolCalls.isNullOrEmpty()) {
                        // No tool calls - final response
                        val assistantMessage = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = result.content,
                            metadata = MessageMetadata(
                                modelUsed = result.model,
                                tokensUsed = result.tokensUsed
                            )
                        )
                        currentMessages.add(assistantMessage)
                        apiMessages.add(assistantMessage)

                        _conversationState.value = _conversationState.value.copy(
                            messages = currentMessages,
                            isLoading = false,
                            progress = null
                        )
                        return _conversationState.value
                    }

                    // Process tool calls
                    val assistantMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = result.content,
                        toolCalls = result.toolCalls,
                        metadata = MessageMetadata(
                            modelUsed = result.model,
                            tokensUsed = result.tokensUsed
                        )
                    )
                    currentMessages.add(assistantMessage)
                    apiMessages.add(assistantMessage)

                    _conversationState.value = _conversationState.value.copy(
                        messages = currentMessages,
                        isLoading = true,
                        progress = AgentProgress(
                            stage = AgentProgressStage.TOOL_PLANNED,
                            detail = "Running ${result.toolCalls.size} tool call(s)..."
                        ),
                        error = null
                    )

                    // Execute each tool call
                    val toolResults = mutableListOf<ChatToolResult>()

                    for (toolCall in result.toolCalls) {
                        currentCoroutineContext().ensureActive()

                        if (toolCall.name != "execute_command") {
                            auditService.log(
                                AuditEntry(
                                    actionType = AuditActionType.COMMAND_FAILED,
                                    sessionId = currentSessionId,
                                    metadata = mapOf(
                                        "reason" to "unknown_tool_name",
                                        "tool_name" to toolCall.name
                                    )
                                )
                            )
                            toolResults.add(
                                ChatToolResult(
                                    toolCallId = toolCall.id,
                                    content = """{"success": false, "error": "Unknown tool: ${toolCall.name}"}""",
                                    success = false
                                )
                            )
                            _conversationState.value = _conversationState.value.copy(
                                progress = AgentProgress(
                                    stage = AgentProgressStage.TOOL_COMPLETED,
                                    detail = "Tool failed: unknown tool ${toolCall.name}"
                                )
                            )
                            continue
                        }

                        val parsedCommand = openRouterClient.parseCommandDetailed(toolCall.arguments)
                        val command = parsedCommand.command
                        if (command == null) {
                            val parseError = parsedCommand.error
                                ?: "Invalid command format. Expected action + args JSON."
                            val argumentsPreview = toolCall.arguments
                                .replace('\n', ' ')
                                .replace('\r', ' ')
                                .take(220)
                            val errorPayload =
                                """{"success": false, "error": ${Json.encodeToString(parseError)}, "arguments_preview": ${Json.encodeToString(argumentsPreview)}}"""
                            auditService.log(
                                AuditEntry(
                                    actionType = AuditActionType.COMMAND_FAILED,
                                    sessionId = currentSessionId,
                                    metadata = mapOf(
                                        "reason" to "tool_argument_parse_failed",
                                        "tool_name" to toolCall.name,
                                        "tool_args_preview" to toolCall.arguments.take(400),
                                        "parse_error" to parseError
                                    )
                                )
                            )
                            toolResults.add(
                                ChatToolResult(
                                    toolCallId = toolCall.id,
                                    content = errorPayload,
                                    success = false
                                )
                            )
                            _conversationState.value = _conversationState.value.copy(
                                progress = AgentProgress(
                                    stage = AgentProgressStage.TOOL_COMPLETED,
                                    detail = "Tool failed: ${parseError.take(120)}"
                                )
                            )
                            continue
                        }

                        _conversationState.value = _conversationState.value.copy(
                            progress = AgentProgress(
                                stage = AgentProgressStage.TOOL_EXECUTING,
                                detail = "Executing ${command.action.displayName()}..."
                            )
                        )

                        // ── Intercept request_photo: handled here, not in CommandExecutor ──
                        if (command.action == CommandAction.REQUEST_PHOTO) {
                            val photoPrompt = command.args.photoPrompt
                                ?: command.args.prompt
                                ?: "Describe what you see in detail"
                            val callback = photoCaptureCallback
                            if (callback == null) {
                                toolResults.add(
                                    ChatToolResult(
                                        toolCallId = toolCall.id,
                                        content = """{"success": false, "error": "Smart glasses are not connected. Cannot capture photo. Ask the user to describe what they see instead."}""",
                                        success = false
                                    )
                                )
                            } else {
                                _conversationState.value = _conversationState.value.copy(
                                    progress = AgentProgress(
                                        stage = AgentProgressStage.TOOL_EXECUTING,
                                        detail = "Capturing photo from glasses..."
                                    )
                                )
                                val description = callback.capturePhoto(photoPrompt, 15_000L)
                                if (description != null) {
                                    // Camera text is device-sourced; clamp it like any other tool payload.
                                    val safeDescription = ToolOutput.truncate(description)
                                    toolResults.add(
                                        ChatToolResult(
                                            toolCallId = toolCall.id,
                                            content = """{"success": true, "data": {"description": ${Json.encodeToString(safeDescription)}}}""",
                                            success = true
                                        )
                                    )
                                    _conversationState.value = _conversationState.value.copy(
                                        progress = AgentProgress(
                                            stage = AgentProgressStage.TOOL_COMPLETED,
                                            detail = "Photo captured and analyzed."
                                        )
                                    )
                                } else {
                                    toolResults.add(
                                        ChatToolResult(
                                            toolCallId = toolCall.id,
                                            content = """{"success": false, "error": "Photo capture failed or timed out. The glasses camera may not be available. Ask the user to describe what they see."}""",
                                            success = false
                                        )
                                    )
                                    _conversationState.value = _conversationState.value.copy(
                                        progress = AgentProgress(
                                            stage = AgentProgressStage.TOOL_COMPLETED,
                                            detail = "Photo capture failed."
                                        )
                                    )
                                }
                            }
                            continue
                        }

                        // Never start a side-effecting command once the turn is cancelled.
                        currentCoroutineContext().ensureActive()
                        val commandResult = commandExecutor.execute(command, currentSessionId)

                        if (commandResult.requiresConfirmation && commandResult.pendingApprovalId != null) {
                            // Need user approval - pause here
                            val pendingApproval = commandExecutor.getPendingApproval(commandResult.pendingApprovalId)
                            if (pendingApproval == null) {
                                toolResults.add(
                                    ChatToolResult(
                                        toolCallId = toolCall.id,
                                        content = """{"success": false, "error": "Approval request expired before it reached UI. Please retry the command."}""",
                                        success = false
                                    )
                                )
                                _conversationState.value = _conversationState.value.copy(
                                    progress = AgentProgress(
                                        stage = AgentProgressStage.TOOL_COMPLETED,
                                        detail = "Approval request expired before confirmation."
                                    )
                                )
                                continue
                            }

                            // Update message with pending approval ID
                            val updatedAssistantMessage = assistantMessage.copy(
                                metadata = assistantMessage.metadata?.copy(
                                    pendingApprovalId = commandResult.pendingApprovalId
                                ) ?: MessageMetadata(pendingApprovalId = commandResult.pendingApprovalId)
                            )
                            currentMessages[currentMessages.lastIndex] = updatedAssistantMessage
                            apiMessages[apiMessages.lastIndex] = updatedAssistantMessage

                            _conversationState.value = _conversationState.value.copy(
                                messages = currentMessages,
                                isLoading = false,
                                pendingApproval = pendingApproval,
                                progress = AgentProgress(
                                    stage = AgentProgressStage.WAITING_APPROVAL,
                                    detail = "Approval required to continue."
                                )
                            )
                            return _conversationState.value
                        }

                        toolResults.add(
                            ChatToolResult(
                                toolCallId = toolCall.id,
                                content = openRouterClient.formatResult(commandResult),
                                success = commandResult.success
                            )
                        )
                        _conversationState.value = _conversationState.value.copy(
                            progress = AgentProgress(
                                stage = AgentProgressStage.TOOL_COMPLETED,
                                detail = if (commandResult.success) {
                                    "Executed ${command.action.displayName()}."
                                } else {
                                    "Failed ${command.action.displayName()}: ${commandResult.error.orEmpty().take(80)}"
                                }
                            )
                        )
                    }

                    // Add tool results and continue
                    val toolMessage = ChatMessage(
                        role = MessageRole.TOOL,
                        content = "",
                        toolResults = toolResults
                    )
                    currentMessages.add(toolMessage)
                    apiMessages.add(toolMessage)

                    _conversationState.value = _conversationState.value.copy(
                        messages = currentMessages,
                        isLoading = true,
                        progress = AgentProgress(
                            stage = AgentProgressStage.MODEL_REQUEST,
                            detail = "Summarizing tool result..."
                        ),
                        error = null
                    )

                    // Continue the loop to get next AI response
                }
            }
        }

        // Max iterations reached
        _conversationState.value = _conversationState.value.copy(
            messages = currentMessages,
            isLoading = false,
            progress = null,
            error = "Maximum iterations reached ($maxIterations). " +
                    "Increase \"AI Max Iterations\" in Settings if needed."
        )
        return _conversationState.value
    }

    private suspend fun restorePersistedConversation() {
        val savedSessionId = settingsStore.lastChatSessionId.first()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val persistedMessages = runCatching {
            chatDao.getMessagesForSessionSync(savedSessionId)
        }.getOrElse {
            return
        }
        if (persistedMessages.isEmpty()) return

        val restoredMessages = persistedMessages.mapNotNull { entity ->
            entity.toDomainMessageOrNull()
        }
        if (restoredMessages.isEmpty()) return

        currentSessionId = savedSessionId
        _conversationState.value = ConversationState(
            messages = restoredMessages,
            sessionId = savedSessionId
        )
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun observeConversationPersistence() {
        conversationState
            .map { state ->
                PersistedConversationSnapshot(
                    sessionId = state.sessionId,
                    messages = state.messages.takeLast(MAX_PERSISTED_MESSAGES)
                )
            }
            .distinctUntilChanged()
            .debounce(CONVERSATION_PERSIST_DEBOUNCE_MS)
            .collect { snapshot ->
                persistConversationSnapshot(snapshot)
            }
    }

    private suspend fun persistConversationSnapshot(snapshot: PersistedConversationSnapshot) {
        persistenceMutex.withLock {
            settingsStore.setLastChatSessionId(snapshot.sessionId)
            chatDao.deleteSession(snapshot.sessionId)
            snapshot.messages.forEach { message ->
                chatDao.insert(message.toEntity(snapshot.sessionId))
            }
        }
    }

    private fun ChatMessage.toEntity(sessionId: String): ChatMessageEntity {
        return ChatMessageEntity(
            id = id,
            role = role.name,
            content = content,
            timestamp = timestamp,
            toolCallsJson = toolCalls?.takeIf { it.isNotEmpty() }?.let { persistenceJson.encodeToString(it) },
            toolResultsJson = toolResults?.takeIf { it.isNotEmpty() }?.let { persistenceJson.encodeToString(it) },
            status = status.name,
            metadataJson = metadata?.let { persistenceJson.encodeToString(it) },
            sessionId = sessionId,
            // Cap each image's base64 data to avoid exceeding Android's 2MB CursorWindow.
            // Full-resolution images are kept in memory for the current session.
            imageAttachmentsJson = imageAttachments?.takeIf { it.isNotEmpty() }?.let { attachments ->
                val capped = attachments.map { img ->
                    if (img.base64Data.length > MAX_PERSISTED_IMAGE_BASE64_LENGTH) {
                        img.copy(base64Data = img.base64Data.take(MAX_PERSISTED_IMAGE_BASE64_LENGTH))
                    } else img
                }
                persistenceJson.encodeToString(capped)
            }
        )
    }

    private fun ChatMessageEntity.toDomainMessageOrNull(): ChatMessage? {
        val parsedRole = runCatching { MessageRole.valueOf(role) }.getOrNull() ?: return null
        val parsedStatus = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE)
        val parsedToolCalls = toolCallsJson?.let { payload ->
            runCatching { persistenceJson.decodeFromString<List<ToolCall>>(payload) }.getOrNull()
        }
        val parsedToolResults = toolResultsJson?.let { payload ->
            runCatching { persistenceJson.decodeFromString<List<ChatToolResult>>(payload) }.getOrNull()
        }
        val parsedMetadata = metadataJson?.let { payload ->
            runCatching { persistenceJson.decodeFromString<MessageMetadata>(payload) }.getOrNull()
        }
        val parsedImageAttachments = imageAttachmentsJson?.let { payload ->
            runCatching { persistenceJson.decodeFromString<List<ImageAttachment>>(payload) }.getOrNull()
        }
        return ChatMessage(
            id = id,
            role = parsedRole,
            content = content,
            timestamp = timestamp,
            toolCalls = parsedToolCalls,
            toolResults = parsedToolResults,
            status = parsedStatus,
            metadata = parsedMetadata,
            imageAttachments = parsedImageAttachments
        )
    }

    /**
     * Clear conversation history
     */
    fun clearConversation() {
        persistenceScope.launch {
            chatDao.deleteSession(currentSessionId)
        }
        auditService.endSession()
        startNewSession()
    }

    /**
     * Get all saved chat sessions for the history drawer.
     */
    fun getSessions(): Flow<List<ChatSessionSummary>> = chatDao.getAllSessions()

    /**
     * Load a previous session by ID.
     */
    suspend fun loadSession(sessionId: String) {
        val messages = chatDao.getMessagesForSessionSync(sessionId)
        if (messages.isEmpty()) return
        val restored = messages.mapNotNull { it.toDomainMessageOrNull() }
        if (restored.isEmpty()) return
        currentSessionId = sessionId
        settingsStore.setLastChatSessionId(sessionId)
        _conversationState.value = ConversationState(
            messages = restored,
            sessionId = sessionId
        )
    }

    /**
     * Delete a saved session from history.
     */
    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteSession(sessionId)
        // If we deleted the current session, start fresh
        if (sessionId == currentSessionId) {
            startNewSession()
        }
    }

    /**
     * Get the current pending approval if any
     */
    fun getCurrentApproval(): PendingApproval? {
        return _conversationState.value.pendingApproval
    }

    private fun CommandAction.displayName(): String {
        return name.lowercase().replace('_', ' ')
    }

    private data class PersistedConversationSnapshot(
        val sessionId: String,
        val messages: List<ChatMessage>
    )

    companion object {
        private const val MAX_PERSISTED_MESSAGES = 220
        private const val CONVERSATION_PERSIST_DEBOUNCE_MS = 250L
        /** ~300KB per image keeps total row size well under Android's 2MB CursorWindow limit. */
        private const val MAX_PERSISTED_IMAGE_BASE64_LENGTH = 300_000
    }
}
