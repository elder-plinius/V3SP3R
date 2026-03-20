// AuditStore.swift
// Vesper - AI-powered Flipper Zero controller
// SwiftData-backed audit log persistence

import Foundation
import SwiftData

// MARK: - Audit Entry Entity

/// Persisted audit log entry using SwiftData.
/// Command and result are stored as JSON blobs for schema flexibility.
@Model
final class AuditEntryEntity {
    @Attribute(.unique) var id: String
    var timestamp: Date
    var actionType: String
    var commandJson: String?
    var resultJson: String?
    var riskLevel: String?
    var sessionId: String

    init(
        id: String = UUID().uuidString,
        timestamp: Date = Date(),
        actionType: String,
        commandJson: String? = nil,
        resultJson: String? = nil,
        riskLevel: String? = nil,
        sessionId: String
    ) {
        self.id = id
        self.timestamp = timestamp
        self.actionType = actionType
        self.commandJson = commandJson
        self.resultJson = resultJson
        self.riskLevel = riskLevel
        self.sessionId = sessionId
    }
}

// MARK: - Audit Store

/// Provides logging, querying, and clearing of audit entries.
final class AuditStore {

    private let modelContainer: ModelContainer

    init(modelContainer: ModelContainer) {
        self.modelContainer = modelContainer
    }

    /// Creates a convenience initializer that builds the default container.
    @MainActor
    convenience init() throws {
        let container = try ModelContainer(for: AuditEntryEntity.self)
        self.init(modelContainer: container)
    }

    // MARK: - Log

    /// Logs an audit entry, serializing the command and result to JSON.
    @MainActor
    func log(_ entry: AuditEntry) throws {
        let context = modelContainer.mainContext
        let encoder = JSONEncoder()

        var commandJson: String?
        if let command = entry.command {
            let data = try encoder.encode(command)
            commandJson = String(data: data, encoding: .utf8)
        }

        var resultJson: String?
        if let result = entry.result {
            let data = try encoder.encode(result)
            resultJson = String(data: data, encoding: .utf8)
        }

        let entity = AuditEntryEntity(
            id: entry.id,
            timestamp: Date(timeIntervalSince1970: TimeInterval(entry.timestamp) / 1000.0),
            actionType: entry.actionType.rawValue,
            commandJson: commandJson,
            resultJson: resultJson,
            riskLevel: entry.riskLevel?.rawValue,
            sessionId: entry.sessionId
        )

        context.insert(entity)
        try context.save()
    }

    // MARK: - Query by Session

