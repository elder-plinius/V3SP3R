import Foundation

public actor CommandExecutor {
    private let rpc: any FlipperRPCClient
    private let audit: any AuditRecording
    private let riskAssessor: RiskAssessor
    private var grants: [PermissionGrant]
    private var pending: PendingApproval?
    private let approvalLifetime: TimeInterval

    public init(
        rpc: any FlipperRPCClient,
        audit: any AuditRecording,
        riskAssessor: RiskAssessor = .init(),
        grants: [PermissionGrant] = [],
        approvalLifetime: TimeInterval = 120
    ) {
        self.rpc = rpc
        self.audit = audit
        self.riskAssessor = riskAssessor
        self.grants = grants
        self.approvalLifetime = approvalLifetime
    }

    public func pendingApproval() -> PendingApproval? {
        clearExpiredApproval()
        return pending
    }

    public func replaceGrants(_ values: [PermissionGrant]) { grants = values }

    public func execute(_ command: ExecuteCommand) async -> CommandResult {
        let start = ContinuousClock.now
        guard CommandAction.alphaEnabled.contains(command.action) else {
            return await fail(command, VesperCoreError.unsupportedAction(command.action), start: start)
        }
        await audit.record(AuditEvent(kind: "command_received", command: command))
        let assessment = riskAssessor.assess(command, grants: grants)
        if assessment.level == .blocked {
            let result = CommandResult(success: false, action: command.action, error: assessment.blockedReason ?? assessment.reason)
            await audit.record(AuditEvent(kind: "command_blocked", command: command, result: result, riskLevel: .blocked, detail: assessment.blockedReason))
            return result
        }
        if assessment.level == .high || assessment.requiresConfirmation || assessment.requiresDiff {
            return await requestApproval(command, assessment: assessment, start: start)
        }
        return await executeApproved(command, assessment: assessment, start: start)
    }

    public func approve(_ id: UUID) async -> CommandResult {
        clearExpiredApproval()
        guard let approval = pending, approval.id == id else {
            return CommandResult(success: false, action: .listDirectory, error: VesperCoreError.approvalNotFound.localizedDescription)
        }
        pending = nil
        await audit.record(AuditEvent(kind: "approval_granted", command: approval.command, riskLevel: approval.assessment.level))
        return await executeApproved(approval.command, assessment: approval.assessment, start: ContinuousClock.now)
    }

    public func reject(_ id: UUID) async -> CommandResult {
        clearExpiredApproval()
        guard let approval = pending, approval.id == id else {
            return CommandResult(success: false, action: .listDirectory, error: VesperCoreError.approvalNotFound.localizedDescription)
        }
        pending = nil
        let result = CommandResult(success: false, action: approval.command.action, error: "Action rejected by user")
        await audit.record(AuditEvent(kind: "approval_rejected", command: approval.command, result: result, riskLevel: approval.assessment.level))
        return result
    }

    public func cancelPendingForBackground() async {
        guard let approval = pending else { return }
        pending = nil
        await audit.record(AuditEvent(kind: "approval_cancelled", command: approval.command, riskLevel: approval.assessment.level, detail: "App entered background"))
    }

    private func requestApproval(_ command: ExecuteCommand, assessment: RiskAssessment, start: ContinuousClock.Instant) async -> CommandResult {
        var diff: FileDiff?
        if assessment.requiresDiff, command.action == .writeFile, let path = command.args.path {
            let original = try? await rpc.readFile(try PathSecurity.normalize(path))
            diff = DiffEngine.compute(original: original.flatMap { String(data: $0, encoding: .utf8) }, replacement: command.args.content ?? "")
        }
        let approval = PendingApproval(command: command, assessment: assessment, diff: diff, expiresAt: .now.addingTimeInterval(approvalLifetime))
        pending = approval
        await audit.record(AuditEvent(kind: "approval_requested", command: command, riskLevel: assessment.level, detail: assessment.reason))
        return CommandResult(
            success: true,
            action: command.action,
            data: CommandResultData(diff: diff, message: "Awaiting user approval: \(assessment.reason)"),
            executionTimeMS: elapsedMS(start),
            requiresConfirmation: true,
            pendingApprovalID: approval.id
        )
    }

    private func executeApproved(_ command: ExecuteCommand, assessment: RiskAssessment, start: ContinuousClock.Instant) async -> CommandResult {
        do {
            let data = try await dispatch(command)
            let result = CommandResult(success: true, action: command.action, data: data, executionTimeMS: elapsedMS(start))
            await audit.record(AuditEvent(kind: "command_executed", command: command, result: result, riskLevel: assessment.level))
            return result
        } catch {
            return await fail(command, error, risk: assessment.level, start: start)
        }
    }

    private func dispatch(_ command: ExecuteCommand) async throws -> CommandResultData {
        let args = command.args
        switch command.action {
        case .listDirectory:
            let path = try PathSecurity.normalize(args.path ?? "/ext")
            return CommandResultData(entries: try await rpc.listDirectory(path))
        case .readFile:
            let path = try requiredPath(args.path)
            let bytes = try await rpc.readFile(path)
            return CommandResultData(content: String(decoding: bytes, as: UTF8.self))
        case .writeFile:
            let path = try requiredPath(args.path)
            let content = try required(args.content, "content")
            let bytes = Data(content.utf8)
            guard bytes.count <= PathSecurity.maximumContentBytes else { throw VesperCoreError.invalidCommand("Content exceeds 10 MB") }
            return CommandResultData(bytesWritten: try await rpc.writeFile(path, data: bytes))
        case .createDirectory:
            let path = try requiredPath(args.path)
            try await rpc.createDirectory(path)
            return CommandResultData(message: "Directory created: \(path)")
        case .delete:
            let path = try requiredPath(args.path)
            try await rpc.delete(path, recursive: args.recursive)
            return CommandResultData(message: "Deleted: \(path)")
        case .move:
            let source = try requiredPath(args.path)
            let destination = try requiredPath(args.destinationPath)
            try await rpc.move(source, to: destination)
            return CommandResultData(message: "Moved: \(source) → \(destination)")
        case .rename:
            let source = try requiredPath(args.path)
            let name = try required(args.newName, "new_name")
            guard !name.contains("/"), !name.contains("..") else { throw VesperCoreError.invalidPath(name) }
            let destination = URL(fileURLWithPath: source).deletingLastPathComponent().appendingPathComponent(name).path
            try await rpc.move(source, to: destination)
            return CommandResultData(message: "Renamed to: \(name)")
        case .copy:
            let source = try requiredPath(args.path)
            let destination = try requiredPath(args.destinationPath)
            try await rpc.copy(source, to: destination)
            return CommandResultData(message: "Copied: \(source) → \(destination)")
        case .getDeviceInfo:
            return CommandResultData(deviceInfo: try await rpc.getDeviceInfo())
        case .getStorageInfo:
            return CommandResultData(storageInfo: try await rpc.getStorageInfo())
        case .pushArtifact:
            let path = try requiredPath(args.path)
            let encoded = try required(args.artifactData, "artifact_data")
            guard let bytes = Data(base64Encoded: encoded), bytes.count <= PathSecurity.maximumContentBytes else {
                throw VesperCoreError.invalidCommand("Invalid or oversized Base64 artifact")
            }
            return CommandResultData(bytesWritten: try await rpc.writeFile(path, data: bytes), message: "Artifact pushed: \(path)")
        case .executeCLI:
            let cli = try required(args.command ?? args.content, "command")
            return CommandResultData(content: try await rpc.executeCLI(cli), message: "Executed CLI command")
        case .launchApp:
            let app = try required(args.appName ?? args.command, "app_name")
            let suffix = args.appArgs.map { " \($0)" } ?? ""
            return CommandResultData(content: try await rpc.executeCLI("loader open \(app)\(suffix)"), message: "Launched \(app)")
        case .subghzTransmit:
            return try await cliForPath("subghz tx", args: args, message: "Sub-GHz transmission started")
        case .irTransmit:
            let suffix = args.signalName.map { " \($0)" } ?? ""
            return try await cliForPath("ir tx", args: args, suffix: suffix, message: "IR transmission started")
        case .nfcEmulate:
            return try await cliForPath("nfc emulate", args: args, message: "NFC emulation started")
        case .rfidEmulate:
            return try await cliForPath("rfid emulate", args: args, message: "RFID emulation started")
        case .ibuttonEmulate:
            return try await cliForPath("ibutton emulate", args: args, message: "iButton emulation started")
        case .badusbExecute:
            return try await cliForPath("badusb run", args: args, message: "BadUSB execution started")
        case .bleSpam:
            let suffix = args.appArgs ?? args.command ?? ""
            return CommandResultData(content: try await rpc.executeCLI("ble_spam \(suffix)".trimmingCharacters(in: .whitespaces)), message: "BLE spam command sent")
        case .ledControl:
            let command = "led \(clamp(args.red)) \(clamp(args.green)) \(clamp(args.blue))"
            return CommandResultData(content: try await rpc.executeCLI(command), message: "LED updated")
        case .vibroControl:
            return CommandResultData(content: try await rpc.executeCLI("vibro \((args.enabled ?? true) ? 1 : 0)"), message: "Vibration updated")
        default:
            throw VesperCoreError.unsupportedAction(command.action)
        }
    }

    private func cliForPath(_ prefix: String, args: CommandArgs, suffix: String = "", message: String) async throws -> CommandResultData {
        let path = try requiredPath(args.path)
        return CommandResultData(content: try await rpc.executeCLI("\(prefix) \(path)\(suffix)"), message: message)
    }

    private func requiredPath(_ value: String?) throws -> String { try PathSecurity.normalize(required(value, "path")) }
    private func required<T>(_ value: T?, _ name: String) throws -> T { guard let value else { throw VesperCoreError.missingArgument(name) }; return value }
    private func clamp(_ value: Int?) -> Int { min(255, max(0, value ?? 0)) }

    private func clearExpiredApproval() {
        if let pending, pending.expiresAt <= .now { self.pending = nil }
    }

    private func elapsedMS(_ start: ContinuousClock.Instant) -> UInt64 {
        let duration = start.duration(to: .now)
        return UInt64(max(0, duration.components.seconds * 1_000 + duration.components.attoseconds / 1_000_000_000_000_000))
    }

    private func fail(_ command: ExecuteCommand, _ error: Error, risk: RiskLevel? = nil, start: ContinuousClock.Instant) async -> CommandResult {
        let result = CommandResult(success: false, action: command.action, error: error.localizedDescription, executionTimeMS: elapsedMS(start))
        await audit.record(AuditEvent(kind: "command_failed", command: command, result: result, riskLevel: risk, detail: error.localizedDescription))
        return result
    }
}
