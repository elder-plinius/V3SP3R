import SwiftUI

struct FapApp: Identifiable, Codable {
    var id: String { uid }
    let uid: String
    let name: String
    let description: String
    let author: String
    let category: String
    let version: String
    let downloadUrl: String?
    let iconUrl: String?

    enum CodingKeys: String, CodingKey {
        case uid = "id"
        case name
        case description = "short_description"
        case author
        case category = "category_name"
        case version = "current_version"
        case downloadUrl = "download_url"
        case iconUrl = "icon_url"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uid = try container.decode(String.self, forKey: .uid)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description) ?? ""
        author = try container.decodeIfPresent(String.self, forKey: .author) ?? "Unknown"
        category = try container.decodeIfPresent(String.self, forKey: .category) ?? "Other"
        version = try container.decodeIfPresent(String.self, forKey: .version) ?? "1.0"
        downloadUrl = try container.decodeIfPresent(String.self, forKey: .downloadUrl)
        iconUrl = try container.decodeIfPresent(String.self, forKey: .iconUrl)
    }

    init(uid: String, name: String, description: String, author: String, category: String, version: String, downloadUrl: String?, iconUrl: String?) {
        self.uid = uid
        self.name = name
        self.description = description
        self.author = author
        self.category = category
        self.version = version
        self.downloadUrl = downloadUrl
        self.iconUrl = iconUrl
    }
}

@Observable
class FapHubViewModel {
    private let commandExecutor: CommandExecutor

    var searchQuery: String = ""
    var apps: [FapApp] = []
    var isLoading: Bool = false
    var error: String?
    var selectedCategory: String?
    var isInstalling: String?

    let categories = ["All", "Tools", "Games", "GPIO", "NFC", "Sub-GHz", "Infrared", "RFID", "BadUSB", "Media"]

    init(commandExecutor: CommandExecutor) {
        self.commandExecutor = commandExecutor
    }

    func searchApps() {
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        isLoading = true
        error = nil

        Task {
            let command = ExecuteCommand(
                action: .searchFaphub,
                args: CommandArgs(filter: searchQuery),
                justification: "User searching FapHub",
                expectedEffect: "Return matching apps"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if result.success, let message = result.data?.message {
                // Parse the response - in a real implementation this would parse JSON
                // For now, show the raw result
                apps = []
                self.error = nil
            } else {
                self.error = result.error ?? "Search failed"
            }
            isLoading = false
        }
    }

    func installApp(_ app: FapApp) {
        isInstalling = app.uid
        Task {
            let command = ExecuteCommand(
                action: .installFaphubApp,
                args: CommandArgs(
                    appName: app.name,
                    downloadUrl: app.downloadUrl
                ),
                justification: "User installing \(app.name) from FapHub",
                expectedEffect: "App installed to Flipper"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if !result.success {
                error = result.error ?? "Install failed"
            }
            isInstalling = nil
        }
    }
}
