import SwiftUI
import VesperCore

struct ApprovalSheet: View {
    let approval: PendingApproval
    let decision: (Bool) -> Void
    @State private var holding = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Label(approval.assessment.level == .high ? "High-risk action" : "Review action", systemImage: approval.assessment.level == .high ? "exclamationmark.triangle.fill" : "doc.text.magnifyingglass")
                        .font(.title2.bold()).foregroundStyle(approval.assessment.level == .high ? .red : .orange)
                    LabeledContent("Action", value: approval.command.action.rawValue)
                    LabeledContent("Reason", value: approval.assessment.reason)
                    Text(approval.command.justification).foregroundStyle(.secondary)
                    ForEach(approval.assessment.affectedPaths, id: \.self) { Text($0).font(.system(.callout, design: .monospaced)) }
                    if let diff = approval.diff {
                        Divider()
                        Text("Changes: +\(diff.linesAdded) −\(diff.linesRemoved)").font(.headline)
                        Text(diff.unifiedDiff).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            .padding().frame(maxWidth: .infinity, alignment: .leading).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                    }
                    if approval.assessment.level == .high {
                        Text(holding ? "Keep holding…" : "Press and hold for 1.5 seconds")
                            .font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                        Text("Hold to confirm").font(.headline).frame(maxWidth: .infinity).padding()
                            .background(holding ? Color.red.opacity(0.7) : .red, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(.white)
                            .onLongPressGesture(minimumDuration: 1.5, pressing: { holding = $0 }, perform: { decision(true) })
                    } else {
                        Button("Apply") { decision(true) }.buttonStyle(.borderedProminent).tint(Color.vesperPurple).frame(maxWidth: .infinity)
                    }
                    Button("Reject", role: .cancel) { decision(false) }.frame(maxWidth: .infinity)
                }.padding()
            }
            .navigationTitle("Approval Required").navigationBarTitleDisplayMode(.inline)
        }
    }
}
