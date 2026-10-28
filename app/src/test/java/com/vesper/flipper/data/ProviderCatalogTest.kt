package com.vesper.flipper.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {
    @Test fun `catalog uses the named provider credentials and preserves all exact IDs`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val catalog = OpenRouterModelCatalog()
            val field = OpenRouterModelCatalog::class.java.getDeclaredField("client").apply { isAccessible = true }
            val urls = mutableListOf<String>()
            field.set(catalog, (field.get(catalog) as OkHttpClient).newBuilder().addInterceptor { chain ->
                urls.add(chain.request().url.toString())
                chain.proceed(chain.request().newBuilder().url(server.url("/models")).build())
            }.build())
            for (provider in AiProvider.entries) {
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"Vendor/Exact:free","name":"Readable"},{"id":"abliterated-model-large-v2"},{"id":" malformed "}]}"""))
                val models = catalog.fetchModels(AiConnectionConfig(provider, "fake-${provider.name}", "")).getOrThrow()
                assertEquals(listOf("Vendor/Exact:free", "abliterated-model-large-v2"), models.map { it.id })
                assertEquals("${provider.baseUrl}/models", urls.last())
                assertEquals("Bearer fake-${provider.name}", server.takeRequest().getHeader("Authorization"))
                server.enqueue(MockResponse().setBody("""{"data":[]}"""))
                assertTrue(catalog.fetchModels(AiConnectionConfig(provider, "fake-${provider.name}", "")).getOrThrow().isEmpty())
                server.takeRequest()
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("fake-secret-reflection"))
            val failure = catalog.fetchModels(AiConnectionConfig(AiProvider.ABLITERATED, "fake-secret-reflection", "")).exceptionOrNull()!!
            assertFalse(failure.message!!.contains("fake-secret-reflection"))
            assertTrue(failure.message!!.contains("401"))
        }
    }
}
