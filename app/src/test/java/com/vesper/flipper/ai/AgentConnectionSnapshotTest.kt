package com.vesper.flipper.ai

import com.vesper.flipper.data.*
import com.vesper.flipper.data.database.ChatDao
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.ForgeEngine
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.AuditService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class AgentConnectionSnapshotTest {
    private val original = AiConnectionConfig(AiProvider.OPENROUTER, "fake-original-key", "Original/Exact:free")
    private val switched = AiConnectionConfig(AiProvider.ORCAROUTER, "fake-switched-key", "Switched/Exact")

    private inner class Harness : AutoCloseable {
        val settings = SettingsStore(MemoryPreferences())
        val api = OpenRouterClient(settings)
        val executor = mockk<CommandExecutor>(relaxed = true)
        val agent = VesperAgent(api, executor, mockk<AuditService>(relaxed = true), mockk<ChatDao>(relaxed = true), settings)
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        var firstAction = "request_photo"
        init {
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val body = if (requests.size == 1) {
                    """{"id":"test","model":"Original/Exact:free","choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"execute_command","arguments":{"action":"$firstAction","args":{"prompt":"Describe test fixture","path":"/ext/test.txt","content":"test"}}}}]}}]}"""
                } else {
                    """{"id":"test","model":"test","choices":[{"message":{"role":"assistant","content":"Fixture response"}}]}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            OpenRouterClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, transport)
        }
        suspend fun select(config: AiConnectionConfig) {
            settings.saveProviderConfig(config)
            settings.setProvider(config.provider)
        }
        fun assertRequest(index: Int, config: AiConnectionConfig) {
            val request = requests[index]
            assertEquals("request $index endpoint", "${config.provider.baseUrl}/chat/completions", request.url.toString())
            assertEquals("request $index key", "Bearer ${config.apiKey}", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("request $index model", config.model, Json.parseToJsonElement(buffer.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
        }
        fun assertRetained(retained: Boolean) {
            val value = VesperAgent::class.java.getDeclaredField("pendingApprovalConnection")
                .apply { isAccessible = true }.get(agent)
            assertEquals("Snapshot retained only while awaiting approval", retained, value != null)
        }
        override fun close() {
            val scope = VesperAgent::class.java.getDeclaredField("persistenceScope").apply { isAccessible = true }.get(agent) as CoroutineScope
            scope.cancel()
        }
    }

    private fun Harness.requireApproval() {
        firstAction = "write_file"
        val pending = mockk<PendingApproval>(relaxed = true)
        every { pending.id } returns "approval-1"
        every { executor.getPendingApproval("approval-1") } returns pending
        coEvery { executor.execute(any(), any()) } returns CommandResult(
            success = false, action = CommandAction.WRITE_FILE,
            requiresConfirmation = true, pendingApprovalId = "approval-1"
        )
        coEvery { executor.approve("approval-1", any()) } coAnswers {
            // Approved execution can also perform nested AI work.
            api.chatSimple("Approved nested work")
            CommandResult(success = true, action = CommandAction.WRITE_FILE)
        }
        coEvery { executor.reject("approval-1", any()) } returns CommandResult(
            success = false, action = CommandAction.WRITE_FILE, error = "Rejected by user"
        )
    }

    @Test fun `approval resumes original snapshot including approved nested work then releases it`() = runBlocking {
        Harness().use { h ->
            h.requireApproval()
            h.select(original)
            assertEquals("approval-1", h.agent.sendMessage("Write fixture").pendingApproval?.id)
            h.assertRetained(true)
            h.select(switched)
            assertNull(h.agent.continueAfterApproval("approval-1", true).error)
            h.assertRetained(false)
            assertEquals(3, h.requests.size)
            repeat(3) { h.assertRequest(it, original) }
            assertNull(h.agent.sendMessage("New turn").error)
            h.assertRequest(3, switched)
            coVerify(exactly = 1) { h.executor.approve("approval-1", any()) }
        }
    }

    @Test fun `rejection continuation retains snapshot until final model response`() = runBlocking {
        Harness().use { h ->
            h.requireApproval()
            h.select(original)
            assertNotNull(h.agent.sendMessage("Write fixture").pendingApproval)
            h.select(switched)
            assertNull(h.agent.continueAfterApproval("approval-1", false).error)
            h.assertRetained(false)
            assertEquals(2, h.requests.size)
            h.assertRequest(1, original)
            assertNull(h.agent.sendMessage("New turn").error)
            h.assertRequest(2, switched)
            coVerify(exactly = 0) { h.executor.approve(any(), any()) }
            coVerify(exactly = 1) { h.executor.reject("approval-1", any()) }
        }
    }

    @Test fun `new session discards paused snapshot`() = runBlocking {
        Harness().use { h ->
            h.requireApproval()
            h.select(original)
            assertNotNull(h.agent.sendMessage("Write fixture").pendingApproval)
            h.select(switched)
            h.agent.startNewSession()
            h.assertRetained(false)
            assertNull(h.agent.sendMessage("Fresh session").error)
            h.assertRequest(1, switched)
        }
    }

    @Test fun `cancelled approval resume releases snapshot for next turn`() = runBlocking {
        Harness().use { h ->
            h.requireApproval()
            h.select(original)
            assertNotNull(h.agent.sendMessage("Write fixture").pendingApproval)
            val entered = CompletableDeferred<Unit>()
            coEvery { h.executor.approve(any(), any()) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            val resume = launch { h.agent.continueAfterApproval("approval-1", true) }
            entered.await()
            resume.cancelAndJoin()
            h.assertRetained(false)
            h.select(switched)
            assertNull(h.agent.sendMessage("After cancel").error)
            h.assertRequest(1, switched)
        }
    }

    @Test fun `nested photo analysis keeps turn snapshot while standalone calls stay fresh`() = runBlocking {
        Harness().use { h ->
            h.select(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.agent.setPhotoCaptureCallback { prompt, _ ->
                h.select(switched)
                entered.complete(Unit)
                release.await()
                h.api.describeImageForAgent(ImageAttachment(base64Data = "ZmFrZQ=="), prompt)
            }
            val turn = async { h.agent.sendMessage("Inspect fixture") }
            entered.await()
            // A concurrent operation outside the agent turn must not inherit its credentials.
            assertEquals("Fixture response", h.api.chatSimple("Independent request"))
            release.complete(Unit)
            assertNull(turn.await().error)
            assertEquals(4, h.requests.size)
            h.assertRequest(0, original)
            h.assertRequest(1, switched)
            h.assertRequest(2, original)
            h.assertRequest(3, original)
            assertEquals("Fixture response", h.api.describeImageForAgent(ImageAttachment(base64Data = "ZmFrZQ=="), "Standalone"))
            h.assertRequest(4, switched)
        }
    }

    @Test fun `nested forge generation keeps original provider key and exact model`() = runBlocking {
        Harness().use { h ->
            h.firstAction = "forge_payload"
            h.select(original)
            coEvery { h.executor.execute(any(), any()) } coAnswers {
                h.select(switched)
                ForgeEngine(h.api).forge("Harmless fixture", "INFRARED")
                CommandResult(success = true, action = CommandAction.FORGE_PAYLOAD)
            }
            assertNull(h.agent.sendMessage("Generate fixture").error)
            assertEquals(3, h.requests.size)
            repeat(3) { h.assertRequest(it, original) }
            assertEquals("Fixture response", h.api.chatSimple("Standalone"))
            h.assertRequest(3, switched)
        }
    }
}
