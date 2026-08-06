import Foundation

public enum CommandAction: String, Codable, CaseIterable, Sendable {
    case listDirectory = "list_directory"
    case readFile = "read_file"
    case writeFile = "write_file"
    case createDirectory = "create_directory"
    case delete
    case move
    case rename
    case copy
    case getDeviceInfo = "get_device_info"
    case getStorageInfo = "get_storage_info"
    case executeCLI = "execute_cli"
    case pushArtifact = "push_artifact"
    case launchApp = "launch_app"
    case subghzTransmit = "subghz_transmit"
    case irTransmit = "ir_transmit"
    case nfcEmulate = "nfc_emulate"
    case rfidEmulate = "rfid_emulate"
    case ibuttonEmulate = "ibutton_emulate"
    case badusbExecute = "badusb_execute"
    case bleSpam = "ble_spam"
    case ledControl = "led_control"
    case vibroControl = "vibro_control"

    // Reserved for parity, but never advertised by the alpha tool schema.
    case searchFapHub = "search_faphub"
    case installFapHubApp = "install_faphub_app"
    case forgePayload = "forge_payload"
    case searchResources = "search_resources"
    case listVault = "list_vault"
    case runRunbook = "run_runbook"
    case browseRepo = "browse_repo"
    case downloadResource = "download_resource"
    case githubSearch = "github_search"
    case requestPhoto = "request_photo"

    public static let alphaEnabled: Set<Self> = [
        .listDirectory, .readFile, .writeFile, .createDirectory, .delete,
        .move, .rename, .copy, .getDeviceInfo, .getStorageInfo,
        .executeCLI, .pushArtifact, .launchApp, .subghzTransmit,
        .irTransmit, .nfcEmulate, .rfidEmulate, .ibuttonEmulate,
        .badusbExecute, .bleSpam, .ledControl, .vibroControl
    ]
}

public struct CommandArgs: Codable, Equatable, Sendable {
    public var command: String?
    public var path: String?
    public var destinationPath: String?
    public var content: String?
    public var newName: String?
    public var recursive: Bool
    public var artifactType: String?
    public var artifactData: String?
    public var prompt: String?
    public var payloadType: String?
    public var payloadSpec: String?
    public var signalFile: String?
    public var script: String?
    public var color: String?
    public var state: Bool?
    public var query: String?
    public var appID: String?
    public var repoURL: String?
    public var resourceURL: String?
    public var appName: String?
    public var appArgs: String?
    public var frequency: Int64?
    public var `protocol`: String?
    public var address: String?
    public var signalName: String?
    public var enabled: Bool?
    public var red: Int?
    public var green: Int?
    public var blue: Int?

    public init(
        command: String? = nil,
        path: String? = nil,
        destinationPath: String? = nil,
        content: String? = nil,
        newName: String? = nil,
        recursive: Bool = false,
        artifactType: String? = nil,
        artifactData: String? = nil,
        prompt: String? = nil,
        payloadType: String? = nil,
        payloadSpec: String? = nil,
        signalFile: String? = nil,
        script: String? = nil,
        color: String? = nil,
        state: Bool? = nil,
        query: String? = nil,
        appID: String? = nil,
        repoURL: String? = nil,
        resourceURL: String? = nil,
        appName: String? = nil,
        appArgs: String? = nil,
        frequency: Int64? = nil,
        protocol: String? = nil,
        address: String? = nil,
        signalName: String? = nil,
        enabled: Bool? = nil,
        red: Int? = nil,
        green: Int? = nil,
        blue: Int? = nil
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
        self.payloadType = payloadType
        self.payloadSpec = payloadSpec
        self.signalFile = signalFile
        self.script = script
        self.color = color
        self.state = state
        self.query = query
        self.appID = appID
        self.repoURL = repoURL
        self.resourceURL = resourceURL
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
    }

