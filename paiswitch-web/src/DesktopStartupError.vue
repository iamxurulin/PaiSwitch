<script setup lang="ts">
import { ref } from 'vue'
import {
  getDesktopDiagnostics,
  openDesktopLogFolder,
  quitDesktopApp,
  repairAndRestartBackend
} from './desktop'

const props = defineProps<{
  message?: string
}>()

const currentMessage = ref(props.message || '后端未运行')
const repairing = ref(false)
const copying = ref(false)
const copied = ref(false)
const openingLogs = ref(false)

async function repair() {
  repairing.value = true
  copied.value = false
  try {
    await repairAndRestartBackend()
    window.location.reload()
  } catch (error) {
    currentMessage.value = error instanceof Error ? error.message : String(error)
  } finally {
    repairing.value = false
  }
}

async function openLogs() {
  openingLogs.value = true
  try {
    await openDesktopLogFolder()
  } catch (error) {
    currentMessage.value = error instanceof Error ? error.message : String(error)
  } finally {
    openingLogs.value = false
  }
}

async function copyDiagnostics() {
  copying.value = true
  copied.value = false
  try {
    const diagnostics = await getDesktopDiagnostics()
    await navigator.clipboard.writeText(diagnostics)
    copied.value = true
  } catch (error) {
    currentMessage.value = error instanceof Error ? error.message : String(error)
  } finally {
    copying.value = false
  }
}

function quit() {
  void quitDesktopApp()
}
</script>

<template>
  <main class="min-h-screen bg-gray-50 flex items-center justify-center px-6 py-10">
    <section class="w-full max-w-2xl bg-white border border-red-100 rounded-2xl shadow-sm p-8">
      <p class="text-sm font-medium text-red-600 mb-3">后端启动失败</p>
      <h1 class="text-2xl font-semibold text-gray-900 mb-4">PaiSwitch 暂时不可用</h1>
      <p class="text-sm leading-6 text-gray-600 mb-5">
        可以先尝试自动修复。PaiSwitch 会清理残留的本地后端进程并重新启动服务，不需要安装 Java、数据库或手动执行命令。
      </p>

      <div class="flex flex-wrap gap-3 mb-5">
        <button
          class="px-4 py-2.5 rounded-lg bg-gray-900 text-white text-sm font-medium hover:bg-gray-800 disabled:opacity-50"
          :disabled="repairing"
          @click="repair"
        >
          {{ repairing ? '修复中...' : '一键修复并重启' }}
        </button>
        <button
          class="px-4 py-2.5 rounded-lg bg-gray-100 text-gray-700 text-sm font-medium hover:bg-gray-200 disabled:opacity-50"
          :disabled="openingLogs"
          @click="openLogs"
        >
          {{ openingLogs ? '打开中...' : '打开日志' }}
        </button>
        <button
          class="px-4 py-2.5 rounded-lg bg-gray-100 text-gray-700 text-sm font-medium hover:bg-gray-200 disabled:opacity-50"
          :disabled="copying"
          @click="copyDiagnostics"
        >
          {{ copied ? '已复制' : copying ? '复制中...' : '复制诊断信息' }}
        </button>
        <button
          class="px-4 py-2.5 rounded-lg text-gray-500 text-sm font-medium hover:bg-gray-100"
          @click="quit"
        >
          退出应用
        </button>
      </div>

      <pre class="text-xs leading-5 text-red-700 bg-red-50 border border-red-100 rounded-lg p-4 overflow-auto max-h-72">{{ currentMessage }}</pre>
    </section>
  </main>
</template>
