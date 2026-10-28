package com.vesper.flipper.ai

import com.vesper.flipper.data.AiConnectionConfig
import com.vesper.flipper.data.AiProvider
import com.vesper.flipper.data.MemoryPreferences
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.MessageRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputBudgetTest {
    @Test fun `tool-enabled scan summary has room to finish in one request`() = runBlocking {
        val settings = SettingsStore(MemoryPreferences())
        settings.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "fake-test-key", "fixture/model"))
        settings.setProvider(AiProvider.ORCAROUTER)
        val api = OpenRouterClient(settings)
        val outgoingBudgets = mutableListOf<Int>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body!!.writeTo(it) }
            val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val maxTokens = payload["max_tokens"]!!.jsonPrimitive.int
            outgoingBudgets += maxTokens
            // Local provider fixture: the old 1,024-token cap reproduces the phone's
            // finish_reason=length; a larger budget permits the complete response.
            val complete = maxTokens >= 4096
            val body = """{"id":"fixture","model":"fixture/model","choices":[{"finish_reason":"${if (complete) "stop" else "length"}","message":{"role":"assistant","content":"${if (complete) "Complete saved-scan summary." else "Incomplete saved-scan"}"}}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)

        val result = api.chat(listOf(ChatMessage(role = MessageRole.USER, content = "Summarize the saved scan; do not run a new scan.")), "fixture-session")
        assertTrue("A one-call reply must complete, not return a length error: $result", result is ChatCompletionResult.Success)
        assertEquals("Complete saved-scan summary.", (result as ChatCompletionResult.Success).content)
        assertEquals("No automatic paid retry", listOf(4096), outgoingBudgets)
    }
}
