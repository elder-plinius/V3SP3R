import Speech
import AVFoundation

@Observable
final class SpeechRecognizer {

    // MARK: - Published State

    var transcript: String = ""
    var isRecording: Bool = false
    var isAvailable: Bool = false
    var error: String? = nil

    // MARK: - Private

    private var audioEngine = AVAudioEngine()
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    // MARK: - Init

    init(locale: Locale = .current) {
        speechRecognizer = SFSpeechRecognizer(locale: locale)
        isAvailable = speechRecognizer?.isAvailable ?? false
    }

    // MARK: - Authorization

    /// Requests authorization for both speech recognition and microphone access.
    /// Returns `true` only when both are granted.
    func requestAuthorization() async -> Bool {
        let speechGranted = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }

        guard speechGranted else {
            error = "Speech recognition permission denied."
            return false
        }

        let micGranted: Bool
        if #available(iOS 17, *) {
            micGranted = await AVAudioApplication.requestRecordPermission()
        } else {
            micGranted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }

        guard micGranted else {
            error = "Microphone permission denied."
            return false
        }

        isAvailable = true
        return true
    }

    // MARK: - Recording

    /// Starts a live recognition session.
    /// Throws if the audio session or engine cannot be configured.
    func startRecording() throws {
        // Cancel any in-flight task.
        resetTask()

        guard let speechRecognizer, speechRecognizer.isAvailable else {
            throw SpeechError.recognizerUnavailable
        }

        // Configure audio session.
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

        // Build recognition request.
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        self.recognitionRequest = request

        // Reset transcript.
        transcript = ""
        error = nil

        // Start recognition task.
        recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, taskError in
            guard let self else { return }

            if let taskError {
                // Ignore cancellation errors that fire during normal stop.
                let nsError = taskError as NSError
                if nsError.domain == "kAFAssistantErrorDomain" && nsError.code == 216 {
                    // "Request was canceled" — expected during stopRecording.
                    return
                }
                self.error = taskError.localizedDescription
                self.stopRecordingInternal()
                return
            }

            guard let result else { return }

            self.transcript = result.bestTranscription.formattedString

            if result.isFinal {
                self.stopRecordingInternal()
            }
        }

        // Install audio tap.
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)

        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { [weak self] buffer, _ in
            self?.recognitionRequest?.append(buffer)
        }

        audioEngine.prepare()
        try audioEngine.start()

        isRecording = true
    }

    /// Stops recording and returns the final transcript.
    @discardableResult
    func stopRecording() -> String {
        stopRecordingInternal()
        return transcript
    }

    /// Cancels recording without caring about the result.
    func cancelRecording() {
        transcript = ""
        stopRecordingInternal()
    }

    // MARK: - Private Helpers

    private func stopRecordingInternal() {
        guard isRecording else { return }

        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)

        recognitionRequest?.endAudio()
        recognitionRequest = nil

        recognitionTask?.cancel()
        recognitionTask = nil

        deactivateAudioSession()

        isRecording = false
    }

    private func resetTask() {
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest?.endAudio()
        recognitionRequest = nil
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

// MARK: - Errors

extension SpeechRecognizer {
    enum SpeechError: LocalizedError {
        case recognizerUnavailable

        var errorDescription: String? {
            switch self {
            case .recognizerUnavailable:
                return "Speech recognizer is not available for the current locale."
            }
        }
    }
}
