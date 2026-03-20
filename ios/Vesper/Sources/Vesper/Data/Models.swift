// Models.swift
// Vesper - AI-powered Flipper Zero controller
// Data models ported from Android with full Codable conformance

import Foundation

// MARK: - Command Action

/// All supported Flipper operations, matching Android's CommandAction enum.
enum CommandAction: String, Codable, CaseIterable, Sendable {
    case listDirectory = "list_directory"
    case readFile = "read_file"
    case writeFile = "write_file"
    case createDirectory = "create_directory"
    case delete = "delete"
    case move = "move"
    case rename = "rename"
    case copy = "copy"
    case getDeviceInfo = "get_device_info"
    case getStorageInfo = "get_storage_info"
    case executeCli = "execute_cli"
    case pushArtifact = "push_artifact"
    case forgePayload = "forge_payload"
    case subghzTransmit = "subghz_transmit"
    case irTransmit = "ir_transmit"
    case nfcEmulate = "nfc_emulate"
    case rfidEmulate = "rfid_emulate"
    case ibuttonEmulate = "ibutton_emulate"
    case badusbExecute = "badusb_execute"
    case bleSpam = "ble_spam"
    case launchApp = "launch_app"
    case ledControl = "led_control"
    case vibroControl = "vibro_control"
    case searchFaphub = "search_faphub"
    case installFaphubApp = "install_faphub_app"
    case browseRepo = "browse_repo"
    case downloadResource = "download_resource"
    case githubSearch = "github_search"
    case searchResources = "search_resources"
    case listVault = "list_vault"
    case runRunbook = "run_runbook"
    case requestPhoto = "request_photo"
}

// MARK: - Execute Command

/// The single command interface for AI agent interaction.
/// All Flipper operations go through this unified structure.
struct ExecuteCommand: Codable, Sendable, Equatable {
    let action: CommandAction
    let args: CommandArgs
    let justification: String
    let expectedEffect: String

    enum CodingKeys: String, CodingKey {
        case action
        case args
        case justification
        case expectedEffect = "expected_effect"
    }
}

// MARK: - Command Args

/// Arguments for command execution. All fields optional except where contextually required.
struct CommandArgs: Codable, Sendable, Equatable {
    var command: String?
    var path: String?
    var destinationPath: String?
    var content: String?
    var newName: String?
    var recursive: Bool
    var artifactType: String?
    var artifactData: String?
    var prompt: String?
    var resourceType: String?
    var runbookId: String?
    var payloadType: String?
    var filter: String?
    var appName: String?
    var appArgs: String?
    var frequency: Int64?
    var `protocol`: String?
    var address: String?
    var signalName: String?
    var enabled: Bool?
    var red: Int?
    var green: Int?
    var blue: Int?
    var repoId: String?
    var subPath: String?
    var downloadUrl: String?
    var searchScope: String?
    var photoPrompt: String?

    enum CodingKeys: String, CodingKey {
        case command, path, content, recursive, prompt, filter, address, enabled, red, green, blue
        case destinationPath = "destination_path"
        case newName = "new_name"
        case artifactType = "artifact_type"
        case artifactData = "artifact_data"
        case resourceType = "resource_type"
        case runbookId = "runbook_id"
        case payloadType = "payload_type"
        case appName = "app_name"
        case appArgs = "app_args"
        case frequency
        case `protocol`
        case signalName = "signal_name"
        case repoId = "repo_id"
        case subPath = "sub_path"
        case downloadUrl = "download_url"
        case searchScope = "search_scope"
        case photoPrompt = "photo_prompt"
    }

