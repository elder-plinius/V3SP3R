package com.vesper.flipper.domain

import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.ForgeEngine
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.AuditActionType
import com.vesper.flipper.domain.model.AuditEntry
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.CommandArgs
import com.vesper.flipper.domain.model.ExecuteCommand
import com.vesper.flipper.domain.model.RiskAssessment
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.service.AuditService
import com.vesper.flipper.domain.service.DiffService
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class CommandExecutorTest {

    private lateinit var fileSystem: FlipperFileSystem
    private lateinit var riskAssessor: RiskAssessor
    private lateinit var permissionService: PermissionService
    private lateinit var auditService: AuditService
    private lateinit var diffService: DiffService
    private lateinit var forgeEngine: ForgeEngine
    private lateinit var settingsStore: SettingsStore
    private lateinit var commandExecutor: CommandExecutor

    @Before
    fun setup() {
        fileSystem = mock()
        riskAssessor = mock()
        permissionService = mock()
        auditService = mock()
        diffService = mock()
        forgeEngine = mock()
        settingsStore = mock()
        whenever(settingsStore.autoApproveMedium).thenReturn(flowOf(false))
        whenever(settingsStore.autoApproveHigh).thenReturn(flowOf(false))
        commandExecutor = CommandExecutor(
            fileSystem = fileSystem,
            riskAssessor = riskAssessor,
            permissionService = permissionService,
            auditService = auditService,
            diffService = diffService,
            forgeEngine = forgeEngine,
            settingsStore = settingsStore
        )
    }

    @Test
    fun `medium risk requiring confirmation returns pending approval and does not execute immediately`() = runBlocking {
        val command = ExecuteCommand(
            action = CommandAction.CREATE_DIRECTORY,
            args = CommandArgs(path = "/ext/apps_data/new_folder"),
            justification = "Create folder",
            expectedEffect = "Folder created"
        )
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.MEDIUM,
                reason = "Directory creation outside scope",
                affectedPaths = listOf("/ext/apps_data/new_folder"),
                requiresDiff = false,
                requiresConfirmation = true
            )
        )

        val result = commandExecutor.execute(command, "session-1")

        assertTrue(result.requiresConfirmation)
        assertNotNull(result.pendingApprovalId)
        assertNotNull(result.pendingApprovalId?.let { commandExecutor.getPendingApproval(it) })
        verifyNoInteractions(fileSystem)
    }

    @Test
    fun `medium risk without confirmation executes directly and logs medium risk`() = runBlocking {
        val command = ExecuteCommand(
            action = CommandAction.CREATE_DIRECTORY,
            args = CommandArgs(path = "/ext/tools"),
            justification = "Create tools directory",
            expectedEffect = "Directory exists"
        )
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.MEDIUM,
                reason = "Scoped directory operation",
                affectedPaths = listOf("/ext/tools"),
                requiresDiff = false,
                requiresConfirmation = false
            )
        )
        whenever(fileSystem.createDirectory("/ext/tools")).thenReturn(Result.success(Unit))

        val result = commandExecutor.execute(command, "session-2")

        assertTrue(result.success)
        assertFalse(result.requiresConfirmation)

        val entryCaptor = argumentCaptor<AuditEntry>()
        verify(auditService, atLeastOnce()).log(entryCaptor.capture())
        val executedEntry = entryCaptor.allValues.last { it.actionType == AuditActionType.COMMAND_EXECUTED }
        assertEquals(RiskLevel.MEDIUM, executedEntry.riskLevel)
    }

    @Test
    fun `approve missing approval returns timeout error and logs timeout`() = runBlocking {
        val result = commandExecutor.approve("missing-approval", "session-3")

        assertFalse(result.success)
        assertTrue(result.error?.contains("not found or expired", ignoreCase = true) == true)

        val entryCaptor = argumentCaptor<AuditEntry>()
        verify(auditService, atLeastOnce()).log(entryCaptor.capture())
        assertTrue(entryCaptor.allValues.any { it.actionType == AuditActionType.APPROVAL_TIMEOUT })
    }

    @Test
    fun `approve execution failure logs command failed`() = runBlocking {
        val command = ExecuteCommand(
            action = CommandAction.DELETE,
            args = CommandArgs(path = "/ext/test.txt"),
            justification = "Remove temp file",
            expectedEffect = "File removed"
        )
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.HIGH,
                reason = "File deletion",
                affectedPaths = listOf("/ext/test.txt"),
                requiresDiff = false,
                requiresConfirmation = true
            )
        )
        whenever(fileSystem.delete("/ext/test.txt", false))
            .thenReturn(Result.failure(IllegalStateException("delete failed")))

        val pending = commandExecutor.execute(command, "session-4")
        val approvalId = pending.pendingApprovalId ?: error("Expected pending approval id")
        val approved = commandExecutor.approve(approvalId, "session-4")

        assertFalse(approved.success)
        assertTrue(approved.error?.contains("delete failed", ignoreCase = true) == true)

        val entryCaptor = argumentCaptor<AuditEntry>()
        verify(auditService, atLeastOnce()).log(entryCaptor.capture())
        assertTrue(entryCaptor.allValues.any { it.actionType == AuditActionType.COMMAND_FAILED })
    }

    @Test
    fun `search faphub executes directly and returns matches`() = runBlocking {
        val command = ExecuteCommand(
            action = CommandAction.SEARCH_FAPHUB,
            args = CommandArgs(command = "wifi"),
            justification = "Find app",
            expectedEffect = "Return catalog matches"
        )
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.LOW,
                reason = "Read-only operation",
                affectedPaths = emptyList(),
                requiresDiff = false,
                requiresConfirmation = false
            )
        )

        val result = commandExecutor.execute(command, "session-5")

        assertTrue(result.success)
        assertFalse(result.requiresConfirmation)
        assertTrue(result.data?.content?.contains("FapHub matches", ignoreCase = true) == true)
        verifyNoInteractions(fileSystem)
    }

    @Test
    fun `search resources returns results`() = runBlocking {
        val command = ExecuteCommand(
            action = CommandAction.SEARCH_RESOURCES,
            args = CommandArgs(command = "Samsung", resourceType = "IR_REMOTE"),
            justification = "Find IR remote repos",
            expectedEffect = "Return matching resource repos"
        )
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.LOW,
                reason = "Read-only catalog query",
                affectedPaths = emptyList(),
                requiresDiff = false,
                requiresConfirmation = false
            )
        )

        val result = commandExecutor.execute(command, "session-6")

        assertTrue(result.success)
        assertTrue(result.data?.content?.contains("Flipper-IRDB", ignoreCase = true) == true)
    }
}
