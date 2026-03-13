package com.vesper.flipper.glasses

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket client that connects the V3SP3R Android app to a smart glasses
 * bridge server (MentraOS cloud app, local bridge, etc.).
 *
 * The bridge server is a lightweight companion app running alongside the
 * glasses runtime. It relays:
 *   Glasses → V3SP3R:  voice transcriptions, camera photos
 *   V3SP3R → Glasses:  AI responses (for TTS), status updates (for HUD)
 *
 * Protocol: JSON messages over WebSocket, defined by [GlassesMessage].
 */
@Singleton
class GlassesBridgeClient @Inject constructor() {

    companion object {
        private const val TAG = "GlassesBridge"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val PING_INTERVAL_SEC = 30L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<GlassesMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<GlassesMessage> = _incomingMessages.asSharedFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var bridgeUrl: String? = null
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    /**
     * Connect to a glasses bridge server.
     * @param url WebSocket URL of the bridge (e.g., "wss://your-bridge.railway.app/ws"
     *            or "ws://192.168.1.100:8089/ws" for local dev)
     */
    fun connect(url: String) {
        // Tear down any existing connection first
        reconnectJob?.cancel()
        reconnectJob = null
        isConnecting.set(false)
        webSocket?.cancel() // Hard cancel — avoids async onClosed clobbering new state
        webSocket = null

        val sanitized = sanitizeUrl(url)
        if (sanitized == null) {
            _state.value = BridgeState.Error("Invalid bridge URL: $url")
            Log.w(TAG, "Invalid bridge URL, not connecting: $url")
            return
        }
        bridgeUrl = sanitized
        reconnectAttempts = 0
        doConnect(sanitized)
    }

    /**
     * Disconnect from the bridge and stop reconnection attempts.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        bridgeUrl = null
        reconnectAttempts = 0
        isConnecting.set(false)
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _state.value = BridgeState.Disconnected
    }

    /**
     * Send an AI response to glasses for TTS playback + HUD display.
     */
    fun sendResponse(text: String, displayText: String? = null) {
        send(GlassesMessage(
            type = MessageType.AI_RESPONSE,
            text = text,
            displayText = displayText ?: truncateForDisplay(text)
        ))
    }

    /**
     * Send a Flipper status update to glasses HUD.
     */
    fun sendStatus(status: String) {
        send(GlassesMessage(
            type = MessageType.STATUS_UPDATE,
            text = status
        ))
    }

    /**
     * Push a config key-value to the bridge (e.g. sailor_mouth toggle).
     */
    fun sendConfig(config: Map<String, String>) {
        send(GlassesMessage(
            type = MessageType.CONFIG,
            metadata = config
        ))
    }

    fun isConnected(): Boolean = _state.value is BridgeState.Connected

    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    // ==================== Internal ====================

    private fun doConnect(url: String) {
        if (!isConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "doConnect skipped — another connection attempt is already in progress")
            return
        }
        _state.value = BridgeState.Connecting(url)
        Log.i(TAG, "Connecting to glasses bridge: $url")

        val request = try {
            Request.Builder()
                .url(url)
                .header("X-Vesper-Client", "v3sp3r-android")
                .build()
        } catch (e: Exception) {
            isConnecting.set(false)
            _state.value = BridgeState.Error("Invalid URL: ${e.message}")
            Log.e(TAG, "Failed to build request for bridge URL: $url", e)
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting.set(false)
                reconnectAttempts = 0
                _state.value = BridgeState.Connected(url)
                Log.i(TAG, "Connected to glasses bridge")

                // Send handshake
                send(GlassesMessage(
                    type = MessageType.STATUS_UPDATE,
                    text = "V3SP3R connected",
                    metadata = mapOf("client" to "v3sp3r-android", "version" to "1.0")
                ))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d(TAG, "Bridge raw: ${text.take(200)}")
                    val message = json.decodeFromString<GlassesMessage>(text)
                    Log.d(TAG, "Parsed: type=${message.type}, hasImage=${message.imageBase64 != null}")
                    scope.launch { _incomingMessages.emit(message) }
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid message from bridge: ${e.message}")
                    Log.w(TAG, "Raw message was: ${text.take(300)}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting.set(false)
                this@GlassesBridgeClient.webSocket = null
                Log.i(TAG, "Bridge connection closed (code=$code): $reason")
                if (code == 1000) {
                    // Normal close (user disconnect) — don't reconnect
                    _state.value = BridgeState.Disconnected
                } else {
                    _state.value = BridgeState.Error("Connection closed: $reason (code $code)")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting.set(false)
                this@GlassesBridgeClient.webSocket = null
                val reason = t.message ?: "unknown error"
                Log.w(TAG, "Bridge connection failed: $reason", t)
                _state.value = BridgeState.Error("Connection failed: $reason")
                scheduleReconnect()
            }
        })
    }

    private fun send(message: GlassesMessage) {
        val ws = webSocket ?: return
        try {
            ws.send(json.encodeToString(message))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send to bridge: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        val url = bridgeUrl ?: run {
            _state.value = BridgeState.Disconnected
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _state.value = BridgeState.Error("Lost connection to glasses bridge after $MAX_RECONNECT_ATTEMPTS attempts")
            return
        }

        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        _state.value = BridgeState.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)
        Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            doConnect(url)
        }
    }

    /**
     * Sanitize and normalize a bridge URL.
     * Accepts https:// or http:// (auto-converts to wss:// / ws://) and
     * trims whitespace. Returns null if the URL is fundamentally invalid.
     */
    private fun sanitizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // Auto-convert https/http to wss/ws for WebSocket
        val converted = when {
            trimmed.startsWith("https://", ignoreCase = true) ->
                "wss://" + trimmed.substring("https://".length)
            trimmed.startsWith("http://", ignoreCase = true) ->
                "ws://" + trimmed.substring("http://".length)
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            // No scheme — assume wss
            else -> "wss://$trimmed"
        }

        // Basic validity: must have a host after the scheme
        val hostPart = converted.substringAfter("://")
        if (hostPart.isBlank() || hostPart.startsWith("/")) return null

        return converted
    }

    private fun truncateForDisplay(text: String): String {
        // Strip markdown and truncate for glasses HUD
        return text
            .replace(Regex("```[\\s\\S]*?```"), "[code]")
            .replace(Regex("\\[.*?]\\(.*?\\)"), "")
            .replace(Regex("[*_~`#]"), "")
            .take(200)
            .trim()
    }
}

// ==================== Bridge State ====================

sealed class BridgeState {
    data object Disconnected : BridgeState()
    data class Connecting(val url: String) : BridgeState()
    data class Connected(val url: String) : BridgeState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : BridgeState()
    data class Error(val message: String) : BridgeState()
}

// ==================== Wire Protocol ====================

@Serializable
data class GlassesMessage(
    val type: MessageType,
    val text: String? = null,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val displayText: String? = null,
    val isFinal: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
enum class MessageType {
    // Glasses → V3SP3R
    VOICE_TRANSCRIPTION,
    CAMERA_PHOTO,
    VOICE_COMMAND,

    // V3SP3R → Glasses
    AI_RESPONSE,
    STATUS_UPDATE,

    // V3SP3R → Bridge (config sync)
    CONFIG,

    // Bridge → Glasses (photo request)
    CAPTURE_REQUEST
}
