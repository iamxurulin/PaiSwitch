import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import http from 'node:http'

const DEFAULT_PROXY_TARGET = 'http://localhost:8086'
const DISCOVERY_PORTS = [8086]
const REQUIRED_API_PATHS = ['/api/v1/auth/login', '/api/v1/providers', '/api/v1/config']

function hasPaiSwitchApis(body: string): boolean {
  try {
    const openApi = JSON.parse(body) as { paths?: Record<string, unknown> }
    const paths = openApi.paths ?? {}
    return REQUIRED_API_PATHS.every((path) => path in paths)
  } catch {
    return false
  }
}

async function isPaiSwitchBackend(port: number): Promise<boolean> {
  return new Promise((resolveCheck) => {
    const request = http.get(
      {
        host: 'localhost',
        port,
        path: '/api-docs',
        timeout: 800
      },
      (response) => {
        if (response.statusCode !== 200) {
          response.resume()
          resolveCheck(false)
          return
        }

        let body = ''
        response.setEncoding('utf8')
        response.on('data', (chunk) => {
          body += chunk
        })
        response.on('end', () => {
          resolveCheck(hasPaiSwitchApis(body))
        })
      }
    )

    request.on('timeout', () => {
      request.destroy()
      resolveCheck(false)
    })
    request.on('error', () => {
      resolveCheck(false)
    })
  })
}

async function resolveApiProxyTarget(explicitTarget?: string): Promise<string> {
  if (explicitTarget) {
    return explicitTarget
  }

  for (const port of DISCOVERY_PORTS) {
    if (await isPaiSwitchBackend(port)) {
      return `http://localhost:${port}`
    }
  }

  return DEFAULT_PROXY_TARGET
}

export default defineConfig(async ({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = await resolveApiProxyTarget(env.VITE_API_PROXY_TARGET)

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true
        }
      }
    }
  }
})
