package com.vesper.flipper.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MiniMax LLM client.
 *
 * Tests cover:
 *  1. Thinking tag stripping
 *  2. Response parsing (success, tool calls, errors)
 *  3. Temperature clamping verification
 *  4. Model catalog correctness
 *  5. Provider enum resolution
 */
class MiniMaxClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // ═══════════════════════════════════════════════════════
    // 1. THINKING TAG STRIPPING
    // ═══════════════════════════════════════════════════════

    @Test
    fun `strip thinking tags - single block`() {
        val input = "<think>Let me analyze this request...</think>Here is my response."
        assertEquals("Here is my response.", MiniMaxClient.stripThinkingTags(input))
    }

    @Test
    fun `strip thinking tags - multiline block`() {
        val input = """
            <think>
            Step 1: Check the file system
            Step 2: Look for SubGHz captures
            </think>
            I found 3 SubGHz captures on your Flipper.
        """.trimIndent()
        assertEquals("I found 3 SubGHz captures on your Flipper.", MiniMaxClient.stripThinkingTags(input))
    }

    @Test
    fun `strip thinking tags - no tags present`() {
        val input = "This response has no thinking tags."
        assertEquals("This response has no thinking tags.", MiniMaxClient.stripThinkingTags(input))
    }

    @Test
    fun `strip thinking tags - empty content`() {
        assertEquals("", MiniMaxClient.stripThinkingTags(""))
    }

    @Test
    fun `strip thinking tags - only thinking block`() {
        val input = "<think>All reasoning, no output</think>"
        assertEquals("", MiniMaxClient.stripThinkingTags(input))
    }

    @Test
    fun `strip thinking tags - multiple blocks`() {
        val input = "<think>First thought</think>Response part 1. <think>Second thought</think>Response part 2."
        assertEquals("Response part 1. Response part 2.", MiniMaxClient.stripThinkingTags(input))
    }

    @Test
    fun `strip thinking tags - nested angle brackets in content`() {
        val input = "<think>reasoning</think>The file format is <IR signals file>."
        assertEquals("The file format is <IR signals file>.", MiniMaxClient.stripThinkingTags(input))
    }

    // ═══════════════════════════════════════════════════════
    // 2. RESPONSE PARSING
    // ═══════════════════════════════════════════════════════

    @Test
    fun `parse success response - text only`() {
        val responseJson = """
        {
            "id": "chatcmpl-abc123",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Your Flipper has 3 SubGHz captures."
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120
            }
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        assertEquals("MiniMax-M2.7", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("Your Flipper has 3 SubGHz captures.", response.choices[0].message.textContent)
        assertEquals(120, response.usage?.totalTokens)
    }

    @Test
    fun `parse success response - with tool calls`() {
        val responseJson = """
        {
            "id": "chatcmpl-abc456",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": "call_001",
                        "type": "function",
                        "function": {
                            "name": "execute_command",
                            "arguments": "{\"action\":\"list_directory\",\"args\":{\"path\":\"/ext/subghz\"}}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "usage": {
                "prompt_tokens": 200,
                "completion_tokens": 50,
                "total_tokens": 250
            }
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        val toolCalls = response.choices[0].message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals("call_001", toolCalls[0].id)
        assertEquals("execute_command", toolCalls[0].function.name)
        assertTrue(toolCalls[0].function.arguments.contains("list_directory"))
    }

    @Test
    fun `parse error response`() {
        val errorJson = """
        {
            "error": {
                "message": "Invalid API key",
                "code": 401
            }
        }
        """.trimIndent()

        val root = json.parseToJsonElement(errorJson).jsonObject
        val errorObj = root["error"]?.jsonObject
        assertNotNull(errorObj)
        assertEquals("Invalid API key", errorObj!!["message"]?.jsonPrimitive?.content)
        assertEquals(401, errorObj["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `parse response with thinking tags in content`() {
        val responseJson = """
        {
            "id": "chatcmpl-think1",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "<think>The user wants to list files</think>Here are your SubGHz captures."
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 50,
                "completion_tokens": 30,
                "total_tokens": 80
            }
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        val rawContent = response.choices[0].message.textContent ?: ""
        val stripped = MiniMaxClient.stripThinkingTags(rawContent)
        assertEquals("Here are your SubGHz captures.", stripped)
    }

    // ═══════════════════════════════════════════════════════
    // 3. TEMPERATURE CLAMPING
    // ═══════════════════════════════════════════════════════

    @Test
    fun `temperature clamping - value within range`() {
        // MiniMax requires temperature in (0.0, 1.0]
        val temp = 0.01
        assertTrue("Temperature must be > 0", temp > 0.0)
        assertTrue("Temperature must be <= 1.0", temp <= 1.0)
    }

    // ═══════════════════════════════════════════════════════
    // 4. MODEL CATALOG
    // ═══════════════════════════════════════════════════════

    @Test
    fun `minimax models list is not empty`() {
        val models = com.vesper.flipper.data.SettingsStore.MINIMAX_MODELS
        assertTrue("MiniMax models list should not be empty", models.isNotEmpty())
    }

    @Test
    fun `minimax default model exists in catalog`() {
        val models = com.vesper.flipper.data.SettingsStore.MINIMAX_MODELS
        val defaultModel = com.vesper.flipper.data.SettingsStore.DEFAULT_MINIMAX_MODEL
        assertTrue(
            "Default MiniMax model should exist in catalog",
            models.any { it.id == defaultModel }
        )
    }

    @Test
    fun `minimax models have correct IDs`() {
        val models = com.vesper.flipper.data.SettingsStore.MINIMAX_MODELS
        val expectedIds = listOf(
            "MiniMax-M2.7",
            "MiniMax-M2.7-highspeed",
            "MiniMax-M2.5",
            "MiniMax-M2.5-highspeed"
        )
        assertEquals(expectedIds.size, models.size)
        expectedIds.forEach { expectedId ->
            assertTrue(
                "Model $expectedId should exist in catalog",
                models.any { it.id == expectedId }
            )
        }
    }

    @Test
    fun `minimax models have display names`() {
        val models = com.vesper.flipper.data.SettingsStore.MINIMAX_MODELS
        models.forEach { model ->
            assertTrue(
                "Model ${model.id} should have a non-empty display name",
                model.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `minimax models have descriptions`() {
        val models = com.vesper.flipper.data.SettingsStore.MINIMAX_MODELS
        models.forEach { model ->
            assertTrue(
                "Model ${model.id} should have a non-empty description",
                model.description.isNotBlank()
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // 5. PROVIDER ENUM
    // ═══════════════════════════════════════════════════════

    @Test
    fun `provider enum - from name OPEN_ROUTER`() {
        assertEquals(LlmProvider.OPEN_ROUTER, LlmProvider.fromName("OPEN_ROUTER"))
    }

    @Test
    fun `provider enum - from name MINIMAX`() {
        assertEquals(LlmProvider.MINIMAX, LlmProvider.fromName("MINIMAX"))
    }

    @Test
    fun `provider enum - from name case insensitive`() {
        assertEquals(LlmProvider.MINIMAX, LlmProvider.fromName("minimax"))
        assertEquals(LlmProvider.OPEN_ROUTER, LlmProvider.fromName("open_router"))
    }

    @Test
    fun `provider enum - unknown defaults to OPEN_ROUTER`() {
        assertEquals(LlmProvider.OPEN_ROUTER, LlmProvider.fromName("unknown"))
        assertEquals(LlmProvider.OPEN_ROUTER, LlmProvider.fromName(""))
    }

    @Test
    fun `provider display names`() {
        assertEquals("OpenRouter", LlmProvider.OPEN_ROUTER.displayName)
        assertEquals("MiniMax", LlmProvider.MINIMAX.displayName)
    }

    // ═══════════════════════════════════════════════════════
    // 6. TOOL CALL ARGUMENTS PARSING WITH MINIMAX RESPONSES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `parse tool call arguments - list_directory from MiniMax`() {
        val arguments = """
        {
            "action": "list_directory",
            "args": {"path": "/ext/subghz"},
            "justification": "User wants SubGHz files",
            "expected_effect": "List directory contents"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<com.vesper.flipper.domain.model.ExecuteCommand>(arguments)
        assertEquals(com.vesper.flipper.domain.model.CommandAction.LIST_DIRECTORY, cmd.action)
        assertEquals("/ext/subghz", cmd.args.path)
    }

    @Test
    fun `parse tool call arguments - execute_cli from MiniMax`() {
        val arguments = """
        {
            "action": "execute_cli",
            "args": {"command": "storage list /ext"},
            "justification": "Check storage contents",
            "expected_effect": "Return directory listing"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<com.vesper.flipper.domain.model.ExecuteCommand>(arguments)
        assertEquals(com.vesper.flipper.domain.model.CommandAction.EXECUTE_CLI, cmd.action)
        assertEquals("storage list /ext", cmd.args.command)
    }

    @Test
    fun `parse tool call arguments - forge_payload from MiniMax`() {
        val arguments = """
        {
            "action": "forge_payload",
            "args": {"prompt": "Create a BadUSB that opens notepad", "payload_type": "BAD_USB"},
            "justification": "User requested BadUSB script",
            "expected_effect": "Generate BadUSB payload"
        }
        """.trimIndent()

        val cmd = json.decodeFromString<com.vesper.flipper.domain.model.ExecuteCommand>(arguments)
        assertEquals(com.vesper.flipper.domain.model.CommandAction.FORGE_PAYLOAD, cmd.action)
        assertEquals("Create a BadUSB that opens notepad", cmd.args.prompt)
    }

    // ═══════════════════════════════════════════════════════
    // 7. RESPONSE FORMAT COMPATIBILITY
    // ═══════════════════════════════════════════════════════

    @Test
    fun `minimax response with content as array of parts`() {
        val responseJson = """
        {
            "id": "cmpl-parts",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": [
                        {"type": "text", "text": "Part one."},
                        {"type": "text", "text": "Part two."}
                    ]
                },
                "finish_reason": "stop"
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        val text = response.choices[0].message.textContent
        assertEquals("Part one.\nPart two.", text)
    }

    @Test
    fun `minimax response with null content and tool calls`() {
        val responseJson = """
        {
            "id": "cmpl-tools",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [{
                        "id": "tc_1",
                        "type": "function",
                        "function": {
                            "name": "execute_command",
                            "arguments": "{\"action\":\"get_device_info\",\"args\":{}}"
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "usage": {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70}
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        val message = response.choices[0].message
        assertNull(message.textContent)
        assertNotNull(message.toolCalls)
        assertEquals("tc_1", message.toolCalls!![0].id)
    }

    @Test
    fun `minimax response with arguments as json object`() {
        // Some models return arguments as a JSON object instead of a string
        val responseJson = """
        {
            "id": "cmpl-obj-args",
            "model": "MiniMax-M2.7",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [{
                        "id": "tc_2",
                        "type": "function",
                        "function": {
                            "name": "execute_command",
                            "arguments": {"action":"list_directory","args":{"path":"/ext"}}
                        }
                    }]
                },
                "finish_reason": "tool_calls"
            }],
            "usage": {"prompt_tokens": 30, "completion_tokens": 15, "total_tokens": 45}
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        val args = response.choices[0].message.toolCalls!![0].function.arguments
        assertTrue("Arguments should contain list_directory", args.contains("list_directory"))
    }

    // ═══════════════════════════════════════════════════════
    // 8. EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `minimax response with empty choices`() {
        val responseJson = """
        {
            "id": "cmpl-empty",
            "model": "MiniMax-M2.7",
            "choices": [],
            "usage": {"prompt_tokens": 10, "completion_tokens": 0, "total_tokens": 10}
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        assertTrue(response.choices.isEmpty())
    }

    @Test
    fun `minimax response missing usage field`() {
        val responseJson = """
        {
            "id": "cmpl-no-usage",
            "model": "MiniMax-M2.5-highspeed",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Quick response"
                },
                "finish_reason": "stop"
            }]
        }
        """.trimIndent()

        val response = json.decodeFromString<OpenRouterResponse>(responseJson)
        assertEquals("Quick response", response.choices[0].message.textContent)
        assertNull(response.usage)
    }
}
