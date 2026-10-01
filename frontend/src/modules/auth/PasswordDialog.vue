<script setup>
// 唯讀分享 spec 7.4、7.11：自己改密碼；成功後所有工作階段都失效，只能重新登入
import { ref, watch } from 'vue'
import { api } from '../dashboard/api.js'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])

const current = ref('')
const password = ref('')
const again = ref('')
const busy = ref(false)
const error = ref('')
const done = ref(false)

// 每次打開都是空的，上次取消時打的密碼不留著
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    current.value = password.value = again.value = error.value = ''
  },
)

async function submit() {
  if (busy.value) return
  if (password.value !== again.value) {
    error.value = '兩次輸入的新密碼不一樣'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await api.changePassword(current.value, password.value)
    done.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function reload() {
  location.reload()
}
</script>

<template>
  <v-dialog :model-value="modelValue" :persistent="done" max-width="420" @update:model-value="(v) => emit('update:modelValue', v)">
    <v-card>
      <v-card-title class="pt-5 px-6">改密碼</v-card-title>
      <template v-if="done">
        <v-card-text class="px-6">密碼已更新，請用新密碼重新登入</v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <v-btn variant="flat" color="primary" @click="reload">重新登入</v-btn>
        </v-card-actions>
      </template>
      <!-- 欄位和按鈕包在同一個表單裡，在最後一個欄位按 Enter 就是按「更新密碼」 -->
      <v-form v-else @submit.prevent="submit">
        <v-card-text class="px-6">
          <v-alert v-if="error" type="error" density="compact" class="mb-4">{{ error }}</v-alert>
          <v-text-field v-model="current" label="目前的密碼" type="password" autocomplete="current-password" autofocus />
          <v-text-field v-model="password" label="新密碼" type="password" autocomplete="new-password" hint="至少 8 個字元" />
          <v-text-field v-model="again" label="再輸入一次新密碼" type="password" autocomplete="new-password" />
        </v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <v-btn variant="flat" @click="emit('update:modelValue', false)">取消</v-btn>
          <v-btn type="submit" variant="flat" color="primary" :loading="busy" :disabled="busy">更新密碼</v-btn>
        </v-card-actions>
      </v-form>
    </v-card>
  </v-dialog>
</template>
