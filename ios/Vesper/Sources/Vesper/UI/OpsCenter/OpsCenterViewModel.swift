import SwiftUI

@Observable
class OpsCenterViewModel {
    private let bleManager: FlipperBLEManager
    private let commandExecutor: CommandExecutor
    private let auditService: AuditService

    var isDeviceConnected: Bool {
        bleManager.connectionState == .connected
    }

    var deviceName: String {
        bleManager.connectedDevice?.name ?? "No Device"
    }

    var recentActions: [AuditEntry] {
        Array(auditService.recentEntries.prefix(10))
    }

    var pipelineHealth: PipelineHealth = .idle

    // Runbook definitions
    static let runbooks: [(id: String, name: String, description: String, icon: String)] = [
        ("health_check", "Health Check", "Device info, storage, battery status", "heart.text.square"),
        ("storage_audit", "Storage Audit", "List all directories, check free space", "externaldrive"),
        ("signal_inventory", "Signal Inventory", "Scan Sub-GHz, IR, NFC, RFID files", "antenna.radiowaves.left.and.right"),
        ("app_inventory", "App Inventory", "List installed .fap applications", "app.badge.checkmark"),
        ("security_scan", "Security Scan", "Check for sensitive files, validate paths", "lock.shield"),
    ]

    init(bleManager: FlipperBLEManager, commandExecutor: CommandExecutor, auditService: AuditService) {
        self.bleManager = bleManager
        self.commandExecutor = commandExecutor
        self.auditService = auditService
    }

    func runRunbook(_ runbookId: String) {
        pipelineHealth = .running(runbookId)
        Task {
            let command = ExecuteCommand(
                action: .runRunbook,
                args: CommandArgs(runbookId: runbookId),
                justification: "User-initiated runbook",
                expectedEffect: "Diagnostic information gathered"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if result.success {
                pipelineHealth = .completed(result.data?.message ?? "Runbook completed")
            } else {
                pipelineHealth = .error(result.error ?? "Runbook failed")
            }
        }
    }
}

enum PipelineHealth: Equatable {
    case idle
    case running(String)
    case completed(String)
    case error(String)
}
