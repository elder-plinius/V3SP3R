import SwiftUI

@Observable
class AuditLogViewModel {
    private let auditService: AuditService

    var entries: [AuditEntry] = []
    var filterActionType: AuditActionType?
    var filterRiskLevel: RiskLevel?

    var filteredEntries: [AuditEntry] {
        entries.filter { entry in
            if let actionFilter = filterActionType, entry.actionType != actionFilter {
                return false
            }
            if let riskFilter = filterRiskLevel, entry.riskLevel != riskFilter {
                return false
            }
            return true
        }
    }

    init(auditService: AuditService) {
        self.auditService = auditService
        loadEntries()
    }

    func loadEntries() {
        entries = auditService.recentEntries
    }

    func clearLog() {
        auditService.clearHistory()
        entries = []
    }
}

struct AuditLogView: View {
    @Bindable var viewModel: AuditLogViewModel

    var body: some View {
        List {
            filterSection

            if viewModel.filteredEntries.isEmpty {
                ContentUnavailableView {
                    Label("No Audit Entries", systemImage: "doc.text.magnifyingglass")
                } description: {
                    Text("AI actions will be logged here for accountability.")
                }
            } else {
                ForEach(viewModel.filteredEntries) { entry in
                    AuditEntryRow(entry: entry)
                }
            }
        }
        .navigationTitle("Audit Log")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Refresh") { viewModel.loadEntries() }
                    Button("Clear Log", role: .destructive) { viewModel.clearLog() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear { viewModel.loadEntries() }
    }

    private var filterSection: some View {
        Section {
            HStack {
                Menu {
                    Button("All Types") { viewModel.filterActionType = nil }
                    Divider()
                    ForEach(AuditActionType.allCases, id: \.self) { type in
                        Button(type.displayName) { viewModel.filterActionType = type }
                    }
                } label: {
                    Label(
                        viewModel.filterActionType?.displayName ?? "All Types",
                        systemImage: "line.3.horizontal.decrease.circle"
                    )
                    .font(.caption)
                }

                Spacer()

                Menu {
                    Button("All Risks") { viewModel.filterRiskLevel = nil }
                    Divider()
                    ForEach(RiskLevel.allCases, id: \.self) { level in
                        Button(level.rawValue.uppercased()) { viewModel.filterRiskLevel = level }
                    }
                } label: {
                    Label(
                        viewModel.filterRiskLevel?.rawValue.uppercased() ?? "All Risks",
                        systemImage: "shield"
                    )
                    .font(.caption)
                }
            }
        }
    }
}

struct AuditEntryRow: View {
    let entry: AuditEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: actionIcon)
                    .foregroundStyle(actionColor)
                    .font(.caption)
                Text(entry.actionType.displayName)
                    .font(.caption.bold())
                Spacer()
                if let risk = entry.riskLevel {
                    Text(risk.rawValue.uppercased())
                        .font(.caption2.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(riskColor(risk).opacity(0.15))
                        .foregroundStyle(riskColor(risk))
                        .clipShape(Capsule())
                }
            }

            if let command = entry.command {
                Text("\(command.action.rawValue)")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                if let path = command.args.path {
                    Text(path)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Text(Date(timeIntervalSince1970: TimeInterval(entry.timestamp) / 1000), style: .relative)
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }

    private var actionIcon: String {
        switch entry.actionType {
        case .commandExecuted: "checkmark.circle"
        case .commandFailed: "xmark.circle"
        case .commandBlocked: "lock.fill"
        case .commandReceived: "arrow.down.circle"
        case .approvalRequested: "hand.raised"
        case .approvalGranted: "hand.thumbsup"
        case .approvalDenied: "hand.thumbsdown"
        case .sessionStarted: "play.circle"
        case .sessionEnded: "stop.circle"
        }
    }

    private var actionColor: Color {
        switch entry.actionType {
        case .commandExecuted: .green
        case .commandFailed: .red
        case .commandBlocked: .gray
        case .commandReceived: .blue
        case .approvalRequested: .orange
        case .approvalGranted: .green
        case .approvalDenied: .red
        case .sessionStarted: .purple
        case .sessionEnded: .purple
        }
    }

    private func riskColor(_ risk: RiskLevel) -> Color {
        switch risk {
        case .low: .green
        case .medium: .orange
        case .high: .red
        case .blocked: .gray
        }
    }
}

extension AuditActionType {
    var displayName: String {
        switch self {
        case .sessionStarted: "Session Started"
        case .sessionEnded: "Session Ended"
        case .commandReceived: "Command Received"
        case .commandExecuted: "Command Executed"
        case .commandFailed: "Command Failed"
        case .commandBlocked: "Command Blocked"
        case .approvalRequested: "Approval Requested"
        case .approvalGranted: "Approval Granted"
        case .approvalDenied: "Approval Denied"
        }
    }
}
