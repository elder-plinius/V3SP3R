import Foundation
import VesperCore

actor OpenRouterClient: LLMClient {
    private let keychain: KeychainStore
    private let session: URLSession
    private let modelProvider: @Sendable () -> String
    private let endpoint = URL(string: "https://openrouter.ai/api/v1/chat/completions")!

    init(keychain: KeychainStore, session: URLSession = .shared, modelProvider: @escaping @Sendable () -> String) {
        self.keychain = keychain
        self.session = session
        self.modelProvider = modelProvider
    }

    func complete(messages: [AgentMessage], tools: [LLMToolDefinition]) async throws -> LLMResponse {
        guard let apiKey = try await keychain.value(for: "openrouter_api_key"), !apiKey.isEmpty else {
            throw OpenRouterError.missingAPIKey
        }
        let payload: [String: Any] = [
            "model": modelProvider(),
            "messages": messages.map(messageObject),
            "tools": tools.map { ["type": "function", "function": ["name": $0.name, "description": $0.description, "parameters": foundation($0.parameters)]] },
            "tool_choice": "auto"
        ]
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("https://vesper.flipper.app", forHTTPHeaderField: "HTTP-Referer")
        request.setValue("Vesper iOS", forHTTPHeaderField: "X-Title")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw OpenRouterError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONDecoder().decode(APIErrorEnvelope.self, from: data).error.message) ?? "HTTP \(http.statusCode)"
            throw OpenRouterError.api(message)
        }
        let envelope = try JSONDecoder().decode(CompletionEnvelope.self, from: data)
        guard let choice = envelope.choices.first else { throw OpenRouterError.invalidResponse }
        return LLMResponse(
            content: choice.message.content ?? "",
            toolCalls: (choice.message.toolCalls ?? []).map { .init(id: $0.id, name: $0.function.name, arguments: $0.function.arguments) },
            model: envelope.model
        )
    }

    private func messageObject(_ message: AgentMessage) -> [String: Any] {
        var result: [String: Any] = ["role": message.role.rawValue]
        if message.role == .user, !message.images.isEmpty {
            var content: [[String: Any]] = [["type": "text", "text": message.content]]
            content += message.images.map { ["type": "image_url", "image_url": ["url": "data:\($0.mimeType);base64,\($0.base64Data)"]] }
            result["content"] = content
        } else {
            result["content"] = message.content
        }
        if !message.toolCalls.isEmpty {
            result["tool_calls"] = message.toolCalls.map { ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] }
        }
        if let id = message.toolCallID { result["tool_call_id"] = id }
        return result
    }

    private func foundation(_ object: [String: JSONValue]) -> [String: Any] {
        object.mapValues(foundation)
    }

    private func foundation(_ value: JSONValue) -> Any {
        switch value {
        case .string(let value): value
        case .number(let value): value
        case .bool(let value): value
        case .object(let value): foundation(value)
        case .array(let value): value.map(foundation)
        case .null: NSNull()
        }
    }
}

private struct CompletionEnvelope: Decodable {
    let model: String
    let choices: [Choice]
    struct Choice: Decodable { let message: Message }
    struct Message: Decodable {
        let content: String?
        let toolCalls: [ToolCall]?
        enum CodingKeys: String, CodingKey { case content; case toolCalls = "tool_calls" }
    }
    struct ToolCall: Decodable {
        let id: String
        let function: Function
        struct Function: Decodable { let name: String; let arguments: String }
    }
}

private struct APIErrorEnvelope: Decodable { let error: APIError; struct APIError: Decodable { let message: String } }

enum OpenRouterError: LocalizedError {
    case missingAPIKey, invalidResponse, api(String)
    var errorDescription: String? {
        switch self {
        case .missingAPIKey: "Add an OpenRouter API key in Settings."
        case .invalidResponse: "OpenRouter returned an invalid response."
        case .api(let message): message
        }
    }
}