    init(
        command: String? = nil,
        path: String? = nil,
        destinationPath: String? = nil,
        content: String? = nil,
        newName: String? = nil,
        recursive: Bool = false,
        artifactType: String? = nil,
        artifactData: String? = nil,
        prompt: String? = nil,
        resourceType: String? = nil,
        runbookId: String? = nil,
        payloadType: String? = nil,
        filter: String? = nil,
        appName: String? = nil,
        appArgs: String? = nil,
        frequency: Int64? = nil,
        protocol: String? = nil,
        address: String? = nil,
        signalName: String? = nil,
        enabled: Bool? = nil,
        red: Int? = nil,
        green: Int? = nil,
        blue: Int? = nil,
        repoId: String? = nil,
        subPath: String? = nil,
        downloadUrl: String? = nil,
        searchScope: String? = nil,
        photoPrompt: String? = nil
    ) {
        self.command = command
        self.path = path
        self.destinationPath = destinationPath
        self.content = content
        self.newName = newName
        self.recursive = recursive
        self.artifactType = artifactType
        self.artifactData = artifactData
        self.prompt = prompt
        self.resourceType = resourceType
        self.runbookId = runbookId
        self.payloadType = payloadType
        self.filter = filter
        self.appName = appName
        self.appArgs = appArgs
        self.frequency = frequency
        self.protocol = `protocol`
        self.address = address
        self.signalName = signalName
        self.enabled = enabled
        self.red = red
        self.green = green
        self.blue = blue
        self.repoId = repoId
        self.subPath = subPath
        self.downloadUrl = downloadUrl
        self.searchScope = searchScope
        self.photoPrompt = photoPrompt
    }
}

// MARK: - Command Result

/// Result returned after command execution.
struct CommandResult: Codable, Sendable {
    let success: Bool
    let action: CommandAction
    var data: CommandResultData?
    var error: String?
    var executionTimeMs: Int64
    var requiresConfirmation: Bool
    var pendingApprovalId: String?

    enum CodingKeys: String, CodingKey {
        case success, action, data, error
        case executionTimeMs = "execution_time_ms"
        case requiresConfirmation = "requires_confirmation"
        case pendingApprovalId = "pending_approval_id"
    }

    init(
        success: Bool,
        action: CommandAction,
        data: CommandResultData? = nil,
        error: String? = nil,
        executionTimeMs: Int64 = 0,
        requiresConfirmation: Bool = false,
        pendingApprovalId: String? = nil
    ) {
        self.success = success
        self.action = action
        self.data = data
        self.error = error
        self.executionTimeMs = executionTimeMs
        self.requiresConfirmation = requiresConfirmation
        self.pendingApprovalId = pendingApprovalId
    }
}

// MARK: - Command Result Data

struct CommandResultData: Codable, Sendable {
    var entries: [FileEntry]?
    var content: String?
    var bytesWritten: Int64?
    var deviceInfo: DeviceInfo?
    var storageInfo: StorageInfo?
    var diff: FileDiff?
    var message: String?

    enum CodingKeys: String, CodingKey {
        case entries, content, diff, message
        case bytesWritten = "bytes_written"
        case deviceInfo = "device_info"
        case storageInfo = "storage_info"
    }

    init(
        entries: [FileEntry]? = nil,
        content: String? = nil,
        bytesWritten: Int64? = nil,
        deviceInfo: DeviceInfo? = nil,
        storageInfo: StorageInfo? = nil,
        diff: FileDiff? = nil,
        message: String? = nil
    ) {
        self.entries = entries
        self.content = content
        self.bytesWritten = bytesWritten
        self.deviceInfo = deviceInfo
        self.storageInfo = storageInfo
        self.diff = diff
        self.message = message
    }
}

// MARK: - File Entry

struct FileEntry: Codable, Sendable, Identifiable, Equatable {
    let name: String
    let path: String
    let isDirectory: Bool
    var size: Int64
    var modifiedTimestamp: Int64?

    var id: String { path }

    enum CodingKeys: String, CodingKey {
        case name, path, size
        case isDirectory = "is_directory"
        case modifiedTimestamp = "modified_timestamp"
    }

    init(
        name: String,
        path: String,
        isDirectory: Bool,
        size: Int64 = 0,
        modifiedTimestamp: Int64? = nil
    ) {
        self.name = name
        self.path = path
        self.isDirectory = isDirectory
        self.size = size
        self.modifiedTimestamp = modifiedTimestamp
    }
}

// MARK: - Device Info

struct DeviceInfo: Codable, Sendable, Equatable {
    let name: String
    let firmwareVersion: String
    let hardwareVersion: String
    let batteryLevel: Int
    let isCharging: Bool

    enum CodingKeys: String, CodingKey {
        case name
        case firmwareVersion = "firmware_version"
        case hardwareVersion = "hardware_version"
        case batteryLevel = "battery_level"
        case isCharging = "is_charging"
    }
}

// MARK: - Storage Info

