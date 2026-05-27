<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useProviderStore } from '@/stores/provider'
import { onMounted } from 'vue'
import Toast from '@/components/Toast.vue'
import type { TargetTool } from '@/types'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const providerStore = useProviderStore()

const navItems = [
  { path: '/', name: 'Dashboard', label: '仪表盘', icon: '📊' },
  { path: '/providers', name: 'Providers', label: '模型管理', icon: '🔌' },
  { path: '/skills', name: 'Skills', label: 'Skills', icon: '🧠' },
  { path: '/ai-chat', name: 'AIChat', label: 'AI 助手', icon: '🤖' }
]

const toolTabs: { value: TargetTool; label: string }[] = [
  { value: 'CLAUDE_CODE', label: 'Claude Code' },
  { value: 'CODEX', label: 'Codex' }
]

const isActive = (path: string) => {
  if (path === '/') {
    return route.path === '/'
  }
  return route.path === path
}

onMounted(() => {
  providerStore.init()
})

function handleLogout() {
  authStore.logout()
  router.push('/login')
}

async function selectTool(tool: TargetTool) {
  await providerStore.setActiveTool(tool)
}
</script>

<template>
  <div class="min-h-screen flex bg-[#fafafa]">
    <!-- Toast Notifications -->
    <Toast />

    <!-- Sidebar -->
    <aside class="w-64 bg-gray-50 border-r border-gray-100">
      <div class="px-6 py-8">
        <h1 class="text-2xl font-bold tracking-tight text-gray-900">PaiSwitch</h1>
        <p class="text-xs text-gray-500 mt-1 font-light">API Key Manager</p>
      </div>

      <nav class="px-4 space-y-2">
        <a
          v-for="item in navItems"
          :key="item.path"
          @click="router.push(item.path)"
          class="flex items-center gap-3 px-4 py-3 rounded-xl transition-all duration-200 cursor-pointer"
          :class="[
            isActive(item.path)
              ? 'bg-white text-primary-600 font-medium shadow-sm'
              : 'text-gray-600 hover:bg-white hover:text-gray-900 hover:shadow-sm'
          ]"
        >
          <span class="text-lg">{{ item.icon }}</span>
          <span class="text-[15px]">{{ item.label }}</span>
        </a>
      </nav>
    </aside>

    <!-- Main Content -->
    <div class="flex-1 flex flex-col">
      <!-- Header -->
      <header class="h-20 bg-white border-b border-gray-100 flex items-center justify-between px-8">
        <div class="flex items-center gap-6">
          <!-- Tool Switcher -->
          <div class="inline-flex bg-gray-100 rounded-xl p-1">
            <button
              v-for="tab in toolTabs"
              :key="tab.value"
              @click="selectTool(tab.value)"
              class="px-4 py-1.5 text-sm font-medium rounded-lg transition-all duration-150"
              :class="providerStore.activeTool === tab.value
                ? 'bg-white text-gray-900 shadow-sm'
                : 'text-gray-500 hover:text-gray-700'"
            >
              {{ tab.label }}
            </button>
          </div>

          <div class="flex items-center gap-3">
            <span class="text-sm text-gray-500 font-light">当前模型:</span>
            <span
              v-if="providerStore.currentConfig?.currentProvider"
              class="px-4 py-1.5 bg-gray-100 rounded-full text-sm font-medium text-gray-900"
            >
              {{ providerStore.currentConfig.currentProvider.name }}
            </span>
            <span v-else class="px-4 py-1.5 bg-orange-50 text-orange-600 rounded-full text-sm font-medium">
              未配置
            </span>
          </div>
        </div>

        <div class="flex items-center gap-6">
          <span class="text-sm text-gray-600 font-medium">{{ authStore.user?.username }}</span>
          <button
            @click="handleLogout"
            class="px-4 py-2 text-sm text-gray-500 hover:text-gray-800 hover:bg-gray-100 rounded-lg transition-all duration-200"
          >
            退出
          </button>
        </div>
      </header>

      <!-- Page Content -->
      <main class="flex-1 px-8 py-8 overflow-auto">
        <router-view />
      </main>
    </div>
  </div>
</template>
