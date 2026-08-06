import Foundation

public enum TransportConnectionState: Equatable, Sendable {
    case idle
    case scanning
    case connecting(name: String)
    case connected(name: String)
    case disconnected(reason: String?)
    case failed(message: String)
}

public struct DiscoveredFlipper: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let name: String
    public let signalStrength: Int

    public init(id: UUID, name: String, signalStrength: Int) {
        self.id = id
        self.name = name
        self.signalStrength = signalStrength
    }
}

public protocol FlipperTransport: Sendable {
    func connectionStates() async -> AsyncStream<TransportConnectionState>
    func discoveredDevices() async -> AsyncStream<[DiscoveredFlipper]>
    func receivedBytes() async -> AsyncStream<Data>
    func scan() async throws
    func stopScan() async
    func connect(to id: UUID) async throws
    func disconnect() async
    func send(_ data: Data) async throws
}

public protocol FlipperRPCClient: Sendable {
    func getDeviceInfo() async throws -> DeviceInfo
    func getStorageInfo() async throws -> StorageInfo
    func listDirectory(_ path: String) async throws -> [FileEntry]
    func readFile(_ path: String) async throws -> Data
    @discardableResult func writeFile(_ path: String, data: Data) async throws -> UInt64
    func createDirectory(_ path: String) async throws
    func delete(_ path: String, recursive: Bool) async throws
    func move(_ source: String, to destination: String) async throws
    func copy(_ source: String, to destination: String) async throws
    func executeCLI(_ command: String) async throws -> String
    func diagnostics() async -> String
}

public protocol AuditRecording: Sendable {
    func record(_ event: AuditEvent) async
}

public actor MemoryAuditRecorder: AuditRecording {
    public private(set) var events: [AuditEvent] = []
    public init() {}
    public func record(_ event: AuditEvent) { events.append(event) }
}

public protocol LLMClient: Sendable {
    func complete(messages: [AgentMessage], tools: [LLMToolDefinition]) async throws -> LLMResponse
}

public struct AgentImage: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let mimeType: String
    public let base64Data: String
    public init(id: UUID = UUID(), mimeType: String, base64Data: String) {
        self.id = id
        self.mimeType = mimeType
        self.base64Data = base64Data
    }
}

public enum AgentRole: String, Codable, Sendable { case system, user, assistant, tool }

public struct AgentMessage: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public var role: AgentRole
    public var content: String
    public var images: [AgentImage]
    public var toolCalls: [LLMToolCall]
    public var toolCallID: String?

    public init(id: UUID = UUID(), role: AgentRole, content: String, images: [AgentImage] = [], toolCalls: [LLMToolCall] = [], toolCallID: String? = nil) {
        self.id = id
        self.role = role
        self.content = content
        self.images = images
        self.toolCalls = toolCalls
        self.toolCallID = toolCallID
    }
}

public struct LLMToolCall: Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let arguments: String
    public init(id: String, name: String, arguments: String) {
        self.id = id
        self.name = name
        self.arguments = arguments
    }
}

public struct LLMResponse: Equatable, Sendable {
    public let content: String
    public let toolCalls: [LLMToolCall]
    public let model: String
    public init(content: String, toolCalls: [LLMToolCall] = [], model: String) {
        self.content = content
        self.toolCalls = toolCalls
        self.model = model
    }
}

public struct LLMToolDefinition: Codable, Equatable, Sendable {
    public let name: String
    public let description: String
    public let parameters: [String: JSONValue]
    public init(name: String, description: String, parameters: [String: JSONValue]) {
        self.name = name
        self.description = description
        self.parameters = parameters
    }
}

public enum JSONValue: Codable, Equatable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let box = try decoder.singleValueContainer()
        if box.decodeNil() { self = .null }
        else if let value = try? box.decode(Bool.self) { self = .bool(value) }
        else if let value = try? box.decode(Double.self) { self = .number(value) }
        else if let value = try? box.decode(String.self) { self = .string(value) }
        else if let value = try? box.decode([String: JSONValue].self) { self = .object(value) }
        else { self = .array(try box.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.singleValueContainer()
        switch self {
        case .string(let value): try box.encode(value)
        case .number(let value): try box.encode(value)
        case .bool(let value): try box.encode(value)
        case .object(let value): try box.encode(value)
        case .array(let value): try box.encode(value)
        case .null: try box.encodeNil()
        }
    }
}

public enum VesperCoreError: LocalizedError, Equatable, Sendable {
    case invalidCommand(String)
    case unsupportedAction(CommandAction)
    case invalidPath(String)
    case missingArgument(String)
    case approvalExpired
    case approvalNotFound
    case blocked(String)
    case disconnected
    case malformedFrame
    case responseTimeout

    public var errorDescription: String? {
        switch self {
        case .invalidCommand(let message): "Invalid command: \(message)"
        case .unsupportedAction(let action): "Unsupported alpha action: \(action.rawValue)"
        case .invalidPath(let path): "Invalid Flipper path: \(path)"
        case .missingArgument(let name): "Missing required argument: \(name)"
        case .approvalExpired: "Approval expired"
        case .approvalNotFound: "Approval not found"
        case .blocked(let reason): "Blocked: \(reason)"
        case .disconnected: "Flipper is disconnected"
        case .malformedFrame: "Malformed protobuf frame"
        case .responseTimeout: "Flipper response timed out"
        }
    }
}
