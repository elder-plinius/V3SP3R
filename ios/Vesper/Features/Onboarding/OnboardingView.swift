import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    let completion: () -> Void
    @State private var page = 0
    @State private var accepted = false
    @State private var key = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Image(systemName: icons[page]).font(.system(size: 72)).foregroundStyle(Color.vesperPurple).symbolEffect(.pulse)
            Text(titles[page]).font(.largeTitle.bold()).multilineTextAlignment(.center)
            Text(details[page]).font(.title3).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal)
            if page == 1 { SecureField("OpenRouter API key", text: $key).textFieldStyle(.roundedBorder).privacySensitive().padding(.horizontal) }
            if page == 2 {
                Toggle("I will use Vesper only on systems I own or am explicitly authorized to test.", isOn: $accepted)
                    .accessibilityIdentifier("authorizedUseToggle")
                    .padding(.horizontal)
            }
            if let error { Text(error).foregroundStyle(.red).font(.footnote) }
            Spacer()
            Button(page == 2 ? "Enter Vesper" : "Continue") {
                Task {
                    if page == 1, !key.isEmpty { do { try await model.saveAPIKey(key) } catch { self.error = error.localizedDescription; return } }
                    if page < 2 { withAnimation { page += 1 } } else { completion() }
                }
            }
            .buttonStyle(.borderedProminent).tint(Color.vesperPurple).controlSize(.large)
            .accessibilityIdentifier("enterVesperButton")
            .disabled(page == 2 && !accepted)
            if page == 1 { Button("Set up later") { withAnimation { page += 1 } } }
        }.padding().interactiveDismissDisabled()
    }

    private let icons = ["wave.3.right.circle.fill", "key.fill", "checkmark.shield.fill"]
    private let titles = ["Meet Vesper", "Connect OpenRouter", "Authorized Use Only"]
    private let details = ["Control your Flipper Zero through natural language over Bluetooth.", "Your key stays in this iPhone's Keychain and is sent only to OpenRouter.", "RF transmission, credential emulation, BadUSB, and other hardware actions can affect real systems."]
}
