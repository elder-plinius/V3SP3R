// VesperAgent.swift
// Vesper - AI-powered Flipper Zero controller
// Main AI conversation orchestrator

import Foundation
import Observation

// MARK: - Chat Session Summary

struct ChatSessionSummary: Identifiable, Sendable {
    let id: String
    let createdAt: Date
    let deviceName: String?
    let messageCount: Int
    let lastMessage: String?
}

// MARK: - Protocol Stubs
// These protocols define the interfaces that concrete implementations must provide.
// They allow VesperAgent to function without coupling to specific BLE/storage implementations.

/// Executes commands against the Flipper Zero device.
protocol CommandExecutor: Sendable {
    func execute(_ command: ExecuteCommand, sessionId: String) async -> CommandResult
    func approve(_ approvalId: String, sessionId: String) async -> CommandResult
    func reject(_ approvalId: String, sessionId: String) async -> CommandResult
    func getPendingApproval(_ approvalId: String) -> PendingApproval?
}

/// Audit logging service for security tracking.
protocol AuditService: Sendable {
    func startSession(_ deviceName: String?)
    func endSession()
    func log(_ entry: AuditEntry)
}

/// Persistence layer for chat sessions.
protocol ChatStore: Sendable {
    func saveMessages(_ messages: [ChatMessage], sessionId: String) async
    func loadMessages(sessionId: String) async -> [ChatMessage]
    func deleteSession(_ sessionId: String) async
    func listSessions() async -> [ChatSessionSummary]
}

/// Validates commands before execution.
enum InputValidator {
    /// Validate an ExecuteCommand for safety and correctness.
    static func validate(_ command: ExecuteCommand) -> (isValid: Bool, error: String?) {
        // Check for blocked paths
        if let path = command.args.path {
            if ProtectedPaths.isProtected(path) {
                return (false, "Path '\(path)' is protected. Unlock in Settings to proceed.")
            }
        }

        if let destPath = command.args.destinationPath {
            if ProtectedPaths.isSystemPath(destPath) {
                return (false, "Destination path '\(destPath)' is in protected system storage.")
            }
        }

        // Validate action-specific requirements
        switch command.action {
        case .readFile, .delete, .listDirectory, .createDirectory:
            if command.args.path == nil || command.args.path?.isEmpty == true {
                return (false, "Action '\(command.action.rawValue)' requires a 'path' argument.")
            }
        case .writeFile:
            if command.args.path == nil || command.args.path?.isEmpty == true {
                return (false, "Action 'write_file' requires a 'path' argument.")
            }
            // content can be empty for creating empty files
        case .move, .copy:
            if command.args.path == nil || command.args.destinationPath == nil {
                return (false, "Action '\(command.action.rawValue)' requires both 'path' and 'destination_path'.")
            }
        case .rename:
            if command.args.path == nil || command.args.newName == nil {
                return (false, "Action 'rename' requires 'path' and 'new_name' arguments.")
            }
        case .subghzTransmit, .irTransmit, .nfcEmulate, .rfidEmulate, .ibuttonEmulate, .badusbExecute:
            if command.args.path == nil || command.args.path?.isEmpty == true {
                return (false, "Action '\(command.action.rawValue)' requires a file 'path'.")
            }
        case .launchApp:
            if (command.args.appName == nil || command.args.appName?.isEmpty == true) &&
               (command.args.command == nil || command.args.command?.isEmpty == true) {
                return (false, "Action 'launch_app' requires 'app_name'.")
            }
        case .forgePayload:
            if command.args.prompt == nil || command.args.prompt?.isEmpty == true {
                return (false, "Action 'forge_payload' requires a 'prompt' describing what to create.")
            }
        default:
            break
        }

        return (true, nil)
    }
}

// MARK: - Vesper Agent

/// Main AI conversation orchestrator.
/// Manages the conversation loop, tool execution, approval flow, and session persistence.
@Observable
final class VesperAgent {

