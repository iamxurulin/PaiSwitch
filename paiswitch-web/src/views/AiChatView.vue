<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useProviderStore } from '@/stores/provider'
import type { NaturalLanguageResponse, ConversationMessage, ConversationHistoryResponse } from '@/types'

const providerStore = useProviderStore()

type UiMessage = { role: 'user' | 'assistant'; content: string }

const STORAGE_SESSION_KEY = 'paiswitch_ai_session_id'
const STORAGE_MESSAGES_KEY = 'paiswitch_ai_messages'
const STORAGE_CLEARED_FLAG_KEY = 'paiswitch_ai_cleared'

const messages = ref<UiMessage[]>([])
const inputText = ref('')
const loading = ref(false)
const sessionId = ref('')
const messagesContainer = ref<HTMLElement | null>(null)
const quickPrompts = [
  '切换到 DeepSeek',
  '帮我换成智谱 AI',
  '用 OpenRouter'
]

onMounted(async () => {
  const restored = restoreFromLocalStorage()
  const isCleared = localStorage.getItem(STORAGE_CLEARED_FLAG_KEY) === '1'
  if (!restored && !isCleared) {
    await loadLatestConversation()
  }
  await nextTick()
  scrollToBottom()
})

function restoreFromLocalStorage(): boolean {
  try {
    const storedSessionId = localStorage.getItem(STORAGE_SESSION_KEY)
    const storedMessages = localStorage.getItem(STORAGE_MESSAGES_KEY)
    if (!storedMessages) {
      return false
    }
    const parsedMessages = JSON.parse(storedMessages) as UiMessage[]
    if (!Array.isArray(parsedMessages) || parsedMessages.length === 0) {
      return false
    }
    messages.value = parsedMessages
    sessionId.value = storedSessionId || ''
    return true
  } catch {
    return false
  }
}

async function loadLatestConversation() {
  try {
    const history: ConversationHistoryResponse = await providerStore.getLatestConversation()
    sessionId.value = history.sessionId || ''
    messages.value = (history.messages || []).map((msg: ConversationMessage) => ({
      role: msg.role,
      content: msg.content
    }))
    persistChatState()
    if (messages.value.length > 0) {
      localStorage.removeItem(STORAGE_CLEARED_FLAG_KEY)
    }
  } catch {
    // ignore restore errors to keep chat usable
  }
}

function persistChatState() {
  try {
    if (sessionId.value) {
      localStorage.setItem(STORAGE_SESSION_KEY, sessionId.value)
    } else {
      localStorage.removeItem(STORAGE_SESSION_KEY)
    }

    if (messages.value.length > 0) {
      localStorage.setItem(STORAGE_MESSAGES_KEY, JSON.stringify(messages.value))
    } else {
      localStorage.removeItem(STORAGE_MESSAGES_KEY)
    }
  } catch {
    // ignore persistence errors
  }
}

async function sendMessage() {
  if (!inputText.value.trim() || loading.value) return

  localStorage.removeItem(STORAGE_CLEARED_FLAG_KEY)
  const userMessage = inputText.value.trim()
  messages.value.push({ role: 'user', content: userMessage })
  inputText.value = ''
  loading.value = true

  try {
    const response: NaturalLanguageResponse = await providerStore.naturalLanguageSwitch(
      userMessage,
      sessionId.value || undefined
    )

    sessionId.value = response.sessionId
    messages.value.push({ role: 'assistant', content: response.aiResponse })
    persistChatState()

    // If switch was triggered, refresh config
    if (response.switchTriggered && response.switchResult?.success) {
      await providerStore.fetchConfig()
    }
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      content: '抱歉，发生了错误。请稍后重试。'
    })
    persistChatState()
  } finally {
    loading.value = false
    await nextTick()
    scrollToBottom()
  }
}

async function sendQuickPrompt(prompt: string) {
  if (loading.value) return
  inputText.value = prompt
  await sendMessage()
}

function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

function clearChat() {
  messages.value = []
  sessionId.value = ''
  persistChatState()
  localStorage.setItem(STORAGE_CLEARED_FLAG_KEY, '1')
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h2 class="text-3xl font-bold tracking-tight text-gray-900">AI 助手</h2>
        <p class="text-gray-500 mt-2 font-light">用自然语言命令切换 AI 模型</p>
      </div>
      <button
        @click="clearChat"
        class="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded-xl transition-colors"
      >
        清空对话
      </button>
    </div>

    <div class="bg-white rounded-2xl border border-gray-100 shadow-sm flex flex-col" style="height: calc(100vh - 240px)">
      <!-- Messages -->
      <div
        ref="messagesContainer"
        class="flex-1 overflow-y-auto p-6 space-y-6"
      >
        <div v-if="messages.length === 0" class="text-center py-16 text-gray-500">
          <p class="text-xl mb-3">👋 你好！我是 PaiSwitch AI 助手</p>
          <p class="text-sm mb-6 text-gray-400">试试输入自然语言命令，比如 "切换到通义千问"</p>
          <div class="flex flex-wrap justify-center gap-3">
            <button
              v-for="prompt in quickPrompts"
              :key="prompt"
              type="button"
              @click="sendQuickPrompt(prompt)"
              :disabled="loading"
              class="px-4 py-2 text-sm rounded-full border border-gray-200 text-gray-700 bg-gray-50 hover:bg-gray-100 hover:border-gray-300 disabled:opacity-50 transition-all"
            >
              {{ prompt }}
            </button>
          </div>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="flex animate-in fade-in slide-in-from-bottom-2 duration-200"
          :class="msg.role === 'user' ? 'justify-end' : 'justify-start'"
        >
          <div
            class="max-w-[75%] px-5 py-3 rounded-2xl"
            :class="msg.role === 'user'
              ? 'bg-gray-900 text-white rounded-br-sm'
              : 'bg-gray-100 text-gray-900 rounded-bl-sm'"
          >
            <div class="whitespace-pre-wrap leading-relaxed">
              {{ msg.content }}
            </div>
          </div>
        </div>

        <div v-if="loading" class="flex justify-start animate-in fade-in">
          <div class="bg-gray-100 px-5 py-3 rounded-2xl rounded-bl-sm text-gray-500">
            <div class="flex items-center gap-2">
              <span class="animate-pulse">●</span>
              <span class="animate-pulse" style="animation-delay: 150ms">●</span>
              <span class="animate-pulse" style="animation-delay: 300ms">●</span>
              <span class="ml-1">思考中...</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="border-t border-gray-100 p-6">
        <form @submit.prevent="sendMessage" class="flex gap-3">
          <input
            v-model="inputText"
            type="text"
            class="flex-1 px-5 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-gray-900 focus:border-gray-900 transition-all outline-none"
            placeholder="输入消息，如：切换到 DeepSeek"
            :disabled="loading"
          />
          <button
            type="submit"
            :disabled="loading || !inputText.trim()"
            class="px-8 py-3 bg-gray-900 hover:bg-gray-800 text-white rounded-xl disabled:opacity-50 transition-all"
          >
            发送
          </button>
        </form>
      </div>
    </div>
  </div>
</template>
