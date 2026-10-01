<script setup>
// 唯讀分享 spec 7.3：帳號鈕的選單；按鈕外觀由 App 的 activator slot 決定，導覽列的樣式才套得到
import { ref } from 'vue'
import { api } from '../dashboard/api.js'
import AccountsDialog from './AccountsDialog.vue'
import PasswordDialog from './PasswordDialog.vue'
import { resetAndReload } from './session.js'

defineProps({ me: { type: Object, required: true } })

const changing = ref(false)
const managing = ref(false)

// 登出失敗（例如連不上）也一樣清掉畫面重新載入，不留前一個人的畫面
function logout() {
  api.logout().catch(() => {}).finally(resetAndReload)
}
</script>

<template>
  <v-menu location="end">
    <template #activator="{ props }"><slot name="activator" :props="props" /></template>
    <v-list density="compact">
      <v-list-item :title="me.admin ? `${me.username} · 管理員` : me.username" disabled />
      <v-list-item v-if="me.password" title="改密碼" @click="changing = true" />
      <v-list-item v-if="me.admin" title="帳號管理" @click="managing = true" />
      <v-list-item title="登出" @click="logout" />
    </v-list>
  </v-menu>
  <PasswordDialog v-model="changing" />
  <AccountsDialog v-model="managing" :me="me" />
</template>
