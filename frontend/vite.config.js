import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      // 唯讀分享 spec 2.3：單一登入的路徑不改 Host，後端算出來的 redirect URI 才是瀏覽器看到的網址
      '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
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
