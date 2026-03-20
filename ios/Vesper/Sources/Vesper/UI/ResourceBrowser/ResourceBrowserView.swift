import SwiftUI

struct ResourceBrowserView: View {
    @Bindable var viewModel: ResourceBrowserViewModel

    var body: some View {
        Group {
            if viewModel.selectedRepo != nil {
                repoContentsView
            } else {
                repoListView
            }
        }
        .navigationTitle(viewModel.selectedRepo?.name ?? "Resources")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if viewModel.selectedRepo != nil {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Repos") {
                        viewModel.selectedRepo = nil
                        viewModel.entries = []
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.searchGitHub()
                } label: {
                    Image(systemName: "magnifyingglass")
                }
            }
        }
    }

    private var repoListView: some View {
        List {
            Section {
                HStack {
                    TextField("Search GitHub...", text: $viewModel.searchQuery)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { viewModel.searchGitHub() }
                    Button("Search") { viewModel.searchGitHub() }
                        .disabled(viewModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }

            Section("Known Repositories") {
                ForEach(viewModel.repos) { repo in
                    Button {
                        viewModel.browseRepo(repo)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: repo.icon)
                                .font(.title3)
                                .foregroundStyle(.purple)
                                .frame(width: 36, height: 36)
                                .background(Color.purple.opacity(0.1))
                                .clipShape(RoundedRectangle(cornerRadius: 8))

                            VStack(alignment: .leading, spacing: 2) {
                                Text(repo.name)
                                    .font(.body.bold())
                                Text(repo.description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                                HStack {
                                    Label(repo.owner, systemImage: "person")
                                    Label(repo.category, systemImage: "tag")
                                }
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            }

                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .tint(.primary)
                }
            }

            if let error = viewModel.error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.caption)
                }
            }
        }
    }

    private var repoContentsView: some View {
        List {
            if !viewModel.currentPath.isEmpty {
                breadcrumbView
            }

            if viewModel.isLoading {
                HStack {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading...")
                        .foregroundStyle(.secondary)
                }
            } else if viewModel.entries.isEmpty {
                Text("No files found")
                    .foregroundStyle(.secondary)
            } else {
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

                ForEach(viewModel.entries) { entry in
                    if entry.isDirectory {
                        Button {
                            viewModel.navigateTo(entry)
                        } label: {
                            HStack {
                                Image(systemName: "folder.fill")
                                    .foregroundStyle(.yellow)
                                Text(entry.name)
                                    .font(.body.monospaced())
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .tint(.primary)
                    } else {
                        HStack {
                            Image(systemName: "doc")
                                .foregroundStyle(.accentColor)
                            VStack(alignment: .leading) {
                                Text(entry.name)
                                    .font(.body.monospaced())
                                    .lineLimit(1)
                                if entry.size > 0 {
                                    Text(ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button {
                                viewModel.downloadFile(entry)
                            } label: {
                                if viewModel.isDownloading == entry.name {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Image(systemName: "arrow.down.circle")
                                }
                            }
                        }
                    }
                }
            }

            if let error = viewModel.error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.caption)
                }
            }
        }
        .listStyle(.plain)
    }

    private var breadcrumbView: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(viewModel.pathHistory.enumerated()), id: \.offset) { index, path in
                    if index > 0 {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Button(path.isEmpty ? "/" : (path as NSString).lastPathComponent) {
                        while viewModel.pathHistory.count > index + 1 {
                            viewModel.pathHistory.removeLast()
                        }
                        viewModel.currentPath = path
                        viewModel.loadRepoContents()
                    }
                    .font(.caption.monospaced())
                }
            }
        }
    }
}
