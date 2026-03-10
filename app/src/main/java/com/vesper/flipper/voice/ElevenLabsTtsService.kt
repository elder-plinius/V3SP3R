package com.vesper.flipper.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.vesper.flipper.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-to-speech service that routes through OpenRouter's audio-capable models.
 * Uses the existing OpenRouter API key — no separate TTS key needed.
 * Streams PCM audio from GPT-Audio for real-time playback.
 */
@Singleton
class OpenRouterTtsService @Inject constructor(
    private val settingsStore: SettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var currentTrack: AudioTrack? = null
    private var currentJob: Job? = null

    /**
     * Speak the given text using OpenRouter's audio model.
     * Streams PCM audio for lowest-latency playback.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return

        stop()

        val apiKey = settingsStore.apiKey.first()
        if (apiKey.isNullOrBlank()) {
            _state.value = TtsState.Error("OpenRouter API key not configured")
            return
        }

        val voiceId = settingsStore.ttsVoiceId.first()

        _state.value = TtsState.Loading

        coroutineScope {
            currentJob = launch(Dispatchers.IO) {
                try {
                    streamAndPlay(apiKey, voiceId, text)
                } catch (e: Exception) {
                    if (_state.value !is TtsState.Idle) {
                        _state.value = TtsState.Error(e.message ?: "TTS playback failed")
                    }
                }
            }
        }
    }

    /**
     * Stop current playback immediately.
     */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        try {
            currentTrack?.apply {
                pause()
                flush()
                release()
            }
        } catch (_: Exception) {}
        currentTrack = null
        _state.value = TtsState.Idle
    }

    /**
     * Check if TTS is available (API key configured and TTS enabled).
     */
    suspend fun isAvailable(): Boolean {
        val enabled = settingsStore.ttsEnabled.first()
        val apiKey = settingsStore.apiKey.first()
        return enabled && !apiKey.isNullOrBlank()
    }

    private suspend fun streamAndPlay(apiKey: String, voiceId: String, text: String) {
        val cleanedText = cleanTextForSpeech(text)
        if (cleanedText.isBlank()) {
            _state.value = TtsState.Idle
            return
        }

        // Build OpenRouter chat completion request with audio output modality
        val requestJson = JSONObject().apply {
            put("model", TTS_MODEL)
            put("stream", true)
            put("modalities", org.json.JSONArray().apply {
                put("text")
                put("audio")
            })
            put("audio", JSONObject().apply {
                put("voice", voiceId)
                put("format", "pcm16")
            })
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Read the following text aloud naturally and expressively. " +
                            "Do not add any commentary, just read the text:\n\n$cleanedText")
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(API_BASE)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string() ?: "Unknown error"
            _state.value = TtsState.Error("OpenRouter API error ${response.code}: $body")
            response.close()
            return
        }

        val inputStream = response.body?.byteStream() ?: run {
            _state.value = TtsState.Error("Empty response from OpenRouter")
            response.close()
            return
        }

        // Set up AudioTrack for PCM 24kHz 16-bit mono playback
        val sampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = audioTrack
        audioTrack.play()
        _state.value = TtsState.Speaking

        val reader = BufferedReader(InputStreamReader(inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (!kotlinx.coroutines.coroutineContext.isActive) break

                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)
                    val delta = chunk
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?: continue

                    val audioData = delta.optJSONObject("audio")
                        ?.optString("data")
                        ?: continue

                    if (audioData.isNotBlank()) {
                        val pcmBytes = Base64.decode(audioData, Base64.NO_WRAP)
                        audioTrack.write(pcmBytes, 0, pcmBytes.size)
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks
                }
            }

            // Flush remaining audio
            withContext(Dispatchers.IO) {
                audioTrack.write(ByteArray(bufferSize), 0, bufferSize)
            }
        } finally {
            reader.close()
            response.close()
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (_: Exception) {}
            currentTrack = null
            if (_state.value is TtsState.Speaking) {
                _state.value = TtsState.Idle
            }
        }
    }

    /**
     * Clean text for speech: remove markdown, code blocks, image descriptions, etc.
     */
    private fun cleanTextForSpeech(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\[Attached image:.*?]"), "")
            .replace(Regex("\\[\\d+ image\\(s\\).*?]"), "")
            .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    companion object {
        private const val API_BASE = "https://openrouter.ai/api/v1/chat/completions"
        private const val TTS_MODEL = "openai/gpt-audio"

        // OpenAI voices available through GPT-Audio
        val AVAILABLE_VOICES = listOf(
            VoiceOption("shimmer", "Shimmer", "Soft, warm female"),
            VoiceOption("coral", "Coral", "Friendly, natural female"),
            VoiceOption("nova", "Nova", "Energetic young female"),
            VoiceOption("sage", "Sage", "Calm, wise female"),
            VoiceOption("alloy", "Alloy", "Neutral, versatile"),
            VoiceOption("ballad", "Ballad", "Warm, expressive"),
            VoiceOption("fable", "Fable", "British, storytelling"),
            VoiceOption("echo", "Echo", "Smooth male"),
            VoiceOption("ash", "Ash", "Confident male"),
            VoiceOption("onyx", "Onyx", "Deep, authoritative male")
        )
    }
}

data class VoiceOption(
    val id: String,
    val name: String,
    val description: String
)

sealed class TtsState {
    data object Idle : TtsState()
    data object Loading : TtsState()
    data object Speaking : TtsState()
    data class Error(val message: String) : TtsState()
}
