// ChatStore.swift
// Vesper - AI-powered Flipper Zero controller
// SwiftData-backed chat session persistence

import Foundation
import SwiftData

// MARK: - Chat Session Entity

/// Persisted chat session using SwiftData.
/// Messages are stored as a JSON blob for flexibility and query simplicity.
@Model
final class ChatSessionEntity {
    @Attribute(.unique) var id: String
    var createdAt: Date
    var deviceName: String?
    var messagesJson: String

    init(
        id: String = UUID().uuidString,
        createdAt: Date = Date(),
        deviceName: String? = nil,
        messagesJson: String = "[]"
    ) {
        self.id = id
        self.createdAt = createdAt
        self.deviceName = deviceName
        self.messagesJson = messagesJson
    }
}

// MARK: - Chat Session Summary

/// Lightweight summary returned from list queries (no message payload).
struct ChatSessionSummary: Sendable, Identifiable {
    let id: String
    let createdAt: Date
    let deviceName: String?
    let messageCount: Int
    let lastMessagePreview: String?
}

// MARK: - Chat Store

/// Provides save, load, delete, and list operations for chat sessions.
final class ChatStore {

    private let modelContainer: ModelContainer

    init(modelContainer: ModelContainer) {
        self.modelContainer = modelContainer
    }

    /// Creates a convenience initializer that builds the default container.
    @MainActor
    convenience init() throws {
        let container = try ModelContainer(for: ChatSessionEntity.self)
        self.init(modelContainer: container)
    }

    // MARK: - Save

    /// Saves or updates a chat session with the given messages.
    @MainActor
    func save(
        sessionId: String,
        messages: [ChatMessage],
        deviceName: String? = nil
    ) throws {
        let context = modelContainer.mainContext
        let encoder = JSONEncoder()
        let jsonData = try encoder.encode(messages)
        let json = String(data: jsonData, encoding: .utf8) ?? "[]"

        let predicate = #Predicate<ChatSessionEntity> { entity in
            entity.id == sessionId
        }
        let descriptor = FetchDescriptor<ChatSessionEntity>(predicate: predicate)
        let existing = try context.fetch(descriptor)

        if let entity = existing.first {
            entity.messagesJson = json
            if let deviceName { entity.deviceName = deviceName }
        } else {
            let entity = ChatSessionEntity(
                id: sessionId,
                createdAt: Date(),
                deviceName: deviceName,
                messagesJson: json
            )
            context.insert(entity)
        }

        try context.save()
    }

    // MARK: - Load

    /// Loads all messages for a given session. Returns an empty array if the session doesn't exist.
    @MainActor
    func loadMessages(sessionId: String) throws -> [ChatMessage] {
        let context = modelContainer.mainContext
        let predicate = #Predicate<ChatSessionEntity> { entity in
            entity.id == sessionId
        }
        let descriptor = FetchDescriptor<ChatSessionEntity>(predicate: predicate)
        let results = try context.fetch(descriptor)

        guard let entity = results.first,
              let data = entity.messagesJson.data(using: .utf8) else {
            return []
        }

        let decoder = JSONDecoder()
        return try decoder.decode([ChatMessage].self, from: data)
    }

    // MARK: - Delete

    /// Deletes a chat session by its ID.
    @MainActor
    func deleteSession(sessionId: String) throws {
        let context = modelContainer.mainContext
        let predicate = #Predicate<ChatSessionEntity> { entity in
            entity.id == sessionId
        }
        let descriptor = FetchDescriptor<ChatSessionEntity>(predicate: predicate)
        let results = try context.fetch(descriptor)

        for entity in results {
            context.delete(entity)
        }
        try context.save()
    }

    // MARK: - List Sessions

    /// Returns summaries of all saved chat sessions, sorted by creation date descending.
    @MainActor
    func listSessions() throws -> [ChatSessionSummary] {
        let context = modelContainer.mainContext
        var descriptor = FetchDescriptor<ChatSessionEntity>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        descriptor.propertiesToFetch = [\.id, \.createdAt, \.deviceName, \.messagesJson]
        let entities = try context.fetch(descriptor)

        return entities.map { entity in
            let decoder = JSONDecoder()
            var messageCount = 0
            var preview: String?

            if let data = entity.messagesJson.data(using: .utf8),
               let messages = try? decoder.decode([ChatMessage].self, from: data) {
                messageCount = messages.count
                preview = messages.last?.content
                if let p = preview, p.count > 100 {
                    preview = String(p.prefix(100)) + "..."
                }
            }

            return ChatSessionSummary(
                id: entity.id,
                createdAt: entity.createdAt,
                deviceName: entity.deviceName,
                messageCount: messageCount,
                lastMessagePreview: preview
            )
        }
    }

    // MARK: - Delete All

    /// Deletes all chat sessions.
    @MainActor
    func deleteAllSessions() throws {
        let context = modelContainer.mainContext
        let descriptor = FetchDescriptor<ChatSessionEntity>()
        let entities = try context.fetch(descriptor)
        for entity in entities {
            context.delete(entity)
        }
        try context.save()
    }
}
