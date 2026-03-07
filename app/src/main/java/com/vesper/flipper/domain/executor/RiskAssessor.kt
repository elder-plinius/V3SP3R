package com.vesper.flipper.domain.executor

import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.PermissionService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assesses the risk level of commands.
 * Android always computes the real risk, ignoring any AI assessment.
 *
 * Risk classes:
 * - LOW: list, read → auto-execute
 * - MEDIUM: write inside project scope → diff + apply
 * - HIGH: delete, move, overwrite, mass ops → confirmation popup
 * - BLOCKED: protected paths → settings unlock required
 */
@Singleton
class RiskAssessor @Inject constructor(
    private val permissionService: PermissionService
) {

    /**
     * Assess the risk of a command.
     * This is the authoritative risk calculation.
     */
    fun assess(command: ExecuteCommand): RiskAssessment {
        val paths = extractPaths(command)

        // Check for blocked paths first
        val blockedPath = paths.find { ProtectedPaths.isProtected(it) }
        if (blockedPath != null && !isUnlockedInSettings(blockedPath)) {
            return RiskAssessment(
                level = RiskLevel.BLOCKED,
                reason = "Protected path",
                affectedPaths = paths,
                requiresDiff = false,
                requiresConfirmation = false,
                blockedReason = getBlockedReason(blockedPath)
            )
        }

        // Assess based on action type
        return when (command.action) {
            // LOW risk: read-only operations
            CommandAction.LIST_DIRECTORY,
            CommandAction.READ_FILE,
            CommandAction.GET_DEVICE_INFO,
            CommandAction.GET_STORAGE_INFO,
            CommandAction.SEARCH_FAPHUB -> {
                RiskAssessment(
                    level = RiskLevel.LOW,
                    reason = "Read-only operation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = false
                )
            }

            // MEDIUM risk: write operations in scope
            CommandAction.WRITE_FILE -> {
                val path = command.args.path ?: ""
                if (permissionService.hasPermission(path, CommandAction.WRITE_FILE)) {
                    RiskAssessment(
                        level = RiskLevel.MEDIUM,
                        reason = "File modification",
                        affectedPaths = paths,
                        requiresDiff = true,
                        requiresConfirmation = false
                    )
                } else {
                    RiskAssessment(
                        level = RiskLevel.HIGH,
                        reason = "Write outside permitted scope",
                        affectedPaths = paths,
                        requiresDiff = true,
                        requiresConfirmation = true
                    )
                }
            }

            CommandAction.CREATE_DIRECTORY -> {
                val path = command.args.path ?: ""
                if (permissionService.hasPermission(path, CommandAction.CREATE_DIRECTORY)) {
                    RiskAssessment(
                        level = RiskLevel.LOW,
                        reason = "Directory creation in scope",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = false
                    )
                } else {
                    RiskAssessment(
                        level = RiskLevel.MEDIUM,
                        reason = "Directory creation outside scope",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                }
            }

            // HIGH risk: destructive operations
            CommandAction.DELETE -> {
                val recursive = command.args.recursive
                RiskAssessment(
                    level = RiskLevel.HIGH,
                    reason = if (recursive) "Recursive deletion" else "File deletion",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.MOVE,
            CommandAction.RENAME -> {
                RiskAssessment(
                    level = RiskLevel.HIGH,
                    reason = "Move/rename operation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.COPY -> {
                val destPath = command.args.destinationPath ?: ""
                if (permissionService.hasPermission(destPath, CommandAction.WRITE_FILE)) {
                    RiskAssessment(
                        level = RiskLevel.MEDIUM,
                        reason = "Copy operation",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = false
                    )
                } else {
                    RiskAssessment(
                        level = RiskLevel.HIGH,
                        reason = "Copy to unscoped destination",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                }
            }

            CommandAction.PUSH_ARTIFACT -> {
                val path = command.args.path ?: ""
                val artifactType = command.args.artifactType ?: "unknown"

                // Executables and apps are HIGH risk
                if (artifactType in listOf("fap", "app", "executable")) {
                    RiskAssessment(
                        level = RiskLevel.HIGH,
                        reason = "Pushing executable artifact",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                } else {
                    RiskAssessment(
                        level = RiskLevel.MEDIUM,
                        reason = "Pushing artifact",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                }
            }

            CommandAction.INSTALL_FAPHUB_APP -> {
                RiskAssessment(
                    level = RiskLevel.HIGH,
                    reason = "Download and install executable app artifact",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // LOW risk: read-only new actions
            CommandAction.SEARCH_RESOURCES,
            CommandAction.LIST_VAULT -> {
                RiskAssessment(
                    level = RiskLevel.LOW,
                    reason = "Read-only catalog/inventory query",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = false
                )
            }

            // MEDIUM risk: AI forge generates content (doesn't write to Flipper yet)
            CommandAction.FORGE_PAYLOAD -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "AI payload generation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // MEDIUM risk: runbooks execute read-only diagnostic sequences
            CommandAction.RUN_RUNBOOK -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Diagnostic runbook execution",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.EXECUTE_CLI -> {
                val cliCommand = (command.args.command ?: command.args.content).orEmpty()
                if (isLowRiskCli(cliCommand)) {
                    RiskAssessment(
                        level = RiskLevel.LOW,
                        reason = "Read-only CLI command",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = false
                    )
                } else {
                    RiskAssessment(
                        level = RiskLevel.HIGH,
                        reason = "Potentially destructive CLI command",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                }
            }
        }
    }

    /**
     * Check if an operation is considered a mass operation
     */
    fun isMassOperation(command: ExecuteCommand): Boolean {
        return when (command.action) {
            CommandAction.DELETE -> command.args.recursive
            CommandAction.EXECUTE_CLI -> isMassCliOperation(command.args.command ?: command.args.content.orEmpty())
            else -> false
        }
    }

    /**
     * Extract all paths affected by a command
     */
    private fun extractPaths(command: ExecuteCommand): List<String> {
        val paths = mutableListOf<String>()
        command.args.path?.let { paths.add(it) }
        command.args.destinationPath?.let { paths.add(it) }
        if (command.action == CommandAction.EXECUTE_CLI) {
            val cliCommand = command.args.command ?: command.args.content.orEmpty()
            cliCommand.split(Regex("\\s+"))
                .filter { it.startsWith("/") }
                .forEach { paths.add(it) }
        }
        return paths
    }

    private fun isUnlockedInSettings(path: String): Boolean {
        // Check if user has explicitly unlocked this protected path
        return permissionService.isProtectedPathUnlocked(path)
    }

    private fun getBlockedReason(path: String): String {
        return when {
            ProtectedPaths.isSystemPath(path) -> "System path requires settings unlock"
            ProtectedPaths.isFirmwarePath(path) -> "Firmware path requires settings unlock"
            ProtectedPaths.SENSITIVE_EXTENSIONS.any { path.endsWith(it) } ->
                "Sensitive file type requires settings unlock"
            else -> "Protected path requires settings unlock"
        }
    }

    private fun isLowRiskCli(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return false
        return SAFE_CLI_PREFIXES.any { normalized.startsWith(it) }
    }

    private fun isMassCliOperation(command: String): Boolean {
        val normalized = command.trim().lowercase()
        return normalized.contains("remove_recursive") ||
                normalized.startsWith("storage format") ||
                normalized.contains(" rm ") ||
                normalized.startsWith("rm ")
    }

    companion object {
        private val SAFE_CLI_PREFIXES = listOf(
            "help",
            "version",
            "device_info",
            "device info",
            "info",
            "storage list",
            "storage ls",
            "storage read",
            "storage cat",
            "storage info",
            "storage stat"
        )
    }
}
