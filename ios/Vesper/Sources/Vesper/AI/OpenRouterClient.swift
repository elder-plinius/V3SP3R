// OpenRouterClient.swift
// Vesper - AI-powered Flipper Zero controller
// URLSession-based HTTP client for OpenRouter API

import Foundation

// MARK: - Result Types

enum ChatCompletionResult {
    case success(ChatCompletionResponse)
    case error(String)
}

struct ChatCompletionResponse {
    let content: String?
    let toolCalls: [ToolCall]?
    let model: String
    let usage: TokenUsage?
}

struct TokenUsage {
    let promptTokens: Int
    let completionTokens: Int
    let totalTokens: Int
}

// MARK: - OpenRouter Client

/// URLSession-based HTTP client for the OpenRouter chat completions API.
/// Handles tool-calling with the execute_command interface, rate limiting,
/// retry with exponential backoff, and response validation.
final class OpenRouterClient: @unchecked Sendable {

    private let settingsStore: SettingsStore
    private let secureStorage: SecureStorage
    private let session: URLSession
    private let jsonEncoder: JSONEncoder
    private let jsonDecoder: JSONDecoder

    // Rate limiter: 30 requests per minute
    private let rateLimitQueue = DispatchQueue(label: "com.vesper.ratelimiter")
    private var requestTimestamps: [Date] = []
    private let maxRequests = 30
    private let windowSeconds: TimeInterval = 60

    // Retry config
    private let maxRetries = 2
    private let initialDelayMs: UInt64 = 700
    private let maxDelayMs: UInt64 = 10_000
    private let backoffMultiplier: Double = 2.0

    // Limits
    private let maxContextMessages = 24
    private let maxToolCallsPerResponse = 1
    private let toolCallResponseMaxTokens = 1024

    private static let apiURL = URL(string: "https://openrouter.ai/api/v1/chat/completions")!
    private static let httpReferer = "https://github.com/elder-plinius/V3SP3R"
    private static let appTitle = "Vesper"

    // MARK: - Init

    init(settingsStore: SettingsStore, secureStorage: SecureStorage = SecureStorage()) {
        self.settingsStore = settingsStore
        self.secureStorage = secureStorage

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 75
        config.timeoutIntervalForResource = 120
        self.session = URLSession(configuration: config)

        self.jsonEncoder = JSONEncoder()
        jsonEncoder.keyEncodingStrategy = .convertToSnakeCase

        self.jsonDecoder = JSONDecoder()
        jsonDecoder.keyDecodingStrategy = .convertFromSnakeCase
    }

    // MARK: - Public API

    /// Send a chat completion request with tool calling support.
    /// Returns parsed response with optional tool calls.
    func chat(messages: [ChatMessage], sessionId: String) async -> ChatCompletionResult {
        // Check rate limit
        if !tryAcquireRateLimit() {
            let waitTime = timeUntilRateLimitReset()
            return .error("Rate limit exceeded. Please wait \(Int(waitTime))s before trying again.")
        }

        // Load API key from Keychain
        guard let apiKey = secureStorage.loadAPIKey(), !apiKey.isEmpty else {
            return .error("OpenRouter API key not configured. Go to Settings to add your key.")
        }

        // Validate API key format
        guard isValidApiKeyFormat(apiKey) else {
            return .error("Invalid API key format. OpenRouter keys start with 'sk-or-'.")
        }

        let model = settingsStore.selectedModel

        // Trim conversation to stay within context limits
        let compactMessages = trimConversation(messages)

        // Build system prompt with optional glasses addendum
        let systemPrompt: String
        if settingsStore.glassesEnabled {
            systemPrompt = VesperPrompts.systemPrompt + "\n\n" + VesperPrompts.smartglassesAddendum
        } else {
            systemPrompt = VesperPrompts.systemPrompt
        }

        // Build API request messages
        var requestMessages: [[String: Any]] = [
            ["role": "system", "content": systemPrompt]
        ]
        requestMessages.append(contentsOf: buildRequestMessages(from: compactMessages))

        // Select tool definition based on glasses state
        let tools: [[String: Any]]
        if settingsStore.glassesEnabled {
            tools = VesperPrompts.toolDefinition
        } else {
            tools = VesperPrompts.toolDefinitionWithoutGlasses()
        }

        // Build request body
        let requestBody: [String: Any] = [
            "model": model,
            "messages": requestMessages,
            "tools": tools,
            "tool_choice": "auto",
            "max_tokens": toolCallResponseMaxTokens
        ]

        // Build HTTP request
        guard let httpRequest = buildHTTPRequest(apiKey: apiKey, body: requestBody) else {
            return .error("Failed to build API request")
        }

        // Execute with retry
        return await executeWithRetry(request: httpRequest)
    }

