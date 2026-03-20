import SwiftUI

struct DiffViewer: View {
    let diff: FileDiff

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("\(diff.linesAdded) added", systemImage: "plus")
                    .font(.caption)
                    .foregroundStyle(.green)
                Label("\(diff.linesRemoved) removed", systemImage: "minus")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                ScrollView(.vertical, showsIndicators: true) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(diff.unifiedDiff.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundStyle(lineColor(line))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(lineBackground(line))
                        }
                    }
                }
            }
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private func lineColor(_ line: String) -> Color {
        if line.hasPrefix("+") { return .green }
        if line.hasPrefix("-") { return .red }
        if line.hasPrefix("@@") { return .cyan }
        return .primary
    }

    private func lineBackground(_ line: String) -> Color {
        if line.hasPrefix("+") { return .green.opacity(0.1) }
        if line.hasPrefix("-") { return .red.opacity(0.1) }
        if line.hasPrefix("@@") { return .cyan.opacity(0.05) }
        return .clear
    }
}
