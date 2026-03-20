import SwiftUI

struct ApprovalDialog: View {
    let approval: PendingApproval
    let onApprove: () -> Void
    let onDeny: () -> Void

    @State private var confirmCount: Int = 0
    @State private var showDoubleConfirm: Bool = false

    private var isHighRisk: Bool {
        approval.riskAssessment.level == .high
    }

    var body: some View {
        VStack(spacing: 16) {
            headerView
            Divider()
            commandDetailView

            if let diff = approval.diff {
                DiffViewer(diff: diff)
                    .frame(maxHeight: 200)
            }

            riskBadge
            actionButtons
        }
        .padding(20)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 10)
        .padding(.horizontal, 20)
    }

    private var headerView: some View {
        HStack {
            Image(systemName: riskIcon)
                .font(.title2)
                .foregroundStyle(riskColor)
            VStack(alignment: .leading) {
                Text("Action Requires Approval")
                    .font(.headline)
                Text(approval.riskAssessment.reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var commandDetailView: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Action:")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                Text(approval.command.action.rawValue)
                    .font(.caption.monospaced())
            }
            if let path = approval.command.args.path {
                HStack {
                    Text("Path:")
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                    Text(path)
                        .font(.caption.monospaced())
                        .lineLimit(2)
                }
            }
            if !approval.command.justification.isEmpty {
                HStack(alignment: .top) {
                    Text("Why:")
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                    Text(approval.command.justification)
                        .font(.caption)
                }
            }
            if !approval.riskAssessment.affectedPaths.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Affected paths:")
                        .font(.caption.bold())
                        .foregroundStyle(.secondary)
                    ForEach(approval.riskAssessment.affectedPaths, id: \.self) { path in
                        Text(path)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var riskBadge: some View {
        HStack {
            Image(systemName: riskIcon)
                .font(.caption)
            Text(riskLabel)
                .font(.caption.bold())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(riskColor.opacity(0.15))
        .foregroundStyle(riskColor)
        .clipShape(Capsule())
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            Button(role: .cancel) {
                onDeny()
            } label: {
                Text("Deny")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            if isHighRisk {
                Button {
                    if confirmCount == 0 {
                        confirmCount = 1
                        showDoubleConfirm = true
                    } else {
                        onApprove()
                    }
                } label: {
                    Text(showDoubleConfirm ? "Confirm Again" : "Approve")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            } else {
                Button {
                    onApprove()
                } label: {
                    Text("Approve")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var riskIcon: String {
        switch approval.riskAssessment.level {
        case .low: "checkmark.shield"
        case .medium: "exclamationmark.shield"
        case .high: "xmark.shield.fill"
        case .blocked: "lock.shield.fill"
        }
    }

    private var riskColor: Color {
        switch approval.riskAssessment.level {
        case .low: .green
        case .medium: .orange
        case .high: .red
        case .blocked: .gray
        }
    }

    private var riskLabel: String {
        switch approval.riskAssessment.level {
        case .low: "LOW RISK"
        case .medium: "MEDIUM RISK"
        case .high: "HIGH RISK"
        case .blocked: "BLOCKED"
        }
    }
}
