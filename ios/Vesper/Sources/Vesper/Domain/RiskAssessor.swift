// RiskAssessor.swift
// Vesper - AI-powered Flipper Zero controller
// Risk classification engine ported from Android with exact parity

import Foundation

// MARK: - Settings Store Protocol

/// Protocol for accessing user settings that affect risk gating.
/// Concrete implementation lives in the Data layer.
protocol SettingsStoreProtocol: Sendable {
    var autoApproveMedium: Bool { get }
    var autoApproveHigh: Bool { get }
    func isProtectedPathUnlocked(_ path: String) -> Bool
    func isPathInScope(_ path: String) -> Bool
}

// MARK: - Risk Assessor

/// Assesses the risk level of commands before execution.
/// Android always computes the real risk, ignoring any AI assessment.
///
/// Risk classes:
/// - LOW: list, read -> auto-execute
/// - MEDIUM: write inside project scope -> diff + apply
/// - HIGH: delete, move, overwrite, mass ops -> confirmation popup
/// - BLOCKED: protected paths -> settings unlock required
final class RiskAssessor: Sendable {

    private let settingsStore: SettingsStoreProtocol

    init(settingsStore: SettingsStoreProtocol) {
        self.settingsStore = settingsStore
    }

    // MARK: - Public API

    /// Assess the risk of a command. This is the authoritative risk calculation.
    func assess(_ command: ExecuteCommand) -> RiskAssessment {
        let paths = extractPaths(command)

        // Check for blocked paths first
        if let blockedPath = paths.first(where: { ProtectedPaths.isProtected($0) }) {
            if !settingsStore.isProtectedPathUnlocked(blockedPath) {
                return RiskAssessment(
                    level: .blocked,
                    reason: "Protected path",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: false,
                    blockedReason: getBlockedReason(blockedPath)
                )
            }
        }

        // Assess based on action type
        switch command.action {

        // LOW risk: read-only operations
        case .listDirectory,
             .readFile,
             .getDeviceInfo,
             .getStorageInfo,
             .searchFaphub:
            return RiskAssessment(
                level: .low,
                reason: "Read-only operation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: false
            )

        // LOW risk: read-only catalog/inventory queries
        case .searchResources,
             .listVault,
             .browseRepo,
             .githubSearch:
            return RiskAssessment(
                level: .low,
                reason: "Read-only catalog/inventory query",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: false
            )

        // LOW risk: photo request reads from glasses camera - no Flipper side-effects
        case .requestPhoto:
            return RiskAssessment(
                level: .low,
                reason: "Glasses camera capture (read-only)",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: false
            )

        // LOW risk: LED and vibro are harmless hardware feedback
        case .ledControl,
             .vibroControl:
            return RiskAssessment(
                level: .low,
                reason: "Hardware feedback control",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: false
            )

        // MEDIUM risk: write operations in scope, HIGH if out of scope
        case .writeFile:
            let path = command.args.path ?? ""
            if settingsStore.isPathInScope(path) {
                return RiskAssessment(
                    level: .medium,
                    reason: "File modification",
                    affectedPaths: paths,
                    requiresDiff: true,
                    requiresConfirmation: false
                )
            } else {
                return RiskAssessment(
                    level: .high,
                    reason: "Write outside permitted scope",
                    affectedPaths: paths,
                    requiresDiff: true,
                    requiresConfirmation: true
                )
            }

        case .createDirectory:
            let path = command.args.path ?? ""
            if settingsStore.isPathInScope(path) {
                return RiskAssessment(
                    level: .medium,
                    reason: "Directory creation in scope",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: false
                )
            } else {
                return RiskAssessment(
                    level: .high,
                    reason: "Directory creation outside scope",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            }

        case .copy:
            let destPath = command.args.destinationPath ?? ""
            if settingsStore.isPathInScope(destPath) {
                return RiskAssessment(
                    level: .medium,
                    reason: "Copy operation",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: false
                )
            } else {
                return RiskAssessment(
                    level: .high,
                    reason: "Copy to unscoped destination",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            }

        // HIGH risk: destructive operations
        case .delete:
            let recursive = command.args.recursive
            return RiskAssessment(
                level: .high,
                reason: recursive ? "Recursive deletion" : "File deletion",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .move,
             .rename:
            return RiskAssessment(
                level: .high,
                reason: "Move/rename operation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .pushArtifact:
            let artifactType = command.args.artifactType ?? "unknown"
            let executableTypes = ["fap", "app", "executable"]
            if executableTypes.contains(artifactType.lowercased()) {
                return RiskAssessment(
                    level: .high,
                    reason: "Pushing executable artifact",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            } else {
                return RiskAssessment(
                    level: .medium,
                    reason: "Pushing artifact",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            }

        case .installFaphubApp:
            return RiskAssessment(
                level: .high,
                reason: "Download and install executable app artifact",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // HIGH risk: BadUSB executes keystrokes on a connected computer
        case .badusbExecute:
            return RiskAssessment(
                level: .high,
                reason: "BadUSB script execution (injects keystrokes)",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: AI forge generates content
        case .forgePayload:
            return RiskAssessment(
                level: .medium,
                reason: "AI payload generation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: downloads file from internet to Flipper
        case .downloadResource:
            return RiskAssessment(
                level: .medium,
                reason: "Download remote file to Flipper storage",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: runbooks execute read-only diagnostic sequences
        case .runRunbook:
            return RiskAssessment(
                level: .medium,
                reason: "Diagnostic runbook execution",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: app launching - non-destructive but affects device state
        case .launchApp:
            return RiskAssessment(
                level: .medium,
                reason: "Launch app on Flipper",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: signal transmission
        case .subghzTransmit:
            return RiskAssessment(
                level: .medium,
                reason: "Sub-GHz signal transmission",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .irTransmit:
            return RiskAssessment(
                level: .medium,
                reason: "Infrared signal transmission",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .nfcEmulate:
            return RiskAssessment(
                level: .medium,
                reason: "NFC emulation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .rfidEmulate:
            return RiskAssessment(
                level: .medium,
                reason: "RFID emulation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        case .ibuttonEmulate:
            return RiskAssessment(
                level: .medium,
                reason: "iButton emulation",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // MEDIUM risk: BLE spam is non-destructive broadcast
        case .bleSpam:
            return RiskAssessment(
                level: .medium,
                reason: "BLE advertisement spam",
                affectedPaths: paths,
                requiresDiff: false,
                requiresConfirmation: true
            )

        // CLI: risk depends on command content
        case .executeCli:
            let cliCommand = command.args.command ?? command.args.content ?? ""
            if isLowRiskCli(cliCommand) {
                return RiskAssessment(
                    level: .low,
                    reason: "Read-only CLI command",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: false
                )
            } else if isMediumRiskCli(cliCommand) {
                return RiskAssessment(
                    level: .medium,
                    reason: "Hardware control CLI command",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            } else {
                return RiskAssessment(
                    level: .high,
                    reason: "Potentially destructive CLI command",
                    affectedPaths: paths,
                    requiresDiff: false,
                    requiresConfirmation: true
                )
            }
        }
    }

    /// Check if an operation is considered a mass operation.
    func isMassOperation(_ command: ExecuteCommand) -> Bool {
        switch command.action {
        case .delete:
            return command.args.recursive
        case .executeCli:
            let cliCommand = command.args.command ?? command.args.content ?? ""
            return isMassCliOperation(cliCommand)
        default:
            return false
        }
    }

    // MARK: - Private Helpers

    /// Extract all paths affected by a command.
    private func extractPaths(_ command: ExecuteCommand) -> [String] {
        var paths: [String] = []
        if let path = command.args.path {
            paths.append(path)
        }
        if let destPath = command.args.destinationPath {
            paths.append(destPath)
        }
        if command.action == .executeCli {
            let cliCommand = command.args.command ?? command.args.content ?? ""
            let tokens = cliCommand.split(separator: " ")
            for token in tokens where token.hasPrefix("/") {
                paths.append(String(token))
            }
        }
        return paths
    }

    private func getBlockedReason(_ path: String) -> String {
        if ProtectedPaths.isSystemPath(path) {
            return "System path requires settings unlock"
        } else if ProtectedPaths.isFirmwarePath(path) {
            return "Firmware path requires settings unlock"
        } else if ProtectedPaths.sensitiveExtensions.contains(where: { path.hasSuffix($0) }) {
            return "Sensitive file type requires settings unlock"
        } else {
            return "Protected path requires settings unlock"
        }
    }

    private func isLowRiskCli(_ command: String) -> Bool {
        let normalized = command.trimmingCharacters(in: .whitespaces).lowercased()
        guard !normalized.isEmpty else { return false }
        return Self.safeCLIPrefixes.contains(where: { normalized.hasPrefix($0) })
    }

    private func isMediumRiskCli(_ command: String) -> Bool {
        let normalized = command.trimmingCharacters(in: .whitespaces).lowercased()
        guard !normalized.isEmpty else { return false }
        return Self.mediumCLIPrefixes.contains(where: { normalized.hasPrefix($0) })
    }

    private func isMassCliOperation(_ command: String) -> Bool {
        let normalized = command.trimmingCharacters(in: .whitespaces).lowercased()
        return normalized.contains("remove_recursive")
            || normalized.hasPrefix("storage format")
            || normalized.contains(" rm ")
            || normalized.hasPrefix("rm ")
    }

    // MARK: - CLI Prefix Tables (exact match from Android)

    private static let safeCLIPrefixes: [String] = [
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
        "led ",
        "vibro "
    ]

    private static let mediumCLIPrefixes: [String] = [
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
        "ble scan"
    ]
}
