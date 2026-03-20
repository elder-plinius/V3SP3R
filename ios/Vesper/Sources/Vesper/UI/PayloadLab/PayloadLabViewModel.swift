import SwiftUI

@Observable
class PayloadLabViewModel {
    private let payloadEngine: PayloadEngine
    private let fileSystem: FlipperFileSystem

    var payloadType: String = "subghz"
    var prompt: String = ""
    var isGenerating: Bool = false
    var generatedPayload: GeneratedPayload?
    var validation: PayloadValidation?
    var error: String?
    var isSaving: Bool = false

    static let payloadTypes = [
        ("subghz", "Sub-GHz", "antenna.radiowaves.left.and.right"),
        ("ir", "Infrared", "infrared"),
        ("nfc", "NFC", "wave.3.right"),
        ("rfid", "RFID", "sensor.tag.radiowaves.forward"),
        ("ibutton", "iButton", "key.fill"),
        ("badusb", "BadUSB", "keyboard"),
    ]

    init(payloadEngine: PayloadEngine, fileSystem: FlipperFileSystem) {
        self.payloadEngine = payloadEngine
        self.fileSystem = fileSystem
    }

    func generate() {
        guard !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        isGenerating = true
        error = nil
        generatedPayload = nil
        validation = nil

        Task {
            do {
                let payload = try await payloadEngine.generatePayload(type: payloadType, prompt: prompt)
                generatedPayload = payload
                validation = payloadEngine.validatePayload(payload)
            } catch {
                self.error = error.localizedDescription
            }
            isGenerating = false
        }
    }

    func saveToFlipper() {
        guard let payload = generatedPayload else { return }
        isSaving = true
        error = nil

        Task {
            do {
                let path = "/ext/\(payloadType)/\(payload.filename)"
                _ = try await fileSystem.writeFile(path, content: payload.content)
            } catch {
                self.error = error.localizedDescription
            }
            isSaving = false
        }
    }
}
