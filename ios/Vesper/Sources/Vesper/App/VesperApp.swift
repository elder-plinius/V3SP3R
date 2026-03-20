import SwiftUI
import SwiftData

@main
struct VesperApp: App {
    @State private var serviceLocator = ServiceLocator()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(serviceLocator)
        }
        .modelContainer(for: [ChatSessionEntity.self, AuditEntryEntity.self])
    }
}

/// Manual dependency injection container (replaces Hilt DI from Android)
@Observable
class ServiceLocator {
    // Data layer
    let secureStorage = SecureStorage()
    let settingsStore = SettingsStore()
    lazy var chatStore: ChatStore = {
        (try? ChatStore()) ?? ChatStore(modelContainer: try! ModelContainer(for: ChatSessionEntity.self))
    }()
    lazy var auditStoreImpl = InMemoryAuditStore()

    // BLE layer
    lazy var bleManager = FlipperBLEManager()
    lazy var flipperProtocol = FlipperProtocol(bleManager: bleManager)
    lazy var fileSystem = FlipperFileSystem(protocol: flipperProtocol)

    // Domain layer
    lazy var auditService = AuditService(store: auditStoreImpl)
    lazy var riskAssessor = RiskAssessor(settingsStore: settingsStore)
    lazy var commandExecutor = CommandExecutor(
        fileSystem: fileSystem,
        riskAssessor: riskAssessor,
        auditService: auditService,
        settingsStore: settingsStore
    )

    // AI layer
    lazy var openRouterClient = OpenRouterClient(settingsStore: settingsStore)
    lazy var payloadEngine = PayloadEngine(openRouterClient: openRouterClient, settingsStore: settingsStore)
    lazy var vesperAgent = VesperAgent(
        openRouterClient: openRouterClient,
        commandExecutor: commandExecutor,
        auditService: auditService,
        chatStore: chatStore,
        settingsStore: settingsStore
    )

    // Voice layer
    lazy var speechRecognizer = SpeechRecognizer()
    lazy var ttsService = TTSService()

    // Glasses layer
    lazy var glassesBridgeClient = GlassesBridgeClient()

    // ViewModels
    lazy var chatViewModel = ChatViewModel(
        agent: vesperAgent,
        speechRecognizer: speechRecognizer,
        ttsService: ttsService
    )
    lazy var deviceViewModel = DeviceViewModel(
        bleManager: bleManager,
        fileSystem: fileSystem
    )
    lazy var fileBrowserViewModel = FileBrowserViewModel(fileSystem: fileSystem)
    lazy var settingsViewModel = SettingsViewModel(
        settingsStore: settingsStore,
        secureStorage: secureStorage
    )
    lazy var auditLogViewModel = AuditLogViewModel(auditService: auditService)
    lazy var opsCenterViewModel = OpsCenterViewModel(
        bleManager: bleManager,
        commandExecutor: commandExecutor,
        auditService: auditService
    )
    lazy var alchemyLabViewModel = AlchemyLabViewModel(fileSystem: fileSystem)
    lazy var payloadLabViewModel = PayloadLabViewModel(
        payloadEngine: payloadEngine,
        fileSystem: fileSystem
    )
    lazy var fapHubViewModel = FapHubViewModel(commandExecutor: commandExecutor)
    lazy var resourceBrowserViewModel = ResourceBrowserViewModel(commandExecutor: commandExecutor)
}

struct ContentView: View {
    @Environment(ServiceLocator.self) private var services

    var body: some View {
        TabView {
            Tab("Chat", systemImage: "message.fill") {
                NavigationStack {
                    ChatView(viewModel: services.chatViewModel)
                }
            }

            Tab("Device", systemImage: "flipphone") {
                NavigationStack {
                    DeviceView(viewModel: services.deviceViewModel)
                }
            }

            Tab("Ops", systemImage: "gauge.with.dots.needle.33percent") {
                NavigationStack {
                    OpsCenterView(viewModel: services.opsCenterViewModel)
                }
            }

            Tab("Tools", systemImage: "wrench.and.screwdriver") {
                NavigationStack {
                    ToolsMenuView()
                }
            }

            Tab("Settings", systemImage: "gear") {
                NavigationStack {
                    SettingsView(viewModel: services.settingsViewModel)
                }
            }
        }
        .tint(.purple)
    }
}

struct ToolsMenuView: View {
    @Environment(ServiceLocator.self) private var services

    var body: some View {
        List {
            Section("Lab") {
                NavigationLink {
                    AlchemyLabView(viewModel: services.alchemyLabViewModel)
                } label: {
                    Label("Alchemy Lab", systemImage: "flask")
                }

                NavigationLink {
                    PayloadLabView(viewModel: services.payloadLabViewModel)
                } label: {
                    Label("Payload Lab", systemImage: "wand.and.stars")
                }
            }

            Section("Browse") {
                NavigationLink {
                    FileBrowserView(viewModel: services.fileBrowserViewModel)
                } label: {
                    Label("File Browser", systemImage: "folder")
                }

                NavigationLink {
                    FapHubView(viewModel: services.fapHubViewModel)
                } label: {
                    Label("FapHub", systemImage: "app.badge")
                }

                NavigationLink {
                    ResourceBrowserView(viewModel: services.resourceBrowserViewModel)
                } label: {
                    Label("Resource Browser", systemImage: "globe")
                }
            }

            Section("Security") {
                NavigationLink {
                    AuditLogView(viewModel: services.auditLogViewModel)
                } label: {
                    Label("Audit Log", systemImage: "doc.text.magnifyingglass")
                }
            }
        }
        .navigationTitle("Tools")
        .navigationBarTitleDisplayMode(.inline)
    }
}
