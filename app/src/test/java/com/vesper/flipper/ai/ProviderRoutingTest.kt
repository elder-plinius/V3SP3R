package com.vesper.flipper.ai

import com.vesper.flipper.data.*
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderRoutingTest {
    private val response = """{"id":"fake","model":"Vendor/Exact:free","choices":[{"message":{"role":"assistant","content":"OK"}}]}"""

    @Test fun `unavailable models and long rate limits are not blindly retried`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            settings.setProvider(AiProvider.ORCAROUTER)
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "fake-test-key", "Exact"))
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(api, (field.get(api) as OkHttpClient).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }.build())
            repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"model_not_found"}}""")) }
            val unavailable = api.sendMessage("Hello")
            assertTrue(unavailable.isFailure)
            assertEquals("Model availability errors must not retry", 1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "fake-test-key", "Exact"))
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(api, (field.get(api) as OkHttpClient).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }.build())
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", Long.MAX_VALUE))
            assertTrue(kotlinx.coroutines.withTimeout(2000) { api.sendMessage("Hello") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `connection test sends a small benign completion only and does not claim tool support`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            val urls = mutableListOf<String>()
            field.set(api, (field.get(api) as OkHttpClient).newBuilder().addInterceptor { chain ->
                urls.add(chain.request().url.toString())
                chain.proceed(chain.request().newBuilder().url(server.url("/")).build())
            }.build())
            for (provider in AiProvider.entries) {
                server.enqueue(MockResponse().setBody(response))
                val result = api.testConnection(AiConnectionConfig(provider, "fake-test-key", "Vendor/Exact:free"))
                assertTrue(result.getOrThrow().contains("completion", ignoreCase = true))
                assertTrue(result.getOrThrow().contains("not", ignoreCase = true))
                assertEquals("${provider.baseUrl}/chat/completions", urls.last())
                val sent = server.takeRequest()!!
                val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals("Vendor/Exact:free", body["model"]!!.jsonPrimitive.content)
                assertTrue(body["max_tokens"]!!.jsonPrimitive.int <= 32)
                assertFalse(body.containsKey("tools"))
                assertFalse(body.containsKey("tool_choice"))
                if (provider != AiProvider.OPENROUTER) assertNull(sent.getHeader("HTTP-Referer"))
            }
            assertTrue(api.testConnection(AiConnectionConfig(AiProvider.ORCAROUTER, "fake-key", "https://wrong/model")).isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `redirects are refused before credentials or images can be forwarded`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "fake-test-key", "Exact"))
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(api, (field.get(api) as OkHttpClient).newBuilder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/original")).build()) }.build())
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/untrusted")))
            server.enqueue(MockResponse().setBody(response))
            assertTrue(api.sendMessage("Hello").isFailure)
            assertEquals(1, server.requestCount)
            val secured = field.get(api) as OkHttpClient
            assertFalse(secured.followRedirects)
            assertFalse(secured.followSslRedirects)
            assertTrue(secured.callTimeoutMillis in 1..90_000)
        }
    }

    @Test fun `cancellation promptly aborts a pending response`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "fake-test-key", "Exact"))
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(api, (field.get(api) as OkHttpClient).newBuilder()
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/")).build()) }.build())
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val started = System.nanoTime()
            try {
                kotlinx.coroutines.withTimeout(150) { api.chatSimple("OK") }
                fail("Cancellation must propagate")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
            assertTrue("Cancellation must cancel the actual HTTP call, not wait for read timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        }
    }

    @Test fun `provider errors redact reflected credentials and never substitute a model`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val settings = SettingsStore(MemoryPreferences())
            settings.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "fake-reflected-secret", "Vendor/Exact:free"))
            val api = OpenRouterClient(settings)
            val field = OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            val transport = (field.get(api) as OkHttpClient).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build()
            field.set(api, transport)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid fake-reflected-secret"}}"""))
            val result = api.chat(listOf(ChatMessage(role = MessageRole.USER, content = "Hi")), "session") as ChatCompletionResult.Error
            assertFalse(result.message.contains("fake-reflected-secret"))
            assertTrue(result.message.contains("401"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `images tool requests and plain history remain on the captured provider`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val seen = mutableListOf<String>()
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                seen.add(chain.request().url.toString())
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build()
            val settings = SettingsStore(MemoryPreferences())
            val api = OpenRouterClient(settings)
            OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
            val image = ImageAttachment(base64Data = "ZmFrZS1pbWFnZQ==")
            for (provider in AiProvider.entries) {
                settings.setProvider(provider)
                settings.saveProviderConfig(AiConnectionConfig(provider, "fake-${provider.name}", "Vendor/Exact:free"))
                repeat(4) { server.enqueue(MockResponse().setBody(response)) }
                val message = ChatMessage(role = MessageRole.USER, content = "Describe", imageAttachments = listOf(image))
                assertTrue(api.chat(listOf(message), "session") is ChatCompletionResult.Success)
                assertEquals("OK", api.describeImageForAgent(image, "Describe"))
                assertEquals("OK", api.sendMessagesWithoutTools(listOf(message)).getOrThrow())
                repeat(4) {
                    val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("${provider.baseUrl}/chat/completions", seen.removeAt(0))
                    assertEquals("Bearer fake-${provider.name}", sent.getHeader("Authorization"))
                if (provider != AiProvider.OPENROUTER) assertNull(sent.getHeader("HTTP-Referer"))
                    assertEquals("Vendor/Exact:free", Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test fun `plain completion uses selected provider and exact model`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val seen = mutableListOf<String>()
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                seen.add(chain.request().url.toString())
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build()
            val settings = SettingsStore(MemoryPreferences())
            val api = OpenRouterClient(settings)
            // Replace only transport: no real provider is contacted, even on a regression.
            OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
            for ((provider, base) in listOf(AiProvider.OPENROUTER to "https://openrouter.ai/api/v1", AiProvider.ORCAROUTER to "https://api.orcarouter.ai/v1")) {
                settings.setProvider(provider)
                settings.saveProviderConfig(AiConnectionConfig(provider, "fake-${provider.name}", "Vendor/Exact:free"))
                server.enqueue(MockResponse().setBody(response))
                assertEquals("OK", api.chatSimple("Reply OK"))
                assertEquals("$base/chat/completions", seen.last())
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("Bearer fake-${provider.name}", sent.getHeader("Authorization"))
                if (provider != AiProvider.OPENROUTER) assertNull(sent.getHeader("HTTP-Referer"))
                val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                assertEquals("Vendor/Exact:free", body["model"]!!.jsonPrimitive.content)
                assertFalse(body.containsKey("tools"))
            }
        }
    }
}
