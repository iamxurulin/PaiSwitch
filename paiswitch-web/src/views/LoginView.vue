<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const isLogin = ref(true)
const username = ref('')
const email = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleSubmit() {
  error.value = ''
  loading.value = true

  try {
    if (isLogin.value) {
      await authStore.login(username.value, password.value)
    } else {
      await authStore.register(username.value, email.value, password.value)
    }

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined
    const target = redirect && router.resolve(redirect).matched.length > 0 ? redirect : '/'

    router.push(target)
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message || '操作失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-900 via-gray-800 to-slate-900">
    <div class="bg-white rounded-3xl shadow-2xl p-10 w-full max-w-md animate-in fade-in zoom-in-95 duration-300">
      <div class="text-center mb-8">
        <h1 class="text-4xl font-bold tracking-tight text-gray-900">PaiSwitch</h1>
        <p class="text-gray-500 mt-2 font-light">AI 模型切换工具</p>
      </div>

      <form @submit.prevent="handleSubmit" class="space-y-5">
        <div v-if="error" class="bg-red-50 border border-red-100 text-red-600 px-5 py-3 rounded-xl text-sm">
          {{ error }}
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-2">用户名</label>
          <input
            v-model="username"
            type="text"
            required
            class="w-full px-5 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-gray-900 focus:border-gray-900 transition-all outline-none"
            placeholder="请输入用户名"
          />
        </div>

        <div v-if="!isLogin">
          <label class="block text-sm font-medium text-gray-700 mb-2">邮箱</label>
          <input
            v-model="email"
            type="email"
            required
            class="w-full px-5 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-gray-900 focus:border-gray-900 transition-all outline-none"
            placeholder="请输入邮箱"
          />
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-2">密码</label>
          <input
            v-model="password"
            type="password"
            required
            class="w-full px-5 py-3 border border-gray-200 rounded-xl focus:ring-2 focus:ring-gray-900 focus:border-gray-900 transition-all outline-none"
            placeholder="请输入密码"
          />
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full bg-gray-900 hover:bg-gray-800 text-white font-medium py-3 px-5 rounded-xl transition-all duration-200 hover:shadow-lg disabled:opacity-50"
        >
          {{ loading ? '处理中...' : (isLogin ? '登录' : '注册') }}
        </button>
      </form>

      <div class="mt-8 text-center">
        <button
          @click="isLogin = !isLogin"
          class="text-gray-600 hover:text-gray-800 text-sm"
        >
          {{ isLogin ? '没有账号？立即注册' : '已有账号？立即登录' }}
        </button>
      </div>
    </div>
  </div>
</template>
