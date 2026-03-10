package com.vesper.flipper.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.VesperAgent
import com.vesper.flipper.ble.ConnectionState
import com.vesper.flipper.ble.FlipperBleService
import com.vesper.flipper.ble.FlipperDevice
import com.vesper.flipper.data.database.ChatSessionSummary
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.voice.OpenRouterTtsService
import com.vesper.flipper.voice.SpeechRecognitionHelper
import com.vesper.flipper.voice.SpeechState
import com.vesper.flipper.voice.TtsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val vesperAgent: VesperAgent,
    private val ttsService: OpenRouterTtsService,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val conversationState: StateFlow<ConversationState> = vesperAgent.conversationState

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val pendingImages: StateFlow<List<ImageAttachment>> = _pendingImages.asStateFlow()

    private val _isProcessingImage = MutableStateFlow(false)
    val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()

    // Voice input
    private val speechRecognitionHelper = SpeechRecognitionHelper(context)
    val voiceState: StateFlow<SpeechState> = speechRecognitionHelper.state
    val voicePartialResult: StateFlow<String> = speechRecognitionHelper.partialResult

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()
    private var approvalDecisionInFlight = false

    // TTS state
    val ttsState: StateFlow<TtsState> = ttsService.state

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
                        if (autoSpeak && ttsEnabled) {
                            speakText(lastMsg.content)
                        }
                    }
                }
                lastMessageCount = messages.size
            }
        }
    }

    companion object {
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
     * Stop current TTS playback
     */
    fun stopSpeaking() {
        ttsService.stop()
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognitionHelper.destroy()
        ttsService.stop()
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
            return // Silently ignore - UI should prevent this
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
                }
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
            // Force garbage collection on OOM
            System.gc()
            null
        } catch (e: Exception) {
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

        _inputText.value = ""
        _pendingImages.value = emptyList()

        viewModelScope.launch {
            vesperAgent.sendMessage(
                userMessage = message,
                imageAttachments = images.ifEmpty { null }
            )
        }
    }

    fun approveAction(approvalId: String? = null) {
        if (approvalDecisionInFlight) return
        val targetApprovalId = approvalId ?: conversationState.value.pendingApproval?.id ?: return
        approvalDecisionInFlight = true
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
        viewModelScope.launch {
            try {
                vesperAgent.continueAfterApproval(targetApprovalId, approved = false)
            } finally {
                approvalDecisionInFlight = false
            }
        }
    }

    fun clearConversation() {
        _pendingImages.value = emptyList()
        vesperAgent.clearConversation()
    }

    fun startNewSession(deviceName: String? = null) {
        _pendingImages.value = emptyList()
        vesperAgent.startNewSession(deviceName)
    }

    val sessionHistory: StateFlow<List<ChatSessionSummary>> =
        vesperAgent.getSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            vesperAgent.loadSession(sessionId)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            vesperAgent.deleteSession(sessionId)
        }
    }
}