struct StorageInfo: Codable, Sendable, Equatable {
    let internalTotal: Int64
    let internalFree: Int64
    var externalTotal: Int64?
    var externalFree: Int64?
    let hasSdCard: Bool

    enum CodingKeys: String, CodingKey {
        case internalTotal = "internal_total"
        case internalFree = "internal_free"
        case externalTotal = "external_total"
        case externalFree = "external_free"
        case hasSdCard = "has_sd_card"
    }
}

// MARK: - File Diff

struct FileDiff: Codable, Sendable, Equatable {
    let originalContent: String?
    let newContent: String
    let linesAdded: Int
    let linesRemoved: Int
    let unifiedDiff: String

    enum CodingKeys: String, CodingKey {
        case originalContent = "original_content"
        case newContent = "new_content"
        case linesAdded = "lines_added"
        case linesRemoved = "lines_removed"
        case unifiedDiff = "unified_diff"
    }
}

// MARK: - Risk Level

enum RiskLevel: String, Codable, Sendable, CaseIterable {
    case low
    case medium
    case high
    case blocked
}

// MARK: - Risk Assessment

struct RiskAssessment: Sendable, Equatable {
    let level: RiskLevel
    let reason: String
    let affectedPaths: [String]
    let requiresDiff: Bool
    let requiresConfirmation: Bool
    var blockedReason: String?

    init(
        level: RiskLevel,
        reason: String,
        affectedPaths: [String] = [],
        requiresDiff: Bool = false,
        requiresConfirmation: Bool = false,
        blockedReason: String? = nil
    ) {
        self.level = level
        self.reason = reason
        self.affectedPaths = affectedPaths
        self.requiresDiff = requiresDiff
        self.requiresConfirmation = requiresConfirmation
        self.blockedReason = blockedReason
    }
}

// MARK: - Message Role

enum MessageRole: String, Codable, Sendable {
    case user
    case assistant
    case system
    case tool
}

// MARK: - Chat Message

struct ChatMessage: Codable, Sendable, Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let content: String?
    let timestamp: Date
    var toolCalls: [ToolCall]?
    var toolResults: [ToolResult]?
    var imageAttachments: [ImageAttachment]?
    var isError: Bool

    enum CodingKeys: String, CodingKey {
        case id, role, content, timestamp
        case toolCalls = "tool_calls"
        case toolResults = "tool_results"
        case imageAttachments = "image_attachments"
        case isError = "is_error"
    }

    init(
        id: String = UUID().uuidString,
        role: MessageRole,
        content: String? = nil,
        timestamp: Date = Date(),
        toolCalls: [ToolCall]? = nil,
        toolResults: [ToolResult]? = nil,
        imageAttachments: [ImageAttachment]? = nil,
        isError: Bool = false
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.toolCalls = toolCalls
        self.toolResults = toolResults
        self.imageAttachments = imageAttachments
        self.isError = isError
    }

    static func == (lhs: ChatMessage, rhs: ChatMessage) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - Tool Call

struct ToolCall: Codable, Sendable, Identifiable, Equatable {
    let id: String
    let name: String
    let arguments: String

    init(
        id: String = UUID().uuidString,
        name: String,
        arguments: String
    ) {
        self.id = id
        self.name = name
        self.arguments = arguments
    }
}

// MARK: - Tool Result

struct ToolResult: Codable, Sendable, Equatable {
    let toolCallId: String
    let content: String

    enum CodingKeys: String, CodingKey {
        case toolCallId = "tool_call_id"
        case content
    }
}

// MARK: - Image Attachment

struct ImageAttachment: Codable, Sendable, Identifiable, Equatable {
    let id: String
    let data: Data
    let mimeType: String

    enum CodingKeys: String, CodingKey {
        case id, data
        case mimeType = "mime_type"
    }

    init(
        id: String = UUID().uuidString,
        data: Data,
        mimeType: String = "image/jpeg"
    ) {
        self.id = id
        self.data = data
        self.mimeType = mimeType
    }
}

// MARK: - Conversation State

struct ConversationState: Sendable {
    var messages: [ChatMessage] = []
    var isLoading: Bool = false
    var error: String?
    var sessionId: String = UUID().uuidString
    var progress: AgentProgress?
}

// MARK: - Agent Progress

struct AgentProgress: Sendable, Equatable {
    let stage: AgentProgressStage
    var detail: String
}

