package com.vesper.flipper.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Integration tests for MiniMax API.
 * These tests hit the real MiniMax API and require MINIMAX_API_KEY to be set.
 * Skipped automatically if the key is not available.
 */
class MiniMaxClientIntegrationTest {

    private val apiKey = System.getenv("MINIMAX_API_KEY")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun requireApiKey() {
        assumeTrue(
            "MINIMAX_API_KEY not set — skipping integration test",
            !apiKey.isNullOrBlank()
        )
    }

    @Test
    fun `MiniMax API - simple chat completion`() {
        requireApiKey()

        val requestBody = buildJsonObject {
            put("model", "MiniMax-M2.7")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "Reply with exactly: PONG")
                }
            }
            put("max_tokens", 10)
            put("temperature", 0.01)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.minimax.io/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue("Expected HTTP 200, got ${response.code}", response.isSuccessful)
            val body = response.body?.string() ?: fail("Empty response body")
            val parsed = json.decodeFromString<OpenRouterResponse>(body)
            assertTrue("Expected at least one choice", parsed.choices.isNotEmpty())
            val content = parsed.choices[0].message.textContent ?: ""
            val stripped = MiniMaxClient.stripThinkingTags(content)
            assertTrue("Response should contain PONG, got: $stripped", stripped.contains("PONG"))
        }
    }

    @Test
    fun `MiniMax API - tool calling with execute_command`() {
        requireApiKey()

        val toolDef = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "execute_command")
                put("description", "Execute a command on the device")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("list_directory")
                                add("get_device_info")
                            }
                        }
                        putJsonObject("args") {
                            put("type", "object")
                        }
                    }
                    putJsonArray("required") {
                        add("action")
                        add("args")
                    }
                }
            }
        }

        val requestBody = buildJsonObject {
            put("model", "MiniMax-M2.7")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "You control a Flipper Zero device. Use the execute_command tool.")
                }
                addJsonObject {
                    put("role", "user")
                    put("content", "Show me device info")
                }
            }
            putJsonArray("tools") { add(toolDef) }
            put("tool_choice", "auto")
            put("max_tokens", 200)
            put("temperature", 0.01)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.minimax.io/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue("Expected HTTP 200, got ${response.code}", response.isSuccessful)
            val body = response.body?.string() ?: fail("Empty response body")
            val parsed = json.decodeFromString<OpenRouterResponse>(body)
            assertTrue("Expected at least one choice", parsed.choices.isNotEmpty())

            val message = parsed.choices[0].message
            // Model should either respond with text or make a tool call
            val hasContent = !message.textContent.isNullOrBlank()
            val hasToolCalls = !message.toolCalls.isNullOrEmpty()
            assertTrue(
                "Expected text content or tool calls, got neither",
                hasContent || hasToolCalls
            )

            if (hasToolCalls) {
                val tc = message.toolCalls!![0]
                assertEquals("execute_command", tc.function.name)
                assertTrue(
                    "Tool call arguments should contain 'action'",
                    tc.function.arguments.contains("action")
                )
            }
        }
    }

    @Test
    fun `MiniMax API - M2_7 highspeed model works`() {
        requireApiKey()

        val requestBody = buildJsonObject {
            put("model", "MiniMax-M2.7-highspeed")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "Say hello in one word.")
                }
            }
            put("max_tokens", 10)
            put("temperature", 0.01)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.minimax.io/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue("Expected HTTP 200, got ${response.code}", response.isSuccessful)
            val body = response.body?.string() ?: fail("Empty response body")
            val parsed = json.decodeFromString<OpenRouterResponse>(body)
            assertTrue("Expected at least one choice", parsed.choices.isNotEmpty())
            assertNotNull(parsed.choices[0].message.textContent)
        }
    }
}