    private let openRouterClient: OpenRouterClient
    private let commandExecutor: CommandExecutor
    private let auditService: AuditService
    private let chatStore: ChatStore
    private let settingsStore: SettingsStore

    /// Current conversation state, observed by SwiftUI views.
    var conversationState = ConversationState()

    /// Callback for capturing photos via smart glasses.
    /// Set by the glasses integration layer when connected.
    var photoCaptureCallback: ((String, TimeInterval) async -> String?)? = nil

    private var currentSessionId = UUID().uuidString

    /// Maximum tool-call loop iterations before bailing out.
    private let maxIterations = 10

    // MARK: - Init

    init(
        openRouterClient: OpenRouterClient,
        commandExecutor: CommandExecutor,
        auditService: AuditService,
        chatStore: ChatStore,
        settingsStore: SettingsStore
    ) {
        self.openRouterClient = openRouterClient
        self.commandExecutor = commandExecutor
        self.auditService = auditService
        self.chatStore = chatStore
        self.settingsStore = settingsStore
    }

    // MARK: - Session Management

    /// Start a fresh conversation session.
    func startNewSession(deviceName: String? = nil) {
        currentSessionId = UUID().uuidString
        conversationState = ConversationState(sessionId: currentSessionId)
        auditService.startSession(deviceName)
    }

    /// Send a user message and process the AI response loop.
    func sendMessage(_ userMessage: String, imageAttachments: [ImageAttachment]? = nil) async {
        var messages = conversationState.messages

        // Add user message
        let userChatMessage = ChatMessage(
            role: .user,
            content: userMessage,
            imageAttachments: imageAttachments
        )
        messages.append(userChatMessage)

        conversationState.messages = messages
        conversationState.isLoading = true
        conversationState.error = nil
        conversationState.progress = AgentProgress(
            stage: .modelRequest,
            detail: "Planning next action..."
        )

        do {
            await processAIResponse(&messages)
        }

        // Persist after completion
        await chatStore.saveMessages(conversationState.messages, sessionId: currentSessionId)
    }

    /// Retry the last failed exchange by rolling back to the last safe point.
    func retryLastMessage() async {
        guard !conversationState.isLoading else { return }

        var messages = conversationState.messages

        // Find safe rollback point: last user message or last successful tool result
        var cutIndex = messages.count
        for i in messages.indices.reversed() {
            let msg = messages[i]
            if msg.role == .user {
                cutIndex = i + 1
                break
            }
            if msg.role == .tool {
                cutIndex = i + 1
                break
            }
        }

        if cutIndex < messages.count {
            messages = Array(messages.prefix(cutIndex))
        }

        guard !messages.isEmpty else { return }

        conversationState.messages = messages
        conversationState.isLoading = true
        conversationState.error = nil
        conversationState.progress = AgentProgress(
            stage: .modelRequest,
            detail: "Retrying..."
        )

        await processAIResponse(&messages)
        await chatStore.saveMessages(conversationState.messages, sessionId: currentSessionId)
    }

    /// Continue after the user approves or rejects a pending action.
    func continueAfterApproval(approvalId: String, approved: Bool) async {
        conversationState.isLoading = true
        conversationState.progress = AgentProgress(
            stage: .toolExecution,
            detail: approved ? "Applying approved action..." : "Rejecting action..."
        )

        let result: CommandResult
        if approved {
            result = await commandExecutor.approve(approvalId, sessionId: currentSessionId)
        } else {
            result = await commandExecutor.reject(approvalId, sessionId: currentSessionId)
        }

        var messages = conversationState.messages

        // Find the tool call that was pending and add its result
        let toolCallId = messages.last(where: { $0.toolCalls != nil })?.toolCalls?.first?.id ?? approvalId

        let toolResultMessage = ChatMessage(
            role: .tool,
            content: "",
            toolResults: [
                ToolResult(
                    toolCallId: toolCallId,
                    content: openRouterClient.formatResult(result)
                )
            ]
        )
        messages.append(toolResultMessage)

        conversationState.messages = messages
        conversationState.progress = AgentProgress(
            stage: .modelRequest,
            detail: "Summarizing result..."
        )

        await processAIResponse(&messages)
        await chatStore.saveMessages(conversationState.messages, sessionId: currentSessionId)
    }

