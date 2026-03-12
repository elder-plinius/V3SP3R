package com.vesper.flipper.glasses

import android.util.Base64
import android.util.Log
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the [GlassesBridgeClient] into V3SP3R's conversation pipeline.
 *
 * Handles:
 * - Voice transcriptions from glasses → VesperAgent as user messages
 * - Camera photos from glasses → VesperAgent as image attachments
 * - AI responses from VesperAgent → glasses for TTS + HUD display
 * - Flipper status events → glasses for HUD notifications
 */
@Singleton
class GlassesIntegration @Inject constructor(
    val bridge: GlassesBridgeClient,
    private val vesperAgent: VesperAgent,
    private val settingsStore: SettingsStore
) {
    companion object {
        private const val TAG = "GlassesIntegration"
    }

    val bridgeState: StateFlow<BridgeState> = bridge.state
    val incomingMessages: SharedFlow<GlassesMessage> = bridge.incomingMessages

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var messageListenerJob: Job? = null
    private var responseListenerJob: Job? = null
    private var lastProcessedMessageCount = 0

    /**
     * Connect to the glasses bridge and start relaying messages.
     */
    fun connect(bridgeUrl: String) {
        bridge.connect(bridgeUrl)
        startListeners()
    }

    /**
     * Disconnect from the glasses bridge and stop all listeners.
     */
    fun disconnect() {
        stopListeners()
        bridge.disconnect()
    }

    fun isConnected(): Boolean = bridge.isConnected()

    /**
     * Start listening for incoming glasses messages and outgoing AI responses.
     */
    private fun startListeners() {
        stopListeners()

        // Listen for messages FROM glasses (voice, photos)
        messageListenerJob = scope.launch {
            bridge.incomingMessages.collect { message ->
                handleGlassesMessage(message)
            }
        }

        // Listen for AI responses TO send to glasses
        responseListenerJob = scope.launch {
            vesperAgent.conversationState.collect { state ->
                handleConversationUpdate(state)
            }
        }
    }

    private fun stopListeners() {
        messageListenerJob?.cancel()
        messageListenerJob = null
        responseListenerJob?.cancel()
        responseListenerJob = null
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
     */
    private suspend fun handleVoiceTranscription(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return
        if (!message.isFinal) return // Skip partial transcriptions

        Log.i(TAG, "Glasses voice: \"$text\"")

        val autoSend = settingsStore.glassesAutoSend.first()
        if (autoSend) {
            // Send directly to VesperAgent as a user message
            vesperAgent.sendMessage(userMessage = text)
            bridge.sendStatus("Processing: \"${text.take(50)}\"")
        }
        // If autoSend is off, the transcription still reaches the UI via incomingMessages
        // and the ChatViewModel can append it to the input field
    }

    /**
     * Camera photo from glasses → send as image-attached message to VesperAgent.
     */
    private suspend fun handleCameraPhoto(message: GlassesMessage) {
        val imageData = message.imageBase64 ?: return
        val mimeType = message.imageMimeType ?: "image/jpeg"
        val promptText = message.text ?: "What am I looking at?"

        Log.i(TAG, "Glasses camera: ${imageData.length} chars base64, prompt: \"$promptText\"")

        val attachment = ImageAttachment(
            base64Data = imageData,
            mimeType = mimeType
        )

        vesperAgent.sendMessage(
            userMessage = promptText,
            imageAttachments = listOf(attachment)
        )
        bridge.sendStatus("Analyzing image...")
    }

    /**
     * Explicit voice command (already parsed/structured by the glasses app).
     * Same as voice transcription but always auto-sends.
     */
    private suspend fun handleVoiceCommand(message: GlassesMessage) {
        val text = message.text?.trim() ?: return
        if (text.isBlank()) return

        Log.i(TAG, "Glasses command: \"$text\"")
        vesperAgent.sendMessage(userMessage = text)
        bridge.sendStatus("Executing: \"${text.take(50)}\"")
    }

    /**
     * Watch VesperAgent conversation state for new assistant messages
     * and relay them to glasses for TTS + HUD display.
     */
    private suspend fun handleConversationUpdate(state: ConversationState) {
        if (!bridge.isConnected()) return

        val messages = state.messages
        if (messages.size <= lastProcessedMessageCount || state.isLoading) {
            lastProcessedMessageCount = messages.size
            return
        }

        val lastMsg = messages.lastOrNull() ?: return
        if (lastMsg.role == MessageRole.ASSISTANT &&
            lastMsg.status == MessageStatus.COMPLETE &&
            lastMsg.content.isNotBlank() &&
            lastMsg.toolCalls.isNullOrEmpty()
        ) {
            bridge.sendResponse(lastMsg.content)
        }

        lastProcessedMessageCount = messages.size
    }

    fun destroy() {
        disconnect()
        scope.cancel()
        bridge.destroy()
    }
}