    enum CodingKeys: String, CodingKey {
        case command, path, content, recursive, prompt, frequency, `protocol`, address
        case destinationPath = "destination_path"
        case newName = "new_name"
        case artifactType = "artifact_type"
        case artifactData = "artifact_data"
        case payloadType = "payload_type"
        case payloadSpec = "payload_spec"
        case signalFile = "signal_file"
        case script, color, state, query
        case appID = "app_id"
        case repoURL = "repo_url"
        case resourceURL = "resource_url"
        case appName = "app_name"
        case appArgs = "app_args"
        case signalName = "signal_name"
        case enabled, red, green, blue
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        command = try values.decodeIfPresent(String.self, forKey: .command)
        path = try values.decodeIfPresent(String.self, forKey: .path)
        destinationPath = try values.decodeIfPresent(String.self, forKey: .destinationPath)
        content = try values.decodeIfPresent(String.self, forKey: .content)
        newName = try values.decodeIfPresent(String.self, forKey: .newName)
        recursive = try values.decodeIfPresent(Bool.self, forKey: .recursive) ?? false
        artifactType = try values.decodeIfPresent(String.self, forKey: .artifactType)
        artifactData = try values.decodeIfPresent(String.self, forKey: .artifactData)
        prompt = try values.decodeIfPresent(String.self, forKey: .prompt)
        payloadType = try values.decodeIfPresent(String.self, forKey: .payloadType)
        payloadSpec = try values.decodeIfPresent(String.self, forKey: .payloadSpec)
        signalFile = try values.decodeIfPresent(String.self, forKey: .signalFile)
        script = try values.decodeIfPresent(String.self, forKey: .script)
        color = try values.decodeIfPresent(String.self, forKey: .color)
        state = try values.decodeIfPresent(Bool.self, forKey: .state)
        query = try values.decodeIfPresent(String.self, forKey: .query)
        appID = try values.decodeIfPresent(String.self, forKey: .appID)
        repoURL = try values.decodeIfPresent(String.self, forKey: .repoURL)
        resourceURL = try values.decodeIfPresent(String.self, forKey: .resourceURL)
        appName = try values.decodeIfPresent(String.self, forKey: .appName)
        appArgs = try values.decodeIfPresent(String.self, forKey: .appArgs)
        frequency = try values.decodeIfPresent(Int64.self, forKey: .frequency)
        `protocol` = try values.decodeIfPresent(String.self, forKey: .protocol)
        address = try values.decodeIfPresent(String.self, forKey: .address)
        signalName = try values.decodeIfPresent(String.self, forKey: .signalName)
        enabled = try values.decodeIfPresent(Bool.self, forKey: .enabled)
        red = try values.decodeIfPresent(Int.self, forKey: .red)
        green = try values.decodeIfPresent(Int.self, forKey: .green)
        blue = try values.decodeIfPresent(Int.self, forKey: .blue)
    }
}

public struct ExecuteCommand: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public var action: CommandAction
    public var args: CommandArgs
    public var justification: String
    public var expectedEffect: String

    public init(
        id: UUID = UUID(),
        action: CommandAction,
        args: CommandArgs = .init(),
        justification: String,
        expectedEffect: String
    ) {
        self.id = id
        self.action = action
        self.args = args
        self.justification = justification
        self.expectedEffect = expectedEffect
    }

    enum CodingKeys: String, CodingKey {
        case action, args, justification
        case expectedEffect = "expected_effect"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = UUID()
        action = try values.decode(CommandAction.self, forKey: .action)
        args = try values.decodeIfPresent(CommandArgs.self, forKey: .args) ?? .init()
        justification = try values.decode(String.self, forKey: .justification)
        expectedEffect = try values.decode(String.self, forKey: .expectedEffect)
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(action, forKey: .action)
        try values.encode(args, forKey: .args)
        try values.encode(justification, forKey: .justification)
        try values.encode(expectedEffect, forKey: .expectedEffect)
    }
}

public struct FileEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String { path }
    public let name: String
    public let path: String
    public let isDirectory: Bool
    public let size: UInt64
    public let modifiedTimestamp: Date?

    public init(name: String, path: String, isDirectory: Bool, size: UInt64 = 0, modifiedTimestamp: Date? = nil) {
        self.name = name
        self.path = path
        self.isDirectory = isDirectory
        self.size = size
        self.modifiedTimestamp = modifiedTimestamp
    }

    enum CodingKeys: String, CodingKey {
        case name, path, size
        case isDirectory = "is_directory"
        case modifiedTimestamp = "modified_timestamp"
    }
}

public struct DeviceInfo: Codable, Equatable, Sendable {
    public let name: String
    public let firmwareVersion: String
    public let hardwareVersion: String
    public let batteryLevel: Int
    public let isCharging: Bool

    public init(name: String, firmwareVersion: String, hardwareVersion: String, batteryLevel: Int, isCharging: Bool) {
        self.name = name
        self.firmwareVersion = firmwareVersion
        self.hardwareVersion = hardwareVersion
        self.batteryLevel = batteryLevel
        self.isCharging = isCharging
    }
}

