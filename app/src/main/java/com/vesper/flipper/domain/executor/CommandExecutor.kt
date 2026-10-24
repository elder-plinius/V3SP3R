package com.vesper.flipper.domain.executor

import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.AuditService
import com.vesper.flipper.domain.service.DiffService
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Central command executor that processes all AI agent commands.
 * Enforces risk assessment, permissions, and audit logging.
 *
 * Key principle: The model issues commands, Android decides what executes.
 */
@Singleton
class CommandExecutor @Inject constructor(
    private val fileSystem: FlipperFileSystem,
    private val riskAssessor: RiskAssessor,
    private val permissionService: PermissionService,
    private val auditService: AuditService,
    private val diffService: DiffService,
    private val forgeEngine: ForgeEngine,
    private val settingsStore: SettingsStore
) {

    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    private val _currentApproval = MutableStateFlow<PendingApproval?>(null)
    val currentApproval: StateFlow<PendingApproval?> = _currentApproval.asStateFlow()

    /**
     * Execute a command from the AI agent.
     * Returns immediately for safe operations or requests approval for risky ones.
     */
    suspend fun execute(command: ExecuteCommand, sessionId: String): CommandResult {
        val startTime = System.currentTimeMillis()
        val traceId = UUID.randomUUID().toString()
        clearExpiredApprovals()

        // Log command receipt
        auditService.log(
            AuditEntry(
                actionType = AuditActionType.COMMAND_RECEIVED,
                command = command,
                sessionId = sessionId,
                metadata = mapOf("trace_id" to traceId)
            )
        )

        // Assess risk (Android decides, not the model)
        val riskAssessment = riskAssessor.assess(command)

        return when (riskAssessment.level) {
            RiskLevel.LOW -> executeDirectly(
                command = command,
                sessionId = sessionId,
                startTime = startTime,
                riskLevel = riskAssessment.level,
                traceId = traceId
            )

            RiskLevel.MEDIUM -> {
                // "Auto-approve medium" is no longer a blanket switch: it only
                // covers project-scoped writes (see AutoApprovePolicy).
                val autoApprove = AutoApprovePolicy.shouldAutoApprove(
                    command = command,
                    assessment = riskAssessment,
                    autoApproveMedium = settingsStore.autoApproveMedium.first(),
                    autoApproveHigh = false,
                    defaultProjectPath = settingsStore.defaultProjectPath.first()
                )
                if (autoApprove) {
                    executeDirectly(
                        command = command,
                        sessionId = sessionId,
                        startTime = startTime,
                        riskLevel = riskAssessment.level,
                        traceId = traceId
                    )
                } else {
                    requestApproval(
                        command = command,
                        riskAssessment = riskAssessment,
                        sessionId = sessionId,
                        startTime = startTime,
                        traceId = traceId
                    )
                }
            }

            RiskLevel.HIGH -> {
                val autoApprove = AutoApprovePolicy.shouldAutoApprove(
                    command = command,
                    assessment = riskAssessment,
                    autoApproveMedium = false,
                    autoApproveHigh = settingsStore.autoApproveHigh.first(),
                    defaultProjectPath = settingsStore.defaultProjectPath.first()
                )
                if (autoApprove) {
                    executeDirectly(
                        command = command,
                        sessionId = sessionId,
                        startTime = startTime,
                        riskLevel = riskAssessment.level,
                        traceId = traceId
                    )
                } else {
                    requestApproval(
                        command = command,
                        riskAssessment = riskAssessment,
                        sessionId = sessionId,
                        startTime = startTime,
                        traceId = traceId
                    )
                }
            }

            RiskLevel.BLOCKED -> {
                auditService.log(
                    AuditEntry(
                        actionType = AuditActionType.COMMAND_BLOCKED,
                        command = command,
                        riskLevel = RiskLevel.BLOCKED,
                        sessionId = sessionId,
                        metadata = mapOf(
                            "reason" to (riskAssessment.blockedReason ?: "Protected path"),
                            "trace_id" to traceId
                        )
                    )
                )
                CommandResult(
                    success = false,
                    action = command.action,
                    error = "Blocked: ${riskAssessment.blockedReason ?: "This path is protected"}"
                )
            }
        }
    }

    private suspend fun executeDirectly(
        command: ExecuteCommand,
        sessionId: String,
        startTime: Long,
        riskLevel: RiskLevel,
        traceId: String
    ): CommandResult {
        // Defense in depth: re-classify immediately before touching the device.
        // A Settings unlock can expire between assessment and execution, and an
        // approval captured earlier must never resurrect a blocked path.
        val finalCheck = riskAssessor.assess(command)
        if (finalCheck.level == RiskLevel.BLOCKED) {
            val blockedResult = CommandResult(
                success = false,
                action = command.action,
                error = "Blocked: ${finalCheck.blockedReason ?: "This path is protected"}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            auditService.log(
                AuditEntry(
                    actionType = AuditActionType.COMMAND_BLOCKED,
                    command = command,
                    riskLevel = RiskLevel.BLOCKED,
                    sessionId = sessionId,
                    metadata = mapOf(
                        "reason" to "reassessed_blocked",
                        "trace_id" to traceId
                    )
                )
            )
            return blockedResult
        }

        var executionTime: Long = 0
        val result = try {
            val data: CommandResultData?
            executionTime = measureTimeMillis {
                data = executeAction(command)
            }
            CommandResult(
                success = true,
                action = command.action,
                data = data,
                executionTimeMs = executionTime
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                action = command.action,
                error = "${command.action.name}: ${e.message ?: "Unknown error"}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Log execution result
        auditService.log(
            AuditEntry(
                actionType = if (result.success) AuditActionType.COMMAND_EXECUTED else AuditActionType.COMMAND_FAILED,
                command = command,
                result = result,
                riskLevel = riskLevel,
                userApproved = true,
                approvalMethod = ApprovalMethod.AUTO,
                sessionId = sessionId,
                metadata = mapOf("trace_id" to traceId)
            )
        )

        return result
    }

    private suspend fun requestApproval(
        command: ExecuteCommand,
        riskAssessment: RiskAssessment,
        sessionId: String,
        startTime: Long,
        traceId: String
    ): CommandResult {
        val diff = if (command.action == CommandAction.WRITE_FILE && command.args.path != null) {
            computeDiff(command.args.path, command.args.content ?: "")
        } else {
            null
        }

        val approval = PendingApproval(
            command = command,
            riskAssessment = riskAssessment,
            diff = diff,
            traceId = traceId
        )

        pendingApprovals[approval.id] = approval
        _currentApproval.value = approval

        auditService.log(
            AuditEntry(
                actionType = AuditActionType.APPROVAL_REQUESTED,
                command = command,
                riskLevel = riskAssessment.level,
                sessionId = sessionId,
                metadata = mapOf(
                    "approval_id" to approval.id,
                    "trace_id" to traceId
                )
            )
        )

        return CommandResult(
            success = true,
            action = command.action,
            data = CommandResultData(
                diff = diff,
                message = "Awaiting user approval for ${riskAssessment.reason}"
            ),
            requiresConfirmation = true,
            pendingApprovalId = approval.id,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Approve a pending action.
     * Called when user confirms via UI.
     */
    suspend fun approve(approvalId: String, sessionId: String): CommandResult {
        clearExpiredApprovals()
        val approval = pendingApprovals.remove(approvalId)
            ?: return CommandResult(
                success = false,
                action = CommandAction.LIST_DIRECTORY,
                error = "Approval not found or expired"
            ).also {
                auditService.log(
                    AuditEntry(
                        actionType = AuditActionType.APPROVAL_TIMEOUT,
                        sessionId = sessionId,
                        metadata = mapOf("approval_id" to approvalId)
                    )
                )
            }

        if (_currentApproval.value?.id == approvalId) {
            _currentApproval.value = null
        }

        val startTime = System.currentTimeMillis()
        val traceId = approval.traceId ?: approval.id

        // Re-assess on approval: permissions, project scope and Settings unlocks
        // may have changed while the dialog was open.
        val reAssessment = riskAssessor.assess(approval.command)
        if (reAssessment.level == RiskLevel.BLOCKED) {
            auditService.log(
                AuditEntry(
                    actionType = AuditActionType.COMMAND_BLOCKED,
                    command = approval.command,
                    riskLevel = RiskLevel.BLOCKED,
                    sessionId = sessionId,
                    metadata = mapOf(
                        "reason" to "approval_no_longer_allowed",
                        "trace_id" to traceId
                    )
                )
            )
            return CommandResult(
                success = false,
                action = approval.command.action,
                error = "Blocked: ${reAssessment.blockedReason ?: "This path is no longer unlocked"}"
            )
        }

        auditService.log(
            AuditEntry(
                actionType = AuditActionType.APPROVAL_GRANTED,
                command = approval.command,
                riskLevel = approval.riskAssessment.level,
                userApproved = true,
                approvalMethod = if (approval.riskAssessment.level == RiskLevel.HIGH) {
                    ApprovalMethod.HOLD_CONFIRM
                } else {
                    ApprovalMethod.DIFF_REVIEW
                },
                sessionId = sessionId,
                metadata = mapOf(
                    "approval_id" to approvalId,
                    "trace_id" to traceId
                )
            )
        )

        // Execute the command
        return try {
            val data = executeAction(approval.command)
            val result = CommandResult(
                success = true,
                action = approval.command.action,
                data = data,
                executionTimeMs = System.currentTimeMillis() - startTime
            )

            auditService.log(
                AuditEntry(
                    actionType = AuditActionType.COMMAND_EXECUTED,
                    command = approval.command,
                    result = result,
                    riskLevel = approval.riskAssessment.level,
                    userApproved = true,
                    sessionId = sessionId,
                    metadata = mapOf("trace_id" to traceId)
                )
            )

            result
        } catch (e: Exception) {
            CommandResult(
                success = false,
                action = approval.command.action,
                error = e.message ?: "Execution failed",
                executionTimeMs = System.currentTimeMillis() - startTime
            ).also { failed ->
                auditService.log(
                    AuditEntry(
                        actionType = AuditActionType.COMMAND_FAILED,
                        command = approval.command,
                        result = failed,
                        riskLevel = approval.riskAssessment.level,
                        userApproved = true,
                        sessionId = sessionId,
                        metadata = mapOf("trace_id" to traceId)
                    )
                )
            }
        }
    }

    /**
     * Reject a pending action.
     * Called when user cancels via UI.
     */
    suspend fun reject(approvalId: String, sessionId: String): CommandResult {
        clearExpiredApprovals()
        val approval = pendingApprovals.remove(approvalId)

        if (_currentApproval.value?.id == approvalId) {
            _currentApproval.value = null
        }

        if (approval == null) {
            return CommandResult(
                success = false,
                action = CommandAction.LIST_DIRECTORY,
                error = "Approval not found or expired"
            ).also {
                auditService.log(
                    AuditEntry(
                        actionType = AuditActionType.APPROVAL_TIMEOUT,
                        sessionId = sessionId,
                        metadata = mapOf("approval_id" to approvalId)
                    )
                )
            }
        }

        auditService.log(
            AuditEntry(
                actionType = AuditActionType.APPROVAL_DENIED,
                command = approval.command,
                riskLevel = approval.riskAssessment.level,
                userApproved = false,
                sessionId = sessionId,
                metadata = mapOf(
                    "approval_id" to approvalId,
                    "trace_id" to (approval.traceId ?: approval.id)
                )
            )
        )

        return CommandResult(
            success = false,
            action = approval.command.action,
            error = "Action rejected by user"
        )
    }

    private suspend fun executeAction(command: ExecuteCommand): CommandResultData? {
        return when (command.action) {
            CommandAction.LIST_DIRECTORY -> {
                val path = command.args.path ?: "/ext"
                val entries = fileSystem.listDirectory(path).getOrThrow()
                CommandResultData(entries = entries)
            }

            CommandAction.READ_FILE -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                val content = fileSystem.readFile(path).getOrThrow()
                CommandResultData(content = content)
            }

            CommandAction.WRITE_FILE -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                val content = command.args.content ?: throw IllegalArgumentException("Content required")
                val bytesWritten = fileSystem.writeFile(path, content).getOrThrow()
                CommandResultData(bytesWritten = bytesWritten)
            }

            CommandAction.CREATE_DIRECTORY -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                fileSystem.createDirectory(path).getOrThrow()
                CommandResultData(message = "Directory created: $path")
            }

            CommandAction.DELETE -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                fileSystem.delete(path, command.args.recursive).getOrThrow()
                CommandResultData(message = "Deleted: $path")
            }

            CommandAction.MOVE -> {
                val path = command.args.path ?: throw IllegalArgumentException("Source path required")
                val dest = command.args.destinationPath ?: throw IllegalArgumentException("Destination path required")
                fileSystem.move(path, dest).getOrThrow()
                CommandResultData(message = "Moved: $path -> $dest")
            }

            CommandAction.RENAME -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                val newName = command.args.newName ?: throw IllegalArgumentException("New name required")
                fileSystem.rename(path, newName).getOrThrow()
                CommandResultData(message = "Renamed to: $newName")
            }

            CommandAction.COPY -> {
                val path = command.args.path ?: throw IllegalArgumentException("Source path required")
                val dest = command.args.destinationPath ?: throw IllegalArgumentException("Destination path required")
                fileSystem.copy(path, dest).getOrThrow()
                CommandResultData(message = "Copied: $path -> $dest")
            }

            CommandAction.GET_DEVICE_INFO -> {
                val deviceInfo = fileSystem.getDeviceInfo().getOrThrow()
                CommandResultData(deviceInfo = deviceInfo)
            }

            CommandAction.GET_STORAGE_INFO -> {
                val storageInfo = fileSystem.getStorageInfo().getOrThrow()
                CommandResultData(storageInfo = storageInfo)
            }

            CommandAction.PUSH_ARTIFACT -> {
                val path = command.args.path ?: throw IllegalArgumentException("Path required")
                val data = command.args.artifactData ?: throw IllegalArgumentException("Artifact data required")
                val bytes = try {
                    android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid Base64 artifact data: ${e.message}")
                }
                val bytesWritten = fileSystem.writeFileBytes(path, bytes).getOrThrow()
                CommandResultData(bytesWritten = bytesWritten, message = "Artifact pushed: $path")
            }

            CommandAction.EXECUTE_CLI -> {
                val cliCommand = command.args.command
                    ?: command.args.content
                    ?: throw IllegalArgumentException("CLI command required")
                val output = fileSystem.executeCli(cliCommand).getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Executed CLI command: $cliCommand"
                )
            }

            CommandAction.SEARCH_FAPHUB -> {
                val query = command.args.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("FapHub query required")
                executeFapHubSearch(query)
            }

            CommandAction.INSTALL_FAPHUB_APP -> {
                val appIdOrName = command.args.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("FapHub app id/name required")
                executeFapHubInstall(
                    appIdOrName = appIdOrName,
                    directDownloadUrl = command.args.downloadUrl?.trim()?.takeIf { it.isNotEmpty() }
                        ?: command.args.content?.trim()?.takeIf { it.isNotEmpty() }
                )
            }

            CommandAction.FORGE_PAYLOAD -> {
                val prompt = command.args.prompt
                    ?: command.args.command
                    ?: throw IllegalArgumentException("Forge prompt required")
                val payloadType = command.args.payloadType
                executeForgePayload(prompt, payloadType)
            }

            CommandAction.SEARCH_RESOURCES -> {
                val query = command.args.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: ""
                val resourceType = command.args.resourceType
                executeSearchResources(query, resourceType)
            }

            CommandAction.BROWSE_REPO -> {
                val repoId = command.args.repoId
                    ?: command.args.command
                    ?: throw IllegalArgumentException("repo_id required (e.g. 'irdb')")
                val subPath = command.args.subPath ?: ""
                executeBrowseRepo(repoId.trim(), subPath.trim())
            }

            CommandAction.DOWNLOAD_RESOURCE -> {
                val downloadUrl = command.args.downloadUrl
                    ?: throw IllegalArgumentException("download_url required")
                val destPath = command.args.path
                    ?: throw IllegalArgumentException("destination path required")
                executeDownloadResource(downloadUrl.trim(), destPath.trim())
            }

            CommandAction.GITHUB_SEARCH -> {
                val query = command.args.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Search query required")
                val scope = command.args.searchScope?.trim()?.lowercase() ?: "code"
                executeGitHubSearch(query, scope)
            }

            CommandAction.LIST_VAULT -> {
                val filter = command.args.filter
                val path = command.args.path
                executeListVault(filter, path)
            }

            CommandAction.RUN_RUNBOOK -> {
                val runbookId = command.args.runbookId
                    ?: command.args.command
                    ?: throw IllegalArgumentException("Runbook ID required")
                executeRunbook(runbookId)
            }

            // ── Hardware control actions ─────────────────────────

            CommandAction.LAUNCH_APP -> {
                val appName = command.args.appName
                    ?: command.args.command
                    ?: throw IllegalArgumentException("App name required")
                val appArgs = command.args.appArgs ?: ""
                val cliCommand = if (appArgs.isNotBlank()) {
                    "loader open $appName $appArgs"
                } else {
                    "loader open $appName"
                }
                val output = fileSystem.executeCli(cliCommand).getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Launched app: $appName"
                )
            }

            CommandAction.SUBGHZ_TRANSMIT -> {
                val path = command.args.path
                    ?: throw IllegalArgumentException("Sub-GHz file path required (e.g. /ext/subghz/signal.sub)")
                val output = fileSystem.executeCli("subghz tx $path").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Transmitted Sub-GHz signal: $path"
                )
            }

            CommandAction.IR_TRANSMIT -> {
                val path = command.args.path
                val signalName = command.args.signalName
                if (path != null) {
                    val cmd = if (signalName != null) "ir tx $path $signalName" else "ir tx $path"
                    val output = fileSystem.executeCli(cmd).getOrThrow()
                    CommandResultData(
                        content = output,
                        message = "Transmitted IR signal from: $path${signalName?.let { " ($it)" } ?: ""}"
                    )
                } else {
                    throw IllegalArgumentException("IR file path required (e.g. /ext/infrared/remote.ir)")
                }
            }

            CommandAction.NFC_EMULATE -> {
                val path = command.args.path
                    ?: throw IllegalArgumentException("NFC file path required (e.g. /ext/nfc/card.nfc)")
                val output = fileSystem.executeCli("nfc emulate $path").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Started NFC emulation: $path"
                )
            }

            CommandAction.RFID_EMULATE -> {
                val path = command.args.path
                    ?: throw IllegalArgumentException("RFID file path required (e.g. /ext/lfrfid/tag.rfid)")
                val output = fileSystem.executeCli("rfid emulate $path").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Started RFID emulation: $path"
                )
            }

            CommandAction.IBUTTON_EMULATE -> {
                val path = command.args.path
                    ?: throw IllegalArgumentException("iButton file path required (e.g. /ext/ibutton/key.ibtn)")
                val output = fileSystem.executeCli("ibutton emulate $path").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Started iButton emulation: $path"
                )
            }

            CommandAction.BADUSB_EXECUTE -> {
                val path = command.args.path
                    ?: throw IllegalArgumentException("BadUSB script path required (e.g. /ext/badusb/script.txt)")
                val output = fileSystem.executeCli("badusb run $path").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "Executing BadUSB script: $path"
                )
            }

            CommandAction.BLE_SPAM -> {
                val appArgs = command.args.appArgs ?: command.args.command ?: ""
                val cmd = if (appArgs.isNotBlank()) "ble_spam $appArgs" else "ble_spam"
                val output = fileSystem.executeCli(cmd).getOrThrow()
                CommandResultData(
                    content = output,
                    message = if (appArgs.contains("stop", ignoreCase = true))
                        "Stopped BLE spam" else "Started BLE spam"
                )
            }

            CommandAction.LED_CONTROL -> {
                val r = command.args.red ?: 0
                val g = command.args.green ?: 0
                val b = command.args.blue ?: 0
                val output = fileSystem.executeCli("led $r $g $b").getOrThrow()
                CommandResultData(
                    content = output,
                    message = "LED set to RGB($r, $g, $b)"
                )
            }

            CommandAction.VIBRO_CONTROL -> {
                val on = command.args.enabled ?: true
                val output = fileSystem.executeCli("vibro ${if (on) "1" else "0"}").getOrThrow()
                CommandResultData(
                    content = output,
                    message = if (on) "Vibration on" else "Vibration off"
                )
            }

            // REQUEST_PHOTO is intercepted by VesperAgent before reaching here.
            // This stub exists only so the exhaustive when compiles.
            CommandAction.REQUEST_PHOTO -> {
                CommandResultData(
                    content = "request_photo is handled at the agent layer",
                    message = "Photo capture handled by agent"
                )
            }
        }
    }

    private fun executeFapHubSearch(query: String): CommandResultData {
        val matches = FapHubCatalog.searchApps(query)
        if (matches.isEmpty()) {
            return CommandResultData(
                content = "No FapHub apps found for \"$query\".",
                message = "FapHub search returned 0 results"
            )
        }

        val previewLimit = 12
        val content = buildString {
            appendLine("FapHub matches for \"$query\":")
            matches.take(previewLimit).forEachIndexed { index, app ->
                appendLine(
                    "${index + 1}. ${app.name} (id=${app.id}, category=${app.category.name.lowercase()}, author=${app.author}, version=${app.version})"
                )
            }
            if (matches.size > previewLimit) {
                append("... ${matches.size - previewLimit} more result(s)")
            }
        }.trim()

        return CommandResultData(
            content = content,
            message = "FapHub search returned ${matches.size} result(s)"
        )
    }

    private suspend fun executeFapHubInstall(
        appIdOrName: String,
        directDownloadUrl: String?
    ): CommandResultData {
        val app = resolveCatalogFapApp(appIdOrName)
        if (app == null && directDownloadUrl.isNullOrBlank()) {
            throw IllegalArgumentException(
                "FapHub app \"$appIdOrName\" was not found in catalog. " +
                        "Run search_faphub first or provide args.download_url with a direct .fap URL."
            )
        }

        val installId = sanitizeAppIdentifier(app?.id ?: appIdOrName)
        val installName = app?.name ?: appIdOrName
        val category = app?.category ?: FapCategory.MISC
        val sourceUrl = directDownloadUrl ?: app?.downloadUrl
            ?: throw IllegalArgumentException("Missing source URL for FapHub app install")

        val resolvedBinaryUrl = resolveFapBinaryUrl(
            sourceUrl = sourceUrl,
            appIdHint = installId
        )
        val download = downloadBinary(resolvedBinaryUrl)
        if (looksLikeHtml(download.contentType, download.bytes)) {
            throw IOException(
                "Resolved URL returned HTML/non-binary content instead of a .fap file. " +
                        "Provide a direct .fap URL in args.download_url."
            )
        }
        if (download.bytes.isEmpty()) {
            throw IOException("Downloaded file is empty")
        }
        if (download.bytes.size > MAX_FAP_BYTES) {
            throw IOException("Downloaded file too large (${download.bytes.size} bytes)")
        }

        val installDir = "/ext/apps/${category.name.lowercase()}"
        ensureDirectoryExists(installDir)
        val targetPath = "$installDir/$installId.fap"
        val bytesWritten = fileSystem.writeFileBytes(targetPath, download.bytes).getOrThrow()

        return CommandResultData(
            bytesWritten = bytesWritten,
            content = "installed_app=$installName\napp_id=$installId\nsource_url=$resolvedBinaryUrl\ntarget_path=$targetPath",
            message = "Installed $installName to $targetPath"
        )
    }

    private fun resolveCatalogFapApp(appIdOrName: String): FapApp? {
        val needle = appIdOrName.trim().lowercase()
        if (needle.isBlank()) return null

        return FapHubCatalog.allApps.firstOrNull { it.id.equals(needle, ignoreCase = true) }
            ?: FapHubCatalog.allApps.firstOrNull { it.name.equals(appIdOrName, ignoreCase = true) }
            ?: FapHubCatalog.allApps.firstOrNull { it.id.lowercase().contains(needle) }
            ?: FapHubCatalog.allApps.firstOrNull { it.name.lowercase().contains(needle) }
    }

    private suspend fun resolveFapBinaryUrl(sourceUrl: String, appIdHint: String): String {
        val normalizedSource = sourceUrl.trim()
        if (normalizedSource.isBlank()) {
            throw IllegalArgumentException("FapHub source URL cannot be blank")
        }

        if (normalizedSource.contains(".fap", ignoreCase = true)) {
            return normalizedSource
        }

        val html = downloadText(normalizedSource)
        val candidates = extractFapCandidates(html, normalizedSource)
        if (candidates.isEmpty()) {
            throw IllegalArgumentException(
                "No .fap download URL found at $normalizedSource. " +
                        "Provide a direct .fap URL in args.download_url."
            )
        }

        return candidates.firstOrNull { it.contains(appIdHint, ignoreCase = true) }
            ?: candidates.first()
    }

    private suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val isGitHubApi = url.contains("api.github.com")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", if (isGitHubApi) "application/vnd.github.v3+json" else "text/html,application/xhtml+xml")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while requesting $url")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty response body from $url")
            if (body.length > MAX_HTML_CHARS) {
                body.take(MAX_HTML_CHARS)
            } else {
                body
            }
        }
    }

    private suspend fun downloadBinary(url: String): DownloadedPayload = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream,*/*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while downloading $url")
            }
            val body = response.body?.bytes()
                ?: throw IOException("Empty download body from $url")
            DownloadedPayload(
                bytes = body,
                contentType = response.header("Content-Type")
            )
        }
    }

    private fun extractFapCandidates(html: String, baseUrl: String): List<String> {
        val hrefMatches = HREF_FAP_REGEX.findAll(html)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .toList()
        val absoluteMatches = ABSOLUTE_FAP_REGEX.findAll(html)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .toList()

        return (hrefMatches + absoluteMatches)
            .mapNotNull { it.trim().trim('"', '\'').takeIf { value -> value.isNotEmpty() } }
            .map { raw ->
                runCatching { URI(baseUrl).resolve(raw).toString() }.getOrElse { raw }
            }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
    }

    private suspend fun ensureDirectoryExists(path: String) {
        val createResult = fileSystem.createDirectory(path)
        if (createResult.isSuccess) return

        val errorMessage = createResult.exceptionOrNull()?.message?.lowercase().orEmpty()
        if ("already" in errorMessage && "exist" in errorMessage) return

        val exists = fileSystem.listDirectory(path).isSuccess
        if (!exists) {
            throw createResult.exceptionOrNull() ?: IOException("Failed to create directory: $path")
        }
    }

    private fun looksLikeHtml(contentType: String?, bytes: ByteArray): Boolean {
        val type = contentType.orEmpty().lowercase()
        if (type.contains("text/html") || type.contains("application/xhtml")) {
            return true
        }
        val preview = bytes
            .take(48)
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trimStart()
            .lowercase()
        return preview.startsWith("<!doctype") || preview.startsWith("<html") || preview.startsWith("<head")
    }

    private fun sanitizeAppIdentifier(value: String): String {
        val sanitized = value.lowercase()
            .replace(Regex("[^a-z0-9_\\-.]+"), "_")
            .trim('_', '.', '-')
        return sanitized.ifBlank { "downloaded_app" }
    }

    private suspend fun computeDiff(path: String, newContent: String): FileDiff {
        val originalContent = try {
            fileSystem.readFile(path).getOrNull()
        } catch (e: Exception) {
            null
        }

        return diffService.computeDiff(originalContent, newContent)
    }

    fun getPendingApproval(approvalId: String): PendingApproval? {
        clearExpiredApprovals()
        return pendingApprovals[approvalId]
    }

    fun clearExpiredApprovals() {
        val now = System.currentTimeMillis()
        pendingApprovals.entries.removeIf { it.value.expiresAt < now }
        _currentApproval.value?.let {
            if (it.expiresAt < now) {
                _currentApproval.value = null
            }
        }
    }

    private data class DownloadedPayload(
        val bytes: ByteArray,
        val contentType: String?
    )

    // ═══════════════════════════════════════════════════════
    // FORGE PAYLOAD — now powered by shared ForgeEngine
    // ═══════════════════════════════════════════════════════

    private suspend fun executeForgePayload(prompt: String, payloadType: String?): CommandResultData {
        val result = forgeEngine.forge(prompt, payloadType)
        val blueprint = result.blueprint
        val validation = result.validation

        return CommandResultData(
            content = buildString {
                appendLine("FORGE RESULT:")
                appendLine("type=${blueprint.payloadType.name}")
                appendLine("title=${blueprint.title}")
                appendLine("filename=${blueprint.flipperPath}")
                appendLine("rarity=${blueprint.rarity.name}")
                if (result.usedFallback) {
                    appendLine("note=Used offline template (AI was unavailable)")
                }
                if (validation.warnings.isNotEmpty()) {
                    appendLine("warnings=${validation.warnings.joinToString("; ")}")
                }
                if (validation.errors.isNotEmpty()) {
                    appendLine("validation_errors=${validation.errors.joinToString("; ")}")
                }
                appendLine("---PAYLOAD---")
                append(blueprint.generatedCode)
            },
            message = "Forged ${blueprint.payloadType.displayName} payload: ${blueprint.title}"
        )
    }

    // ═══════════════════════════════════════════════════════
    // SEARCH RESOURCES
    // ═══════════════════════════════════════════════════════

    private fun executeSearchResources(query: String, resourceType: String?): CommandResultData {
        val type = resourceType?.let {
            runCatching { FlipperResourceType.valueOf(it.uppercase()) }.getOrNull()
        }

        val repos = if (query.isNotEmpty()) {
            FlipperResourceLibrary.search(query).let { results ->
                if (type != null) results.filter { it.resourceType == type } else results
            }
        } else if (type != null) {
            FlipperResourceLibrary.getByType(type)
        } else {
            FlipperResourceLibrary.repositories
        }

        if (repos.isEmpty()) {
            return CommandResultData(
                content = "No resource repositories found${if (query.isNotEmpty()) " for \"$query\"" else ""}${if (type != null) " (type: ${type.name})" else ""}.",
                message = "Resource search returned 0 results"
            )
        }

        val content = buildString {
            appendLine("Flipper Resource Repositories${if (query.isNotEmpty()) " matching \"$query\"" else ""}:")
            repos.take(15).forEachIndexed { index, repo ->
                appendLine("${index + 1}. ${repo.name} by ${repo.author} (${repo.resourceType.name.lowercase()}, ${repo.stars} stars, ${repo.fileCount} files)")
                appendLine("   ${repo.description}")
                appendLine("   Tags: ${repo.tags.joinToString(", ")}")
            }
            if (repos.size > 15) {
                append("... ${repos.size - 15} more result(s)")
            }
        }.trim()

        return CommandResultData(
            content = content,
            message = "Resource search returned ${repos.size} result(s)"
        )
    }

    // ═══════════════════════════════════════════════════════
    // BROWSE REPO — list files in a GitHub repo via API
    // ═══════════════════════════════════════════════════════

    private suspend fun executeBrowseRepo(repoId: String, subPath: String): CommandResultData {
        val repo = FlipperResourceLibrary.repositories.firstOrNull {
            it.id.equals(repoId, ignoreCase = true) ||
            it.name.equals(repoId, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unknown repo \"$repoId\". Use search_resources first to find available repos. " +
            "Valid IDs: ${FlipperResourceLibrary.repositories.joinToString { it.id }}"
        )

        // Parse GitHub owner/repo from URL
        val ghPath = repo.repoUrl.removePrefix("https://github.com/").trimEnd('/')
        val parts = ghPath.split("/")
        if (parts.size < 2) throw IllegalArgumentException("Invalid GitHub URL in repo: ${repo.repoUrl}")
        val owner = parts[0]
        val repoName = parts[1]

        val apiPath = if (subPath.isNotEmpty()) {
            "https://api.github.com/repos/$owner/$repoName/contents/$subPath"
        } else {
            "https://api.github.com/repos/$owner/$repoName/contents"
        }

        val json = downloadText(apiPath)

        // Parse GitHub API JSON response (array of file entries)
        val content = buildString {
            appendLine("Repository: ${repo.name} by ${repo.author}")
            appendLine("Path: /${subPath.ifEmpty { "(root)" }}")
            appendLine("Flipper target: ${repo.resourceType.flipperDir}")
            appendLine("---")

            // Simple JSON array parsing for GitHub Contents API
            val entries = parseGitHubContentsJson(json)
            if (entries.isEmpty()) {
                appendLine("(empty directory or API rate limit reached)")
            } else {
                val dirs = entries.filter { it.type == "dir" }.sortedBy { it.name }
                val files = entries.filter { it.type == "file" }.sortedBy { it.name }

                if (dirs.isNotEmpty()) {
                    appendLine("Directories (${dirs.size}):")
                    dirs.take(50).forEach { entry ->
                        appendLine("  📁 ${entry.name}/")
                    }
                    if (dirs.size > 50) appendLine("  ... ${dirs.size - 50} more directories")
                }
                if (files.isNotEmpty()) {
                    appendLine("Files (${files.size}):")
                    files.take(80).forEach { entry ->
                        val sizeStr = if (entry.size > 0) " (${entry.size} bytes)" else ""
                        appendLine("  📄 ${entry.name}$sizeStr")
                        if (entry.downloadUrl.isNotEmpty()) {
                            appendLine("     download_url: ${entry.downloadUrl}")
                        }
                    }
                    if (files.size > 80) appendLine("  ... ${files.size - 80} more files")
                }
            }
        }.trim()

        return CommandResultData(
            content = content,
            message = "Browsed ${repo.name}/${subPath.ifEmpty { "root" }}"
        )
    }

    private data class GitHubEntry(
        val name: String,
        val type: String,
        val size: Long,
        val downloadUrl: String,
        val path: String
    )

    private fun parseGitHubContentsJson(json: String): List<GitHubEntry> {
        val entries = mutableListOf<GitHubEntry>()
        try {
            // The GitHub Contents API returns a JSON array of objects.
            // Parse using kotlinx.serialization JsonElement.
            val element = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json)

            val array = when (element) {
                is kotlinx.serialization.json.JsonArray -> element
                is kotlinx.serialization.json.JsonObject -> {
                    // Single file response — wrap in array
                    kotlinx.serialization.json.JsonArray(listOf(element))
                }
                else -> return entries
            }

            for (item in array) {
                if (item !is kotlinx.serialization.json.JsonObject) continue
                val name = item["name"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: continue
                val type = item["type"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: "file"
                val size = item["size"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                } ?: 0L
                val downloadUrl = item["download_url"]?.let {
                    if (it is kotlinx.serialization.json.JsonNull) null
                    else (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: ""
                val path = item["path"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: name

                entries.add(GitHubEntry(name, type, size, downloadUrl, path))
            }
        } catch (_: Exception) {
            // If JSON parsing fails, return empty
        }
        return entries
    }

    // ═══════════════════════════════════════════════════════
    // DOWNLOAD RESOURCE — fetch a file from a URL and write to Flipper
    // ═══════════════════════════════════════════════════════

    private suspend fun executeDownloadResource(url: String, destPath: String): CommandResultData {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IllegalArgumentException("download_url must be a valid HTTP(S) URL")
        }

        // Ensure parent directory exists
        val parentDir = destPath.substringBeforeLast("/")
        if (parentDir.isNotEmpty() && parentDir != destPath) {
            ensureDirectoryExists(parentDir)
        }

        val download = downloadBinary(url)
        if (download.bytes.isEmpty()) {
            throw IOException("Downloaded file is empty")
        }
        if (download.bytes.size > MAX_FAP_BYTES) {
            throw IOException("Downloaded file too large (${download.bytes.size} bytes, max ${MAX_FAP_BYTES})")
        }

        val bytesWritten = fileSystem.writeFileBytes(destPath, download.bytes).getOrThrow()
        val fileName = destPath.substringAfterLast("/")

        return CommandResultData(
            bytesWritten = bytesWritten,
            content = "downloaded=$fileName\nsource_url=$url\ntarget_path=$destPath\nsize=${download.bytes.size} bytes",
            message = "Downloaded $fileName to $destPath (${download.bytes.size} bytes)"
        )
    }

    // ═══════════════════════════════════════════════════════
    // GITHUB SEARCH — search GitHub for Flipper resources
    // ═══════════════════════════════════════════════════════

    /**
     * Search GitHub using the Search API. Supports three scopes:
     * - "repositories" — find repos (e.g. "flipper infrared remote")
     * - "code" — find specific files (e.g. "Samsung TV .ir extension:ir")
     * - "topics" — find repos by topic tags
     *
     * Unauthenticated rate limit: 10 requests/minute (plenty for agent use).
     * Results are Flipper-focused: we auto-append "flipper" to queries that
     * don't already mention it, to keep results relevant.
     */
    private suspend fun executeGitHubSearch(query: String, scope: String): CommandResultData {
        val resolvedScope = when (scope) {
            "repos", "repositories", "repo" -> "repositories"
            "code", "files", "file" -> "code"
            else -> "code"
        }

        // Auto-append "flipper" context if not already present
        val enrichedQuery = if (query.contains("flipper", ignoreCase = true) ||
            query.contains("flipperzero", ignoreCase = true)) {
            query
        } else {
            "$query flipper"
        }

        // Build GitHub Search API URL
        val encodedQuery = java.net.URLEncoder.encode(enrichedQuery, "UTF-8")
        val apiUrl = "https://api.github.com/search/$resolvedScope?q=$encodedQuery&per_page=15&sort=stars&order=desc"

        val json = downloadText(apiUrl)

        val content = when (resolvedScope) {
            "repositories" -> parseGitHubRepoSearchResults(json, query)
            "code" -> parseGitHubCodeSearchResults(json, query)
            else -> parseGitHubCodeSearchResults(json, query)
        }

        return CommandResultData(
            content = content,
            message = "GitHub search ($resolvedScope) returned results for \"$query\""
        )
    }

    private fun parseGitHubRepoSearchResults(json: String, query: String): String {
        return buildString {
            try {
                val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(json)
                if (root !is kotlinx.serialization.json.JsonObject) {
                    append("GitHub API returned unexpected format. May be rate-limited (10 req/min unauthenticated).")
                    return@buildString
                }

                val totalCount = root["total_count"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                } ?: 0
                val items = root["items"] as? kotlinx.serialization.json.JsonArray

                if (items == null || items.isEmpty()) {
                    append("No GitHub repositories found for \"$query\".")
                    return@buildString
                }

                appendLine("GitHub Repository Search: \"$query\" ($totalCount total)")
                appendLine("---")

                items.take(15).forEachIndexed { index, item ->
                    if (item !is kotlinx.serialization.json.JsonObject) return@forEachIndexed
                    val name = item.str("full_name") ?: return@forEachIndexed
                    val desc = item.str("description") ?: ""
                    val stars = item.num("stargazers_count") ?: 0
                    val url = item.str("html_url") ?: ""
                    val language = item.str("language") ?: ""
                    val updated = item.str("updated_at")?.take(10) ?: ""

                    appendLine("${index + 1}. $name ($stars stars)")
                    if (desc.isNotEmpty()) appendLine("   $desc")
                    appendLine("   url: $url")
                    if (language.isNotEmpty()) appendLine("   language: $language, updated: $updated")
                    appendLine()
                }

                if (totalCount > 15) {
                    append("... ${totalCount - 15} more repositories on GitHub")
                }

                appendLine()
                appendLine("TIP: Use browse_repo with the repo URL, or download_resource to fetch specific files.")
            } catch (e: Exception) {
                append("Failed to parse GitHub search results: ${e.message}")
            }
        }.trim()
    }

    private fun parseGitHubCodeSearchResults(json: String, query: String): String {
        return buildString {
            try {
                val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(json)
                if (root !is kotlinx.serialization.json.JsonObject) {
                    append("GitHub API returned unexpected format. May be rate-limited (10 req/min unauthenticated).")
                    return@buildString
                }

                val totalCount = root["total_count"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                } ?: 0
                val items = root["items"] as? kotlinx.serialization.json.JsonArray

                if (items == null || items.isEmpty()) {
                    append("No files found on GitHub for \"$query\".")
                    return@buildString
                }

                appendLine("GitHub Code Search: \"$query\" ($totalCount total)")
                appendLine("---")

                items.take(15).forEachIndexed { index, item ->
                    if (item !is kotlinx.serialization.json.JsonObject) return@forEachIndexed
                    val name = item.str("name") ?: return@forEachIndexed
                    val path = item.str("path") ?: name
                    val htmlUrl = item.str("html_url") ?: ""

                    val repo = item["repository"] as? kotlinx.serialization.json.JsonObject
                    val repoName = repo?.str("full_name") ?: "unknown"
                    val repoStars = repo?.num("stargazers_count") ?: 0

                    // Build raw download URL from html_url
                    // GitHub html: https://github.com/owner/repo/blob/branch/path
                    // Raw: https://raw.githubusercontent.com/owner/repo/branch/path
                    val downloadUrl = htmlUrl
                        .replace("github.com", "raw.githubusercontent.com")
                        .replace("/blob/", "/")

                    appendLine("${index + 1}. $name")
                    appendLine("   repo: $repoName ($repoStars stars)")
                    appendLine("   path: $path")
                    if (downloadUrl.isNotEmpty()) {
                        appendLine("   download_url: $downloadUrl")
                    }
                    appendLine()
                }

                if (totalCount > 15) {
                    append("... ${totalCount - 15} more files on GitHub")
                }

                appendLine()
                appendLine("TIP: Use download_resource with the download_url and a Flipper path to save files.")
            } catch (e: Exception) {
                append("Failed to parse GitHub code search results: ${e.message}")
            }
        }.trim()
    }

    // Helpers for parsing JsonObject fields
    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is kotlinx.serialization.json.JsonNull) return null
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private fun kotlinx.serialization.json.JsonObject.num(key: String): Int? {
        val v = this[key] ?: return null
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    }

    // ═══════════════════════════════════════════════════════
    // LIST VAULT
    // ═══════════════════════════════════════════════════════

    private suspend fun executeListVault(filter: String?, path: String?): CommandResultData {
        val scanDirs = if (path != null) {
            listOf(path)
        } else {
            listOf("/ext/subghz", "/ext/infrared", "/ext/nfc", "/ext/lfrfid", "/ext/badusb", "/ext/ibutton")
        }

        val allEntries = mutableListOf<String>()
        for (dir in scanDirs) {
            val result = fileSystem.listDirectory(dir)
            if (result.isSuccess) {
                val entries = result.getOrNull().orEmpty()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        if (filter.isNullOrEmpty()) true
                        else {
                            val type = LootClassifier.detectPayloadType(entry.name, dir)
                            type.name.equals(filter, ignoreCase = true)
                        }
                    }
                entries.forEach { entry ->
                    val type = LootClassifier.detectPayloadType(entry.name, dir)
                    val rarity = LootClassifier.classifyRarity(type, emptyMap())
                    allEntries.add("${entry.name} | type=${type.name.lowercase()} | rarity=${rarity.name.lowercase()} | size=${entry.size}B | path=${entry.path}")
                }
            }
        }

        val content = if (allEntries.isEmpty()) {
            "Vault is empty${if (filter != null) " (filter: $filter)" else ""}. No files found on Flipper."
        } else {
            buildString {
                appendLine("VAULT INVENTORY (${allEntries.size} items)${if (filter != null) " [filter: $filter]" else ""}:")
                allEntries.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. $entry")
                }
            }.trim()
        }

        return CommandResultData(
            content = content,
            message = "Vault scan found ${allEntries.size} item(s)"
        )
    }

    // ═══════════════════════════════════════════════════════
    // RUN RUNBOOK
    // ═══════════════════════════════════════════════════════

    private suspend fun executeRunbook(runbookId: String): CommandResultData {
        val steps = when (runbookId.lowercase()) {
            "link_health", "link_health_sweep" -> listOf(
                "get_device_info" to "Checking device connectivity",
                "get_storage_info" to "Checking storage health",
                "storage info /ext" to "Checking SD card status"
            )
            "input_smoke_test" -> listOf(
                "device_info" to "Reading device info",
                "info" to "Checking system status"
            )
            "recover_scan", "recover_and_scan" -> listOf(
                "storage list /ext" to "Listing root SD card",
                "storage list /ext/subghz" to "Scanning Sub-GHz files",
                "storage list /ext/infrared" to "Scanning IR files",
                "storage list /ext/nfc" to "Scanning NFC files",
                "storage list /ext/badusb" to "Scanning BadUSB scripts"
            )
            else -> throw IllegalArgumentException("Unknown runbook: $runbookId. Available: link_health, input_smoke_test, recover_scan")
        }

        val results = mutableListOf<String>()
        for ((cmd, desc) in steps) {
            results.add("[$desc]")
            try {
                if (cmd.startsWith("get_device_info")) {
                    val info = fileSystem.getDeviceInfo().getOrThrow()
                    results.add("  Device: ${info.name}, FW: ${info.firmwareVersion}, Battery: ${info.batteryLevel}%")
                } else if (cmd.startsWith("get_storage_info") || cmd == "storage info /ext") {
                    val info = fileSystem.getStorageInfo().getOrThrow()
                    results.add("  Internal: ${info.internalFree}/${info.internalTotal} free, SD: ${if (info.hasSdCard) "present" else "missing"}")
                } else if (cmd.startsWith("storage list")) {
                    val path = cmd.removePrefix("storage list").trim()
                    val entries = fileSystem.listDirectory(path).getOrThrow()
                    results.add("  Found ${entries.size} entries in $path")
                } else {
                    val output = fileSystem.executeCli(cmd).getOrThrow()
                    results.add("  $output")
                }
                results.add("  -> OK")
            } catch (e: Exception) {
                results.add("  -> FAILED: ${e.message}")
            }
        }

        return CommandResultData(
            content = results.joinToString("\n"),
            message = "Runbook '$runbookId' completed (${steps.size} steps)"
        )
    }

    companion object {
        private const val USER_AGENT = "VesperFlipper/1.0 (Android)"
        private const val MAX_FAP_BYTES = 6 * 1024 * 1024
        private const val MAX_HTML_CHARS = 256_000
        private val HREF_FAP_REGEX = Regex(
            """href\s*=\s*["']([^"']+\.fap(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        private val ABSOLUTE_FAP_REGEX = Regex(
            """(https?://[^\s"'<>]+\.fap(?:\?[^\s"'<>]*)?)""",
            RegexOption.IGNORE_CASE
        )
        private val httpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
