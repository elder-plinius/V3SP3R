import SwiftUI

struct FapHubView: View {
    @Bindable var viewModel: FapHubViewModel

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            categoryBar
            Divider()

            if viewModel.isLoading {
                ProgressView("Searching FapHub...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.apps.isEmpty {
                ContentUnavailableView {
                    Label("FapHub", systemImage: "app.badge")
                } description: {
                    Text("Search for Flipper apps to install on your device.")
                } actions: {
                    if !viewModel.searchQuery.isEmpty {
                        Button("Search Again") { viewModel.searchApps() }
                    }
                }
            } else {
                appList
            }
        }
        .navigationTitle("FapHub")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search apps...", text: $viewModel.searchQuery)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { viewModel.searchApps() }
            if !viewModel.searchQuery.isEmpty {
                Button {
                    viewModel.searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private var categoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.categories, id: \.self) { category in
                    Button {
                        viewModel.selectedCategory = category == "All" ? nil : category
                    } label: {
                        Text(category)
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(
                                (viewModel.selectedCategory == category || (viewModel.selectedCategory == nil && category == "All"))
                                    ? Color.accentColor.opacity(0.2) : Color(.systemGray6)
                            )
                            .clipShape(Capsule())
                    }
                    .tint(.primary)
                }
            }
            .padding(.horizontal)
        }
        .padding(.bottom, 4)
    }

    private var appList: some View {
        List {
            ForEach(viewModel.apps) { app in
                FapAppRow(
                    app: app,
                    isInstalling: viewModel.isInstalling == app.uid
                ) {
                    viewModel.installApp(app)
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
}

struct FapAppRow: View {
    let app: FapApp
    let isInstalling: Bool
    let onInstall: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "app.badge")
                .font(.title2)
                .foregroundStyle(.purple)
                .frame(width: 40, height: 40)
                .background(Color.purple.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                    .font(.body.bold())
                Text(app.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    Label(app.author, systemImage: "person")
                    Label(app.category, systemImage: "tag")
                    Label("v\(app.version)", systemImage: "number")
                }
                .font(.caption2)
                .foregroundStyle(.tertiary)
            }

            Spacer()

            Button {
                onInstall()
            } label: {
                if isInstalling {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "arrow.down.circle")
                        .font(.title3)
                }
            }
            .disabled(isInstalling)
        }
        .padding(.vertical, 4)
    }
}
