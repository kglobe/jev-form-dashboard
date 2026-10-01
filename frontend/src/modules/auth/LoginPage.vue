<script setup>
// 唯讀分享 spec 7.1：登入頁；開機的 me 失敗（不是 401）時，同一張卡片改成錯誤訊息加「重試」
import { onMounted, ref } from 'vue'
import { api } from '../dashboard/api.js'
import LoginForm from './LoginForm.vue'
import { takeLoginError } from './session.js'

defineProps({
  error: { type: String, default: null },
  isLight: Boolean,
})
const emit = defineEmits(['logged-in', 'retry', 'toggle-theme'])

const message = ref(null)
const oidc = ref(null)

onMounted(async () => {
  message.value = takeLoginError()
  // 讀不到登入選項就當作沒有單一登入，帳密照樣能登入
  oidc.value = (await api.authOptions().catch(() => ({ oidc: null }))).oidc
})
</script>

<template>
  <div class="login">
    <v-card class="login-card" max-width="400" width="100%">
      <v-btn
        class="login-theme"
        variant="text"
        size="small"
        :icon="isLight ? 'mdi-weather-night' : 'mdi-white-balance-sunny'"
        :aria-label="isLight ? '切換成深色主題' : '切換成淺色主題'"
        :title="isLight ? '切換成深色主題' : '切換成淺色主題'"
        @click="emit('toggle-theme')"
      />
      <v-card-text class="pa-8">
        <div class="login-brand">
          <div class="cc-logo" aria-hidden="true">
            <svg viewBox="0 0 16 16" width="26" height="26">
              <rect width="16" height="16" rx="4" fill="currentColor" />
              <path d="M4 12V8m4 4V4m4 8V7" stroke="#121315" stroke-width="2" stroke-linecap="round" />
            </svg>
            <span>JEV</span>
          </div>
          <h1 class="login-title">JEV 儀表板</h1>
        </div>
        <template v-if="error">
          <v-alert type="error" density="compact" class="mb-4">{{ error }}</v-alert>
          <v-btn color="primary" block @click="emit('retry')">重試</v-btn>
        </template>
        <template v-else>
          <v-alert v-if="message" type="error" density="compact" class="mb-4">{{ message }}</v-alert>
          <LoginForm :oidc="oidc" @submit="message = null" @done="emit('logged-in')" />
        </template>
      </v-card-text>
    </v-card>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 16px;
}
.login-card { position: relative; }
.login-theme {
  position: absolute;
  top: 8px;
  right: 8px;
}
.login-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.login-title { font-size: 20px; font-weight: 600; }
/* ponytail: 從 App.vue 照抄的標誌樣式，兩邊要一起改；只拿掉 margin-right，這裡是置中的直排，留著會偏左 */
.cc-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font: 400 15px/1 var(--tc-display);
  letter-spacing: 0.06em;
  color: var(--tc-text);
}
/* 標誌兩套主題都用亮橘，長條固定深色 */
.cc-logo svg { color: rgb(var(--tc-accent-rgb)); }
</style>
