import SwiftUI

struct OpsCenterView: View {
    @Bindable var viewModel: OpsCenterViewModel

    var body: some View {
        List {
            statusSection
            runbooksSection
            recentActionsSection
        }
        .navigationTitle("Ops Center")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var statusSection: some View {
        Section("System Status") {
            HStack {
                Image(systemName: viewModel.isDeviceConnected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(viewModel.isDeviceConnected ? .green : .secondary)
                VStack(alignment: .leading) {
                    Text(viewModel.isDeviceConnected ? "Device Connected" : "No Device")
                        .font(.headline)
                    Text(viewModel.deviceName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            switch viewModel.pipelineHealth {
            case .idle:
                HStack {
                    Image(systemName: "circle.dashed")
                        .foregroundStyle(.secondary)
                    Text("Pipeline idle")
                        .foregroundStyle(.secondary)
                }
            case .running(let runbook):
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Running: \(runbook)")
                        .foregroundStyle(.orange)
                }
            case .completed(let message):
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text(message)
                        .font(.caption)
                }
            case .error(let error):
                HStack {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.red)
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private var runbooksSection: some View {
        Section("Runbooks") {
            ForEach(OpsCenterViewModel.runbooks, id: \.id) { runbook in
                Button {
                    viewModel.runRunbook(runbook.id)
                } label: {
                    HStack {
                        Image(systemName: runbook.icon)
                            .foregroundStyle(.purple)
                            .frame(width: 28)
                        VStack(alignment: .leading) {
                            Text(runbook.name)
                                .font(.body)
                            Text(runbook.description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "play.circle")
                            .foregroundStyle(.accentColor)
                    }
                }
                .tint(.primary)
                .disabled(!viewModel.isDeviceConnected)
            }
        }
    }

    private var recentActionsSection: some View {
        Section("Recent Actions") {
            if viewModel.recentActions.isEmpty {
                Text("No recent actions")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(viewModel.recentActions) { entry in
                    AuditEntryRow(entry: entry)
                }
            }
        }
    }
}