    /// Format a CommandResult as JSON string for sending back to the AI as a tool result.
    func formatResult(_ result: CommandResult) -> String {
        if let data = try? jsonEncoder.encode(result),
           let jsonString = String(data: data, encoding: .utf8) {
            return jsonString
        }
        if result.success {
            return """
            {"success": true, "action": "\(result.action.rawValue)", "data": {"message": "\(result.data?.message ?? "Done")"}}
            """
        } else {
            return """
            {"success": false, "action": "\(result.action.rawValue)", "error": "\(result.error ?? "Unknown error")"}
            """
        }
    }

    /// Parse an ExecuteCommand from tool call arguments JSON.
    func parseCommand(from arguments: String) -> (command: ExecuteCommand?, error: String?) {
        guard let data = arguments.data(using: .utf8) else {
            return (nil, "Invalid UTF-8 in tool arguments")
        }

        // Try direct decoding first
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        if let command = try? decoder.decode(ExecuteCommand.self, from: data) {
            return (command, nil)
        }

        // Fallback: manual JSON parsing for models that produce slightly non-standard output
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return (nil, "Could not parse tool arguments as JSON. Expected: {\"action\":\"...\",\"args\":{...}}")
        }

        // Extract action
        guard let actionString = json["action"] as? String else {
            return (nil, "Missing 'action' field in tool arguments")
        }

        guard let action = CommandAction(rawValue: actionString) else {
            return (nil, "Unknown action: '\(actionString)'. Check spelling and use snake_case.")
        }

        // Extract args
        let argsDict = json["args"] as? [String: Any] ?? [:]

        // Build CommandArgs manually
        let args = CommandArgs(
            command: (argsDict["command"] as? String) ?? (argsDict["query"] as? String) ?? (argsDict["app_id"] as? String),
            path: argsDict["path"] as? String,
            destinationPath: argsDict["destination_path"] as? String,
            content: argsDict["content"] as? String,
            newName: argsDict["new_name"] as? String,
            recursive: argsDict["recursive"] as? Bool ?? false,
            artifactType: argsDict["artifact_type"] as? String,
            artifactData: argsDict["artifact_data"] as? String,
            prompt: argsDict["prompt"] as? String,
            resourceType: argsDict["resource_type"] as? String,
            runbookId: argsDict["runbook_id"] as? String,
            payloadType: argsDict["payload_type"] as? String,
            filter: argsDict["filter"] as? String,
            appName: argsDict["app_name"] as? String,
            appArgs: argsDict["app_args"] as? String,
            frequency: (argsDict["frequency"] as? NSNumber)?.int64Value,
            protocol: argsDict["protocol"] as? String,
            address: argsDict["address"] as? String,
            signalName: argsDict["signal_name"] as? String,
            enabled: argsDict["enabled"] as? Bool,
            red: argsDict["red"] as? Int,
            green: argsDict["green"] as? Int,
            blue: argsDict["blue"] as? Int,
            repoId: argsDict["repo_id"] as? String,
            subPath: argsDict["sub_path"] as? String,
            downloadUrl: argsDict["download_url"] as? String,
            searchScope: argsDict["search_scope"] as? String,
            photoPrompt: argsDict["photo_prompt"] as? String
        )

        let justification = json["justification"] as? String ?? ""
        let expectedEffect = json["expected_effect"] as? String ?? ""

        let command = ExecuteCommand(
            action: action,
            args: args,
            justification: justification,
            expectedEffect: expectedEffect
        )

