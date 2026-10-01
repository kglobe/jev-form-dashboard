<script setup>
// 唯讀分享 spec 7.5、7.11：管理員的帳號管理；自己那一列不能停用、取消管理員、重設密碼（spec 2.1）
import { ref, watch } from 'vue'
import { api } from '../dashboard/api.js'

const props = defineProps({ modelValue: Boolean, me: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue'])

const accounts = ref([])
const msg = ref(null) // { type: 'error' | 'success', text }
const busy = ref('') // 送出中的動作，同時只送一個
const username = ref('')
const password = ref('')
const admin = ref(false)
const showNew = ref(false)
const resetting = ref(null) // 展開重設密碼的帳號名稱
const resetPassword = ref('')
const showReset = ref(false)

// 每次打開都重抓清單、清空表單；在 UserMenu 裡一直掛著，所以看 modelValue，不看 mounted
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    username.value = password.value = ''
    admin.value = showNew.value = false
    msg.value = resetting.value = null
    load()
  },
  { immediate: true },
)

async function load() {
  try {
    accounts.value = await api.listAccounts()
  } catch (e) {
    msg.value = { type: 'error', text: e.message }
  }
}

async function run(key, fn) {
  if (busy.value) return
  busy.value = key
  msg.value = null
  try {
    await fn()
  } catch (e) {
    msg.value = { type: 'error', text: e.message }
  } finally {
    busy.value = ''
  }
}

const add = () =>
  run('add', async () => {
    await api.createAccount({ username: username.value, password: password.value, admin: admin.value })
    username.value = password.value = ''
    admin.value = false
    await load()
  })

function openReset(a) {
  resetting.value = a.username
  resetPassword.value = ''
  showReset.value = false
}

const saveReset = (a) =>
  run('reset', async () => {
    await api.setAccountPassword(a.username, resetPassword.value)
    msg.value = { type: 'success', text: `${a.password ? '已重設' : '已設定'}「${a.username}」的密碼` }
    a.password = true // PUT 回 204；設了密碼之後兩種方式都能登入（spec 4.2）
    resetting.value = null
  })

// PATCH 只送要改的那個欄位，回來的帳號換掉那一列
const toggle = (a, key) =>
  run(`${a.username}:${key}`, async () => {
    const updated = await api.updateAccount(a.username, { [key]: !a[key] })
    accounts.value = accounts.value.map((x) => (x.username === a.username ? updated : x))
  })

const loginMethod = (a) => (a.password ? (a.sso ? '兩者' : '密碼') : '單一登入')
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="720" @update:model-value="(v) => emit('update:modelValue', v)">
    <v-card>
      <v-card-title class="pt-5 px-6">帳號管理</v-card-title>
      <v-card-text class="px-6">
        <v-alert v-if="msg" :type="msg.type" density="compact" class="mb-4">{{ msg.text }}</v-alert>
        <!-- 欄位和按鈕包在同一個表單裡，按 Enter 就是按「新增帳號」 -->
        <v-form class="d-flex align-start ga-2" @submit.prevent="add">
          <v-text-field v-model="username" label="帳號" autocomplete="off" autofocus density="compact" />
          <v-text-field
            v-model="password"
            label="初始密碼"
            :type="showNew ? 'text' : 'password'"
            autocomplete="new-password"
            density="compact"
          >
            <template #append-inner>
              <v-btn
                :icon="showNew ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                :aria-label="showNew ? '隱藏密碼' : '顯示密碼'"
                variant="text"
                size="small"
                density="compact"
                @click="showNew = !showNew"
              />
            </template>
          </v-text-field>
          <v-checkbox v-model="admin" label="管理員" density="compact" hide-details class="flex-grow-0" />
          <v-btn type="submit" variant="flat" color="primary" class="mt-1" :loading="busy === 'add'" :disabled="busy === 'add'">
            新增帳號
          </v-btn>
        </v-form>
        <v-table density="compact">
          <thead>
            <tr>
              <th>帳號</th>
              <th>身分</th>
              <th>登入方式</th>
              <th>狀態</th>
              <th>動作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="a in accounts" :key="a.username">
              <tr>
                <td>{{ a.username }}{{ a.username === me.username ? '（你）' : '' }}</td>
                <td>{{ a.admin ? '管理員' : '一般' }}</td>
                <td>{{ loginMethod(a) }}</td>
                <td>{{ a.enabled ? '啟用' : '停用' }}</td>
                <td class="text-no-wrap">
                  <template v-if="a.username !== me.username">
                    <v-btn variant="text" size="small" @click="openReset(a)">{{ a.password ? '重設密碼' : '設定密碼' }}</v-btn>
                    <v-btn
                      variant="text"
                      size="small"
                      :loading="busy === `${a.username}:enabled`"
                      :disabled="busy === `${a.username}:enabled`"
                      @click="toggle(a, 'enabled')"
                    >
                      {{ a.enabled ? '停用' : '啟用' }}
                    </v-btn>
                    <v-btn
                      variant="text"
                      size="small"
                      :loading="busy === `${a.username}:admin`"
                      :disabled="busy === `${a.username}:admin`"
                      @click="toggle(a, 'admin')"
                    >
                      {{ a.admin ? '取消管理員' : '設成管理員' }}
                    </v-btn>
                  </template>
                </td>
              </tr>
              <tr v-if="resetting === a.username">
                <td colspan="5">
                  <!-- 按 Enter 等於「儲存」 -->
                  <v-form class="d-flex align-start ga-2 pt-2" @submit.prevent="saveReset(a)">
                    <v-text-field
                      v-model="resetPassword"
                      label="新密碼"
                      :type="showReset ? 'text' : 'password'"
                      autocomplete="new-password"
                      autofocus
                      density="compact"
                    >
                      <template #append-inner>
                        <v-btn
                          :icon="showReset ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                          :aria-label="showReset ? '隱藏密碼' : '顯示密碼'"
                          variant="text"
                          size="small"
                          density="compact"
                          @click="showReset = !showReset"
                        />
                      </template>
                    </v-text-field>
                    <v-btn type="submit" variant="flat" color="primary" class="mt-1" :loading="busy === 'reset'" :disabled="busy === 'reset'">
                      儲存
                    </v-btn>
                    <v-btn variant="flat" class="mt-1" @click="resetting = null">取消</v-btn>
                  </v-form>
                </td>
              </tr>
            </template>
          </tbody>
        </v-table>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
