// AuditService.swift
// Vesper - AI-powered Flipper Zero controller
// Audit logging service for all command executions

import Foundation

// MARK: - Audit Store Protocol

/// Protocol for persisting audit entries.
/// Concrete implementation lives in the Data layer (e.g., on-disk JSON or Core Data).
protocol AuditStoreProtocol: Sendable {
    func save(_ entry: AuditEntry)
    func loadEntries(limit: Int) -> [AuditEntry]
    func loadEntries(sessionId: String) -> [AuditEntry]
    func clearAll()
}

// MARK: - Audit Service

/// Central audit logging service. All command executions, approvals, and rejections
/// flow through here to create an immutable audit trail.
final class AuditService: @unchecked Sendable {

    private let store: AuditStoreProtocol
    private let lock = NSLock()

    private var _currentSessionId: String?
    private var _recentEntries: [AuditEntry] = []
    private let maxRecentEntries = 500

    init(store: AuditStoreProtocol) {
        self.store = store
        self._recentEntries = store.loadEntries(limit: maxRecentEntries)
    }

    // MARK: - Session Management

    /// Start an audit session, logging the session start event.
    func startSession(deviceName: String?) {
        let sessionId = UUID().uuidString
        lock.lock()
        _currentSessionId = sessionId
        lock.unlock()

        let metadata: [String: String]
        if let name = deviceName {
            metadata = ["device_name": name]
        } else {
            metadata = [:]
        }

        log(AuditEntry(
            actionType: .sessionStarted,
            sessionId: sessionId,
            metadata: metadata
        ))
    }

    /// End the current audit session.
    func endSession() {
        lock.lock()
        let sessionId = _currentSessionId ?? UUID().uuidString
        _currentSessionId = nil
        lock.unlock()

        log(AuditEntry(
            actionType: .sessionEnded,
            sessionId: sessionId
        ))
    }

    /// The current session ID, if any.
    var currentSessionId: String? {
        lock.lock()
        defer { lock.unlock() }
        return _currentSessionId
    }

    // MARK: - Logging

    /// Log an audit entry. Thread-safe.
    func log(_ entry: AuditEntry) {
        store.save(entry)
        lock.lock()
        _recentEntries.append(entry)
        if _recentEntries.count > maxRecentEntries {
            _recentEntries.removeFirst(_recentEntries.count - maxRecentEntries)
        }
        lock.unlock()
    }

    /// Recent audit entries (in-memory cache for UI display).
    var recentEntries: [AuditEntry] {
        lock.lock()
        defer { lock.unlock() }
        return _recentEntries
    }

    /// Load entries for a specific session from the persistent store.
    func entries(forSession sessionId: String) -> [AuditEntry] {
        return store.loadEntries(sessionId: sessionId)
    }

    /// Clear all audit history from the persistent store and in-memory cache.
    func clearHistory() {
        store.clearAll()
        lock.lock()
        _recentEntries.removeAll()
        lock.unlock()
    }
}

// MARK: - In-Memory Audit Store

/// A simple in-memory audit store for use during development or testing.
final class InMemoryAuditStore: AuditStoreProtocol {

    private let lock = NSLock()
    private var entries: [AuditEntry] = []

    func save(_ entry: AuditEntry) {
        lock.lock()
        entries.append(entry)
        lock.unlock()
    }

    func loadEntries(limit: Int) -> [AuditEntry] {
        lock.lock()
        defer { lock.unlock() }
        if entries.count <= limit {
            return entries
        }
        return Array(entries.suffix(limit))
    }

    func loadEntries(sessionId: String) -> [AuditEntry] {
        lock.lock()
        defer { lock.unlock() }
        return entries.filter { $0.sessionId == sessionId }
    }

    func clearAll() {
        lock.lock()
        entries.removeAll()
        lock.unlock()
    }
}
