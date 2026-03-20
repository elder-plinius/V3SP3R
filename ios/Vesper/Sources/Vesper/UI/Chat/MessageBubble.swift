import SwiftUI

struct MessageBubble: View {
    let message: ChatMessage
    let onApprove: ((String) -> Void)?
    let onDeny: ((String) -> Void)?

    init(message: ChatMessage, onApprove: ((String) -> Void)? = nil, onDeny: ((String) -> Void)? = nil) {
        self.message = message
        self.onApprove = onApprove
        self.onDeny = onDeny
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if message.role == .assistant || message.role == .system {
                avatarView
            }
            if message.role == .user {
                Spacer(minLength: 48)
            }

            VStack(alignment: message.role == .user ? .trailing : .leading, spacing: 4) {
                if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
                    ForEach(toolCalls, id: \.id) { toolCall in
                        toolCallView(toolCall)
                    }
                }

                if let content = message.content, !content.isEmpty {
                    Text(content)
                        .font(.body)
                        .foregroundStyle(message.role == .user ? .white : .primary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(bubbleBackground)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                }

                if message.isError {
                    Label("Error", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                Text(message.timestamp, style: .time)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if message.role == .assistant || message.role == .system {
                Spacer(minLength: 48)
            }
            if message.role == .user {
                userAvatarView
            }
        }
        .padding(.horizontal, 12)
    }

    private var bubbleBackground: some ShapeStyle {
        message.role == .user
            ? AnyShapeStyle(Color.accentColor)
            : AnyShapeStyle(Color(.systemGray6))
    }

    private var avatarView: some View {
        Image(systemName: "cpu")
            .font(.title3)
            .foregroundStyle(.purple)
            .frame(width: 32, height: 32)
            .background(Color.purple.opacity(0.15))
            .clipShape(Circle())
    }

    private var userAvatarView: some View {
        Image(systemName: "person.fill")
            .font(.title3)
            .foregroundStyle(.blue)
            .frame(width: 32, height: 32)
            .background(Color.blue.opacity(0.15))
            .clipShape(Circle())
    }

    private func toolCallView(_ toolCall: ToolCall) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "terminal")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Text(toolCall.name)
                    .font(.caption.monospaced())
                    .foregroundStyle(.orange)
            }

            if !toolCall.arguments.isEmpty {
                Text(toolCall.arguments)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
            }
        }
        .padding(10)
        .background(Color.orange.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
