import './assets/main.scss'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createPersistedState } from 'pinia-persistedstate-plugin'
import router from '@/router'
import App from './App.vue'
const app = createApp(App)
const pinia = createPinia()
pinia.use(createPersistedState())
app.use(pinia)
app.use(router)
app.mount('#app')
