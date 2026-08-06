import Foundation
import Observation
import SwiftData
import UIKit
import VesperCore

@MainActor
@Observable
final class AppModel {
    private let context: ModelContext
    let transport: CoreBluetoothTransport
    private let rpc: FlipperRPCService
    private let keychain: KeychainStore
    private let auditStream: AuditStream
    private let executor: CommandExecutor
    private let agent: VesperAgent
    private var sessionID: UUID
    private var lastPersistedMessageIDs = Set<UUID>()
    private var reconnectInFlight = false

    var agentState = AgentSnapshot()
    var connectionState: TransportConnectionState = .idle
    var discoveredDevices: [DiscoveredFlipper] = []
    var deviceInfo: DeviceInfo?
    var storageInfo: StorageInfo?
    var diagnostics = "Not connected"
    var files: [FileEntry] = []
    var currentPath = "/ext"
    var selectedFileContent: String?
    var auditEvents: [AuditEvent] = []
    var isScanning = false
    var isBusy = false
    var alertMessage: String?
    var pendingImages: [AgentImage] = []
    var permissionStatus: String?
    var model: String {
        didSet { UserDefaults.standard.set(model, forKey: "openrouter_model") }
    }

    init(context: ModelContext) {
        self.context = context
        sessionID = UUID()
        model = UserDefaults.standard.string(forKey: "openrouter_model") ?? "anthropic/claude-sonnet-4"
        transport = CoreBluetoothTransport()
        rpc = FlipperRPCService(transport: transport)
        keychain = KeychainStore()
        auditStream = AuditStream()
        executor = CommandExecutor(rpc: rpc, audit: auditStream, grants: [
            PermissionGrant(pathPrefix: "/ext/apps_data/vesper", expiresAt: .distantFuture)
        ])
        let keychainRef = keychain
        let llm = OpenRouterClient(keychain: keychainRef, modelProvider: {
            UserDefaults.standard.string(forKey: "openrouter_model") ?? "anthropic/claude-sonnet-4"
        })
        agent = VesperAgent(llm: llm, executor: executor)
        restorePersistence()
        observeServices()
    }

    func send(_ text: String) async {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pendingImages.isEmpty else { return }
        let images = pendingImages
        pendingImages.removeAll()
        agentState = await agent.send(text, images: images)
        persistMessages()
    }

    func decideApproval(approved: Bool) async {
        guard let approval = agentState.pendingApproval else { return }
        agentState = await agent.decideApproval(id: approval.id, approved: approved)
        persistMessages()
    }

    func appDidEnterBackground() async {
        await agent.appDidEnterBackground()
        agentState = await agent.state()
    }

    func appDidBecomeActive() async {
        await agent.appDidBecomeActive()
        guard UserDefaults.standard.string(forKey: "last_flipper_id") != nil else { return }
        switch connectionState {
        case .connected, .connecting, .scanning: return
        default: try? await transport.scan()
        }
    }

    func scan() async {
        do { try await transport.scan(); isScanning = true }
        catch { alertMessage = error.localizedDescription }
    }

    func connect(_ device: DiscoveredFlipper) async {
        isBusy = true
        defer { isBusy = false }
        do {
            try await transport.connect(to: device.id)
            UserDefaults.standard.set(device.id.uuidString, forKey: "last_flipper_id")
            await refreshDevice()
        } catch { alertMessage = error.localizedDescription }
    }

    func disconnect() async { await transport.disconnect() }

    func refreshDevice() async {
        do {
            async let device = rpc.getDeviceInfo()
            async let storage = rpc.getStorageInfo()
            deviceInfo = try await device
            storageInfo = try await storage
            diagnostics = await rpc.diagnostics()
        } catch { alertMessage = error.localizedDescription }
    }

    func loadDirectory(_ path: String) async {
        isBusy = true
        defer { isBusy = false }
        do {
            currentPath = try PathSecurity.normalize(path)
            files = try await rpc.listDirectory(currentPath)
        } catch { alertMessage = error.localizedDescription }
    }

    func open(_ entry: FileEntry) async {
        if entry.isDirectory { await loadDirectory(entry.path); return }
        do { selectedFileContent = String(decoding: try await rpc.readFile(entry.path), as: UTF8.self) }
        catch { alertMessage = error.localizedDescription }
    }

