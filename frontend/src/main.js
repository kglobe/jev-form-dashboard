import { createApp } from 'vue'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
// App 要排在兩份 Tactile 樣式前面：vite-plugin-vuetify 的 autoImport 讓 VBtn.css 這些元件樣式跟著 App 的 import 進來，
// App 排在後面的話，元件樣式會比覆寫檔晚載入，同權重的規則就被蓋回去
import App from './App.vue'
import './styles/tactile-clean.css'
import './styles/vuetify-overrides.css'

const vuetify = createVuetify({
  defaults: {
    global: { density: 'compact' },
    // 全域 compact 會把按鈕壓到 24px，比 43px 的輸入框矮一截；按鈕回到 default 的 36px，對上 --tc-h-md
    VBtn: { variant: 'flat', ripple: false, density: 'default' },
    VListItem: { ripple: false },
    VTab: { ripple: false },
    VCheckbox: { ripple: false },
    VTextField: { variant: 'outlined' },
    VTextarea: { variant: 'outlined' },
    VFileInput: { variant: 'outlined' },
  },
  // spec 3.1：只有淺色一套，色值取自 tactile-clean.css 的 :root；改那邊的色盤要回來同步
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          background: '#f1f5f9',
          surface: '#ffffff',
          primary: '#005caf',
          error: '#cb1b45',
          success: '#166534',
          warning: '#92400e',
          info: '#004888',
        },
      },
    },
  },
})

createApp(App).use(vuetify).mount('#app')
