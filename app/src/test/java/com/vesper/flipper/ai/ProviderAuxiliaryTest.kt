package com.vesper.flipper.ai

import com.vesper.flipper.data.*
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.ui.viewmodel.SpectralOracleViewModel
import com.vesper.flipper.ui.viewmodel.OracleAnalysisResult
import com.vesper.flipper.voice.OpenRouterTtsService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderAuxiliaryTest {
    @Test fun `OpenRouter speech never considers another provider key available`() = runBlocking {
        val settings = SettingsStore(MemoryPreferences())
        settings.setProvider(AiProvider.ORCAROUTER)
        settings.setApiKey("fake-orca-key", AiProvider.ORCAROUTER)
        settings.setTtsEnabled(true)
        val tts = OpenRouterTtsService(settings)
        assertFalse(tts.isAvailable())
        settings.setApiKey("fake-openrouter-key", AiProvider.OPENROUTER)
        assertTrue(tts.isAvailable())
    }

    @Test fun `Oracle analysis routes its atomic config without changing analysis payload`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fs = mockk<FlipperFileSystem>()
            coEvery { fs.listDirectory(any()) } returns Result.success(emptyList())
            val settings = SettingsStore(MemoryPreferences())
            settings.setProvider(AiProvider.ABLITERATED)
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.ABLITERATED, "fake-abliteration-key", "abliterated-model-large-v2"))
            val vm = SpectralOracleViewModel(fs, settings)
            MockWebServer().use { server ->
                server.start()
                var url = ""
                val field = SpectralOracleViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
                field.set(vm, (field.get(vm) as OkHttpClient).newBuilder().addInterceptor { chain ->
                    url = chain.request().url.toString()
                    chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
                }.build())
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Test response"}}]}"""))
                val request = SignalIntelRequest(CapturedSignalData(0, emptyList(), null, 0), AnalysisType.FULL_ANALYSIS)
                val method = SpectralOracleViewModel::class.java.getDeclaredMethod("performAIAnalysis", SignalIntelRequest::class.java, Continuation::class.java).apply { isAccessible = true }
                suspendCoroutine<OracleAnalysisResult> { continuation ->
                    val result = method.invoke(vm, request, continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(result as OracleAnalysisResult)
                }
                assertEquals("https://api.abliteration.ai/v1/chat/completions", url)
                val sent = server.takeRequest()
                assertEquals("Bearer fake-abliteration-key", sent.getHeader("Authorization"))
                val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals("abliterated-model-large-v2", body["model"]!!.jsonPrimitive.content)
                assertEquals(4000, body["max_tokens"]!!.jsonPrimitive.int)
                assertEquals(0.3, body["temperature"]!!.jsonPrimitive.double, 0.001)
            }
        } finally { Dispatchers.resetMain() }
    }
}
