import SwiftUI

struct ResourceRepo: Identifiable {
    let id: String
    let name: String
    let description: String
    let owner: String
    let icon: String
    let category: String

    static let knownRepos: [ResourceRepo] = [
        ResourceRepo(id: "UberGuidoZ/Flipper", name: "UberGuidoZ Flipper", description: "Large collection of Sub-GHz, IR, NFC, BadUSB files", owner: "UberGuidoZ", icon: "folder.fill", category: "Multi"),
        ResourceRepo(id: "logickworkshop/Flipper-IRDB", name: "Flipper IRDB", description: "Infrared remote database", owner: "logickworkshop", icon: "infrared", category: "IR"),
        ResourceRepo(id: "UberGuidoZ/Flipper-IRDB", name: "UberGuidoZ IRDB", description: "Extended IR database", owner: "UberGuidoZ", icon: "infrared", category: "IR"),
        ResourceRepo(id: "jamisonderek/flipper-zero-tutorials", name: "Tutorials & Files", description: "Tutorials and example files", owner: "jamisonderek", icon: "book", category: "Education"),
    ]
}

struct RepoFileEntry: Identifiable {
    let id = UUID()
    let name: String
    let path: String
    let isDirectory: Bool
    let downloadUrl: String?
    let size: Int64
}

@Observable
class ResourceBrowserViewModel {
    private let commandExecutor: CommandExecutor

    var repos: [ResourceRepo] = ResourceRepo.knownRepos
    var selectedRepo: ResourceRepo?
    var currentPath: String = ""
    var entries: [RepoFileEntry] = []
    var pathHistory: [String] = []
    var isLoading: Bool = false
    var isDownloading: String?
    var error: String?
    var searchQuery: String = ""

    init(commandExecutor: CommandExecutor) {
        self.commandExecutor = commandExecutor
    }

    func browseRepo(_ repo: ResourceRepo) {
        selectedRepo = repo
        currentPath = ""
        pathHistory = [""]
        loadRepoContents()
    }

    func navigateTo(_ entry: RepoFileEntry) {
        if entry.isDirectory {
            pathHistory.append(entry.path)
            currentPath = entry.path
            loadRepoContents()
        }
    }

    func navigateUp() {
        guard pathHistory.count > 1 else { return }
        pathHistory.removeLast()
        currentPath = pathHistory.last ?? ""
        loadRepoContents()
    }

    func loadRepoContents() {
        guard let repo = selectedRepo else { return }
        isLoading = true
        error = nil

        Task {
            let command = ExecuteCommand(
                action: .browseRepo,
                args: CommandArgs(repoId: repo.id, subPath: currentPath),
                justification: "Browsing resource repo",
                expectedEffect: "List repo contents"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if result.success {
                if let repoEntries = result.data?.entries {
                    entries = repoEntries.map { entry in
                        RepoFileEntry(
                            name: entry.name,
                            path: entry.path,
                            isDirectory: entry.isDirectory,
                            downloadUrl: nil,
                            size: entry.size
                        )
                    }
                }
            } else {
                self.error = result.error ?? "Failed to browse repo"
            }
            isLoading = false
        }
    }

    func downloadFile(_ entry: RepoFileEntry) {
        guard let repo = selectedRepo else { return }
        isDownloading = entry.name

        Task {
            let destPath = "/ext/downloads/\(entry.name)"
            let command = ExecuteCommand(
                action: .downloadResource,
                args: CommandArgs(
                    path: destPath,
                    repoId: repo.id,
                    subPath: entry.path,
                    downloadUrl: entry.downloadUrl
                ),
                justification: "Download resource from \(repo.name)",
                expectedEffect: "File saved to \(destPath)"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if !result.success {
                error = result.error ?? "Download failed"
            }
            isDownloading = nil
        }
    }

    func searchGitHub() {
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        isLoading = true
        error = nil

        Task {
            let command = ExecuteCommand(
                action: .githubSearch,
                args: CommandArgs(filter: searchQuery, searchScope: "code"),
                justification: "User searching GitHub",
                expectedEffect: "Return search results"
            )
            let result = await commandExecutor.execute(command, sessionId: UUID().uuidString)
            if !result.success {
                self.error = result.error ?? "Search failed"
            }
            isLoading = false
        }
    }
}
