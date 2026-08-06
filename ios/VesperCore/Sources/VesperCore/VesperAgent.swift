import Foundation

public struct AgentSnapshot: Equatable, Sendable {
    public var messages: [AgentMessage]
    public var isLoading: Bool
    public var status: String?
    public var pendingApproval: PendingApproval?
    public var error: String?

    public init(messages: [AgentMessage] = [], isLoading: Bool = false, status: String? = nil, pendingApproval: PendingApproval? = nil, error: String? = nil) {
        self.messages = messages
        self.isLoading = isLoading
        self.status = status
        self.pendingApproval = pendingApproval
        self.error = error
    }
}

public actor VesperAgent {
    private let llm: any LLMClient
    private let executor: CommandExecutor
    private let parser: CommandParser
    private let maxIterations: Int
    private var snapshot = AgentSnapshot()
    private var pausedToolCallID: String?
    private var completedToolResults: [String: CommandResult] = [:]
    private var isActive = true

    public init(llm: any LLMClient, executor: CommandExecutor, parser: CommandParser = .init(), maxIterations: Int = 8) {
        self.llm = llm
        self.executor = executor
        self.parser = parser
        self.maxIterations = min(20, max(1, maxIterations))
    }

    public func state() -> AgentSnapshot { snapshot }

    public func restore(messages: [AgentMessage]) { snapshot.messages = messages }
    public func clear() { snapshot = AgentSnapshot(); pausedToolCallID = nil; completedToolResults.removeAll() }

    public func send(_ text: String, images: [AgentImage] = []) async -> AgentSnapshot {
        guard isActive, !snapshot.isLoading else { return snapshot }
        snapshot.messages.append(AgentMessage(role: .user, content: text, images: images))
        snapshot.isLoading = true
        snapshot.error = nil
        return await runLoop()
    }

    public func decideApproval(id: UUID, approved: Bool) async -> AgentSnapshot {
        guard let toolCallID = pausedToolCallID else { return snapshot }
        snapshot.pendingApproval = nil
        snapshot.isLoading = true
        snapshot.status = approved ? "Applying approved action…" : "Rejecting action…"
        let result = approved ? await executor.approve(id) : await executor.reject(id)
        completedToolResults[toolCallID] = result
        appendToolResult(result, callID: toolCallID)
        pausedToolCallID = nil
        return await runLoop()
    }

    public func appDidEnterBackground() async {
        isActive = false
        await executor.cancelPendingForBackground()
        snapshot.isLoading = false
        snapshot.pendingApproval = nil
        snapshot.status = nil
        pausedToolCallID = nil
    }

    public func appDidBecomeActive() { isActive = true }

    private func runLoop() async -> AgentSnapshot {
        for _ in 0..<maxIterations {
            guard isActive else { snapshot.isLoading = false; snapshot.status = nil; return snapshot }
            do {
                snapshot.status = "Planning next action…"
                let response = try await llm.complete(messages: apiMessages, tools: [.executeCommandAlpha])
                guard isActive else { snapshot.isLoading = false; snapshot.status = nil; return snapshot }
                let assistant = AgentMessage(role: .assistant, content: response.content, toolCalls: response.toolCalls)
                snapshot.messages.append(assistant)
                guard !response.toolCalls.isEmpty else {
                    snapshot.isLoading = false
                    snapshot.status = nil
                    return snapshot
                }

                for call in response.toolCalls {
                    guard call.name == "execute_command" else {
                        appendToolError("Unknown tool: \(call.name)", callID: call.id)
                        continue
                    }
                    if let completed = completedToolResults[call.id] {
                        appendToolResult(completed, callID: call.id)
                        continue
                    }
                    let command: ExecuteCommand
                    do { command = try parser.parse(call.arguments) }
                    catch { appendToolError(error.localizedDescription, callID: call.id); continue }
                    guard isActive else { snapshot.isLoading = false; snapshot.status = nil; return snapshot }
                    snapshot.status = "Executing \(command.action.rawValue)…"
                    let result = await executor.execute(command)
                    if result.requiresConfirmation, let id = result.pendingApprovalID,
                       let approval = await executor.pendingApproval(), approval.id == id {
                        snapshot.pendingApproval = approval
                        snapshot.isLoading = false
                        snapshot.status = "Approval required"
                        pausedToolCallID = call.id
                        return snapshot
                    }
                    completedToolResults[call.id] = result
                    appendToolResult(result, callID: call.id)
                }
            } catch {
                snapshot.isLoading = false
                snapshot.status = nil
                snapshot.error = error.localizedDescription
                return snapshot
            }
        }
        snapshot.isLoading = false
        snapshot.status = nil
        snapshot.error = "Maximum tool iterations reached"
        return snapshot
    }

    private var apiMessages: [AgentMessage] {
        [AgentMessage(role: .system, content: Self.systemPrompt)] + snapshot.messages
    }

    private func appendToolResult(_ result: CommandResult, callID: String) {
        let data = try? JSONEncoder().encode(result)
        snapshot.messages.append(AgentMessage(role: .tool, content: data.flatMap { String(data: $0, encoding: .utf8) } ?? "{\"success\":false}", toolCallID: callID))
    }

    private func appendToolError(_ message: String, callID: String) {
        let escaped = message.replacingOccurrences(of: "\"", with: "\\\"")
        snapshot.messages.append(AgentMessage(role: .tool, content: "{\"success\":false,\"error\":\"\(escaped)\"}", toolCallID: callID))
    }

    private static let systemPrompt = """
    You are Vesper, an assistant that controls a user's Flipper Zero only through execute_command.
    Use only actions present in the tool schema. Never claim an action succeeded until the tool result confirms it.
    Prefer read-only inspection before mutation. Respect authorization, safety, and all user confirmation results.
    """
}

public extension LLMToolDefinition {
    static let executeCommandAlpha = LLMToolDefinition(
        name: "execute_command",
        description: "Execute an authorized operation on the connected Flipper Zero. iOS independently enforces risk and approval.",
        parameters: [
            "type": .string("object"),
            "properties": .object([
                "action": .object([
                    "type": .string("string"),
                    "enum": .array(CommandAction.alphaEnabled.sorted { $0.rawValue < $1.rawValue }.map { .string($0.rawValue) })
                ]),
                "args": .object(["type": .string("object")]),
                "justification": .object(["type": .string("string")]),
                "expected_effect": .object(["type": .string("string")])
            ]),
            "required": .array(["action", "args", "justification", "expected_effect"].map(JSONValue.string))
        ]
    )
}
