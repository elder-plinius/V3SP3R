package com.vesper.flipper.ai

import com.vesper.flipper.data.*
import com.vesper.flipper.data.database.ChatDao
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.AuditService
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentInterruptionTest {
    private class Harness(val settings: SettingsStore = SettingsStore(MemoryPreferences())) : AutoCloseable {
        val api = mockk<OpenRouterClient>(relaxed = true)
        val executor = mockk<CommandExecutor>(relaxed = true)
        val agent = VesperAgent(api, executor, mockk<AuditService>(relaxed = true), mockk<ChatDao>(relaxed = true), settings)
        override fun close() {
            (VesperAgent::class.java.getDeclaredField("persistenceScope").apply { isAccessible = true }
                .get(agent) as CoroutineScope).cancel()
        }
    }

    @Test fun `cancelled send releases loading and progress and propagates cancellation`() = runBlocking {
        Harness().use { h ->
            val entered = CompletableDeferred<Unit>()
            coEvery { h.api.chat(any(), any(), any()) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            val turn = async { h.agent.sendMessage("Fixture") }
            withTimeout(5000) { entered.await() }
            assertTrue(h.agent.conversationState.value.isLoading)
            turn.cancelAndJoin()
            assertTrue(turn.isCancelled)
            val state = h.agent.conversationState.value
            assertFalse("Cancelled request must release loading", state.isLoading)
            assertNull(state.progress)
            assertTrue(state.error.orEmpty().contains("interrupt", ignoreCase = true))
            assertEquals(listOf("Fixture"), state.messages.map { it.content })
        }
    }

    @Test fun `truncated text remains visible but is never marked complete`() = runBlocking {
        Harness().use { h ->
            coEvery { h.api.chat(any(), any(), any()) } returns
                ChatCompletionResult.Error("Output limit: incomplete reply", "An incomple")
            val state = h.agent.sendMessage("Fixture")
            assertFalse(state.isLoading)
            assertEquals("An incomple", state.messages.last().content)
            assertEquals(MessageStatus.ERROR, state.messages.last().status)
            assertTrue(state.error.orEmpty().contains("limit"))
            coVerify(exactly = 0) { h.executor.execute(any(), any()) }
        }
    }

    @Test fun `cancelled retry releases loading without automatic retry`() = runBlocking {
        Harness().use { h ->
            coEvery { h.api.chat(any(), any(), any()) } returns ChatCompletionResult.Error("Fixture failure")
            h.agent.sendMessage("Fixture")
            val entered = CompletableDeferred<Unit>()
            coEvery { h.api.chat(any(), any(), any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
            val retry = async { h.agent.retryLastMessage() }
            withTimeout(5000) { entered.await() }
            retry.cancelAndJoin()
            assertFalse(h.agent.conversationState.value.isLoading)
            assertNull(h.agent.conversationState.value.progress)
            coVerify(exactly = 2) { h.api.chat(any(), any(), any()) }
        }
    }

    @Test fun `old cancellation does not change a newly started session`() = runBlocking {
        Harness().use { h ->
            val entered = CompletableDeferred<Unit>()
            coEvery { h.api.chat(any(), any(), any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
            val turn = async { h.agent.sendMessage("Old fixture") }
            withTimeout(5000) { entered.await() }
            h.agent.startNewSession()
            val fresh = h.agent.conversationState.value
            turn.cancelAndJoin()
            assertEquals(fresh, h.agent.conversationState.value)
        }
    }

    @Test fun `old config load cannot adopt newer request ownership when cancelled`() = runBlocking {
        val oldConfigEntered = CompletableDeferred<Unit>()
        val releaseOldConfig = CompletableDeferred<Unit>()
        val oldChatEntered = CompletableDeferred<Unit>()
        val newChatEntered = CompletableDeferred<Unit>()
        val releaseNewChat = CompletableDeferred<Unit>()
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "fake-old-key", "Old/Exact")
        val newer = AiConnectionConfig(AiProvider.ORCAROUTER, "fake-new-key", "New/Exact")
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.lastChatSessionId } returns kotlinx.coroutines.flow.flowOf(null)
        every { settings.aiMaxIterations } returns kotlinx.coroutines.flow.flowOf(SettingsStore.DEFAULT_AI_MAX_ITERATIONS)
        var configReads = 0
        every { settings.aiConnectionConfig } returns kotlinx.coroutines.flow.flow {
            if (++configReads == 1) {
                oldConfigEntered.complete(Unit)
                releaseOldConfig.await()
                emit(original)
            } else emit(newer)
        }
        Harness(settings).use { h ->
            val pending = mockk<PendingApproval>(relaxed = true)
            every { pending.id } returns "new-approval"
            every { h.executor.getPendingApproval("new-approval") } returns pending
            every { h.api.parseCommandDetailed(any()) } returns OpenRouterClient.ParsedCommand(
                ExecuteCommand(CommandAction.WRITE_FILE, CommandArgs(), "Fixture", "Fixture")
            )
            coEvery { h.executor.execute(any(), any()) } returns CommandResult(
                success = false, action = CommandAction.WRITE_FILE,
                requiresConfirmation = true, pendingApprovalId = "new-approval"
            )
            coEvery { h.api.chat(any(), any(), original) } coAnswers {
                oldChatEntered.complete(Unit)
                awaitCancellation()
            }
            coEvery { h.api.chat(any(), any(), newer) } coAnswers {
                newChatEntered.complete(Unit)
                releaseNewChat.await()
                ChatCompletionResult.Success("", listOf(ToolCall("new-call", "execute_command", "{}")), newer.model, null)
            }
            val oldTurn = async { h.agent.sendMessage("Old fixture") }
            withTimeout(5000) { oldConfigEntered.await() }
            val newTurn = async { h.agent.sendMessage("New fixture") }
            withTimeout(5000) { newChatEntered.await() }
            // The old request enters withTurnConnection only after the new generation exists.
            releaseOldConfig.complete(Unit)
            withTimeout(5000) { oldChatEntered.await() }
            releaseNewChat.complete(Unit)
            val awaitingApproval = withTimeout(5000) { newTurn.await() }
            assertEquals("new-approval", awaitingApproval.pendingApproval?.id)
            val retainedField = VesperAgent::class.java.getDeclaredField("pendingApprovalConnection")
                .apply { isAccessible = true }
            val retained = retainedField.get(h.agent)
            assertNotNull("New request must retain its approval credentials", retained)
            oldTurn.cancelAndJoin()
            assertTrue(oldTurn.isCancelled)
            assertEquals("Old cancellation must leave newer approval state unchanged", awaitingApproval, h.agent.conversationState.value)
            assertSame("Old cancellation must not clear newer approval credentials", retained, retainedField.get(h.agent))
        }
    }

    @Test fun `cancellation while loading provider settings also releases loading`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.lastChatSessionId } returns kotlinx.coroutines.flow.flowOf(null)
        every { settings.aiConnectionConfig } returns kotlinx.coroutines.flow.flow {
            entered.complete(Unit)
            awaitCancellation()
        }
        Harness(settings).use { h ->
            val turn = async { h.agent.sendMessage("Fixture") }
            withTimeout(5000) { entered.await() }
            turn.cancelAndJoin()
            assertFalse("Cancelled settings read must release loading", h.agent.conversationState.value.isLoading)
        }
    }
}
