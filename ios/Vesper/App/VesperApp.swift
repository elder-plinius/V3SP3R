import SwiftData
import SwiftUI

@main
struct VesperApp: App {
    private let container: ModelContainer
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        do {
            let configuration = ModelConfiguration("Vesper", cloudKitDatabase: .none)
            container = try ModelContainer(for: StoredMessage.self, StoredAuditEvent.self, configurations: configuration)
            try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: URL.applicationSupportDirectory.path)
            _model = State(initialValue: AppModel(context: container.mainContext))
        } catch {
            fatalError("Unable to create Vesper data store: \(error.localizedDescription)")
        }
    }

    var body: some Scene {
        WindowGroup { RootView().environment(model) }
            .modelContainer(container)
            .onChange(of: scenePhase) { _, phase in
                Task {
                    if phase == .active { await model.appDidBecomeActive() }
                    else { await model.appDidEnterBackground() }
                }
            }
    }
}
