import { setApiBaseURL } from '@/api/client'

type TauriWindow = Window & {
  __TAURI_INTERNALS__?: unknown
}

export interface DesktopApiConfigResult {
  ok: boolean
  error?: string
}

function isTauriRuntime(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in (window as TauriWindow)
}

export async function configureDesktopApiBaseURL(): Promise<DesktopApiConfigResult> {
  if (!isTauriRuntime() || import.meta.env.VITE_API_BASE_URL) {
    return { ok: true }
  }

  try {
    const { invoke } = await import('@tauri-apps/api/core')
    const baseURL = await invoke<string>('backend_api_base_url')
    setApiBaseURL(baseURL)
    return { ok: true }
  } catch (error) {
    return {
      ok: false,
      error: error instanceof Error ? error.message : String(error)
    }
  }
}

async function invokeDesktop<T>(command: string): Promise<T> {
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke<T>(command)
}

export async function repairAndRestartBackend(): Promise<string> {
  const baseURL = await invokeDesktop<string>('repair_and_restart_backend')
  setApiBaseURL(baseURL)
  return baseURL
}

export async function openDesktopLogFolder(): Promise<void> {
  await invokeDesktop<void>('open_log_folder')
}

export async function getDesktopDiagnostics(): Promise<string> {
  return invokeDesktop<string>('copy_diagnostics')
}

export async function quitDesktopApp(): Promise<void> {
  await invokeDesktop<void>('quit_app')
}