public struct StorageInfo: Codable, Equatable, Sendable {
    public let internalTotal: UInt64
    public let internalFree: UInt64
    public let externalTotal: UInt64?
    public let externalFree: UInt64?
    public let hasSDCard: Bool

    public init(internalTotal: UInt64, internalFree: UInt64, externalTotal: UInt64?, externalFree: UInt64?, hasSDCard: Bool) {
        self.internalTotal = internalTotal
        self.internalFree = internalFree
        self.externalTotal = externalTotal
        self.externalFree = externalFree
        self.hasSDCard = hasSDCard
    }
}

public struct FileDiff: Codable, Equatable, Sendable {
    public let originalContent: String?
    public let newContent: String
    public let linesAdded: Int
    public let linesRemoved: Int
    public let unifiedDiff: String
}

public struct CommandResultData: Codable, Equatable, Sendable {
    public var entries: [FileEntry]?
    public var content: String?
    public var bytesWritten: UInt64?
    public var deviceInfo: DeviceInfo?
    public var storageInfo: StorageInfo?
    public var diff: FileDiff?
    public var message: String?

    public init(entries: [FileEntry]? = nil, content: String? = nil, bytesWritten: UInt64? = nil, deviceInfo: DeviceInfo? = nil, storageInfo: StorageInfo? = nil, diff: FileDiff? = nil, message: String? = nil) {
        self.entries = entries
        self.content = content
        self.bytesWritten = bytesWritten
        self.deviceInfo = deviceInfo
        self.storageInfo = storageInfo
        self.diff = diff
        self.message = message
    }
}

public struct CommandResult: Codable, Equatable, Sendable {
    public let success: Bool
    public let action: CommandAction
    public let data: CommandResultData?
    public let error: String?
    public let executionTimeMS: UInt64
    public let requiresConfirmation: Bool
    public let pendingApprovalID: UUID?

    public init(success: Bool, action: CommandAction, data: CommandResultData? = nil, error: String? = nil, executionTimeMS: UInt64 = 0, requiresConfirmation: Bool = false, pendingApprovalID: UUID? = nil) {
        self.success = success
        self.action = action
        self.data = data
        self.error = error
        self.executionTimeMS = executionTimeMS
        self.requiresConfirmation = requiresConfirmation
        self.pendingApprovalID = pendingApprovalID
    }
}

public enum RiskLevel: String, Codable, CaseIterable, Sendable {
    case low, medium, high, blocked
}

public struct RiskAssessment: Codable, Equatable, Sendable {
    public let level: RiskLevel
    public let reason: String
    public let affectedPaths: [String]
    public let requiresDiff: Bool
    public let requiresConfirmation: Bool
    public let blockedReason: String?
}

public struct PermissionGrant: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let pathPrefix: String
    public let expiresAt: Date
    public let unlocksProtectedPath: Bool

    public init(id: UUID = UUID(), pathPrefix: String, expiresAt: Date, unlocksProtectedPath: Bool = false) {
        self.id = id
        self.pathPrefix = pathPrefix
        self.expiresAt = expiresAt
        self.unlocksProtectedPath = unlocksProtectedPath
    }

    public func covers(_ path: String, now: Date = .now) -> Bool {
        expiresAt > now && path.hasPrefix(pathPrefix)
    }
}

public struct PendingApproval: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let command: ExecuteCommand
    public let assessment: RiskAssessment
    public let diff: FileDiff?
    public let createdAt: Date
    public let expiresAt: Date

    public init(id: UUID = UUID(), command: ExecuteCommand, assessment: RiskAssessment, diff: FileDiff?, createdAt: Date = .now, expiresAt: Date) {
        self.id = id
        self.command = command
        self.assessment = assessment
        self.diff = diff
        self.createdAt = createdAt
        self.expiresAt = expiresAt
    }
}

public struct AuditEvent: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let timestamp: Date
    public let kind: String
    public let command: ExecuteCommand?
    public let result: CommandResult?
    public let riskLevel: RiskLevel?
    public let detail: String?

    public init(id: UUID = UUID(), timestamp: Date = .now, kind: String, command: ExecuteCommand? = nil, result: CommandResult? = nil, riskLevel: RiskLevel? = nil, detail: String? = nil) {
        self.id = id
        self.timestamp = timestamp
        self.kind = kind
        self.command = command
        self.result = result
        self.riskLevel = riskLevel
        self.detail = detail
    }
}
