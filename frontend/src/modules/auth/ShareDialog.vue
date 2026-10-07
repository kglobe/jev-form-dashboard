<script setup>
// 唯讀分享 spec 7.6、7.11：擁有者設定公開連結、分享給哪些帳號，複製分享連結
// 資料來源共享 spec 6.2：給 datasource 就是分享資料來源：開關換成「所有帳號都能用」，沒有連結那一段
import { computed, ref, watch } from 'vue'
import { api } from '../dashboard/api.js'

const props = defineProps({ modelValue: Boolean, dashboard: Object, datasource: Object })
const emit = defineEmits(['update:modelValue'])

const target = computed(() => props.datasource ?? props.dashboard)
const publicLink = ref(false)
const anonymous = ref(false) // 不用登入也能看，要先打開公開連結
const allUsers = ref(false)
const users = ref([])
const loaded = ref(false)
const input = ref('')
const inputField = ref(null)
const linkField = ref(null)
const busy = ref(false)
const error = ref('')
const copied = ref(false)
const copyFailed = ref(false)
let copiedTimer = null

const link = computed(() => `${location.origin}${location.pathname}#/d/${target.value.id}`)
const notice = computed(() => {
  if (props.datasource) return '對方可以拿它製圖，看得到明細資料和敏感欄位的值；看不到 API 網址和 header，也不能修改。'
  return anonymous.value
    ? '沒有帳號的人也看得到這份儀表板的所有資料，包括明細表和敏感欄位。'
    : '對方看到的資料跟你一樣，包括明細表和敏感欄位。'
})

// 關掉公開連結時，「不用登入也能看」一起關掉
watch(publicLink, (on) => {
  if (!on) anonymous.value = false
})

// 每次打開都重抓分享設定；App 一直掛著它，所以看 modelValue
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    publicLink.value = anonymous.value = allUsers.value = loaded.value = copied.value = copyFailed.value = false
    users.value = []
    input.value = error.value = ''
    try {
      if (props.datasource) {
        const share = await api.getDatasourceShare(props.datasource.id)
        allUsers.value = share.allUsers
        users.value = share.users
      } else {
        const share = await api.getShare(props.dashboard.id)
        publicLink.value = share.publicLink
        anonymous.value = share.anonymous
        users.value = share.users
      }
      // 還沒抓回來就按儲存，會用空的清單蓋掉原本的分享，所以抓回來之前不能儲存
      loaded.value = true
    } catch (e) {
      error.value = e.message
    }
  },
  { immediate: true },
)

// 跟後端一樣轉小寫、去空白（spec 2.1）；重複的不加；加完焦點回到輸入框
function add() {
  const u = input.value.trim().toLowerCase()
  if (u && !users.value.includes(u)) users.value.push(u)
  input.value = ''
  inputField.value.focus()
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.datasource) await api.setDatasourceShare(props.datasource.id, { allUsers: allUsers.value, users: users.value })
    else await api.setShare(props.dashboard.id, { publicLink: publicLink.value, anonymous: anonymous.value, users: users.value })
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

// 沒有 navigator.clipboard（http 開在區網上）或 writeText 失敗：選取連結，請對方自己按 Ctrl+C
async function copy() {
  try {
    await navigator.clipboard.writeText(link.value)
    copied.value = true
    copyFailed.value = false
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copied.value = false), 2000)
  } catch {
    linkField.value.focus()
    linkField.value.select()
    copyFailed.value = true
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="480" @update:model-value="(v) => emit('update:modelValue', v)">
    <v-card>
      <v-card-title class="pt-5 px-6">分享「{{ target.name }}」</v-card-title>
      <v-card-text class="px-6">
        <v-alert v-if="error" type="error" density="compact" class="mb-4">{{ error }}</v-alert>
        <template v-if="datasource">
          <div class="cc-label mb-1">所有帳號都能用</div>
          <v-switch v-model="allUsers" label="所有登入的人都能在資料頁看到它、拿來製圖" color="primary" hide-details class="mb-4" />
        </template>
        <template v-else>
          <div class="cc-label mb-1">公開連結</div>
          <v-switch v-model="publicLink" label="有帳號的人拿到連結就能看" color="primary" hide-details class="mb-4" />
          <div class="cc-label mb-1">不用登入也能看</div>
          <v-switch
            v-model="anonymous"
            label="任何拿到連結的人都能看，包括沒有帳號的人"
            color="primary"
            hide-details
            :disabled="!publicLink"
            class="mb-4"
          />
        </template>
        <div class="cc-label mb-1">分享給</div>
        <!-- 欄位和按鈕包在同一個表單裡，按 Enter 就是按「加入」 -->
        <v-form class="d-flex align-start ga-2" @submit.prevent="add">
          <v-text-field
            ref="inputField"
            v-model="input"
            placeholder="輸入對方的帳號"
            hint="對方的帳號在他帳號選單的第一行"
            persistent-hint
            autocomplete="off"
            autofocus
            density="compact"
          />
          <v-btn type="submit" variant="flat" class="mt-1">加入</v-btn>
        </v-form>
        <div class="d-flex flex-wrap ga-2 mt-3 mb-4">
          <v-chip v-for="u in users" :key="u" closable :close-label="`移除「${u}」`" @click:close="users = users.filter((x) => x !== u)">
            {{ u }}
          </v-chip>
          <p v-if="!users.length" class="text-caption text-medium-emphasis">還沒有分享給任何人</p>
        </div>
        <div v-if="!datasource" class="d-flex align-start ga-2">
          <v-text-field
            ref="linkField"
            :model-value="link"
            readonly
            aria-label="分享連結"
            density="compact"
            :hint="copyFailed ? '瀏覽器不讓網頁直接複製，請按 Ctrl+C' : undefined"
            persistent-hint
          />
          <v-btn variant="flat" class="mt-1" prepend-icon="mdi-link-variant" @click="copy">{{ copied ? '已複製' : '複製連結' }}</v-btn>
        </div>
        <p class="cc-desc mt-4">{{ notice }}</p>
      </v-card-text>
      <v-card-actions class="px-6 pb-5">
        <v-spacer />
        <v-btn variant="flat" @click="emit('update:modelValue', false)">取消</v-btn>
        <v-btn variant="flat" color="primary" :loading="busy" :disabled="busy || !loaded" @click="save">儲存</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
