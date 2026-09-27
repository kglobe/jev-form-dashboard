import { createApp } from 'vue'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
// App 要排在主題樣式前面：vite-plugin-vuetify 的 autoImport 讓 VBtn.css 這些元件樣式跟著 App 的 import 進來，
// App 排在後面的話，元件樣式會比覆寫檔晚載入，同權重的規則就被蓋回去
import App from './App.vue'
import './styles/command-center.css'

const vuetify = createVuetify({
  defaults: {
    global: { density: 'compact' },
    // 全域 compact 會把按鈕壓到 24px，比輸入框矮一截；按鈕回到 default 的 36px
    VBtn: { variant: 'flat', ripple: false, density: 'default' },
    VListItem: { ripple: false },
    VTab: { ripple: false },
    VChip: { ripple: false },
    VSwitch: { ripple: false, color: 'primary', inset: true },
    VTextField: { variant: 'outlined' },
    VTextarea: { variant: 'outlined' },
    VFileInput: { variant: 'outlined' },
    VSelect: { variant: 'outlined' },
    VAlert: { variant: 'tonal' },
  },
  // 深淺兩套，色值取自 command-center.css 的 :root 和 [data-theme="light"]；改那邊的色盤要回來同步。
  // 用內建的 light／dark 名稱，theme.toggle() 直接在兩套之間切換。
  // 一開始用哪套：index.html 的 inline script 已經照 localStorage 設好 <html data-theme>，這裡跟著它
  theme: {
    defaultTheme: document.documentElement.dataset.theme === 'light' ? 'light' : 'dark',
    themes: {
      dark: {
        dark: true,
        colors: {
          background: '#060a13',
          surface: '#0c1220',
          'surface-bright': '#111a2c',
          'surface-variant': '#1a2436',
          'on-surface-variant': '#e6edf7',
          primary: '#22d3ee',
          secondary: '#818cf8',
          error: '#fb7185',
          success: '#34d399',
          warning: '#fbbf24',
          info: '#60a5fa',
        },
        variables: { 'border-color': '#94a3b8', 'border-opacity': 0.14 },
      },
      light: {
        dark: false,
        colors: {
          background: '#f2f5fa',
          surface: '#ffffff',
          'surface-bright': '#f6f8fb',
          'surface-variant': '#e2e8f0',
          'on-surface-variant': '#0b1220',
          primary: '#0e7490',
          secondary: '#6366f1',
          error: '#be123c',
          success: '#047857',
          warning: '#b45309',
          info: '#1d4ed8',
        },
        variables: { 'border-color': '#334155', 'border-opacity': 0.14 },
      },
    },
  },
})

createApp(App).use(vuetify).mount('#app')
