import SwiftUI

struct RootView: View {
    @AppStorage("onboarding_complete") private var onboardingComplete = false
    @State private var showOnboarding = false

    var body: some View {
        TabView {
            ChatView()
                .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right.fill") }
            DeviceView()
                .tabItem { Label("Device", systemImage: "wave.3.right.circle.fill") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .tint(Color.vesperPurple)
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("-ui-testing") { onboardingComplete = false }
            showOnboarding = !onboardingComplete
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView { onboardingComplete = true; showOnboarding = false }
        }
    }
}

extension Color {
    static let vesperPurple = Color(red: 0.57, green: 0.36, blue: 0.96)
    static let vesperCyan = Color(red: 0.18, green: 0.82, blue: 0.88)
}
