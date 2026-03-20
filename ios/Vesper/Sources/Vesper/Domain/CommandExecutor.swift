// CommandExecutor.swift
// Vesper - AI-powered Flipper Zero controller
// Central command executor with risk gating, approval flow, and audit logging

import Foundation
import Observation

// MARK: - Flipper File System Protocol

/// Protocol for Flipper Zero file system operations.
/// Concrete implementation lives in the BLE layer.
protocol FlipperFileSystem: Sendable {
    func listDirectory(_ path: String) async throws -> [FileEntry]
    func readFile(_ path: String) async throws -> String
    func writeFile(_ path: String, content: String) async throws -> Int64
    func writeFileBytes(_ path: String, data: Data) async throws -> Int64
    func createDirectory(_ path: String) async throws
    func delete(_ path: String, recursive: Bool) async throws
    func move(_ source: String, destination: String) async throws
    func copy(_ source: String, destination: String) async throws
    func rename(_ path: String, newName: String) async throws
    func getDeviceInfo() async throws -> DeviceInfo
    func getStorageInfo() async throws -> StorageInfo
    func sendCliCommand(_ command: String) async throws -> String
}

// MARK: - Photo Capture Callback

/// Callback for glasses camera photo capture. Set by the agent layer.
typealias PhotoCaptureCallback = (String?) async -> String?

// MARK: - Command Executor

/// Central command executor that processes all AI agent commands.
/// Enforces risk assessment, permissions, and audit logging.
///
/// Key principle: The model issues commands, iOS decides what executes.
@Observable
final class CommandExecutor {

    // MARK: - Dependencies

    private let fileSystem: FlipperFileSystem
    private let riskAssessor: RiskAssessor
    private let auditService: AuditService
    private let settingsStore: SettingsStore

    // MARK: - State

    var currentApproval: PendingApproval? = nil

    /// Callback for photo capture (set by agent layer).
    var photoCaptureCallback: PhotoCaptureCallback?

    /// Pending approvals keyed by approval ID.
    private var pendingApprovals: [String: PendingApproval] = [:]

    /// Approval expiration interval (5 minutes).
    private static let approvalExpirationMs: Int64 = 5 * 60 * 1000

    // MARK: - Init

    init(
        fileSystem: FlipperFileSystem,
        riskAssessor: RiskAssessor,
        auditService: AuditService,
        settingsStore: SettingsStore
    ) {
        self.fileSystem = fileSystem
        self.riskAssessor = riskAssessor
        self.auditService = auditService
        self.settingsStore = settingsStore
    }

    // MARK: - Public API

    /// Execute a command from the AI agent.
    /// Returns immediately for safe operations or requests approval for risky ones.
    func execute(_ command: ExecuteCommand, sessionId: String) async -> CommandResult {
        let startTime = currentTimeMs()
        let traceId = UUID().uuidString
        clearExpiredApprovals()

        // Log command receipt
        auditService.log(AuditEntry(
            actionType: .commandReceived,
            command: command,
            sessionId: sessionId,
            metadata: ["trace_id": traceId]
        ))

        // Assess risk (iOS decides, not the model)
        let riskAssessment = riskAssessor.assess(command)

        switch riskAssessment.level {
        case .low:
            return await executeDirectly(
                command: command,
                sessionId: sessionId,
                startTime: startTime,
                riskLevel: riskAssessment.level,
                traceId: traceId
            )

        case .medium:
            let autoApprove = settingsStore.autoApproveMedium
            if !autoApprove && (riskAssessment.requiresDiff || riskAssessment.requiresConfirmation) {
                return await requestApproval(
                    command: command,
                    riskAssessment: riskAssessment,
                    sessionId: sessionId,
                    startTime: startTime,
                    traceId: traceId
                )
            } else {
                return await executeDirectly(
                    command: command,
                    sessionId: sessionId,
                    startTime: startTime,
                    riskLevel: riskAssessment.level,
                    traceId: traceId
                )
            }

        case .high:
            let autoApprove = settingsStore.autoApproveHigh
            if autoApprove {
                return await executeDirectly(
                    command: command,
                    sessionId: sessionId,
                    startTime: startTime,
                    riskLevel: riskAssessment.level,
                    traceId: traceId
                )
            } else {
                return await requestApproval(
                    command: command,
                    riskAssessment: riskAssessment,
                    sessionId: sessionId,
                    startTime: startTime,
                    traceId: traceId
                )
            }

        case .blocked:
            auditService.log(AuditEntry(
                actionType: .commandBlocked,
                command: command,
                riskLevel: .blocked,
                sessionId: sessionId,
                metadata: [
                    "reason": riskAssessment.blockedReason ?? "Protected path",
                    "trace_id": traceId
                ]
            ))
            return CommandResult(
                success: false,
                action: command.action,
                error: "Blocked: \(riskAssessment.blockedReason ?? "This path is protected")"
            )
        }
    }