        return (command, nil)
    }

    // MARK: - Rate Limiting

    private func tryAcquireRateLimit() -> Bool {
        rateLimitQueue.sync {
            let now = Date()
            let windowStart = now.addingTimeInterval(-windowSeconds)
            requestTimestamps.removeAll { $0 < windowStart }
            if requestTimestamps.count >= maxRequests {
                return false
            }
            requestTimestamps.append(now)
            return true
        }
    }

    private func timeUntilRateLimitReset() -> TimeInterval {
        rateLimitQueue.sync {
            guard let oldest = requestTimestamps.first else { return 0 }
            let resetTime = oldest.addingTimeInterval(windowSeconds)
            return max(0, resetTime.timeIntervalSinceNow)
        }
    }

    // MARK: - API Key Validation

    private func isValidApiKeyFormat(_ key: String) -> Bool {
        // OpenRouter keys typically start with "sk-or-" and are at least 20 chars
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count >= 20 && (trimmed.hasPrefix("sk-or-") || trimmed.hasPrefix("sk-"))
    }

    // MARK: - Request Building

    private func buildHTTPRequest(apiKey: String, body: [String: Any]) -> URLRequest? {
        guard let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return nil
        }

        var request = URLRequest(url: Self.apiURL)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.httpReferer, forHTTPHeaderField: "HTTP-Referer")
        request.setValue(Self.appTitle, forHTTPHeaderField: "X-Title")
        request.httpBody = bodyData

        return request
    }

    private func buildRequestMessages(from messages: [ChatMessage]) -> [[String: Any]] {
        var result: [[String: Any]] = []

        for message in messages {
            switch message.role {
            case .user:
                if let attachments = message.imageAttachments, !attachments.isEmpty {
                    // Multimodal message with images
                    var contentParts: [[String: Any]] = []
                    if !message.content.isEmpty {
                        contentParts.append([
                            "type": "text",
                            "text": message.content
                        ])
                    }
                    for attachment in attachments {
                        let base64 = attachment.data.base64EncodedString()
                        contentParts.append([
                            "type": "image_url",
                            "image_url": [
                                "url": "data:\(attachment.mimeType);base64,\(base64)",
                                "detail": "auto"
                            ]
                        ])
                    }
                    result.append([
                        "role": "user",
                        "content": contentParts
                    ])
                } else {
                    result.append([
                        "role": "user",
                        "content": message.content
                    ])
                }

            case .assistant:
                var msg: [String: Any] = [
                    "role": "assistant",
                    "content": message.content
                ]
                if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
                    msg["tool_calls"] = toolCalls.map { tc in
                        [
                            "id": tc.id,
                            "type": "function",
                            "function": [
                                "name": tc.name,
                                "arguments": tc.arguments
                            ]
                        ] as [String: Any]
                    }
                }
                result.append(msg)

            case .tool:
                if let toolResults = message.toolResults {
                    for tr in toolResults {
                        result.append([
                            "role": "tool",
                            "tool_call_id": tr.toolCallId,
                            "content": tr.content
                        ])
                    }
                }

            case .system:
                result.append([
                    "role": "system",
                    "content": message.content
                ])
            }
        }

        return result
    }

    private func trimConversation(_ messages: [ChatMessage]) -> [ChatMessage] {
        if messages.count <= maxContextMessages {
            return messages
        }
        // Keep the first message (if it contains user context) and the last N messages
        let tail = Array(messages.suffix(maxContextMessages))
        return tail
    }

    // MARK: - Request Execution

    private func executeWithRetry(request: URLRequest) async -> ChatCompletionResult {
        var lastError: String?
        var delayMs = initialDelayMs

        for attempt in 0..<(maxRetries + 1) {
            do {
                let (data, response) = try await session.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    lastError = "Invalid response type"
                    continue
                }

                // Rate limit from server
                if httpResponse.statusCode == 429 {
                    let retryAfter = httpResponse.value(forHTTPHeaderField: "Retry-After")
                        .flatMap { UInt64($0) } ?? 60
                    try await Task.sleep(nanoseconds: retryAfter * 1_000_000_000)
                    continue
                }

                // Server errors (5xx) are retryable
                if (500...599).contains(httpResponse.statusCode) {
                    lastError = "Server error: \(httpResponse.statusCode)"
                    if attempt < maxRetries {
                        try await Task.sleep(nanoseconds: delayMs * 1_000_000)
                        delayMs = min(UInt64(Double(delayMs) * backoffMultiplier), maxDelayMs)
                    }
                    continue
                }

                // Client errors (4xx except 429) are not retryable
                if !(200...299).contains(httpResponse.statusCode) {
                    let body = String(data: data, encoding: .utf8) ?? "Unknown error"
                    return .error("API error \(httpResponse.statusCode): \(body.prefix(500))")
                }

                // Parse successful response
                return parseResponse(data: data)

            } catch is CancellationError {
                return .error("Request cancelled")
            } catch let urlError as URLError {
                if urlError.code == .notConnectedToInternet || urlError.code == .cannotFindHost {
                    return .error("No internet connection. Check your network and try again.")
                }
                lastError = urlError.localizedDescription
                if attempt < maxRetries {
                    try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
                    delayMs = min(UInt64(Double(delayMs) * backoffMultiplier), maxDelayMs)
                }
            } catch {
                lastError = error.localizedDescription
                if attempt < maxRetries {
                    try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
                    delayMs = min(UInt64(Double(delayMs) * backoffMultiplier), maxDelayMs)
                }
            }
        }

        return .error("Request failed after \(maxRetries + 1) attempts: \(lastError ?? "unknown error")")
    }

    // MARK: - Response Parsing

    private func parseResponse(data: Data) -> ChatCompletionResult {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .error("Invalid JSON in API response")
        }

        // Check for error envelope
        if let errorObj = json["error"] as? [String: Any] {
            let message = errorObj["message"] as? String ?? "Unknown API error"
            let code = errorObj["code"] as? Int
            let prefix = code.map { "API error \($0)" } ?? "API error"
            return .error("\(prefix): \(message)")
        }

        // Extract choices
        guard let choices = json["choices"] as? [[String: Any]],
              let firstChoice = choices.first,
              let message = firstChoice["message"] as? [String: Any] else {
            return .error("No choices in API response")
        }

        // Extract content
        let content: String?
        if let contentString = message["content"] as? String {
            content = contentString
        } else if let contentArray = message["content"] as? [[String: Any]] {
            // Handle array-of-parts format
            content = contentArray.compactMap { part -> String? in
                if part["type"] as? String == "text" {
                    return part["text"] as? String
                }
                return nil
            }.joined(separator: "\n")
        } else {
            content = nil
        }

        // Extract tool calls
        var toolCalls: [ToolCall]?
        if let rawToolCalls = message["tool_calls"] as? [[String: Any]] {
            toolCalls = rawToolCalls.compactMap { tc -> ToolCall? in
                guard let id = tc["id"] as? String, !id.isEmpty,
                      let function = tc["function"] as? [String: Any],
                      let name = function["name"] as? String, !name.isEmpty,
                      let arguments = function["arguments"] as? String else {
                    return nil
                }
                return ToolCall(id: id, name: name, arguments: arguments)
            }
            // Limit tool calls per response
            if let calls = toolCalls, calls.count > maxToolCallsPerResponse {
                toolCalls = Array(calls.prefix(maxToolCallsPerResponse))
            }
            if toolCalls?.isEmpty == true {
                toolCalls = nil
            }
        }

        // Extract model name
        let modelName = json["model"] as? String ?? "unknown"

        // Extract usage
        var usage: TokenUsage?
        if let usageDict = json["usage"] as? [String: Any] {
            let prompt = usageDict["prompt_tokens"] as? Int ?? 0
            let completion = usageDict["completion_tokens"] as? Int ?? 0
            let total = usageDict["total_tokens"] as? Int ?? (prompt + completion)
            usage = TokenUsage(
                promptTokens: prompt,
                completionTokens: completion,
                totalTokens: total
            )
        }

        let response = ChatCompletionResponse(
            content: content,
            toolCalls: toolCalls,
            model: modelName,
            usage: usage
        )

        return .success(response)
    }
}
