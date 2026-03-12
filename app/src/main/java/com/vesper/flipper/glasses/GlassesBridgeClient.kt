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
        if (webSocket != null) disconnect()
        bridgeUrl = url
        reconnectAttempts = 0
        doConnect(url)
    }

    /**
     * Disconnect from the bridge and stop reconnection attempts.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        bridgeUrl = null
        reconnectAttempts = 0
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

    fun isConnected(): Boolean = _state.value is BridgeState.Connected

    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    // ==================== Internal ====================

    private fun doConnect(url: String) {
        if (!isConnecting.compareAndSet(false, true)) return
        _state.value = BridgeState.Connecting(url)
        Log.i(TAG, "Connecting to glasses bridge: $url")

        val request = Request.Builder()
            .url(url)
            .header("X-Vesper-Client", "v3sp3r-android")
            .build()

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
                    val message = json.decodeFromString<GlassesMessage>(text)
                    scope.launch { _incomingMessages.emit(message) }
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid message from bridge: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting.set(false)
                this@GlassesBridgeClient.webSocket = null
                Log.i(TAG, "Bridge connection closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting.set(false)
                this@GlassesBridgeClient.webSocket = null
                Log.w(TAG, "Bridge connection failed: ${t.message}")
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
    STATUS_UPDATE
}