    /// Approve a pending command and execute it.
    func approveCommand(_ approvalId: String) async -> CommandResult {
        clearExpiredApprovals()

        guard let approval = pendingApprovals.removeValue(forKey: approvalId) else {
            let sessionId = auditService.currentSessionId ?? ""
            auditService.log(AuditEntry(
                actionType: .approvalDenied,
                sessionId: sessionId,
                metadata: ["approval_id": approvalId, "reason": "not_found_or_expired"]
            ))
            return CommandResult(
                success: false,
                action: .listDirectory,
                error: "Approval not found or expired"
            )
        }

        if currentApproval?.id == approvalId {
            currentApproval = nil
        }

        let startTime = currentTimeMs()
        let sessionId = approval.sessionId

        let approvalMethod: ApprovalMethod = approval.riskAssessment.level == .high
            ? .doubleTap
            : .singleTap

        auditService.log(AuditEntry(
            actionType: .approvalGranted,
            command: approval.command,
            riskLevel: approval.riskAssessment.level,
            userApproved: true,
            approvalMethod: approvalMethod,
            sessionId: sessionId,
            metadata: ["approval_id": approvalId]
        ))

        // Execute the command
        do {
            let data = try await executeAction(approval.command)
            let result = CommandResult(
                success: true,
                action: approval.command.action,
                data: data,
                executionTimeMs: currentTimeMs() - startTime
            )

            auditService.log(AuditEntry(
                actionType: .commandExecuted,
                command: approval.command,
                result: result,
                riskLevel: approval.riskAssessment.level,
                userApproved: true,
                sessionId: sessionId
            ))

            return result
        } catch {
            let result = CommandResult(
                success: false,
                action: approval.command.action,
                error: error.localizedDescription,
                executionTimeMs: currentTimeMs() - startTime
            )

            auditService.log(AuditEntry(
                actionType: .commandFailed,
                command: approval.command,
                result: result,
                riskLevel: approval.riskAssessment.level,
                userApproved: true,
                sessionId: sessionId
            ))

            return result
        }
    }

    /// Deny a pending command.
    func denyCommand(_ approvalId: String) {
        clearExpiredApprovals()

        let approval = pendingApprovals.removeValue(forKey: approvalId)

        if currentApproval?.id == approvalId {
            currentApproval = nil
        }

        let sessionId = approval?.sessionId ?? auditService.currentSessionId ?? ""

        auditService.log(AuditEntry(
            actionType: .approvalDenied,
            command: approval?.command,
            riskLevel: approval?.riskAssessment.level,
            userApproved: false,
            sessionId: sessionId,
            metadata: ["approval_id": approvalId]
        ))
    }

    // MARK: - Direct Execution

