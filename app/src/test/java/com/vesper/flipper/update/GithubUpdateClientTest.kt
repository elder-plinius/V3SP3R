package com.vesper.flipper.update

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GithubUpdateClientTest {
    private val server = MockWebServer().apply { start() }
    private var intendedHost: String? = null
    private var intendedPath: String? = null
    private var intendedAuthorization: String? = "not-observed"
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        intendedHost = chain.request().url.host
        intendedPath = chain.request().url.encodedPath
        intendedAuthorization = chain.request().header("Authorization")
        chain.proceed(chain.request().newBuilder().url(server.url("/releases")).build())
    }.build()

    @After fun cleanup() { server.shutdown() }

    @Test fun `cancelled check cancels its HTTP call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancelled = AtomicInteger()
        val cancellableClient = client.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.incrementAndGet() }
        }).build()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { GithubUpdateClient(cancellableClient).fetch("psigho/V3SP3R") }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(1, cancelled.get())
    }

    @Test fun `redirect is not followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
        val error = runCatching { GithubUpdateClient(client).fetch("psigho/V3SP3R") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("302"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `rate limit is an error without echoing response text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("UNTRUSTED_ERROR_TEXT"))
        val error = runCatching { GithubUpdateClient(client).fetch("psigho/V3SP3R") }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("rate-limited"))
        assertFalse(error.message.orEmpty().contains("UNTRUSTED_ERROR_TEXT"))
    }

    @Test fun `incomplete paginated results cannot claim current version`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").addHeader("Link", "<https://api.github.com/repos/psigho/V3SP3R/releases?page=2>; rel=\"next\""))
        val error = runCatching { GithubUpdateClient(client).fetch("psigho/V3SP3R") }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("100 releases"))
    }

    @Test fun `invalid repository never reaches transport`() = runBlocking {
        assertTrue(runCatching { GithubUpdateClient(client).fetch("psigho/../evil") }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun `oversized response is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody(" ".repeat(1_048_577)))
        val error = runCatching { GithubUpdateClient(client).fetch("psigho/V3SP3R") }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("too large"))
    }

    @Test fun `malformed response is not reported as no releases`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"message\":\"bad\"}"))
        assertTrue(runCatching { GithubUpdateClient(client).fetch("psigho/V3SP3R") }.isFailure)
    }

    @Test fun `manual fetch uses the pinned public repository without credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        val service = GithubUpdateClient(client)
        assertEquals(0, server.requestCount)
        val result = service.fetch("psigho/V3SP3R")
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("api.github.com", intendedHost)
        assertEquals("/repos/psigho/V3SP3R/releases", intendedPath)
        assertNull(intendedAuthorization)
        assertTrue(result.releases.isEmpty())
    }
}