enum AgentProgressStage: String, Sendable, CaseIterable {
    case modelRequest
    case toolExecution
    case approval
}

// MARK: - Audit Entry

struct AuditEntry: Codable, Sendable, Identifiable {
    let id: String
    let timestamp: Int64
    let actionType: AuditActionType
    var command: ExecuteCommand?
    var result: CommandResult?
    var riskLevel: RiskLevel?
    var userApproved: Bool?
    var approvalMethod: ApprovalMethod?
    let sessionId: String
    var metadata: [String: String]

    enum CodingKeys: String, CodingKey {
        case id, timestamp, command, result, metadata
        case actionType = "action_type"
        case riskLevel = "risk_level"
        case userApproved = "user_approved"
        case approvalMethod = "approval_method"
        case sessionId = "session_id"
    }

    init(
        id: String = UUID().uuidString,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        actionType: AuditActionType,
        command: ExecuteCommand? = nil,
        result: CommandResult? = nil,
        riskLevel: RiskLevel? = nil,
        userApproved: Bool? = nil,
        approvalMethod: ApprovalMethod? = nil,
        sessionId: String,
        metadata: [String: String] = [:]
    ) {
        self.id = id
        self.timestamp = timestamp
        self.actionType = actionType
        self.command = command
        self.result = result
        self.riskLevel = riskLevel
        self.userApproved = userApproved
        self.approvalMethod = approvalMethod
        self.sessionId = sessionId
        self.metadata = metadata
    }
}

// MARK: - Audit Action Type

enum AuditActionType: String, Codable, Sendable, CaseIterable {
    case sessionStarted = "session_started"
    case sessionEnded = "session_ended"
    case commandReceived = "command_received"
    case commandExecuted = "command_executed"
    case commandFailed = "command_failed"
    case commandBlocked = "command_blocked"
    case approvalRequested = "approval_requested"
    case approvalGranted = "approval_granted"
    case approvalDenied = "approval_denied"
}

// MARK: - Approval Method

enum ApprovalMethod: String, Codable, Sendable {
    case auto
    case singleTap = "single_tap"
    case doubleTap = "double_tap"
}

// MARK: - Pending Approval

struct PendingApproval: Sendable, Identifiable {
    let id: String
    let command: ExecuteCommand
    let riskAssessment: RiskAssessment
    var diff: FileDiff?
    let sessionId: String
    let createdAt: Int64

    init(
        id: String = UUID().uuidString,
        command: ExecuteCommand,
        riskAssessment: RiskAssessment,
        diff: FileDiff? = nil,
        sessionId: String,
        createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.id = id
        self.command = command
        self.riskAssessment = riskAssessment
        self.diff = diff
        self.sessionId = sessionId
        self.createdAt = createdAt
    }
}

// MARK: - Connection State

enum ConnectionState: Sendable, Equatable {
    case disconnected
    case scanning
    case connecting
    case connected
    case error(String)
}

// MARK: - Flipper Device

struct FlipperDevice: Sendable, Identifiable, Equatable {
    let id: String
    let name: String
    var rssi: Int
    var isConnected: Bool

    init(
        id: String,
        name: String,
        rssi: Int = 0,
        isConnected: Bool = false
    ) {
        self.id = id
        self.name = name
        self.rssi = rssi
        self.isConnected = isConnected
    }
}

// MARK: - Protected Paths

/// Protected path patterns that require elevated permissions.
enum ProtectedPaths {
    static let systemPaths: [String] = [
        "/int/",
        "/int/.region",
        "/int/manifest.txt",
        "/ext/.region"
    ]

    static let firmwarePaths: [String] = [
        "/int/update/",
        "/ext/update/"
    ]

    static let sensitiveExtensions: [String] = [
        ".key",
        ".priv",
        ".secret"
    ]

    static func isProtected(_ path: String) -> Bool {
        systemPaths.contains(where: { path.hasPrefix($0) }) ||
        firmwarePaths.contains(where: { path.hasPrefix($0) }) ||
        sensitiveExtensions.contains(where: { path.hasSuffix($0) })
    }

    static func isSystemPath(_ path: String) -> Bool {
        systemPaths.contains(where: { path.hasPrefix($0) })
    }

    static func isFirmwarePath(_ path: String) -> Bool {
        firmwarePaths.contains(where: { path.hasPrefix($0) })
    }
}
