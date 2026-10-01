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
          <div class="cc-logo" aria-hidden="true">JEV</div>
          <h1 class="login-title">JEV 儀表板</h1>
        </div>
        <template v-if="error">
          <v-alert type="error" density="compact" class="mb-4">{{ error }}</v-alert>
          <v-btn color="primary" block @click="emit('retry')">重試</v-btn>
        </template>
        <template v-else>
          <v-alert v-if="message" type="error" density="compact" class="mb-4">{{ message }}</v-alert>
          <LoginForm :oidc="oidc" @done="emit('logged-in')" />
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
/* ponytail: 從 App.vue 複製的標誌樣式，M1 合併 feat/industrial-theme 之後換成新的標誌 */
.cc-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font: 700 12px/1 var(--tc-mono);
  letter-spacing: 0.04em;
  color: #031018; /* 標誌兩套主題都用亮色漸層，字固定深色 */
  background: linear-gradient(135deg, #5ee6f7, #22d3ee 40%, #818cf8);
  box-shadow: 0 0 28px -6px rgba(var(--tc-accent-rgb), 0.8);
}
</style>
