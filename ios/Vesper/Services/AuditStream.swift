import Foundation
import VesperCore

actor AuditStream: AuditRecording {
    private var continuation: AsyncStream<AuditEvent>.Continuation?
    private lazy var stream = AsyncStream<AuditEvent> { continuation = $0 }

    func events() -> AsyncStream<AuditEvent> { stream }
    func record(_ event: AuditEvent) { continuation?.yield(event) }
}
