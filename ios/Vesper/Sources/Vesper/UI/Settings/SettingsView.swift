import SwiftUI

struct SettingsView: View {
    @Bindable var viewModel: SettingsViewModel

    var body: some View {
        Form {
            apiKeySection
            modelSection
            approvalSection
            glassesSection
            aboutSection
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var apiKeySection: some View {
        Section {
            if viewModel.hasApiKey {
                HStack {
                    Label("API Key", systemImage: "key.fill")
                    Spacer()
                    Text(viewModel.apiKeyMasked)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                Button("Change API Key") {
                    viewModel.showApiKeyField = true
                }
                Button("Remove API Key", role: .destructive) {
                    viewModel.deleteApiKey()
                }
            } else {
                Label("No API key configured", systemImage: "key")
                    .foregroundStyle(.secondary)
                Button("Add API Key") {
                    viewModel.showApiKeyField = true
                }
            }

            if viewModel.showApiKeyField {
                SecureField("sk-or-...", text: $viewModel.apiKey)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button("Save") {
                    viewModel.saveApiKey()
                }
                .disabled(viewModel.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if let error = viewModel.saveError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            Text("OpenRouter API Key")
        } footer: {
            Text("Stored securely in iOS Keychain. Never saved to UserDefaults or disk.")
        }
    }

    private var modelSection: some View {
        Section("AI Model") {
            Picker("Model", selection: $viewModel.selectedModel) {
                ForEach(SettingsViewModel.availableModels, id: \.id) { model in
                    Text(model.name).tag(model.id)
                }
            }
            .onChange(of: viewModel.selectedModel) { _, _ in
                viewModel.saveModel()
            }
        }
    }

    private var approvalSection: some View {
        Section {
            Toggle("Auto-approve Medium risk", isOn: $viewModel.autoApproveMedium)
                .onChange(of: viewModel.autoApproveMedium) { _, _ in
                    viewModel.saveAutoApprove()
                }
            Toggle("Auto-approve High risk", isOn: $viewModel.autoApproveHigh)
                .onChange(of: viewModel.autoApproveHigh) { _, _ in
                    viewModel.saveAutoApprove()
                }
        } header: {
            Text("Approval Tiers")
        } footer: {
            Text("Medium: file writes, signal transmission, payload generation.\nHigh: deletions, moves, BadUSB, app installs.\nBlocked operations always require settings unlock.")
        }
    }

    private var glassesSection: some View {
        Section {
            Toggle("Enable Smart Glasses", isOn: $viewModel.glassesEnabled)
                .onChange(of: viewModel.glassesEnabled) { _, _ in
                    viewModel.saveGlassesSettings()
                }

            if viewModel.glassesEnabled {
                TextField("Bridge URL (wss://...)", text: $viewModel.glassesBridgeUrl)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .onSubmit {
                        viewModel.saveGlassesSettings()
                    }
            }
        } header: {
            Text("Smart Glasses")
        } footer: {
            Text("Connect to a MentraOS bridge server for glasses integration.")
        }
    }

    private var aboutSection: some View {
        Section("About") {
            LabeledContent("Version", value: "1.0.0")
            LabeledContent("Platform", value: "iOS")
            LabeledContent("Architecture", value: "SwiftUI + CoreBluetooth")
        }
    }
}
