import SwiftUI

struct AIChatView: View {
    @State private var messages: [ChatMessage] = []
    @State private var inputText = ""
    @State private var isLoading = false
    @State private var sessionId: String?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("AI 助手")
                    .font(.headline)
                Spacer()
                Button("清空对话") {
                    messages.removeAll()
                    sessionId = nil
                }
                .buttonStyle(.plain)
                .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(NSColor.windowBackgroundColor))

            Divider()

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        if messages.isEmpty {
                            VStack(spacing: 16) {
                                Image(systemName: "bubble.left.and.bubble.right")
                                    .font(.system(size: 48))
                                    .foregroundColor(.secondary)
                                Text("👋 你好！我是 PaiSwitch AI 助手")
                                    .font(.headline)
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("你可以说：")
                                        .foregroundColor(.secondary)
                                    Text("• \"切换到 DeepSeek\"")
                                    Text("• \"帮我换成智谱 AI\"")
                                    Text("• \"用 OpenRouter\"")
                                }
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.top, 80)
                        }

                        ForEach(messages) { message in
                            MessageBubble(message: message)
                                .id(message.id)
                        }

                        if isLoading {
                            HStack {
                                ProgressView()
                                    .scaleEffect(0.8)
                                Text("思考中...")
                                    .foregroundColor(.secondary)
                                    .font(.subheadline)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal)
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { _ in
                    if let lastMessage = messages.last {
                        withAnimation {
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }
            }

            Divider()

            // Input
            HStack(spacing: 12) {
                TextField("输入消息，如：切换到 DeepSeek", text: $inputText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        sendMessage()
                    }

                Button {
                    sendMessage()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(inputText.trimmingCharacters(in: .whitespaces).isEmpty || isLoading)
            }
            .padding()
        }
    }

    private func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, !isLoading else { return }

        let userMessage = ChatMessage(role: .user, content: text)
        messages.append(userMessage)
        inputText = ""
        isLoading = true

        Task {
            do {
                let response = try await PaiSwitchService.shared.naturalLanguageSwitch(
                    prompt: text,
                    sessionId: sessionId
                )

                sessionId = response.sessionId

                let assistantMessage = ChatMessage(role: .assistant, content: response.aiResponse)
                messages.append(assistantMessage)

                if response.switchTriggered == true,
                   let result = response.switchResult,
                   result.success {
                    // Notify about successful switch
                    await MainActor.run {
                        NotificationCenter.default.post(
                            name: .providerSwitched,
                            object: result.currentProvider?.code
                        )
                    }
                }
            } catch {
                let errorMessage = ChatMessage(
                    role: .assistant,
                    content: "抱歉，发生了错误：\(error.localizedDescription)"
                )
                messages.append(errorMessage)
            }

            isLoading = false
        }
    }
}

struct ChatMessage: Identifiable, Equatable {
    let id = UUID()
    let role: Role
    let content: String

    enum Role {
        case user
        case assistant
    }
}

struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == .user {
                Spacer(minLength: 60)
            }

            Text(message.content)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(message.role == .user
                    ? Color.accentColor
                    : Color(NSColor.controlBackgroundColor))
                .foregroundColor(message.role == .user ? .white : .primary)
                .cornerRadius(16)

            if message.role == .assistant {
                Spacer(minLength: 60)
            }
        }
    }
}

extension Notification.Name {
    static let providerSwitched = Notification.Name("providerSwitched")
}
