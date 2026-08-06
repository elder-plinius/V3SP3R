import SwiftUI
import VesperCore

struct AuditView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        List(model.auditEvents) { event in
            VStack(alignment: .leading, spacing: 5) {
                HStack { Text(event.kind.replacingOccurrences(of: "_", with: " ").capitalized).font(.headline); Spacer(); if let risk = event.riskLevel { Text(risk.rawValue.uppercased()).font(.caption.bold()).foregroundStyle(color(risk)) } }
                if let action = event.command?.action.rawValue { Text(action).font(.system(.caption, design: .monospaced)) }
                if let detail = event.detail { Text(detail).font(.caption).foregroundStyle(.secondary) }
                Text(event.timestamp.formatted(date: .abbreviated, time: .standard)).font(.caption2).foregroundStyle(.tertiary)
            }.padding(.vertical, 3)
        }
        .navigationTitle("Audit Log")
        .overlay { if model.auditEvents.isEmpty { ContentUnavailableView("No actions yet", systemImage: "clock.arrow.circlepath") } }
    }
    private func color(_ risk: VesperCore.RiskLevel) -> Color { switch risk { case .low: .green; case .medium: .orange; case .high, .blocked: .red } }
}
