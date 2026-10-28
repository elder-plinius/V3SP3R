package com.vesper.flipper.ai

import com.vesper.flipper.data.MemoryPreferences
import com.vesper.flipper.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test

class CompletionTerminationTest {
    private fun parse(body: String): ChatCompletionResult {
        val api = OpenRouterClient(SettingsStore(MemoryPreferences()))
        return OpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java)
            .apply { isAccessible = true }.invoke(api, body) as ChatCompletionResult
    }

    @Test fun `output limit is not reported as a complete answer`() {
        val result = parse("""{"id":"fixture","model":"fixture","choices":[{"finish_reason":"length","message":{"role":"assistant","content":"An incomple"}}]}""")
        assertTrue("Truncated output must be reported explicitly", result is ChatCompletionResult.Error)
        assertTrue((result as ChatCompletionResult.Error).message.contains("limit"))
    }

    @Test fun `output limit never yields executable tools even with valid argument JSON`() {
        val result = parse("""{"id":"fixture","model":"fixture","choices":[{"finish_reason":"length","message":{"role":"assistant","content":"","tool_calls":[{"id":"call-1","function":{"name":"execute_command","arguments":"{}"}}]}}]}""")
        assertTrue("Incomplete tool response must fail closed", result is ChatCompletionResult.Error)
    }

    @Test fun `normal stop still returns the complete answer`() {
        val result = parse("""{"id":"fixture","model":"fixture","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Complete fixture."}}]}""")
        assertEquals("Complete fixture.", (result as ChatCompletionResult.Success).content)
    }
}
