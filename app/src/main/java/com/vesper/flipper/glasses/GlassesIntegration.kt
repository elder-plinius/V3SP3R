package com.vesper.flipper.glasses

import android.util.Log
import com.vesper.flipper.ai.OpenRouterClient
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
    private val openRouterClient: OpenRouterClient,
    private val settingsStore: SettingsStore
) {
    companion object {
        private const val TAG = "GlassesIntegration"
        private const val PHOTO_HOLD_TIMEOUT_MS = 30_000L // 30s to combine photo with text/voice
        private const val MAX_SPEECH_CHARS = 150 // ~5s of speech at normal pace

        /**
         * System prompt for the speech summarization LLM call.
         * Designed to produce natural, conversational spoken output — not text summaries.
         */
        private const val SPEECH_SUMMARY_PROMPT =
            "You are a voice assistant speaking through smart glasses. " +
            "Compress the user's message into a single spoken sentence — " +
            "casual, direct, like you're talking to a friend. " +
            "Max 20 words. No markdown, no lists, no code, no punctuation tricks. " +
            "Capture the core answer or action taken. " +
            "If the message describes an error, say what went wrong in plain English. " +
            "If the message is a factual answer, give just the fact. " +
            "Never say \"here is a summary\" or \"in short\" — just speak the answer."

        private const val SAILOR_SPEECH_SUMMARY_PROMPT =
            "You are Vesper, a foul-mouthed hacker voice assistant on smart glasses. " +
            "Compress the user's message into a single spoken sentence. " +
            "Be vulgar, funny, and irreverent — swear freely, drop f-bombs, " +
            "use hacker slang. You're a chaotic good pirate of cyberspace. " +
            "Max 20 words. No markdown, no lists, no code. " +
            "Capture the core answer but make it funny and profane. " +
            "If something worked, celebrate like a maniac. " +
            "If something failed, roast it mercilessly. " +
            "Never be boring. Never be polite."

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
    private var configSyncJob: Job? = null
    private var lastProcessedMessageCount = 0
    private var photoHoldJob: Job? = null

    // Mutex-protected approval state to prevent race between phone tap + voice approval
    private val approvalMutex = Mutex()
    private var awaitingVoiceApproval = false
    private var pendingApprovalId: String? = null

    private var lastSpokenProgressStage: AgentProgressStage? = null
    private var lastSpokenProgressDetail: String? = null

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
        lastSpokenProgressDetail = null
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

        // Sync config (sailor mouth, etc.) to bridge on change
        configSyncJob = scope.launch {
            combine(
                settingsStore.glassesSailorMouth,
                settingsStore.glassesMuted
            ) { sailor, muted -> sailor to muted }
                .distinctUntilChanged()
                .collect { (sailorMouth, muted) ->
                    if (bridge.isConnected()) {
                        bridge.sendConfig(mapOf(
                            "sailor_mouth" to sailorMouth.toString(),
                            "muted" to muted.toString()
                        ))
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
        configSyncJob?.cancel()
        configSyncJob = null
    }

    /**
     * Handle an incoming message from the glasses.
     */
    private suspend fun handleGlassesMessage(message: GlassesMessage) {
        val glassesEnabled = settingsStore.glassesEnabled.first()
        if (!glassesEnabled) return

        Log.d(TAG, "Glasses message: type=${message.type}, hasImage=${message.imageBase64 != null}, text=${message.text?.take(40)}")

        when (message.type) {
            MessageType.VOICE_TRANSCRIPTION -> handleVoiceTranscription(message)
            MessageType.CAMERA_PHOTO -> handleCameraPhoto(message)
            MessageType.VOICE_COMMAND -> handleVoiceCommand(message)
            else -> { Log.d(TAG, "Ignoring message type: ${message.type}") }
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

        // Check mute setting — when muted, ignore all voice input
        val muted = settingsStore.glassesMuted.first()
        if (muted) {
            Log.d(TAG, "Glasses muted, ignoring voice: \"${text.take(40)}\"")
            return
        }

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

        // If the photo arrived with a prompt (e.g. voice-triggered "what am I
        // looking at?"), send immediately — no reason to wait for a follow-up.
        if (!promptText.isNullOrBlank()) {
            Log.i(TAG, "Photo has prompt — sending immediately: \"$promptText\"")
            _pendingGlassesPhoto.value = null
            vesperAgent.sendMessage(
                userMessage = promptText,
                imageAttachments = listOf(attachment)
            )
            return
        }

        // No prompt — hold as pending so it can combine with the next voice/text input
        _pendingGlassesPhoto.value = attachment

        photoHoldJob = scope.launch {
            delay(PHOTO_HOLD_TIMEOUT_MS)
            val stillPending = _pendingGlassesPhoto.value
            if (stillPending != null && stillPending.id == attachment.id) {
                Log.i(TAG, "Photo hold timed out — sending with default prompt")
                _pendingGlassesPhoto.value = null
                vesperAgent.sendMessage(
                    userMessage = "What am I looking at?",
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
            // Summarize for TTS — use LLM for intelligent compression
            val spokenText = summarizeForSpeech(lastMsg.content)
            bridge.sendResponse(spokenText)
            clearApprovalState()
        }

        lastProcessedMessageCount = messages.size
    }

    /**
     * Compress an AI response into a brief spoken summary using the LLM.
     * Falls back to simple truncation if the LLM call fails or the text is
     * already short enough to speak directly.
     */
    private suspend fun summarizeForSpeech(text: String): String {
        // Strip markdown for length check
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("\\[.*?]\\(.*?\\)"), "")
            .replace(Regex("[*_~`#>]"), "")
            .replace(Regex("\\n+"), " ")
            .trim()

        // Short enough to speak directly
        if (clean.length <= MAX_SPEECH_CHARS) return clean

        // Use the LLM to intelligently summarize for speech
        val sailorMouth = settingsStore.glassesSailorMouth.first()
        val prompt = if (sailorMouth) SAILOR_SPEECH_SUMMARY_PROMPT else SPEECH_SUMMARY_PROMPT
        return try {
            val result = openRouterClient.sendMessage(
                message = clean,
                customSystemPrompt = prompt
            )
            result.getOrNull()?.trim()?.take(MAX_SPEECH_CHARS)
                ?: truncateFallback(clean)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Speech summarization failed, using fallback: ${e.message}")
            truncateFallback(clean)
        }
    }

    /** Fast fallback: grab first sentence, cut at word boundary. */
    private fun truncateFallback(text: String): String {
        val firstSentence = Regex("[.!?]\\s+|[.!?]$").find(text)
        if (firstSentence != null && firstSentence.range.first < MAX_SPEECH_CHARS) {
            return text.substring(0, firstSentence.range.first + 1).trim()
        }
        val cutoff = text.take(MAX_SPEECH_CHARS).lastIndexOf(' ')
        return if (cutoff > 40) text.substring(0, cutoff).trim() + "."
        else text.take(MAX_SPEECH_CHARS).trim() + "."
    }

    /**
     * Narrate agent progress through glasses — key moments with enough
     * feedback that the user knows things are working, without being chatty.
     */
    private suspend fun handleProgressUpdate(progress: AgentProgress) {
        // Avoid repeating the exact same stage + detail combo
        if (progress.stage == lastSpokenProgressStage &&
            progress.detail == lastSpokenProgressDetail) return
        lastSpokenProgressStage = progress.stage
        lastSpokenProgressDetail = progress.detail

        val sailor = settingsStore.glassesSailorMouth.first()

        val narration = when (progress.stage) {
            AgentProgressStage.MODEL_REQUEST -> {
                if (progress.detail?.contains("Summarizing") == true) null
                else if (sailor) sailorNarration("thinking") else "Thinking..."
            }
            AgentProgressStage.TOOL_PLANNED -> {
                val clean = progress.detail?.let { briefNarration(it) }
                if (sailor && clean != null) sailorNarration("planned", clean) else clean
            }
            AgentProgressStage.TOOL_EXECUTING -> {
                val clean = progress.detail?.let { briefNarration(it) }
                if (sailor && clean != null) sailorNarration("executing", clean) else clean
            }
            AgentProgressStage.TOOL_COMPLETED -> {
                val isFail = progress.detail?.contains("failed", ignoreCase = true) == true
                if (isFail) {
                    val clean = progress.detail?.let { briefNarration(it) }
                    if (sailor && clean != null) sailorNarration("failed", clean) else clean
                } else null
            }
            AgentProgressStage.WAITING_APPROVAL -> null
        } ?: return

        Log.d(TAG, "Glasses narration: $narration")
        bridge.sendStatus(narration)
    }

    /**
     * Clean up a progress detail string for brief spoken narration.
     */
    private fun briefNarration(detail: String): String {
        return detail
            .replace(Regex("\\(.*?\\)"), "")
            .replace("...", "")
            .trim()
            .take(60)
    }

    /**
     * Generate a sailor-mouth flavored narration for a progress stage.
     */
    private fun sailorNarration(stage: String, detail: String? = null): String {
        return when (stage) {
            "thinking" -> listOf(
                "hold on, thinking...",
                "gimme a sec, working on it",
                "cooking something up...",
                "let me figure this shit out",
                "brain's churning, one sec"
            ).random()
            "planned" -> listOf(
                "alright, firing up $detail",
                "let's fucking go, $detail",
                "kicking off $detail",
                "time to roll, $detail"
            ).random()
            "executing" -> listOf(
                "doing the thing, $detail",
                "on it, $detail",
                "running $detail, hold tight",
                "executing $detail, baby"
            ).random()
            "failed" -> listOf(
                "shit, $detail",
                "well that's fucked, $detail",
                "goddammit, $detail",
                "oh hell, $detail"
            ).random()
            else -> detail ?: "working on it"
        }
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
