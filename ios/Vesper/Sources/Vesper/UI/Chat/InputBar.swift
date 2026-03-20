import SwiftUI

struct InputBar: View {
    @Binding var text: String
    let isLoading: Bool
    let isRecording: Bool
    let onSend: () -> Void
    let onVoice: () -> Void
    let onCamera: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onCamera) {
                Image(systemName: "camera.fill")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            .disabled(isLoading)

            HStack(spacing: 8) {
                TextField("Message Vesper...", text: $text, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...5)
                    .focused($isFocused)
                    .disabled(isLoading)
                    .onSubmit {
                        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            onSend()
                        }
                    }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 20))

            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button(action: onVoice) {
                    Image(systemName: isRecording ? "mic.fill" : "mic")
                        .font(.title3)
                        .foregroundStyle(isRecording ? .red : .accentColor)
                        .symbolEffect(.pulse, isActive: isRecording)
                }
                .disabled(isLoading)
            } else {
                Button(action: onSend) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.accentColor)
                }
                .disabled(isLoading || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }
}
