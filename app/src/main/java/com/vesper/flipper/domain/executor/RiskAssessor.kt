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

        // Reject path syntax that would be resolved differently by the Flipper
        // firmware than by this classifier.
        val invalidPath = paths.firstOrNull { it.contains("..") || it.contains('\u0000') }
        if (invalidPath != null) {
            return blocked(
                paths = paths,
                reason = "Invalid path rejected",
                blockedReason = "Path traversal segments are never executed"
            )
        }

        // Protected paths win over every action classification: /int, /ext/update,
        // keys and firmware files are BLOCKED until the user unlocks them in Settings.
        val lockedPath = paths.firstOrNull {
            ProtectedPaths.isProtected(it) && !isUnlockedInSettings(it)
        }
        if (lockedPath != null) {
            return blocked(
                paths = paths,
                reason = "Protected path",
                blockedReason = ProtectedPaths.protectedReason(lockedPath)
            )
        }

        // Installing/replacing executables under /ext/apps is never auto-approved.
        if (writeTargets(command).any { ProtectedPaths.isExecutableArea(it) }) {
            return RiskAssessment(
                level = RiskLevel.HIGH,
                reason = "Modifying installed applications",
                affectedPaths = paths,
                requiresDiff = false,
                requiresConfirmation = true
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
            CommandAction.LIST_VAULT,
            CommandAction.BROWSE_REPO,
            CommandAction.GITHUB_SEARCH -> {
                RiskAssessment(
                    level = RiskLevel.LOW,
                    reason = "Read-only catalog/inventory query",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = false
                )
            }

            // MEDIUM risk: downloads file from internet to Flipper
            CommandAction.DOWNLOAD_RESOURCE -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Download remote file to Flipper storage",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
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

            // ── Hardware control actions ─────────────────────────

            // MEDIUM risk: app launching — non-destructive but affects device state
            CommandAction.LAUNCH_APP -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Launch app on Flipper",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // MEDIUM risk: signal transmission — sends RF/IR but not destructive
            CommandAction.SUBGHZ_TRANSMIT -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Sub-GHz signal transmission",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.IR_TRANSMIT -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Infrared signal transmission",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.NFC_EMULATE -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "NFC emulation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.RFID_EMULATE -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "RFID emulation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            CommandAction.IBUTTON_EMULATE -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "iButton emulation",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // HIGH risk: BadUSB executes keystrokes on a connected computer
            CommandAction.BADUSB_EXECUTE -> {
                RiskAssessment(
                    level = RiskLevel.HIGH,
                    reason = "BadUSB script execution (injects keystrokes)",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // MEDIUM risk: BLE spam is non-destructive broadcast
            CommandAction.BLE_SPAM -> {
                RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "BLE advertisement spam",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = true
                )
            }

            // LOW risk: photo request reads from glasses camera — no Flipper side-effects
            CommandAction.REQUEST_PHOTO -> {
                RiskAssessment(
                    level = RiskLevel.LOW,
                    reason = "Glasses camera capture (read-only)",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = false
                )
            }

            // LOW risk: LED and vibro are harmless hardware feedback
            CommandAction.LED_CONTROL,
            CommandAction.VIBRO_CONTROL -> {
                RiskAssessment(
                    level = RiskLevel.LOW,
                    reason = "Hardware feedback control",
                    affectedPaths = paths,
                    requiresDiff = false,
                    requiresConfirmation = false
                )
            }

            CommandAction.EXECUTE_CLI -> {
                val cliCommand = (command.args.command ?: command.args.content).orEmpty()
                when {
                    isLowRiskCli(cliCommand) -> RiskAssessment(
                        level = RiskLevel.LOW,
                        reason = "Read-only CLI command",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = false
                    )
                    isMediumRiskCli(cliCommand) -> RiskAssessment(
                        level = RiskLevel.MEDIUM,
                        reason = "Hardware control CLI command",
                        affectedPaths = paths,
                        requiresDiff = false,
                        requiresConfirmation = true
                    )
                    else -> RiskAssessment(
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
        val paths = linkedSetOf<String>()
        command.args.path?.takeIf { it.isNotBlank() }?.let(paths::add)
        command.args.destinationPath?.takeIf { it.isNotBlank() }?.let(paths::add)

        if (command.action == CommandAction.EXECUTE_CLI) {
            val cliCommand = command.args.command ?: command.args.content.orEmpty()
            cliCommand.split(Regex("\\s+"))
                .asSequence()
                .map { it.trim('"', '\'', ',') }
                .filter { it.startsWith("/") }
                .forEach(paths::add)
        }
        return paths.toList()
    }

    /**
     * Paths whose *contents* are replaced by the command. Used for the
     * executable-area floor; read-only actions intentionally return nothing.
     */
    private fun writeTargets(command: ExecuteCommand): List<String> = when (command.action) {
        CommandAction.WRITE_FILE,
        CommandAction.CREATE_DIRECTORY,
        CommandAction.PUSH_ARTIFACT,
        CommandAction.DOWNLOAD_RESOURCE -> listOfNotNull(command.args.path)

        CommandAction.COPY,
        CommandAction.MOVE,
        CommandAction.RENAME -> listOfNotNull(command.args.destinationPath, command.args.path)

        else -> emptyList()
    }

    private fun blocked(
        paths: List<String>,
        reason: String,
        blockedReason: String
    ): RiskAssessment = RiskAssessment(
        level = RiskLevel.BLOCKED,
        reason = reason,
        affectedPaths = paths,
        requiresDiff = false,
        requiresConfirmation = false,
        blockedReason = blockedReason
    )

    private fun isUnlockedInSettings(path: String): Boolean {
        // Check if user has explicitly unlocked this protected path (or its root)
        return permissionService.isProtectedPathUnlocked(path)
    }

    private fun isLowRiskCli(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return false
        return SAFE_CLI_PREFIXES.any { normalized.startsWith(it) }
    }

    private fun isMediumRiskCli(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return false
        return MEDIUM_CLI_PREFIXES.any { normalized.startsWith(it) }
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
            // Read-only diagnostics
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
            "storage stat",
            // Harmless hardware feedback
            "led ",
            "vibro "
        )

        /**
         * CLI commands that should be MEDIUM risk (user confirms once)
         * instead of HIGH risk. Non-destructive hardware operations.
         */
        private val MEDIUM_CLI_PREFIXES = listOf(
            "loader open",
            "loader list",
            "loader info",
            "subghz tx",
            "subghz tx_from_file",
            "ir tx",
            "infrared tx",
            "nfc emulate",
            "nfc emu",
            "rfid emulate",
            "rfid emu",
            "lfrfid emulate",
            "lfrfid emu",
            "ibutton emulate",
            "ibutton emu",
            "ble_spam",
            "blespam",
            "ble spam",
            "ble_scan",
            "blescan",
            "ble scan",
            // Note: badusb is HIGH risk via dedicated BADUSB_EXECUTE action.
            // But if someone uses raw CLI, we still want user confirmation (HIGH via else branch).
            // So badusb is intentionally NOT in the MEDIUM list.
        )
    }
}

/**
 * Narrow, auditable auto-approval policy.
 *
 * The historical behaviour — "auto-approve medium" running *every* MEDIUM
 * action unattended — is removed. Auto-approval now requires all of:
 *
 *  1. the matching Settings checkbox is enabled;
 *  2. the action is in the small allowlist below;
 *  3. every affected path resolves inside the configured project directory
 *     (Settings → default project path);
 *  4. no path is protected (/int, /ext/update, key material) or an installed-app
 *     directory.
 *
 * Everything else — deletes, moves out of the project, hardware actions, CLI,
 * installs, downloads, firmware writes — always requires explicit confirmation.
 */
object AutoApprovePolicy {

    /** MEDIUM actions that may run unattended once the user opts in. */
    private val MEDIUM_AUTO_ACTIONS = setOf(
        CommandAction.WRITE_FILE,
        CommandAction.CREATE_DIRECTORY
    )

    /** HIGH actions that may run unattended, and only strictly inside the project. */
    private val HIGH_AUTO_ACTIONS = setOf(
        CommandAction.WRITE_FILE,
        CommandAction.CREATE_DIRECTORY,
        CommandAction.COPY,
        CommandAction.MOVE,
        CommandAction.RENAME
    )

    fun shouldAutoApprove(
        command: ExecuteCommand,
        assessment: RiskAssessment,
        autoApproveMedium: Boolean,
        autoApproveHigh: Boolean,
        defaultProjectPath: String
    ): Boolean {
        if (assessment.level != RiskLevel.MEDIUM && assessment.level != RiskLevel.HIGH) return false

        val paths = assessment.affectedPaths
        if (paths.isEmpty()) return false
        if (!paths.all { ProtectedPaths.isInsideProject(it, defaultProjectPath) }) return false
        if (paths.any { ProtectedPaths.isProtected(it) || ProtectedPaths.isExecutableArea(it) }) return false

        return when (assessment.level) {
            RiskLevel.MEDIUM -> autoApproveMedium && command.action in MEDIUM_AUTO_ACTIONS
            RiskLevel.HIGH -> autoApproveHigh && command.action in HIGH_AUTO_ACTIONS
            else -> false
        }
    }
}
