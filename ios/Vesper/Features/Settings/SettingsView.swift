import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var apiKey = ""
    @State private var saveStatus = ""
    @State private var protectedPath = ""

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section("OpenRouter") {
                    SecureField("sk-or-…", text: $apiKey).textContentType(.password).privacySensitive()
                    Button("Save API Key") {
                        Task { do { try await model.saveAPIKey(apiKey); apiKey = ""; saveStatus = "Saved securely in Keychain" } catch { saveStatus = error.localizedDescription } }
                    }
                    if !saveStatus.isEmpty { Text(saveStatus).font(.caption).foregroundStyle(.secondary) }
                    TextField("Model", text: $model.model).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                Section("Safety") {
                    Text("Every command is classified on-device. Writes show a diff; destructive actions require explicit confirmation; protected paths remain blocked unless deliberately unlocked.")
                    Text("Only use Vesper on hardware and signals you own or are authorized to test.").foregroundStyle(.secondary)
                }
                Section("Temporary protected-path access") {
                    TextField("Exact path, for example /ext/.region", text: $protectedPath)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Unlock for 15 minutes", role: .destructive) {
                        Task {
                            do {
                                try await model.grantProtectedPath(protectedPath)
                                protectedPath = ""
                            } catch {
                                model.alertMessage = error.localizedDescription
                            }
                        }
                    }
                    .disabled(protectedPath.isEmpty)
                    if let status = model.permissionStatus { Text(status).font(.caption).foregroundStyle(.orange) }
                    Text("This grants only the entered path prefix and does not bypass per-command confirmation.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("Privacy") {
                    LabeledContent("Chat and audit history", value: "On device")
                    LabeledContent("API key", value: "Keychain")
                    LabeledContent("Telemetry", value: "None")
                }
                Section("About") { LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"); Link("GPL-3.0 License", destination: URL(string: "https://github.com/elder-plinius/V3SP3R/blob/main/LICENSE")!) }
            }.navigationTitle("Settings")
        }
    }
}
