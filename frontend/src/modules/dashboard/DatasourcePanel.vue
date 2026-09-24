<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api.js'

defineProps({ selectedId: { type: String, default: null } })
const emit = defineEmits(['select'])

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
</script>

<template>
  <div class="pa-4">
    <v-tabs v-model="tab" density="compact" class="mb-2">
      <v-tab value="upload">上傳檔案</v-tab>
      <v-tab value="api">外部 API</v-tab>
    </v-tabs>
    <v-window v-model="tab">
      <v-window-item value="upload">
        <v-file-input v-model="file" label="CSV / Excel" accept=".csv,.xlsx,.xls" density="compact" />
        <v-text-field v-model="name" label="名稱（預設用檔名）" density="compact" />
        <v-text-field v-model="sheet" label="工作表（Excel 選填）" density="compact" />
        <v-btn block :loading="busy" @click="upload">上傳</v-btn>
      </v-window-item>
      <v-window-item value="api">
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
      </v-list-group>
    </v-list>
  </div>
</template>
