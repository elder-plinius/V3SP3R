import SwiftUI
import VesperCore

struct FileBrowserView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        List {
            Section { Text(model.currentPath).font(.system(.caption, design: .monospaced)).textSelection(.enabled) }
            ForEach(model.files) { entry in
                Button { Task { await model.open(entry) } } label: {
                    HStack { Image(systemName: entry.isDirectory ? "folder.fill" : "doc").foregroundStyle(entry.isDirectory ? Color.vesperPurple : Color.secondary); VStack(alignment: .leading) { Text(entry.name); if !entry.isDirectory { Text(ByteCountFormatter.string(fromByteCount: Int64(entry.size), countStyle: .file)).font(.caption).foregroundStyle(.secondary) } }; Spacer(); if entry.isDirectory { Image(systemName: "chevron.right").foregroundStyle(.tertiary) } }
                }.foregroundStyle(.primary)
            }
        }
        .navigationTitle("Files").navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { Task { await model.navigateUp() } } label: { Image(systemName: "arrow.up.to.line") }.disabled(model.currentPath == "/ext") } }
        .sheet(isPresented: Binding(get: { model.selectedFileContent != nil }, set: { if !$0 { model.selectedFileContent = nil } })) {
            NavigationStack { ScrollView { Text(model.selectedFileContent ?? "").font(.system(.body, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding() }.navigationTitle("File Preview").toolbar { Button("Done") { model.selectedFileContent = nil } } }
        }
    }
}
