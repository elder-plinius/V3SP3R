package com.vesper.flipper.glasses

import android.util.Log
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the [GlassesBridgeClient] into V3SP3R's conversation pipeline.
 *
 * Handles:
 * - Voice transcriptions from glasses → VesperAgent as user messages
 * - Camera photos from glasses → VesperAgent as image attachments
 * - AI responses from VesperAgent → glasses for TTS + HUD display
 * - Agent progress narration → glasses for conversational status updates
 * - Voice-based approval flow → hands-free Flipper command confirmation
 * - "Hey Vesper" wake word commands → immediate execution
 * - Photo auto-upload to chat pending images for combo with voice/text
 */
@Singleton
class GlassesIntegration @Inject constructor(
    val bridge: GlassesBridgeClient,
    private val vesperAgent: VesperAgent,
    private val settingsStore: SettingsStore
) {
    companion object {
        private const val TAG = "GlassesIntegration"
        private const val PHOTO_HOLD_TIMEOUT_MS = 30_000L // 30s to combine photo with text/voice

        // Voice patterns for approving/denying Flipper operations hands-free.
        // Use word-boundary regex to avoid false positives like "yesterday" → "yes".
        private val APPROVE_REGEX = Regex(
            """\b(yes|approve|confirm|do it|go ahead|execute|proceed|affirmative|yep|yeah)\b""",
            RegexOption.IGNORE_CASE
        )
        private val DENY_REGEX = Regex(
            """\b(no|deny|reject|cancel|stop|abort|negative|nope|don't|do not)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    val bridgeState: StateFlow<BridgeState> = bridge.state
    val incomingMessages: SharedFlow<GlassesMessage> = bridge.incomingMessages

    // Pending photo from glasses — exposed so ChatViewModel can show it in the input area
    private val _pendingGlassesPhoto = MutableStateFlow<ImageAttachment?>(null)
    val pendingGlassesPhoto: StateFlow<ImageAttachment?> = _pendingGlassesPhoto.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var messageListenerJob: Job? = null
    private var responseListenerJob: Job? = null
    private var progressListenerJob: Job? = null
    private var approvalListenerJob: Job? = null
    private var lastProcessedMessageCount = 0
    private var photoHoldJob: Job? = null

    // Mutex-protected approval state to prevent race between phone tap + voice approval
    private val approvalMutex = Mutex()
    private var awaitingVoiceApproval = false
    private var pendingApprovalId: String? = null

    private var lastSpokenProgressStage: AgentProgressStage? = null

    /**
     * Connect to the glasses bridge and start relaying messages.
     */
    fun connect(bridgeUrl: String) {
        bridge.connect(bridgeUrl)
        startListeners()
    }

    /**
     * Disconnect from the glasses bridge and stop all listeners.
     * Clears all pending state so stale approvals don't fire on reconnect.
     */
    fun disconnect() {
        stopListeners()
        bridge.disconnect()
        clearApprovalState()
        photoHoldJob?.cancel()
        photoHoldJob = null
        lastSpokenProgressStage = null
    }

    fun isConnected(): Boolean = bridge.isConnected()

    /**
     * Start listening for incoming glasses messages and outgoing AI responses.
     */
    private fun startListeners() {
        stopListeners()

        // Listen for messages FROM glasses (voice, photos).
        // Each message is handled in its own coroutine so a failure in one
        // (e.g. network error during sendMessage) doesn't kill the collector.
        messageListenerJob = scope.launch {
            bridge.incomingMessages.collect { message ->
                launch {
                    try {
                        handleGlassesMessage(message)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Error handling glasses message: ${e.message}", e)
                        bridge.sendStatus("Error: ${e.message?.take(60) ?: "unknown"}")
                    }
                }
            }
        }

        // Listen for AI responses + progress TO send to glasses
        responseListenerJob = scope.launch {
            vesperAgent.conversationState.collect { state ->
                handleConversationUpdate(state)
            }
        }

        // Listen for agent progress updates → narrate on glasses
        progressListenerJob = scope.launch {
            vesperAgent.conversationState
                .map { it.progress }
                .distinctUntilChanged()
                .collect { progress ->
                    if (progress != null && bridge.isConnected()) {
                        handleProgressUpdate(progress)
                    }
                }
        }

        // Listen for approval requests → speak on glasses
        approvalListenerJob = scope.launch {
            vesperAgent.conversationState
                .map { it.pendingApproval }
                .distinctUntilChanged()
                .collect { approval ->
                    if (approval != null && bridge.isConnected()) {
                        handleApprovalRequest(approval)
                    }
                }
        }
    }

    private fun stopListeners() {
        messageListenerJob?.cancel()
        messageListenerJob = null
        responseListenerJob?.cancel()
        responseListenerJob = null
        progressListenerJob?.cancel()
        progressListenerJob = null
        approvalListenerJob?.cancel()
        approvalListenerJob = null
    }

    /**
     * Handle an incoming message from the glasses.
     */
    private suspend fun handleGlassesMessage(message: GlassesMessage) {
        val glassesEnabled = settingsStore.glassesEnabled.first()
        if (!glassesEnabled) return

        when (message.type) {
            MessageType.VOICE_TRANSCRIPTION -> handleVoiceTranscription(message)
            MessageType.CAMERA_PHOTO -> handleCameraPhoto(message)
            MessageType.VOICE_COMMAND -> handleVoiceCommand(message)
            else -> { /* Outbound message types — ignore */ }
        }
    }

    /**
     * Voice transcription from glasses mic → send as user message to VesperAgent.
     * Only processes final transcriptions (not partials).
     * Intercepts yes/no when waiting for approval.
     * If a glasses photo is pending, combines it with the voice text.
     */
    private suspend fun handleVoiceTranscription(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return
        if (!message.isFinal) return // Skip partial transcriptions

        Log.i(TAG, "Glasses voice: \"$text\"")

        // Intercept approval responses even on passive transcriptions
        if (tryHandleApprovalVoice(text)) return

        val autoSend = settingsStore.glassesAutoSend.first()
        if (autoSend) {
            sendWithPendingPhoto(text)
        }
        // If autoSend is off, the transcription still reaches the UI via incomingMessages
        // and the ChatViewModel can append it to the input field
    }

    /**
     * Camera photo from glasses → holds as pending image in chat input.
     *
     * The photo is exposed via [pendingGlassesPhoto] so the ChatViewModel can
     * add it to the pending images list. If no voice/text directive arrives
     * within [PHOTO_HOLD_TIMEOUT_MS], sends with a default prompt.
     */
    private suspend fun handleCameraPhoto(message: GlassesMessage) {
        val imageData = message.imageBase64 ?: return
        val mimeType = message.imageMimeType ?: "image/jpeg"
        val promptText = message.text

        Log.i(TAG, "Glasses camera: ${imageData.length} chars base64")

        val attachment = ImageAttachment(
            base64Data = imageData,
            mimeType = mimeType
        )

        // Cancel any previous hold timer
        photoHoldJob?.cancel()

        // Expose photo to UI as pending
        _pendingGlassesPhoto.value = attachment

        // Start a timeout: if no directive arrives, auto-send with default prompt
        photoHoldJob = scope.launch {
            delay(PHOTO_HOLD_TIMEOUT_MS)
            val stillPending = _pendingGlassesPhoto.value
            if (stillPending != null && stillPending.id == attachment.id) {
                Log.i(TAG, "Photo hold timed out — sending with default prompt")
                _pendingGlassesPhoto.value = null
                vesperAgent.sendMessage(
                    userMessage = promptText ?: "What am I looking at?",
                    imageAttachments = listOf(attachment)
                )
            }
        }
    }

    /**
     * Explicit voice command from "Hey Vesper" wake word.
     * Always auto-sends. Combines with pending photo if one exists.
     * Intercepts approval responses (yes/no) when waiting for confirmation.
     */
    private suspend fun handleVoiceCommand(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return

        // Check if this is a voice approval/rejection
        if (tryHandleApprovalVoice(text)) return

        Log.i(TAG, "Glasses command: \"$text\"")
        sendWithPendingPhoto(text)
    }

    /**
     * Try to handle voice text as an approval/denial response.
     * Uses mutex to prevent race with simultaneous phone-tap approval.
     * Validates the approval is still live in VesperAgent before acting.
     *
     * @return true if the text was consumed as an approval/denial
     */
    private suspend fun tryHandleApprovalVoice(text: String): Boolean {
        approvalMutex.withLock {
            if (!awaitingVoiceApproval || pendingApprovalId == null) return false

            // Verify the approval is still active in the agent (not already
            // consumed by phone tap or expired)
            val currentApproval = vesperAgent.conversationState.value.pendingApproval
            if (currentApproval?.id != pendingApprovalId) {
                Log.w(TAG, "Approval $pendingApprovalId already consumed or expired")
                awaitingVoiceApproval = false
                pendingApprovalId = null
                return false
            }

            val approved = when {
                APPROVE_REGEX.containsMatchIn(text) -> true
                DENY_REGEX.containsMatchIn(text) -> false
                else -> return false // Not an approval/denial phrase
            }

            Log.i(TAG, "Voice ${if (approved) "approval" else "denial"}: \"$text\"")
            val approvalId = pendingApprovalId!!
            awaitingVoiceApproval = false
            pendingApprovalId = null

            bridge.sendStatus(if (approved) "Approved — executing" else "Denied — cancelled")
            vesperAgent.continueAfterApproval(approvalId, approved = approved)
            return true
        }
    }

    /**
     * Send a message to VesperAgent, attaching any pending glasses photo.
     * Clears the pending photo after sending.
     */
    private suspend fun sendWithPendingPhoto(text: String) {
        val photo = _pendingGlassesPhoto.value
        if (photo != null) {
            // Combine voice/text directive with the pending photo
            photoHoldJob?.cancel()
            _pendingGlassesPhoto.value = null
            Log.i(TAG, "Combining pending photo with directive: \"$text\"")
            vesperAgent.sendMessage(
                userMessage = text,
                imageAttachments = listOf(photo)
            )
            // Skip verbose status — "Thinking..." will narrate via progress listener
        } else {
            vesperAgent.sendMessage(userMessage = text)
            // Skip verbose status — "Thinking..." will narrate via progress listener
        }
    }

    /**
     * Clear the pending glasses photo (called by ChatViewModel when user
     * manually removes it from the input area).
     */
    fun clearPendingPhoto() {
        photoHoldJob?.cancel()
        _pendingGlassesPhoto.value = null
    }

    /**
     * Watch VesperAgent conversation state for new assistant messages
     * and relay them to glasses for TTS + HUD display.
     * Sends a brief summary for TTS (≤2 sentences) and the full text for HUD.
     */
    private suspend fun handleConversationUpdate(state: ConversationState) {
        if (!bridge.isConnected()) return

        val messages = state.messages

        // While loading, don't update the count — we need to see the final
        // response once loading finishes
        if (state.isLoading || messages.size <= lastProcessedMessageCount) {
            return
        }

        val lastMsg = messages.lastOrNull() ?: return
        if (lastMsg.role == MessageRole.ASSISTANT &&
            lastMsg.status == MessageStatus.COMPLETE &&
            lastMsg.content.isNotBlank() &&
            lastMsg.toolCalls.isNullOrEmpty()
        ) {
            // Summarize for TTS — first 2 sentences, max ~120 chars spoken
            val spokenText = summarizeForSpeech(lastMsg.content)
            bridge.sendResponse(spokenText)
            clearApprovalState()
        }

        lastProcessedMessageCount = messages.size
    }

    /**
     * Extract the first 1-2 sentences from a response for brief TTS.
     * Targets ~5 seconds of speech (~15 words / ~120 chars).
     */
    private fun summarizeForSpeech(text: String): String {
        // Strip markdown formatting
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("\\[.*?]\\(.*?\\)"), "")
            .replace(Regex("[*_~`#>]"), "")
            .replace(Regex("\\n+"), " ")
            .trim()

        if (clean.length <= 120) return clean

        // Grab up to 2 sentences
        val sentenceEnds = Regex("[.!?]\\s+|[.!?]$")
        var endIndex = -1
        var sentenceCount = 0
        sentenceEnds.findAll(clean).forEach { match ->
            if (sentenceCount < 2 && match.range.first < 200) {
                endIndex = match.range.first + 1
                sentenceCount++
            }
        }

        return if (endIndex > 0 && endIndex <= 200) {
            clean.substring(0, endIndex).trim()
        } else {
            // No sentence boundary found — cut at word boundary near 120 chars
            val cutoff = clean.take(120).lastIndexOf(' ')
            if (cutoff > 40) clean.substring(0, cutoff).trim() + "."
            else clean.take(120).trim() + "."
        }
    }

    /**
     * Narrate agent progress through glasses — only key moments.
     * Skips intermediate stages to avoid rapid-fire chatter.
     */
    private fun handleProgressUpdate(progress: AgentProgress) {
        // Avoid repeating the same stage
        if (progress.stage == lastSpokenProgressStage) return
        lastSpokenProgressStage = progress.stage

        // Only narrate start (thinking) and completion — skip intermediate stages
        val narration = when (progress.stage) {
            AgentProgressStage.MODEL_REQUEST -> "Thinking..."
            AgentProgressStage.TOOL_COMPLETED -> "Done."
            AgentProgressStage.WAITING_APPROVAL -> return // Handled separately
            else -> return // Skip TOOL_PLANNED and TOOL_EXECUTING to reduce chatter
        }

        Log.d(TAG, "Glasses narration: $narration")
        bridge.sendStatus(narration)
    }

    /**
     * Speak an approval request through the glasses so the user can
     * approve or deny hands-free with voice ("yes"/"no"/"approve"/"deny").
     */
    private fun handleApprovalRequest(approval: PendingApproval) {
        val command = approval.command
        val risk = approval.riskAssessment
        val actionName = command.action.name.lowercase().replace('_', ' ')
        val path = command.args.path ?: command.args.destinationPath ?: ""

        val spokenPrompt = buildString {
            append("Approval needed. ")
            append("${risk.level.name.lowercase()} risk. ")
            append(actionName)
            if (path.isNotBlank()) append(" on $path")
            append(". ${risk.reason}. ")
            append("Say yes to approve, or no to deny.")
        }

        Log.i(TAG, "Glasses approval prompt: $spokenPrompt")

        // Arm voice approval interception (thread-safe via mutex in tryHandleApprovalVoice)
        scope.launch {
            approvalMutex.withLock {
                awaitingVoiceApproval = true
                pendingApprovalId = approval.id
            }
        }

        // Speak + display on glasses
        bridge.sendResponse(
            text = spokenPrompt,
            displayText = "${risk.level}: $actionName ${path.takeLast(30)}\nSay YES or NO"
        )
    }

    /**
     * Clear approval state. Called on disconnect, final response, or session reset
     * to prevent stale approvals from firing on reconnect.
     */
    private fun clearApprovalState() {
        // Best-effort clear — no mutex needed since we're just resetting
        awaitingVoiceApproval = false
        pendingApprovalId = null
    }

    fun destroy() {
        disconnect()
        clearPendingPhoto()
        scope.cancel()
        bridge.destroy()
    }
}
