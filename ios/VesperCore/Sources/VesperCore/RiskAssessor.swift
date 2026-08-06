import Foundation

public struct RiskAssessor: Sendable {
    public init() {}

    public func assess(_ command: ExecuteCommand, grants: [PermissionGrant] = [], now: Date = .now) -> RiskAssessment {
        let paths = affectedPaths(command)
        if let blocked = paths.first(where: PathSecurity.isProtected),
           !grants.contains(where: { $0.unlocksProtectedPath && $0.covers(blocked, now: now) }) {
            return assessment(.blocked, "Protected path", paths, blockedReason: PathSecurity.blockedReason(for: blocked))
        }

        switch command.action {
        case .listDirectory, .readFile, .getDeviceInfo, .getStorageInfo, .ledControl, .vibroControl:
            return assessment(.low, "Read-only or harmless hardware operation", paths)
        case .writeFile:
            let permitted = command.args.path.map { hasWriteGrant($0, grants, now) } ?? false
            return assessment(permitted ? .medium : .high, permitted ? "File modification" : "Write outside permitted scope", paths, diff: true, confirmation: !permitted)
        case .createDirectory:
            let permitted = command.args.path.map { hasWriteGrant($0, grants, now) } ?? false
            return assessment(permitted ? .low : .medium, "Directory creation", paths, confirmation: !permitted)
        case .copy:
            let permitted = command.args.destinationPath.map { hasWriteGrant($0, grants, now) } ?? false
            return assessment(permitted ? .medium : .high, "Copy operation", paths, confirmation: !permitted)
        case .delete:
            return assessment(.high, command.args.recursive ? "Recursive deletion" : "File deletion", paths, confirmation: true)
        case .move, .rename:
            return assessment(.high, "Move or rename operation", paths, confirmation: true)
        case .pushArtifact:
            let executable = ["fap", "app", "executable"].contains(command.args.artifactType?.lowercased() ?? "")
            return assessment(executable ? .high : .medium, executable ? "Pushing executable artifact" : "Pushing artifact", paths, confirmation: true)
        case .executeCLI:
            return assessCLI(command, paths: paths)
        case .launchApp, .subghzTransmit, .irTransmit, .nfcEmulate, .rfidEmulate, .ibuttonEmulate, .bleSpam:
            return assessment(.medium, "Hardware operation", paths, confirmation: true)
        case .badusbExecute:
            return assessment(.high, "BadUSB injects keystrokes", paths, confirmation: true)
        default:
            return assessment(.blocked, "Action is not available in the iOS alpha", paths, blockedReason: "Unsupported alpha action")
        }
    }

    private func affectedPaths(_ command: ExecuteCommand) -> [String] {
        var values = [command.args.path, command.args.destinationPath].compactMap { $0 }
        if command.action == .executeCLI {
            values += (command.args.command ?? command.args.content ?? "")
                .split(whereSeparator: \ .isWhitespace).map(String.init).filter { $0.hasPrefix("/") }
        }
        return values
    }

    private func hasWriteGrant(_ path: String, _ grants: [PermissionGrant], _ now: Date) -> Bool {
        grants.contains { $0.covers(path, now: now) }
    }

    private func assessCLI(_ command: ExecuteCommand, paths: [String]) -> RiskAssessment {
        let value = (command.args.command ?? command.args.content ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let safe = ["help", "version", "device_info", "device info", "info", "storage list", "storage ls", "storage read", "storage cat", "storage info", "storage stat", "led ", "vibro "]
        let medium = ["loader open", "loader list", "loader info", "subghz tx", "subghz tx_from_file", "ir tx", "infrared tx", "nfc emulate", "nfc emu", "rfid emulate", "rfid emu", "lfrfid emulate", "lfrfid emu", "ibutton emulate", "ibutton emu", "ble_spam", "blespam", "ble spam", "ble_scan", "blescan", "ble scan"]
        if safe.contains(where: value.hasPrefix) { return assessment(.low, "Read-only CLI command", paths) }
        if medium.contains(where: value.hasPrefix) { return assessment(.medium, "Hardware control CLI command", paths, confirmation: true) }
        return assessment(.high, "Potentially destructive CLI command", paths, confirmation: true)
    }

    private func assessment(_ level: RiskLevel, _ reason: String, _ paths: [String], diff: Bool = false, confirmation: Bool = false, blockedReason: String? = nil) -> RiskAssessment {
        RiskAssessment(level: level, reason: reason, affectedPaths: paths, requiresDiff: diff, requiresConfirmation: confirmation, blockedReason: blockedReason)
    }
}