    private func executeDirectly(
        command: ExecuteCommand,
        sessionId: String,
        startTime: Int64,
        riskLevel: RiskLevel,
        traceId: String
    ) async -> CommandResult {
        do {
            let data = try await executeAction(command)
            let result = CommandResult(
                success: true,
                action: command.action,
                data: data,
                executionTimeMs: currentTimeMs() - startTime
            )

            auditService.log(AuditEntry(
                actionType: .commandExecuted,
                command: command,
                result: result,
                riskLevel: riskLevel,
                userApproved: true,
                approvalMethod: .auto,
                sessionId: sessionId,
                metadata: ["trace_id": traceId]
            ))

            return result
        } catch {
            let result = CommandResult(
                success: false,
                action: command.action,
                error: "\(command.action.rawValue): \(error.localizedDescription)",
                executionTimeMs: currentTimeMs() - startTime
            )

            auditService.log(AuditEntry(
                actionType: .commandFailed,
                command: command,
                result: result,
                riskLevel: riskLevel,
                userApproved: true,
                approvalMethod: .auto,
                sessionId: sessionId,
                metadata: ["trace_id": traceId]
            ))

            return result
        }
    }

    // MARK: - Approval Request

    private func requestApproval(
        command: ExecuteCommand,
        riskAssessment: RiskAssessment,
        sessionId: String,
        startTime: Int64,
        traceId: String
    ) async -> CommandResult {
        // Compute diff for write operations
        var diff: FileDiff? = nil
        if command.action == .writeFile, let path = command.args.path {
            let content = command.args.content ?? ""
            let originalContent = try? await fileSystem.readFile(path)
            diff = DiffService.computeDiff(original: originalContent, new: content)
        }

        let approval = PendingApproval(
            command: command,
            riskAssessment: riskAssessment,
            diff: diff,
            sessionId: sessionId
        )

        pendingApprovals[approval.id] = approval
        currentApproval = approval

        auditService.log(AuditEntry(
            actionType: .approvalRequested,
            command: command,
            riskLevel: riskAssessment.level,
            sessionId: sessionId,
            metadata: [
                "approval_id": approval.id,
                "trace_id": traceId
            ]
        ))

        return CommandResult(
            success: true,
            action: command.action,
            data: CommandResultData(
                diff: diff,
                message: "Awaiting user approval for \(riskAssessment.reason)"
            ),
            requiresConfirmation: true,
            pendingApprovalId: approval.id,
            executionTimeMs: currentTimeMs() - startTime
        )
    }

    // MARK: - Action Dispatch

