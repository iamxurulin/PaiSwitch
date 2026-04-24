<script setup lang="ts">
import { useProviderStore } from '@/stores/provider'
import { useToastStore } from '@/stores/toast'
import { computed } from 'vue'
import type { ProviderInfo } from '@/types'

const providerStore = useProviderStore()
const toastStore = useToastStore()

function providerSubtitle(provider: ProviderInfo): string {
  return provider.code === 'claude' ? '官方登录' : provider.modelName
}

function isProviderSwitchable(provider: ProviderInfo): boolean {
  return !!provider.hasApiKey || provider.code === 'claude'
}

const switchableProviders = computed(() => providerStore.providers.filter(isProviderSwitchable))

async function handleSwitch(providerCode: string) {
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
    <div class="mb-10">
      <h2 class="text-3xl font-bold tracking-tight text-gray-900">配置管理</h2>
      <p class="text-gray-500 mt-2 font-light">查看当前配置和切换模型</p>
    </div>

    <!-- Current Config -->
    <div class="bg-white rounded-2xl p-8 border border-gray-100 shadow-sm mb-6">
      <h3 class="text-xl font-semibold text-gray-900 mb-6">当前配置</h3>

      <div v-if="providerStore.currentConfig" class="space-y-4">
        <div class="flex justify-between items-center py-3 border-b border-gray-100">
          <span class="text-gray-500">当前模型</span>
          <span class="font-medium text-gray-900">
            {{ providerStore.currentConfig.currentProvider.name }}
          </span>
        </div>

        <div class="flex justify-between items-center py-3 border-b border-gray-100">
          <span class="text-gray-500">模型代码</span>
          <span class="font-mono text-gray-900 text-sm bg-gray-50 px-2 py-1 rounded-md">
            {{ providerStore.currentConfig.currentProvider.code }}
          </span>
        </div>

        <div class="flex justify-between items-center py-3 border-b border-gray-100">
          <span class="text-gray-500">API 超时</span>
          <span class="text-gray-900 font-mono text-sm bg-gray-50 px-2 py-1 rounded-md">
            {{ providerStore.currentConfig.apiTimeout }}ms
          </span>
        </div>
      </div>

      <div v-else class="text-center py-8 text-gray-500">
        暂无配置，请选择一个模型
      </div>
    </div>

    <!-- Switch Model -->
    <div class="bg-white rounded-2xl p-8 border border-gray-100 shadow-sm">
      <h3 class="text-xl font-semibold text-gray-900 mb-6">切换模型</h3>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <button
          v-for="provider in switchableProviders"
          :key="provider.code"
          @click="handleSwitch(provider.code)"
          class="p-5 border border-gray-200 rounded-xl text-left hover:border-gray-900 hover:bg-gray-50 transition-all duration-200 hover:shadow-md"
          :class="{
            'border-gray-900 bg-gray-50 shadow-md': providerStore.currentConfig?.currentProvider.code === provider.code
          }"
        >
          <div class="font-medium text-gray-900 text-lg">{{ provider.name }}</div>
          <div class="text-sm text-gray-500 mt-1 font-mono">{{ providerSubtitle(provider) }}</div>
        </button>
      </div>

      <div v-if="switchableProviders.length === 0" class="text-center py-12 text-gray-500">
        暂无可切换的模型，请先在「模型管理」中配置 API Key 或使用 Claude 官方登录
      </div>
    </div>
  </div>
</template>