    /// Load a previously saved session.
    func loadSession(_ sessionId: String) async {
        let messages = await chatStore.loadMessages(sessionId: sessionId)
        guard !messages.isEmpty else { return }

        currentSessionId = sessionId
        conversationState = ConversationState(
            messages: messages,
            sessionId: sessionId
        )
    }

    /// Delete a saved session.
    func deleteSession(_ sessionId: String) async {
        await chatStore.deleteSession(sessionId)
        if sessionId == currentSessionId {
            startNewSession()
        }
    }

    /// List all saved chat sessions.
    func listSessions() async -> [ChatSessionSummary] {
        return await chatStore.listSessions()
    }

    // MARK: - AI Response Loop

    private func processAIResponse(_ messages: inout [ChatMessage]) async {
        var iterations = 0

        while iterations < maxIterations {
            iterations += 1

            conversationState.messages = messages
            conversationState.isLoading = true
            conversationState.error = nil
            conversationState.progress = AgentProgress(
                stage: .modelRequest,
                detail: "Contacting model (\(iterations)/\(maxIterations))..."
            )

            // Log AI request
            auditService.log(AuditEntry(
                actionType: .commandReceived,
                sessionId: currentSessionId,
                metadata: ["message_count": "\(messages.count)"]
            ))

            // Call OpenRouter API
            let result = await openRouterClient.chat(
                messages: messages,
                sessionId: currentSessionId
            )

            switch result {
            case .error(let errorMessage):
                conversationState.isLoading = false
                conversationState.progress = nil
                conversationState.error = errorMessage
                return

            case .success(let response):
                // No tool calls: final text response
                if response.toolCalls == nil || response.toolCalls?.isEmpty == true {
                    let assistantMessage = ChatMessage(
                        role: .assistant,
                        content: response.content ?? ""
                    )
                    messages.append(assistantMessage)

                    conversationState.messages = messages
                    conversationState.isLoading = false
                    conversationState.progress = nil
                    return
                }

                // Has tool calls: process them
                guard let toolCalls = response.toolCalls else { continue }

                let assistantMessage = ChatMessage(
                    role: .assistant,
                    content: response.content ?? "",
                    toolCalls: toolCalls
                )
                messages.append(assistantMessage)

                conversationState.messages = messages
                conversationState.progress = AgentProgress(
                    stage: .toolExecution,
                    detail: "Running \(toolCalls.count) tool call(s)..."
                )

                // Execute each tool call
                var toolResults: [ToolResult] = []

                for toolCall in toolCalls {
                    // Only handle execute_command
                    guard toolCall.name == "execute_command" else {
                        toolResults.append(ToolResult(
                            toolCallId: toolCall.id,
                            content: """
                            {"success": false, "error": "Unknown tool: \(toolCall.name)"}
                            """
                        ))
                        continue
                    }

                    // Parse command from arguments
                    let parsed = openRouterClient.parseCommand(from: toolCall.arguments)
                    guard let command = parsed.command else {
                        let parseError = parsed.error ?? "Invalid command format."
                        toolResults.append(ToolResult(
                            toolCallId: toolCall.id,
                            content: """
                            {"success": false, "error": "\(parseError)"}
                            """
                        ))
                        continue
                    }

                    // Validate command
                    let validation = InputValidator.validate(command)
                    if !validation.isValid {
                        toolResults.append(ToolResult(
                            toolCallId: toolCall.id,
                            content: """
                            {"success": false, "error": "\(validation.error ?? "Validation failed")"}
                            """
                        ))
                        auditService.log(AuditEntry(
                            actionType: .commandBlocked,
                            command: command,
                            riskLevel: .blocked,
                            sessionId: currentSessionId,
                            metadata: ["reason": validation.error ?? "validation"]
                        ))
                        continue
                    }

                    conversationState.progress = AgentProgress(
                        stage: .toolExecution,
                        detail: "Executing \(command.action.rawValue.replacingOccurrences(of: "_", with: " "))..."
                    )

                    // Intercept request_photo: handled by glasses callback, not CommandExecutor
                    if command.action == .requestPhoto {
                        let photoResult = await handlePhotoRequest(command: command, toolCallId: toolCall.id)
                        toolResults.append(photoResult)
                        continue
                    }

                    // Execute command
                    let commandResult = await commandExecutor.execute(command, sessionId: currentSessionId)

                    // Check if approval is required
                    if commandResult.requiresConfirmation, let approvalId = commandResult.pendingApprovalId {
                        if let pendingApproval = commandExecutor.getPendingApproval(approvalId) {
                            conversationState.messages = messages
                            conversationState.isLoading = false
                            conversationState.progress = AgentProgress(
                                stage: .approval,
                                detail: "Approval required to continue."
                            )
                            // Store pending state -- the UI will call continueAfterApproval
                            return
                        }
                    }

                    // Log result
                    auditService.log(AuditEntry(
                        actionType: commandResult.success ? .commandExecuted : .commandFailed,
                        command: command,
                        result: commandResult,
                        sessionId: currentSessionId
                    ))

                    toolResults.append(ToolResult(
                        toolCallId: toolCall.id,
                        content: openRouterClient.formatResult(commandResult)
                    ))

                    conversationState.progress = AgentProgress(
                        stage: .toolExecution,
                        detail: commandResult.success
                            ? "Executed \(command.action.rawValue.replacingOccurrences(of: "_", with: " "))."
                            : "Failed: \(commandResult.error?.prefix(80) ?? "unknown")"
                    )
                }

                // Add tool results as a new message and loop back for AI summary
                let toolMessage = ChatMessage(
                    role: .tool,
                    content: "",
                    toolResults: toolResults
                )
                messages.append(toolMessage)

                conversationState.messages = messages
                conversationState.progress = AgentProgress(
                    stage: .modelRequest,
                    detail: "Summarizing tool result..."
                )

                // Continue the while loop to get next AI response
            }
        }

        // Max iterations reached
        conversationState.messages = messages
        conversationState.isLoading = false
        conversationState.progress = nil
        conversationState.error = "Maximum iterations reached (\(maxIterations)). The AI agent loop exceeded the safety limit."
    }

    // MARK: - Photo Handling

    private func handlePhotoRequest(command: ExecuteCommand, toolCallId: String) async -> ToolResult {
        let photoPrompt = command.args.photoPrompt
            ?? command.args.prompt
            ?? "Describe what you see in detail"

        guard let callback = photoCaptureCallback else {
            return ToolResult(
                toolCallId: toolCallId,
                content: """
                {"success": false, "error": "Smart glasses are not connected. Cannot capture photo. Ask the user to describe what they see instead."}
                """
            )
        }

        conversationState.progress = AgentProgress(
            stage: .toolExecution,
            detail: "Capturing photo from glasses..."
        )

        let description = await callback(photoPrompt, 15.0)

        if let description = description {
            return ToolResult(
                toolCallId: toolCallId,
                content: """
                {"success": true, "data": {"description": "\(description.replacingOccurrences(of: "\"", with: "\\\""))"}}
                """
            )
        } else {
            return ToolResult(
                toolCallId: toolCallId,
                content: """
                {"success": false, "error": "Photo capture failed or timed out. Ask the user to describe what they see."}
                """
            )
        }
    }
}