    /// Execute the actual action. This is the exhaustive dispatch for all 32 CommandActions.
    private func executeAction(_ command: ExecuteCommand) async throws -> CommandResultData? {
        switch command.action {

        // ── File System Operations ──────────────────────────────

        case .listDirectory:
            let path = command.args.path ?? "/ext"
            let entries = try await fileSystem.listDirectory(path)
            return CommandResultData(entries: entries)

        case .readFile:
            let path = try requirePath(command)
            let content = try await fileSystem.readFile(path)
            return CommandResultData(content: content)

        case .writeFile:
            let path = try requirePath(command)
            let content = try requireContent(command)
            let bytesWritten = try await fileSystem.writeFile(path, content: content)
            return CommandResultData(bytesWritten: bytesWritten)

        case .createDirectory:
            let path = try requirePath(command)
            try await fileSystem.createDirectory(path)
            return CommandResultData(message: "Directory created: \(path)")

        case .delete:
            let path = try requirePath(command)
            try await fileSystem.delete(path, recursive: command.args.recursive)
            return CommandResultData(message: "Deleted: \(path)")

        case .move:
            let source = try requirePath(command)
            let dest = try requireDestinationPath(command)
            try await fileSystem.move(source, destination: dest)
            return CommandResultData(message: "Moved: \(source) -> \(dest)")

        case .copy:
            let source = try requirePath(command)
            let dest = try requireDestinationPath(command)
            try await fileSystem.copy(source, destination: dest)
            return CommandResultData(message: "Copied: \(source) -> \(dest)")

        case .rename:
            let path = try requirePath(command)
            guard let newName = command.args.newName, !newName.isEmpty else {
                throw CommandError.missingArgument("new_name")
            }
            try await fileSystem.rename(path, newName: newName)
            return CommandResultData(message: "Renamed to: \(newName)")

        // ── Device Info ─────────────────────────────────────────

        case .getDeviceInfo:
            let deviceInfo = try await fileSystem.getDeviceInfo()
            return CommandResultData(deviceInfo: deviceInfo)

        case .getStorageInfo:
            let storageInfo = try await fileSystem.getStorageInfo()
            return CommandResultData(storageInfo: storageInfo)

        // ── CLI Execution ───────────────────────────────────────

        case .executeCli:
            let cliCommand = command.args.command
                ?? command.args.content
            guard let cmd = cliCommand, !cmd.isEmpty else {
                throw CommandError.missingArgument("command")
            }
            let output = try await fileSystem.sendCliCommand(cmd)
            return CommandResultData(
                content: output,
                message: "Executed CLI command: \(cmd)"
            )

        // ── Artifact Push ───────────────────────────────────────

        case .pushArtifact:
            let path = try requirePath(command)
            guard let artifactData = command.args.artifactData, !artifactData.isEmpty else {
                throw CommandError.missingArgument("artifact_data")
            }
            guard let decoded = Data(base64Encoded: artifactData) else {
                throw CommandError.invalidArgument("Invalid Base64 artifact data")
            }
            let bytesWritten = try await fileSystem.writeFileBytes(path, data: decoded)
            return CommandResultData(
                bytesWritten: bytesWritten,
                message: "Artifact pushed: \(path)"
            )

        // ── Payload Forge ───────────────────────────────────────

        case .forgePayload:
            // Payload forging is handled at the AI layer, which generates the content
            // and then issues a writeFile command. This action returns a placeholder.
            let prompt = command.args.prompt ?? command.args.command ?? "unknown"
            let payloadType = command.args.payloadType ?? "generic"
            return CommandResultData(
                content: "forge_payload is handled at the agent layer",
                message: "Payload forge requested: type=\(payloadType), prompt=\(prompt)"
            )

        // ── Hardware Control: Signal Transmission ───────────────

        case .subghzTransmit:
            let path = try requirePath(command, detail: "Sub-GHz file path required (e.g. /ext/subghz/signal.sub)")
            let output = try await fileSystem.sendCliCommand("subghz tx_from_file \(path)")
            return CommandResultData(
                content: output,
                message: "Transmitted Sub-GHz signal: \(path)"
            )

        case .irTransmit:
            let path = try requirePath(command, detail: "IR file path required (e.g. /ext/infrared/remote.ir)")
            let signalName = command.args.signalName
            let cmd: String
            if let name = signalName {
                cmd = "ir tx \(path) \(name)"
            } else {
                cmd = "ir tx \(path)"
            }
            let output = try await fileSystem.sendCliCommand(cmd)
            let detail = signalName.map { " (\($0))" } ?? ""
            return CommandResultData(
                content: output,
                message: "Transmitted IR signal from: \(path)\(detail)"
            )

        case .nfcEmulate:
            let path = try requirePath(command, detail: "NFC file path required (e.g. /ext/nfc/card.nfc)")
            let output = try await fileSystem.sendCliCommand("nfc emulate \(path)")
            return CommandResultData(
                content: output,
                message: "Started NFC emulation: \(path)"
            )

        case .rfidEmulate:
            let path = try requirePath(command, detail: "RFID file path required (e.g. /ext/lfrfid/tag.rfid)")
            let output = try await fileSystem.sendCliCommand("lfrfid emulate \(path)")
            return CommandResultData(
                content: output,
                message: "Started RFID emulation: \(path)"
            )

        case .ibuttonEmulate:
            let path = try requirePath(command, detail: "iButton file path required (e.g. /ext/ibutton/key.ibtn)")
            let output = try await fileSystem.sendCliCommand("ibutton emulate \(path)")
            return CommandResultData(
                content: output,
                message: "Started iButton emulation: \(path)"
            )

        case .badusbExecute:
            let path = try requirePath(command, detail: "BadUSB script path required (e.g. /ext/badusb/script.txt)")
            let output = try await fileSystem.sendCliCommand("badusb run \(path)")
            return CommandResultData(
                content: output,
                message: "Executing BadUSB script: \(path)"
            )

        // ── Hardware Control: BLE, LED, Vibro ───────────────────

        case .bleSpam:
            let proto = command.args.protocol ?? command.args.appArgs ?? command.args.command ?? ""
            let cmd = proto.isEmpty ? "ble_spam" : "ble_spam \(proto)"
            let output = try await fileSystem.sendCliCommand(cmd)
            let msg = proto.lowercased().contains("stop") ? "Stopped BLE spam" : "Started BLE spam"
            return CommandResultData(content: output, message: msg)

        case .launchApp:
            let appName = command.args.appName ?? command.args.command
            guard let name = appName, !name.isEmpty else {
                throw CommandError.missingArgument("app_name")
            }
            let appArgs = command.args.appArgs ?? ""
            let cmd: String
            if appArgs.trimmingCharacters(in: .whitespaces).isEmpty {
                cmd = "loader open \(name)"
            } else {
                cmd = "loader open \(name) \(appArgs)"
            }
            let output = try await fileSystem.sendCliCommand(cmd)
            return CommandResultData(
                content: output,
                message: "Launched app: \(name)"
            )

        case .ledControl:
            let r = command.args.red ?? 0
            let g = command.args.green ?? 0
            let b = command.args.blue ?? 0
            let output = try await fileSystem.sendCliCommand("led \(r) \(g) \(b)")
            return CommandResultData(
                content: output,
                message: "LED set to RGB(\(r), \(g), \(b))"
            )

        case .vibroControl:
            let on = command.args.enabled ?? true
            let output = try await fileSystem.sendCliCommand("vibro \(on ? 1 : 0)")
            return CommandResultData(
                content: output,
                message: on ? "Vibration on" : "Vibration off"
            )

        // ── FapHub ──────────────────────────────────────────────

        case .searchFaphub:
            let query = command.args.command?.trimmingCharacters(in: .whitespaces)
            guard let q = query, !q.isEmpty else {
                throw CommandError.missingArgument("command (search query)")
            }
            return try await executeFapHubSearch(q)

        case .installFaphubApp:
            let appIdOrName = command.args.command?.trimmingCharacters(in: .whitespaces)
            guard let appId = appIdOrName, !appId.isEmpty else {
                throw CommandError.missingArgument("command (app id/name)")
            }
            let downloadUrl = command.args.downloadUrl?.trimmingCharacters(in: .whitespaces)
                ?? command.args.content?.trimmingCharacters(in: .whitespaces)
            return try await executeFapHubInstall(appId: appId, downloadUrl: downloadUrl)

        // ── Repository / Resource Operations ────────────────────

        case .browseRepo:
            let repoId = command.args.repoId ?? command.args.command
            guard let repo = repoId?.trimmingCharacters(in: .whitespaces), !repo.isEmpty else {
                throw CommandError.missingArgument("repo_id")
            }
            let subPath = command.args.subPath?.trimmingCharacters(in: .whitespaces) ?? ""
            return try await executeBrowseRepo(repoId: repo, subPath: subPath)

        case .downloadResource:
            guard let downloadUrl = command.args.downloadUrl?.trimmingCharacters(in: .whitespaces),
                  !downloadUrl.isEmpty else {
                throw CommandError.missingArgument("download_url")
            }
            let destPath = try requirePath(command, detail: "Destination path required")
            return try await executeDownloadResource(url: downloadUrl, destinationPath: destPath)

        case .githubSearch:
            let query = command.args.command?.trimmingCharacters(in: .whitespaces)
            guard let q = query, !q.isEmpty else {
                throw CommandError.missingArgument("command (search query)")
            }
            let scope = command.args.searchScope?.trimmingCharacters(in: .whitespaces).lowercased() ?? "code"
            return try await executeGitHubSearch(query: q, scope: scope)

        case .searchResources:
            let query = command.args.command?.trimmingCharacters(in: .whitespaces) ?? ""
            let resourceType = command.args.resourceType
            return try await executeSearchResources(query: query, resourceType: resourceType)

        // ── Vault / Runbook ─────────────────────────────────────

        case .listVault:
            let filter = command.args.filter
            let path = command.args.path
            return try await executeListVault(filter: filter, path: path)

        case .runRunbook:
            let runbookId = command.args.runbookId ?? command.args.command
            guard let rbId = runbookId?.trimmingCharacters(in: .whitespaces), !rbId.isEmpty else {
                throw CommandError.missingArgument("runbook_id")
            }
            return try await executeRunbook(rbId)

        // ── Photo ───────────────────────────────────────────────

        case .requestPhoto:
            let prompt = command.args.photoPrompt ?? command.args.prompt
            if let callback = photoCaptureCallback {
                let result = await callback(prompt)
                return CommandResultData(
                    content: result ?? "Photo capture completed",
                    message: "Photo captured"
                )
            }
            return CommandResultData(
                content: "request_photo is handled at the agent layer",
                message: "Photo capture handled by agent"
            )
        }
    }

