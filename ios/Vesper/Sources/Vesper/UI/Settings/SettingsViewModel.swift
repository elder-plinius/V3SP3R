import SwiftUI

@Observable
class SettingsViewModel {
    private let settingsStore: SettingsStore
    private let secureStorage: SecureStorage

    var apiKey: String = ""
    var apiKeyMasked: String = ""
    var hasApiKey: Bool = false
    var selectedModel: String = ""
    var autoApproveMedium: Bool = false
    var autoApproveHigh: Bool = false
    var glassesEnabled: Bool = false
    var glassesBridgeUrl: String = ""
    var showApiKeyField: Bool = false
    var saveError: String?

    static let availableModels: [(id: String, name: String)] = [
        ("anthropic/claude-sonnet-4-20250514", "Claude Sonnet 4"),
        ("anthropic/claude-opus-4-20250514", "Claude Opus 4"),
        ("openai/gpt-4o", "GPT-4o"),
        ("openai/gpt-4o-mini", "GPT-4o Mini"),
        ("google/gemini-2.0-flash-001", "Gemini 2.0 Flash"),
        ("google/gemini-2.5-pro-preview", "Gemini 2.5 Pro"),
        ("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
        ("x-ai/grok-3-mini-beta", "Grok 3 Mini"),
    ]

    init(settingsStore: SettingsStore, secureStorage: SecureStorage) {
        self.settingsStore = settingsStore
        self.secureStorage = secureStorage
        loadSettings()
    }

    func loadSettings() {
        selectedModel = settingsStore.selectedModel
        autoApproveMedium = settingsStore.autoApproveMedium
        autoApproveHigh = settingsStore.autoApproveHigh
        glassesEnabled = settingsStore.glassesEnabled
        glassesBridgeUrl = settingsStore.glassesBridgeUrl

        if let key = secureStorage.loadAPIKey() {
            hasApiKey = true
            apiKeyMasked = maskApiKey(key)
        }
    }

    func saveApiKey() {
        let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        do {
            try secureStorage.saveAPIKey(trimmed)
            hasApiKey = true
            apiKeyMasked = maskApiKey(trimmed)
            apiKey = ""
            showApiKeyField = false
            saveError = nil
        } catch {
            saveError = "Failed to save API key: \(error.localizedDescription)"
        }
    }

    func deleteApiKey() {
        do {
            try secureStorage.deleteAPIKey()
            hasApiKey = false
            apiKeyMasked = ""
            apiKey = ""
            saveError = nil
        } catch {
            saveError = "Failed to delete API key: \(error.localizedDescription)"
        }
    }

    func saveModel() {
        settingsStore.selectedModel = selectedModel
    }

    func saveAutoApprove() {
        settingsStore.autoApproveMedium = autoApproveMedium
        settingsStore.autoApproveHigh = autoApproveHigh
    }

    func saveGlassesSettings() {
        settingsStore.glassesEnabled = glassesEnabled
        settingsStore.glassesBridgeUrl = glassesBridgeUrl
    }

    private func maskApiKey(_ key: String) -> String {
        guard key.count > 8 else { return String(repeating: "*", count: key.count) }
        let prefix = String(key.prefix(4))
        let suffix = String(key.suffix(4))
        return "\(prefix)...\(suffix)"
    }
}