    /// Returns all audit entries for a given session, sorted by timestamp ascending.
    @MainActor
    func queryBySession(sessionId: String) throws -> [AuditEntry] {
        let context = modelContainer.mainContext
        let predicate = #Predicate<AuditEntryEntity> { entity in
            entity.sessionId == sessionId
        }
        let descriptor = FetchDescriptor<AuditEntryEntity>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.timestamp, order: .forward)]
        )
        let entities = try context.fetch(descriptor)
        return try entities.map { try toAuditEntry($0) }
    }

    // MARK: - Query by Action Type

    /// Returns all audit entries matching the given action type.
    @MainActor
    func queryByActionType(_ actionType: AuditActionType, limit: Int? = nil) throws -> [AuditEntry] {
        let context = modelContainer.mainContext
        let actionTypeRaw = actionType.rawValue
        let predicate = #Predicate<AuditEntryEntity> { entity in
            entity.actionType == actionTypeRaw
        }
        var descriptor = FetchDescriptor<AuditEntryEntity>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
        )
        if let limit {
            descriptor.fetchLimit = limit
        }
        let entities = try context.fetch(descriptor)
        return try entities.map { try toAuditEntry($0) }
    }

    // MARK: - Query Recent

    /// Returns the most recent audit entries across all sessions.
    @MainActor
    func queryRecent(limit: Int = 50) throws -> [AuditEntry] {
        let context = modelContainer.mainContext
        var descriptor = FetchDescriptor<AuditEntryEntity>(
            sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        let entities = try context.fetch(descriptor)
        return try entities.map { try toAuditEntry($0) }
    }

    // MARK: - Query by Date Range

    /// Returns audit entries within a date range.
    @MainActor
    func queryByDateRange(from startDate: Date, to endDate: Date) throws -> [AuditEntry] {
        let context = modelContainer.mainContext
        let predicate = #Predicate<AuditEntryEntity> { entity in
            entity.timestamp >= startDate && entity.timestamp <= endDate
        }
        let descriptor = FetchDescriptor<AuditEntryEntity>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.timestamp, order: .forward)]
        )
        let entities = try context.fetch(descriptor)
        return try entities.map { try toAuditEntry($0) }
    }

    // MARK: - Clear

    /// Deletes all audit entries.
    @MainActor
    func clear() throws {
        let context = modelContainer.mainContext
        let descriptor = FetchDescriptor<AuditEntryEntity>()
        let entities = try context.fetch(descriptor)
        for entity in entities {
            context.delete(entity)
        }
        try context.save()
    }

    // MARK: - Clear by Session

    /// Deletes all audit entries for a specific session.
    @MainActor
    func clearSession(sessionId: String) throws {
        let context = modelContainer.mainContext
        let predicate = #Predicate<AuditEntryEntity> { entity in
            entity.sessionId == sessionId
        }
        let descriptor = FetchDescriptor<AuditEntryEntity>(predicate: predicate)
        let entities = try context.fetch(descriptor)
        for entity in entities {
            context.delete(entity)
        }
        try context.save()
    }

    // MARK: - Clear Old Entries

    /// Deletes audit entries older than the specified number of days.
    @MainActor
    func clearOlderThan(days: Int) throws {
        let cutoff = Calendar.current.date(byAdding: .day, value: -days, to: Date()) ?? Date()
        let context = modelContainer.mainContext
        let predicate = #Predicate<AuditEntryEntity> { entity in
            entity.timestamp < cutoff
        }
        let descriptor = FetchDescriptor<AuditEntryEntity>(predicate: predicate)
        let entities = try context.fetch(descriptor)
        for entity in entities {
            context.delete(entity)
        }
        try context.save()
    }

    // MARK: - Entry Count

    /// Returns the total number of audit entries.
    @MainActor
    func entryCount() throws -> Int {
        let context = modelContainer.mainContext
        let descriptor = FetchDescriptor<AuditEntryEntity>()
        return try context.fetchCount(descriptor)
    }

    // MARK: - Private Helpers

    private func toAuditEntry(_ entity: AuditEntryEntity) throws -> AuditEntry {
        let decoder = JSONDecoder()

        var command: ExecuteCommand?
        if let json = entity.commandJson, let data = json.data(using: .utf8) {
            command = try decoder.decode(ExecuteCommand.self, from: data)
        }

        var result: CommandResult?
        if let json = entity.resultJson, let data = json.data(using: .utf8) {
            result = try decoder.decode(CommandResult.self, from: data)
        }

        guard let actionType = AuditActionType(rawValue: entity.actionType) else {
            throw AuditStoreError.invalidActionType(entity.actionType)
        }

        let riskLevel: RiskLevel? = entity.riskLevel.flatMap { RiskLevel(rawValue: $0) }

        return AuditEntry(
            id: entity.id,
            timestamp: Int64(entity.timestamp.timeIntervalSince1970 * 1000),
            actionType: actionType,
            command: command,
            result: result,
            riskLevel: riskLevel,
            sessionId: entity.sessionId
        )
    }
}

// MARK: - Audit Store Error

enum AuditStoreError: LocalizedError {
    case invalidActionType(String)

    var errorDescription: String? {
        switch self {
        case .invalidActionType(let raw):
            return "Unknown audit action type: \(raw)"
        }
    }
}
