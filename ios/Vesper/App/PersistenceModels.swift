import Foundation
import SwiftData
import VesperCore

@Model
final class StoredMessage {
    @Attribute(.unique) var id: UUID
    var sessionID: UUID
    var role: String
    var content: String
    var timestamp: Date
    var payload: Data?

    init(message: AgentMessage, sessionID: UUID, timestamp: Date = .now) {
        id = message.id
        self.sessionID = sessionID
        role = message.role.rawValue
        content = message.content
        self.timestamp = timestamp
        payload = try? JSONEncoder().encode(message)
    }

    var agentMessage: AgentMessage? {
        payload.flatMap { try? JSONDecoder().decode(AgentMessage.self, from: $0) }
            ?? AgentRole(rawValue: role).map { AgentMessage(id: id, role: $0, content: content) }
    }
}

@Model
final class StoredAuditEvent {
    @Attribute(.unique) var id: UUID
    var timestamp: Date
    var kind: String
    var risk: String?
    var detail: String?
    var payload: Data?

    init(event: AuditEvent) {
        id = event.id
        timestamp = event.timestamp
        kind = event.kind
        risk = event.riskLevel?.rawValue
        detail = event.detail
        payload = try? JSONEncoder().encode(event)
    }

    var event: AuditEvent? { payload.flatMap { try? JSONDecoder().decode(AuditEvent.self, from: $0) } }
}
