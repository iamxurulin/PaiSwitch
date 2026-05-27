<script setup lang="ts">
import { computed } from 'vue'
import { useProviderStore } from '@/stores/provider'
import { useToastStore } from '@/stores/toast'
import type { ProviderInfo } from '@/types'

function getHostname(baseUrl?: string): string {
  if (!baseUrl) return ''
  try {
    const url = new URL(baseUrl)
    return url.hostname
  } catch {
    return ''
  }
}

const providerStore = useProviderStore()
const toastStore = useToastStore()

function isOfficialClaude(provider: ProviderInfo): boolean {
  return provider.code === 'claude' && providerStore.activeTool === 'CLAUDE_CODE'
}

function isOfficialOpenAi(provider: ProviderInfo): boolean {
  return provider.code === 'openai' && providerStore.activeTool === 'CODEX'
}

function isOfficialBuiltin(provider: ProviderInfo): boolean {
  return isOfficialClaude(provider) || isOfficialOpenAi(provider)
}

function providerSubtitle(provider?: ProviderInfo | null): string {
  if (!provider) return '-'
  return isOfficialBuiltin(provider) ? '官方登录' : provider.modelName
}

function isProviderSwitchable(provider: ProviderInfo): boolean {
  return !!provider.hasApiKey || isOfficialBuiltin(provider)
}

const stats = computed(() => {
  const total = providerStore.providers.length
  const withKey = providerStore.providers.filter(p => p.hasApiKey).length
  return { total, withKey }
})

const currentProvider = computed(() => providerStore.currentConfig?.currentProvider)

const sortedProviders = computed(() => {
  const providers = [...providerStore.providers]

  // Sort by priority:
  // 1. Current selected provider first
  // 2. Has API Key comes before no API Key
  // 3. If updatedAt exists, newer comes first
  providers.sort((a, b) => {
    // Current first
    const aIsCurrent = a.code === currentProvider.value?.code
    const bIsCurrent = b.code === currentProvider.value?.code
    if (aIsCurrent && !bIsCurrent) return -1
    if (!aIsCurrent && bIsCurrent) return 1

    // Has API key first
    if (a.hasApiKey && !b.hasApiKey) return -1
    if (!a.hasApiKey && b.hasApiKey) return 1

    if (isOfficialBuiltin(a) && !isOfficialBuiltin(b)) return -1
    if (!isOfficialBuiltin(a) && isOfficialBuiltin(b)) return 1

    // Default order is what's stored
    return 0
  })

  return providers
})

const quickSwitchProviders = computed(() => {
  return sortedProviders.value.filter(isProviderSwitchable)
})

async function handleQuickSwitch(providerCode: string) {
  const result = await providerStore.switchProvider(providerCode)
  if (result.success) {
    toastStore.success(`已切换到 ${result.currentProvider?.name}`)
  } else {
    toastStore.error(`切换失败: ${result.message}`)
  }
}
</script>

<template>
  <div>
    <div class="mb-8">
      <h2 class="text-3xl font-bold tracking-tight text-gray-900">仪表盘</h2>
      <p class="text-gray-500 mt-2 font-light">快速查看和切换 AI 模型</p>
    </div>

    <!-- Stats -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
      <div class="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm hover:shadow-lg transition-all duration-300">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-gray-500 text-sm font-light">当前模型</div>
            <div class="text-xl font-semibold text-gray-900 mt-2 truncate max-w-[180px]">
              {{ currentProvider?.name || '-' }}
            </div>
            <div v-if="currentProvider" class="text-xs text-gray-400 mt-1 font-mono truncate max-w-[180px]">
              {{ providerSubtitle(currentProvider) }}
            </div>
          </div>
          <div class="w-12 h-12 bg-gray-50 rounded-full flex items-center justify-center text-2xl">
            🤖
          </div>
        </div>
      </div>

      <div class="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm hover:shadow-lg transition-all duration-300">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-gray-500 text-sm font-light">可用模型</div>
            <div class="text-3xl font-bold tracking-tight text-gray-900 mt-2">{{ stats.total }}</div>
          </div>
          <div class="w-12 h-12 bg-blue-50 rounded-full flex items-center justify-center text-2xl">
            📊
          </div>
        </div>
      </div>

      <div class="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm hover:shadow-lg transition-all duration-300">
        <div class="flex items-center justify-between">
          <div>
            <div class="text-gray-500 text-sm font-light">已配置 API Key</div>
            <div class="text-3xl font-bold tracking-tight text-gray-900 mt-2">{{ stats.withKey }}</div>
          </div>
          <div class="w-12 h-12 bg-emerald-50 rounded-full flex items-center justify-center text-2xl">
            🔑
          </div>
        </div>
      </div>
    </div>

    <!-- Quick Switch -->
    <div class="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm mb-8">
      <h3 class="text-xl font-semibold text-gray-900 mb-4">快速切换</h3>
      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
        <button
          v-for="provider in quickSwitchProviders"
          :key="provider.code"
          @click="handleQuickSwitch(provider.code)"
          class="p-4 border border-gray-200 rounded-xl hover:border-gray-900 hover:bg-gray-50 transition-all duration-200 hover:shadow-md text-left cursor-pointer"
          :class="{ 'border-gray-900 bg-gray-50 shadow-md': currentProvider?.code === provider.code }"
        >
          <div class="font-medium text-gray-900 truncate">{{ provider.name }}</div>
          <div class="text-xs text-gray-500 mt-1 font-mono truncate">{{ providerSubtitle(provider) }}</div>
        </button>
      </div>
      <div v-if="quickSwitchProviders.length === 0" class="text-center py-10 text-gray-500">
        暂无可切换的模型，请先到模型管理添加 API Key 或使用官方登录
      </div>
    </div>

    <!-- Providers List -->
    <div class="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
      <div class="px-6 py-4 border-b border-gray-100">
        <h3 class="text-xl font-semibold text-gray-900">所有模型</h3>
      </div>
      <div class="divide-y divide-gray-100">
        <div
          v-for="provider in sortedProviders"
          :key="provider.id"
          class="px-6 py-4 flex items-center justify-between"
        >
          <div class="min-w-0">
            <div class="font-medium text-gray-900 text-lg truncate">{{ provider.name }}</div>
            <div class="flex flex-wrap items-center gap-4 mt-1">
              <span class="text-sm text-gray-500 font-mono truncate max-w-[200px]">{{ providerSubtitle(provider) }}</span>
              <span v-if="getHostname(provider.baseUrl)" class="text-xs text-gray-400">
                {{ getHostname(provider.baseUrl) }}
              </span>
            </div>
          </div>
          <div class="flex items-center gap-2 flex-shrink-0">
            <span
              v-if="provider.hasApiKey"
              class="px-3 py-1 bg-emerald-50 text-emerald-700 text-xs font-medium rounded-full"
            >
              已配置
            </span>
            <span
              v-else-if="isOfficialBuiltin(provider)"
              class="px-3 py-1 bg-blue-50 text-blue-700 text-xs font-medium rounded-full"
            >
              官方登录
            </span>
            <span
              v-else
              class="px-3 py-1 bg-gray-100 text-gray-500 text-xs font-medium rounded-full"
            >
              未配置
            </span>
            <span
              v-if="currentProvider?.code === provider.code"
              class="px-3 py-1 bg-gray-900 text-white text-xs font-medium rounded-full"
            >
              当前
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
