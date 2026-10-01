<script setup>
// 唯讀分享 spec 7.1、7.11：帳密表單，登入頁和重新登入對話框共用
import { ref } from 'vue'
import { api } from '../dashboard/api.js'
import { oidcButtonText, startOidc } from './session.js'

const props = defineProps({
  username: { type: String, default: '' },
  oidc: { type: Object, default: null },
})
const emit = defineEmits(['done'])

const name = ref(props.username)
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api.login(name.value, password.value)
    emit('done')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <v-form @submit.prevent="submit">
      <v-alert v-if="error" type="error" density="compact" class="mb-4">{{ error }}</v-alert>
      <!-- spec 7.11：有預填帳號時焦點放在密碼欄 -->
      <v-text-field v-model="name" label="帳號" autocomplete="username" :autofocus="!username" />
      <v-text-field
        v-model="password"
        label="密碼"
        type="password"
        autocomplete="current-password"
        :autofocus="!!username"
      />
      <v-btn type="submit" color="primary" block :loading="busy" :disabled="busy">登入</v-btn>
    </v-form>
    <template v-if="oidc">
      <v-divider class="my-4" />
      <v-btn variant="tonal" prepend-icon="mdi-login" block @click="startOidc">{{ oidcButtonText(oidc.label) }}</v-btn>
    </template>
  </div>
</template>
