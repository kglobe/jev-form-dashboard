<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api.js'

const props = defineProps({
  selectedId: { type: String, default: null },
  // 勾選想看的欄位（只屬於 selectedId 那個資料源）
  picked: { type: Array, default: () => [] },
})
const emit = defineEmits(['select', 'update:picked'])
// 哪個資料源的隱私設定是展開的；預設都收起來，避免把敏感勾選當成選欄位
const privacyOpen = ref(null)

const tab = ref('upload')
const list = ref([])
const error = ref('')
const busy = ref(false)

const file = ref(null)
const name = ref('')
const sheet = ref('')

const apiName = ref('')
const apiUrl = ref('')
const apiHeaders = ref('{}')

async function refresh() {
  list.value = await api.listDatasources()
}

onMounted(() => refresh().catch((e) => (error.value = e.message)))

async function run(action) {
  error.value = ''
  busy.value = true
  try {
    const ds = await action()
    await refresh()
    emit('select', ds.id)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

function upload() {
  // v-file-input 在不同版本可能回傳 File 或 File[]
  const f = Array.isArray(file.value) ? file.value[0] : file.value
  if (!f) {
    error.value = '請先選擇檔案'
    return
  }
  if (f.size > MAX_UPLOAD_BYTES) {
    error.value = '檔案太大，上限 50MB'
    return
  }
  const form = new FormData()
  form.append('file', f)
  form.append('name', name.value || f.name)
  if (sheet.value) form.append('sheet', sheet.value)
  return run(() => api.uploadDatasource(form))
}

function register() {
  let headers
  try {
    headers = JSON.parse(apiHeaders.value || '{}')
  } catch {
    error.value = 'headers 必須是 JSON 物件'
    return
  }
  if (typeof headers !== 'object' || headers === null || Array.isArray(headers)) {
    error.value = 'headers 必須是 JSON 物件'
    return
  }
  return run(() => api.registerApi({ name: apiName.value, url: apiUrl.value, headers }))
}

async function toggleSensitive(ds, field, on) {
  busy.value = true
  try {
    const fields = ds.schema.filter((f) => (f.name === field ? on : f.sensitive)).map((f) => f.name)
    await api.setSensitive(ds.id, fields)
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function togglePick(ds, field, on) {
  // 勾別的資料源的欄位：先切換過去，勾選從這一欄重新開始
  if (ds.id !== props.selectedId) {
    emit('select', ds.id)
    emit('update:picked', on ? [field] : [])
    return
  }
  emit('update:picked', on ? [...props.picked, field] : props.picked.filter((n) => n !== field))
}

async function saveLabel(ds, field, value) {
  const current = ds.schema.find((f) => f.name === field)
  if ((current.label ?? '') === value.trim()) return
  busy.value = true
  try {
    const labels = Object.fromEntries(ds.schema.filter((f) => f.label).map((f) => [f.name, f.label]))
    labels[field] = value
    await api.setLabels(ds.id, labels)
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="pa-4">
    <v-tabs v-model="tab" density="compact" class="mb-2">
      <v-tab value="upload">上傳檔案</v-tab>
      <v-tab value="api">外部 API</v-tab>
    </v-tabs>
    <v-window v-model="tab">
      <!-- spec 4.8：兩個分頁不是前後頁，切換時直接換內容，不左右滑 -->
      <v-window-item value="upload" :transition="false" :reverse-transition="false">
        <v-file-input v-model="file" label="CSV / Excel" accept=".csv,.xlsx,.xls" density="compact" />
        <v-text-field v-model="name" label="名稱（預設用檔名）" density="compact" />
        <v-text-field v-model="sheet" label="工作表（Excel 選填）" density="compact" />
        <v-btn block :loading="busy" @click="upload">上傳</v-btn>
      </v-window-item>
      <v-window-item value="api" :transition="false" :reverse-transition="false">
        <v-text-field v-model="apiName" label="名稱" density="compact" />
        <v-text-field v-model="apiUrl" label="網址" density="compact" />
        <v-textarea
          v-model="apiHeaders"
          label="headers（JSON，值只能寫 ${DS_環境變數名}）"
          hint='範例：{"Authorization":"${DS_ERP_AUTH}"}'
          persistent-hint
          rows="2"
          density="compact"
        />
        <v-btn block :loading="busy" @click="register">註冊</v-btn>
      </v-window-item>
    </v-window>

    <v-alert v-if="error" type="error" density="compact" class="mt-2">{{ error }}</v-alert>

    <v-list density="compact" class="mt-2">
      <v-list-subheader>資料源</v-list-subheader>
      <v-list-group v-for="ds in list" :key="ds.id" :value="ds.id">
        <template #activator="{ props: activator }">
          <v-list-item
            v-bind="activator"
            :title="ds.name"
            :subtitle="ds.kind === 'api' ? ds.apiUrl : `${ds.schema.length} 個欄位`"
            :active="ds.id === selectedId"
            @click="emit('select', ds.id)"
          />
        </template>
        <v-list-item density="compact">
          <p class="text-caption text-medium-emphasis">勾選想看的欄位，按「分析」時會優先用它們出圖，可以搭配下方「想看什麼」一起用；都不勾就由 JEV 自己挑。</p>
          <p class="text-caption text-medium-emphasis">顯示名稱會用在圖表標題、座標軸和明細表頭，空白就顯示原始欄位名稱。</p>
        </v-list-item>
        <v-list-item v-for="f in ds.schema" :key="f.name" density="compact">
          <v-checkbox
            :model-value="ds.id === selectedId && picked.includes(f.name)"
            :label="`${f.name}（${f.type}）`"
            density="compact"
            hide-details
            @update:model-value="(on) => togglePick(ds, f.name, on)"
          />
          <v-text-field
            :model-value="f.label ?? ''"
            :label="`${f.name} 顯示名稱`"
            :placeholder="f.name"
            density="compact"
            hide-details
            class="mb-2"
            :disabled="busy"
            @change="(e) => saveLabel(ds, f.name, e.target.value)"
          />
        </v-list-item>
        <v-list-item density="compact">
          <v-btn
            variant="text"
            size="small"
            :aria-expanded="privacyOpen === ds.id"
            @click="privacyOpen = privacyOpen === ds.id ? null : ds.id"
          >
            隱私設定（{{ ds.schema.filter((f) => f.sensitive).length }} 個敏感欄位）
          </v-btn>
        </v-list-item>
        <template v-if="privacyOpen === ds.id">
          <v-list-item density="compact">
            <p class="text-caption text-medium-emphasis">勾選為敏感的欄位：不會把樣本和相異值送給 JEV，用到它的卡片也不做異常檢查。</p>
          </v-list-item>
          <v-list-item v-for="f in ds.schema" :key="f.name" density="compact">
            <v-checkbox
              :model-value="f.sensitive"
              :label="`${f.name}（${f.type}）敏感`"
              density="compact"
              hide-details
              :disabled="busy"
              @update:model-value="(on) => toggleSensitive(ds, f.name, on)"
            />
          </v-list-item>
        </template>
      </v-list-group>
    </v-list>
  </div>
</template>
