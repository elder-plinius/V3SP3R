import Foundation

// MARK: - Models

enum BridgeState: Equatable {
    case disconnected
    case connecting
    case connected
    case error(String)
}

struct GlassesMessage: Codable {
    let type: String       // "transcription", "photo", "ai_response", "status"
    let content: String?
    let imageData: String? // base64-encoded image bytes
    let metadata: [String: String]?

    enum CodingKeys: String, CodingKey {
        case type
        case content
        case imageData = "image_data"
        case metadata
    }
}

// MARK: - Client

@Observable
final class GlassesBridgeClient {

    // MARK: - Published State

    var state: BridgeState = .disconnected

    // MARK: - Callback

    /// Called on the main actor whenever a decoded message arrives.
    var onMessage: ((GlassesMessage) -> Void)?

    // MARK: - Private

    private var webSocketTask: URLSessionWebSocketTask?
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        return URLSession(configuration: config)
    }()

    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 10
    private let reconnectDelayNs: UInt64 = 3_000_000_000 // 3 seconds

    private var currentURL: String?
    private var intentionalDisconnect = false

    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    // MARK: - Public API

    /// Opens a WebSocket connection to the given URL string.
    func connect(url: String) {
        guard let parsed = URL(string: url),
              let scheme = parsed.scheme,
              ["ws", "wss"].contains(scheme.lowercased()) else {
            state = .error("Invalid WebSocket URL. Must begin with ws:// or wss://.")
            return
        }

        intentionalDisconnect = false
        reconnectAttempts = 0
        openConnection(to: parsed)
    }

    /// Gracefully closes the connection. No auto-reconnect will follow.
    func disconnect() {
        intentionalDisconnect = true
        closeConnection(reason: "Client disconnected")
    }

    /// Sends a message over the open WebSocket.
    func send(_ message: GlassesMessage) async throws {
        guard let task = webSocketTask,
              state == .connected else {
            throw BridgeError.notConnected
        }

        let data = try encoder.encode(message)
        guard let jsonString = String(data: data, encoding: .utf8) else {
            throw BridgeError.encodingFailed
        }

        try await task.send(.string(jsonString))
    }

    // MARK: - Private – Connection Lifecycle

    private func openConnection(to url: URL) {
        closeConnection(reason: nil) // tear down any prior socket

        state = .connecting
        currentURL = url.absoluteString

        let task = session.webSocketTask(with: url)
        task.maximumMessageSize = 16 * 1024 * 1024 // 16 MB for image payloads
        self.webSocketTask = task

        task.resume()

        // Send an initial ping to confirm the connection is alive.
        task.sendPing { [weak self] error in
            guard let self else { return }
            if let error {
                self.handleConnectionFailure(error)
            } else {
                self.state = .connected
                self.reconnectAttempts = 0
                self.receiveMessages()
            }
        }
    }

    private func closeConnection(reason: String?) {
        webSocketTask?.cancel(with: .normalClosure,
                              reason: reason?.data(using: .utf8))
        webSocketTask = nil

        if state != .disconnected {
            state = .disconnected
        }
    }

    // MARK: - Private – Receive Loop

    private func receiveMessages() {
        guard let task = webSocketTask else { return }

        task.receive { [weak self] result in
            guard let self else { return }

            switch result {
            case .success(let message):
                self.handleIncoming(message)
                // Continue listening.
                self.receiveMessages()

            case .failure(let error):
                self.handleConnectionFailure(error)
            }
        }
    }

    private func handleIncoming(_ message: URLSessionWebSocketTask.Message) {
        let data: Data

        switch message {
        case .string(let text):
            guard let encoded = text.data(using: .utf8) else { return }
            data = encoded
        case .data(let raw):
            data = raw
        @unknown default:
            return
        }

        do {
            let glassesMessage = try decoder.decode(GlassesMessage.self, from: data)
            Task { @MainActor [weak self] in
                self?.onMessage?(glassesMessage)
            }
        } catch {
            // Non-fatal: log and keep listening.
            debugLog("Failed to decode message: \(error.localizedDescription)")
        }
    }

    // MARK: - Private – Reconnection

    private func handleConnectionFailure(_ error: Error) {
        webSocketTask = nil

        if intentionalDisconnect {
            state = .disconnected
            return
        }

        state = .error(error.localizedDescription)
        attemptReconnect()
    }

    private func attemptReconnect() {
        guard !intentionalDisconnect else { return }
        guard reconnectAttempts < maxReconnectAttempts else {
            state = .error("Exceeded maximum reconnect attempts (\(maxReconnectAttempts)).")
            return
        }

        guard let urlString = currentURL, let url = URL(string: urlString) else {
            state = .error("No URL available for reconnection.")
            return
        }

        reconnectAttempts += 1
        let attempt = reconnectAttempts

        debugLog("Reconnect attempt \(attempt)/\(maxReconnectAttempts) in 3 s...")

        Task { [weak self] in
            try? await Task.sleep(nanoseconds: self?.reconnectDelayNs ?? 3_000_000_000)
            guard let self, !self.intentionalDisconnect else { return }
            self.openConnection(to: url)
        }
    }

    // MARK: - Helpers

    private func debugLog(_ message: String) {
        #if DEBUG
        print("[GlassesBridgeClient] \(message)")
        #endif
    }
}

// MARK: - Errors

extension GlassesBridgeClient {
    enum BridgeError: LocalizedError {
        case notConnected
        case encodingFailed

        var errorDescription: String? {
            switch self {
            case .notConnected:
                return "WebSocket is not connected."
            case .encodingFailed:
                return "Failed to encode message to JSON."
            }
        }
    }
}
