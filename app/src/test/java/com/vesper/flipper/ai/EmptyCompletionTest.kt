package com.vesper.flipper.ai

import com.vesper.flipper.data.*
import com.vesper.flipper.data.database.ChatDao
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.AuditService
import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EmptyCompletionTest {
    private fun parse(message: String): ChatCompletionResult {
        val api = OpenRouterClient(SettingsStore(MemoryPreferences()))
        val body = """{"id":"fixture","model":"fixture","choices":[{"finish_reason":"stop","message":$message}]}"""
        return OpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java)
            .apply { isAccessible = true }.invoke(api, body) as ChatCompletionResult
    }

    @Test fun `missing null whitespace empty parts and reasoning-only replies fail explicitly`() {
        val messages = listOf(
            """{"role":"assistant"}""",
            """{"role":"assistant","content":null}""",
            """{"role":"assistant","content":""}""",
            """{"role":"assistant","content":"  \n\t"}""",
            """{"role":"assistant","content":[]}""",
            """{"role":"assistant","content":[{"type":"text","text":" "}]}""",
            """{"role":"assistant","content":null,"reasoning":"Synthetic internal reasoning, not an answer"}"""
        )
        messages.forEach { message ->
            val result = parse(message)
            assertTrue("Expected explicit error for $message", result is ChatCompletionResult.Error)
            assertTrue((result as ChatCompletionResult.Error).message.contains("no answer"))
            assertNull(result.partialContent)
        }
    }

    @Test fun `tool-only assistant response remains executable`() {
        val result = parse("""{"role":"assistant","content":null,"tool_calls":[{"id":"fixture-call","function":{"name":"execute_command","arguments":"{}"}}]}""")
        assertTrue(result is ChatCompletionResult.Success)
        assertEquals("fixture-call", (result as ChatCompletionResult.Success).toolCalls!!.single().id)
    }

    @Test fun `all malformed tools without text cannot become blank success`() {
        val result = parse("""{"role":"assistant","content":"","tool_calls":[{"id":"","function":{"name":"execute_command","arguments":"{}"}}]}""")
        assertTrue(result is ChatCompletionResult.Error)
    }

    @Test fun `normal text parts still form a final answer`() {
        val result = parse("""{"role":"assistant","content":[{"type":"text","text":"Tool result summary."}]}""")
        assertEquals("Tool result summary.", (result as ChatCompletionResult.Success).content)
    }

    @Test fun `empty answer after a completed tool shows error without blank bubble or replay`() = runBlocking {
        val settings = SettingsStore(MemoryPreferences())
        settings.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "fake-fixture-key", "Fixture/Model"))
        settings.setProvider(AiProvider.OPENROUTER)
        val api = OpenRouterClient(settings)
        val requests = AtomicInteger()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (requests.incrementAndGet() == 1) {
                """{"id":"fixture","model":"Fixture/Model","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-fixture","type":"function","function":{"name":"execute_command","arguments":{"action":"write_file","args":{"path":"/ext/fixture.txt","content":"synthetic"},"reason":"Fixture","expected_result":"Fixture"}}}]}}]}"""
            } else {
                """{"id":"fixture","model":"Fixture/Model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null}}]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
        val executor = mockk<CommandExecutor>(relaxed = true)
        coEvery { executor.execute(any(), any()) } returns CommandResult(success = true, action = CommandAction.WRITE_FILE)
        val agent = VesperAgent(api, executor, mockk<AuditService>(relaxed = true), mockk<ChatDao>(relaxed = true), settings)
        try {
            val state = withTimeout(5000) { agent.sendMessage("Synthetic fixture request") }
            coVerify(exactly = 1) { executor.execute(any(), any()) }
            assertEquals("No automatic model retry", 2, requests.get())
            assertFalse(state.isLoading)
            assertNull(state.progress)
            assertTrue("Completed tool result must remain visible", state.messages.any { message ->
                message.toolResults.orEmpty().any { it.toolCallId == "call-fixture" && it.success }
            })
            assertFalse("Empty final reply must produce an actionable error", state.error.isNullOrBlank())
            assertFalse("Never append an empty final assistant bubble", state.messages.any {
                it.role == MessageRole.ASSISTANT && it.content.isBlank() && it.toolCalls.isNullOrEmpty()
            })
        } finally {
            (VesperAgent::class.java.getDeclaredField("persistenceScope").apply { isAccessible = true }
                .get(agent) as CoroutineScope).cancel()
            transport.dispatcher.executorService.shutdown()
            transport.connectionPool.evictAll()
        }
    }
}
