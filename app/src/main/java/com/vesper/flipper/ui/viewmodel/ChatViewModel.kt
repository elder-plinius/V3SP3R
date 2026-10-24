package com.vesper.flipper.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.ble.BleServiceManager
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.database.ChatSessionSummary
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.glasses.BridgeState
import com.vesper.flipper.glasses.GlassesIntegration
import com.vesper.flipper.glasses.MessageType
import com.vesper.flipper.voice.OpenRouterTtsService
import com.vesper.flipper.voice.SpeechRecognitionHelper
import com.vesper.flipper.voice.SpeechState
import com.vesper.flipper.voice.TtsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Voice capture phase, flattened for the UI.
 *
 * [SpeechState.Result] folds into [IDLE] because the transcript is already
 * appended to the input field by the time the screen sees it.
 */
enum class ChatVoiceState { IDLE, LISTENING, PROCESSING }

/**
 * One immutable snapshot of everything chat needs to draw.
 *
 * The screen collects this exactly once. Before this existed, ten separate
 * collectAsState calls at the screen root meant every partial voice transcript
 * recomposed the whole message list.
 */
data class ChatUiState(
    val isReady: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val progress: AgentProgress? = null,
    val error: String? = null,
    val pendingApproval: PendingApproval? = null,
    val sessionId: String = "",
    val input: String = "",
    val pendingImages: List<ImageAttachment> = emptyList(),
    val isProcessingImage: Boolean = false,
    val imageError: String? = null,
    val voiceState: ChatVoiceState = ChatVoiceState.IDLE,
    val voicePartialText: String = "",
    val voiceError: String? = null,
    val ttsSpeakingId: String? = null,
    val ttsLoading: Boolean = false,
    val deviceName: String? = null,
    val hasDevice: Boolean = false,
    val hasApiKey: Boolean = false,
    val glassesConnected: Boolean = false,
    val glassesMuted: Boolean = false,
    /** True only while a model/tool turn is cancellable (not during approval execution). */
    val canStop: Boolean = false,
    /** Set when the user stops a turn, so the list can offer Retry without an error. */
    val turnStopped: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val vesperAgent: VesperAgent,
    private val ttsService: OpenRouterTtsService,
    private val settingsStore: SettingsStore,
    private val glassesIntegration: GlassesIntegration,
    private val bleServiceManager: BleServiceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val conversationState: StateFlow<ConversationState> = vesperAgent.conversationState

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val pendingImages: StateFlow<List<ImageAttachment>> = _pendingImages.asStateFlow()

    private val _isProcessingImage = MutableStateFlow(false)
    val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()

    private val _imageError = MutableStateFlow<String?>(null)
    val imageError: StateFlow<String?> = _imageError.asStateFlow()

    fun clearImageError() { _imageError.value = null }

    // Voice input
    private val speechRecognitionHelper = SpeechRecognitionHelper(context)
    val voiceState: StateFlow<SpeechState> = speechRecognitionHelper.state
    val voicePartialResult: StateFlow<String> = speechRecognitionHelper.partialResult

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()
    private var approvalDecisionInFlight = false

    // TTS state
    val ttsState: StateFlow<TtsState> = ttsService.state

    // Smart glasses bridge state
    val glassesBridgeState: StateFlow<BridgeState> = glassesIntegration.bridgeState

    // ── Turn lifecycle ───────────────────────────────────────
    private var agentTurnJob: Job? = null
    /** Invalidates a stale turn's finally block after a fast Stop then Send. */
    private var turnSequence = 0L
    private val _canStop = MutableStateFlow(false)
    private val _turnStopped = MutableStateFlow(false)
    /** Local safety net: hides loading if the agent reset ever fails after a Stop. */
    private val _cancelledTurn = MutableStateFlow(false)
    private val _ttsSpeakingId = MutableStateFlow<String?>(null)

    private val inputUi: Flow<InputUi> = combine(
        _inputText,
        _pendingImages,
        _isProcessingImage,
        _imageError
    ) { text, images, processing, error ->
        InputUi(text, images, processing, error)
    }

    private val voiceUi: Flow<VoiceUi> = combine(
        speechRecognitionHelper.state,
        speechRecognitionHelper.partialResult,
        _voiceError
    ) { state, partial, error ->
        VoiceUi(state.toChatVoiceState(), partial, error)
    }

    private val ttsUi: Flow<TtsUi> = combine(ttsService.state, _ttsSpeakingId) { state, speakingId ->
        TtsUi(speakingId = speakingId, loading = state is TtsState.Loading)
    }

    private val turnUi: Flow<TurnUi> = combine(_canStop, _turnStopped, _cancelledTurn) { canStop, stopped, cancelled ->
        TurnUi(canStop = canStop, stopped = stopped, cancelled = cancelled)
    }

    private val environmentUi: Flow<EnvironmentUi> = combine(
        bleServiceManager.connectionState,
        settingsStore.apiKey,
        glassesIntegration.bridgeState,
        settingsStore.glassesMuted.catch { emit(false) },
        turnUi
    ) { connection, apiKey, bridge, glassesMuted, turn ->
        EnvironmentUi(
            deviceName = (connection as? ConnectionState.Connected)?.deviceName,
            hasApiKey = !apiKey.isNullOrBlank(),
            glassesConnected = bridge is BridgeState.Connected,
            glassesMuted = glassesMuted,
            canStop = turn.canStop,
            turnStopped = turn.stopped,
            turnCancelled = turn.cancelled
        )
    }

    /**
     * The single state the chat screen collects. The individual StateFlows
     * above stay public so other consumers keep working.
     */
    val uiState: StateFlow<ChatUiState> = combine(
        conversationState,
        inputUi,
        voiceUi,
        ttsUi,
        environmentUi
    ) { conversation, input, voice, tts, environment ->
        ChatUiState(
            isReady = true,
            messages = conversation.messages,
            isLoading = conversation.isLoading && !environment.turnCancelled,
            progress = if (environment.turnCancelled) null else conversation.progress,
            error = conversation.error,
            pendingApproval = if (environment.turnCancelled) null else conversation.pendingApproval,
            sessionId = conversation.sessionId,
            input = input.text,
            pendingImages = input.images,
            isProcessingImage = input.processing,
            imageError = input.error,
            voiceState = voice.mode,
            voicePartialText = voice.partial,
            voiceError = voice.error,
            ttsSpeakingId = tts.speakingId,
            ttsLoading = tts.loading,
            deviceName = environment.deviceName,
            hasDevice = environment.deviceName != null,
            hasApiKey = environment.hasApiKey,
            glassesConnected = environment.glassesConnected,
            glassesMuted = environment.glassesMuted,
            canStop = environment.canStop,
            turnStopped = environment.turnStopped
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ChatUiState()
    )

    init {
        // Listen for voice recognition results
        viewModelScope.launch {
            speechRecognitionHelper.state.collect { state ->
                when (state) {
                    is SpeechState.Result -> {
                        val currentText = _inputText.value
                        val newText = if (currentText.isBlank()) {
                            state.text
                        } else {
                            "$currentText ${state.text}"
                        }
                        _inputText.value = newText
                        _voiceError.value = null
                    }
                    is SpeechState.Error -> {
                        _voiceError.value = state.message
                    }
                    else -> {
                        _voiceError.value = null
                    }
                }
            }
        }

        // Drop the local stop override once the agent reports a settled turn.
        viewModelScope.launch {
            conversationState.collect { state ->
                if (!state.isLoading && _cancelledTurn.value) {
                    _cancelledTurn.value = false
                }
            }
        }

        // TTS bookkeeping so the message list can show which bubble is speaking.
        viewModelScope.launch {
            ttsService.state.collect { state ->
                if (state is TtsState.Idle || state is TtsState.Error) {
                    _ttsSpeakingId.value = null
                }
            }
        }

        // Auto-speak: watch for new completed assistant messages
        viewModelScope.launch {
            var lastMessageCount = 0
            conversationState.collect { state ->
                val messages = state.messages
                if (messages.size > lastMessageCount && !state.isLoading) {
                    val lastMsg = messages.lastOrNull()
                    if (lastMsg != null &&
                        lastMsg.role == MessageRole.ASSISTANT &&
                        lastMsg.status == MessageStatus.COMPLETE &&
                        lastMsg.content.isNotBlank() &&
                        lastMsg.toolCalls.isNullOrEmpty()
                    ) {
                        val autoSpeak = settingsStore.ttsAutoSpeak.first()
                        val ttsEnabled = settingsStore.ttsEnabled.first()
                        val glassesActive = glassesIntegration.isConnected()
                        if (autoSpeak && ttsEnabled && !glassesActive) {
                            speakMessage(lastMsg.id, lastMsg.content)
                        }
                    }
                }
                lastMessageCount = messages.size
            }
        }

        // Auto-connect glasses bridge if configured
        viewModelScope.launch {
            try {
                val autoConnect = settingsStore.glassesAutoConnect.first()
                val enabled = settingsStore.glassesEnabled.first()
                if (autoConnect && enabled) {
                    val url = settingsStore.glassesBridgeUrl.first()
                    if (!url.isNullOrBlank()) {
                        glassesIntegration.connect(url)
                    }
                }
            } catch (e: Exception) {
                // Don't let a bad bridge URL crash the app on startup
                Log.w(TAG, "Glasses auto-connect failed: " + (e.message ?: "unknown"))
            }
        }

        // Relay glasses transcriptions to input field when auto-send is off
        viewModelScope.launch {
            glassesIntegration.incomingMessages.collect { message ->
                if (message.type == MessageType.VOICE_TRANSCRIPTION && message.isFinal) {
                    val autoSend = settingsStore.glassesAutoSend.first()
                    if (!autoSend) {
                        // Append to input text for manual review
                        val text = message.text?.trim() ?: return@collect
                        val currentText = _inputText.value
                        _inputText.value = if (currentText.isBlank()) text else "$currentText $text"
                    }
                }
            }
        }

        // Auto-add glasses photos to pending images so they appear in chat input
        viewModelScope.launch {
            glassesIntegration.pendingGlassesPhoto.collect { photo ->
                if (photo != null && _pendingImages.value.size < MAX_PENDING_IMAGES) {
                    // Add to pending images if not already there
                    if (_pendingImages.value.none { it.id == photo.id }) {
                        _pendingImages.value = _pendingImages.value + photo
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_IMAGE_SIZE = 1024 // Max dimension for resizing
        private const val JPEG_QUALITY = 85
        private const val MAX_IMAGE_FILE_SIZE = 10 * 1024 * 1024 // 10MB max file size
        private const val MAX_PENDING_IMAGES = 4
    }

    /**
     * Check if voice input is available on this device
     */
    fun isVoiceInputAvailable(): Boolean = speechRecognitionHelper.isAvailable()

    /**
     * Start voice input
     */
    fun startVoiceInput() {
        _voiceError.value = null
        speechRecognitionHelper.startListening()
    }

    /**
     * Stop voice input
     */
    fun stopVoiceInput() {
        speechRecognitionHelper.stopListening()
    }

    /**
     * Cancel voice input without using the result
     */
    fun cancelVoiceInput() {
        speechRecognitionHelper.cancel()
    }

    /**
     * Clear voice error
     */
    fun clearVoiceError() {
        _voiceError.value = null
    }

    /**
     * Speak text using OpenRouter TTS
     */
    fun speakText(text: String) {
        viewModelScope.launch {
            ttsService.speak(text)
        }
    }

    /**
     * Speak a specific message so the bubble can show a speaking state.
     */
    fun speakMessage(messageId: String, text: String) {
        _ttsSpeakingId.value = messageId
        speakText(text)
    }

    // ==================== Smart Glasses ====================

    /**
     * Connect to a smart glasses bridge server.
     */
    fun connectGlasses(bridgeUrl: String) {
        viewModelScope.launch {
            try {
                // Stop phone TTS — glasses will handle speech from now on
                ttsService.stop()
                settingsStore.setGlassesBridgeUrl(bridgeUrl)
                glassesIntegration.connect(bridgeUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Glasses connect failed: " + (e.message ?: "unknown"))
            }
        }
    }

    /**
     * Disconnect from the glasses bridge.
     */
    fun disconnectGlasses() {
        glassesIntegration.disconnect()
    }

    /**
     * Check if glasses are currently connected.
     */
    fun isGlassesConnected(): Boolean = glassesIntegration.isConnected()

    // Glasses mic mute — exposed for quick toggle in chat UI
    val glassesMuted: StateFlow<Boolean> = settingsStore.glassesMuted
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleGlassesMute() {
        viewModelScope.launch {
            val current = glassesMuted.value
            settingsStore.setGlassesMuted(!current)
        }
    }

    /**
     * Stop current TTS playback
     */
    fun stopSpeaking() {
        _ttsSpeakingId.value = null
        ttsService.stop()
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognitionHelper.destroy()
        ttsService.stop()
        glassesIntegration.disconnect()
    }

    fun updateInput(text: String) {
        _inputText.value = text
    }

    /**
     * Add an image from URI (gallery or camera)
     * Enforces maximum pending image limit to prevent memory issues.
     */
    fun addImage(uri: Uri) {
        // Enforce max pending images
        if (_pendingImages.value.size >= MAX_PENDING_IMAGES) {
            Log.w(TAG, "Max pending images reached, ignoring addImage")
            return
        }

        viewModelScope.launch {
            _isProcessingImage.value = true
            try {
                val attachment = processImageUri(uri)
                if (attachment != null) {
                    // Double-check limit in case of race condition
                    if (_pendingImages.value.size < MAX_PENDING_IMAGES) {
                        _pendingImages.value = _pendingImages.value + attachment
                    }
                } else {
                    Log.e(TAG, "processImageUri returned null for " + uri)
                    _imageError.value = "Could not read that image"
                }
            } catch (e: Exception) {
                Log.e(TAG, "addImage failed for " + uri, e)
                _imageError.value = "Image error: " + (e.message?.take(60) ?: "unknown")
            } finally {
                _isProcessingImage.value = false
            }
        }
    }

    /**
     * Remove a pending image
     */
    fun removeImage(imageId: String) {
        _pendingImages.value = _pendingImages.value.filter { it.id != imageId }
    }

    /**
     * Clear all pending images
     */
    fun clearPendingImages() {
        _pendingImages.value = emptyList()
    }

    /**
     * Process an image URI to create an ImageAttachment.
     * Includes proper memory management with bitmap recycling.
     */
    private suspend fun processImageUri(uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        try {
            // Check if this is a video — extract a frame instead of decoding as image
            val detectedType = context.contentResolver.getType(uri)
            if (detectedType != null && detectedType.startsWith("video/")) {
                return@withContext extractVideoFrame(uri)
            }

            // Check file size first to prevent OOM
            val fileSize = context.contentResolver.openInputStream(uri)?.use { it.available() } ?: 0
            if (fileSize > MAX_IMAGE_FILE_SIZE) {
                return@withContext null // File too large
            }

            // Decode with inJustDecodeBounds to get dimensions first
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // Calculate sample size to avoid loading huge images into memory
            val (width, height) = options.outWidth to options.outHeight
            var sampleSize = 1
            while (width / sampleSize > MAX_IMAGE_SIZE * 2 || height / sampleSize > MAX_IMAGE_SIZE * 2) {
                sampleSize *= 2
            }

            // Decode with calculated sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            originalBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            // Resize if still needed
            scaledBitmap = scaleBitmap(originalBitmap, MAX_IMAGE_SIZE)

            // Detect mime type
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val format = when {
                mimeType.contains("png") -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }

            // Compress to base64 with bounded output stream
            val outputStream = ByteArrayOutputStream(64 * 1024) // Start with 64KB buffer
            scaledBitmap.compress(format, JPEG_QUALITY, outputStream)
            val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.reset() // Free memory immediately

            val result = ImageAttachment(
                base64Data = base64Data,
                mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg",
                localUri = uri,
                width = scaledBitmap.width,
                height = scaledBitmap.height
            )

            result
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM processing image: " + uri, e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image: " + uri, e)
            null
        } finally {
            // Always recycle bitmaps to free native memory
            if (scaledBitmap != null && scaledBitmap != originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap?.recycle()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxSize
            newHeight = (maxSize / ratio).toInt()
        } else {
            newHeight = maxSize
            newWidth = (maxSize * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Extract a representative frame from a video URI.
     * Grabs a frame at 1 second (or the first available frame) and
     * converts it to an ImageAttachment so the AI can "see" what
     * the user recorded.
     */
    private suspend fun extractVideoFrame(uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        var frameBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(context, uri)
            }
            // Grab frame at 1 second; falls back to nearest keyframe
            frameBitmap = retriever.getFrameAtTime(
                1_000_000L, // 1 second in microseconds
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime() // fallback: any frame
            ?: return@withContext null

            scaledBitmap = scaleBitmap(frameBitmap, MAX_IMAGE_SIZE)

            val outputStream = java.io.ByteArrayOutputStream(64 * 1024)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.reset()

            ImageAttachment(
                base64Data = base64Data,
                mimeType = "image/jpeg",
                localUri = uri,
                width = scaledBitmap.width,
                height = scaledBitmap.height
            )
        } catch (_: Exception) {
            null
        } finally {
            if (scaledBitmap != null && scaledBitmap != frameBitmap) {
                scaledBitmap.recycle()
            }
            frameBitmap?.recycle()
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    fun sendMessage() {
        val message = _inputText.value.trim()
        val images = _pendingImages.value

        // Allow sending if there's text OR images
        if (message.isEmpty() && images.isEmpty()) return
        if (agentTurnJob?.isActive == true) return

        _inputText.value = ""
        _pendingImages.value = emptyList()
        _turnStopped.value = false
        _cancelledTurn.value = false
        glassesIntegration.clearPendingPhoto() // Clear glasses hold timer

        val turnId = ++turnSequence
        _canStop.value = true
        agentTurnJob = viewModelScope.launch {
            try {
                vesperAgent.sendMessage(
                    userMessage = message,
                    imageAttachments = images.ifEmpty { null }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // VesperAgent.sendMessage() has its own try-catch that resets
                // isLoading. This outer catch handles truly unexpected errors
                // (e.g. serialization bugs) so the user sees feedback.
                Log.e(TAG, "sendMessage failed", e)
                _imageError.value = "Could not send: " + (e.message?.take(80) ?: "unknown error")
            } finally {
                finishTurn(turnId)
            }
        }
    }

    fun retryLastMessage() {
        if (agentTurnJob?.isActive == true) return
        _turnStopped.value = false
        _cancelledTurn.value = false
        val turnId = ++turnSequence
        _canStop.value = true
        agentTurnJob = viewModelScope.launch {
            try {
                vesperAgent.retryLastMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "retryLastMessage failed", e)
                _imageError.value = "Retry failed: " + (e.message?.take(80) ?: "unknown error")
            } finally {
                finishTurn(turnId)
            }
        }
    }

    private fun finishTurn(turnId: Long) {
        // A newer turn may already own the slot; never clear its state.
        if (turnId == turnSequence) {
            _canStop.value = false
            agentTurnJob = null
        }
    }

    /**
     * Stop the in-flight model/tool turn. The last user message is kept so the
     * turn can be retried; [turnStopped] drives the Retry affordance.
     */
    fun cancel() {
        val job = agentTurnJob ?: return
        agentTurnJob = null
        job.cancel()
        _canStop.value = false
        _turnStopped.value = true
        _cancelledTurn.value = true
        viewModelScope.launch {
            runCatching { vesperAgent.cancelCurrentTurn() }
        }
    }

    fun dismissStoppedTurn() {
        _turnStopped.value = false
    }

    fun approveAction(approvalId: String? = null) {
        if (approvalDecisionInFlight) return
        val targetApprovalId = approvalId ?: conversationState.value.pendingApproval?.id ?: return
        approvalDecisionInFlight = true
        _turnStopped.value = false
        _cancelledTurn.value = false
        viewModelScope.launch {
            try {
                vesperAgent.continueAfterApproval(targetApprovalId, approved = true)
            } finally {
                approvalDecisionInFlight = false
            }
        }
    }

    fun rejectAction(approvalId: String? = null) {
        if (approvalDecisionInFlight) return
        val targetApprovalId = approvalId ?: conversationState.value.pendingApproval?.id ?: return
        approvalDecisionInFlight = true
        _turnStopped.value = false
        _cancelledTurn.value = false
        viewModelScope.launch {
            try {
                vesperAgent.continueAfterApproval(targetApprovalId, approved = false)
            } finally {
                approvalDecisionInFlight = false
            }
        }
    }

    fun clearConversation() {
        cancelActiveTurn()
        _pendingImages.value = emptyList()
        _turnStopped.value = false
        _cancelledTurn.value = false
        vesperAgent.clearConversation()
    }

    fun startNewSession(deviceName: String? = null) {
        cancelActiveTurn()
        _pendingImages.value = emptyList()
        _turnStopped.value = false
        _cancelledTurn.value = false
        vesperAgent.startNewSession(deviceName)
    }

    private fun cancelActiveTurn() {
        val job = agentTurnJob ?: return
        agentTurnJob = null
        job.cancel()
        _canStop.value = false
        viewModelScope.launch {
            runCatching { vesperAgent.cancelCurrentTurn() }
        }
    }

    val sessionHistory: StateFlow<List<ChatSessionSummary>> =
        vesperAgent.getSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadSession(sessionId: String) {
        cancelActiveTurn()
        _turnStopped.value = false
        _cancelledTurn.value = false
        viewModelScope.launch {
            vesperAgent.loadSession(sessionId)
        }
    }

    fun deleteSession(sessionId: String) {
        // Deleting the live conversation must not leave a turn writing into the
        // new session the agent starts afterwards.
        if (sessionId == conversationState.value.sessionId) {
            cancelActiveTurn()
            _turnStopped.value = false
            _cancelledTurn.value = false
        }
        viewModelScope.launch {
            vesperAgent.deleteSession(sessionId)
        }
    }

    private fun SpeechState.toChatVoiceState(): ChatVoiceState = when (this) {
        is SpeechState.Listening -> ChatVoiceState.LISTENING
        is SpeechState.Processing -> ChatVoiceState.PROCESSING
        else -> ChatVoiceState.IDLE
    }

    private data class InputUi(
        val text: String,
        val images: List<ImageAttachment>,
        val processing: Boolean,
        val error: String?
    )

    private data class VoiceUi(
        val mode: ChatVoiceState,
        val partial: String,
        val error: String?
    )

    private data class TtsUi(
        val speakingId: String?,
        val loading: Boolean
    )

    private data class TurnUi(
        val canStop: Boolean,
        val stopped: Boolean,
        val cancelled: Boolean
    )

    private data class EnvironmentUi(
        val deviceName: String?,
        val hasApiKey: Boolean,
        val glassesConnected: Boolean,
        val glassesMuted: Boolean,
        val canStop: Boolean,
        val turnStopped: Boolean,
        val turnCancelled: Boolean
    )
}
