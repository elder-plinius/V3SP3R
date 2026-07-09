package com.vesper.flipper.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRequestSerializationTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `serializes session id for auto-router stickiness`() {
        val request = OpenRouterRequest(
            model = "openrouter/auto",
            messages = listOf(
                OpenRouterMessage.text(role = "user", content = "ping")
            ),
            sessionId = "chat-session-123"
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"model\":\"openrouter/auto\""))
        assertTrue(encoded.contains("\"session_id\":\"chat-session-123\""))
    }
}
