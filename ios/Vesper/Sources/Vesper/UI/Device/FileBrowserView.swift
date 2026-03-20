import SwiftUI

@Observable
class FileBrowserViewModel {
    private let fileSystem: FlipperFileSystem
    var currentPath: String = "/ext"
    var entries: [FileEntry] = []
    var isLoading: Bool = false
    var error: String?
    var selectedFileContent: String?
    var selectedFileName: String?
    var pathHistory: [String] = ["/ext"]

    init(fileSystem: FlipperFileSystem) {
        self.fileSystem = fileSystem
    }

    func loadDirectory() {
        isLoading = true
        error = nil
        Task {
            do {
                entries = try await fileSystem.listDirectory(currentPath)
                entries.sort { a, b in
                    if a.isDirectory != b.isDirectory {
                        return a.isDirectory
                    }
                    return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
                }
            } catch {
                self.error = error.localizedDescription
            }
            isLoading = false
        }
    }

    func navigateTo(_ entry: FileEntry) {
        if entry.isDirectory {
            pathHistory.append(entry.path)
            currentPath = entry.path
            loadDirectory()
        } else {
            loadFileContent(entry)
        }
    }

    func navigateUp() {
        guard pathHistory.count > 1 else { return }
        pathHistory.removeLast()
        currentPath = pathHistory.last ?? "/ext"
        loadDirectory()
    }

    func navigateToRoot(_ root: String) {
        pathHistory = [root]
        currentPath = root
        loadDirectory()
    }

    private func loadFileContent(_ entry: FileEntry) {
        isLoading = true
        Task {
            do {
                selectedFileContent = try await fileSystem.readFile(entry.path)
                selectedFileName = entry.name
            } catch {
                self.error = error.localizedDescription
            }
            isLoading = false
        }
    }

    func deleteEntry(_ entry: FileEntry) {
        Task {
            do {
                try await fileSystem.delete(entry.path, recursive: entry.isDirectory)
                loadDirectory()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct FileBrowserView: View {
    @Bindable var viewModel: FileBrowserViewModel

    var body: some View {
        VStack(spacing: 0) {
            pathBar
            Divider()

            if viewModel.isLoading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = viewModel.error {
                ContentUnavailableView {
                    Label("Error", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error)
                } actions: {
                    Button("Retry") { viewModel.loadDirectory() }
                }
            } else {
                fileList
            }
        }
        .navigationTitle("File Browser")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Menu {
                    Button("Internal (/int)") { viewModel.navigateToRoot("/int") }
                    Button("SD Card (/ext)") { viewModel.navigateToRoot("/ext") }
                } label: {
                    Image(systemName: "folder.badge.gear")
                }
            }
        }
        .onAppear { viewModel.loadDirectory() }
        .sheet(item: fileContentBinding) { item in
            FileContentSheet(fileName: item.name, content: item.content)
        }
    }

    private var pathBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(viewModel.pathHistory.enumerated()), id: \.offset) { index, path in
                    if index > 0 {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Button(pathComponent(path)) {
                        while viewModel.pathHistory.count > index + 1 {
                            viewModel.pathHistory.removeLast()
                        }
                        viewModel.currentPath = path
                        viewModel.loadDirectory()
                    }
                    .font(.caption.monospaced())
                    .foregroundStyle(index == viewModel.pathHistory.count - 1 ? .primary : .accentColor)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(.systemGray6))
    }

    private var fileList: some View {
        List {
            if viewModel.pathHistory.count > 1 {
                Button {
                    viewModel.navigateUp()
                } label: {
                    HStack {
                        Image(systemName: "arrow.up.left")
                            .foregroundStyle(.secondary)
                        Text("..")
                            .font(.body.monospaced())
                    }
                }
            }

            ForEach(viewModel.entries, id: \.path) { entry in
                Button {
                    viewModel.navigateTo(entry)
                } label: {
                    HStack {
                        Image(systemName: entry.isDirectory ? "folder.fill" : fileIcon(entry.name))
                            .foregroundStyle(entry.isDirectory ? .yellow : .accentColor)
                            .frame(width: 24)
                        VStack(alignment: .leading) {
                            Text(entry.name)
                                .font(.body.monospaced())
                                .lineLimit(1)
                            if !entry.isDirectory {
                                Text(formatBytes(entry.size))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if entry.isDirectory {
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .tint(.primary)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) {
                        viewModel.deleteEntry(entry)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    private func pathComponent(_ path: String) -> String {
        if path == "/ext" || path == "/int" { return path }
        return (path as NSString).lastPathComponent
    }

    private func fileIcon(_ name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "sub": return "antenna.radiowaves.left.and.right"
        case "ir": return "infrared"
        case "nfc": return "wave.3.right"
        case "rfid": return "sensor.tag.radiowaves.forward"
        case "ibtn": return "key.fill"
        case "txt", "log": return "doc.text"
        case "js": return "curlybraces"
        case "fap": return "app.badge"
        default: return "doc"
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    private var fileContentBinding: Binding<FileContentItem?> {
        Binding(
            get: {
                if let name = viewModel.selectedFileName, let content = viewModel.selectedFileContent {
                    return FileContentItem(name: name, content: content)
                }
                return nil
            },
            set: { _ in
                viewModel.selectedFileName = nil
                viewModel.selectedFileContent = nil
            }
        )
    }
}

struct FileContentItem: Identifiable {
    let id = UUID()
    let name: String
    let content: String
}

struct FileContentSheet: View {
    let fileName: String
    let content: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(content)
                    .font(.system(.body, design: .monospaced))
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(fileName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(item: content)
                }
            }
        }
    }
}
