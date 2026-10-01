import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

// 測試劇本（docs/testing/e2e-playbook.md）：E2E 的後端開在別的 port，用 API_TARGET 指過去；空字串當作沒設
const target = process.env.API_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  server: {
    proxy: {
      '/api': target,
      // 唯讀分享 spec 2.3：單一登入的路徑不改 Host，後端算出來的 redirect URI 才是瀏覽器看到的網址
      '/oauth2': { target, changeOrigin: false },
      '/login/oauth2': { target, changeOrigin: false },
    },
  },
  test: {
    environment: 'node',
    server: {
      deps: {
        inline: ['vuetify'],
      },
    },
  },
})
