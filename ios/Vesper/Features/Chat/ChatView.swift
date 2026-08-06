import PhotosUI
import SwiftUI
import VesperCore

struct ChatView: View {
    @Environment(AppModel.self) private var model
    @State private var input = ""
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showCamera = false
    @State private var showAudit = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            if model.agentState.messages.isEmpty { welcome }
                            ForEach(model.agentState.messages.filter { $0.role != .tool }) { message in
                                MessageBubble(message: message).id(message.id)
                            }
                            if let status = model.agentState.status {
                                HStack { ProgressView(); Text(status).foregroundStyle(.secondary); Spacer() }
                                    .padding(.horizontal)
                            }
                        }
                        .padding()
                    }
                    .onChange(of: model.agentState.messages.count) { _, _ in
                        if let id = model.agentState.messages.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                    }
                }

                if !model.pendingImages.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(model.pendingImages) { image in
                                ZStack(alignment: .topTrailing) {
                                    if let data = Data(base64Encoded: image.base64Data), let uiImage = UIImage(data: data) {
                                        Image(uiImage: uiImage).resizable().scaledToFill().frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 12))
                                    }
                                    Button { model.removeImage(image.id) } label: { Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.7)) }
                                        .offset(x: 6, y: -6)
                                }
                            }
                        }.padding(.horizontal).padding(.top, 8)
                    }
                }

                HStack(alignment: .bottom, spacing: 10) {
                    Menu {
                        PhotosPicker(selection: $photoItems, maxSelectionCount: max(0, 4 - model.pendingImages.count), matching: .images) {
                            Label("Photo Library", systemImage: "photo.on.rectangle")
                        }
                        Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
                    } label: { Image(systemName: "plus.circle.fill").font(.title2) }

                    TextField("Ask Vesper to control your Flipper…", text: $input, axis: .vertical)
                        .lineLimit(1...5).textFieldStyle(.roundedBorder)
                    Button {
                        let outgoing = input
                        input = ""
                        Task { await model.send(outgoing) }
                    } label: {
                        Image(systemName: "arrow.up.circle.fill").font(.title).foregroundStyle(Color.vesperPurple)
                    }
                    .disabled(model.agentState.isLoading || (input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && model.pendingImages.isEmpty))
                }
                .padding()
                .background(.bar)
            }
            .navigationTitle("Vesper")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAudit = true } label: { Label("Audit", systemImage: "clock.arrow.circlepath") }
                }
            }
            .navigationDestination(isPresented: $showAudit) { AuditView() }
            .sheet(isPresented: $showCamera) { CameraPicker { data in addImage(data) } }
            .sheet(isPresented: Binding(get: { model.agentState.pendingApproval != nil }, set: { _ in })) {
                if let approval = model.agentState.pendingApproval {
                    ApprovalSheet(approval: approval) { approved in Task { await model.decideApproval(approved: approved) } }
                        .presentationDetents([.medium, .large]).interactiveDismissDisabled()
                }
            }
            .onChange(of: photoItems) { _, items in
                Task {
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self) { addImage(data) }
                    }
                    photoItems.removeAll()
                }
            }
        }
    }

    private var welcome: some View {
        ContentUnavailableView("Your Flipper, with an AI brain", systemImage: "sparkles", description: Text("Connect a Flipper in Device, add your OpenRouter key in Settings, then ask Vesper what you want to do."))
            .padding(.top, 60)
    }

    private func addImage(_ data: Data) {
        do { try model.addImage(data: data) } catch { model.alertMessage = error.localizedDescription }
    }
}

private struct MessageBubble: View {
    let message: AgentMessage
    var body: some View {
        HStack {
            if message.role == .user { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 8) {
                ForEach(message.images) { image in
                    if let data = Data(base64Encoded: image.base64Data), let uiImage = UIImage(data: data) {
                        Image(uiImage: uiImage).resizable().scaledToFit().frame(maxHeight: 220).clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
                if !message.content.isEmpty { Text(message.content).textSelection(.enabled) }
            }
            .padding(12)
            .background(message.role == .user ? Color.vesperPurple : Color(uiColor: .secondarySystemBackground))
            .foregroundStyle(message.role == .user ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            if message.role != .user { Spacer(minLength: 48) }
        }
    }
}
