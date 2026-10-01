<script setup>
// 唯讀分享 spec 7.2：登入逾時的對話框，點外面也關不掉；畫面上的元件都留著
import { ref, watch } from 'vue'
import { api } from '../dashboard/api.js'
import LoginForm from './LoginForm.vue'
import { oidcButtonText, resetAndReload, startOidc } from './session.js'

const props = defineProps({
  modelValue: Boolean,
  me: { type: Object, required: true },
})
const emit = defineEmits(['done'])

const oidc = ref(null)
const error = ref('')
const checking = ref(false)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    error.value = ''
    oidc.value = (await api.authOptions().catch(() => ({ oidc: null }))).oidc
  },
  { immediate: true },
)

// spec 7.2：比的是 me 回來的帳號，不是輸入框的字；換了人就整頁重新載入，不留前一個人的畫面。
// 同一個人就把新的 me 交給 App（管理員、密碼可能變了）
async function onLoggedIn() {
  checking.value = true
  try {
    const now = await api.me()
    if (now.username === props.me.username) emit('done', now)
    else resetAndReload()
  } catch (e) {
    error.value = e.message
  } finally {
    checking.value = false
  }
}

function reload() {
  location.reload()
}
</script>

<template>
  <v-dialog :model-value="modelValue" persistent max-width="420">
    <v-card>
      <v-card-title class="pt-5 px-6">請重新登入</v-card-title>
      <v-card-text class="px-6">
        <v-alert v-if="error" type="error" density="compact" class="mb-4">{{ error }}</v-alert>
        <template v-if="me.password">
          <p class="mb-4">登入已經逾時，請重新登入。畫面上的東西會留著。</p>
          <LoginForm :username="me.username" :oidc="oidc" :pending="checking" @submit="error = ''" @done="onLoggedIn" />
        </template>
        <template v-else>
          <p class="mb-4">你用單一登入，請按下面的按鈕重新登入。「製圖」頁還沒存的預覽會不見。</p>
          <v-btn v-if="oidc" variant="tonal" prepend-icon="mdi-login" block @click="startOidc">{{ oidcButtonText(oidc.label) }}</v-btn>
        </template>
      </v-card-text>
      <v-card-actions class="px-6 pb-5">
        <!-- spec 7.2：停用的帳號登不回來，至少還能重新整理 -->
        <v-btn variant="text" @click="reload">重新整理</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
