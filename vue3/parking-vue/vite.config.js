import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
const appBase = process.env.VITE_APP_BASE || '/'
export default defineConfig({
  base: appBase,
  plugins: [
    vue(),
    !process.env.VITEST &&
      Components({ resolvers: [ElementPlusResolver({ importStyle: 'sass' })], dts: false }),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      [`${appBase}api`]: {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.slice(`${appBase}api`.length) || '/',
      },
    },
  },
  test: { environment: 'jsdom', include: ['tests/**/*.spec.js'] },
})
