import AVFoundation

@Observable
final class TTSService: NSObject {

    // MARK: - Published State

    var isSpeaking: Bool = false

    // MARK: - Private

    private let synthesizer = AVSpeechSynthesizer()

    // MARK: - Init

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    // MARK: - Public API

    /// Speaks the given text using the default system voice.
    ///
    /// - Parameters:
    ///   - text: The string to speak.
    ///   - rate: Speech rate (0.0 – 1.0). Default `0.52` is slightly above normal.
    ///   - pitch: Voice pitch multiplier (0.5 – 2.0). Default `1.0`.
    func speak(_ text: String, rate: Float = 0.52, pitch: Float = 1.0) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // Stop any ongoing utterance before starting a new one.
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }

        configureAudioSession()

        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = clamp(rate, min: AVSpeechUtteranceMinimumSpeechRate,
                               max: AVSpeechUtteranceMaximumSpeechRate)
        utterance.pitchMultiplier = clamp(pitch, min: 0.5, max: 2.0)
        utterance.preUtteranceDelay = 0.05
        utterance.postUtteranceDelay = 0.0

        // Prefer a high-quality enhanced voice when available.
        if let voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode()) {
            utterance.voice = voice
        }

        isSpeaking = true
        synthesizer.speak(utterance)
    }

    /// Immediately stops any ongoing speech.
    func stop() {
        guard synthesizer.isSpeaking else { return }
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
        deactivateAudioSession()
    }

    // MARK: - Private Helpers

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
        try? session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func clamp(_ value: Float, min lo: Float, max hi: Float) -> Float {
        Swift.min(Swift.max(value, lo), hi)
    }
}

// MARK: - AVSpeechSynthesizerDelegate

extension TTSService: AVSpeechSynthesizerDelegate {

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        isSpeaking = true
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        isSpeaking = false
        deactivateAudioSession()
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        isSpeaking = false
        deactivateAudioSession()
    }
}