    func navigateUp() async {
        guard currentPath != "/ext" else { return }
        await loadDirectory(URL(fileURLWithPath: currentPath).deletingLastPathComponent().path)
    }

    func saveAPIKey(_ value: String) async throws {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { try await keychain.remove("openrouter_api_key") }
        else { try await keychain.set(trimmed, for: "openrouter_api_key") }
    }

    func hasAPIKey() async -> Bool { (try? await keychain.value(for: "openrouter_api_key"))?.isEmpty == false }

    func grantProtectedPath(_ value: String) async throws {
        let path = try PathSecurity.normalize(value)
        guard PathSecurity.isProtected(path) else {
            throw VesperCoreError.invalidCommand("Only protected system, firmware, or sensitive-file paths need this unlock")
        }
        let expiresAt = Date.now.addingTimeInterval(15 * 60)
        await executor.replaceGrants([
            PermissionGrant(pathPrefix: "/ext/apps_data/vesper", expiresAt: .distantFuture),
            PermissionGrant(pathPrefix: path, expiresAt: expiresAt, unlocksProtectedPath: true)
        ])
        permissionStatus = "Unlocked \(path) until \(expiresAt.formatted(date: .omitted, time: .shortened))"
    }

    func addImage(data: Data, mimeType: String = "image/jpeg") throws {
        guard pendingImages.count < 4 else { throw ImageError.tooMany }
        guard data.count <= PathSecurity.maximumContentBytes else { throw ImageError.tooLarge }
        guard let image = UIImage(data: data), let resized = image.vesperJPEG() else { throw ImageError.invalid }
        pendingImages.append(AgentImage(mimeType: mimeType, base64Data: resized.base64EncodedString()))
    }

    func removeImage(_ id: UUID) { pendingImages.removeAll { $0.id == id } }

    private func observeServices() {
        Task {
            for await state in await transport.connectionStates() {
                connectionState = state
                if case .connected = state { isScanning = false }
            }
        }
        Task {
            for await devices in await transport.discoveredDevices() {
                discoveredDevices = devices
                guard !reconnectInFlight,
                      let saved = UserDefaults.standard.string(forKey: "last_flipper_id"),
                      let id = UUID(uuidString: saved),
                      let remembered = devices.first(where: { $0.id == id }) else { continue }
                reconnectInFlight = true
                await connect(remembered)
                reconnectInFlight = false
            }
        }
        Task {
            for await event in await auditStream.events() {
                auditEvents.insert(event, at: 0)
                context.insert(StoredAuditEvent(event: event))
                try? context.save()
            }
        }
    }

    private func restorePersistence() {
        let messageDescriptor = FetchDescriptor<StoredMessage>(sortBy: [SortDescriptor(\.timestamp)])
        let storedMessages = (try? context.fetch(messageDescriptor)) ?? []
        if let existingSession = storedMessages.last?.sessionID { sessionID = existingSession }
        let messages = storedMessages.filter { $0.sessionID == sessionID }.compactMap(\.agentMessage)
        lastPersistedMessageIDs = Set(messages.map(\.id))
        Task { await agent.restore(messages: messages); agentState = await agent.state() }

        let auditDescriptor = FetchDescriptor<StoredAuditEvent>(sortBy: [SortDescriptor(\.timestamp, order: .reverse)])
        auditEvents = ((try? context.fetch(auditDescriptor)) ?? []).prefix(500).compactMap(\.event)
    }

    private func persistMessages() {
        for message in agentState.messages where !lastPersistedMessageIDs.contains(message.id) {
            context.insert(StoredMessage(message: message, sessionID: sessionID))
            lastPersistedMessageIDs.insert(message.id)
        }
        try? context.save()
    }
}

private extension UIImage {
    func vesperJPEG() -> Data? {
        let maximum: CGFloat = 1024
        let scale = min(1, maximum / max(size.width, size.height))
        let target = CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
        let renderer = UIGraphicsImageRenderer(size: target)
        return renderer.image { _ in draw(in: CGRect(origin: .zero, size: target)) }.jpegData(compressionQuality: 0.85)
    }
}

enum ImageError: LocalizedError {
    case tooMany, tooLarge, invalid
    var errorDescription: String? {
        switch self {
        case .tooMany: "You can attach up to four images."
        case .tooLarge: "Images must be 10 MB or smaller."
        case .invalid: "That image could not be processed."
        }
    }
}
