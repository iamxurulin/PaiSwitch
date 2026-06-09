import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import DesktopStartupError from './DesktopStartupError.vue'
import './assets/main.css'
import { configureDesktopApiBaseURL } from './desktop'

async function bootstrap() {
  const desktopApi = await configureDesktopApiBaseURL()

  const app = desktopApi.ok
    ? createApp(App)
    : createApp(DesktopStartupError, { message: desktopApi.error })

  app.use(createPinia())
  if (desktopApi.ok) {
    app.use(router)
  }

  app.mount('#app')
}

bootstrap()