    // MARK: - FapHub Operations

    private func executeFapHubSearch(_ query: String) async throws -> CommandResultData {
        let urlString = "https://catalog.flipperzero.one/api/v0/application?query=\(query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query)&limit=12"
        guard let url = URL(string: urlString) else {
            throw CommandError.invalidArgument("Invalid FapHub search URL")
        }

        let (data, response) = try await URLSession.shared.data(from: url)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            return CommandResultData(
                content: "FapHub search failed. HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)",
                message: "FapHub search error"
            )
        }

        let content = String(data: data, encoding: .utf8) ?? "No results"
        return CommandResultData(
            content: content,
            message: "FapHub search returned results for \"\(query)\""
        )
    }

    private func executeFapHubInstall(appId: String, downloadUrl: String?) async throws -> CommandResultData {
        // Resolve download URL
        let sourceUrl: String
        if let directUrl = downloadUrl, !directUrl.isEmpty {
            sourceUrl = directUrl
        } else {
            // Try the catalog API
            sourceUrl = "https://catalog.flipperzero.one/api/v0/application/\(appId)/build/last"
        }

        guard let url = URL(string: sourceUrl) else {
            throw CommandError.invalidArgument("Invalid download URL: \(sourceUrl)")
        }

        let (data, response) = try await URLSession.shared.data(from: url)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw CommandError.networkError("Failed to download FAP: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }

        guard !data.isEmpty else {
            throw CommandError.networkError("Downloaded file is empty")
        }

        let maxFapBytes = 2 * 1024 * 1024 // 2 MB
        guard data.count <= maxFapBytes else {
            throw CommandError.invalidArgument("Downloaded file too large (\(data.count) bytes)")
        }

        let installDir = "/ext/apps/misc"
        try? await fileSystem.createDirectory(installDir)
        let sanitizedId = appId.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "..", with: "")
        let targetPath = "\(installDir)/\(sanitizedId).fap"
        let bytesWritten = try await fileSystem.writeFileBytes(targetPath, data: data)

        return CommandResultData(
            bytesWritten: bytesWritten,
            content: "installed_app=\(appId)\ntarget_path=\(targetPath)\nsource_url=\(sourceUrl)",
            message: "Installed \(appId) to \(targetPath)"
        )
    }

    // MARK: - Repository Operations

    private func executeBrowseRepo(repoId: String, subPath: String) async throws -> CommandResultData {
        // Map known repo IDs to GitHub repos
        let repoMapping: [String: String] = [
            "irdb": "Lucaslhm/Flipper-IRDB",
            "subghz": "UberGuidoZ/Flipper",
            "badusb": "UberGuidoZ/Flipper",
            "nfc": "UberGuidoZ/Flipper",
            "rfid": "UberGuidoZ/Flipper",
            "ibutton": "UberGuidoZ/Flipper",
            "music": "neverfa11ing/FlipperMusicRTTTL",
        ]

        let ghRepo = repoMapping[repoId.lowercased()] ?? repoId
        let apiPath = subPath.isEmpty ? "contents" : "contents/\(subPath)"
        let urlString = "https://api.github.com/repos/\(ghRepo)/\(apiPath)"

        guard let url = URL(string: urlString) else {
            throw CommandError.invalidArgument("Invalid GitHub URL for repo: \(repoId)")
        }

        var request = URLRequest(url: url)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.setValue("Vesper-Flipper-Controller", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw CommandError.networkError("GitHub API error: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }

        let content = String(data: data, encoding: .utf8) ?? "No content"
        return CommandResultData(
            content: content,
            message: "Browsed repo \(ghRepo)/\(subPath)"
        )
    }

    private func executeDownloadResource(url downloadUrl: String, destinationPath: String) async throws -> CommandResultData {
        guard let url = URL(string: downloadUrl) else {
            throw CommandError.invalidArgument("Invalid download URL: \(downloadUrl)")
        }

        var request = URLRequest(url: url)
        request.setValue("Vesper-Flipper-Controller", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw CommandError.networkError("Download failed: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }

        guard !data.isEmpty else {
            throw CommandError.networkError("Downloaded file is empty")
        }

        // Ensure parent directory exists
        let parentDir = (destinationPath as NSString).deletingLastPathComponent
        if !parentDir.isEmpty && parentDir != "/" {
            try? await fileSystem.createDirectory(parentDir)
        }

        // Write as text if possible, otherwise binary
        if let textContent = String(data: data, encoding: .utf8) {
            let bytesWritten = try await fileSystem.writeFile(destinationPath, content: textContent)
            return CommandResultData(
                bytesWritten: bytesWritten,
                message: "Downloaded and saved to \(destinationPath)"
            )
        } else {
            let bytesWritten = try await fileSystem.writeFileBytes(destinationPath, data: data)
            return CommandResultData(
                bytesWritten: bytesWritten,
                message: "Downloaded binary and saved to \(destinationPath)"
            )
        }
    }

    private func executeGitHubSearch(query: String, scope: String) async throws -> CommandResultData {
        let flipperQuery = "\(query)+repo:UberGuidoZ/Flipper+repo:Lucaslhm/Flipper-IRDB+repo:djsime1/awesome-flipperzero"
        let encodedQuery = flipperQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? flipperQuery
        let urlString = "https://api.github.com/search/\(scope)?q=\(encodedQuery)&per_page=15"

        guard let url = URL(string: urlString) else {
            throw CommandError.invalidArgument("Invalid GitHub search URL")
        }

        var request = URLRequest(url: url)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.setValue("Vesper-Flipper-Controller", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw CommandError.networkError("GitHub search failed: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }

        let content = String(data: data, encoding: .utf8) ?? "No results"
        return CommandResultData(
            content: content,
            message: "GitHub search for \"\(query)\" (scope: \(scope))"
        )
    }

    private func executeSearchResources(query: String, resourceType: String?) async throws -> CommandResultData {
        // Search across known Flipper resource repositories
        let repos = [
            "UberGuidoZ/Flipper",
            "Lucaslhm/Flipper-IRDB",
            "djsime1/awesome-flipperzero",
            "neverfa11ing/FlipperMusicRTTTL",
        ]

        var searchQuery = query
        if let rt = resourceType, !rt.isEmpty {
            searchQuery += " extension:\(rt)"
        }

        let encodedQuery = (searchQuery + " " + repos.map { "repo:\($0)" }.joined(separator: " "))
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? searchQuery
        let urlString = "https://api.github.com/search/code?q=\(encodedQuery)&per_page=15"

        guard let url = URL(string: urlString) else {
            throw CommandError.invalidArgument("Invalid search URL")
        }

        var request = URLRequest(url: url)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.setValue("Vesper-Flipper-Controller", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw CommandError.networkError("Resource search failed: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }

        let content = String(data: data, encoding: .utf8) ?? "No results"
        return CommandResultData(
            content: content,
            message: "Resource search for \"\(query)\"\(resourceType.map { " (type: \($0))" } ?? "")"
        )
    }

    // MARK: - Vault / Runbook Operations

    private func executeListVault(filter: String?, path: String?) async throws -> CommandResultData {
        // List multiple Flipper directories to give a vault-style overview
        let directoriesToList: [String]
        if let specificPath = path, !specificPath.isEmpty {
            directoriesToList = [specificPath]
        } else {
            directoriesToList = [
                "/ext/subghz",
                "/ext/infrared",
                "/ext/nfc",
                "/ext/lfrfid",
                "/ext/ibutton",
                "/ext/badusb",
                "/ext/music_player",
                "/ext/apps",
            ]
        }

        var allEntries: [FileEntry] = []
        var messages: [String] = []

        for dir in directoriesToList {
            do {
                let entries = try await fileSystem.listDirectory(dir)
                let filtered: [FileEntry]
                if let filterText = filter, !filterText.isEmpty {
                    let lowered = filterText.lowercased()
                    filtered = entries.filter { $0.name.lowercased().contains(lowered) }
                } else {
                    filtered = entries
                }
                allEntries.append(contentsOf: filtered)
                messages.append("\(dir): \(filtered.count) item(s)")
            } catch {
                messages.append("\(dir): not accessible")
            }
        }

        let summary = messages.joined(separator: "\n")
        return CommandResultData(
            entries: allEntries,
            content: summary,
            message: "Vault listing: \(allEntries.count) total item(s)"
        )
    }

    private func executeRunbook(_ runbookId: String) async throws -> CommandResultData {
        // Predefined diagnostic runbooks
        let commands: [String]
        let label: String

        switch runbookId.lowercased() {
        case "health", "health_check":
            label = "Health Check"
            commands = [
                "device_info",
                "storage info /ext",
                "storage info /int",
            ]
        case "storage", "storage_check":
            label = "Storage Diagnostics"
            commands = [
                "storage info /ext",
                "storage info /int",
                "storage list /ext",
            ]
        case "radio", "radio_check":
            label = "Radio Diagnostics"
            commands = [
                "device_info",
                "subghz",
            ]
        case "full", "full_diagnostic":
            label = "Full Diagnostic"
            commands = [
                "device_info",
                "storage info /ext",
                "storage info /int",
                "storage list /ext",
                "storage list /ext/subghz",
                "storage list /ext/nfc",
                "storage list /ext/infrared",
                "storage list /ext/lfrfid",
                "storage list /ext/ibutton",
                "storage list /ext/apps",
            ]
        default:
            label = "Custom Runbook: \(runbookId)"
            commands = ["device_info"]
        }

        var outputs: [String] = []
        for cmd in commands {
            do {
                let output = try await fileSystem.sendCliCommand(cmd)
                outputs.append("$ \(cmd)\n\(output)")
            } catch {
                outputs.append("$ \(cmd)\nERROR: \(error.localizedDescription)")
            }
        }

        let content = outputs.joined(separator: "\n\n")
        return CommandResultData(
            content: content,
            message: "Runbook '\(label)' completed (\(commands.count) commands)"
        )
    }

    // MARK: - Helpers

    private func requirePath(_ command: ExecuteCommand, detail: String = "Path required") throws -> String {
        guard let path = command.args.path, !path.isEmpty else {
            throw CommandError.missingArgument(detail)
        }
        return path
    }

    private func requireDestinationPath(_ command: ExecuteCommand) throws -> String {
        guard let path = command.args.destinationPath, !path.isEmpty else {
            throw CommandError.missingArgument("destination_path")
        }
        return path
    }

    private func requireContent(_ command: ExecuteCommand) throws -> String {
        guard let content = command.args.content else {
            throw CommandError.missingArgument("content")
        }
        return content
    }

    private func currentTimeMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func clearExpiredApprovals() {
        let now = currentTimeMs()
        let expired = pendingApprovals.filter { (_, approval) in
            now - approval.createdAt > Self.approvalExpirationMs
        }
        for (key, _) in expired {
            pendingApprovals.removeValue(forKey: key)
        }
        if let current = currentApproval, expired.keys.contains(current.id) {
            currentApproval = nil
        }
    }
}

// MARK: - Command Errors

enum CommandError: LocalizedError, Sendable {
    case missingArgument(String)
    case invalidArgument(String)
    case networkError(String)
    case executionFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingArgument(let arg):
            return "Missing required argument: \(arg)"
        case .invalidArgument(let detail):
            return "Invalid argument: \(detail)"
        case .networkError(let detail):
            return "Network error: \(detail)"
        case .executionFailed(let detail):
            return "Execution failed: \(detail)"
        }
    }
}
