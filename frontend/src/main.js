import { createApp } from 'vue'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
// 寬體科技字只用在英數字標籤，只載拉丁子集
import '@fontsource/michroma/latin-400.css'
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
  // 深淺兩套，色值取自 command-center.css 的 :root（淺色）和 [data-theme="dark"]；改那邊的色盤要回來同步。
  // 用內建的 light／dark 名稱，theme.toggle() 直接在兩套之間切換。
  // 一開始用哪套：index.html 的 inline script 已經照 localStorage 設好 <html data-theme>，這裡跟著它；沒設過就是淺色
  theme: {
    defaultTheme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
    themes: {
      dark: {
        dark: true,
        colors: {
          background: '#0e0f11',
          surface: '#17181b',
          'surface-bright': '#1f2024',
          'surface-variant': '#2a2b2f',
          'on-surface-variant': '#ededee',
          primary: '#ff7a3d',
          secondary: '#b8bac0',
          error: '#fb7185',
          success: '#34d399',
          warning: '#facc15',
          info: '#60a5fa',
        },
        variables: { 'border-color': '#c8cacf', 'border-opacity': 0.14 },
      },
      light: {
        dark: false,
        colors: {
          background: '#e3e4e7',
          surface: '#ffffff',
          'surface-bright': '#f5f5f6',
          'surface-variant': '#dcdde0',
          'on-surface-variant': '#121315',
          primary: '#b33f0a',
          secondary: '#3b3d42',
          error: '#be123c',
          success: '#036a4b',
          warning: '#8a5700',
          info: '#1d4ed8',
        },
        variables: { 'border-color': '#17181b', 'border-opacity': 0.12 },
      },
    },
  },
})

createApp(App).use(vuetify).mount('#app')
