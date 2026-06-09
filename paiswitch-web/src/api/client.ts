import axios from 'axios'
import type { ApiResponse } from '@/types'
import { useAuthStore } from '@/stores/auth'

const apiBaseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

const api = axios.create({
  baseURL: apiBaseURL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

export function setApiBaseURL(baseURL: string) {
  api.defaults.baseURL = baseURL
}

api.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore()
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if ([401, 403].includes(error.response?.status)) {
      const authStore = useAuthStore()
      if (authStore.token) {
        authStore.logout()
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

export default api

export async function apiGet<T>(url: string): Promise<T> {
  const response = await api.get<ApiResponse<T>>(url)
  return response.data.data
}

export async function apiPost<T>(url: string, data?: unknown): Promise<T> {
  const response = await api.post<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function apiPut<T>(url: string, data?: unknown): Promise<T> {
  const response = await api.put<ApiResponse<T>>(url, data)
  return response.data.data
}

export async function apiDelete<T>(url: string): Promise<T> {
  const response = await api.delete<ApiResponse<T>>(url)
  return response.data.data
}
