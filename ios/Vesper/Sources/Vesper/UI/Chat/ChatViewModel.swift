import SwiftUI
import Combine

@Observable
class ChatViewModel {
    var messageText: String = ""
    var isRecording: Bool = false
    var showCamera: Bool = false
    var capturedImages: [ImageAttachment] = []

    private let agent: VesperAgent
    private let speechRecognizer: SpeechRecognizer
    private let ttsService: TTSService

    var conversationState: ConversationState {
        agent.conversationState
    }

    init(agent: VesperAgent, speechRecognizer: SpeechRecognizer, ttsService: TTSService) {
        self.agent = agent
        self.speechRecognizer = speechRecognizer
        self.ttsService = ttsService
    }

    func sendMessage() {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let images = capturedImages.isEmpty ? nil : capturedImages
        messageText = ""
        capturedImages = []

        Task {
            await agent.sendMessage(text, imageAttachments: images)
        }
    }

    func toggleRecording() {
        if isRecording {
            let transcript = speechRecognizer.stopRecording()
            isRecording = false
            if !transcript.isEmpty {
                messageText = transcript
            }
        } else {
            do {
                try speechRecognizer.startRecording()
                isRecording = true
            } catch {
                isRecording = false
            }
        }
    }

    func retryLastMessage() {
        Task {
            await agent.retryLastMessage()
        }
    }

    func startNewSession() {
        agent.startNewSession(deviceName: nil)
    }

    func approveCommand(_ approvalId: String) {
        Task {
            // CommandExecutor handles approval internally
        }
    }

    func denyCommand(_ approvalId: String) {
        // CommandExecutor handles denial internally
    }

    func speakResponse(_ text: String) {
        if ttsService.isSpeaking {
            ttsService.stop()
        } else {
            ttsService.speak(text)
        }
    }
}
