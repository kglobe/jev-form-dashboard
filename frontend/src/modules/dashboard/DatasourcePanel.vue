<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from './api.js'
import { FIELD_TYPES } from './fields.js'
import MergeForm from './MergeForm.vue'
import './motion.css'

const props = defineProps({
  selectedId: { type: String, default: null },
})
// build：按「用這個來源製圖」，交給 App 切到製圖頁
const emit = defineEmits(['select', 'build'])

const list = ref([])
const error = ref('')
// 顯示名稱、敏感設定送出中：整張表一起停用，連續兩次勾選才不會各自拿舊的清單算聯集
const busy = ref(false)
// 儲存失敗時 model 沒變，Vue 不會把原生 input 改回去（開關停在錯的位置、輸入框留著沒存的字）；換 key 重畫欄位表
const rowsKey = ref(0)
const filter = ref('')
const KINDS = {
  upload: { icon: 'mdi-file-table-outline', text: '上傳檔案' },
  api: { icon: 'mdi-api', text: '外部 API' },
  merged: { icon: 'mdi-set-merge', text: '合併' },
}

const filtered = computed(() => {
  const q = filter.value?.trim().toLowerCase()
  return q ? list.value.filter((ds) => ds.name.toLowerCase().includes(q)) : list.value
})
const selected = computed(() => list.value.find((ds) => ds.id === props.selectedId) ?? null)
const sensitiveCount = computed(() => selected.value?.schema.filter((f) => f.sensitive).length ?? 0)

async function refresh() {
  list.value = await api.listDatasources()
  // 還沒選來源就先選第一個，右邊的欄位表不會空著
  if (!selected.value && list.value.length) emit('select', list.value[0].id)
}

onMounted(() => refresh().catch((e) => (error.value = e.message)))

// ---------- 新增來源（對話框） ----------
const addOpen = ref(false)
const addTab = ref('upload')
const addError = ref('')
const adding = ref(false)

const file = ref(null)
const name = ref('')
const sheet = ref('')

const apiName = ref('')
const apiUrl = ref('')
const apiHeaders = ref('{}')
// 建立成功後換 key，下次打開是乾淨的表單
const mergeKey = ref(0)

function openAdd() {
  addError.value = ''
  addOpen.value = true
}

async function run(action) {
  addError.value = ''
  adding.value = true
  try {
    const ds = await action()
    await refresh()
    emit('select', ds.id)
    addOpen.value = false
    file.value = null
    name.value = sheet.value = apiName.value = apiUrl.value = ''
    apiHeaders.value = '{}'
  } catch (e) {
    addError.value = e.message
  } finally {
    adding.value = false
  }
}

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

function upload() {
  // v-file-input 在不同版本可能回傳 File 或 File[]
  const f = Array.isArray(file.value) ? file.value[0] : file.value
  if (!f) {
    addError.value = '請先選擇檔案'
    return
  }
  if (f.size > MAX_UPLOAD_BYTES) {
    addError.value = '檔案太大，上限 50MB'
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
    addError.value = 'headers 必須是 JSON 物件'
    return
  }
  if (typeof headers !== 'object' || headers === null || Array.isArray(headers)) {
    addError.value = 'headers 必須是 JSON 物件'
    return
  }
  return run(() => api.registerApi({ name: apiName.value, url: apiUrl.value, headers }))
}

async function onMerged(ds) {
  try {
    await refresh()
    emit('select', ds.id)
    addOpen.value = false
    mergeKey.value++
  } catch (e) {
    addError.value = e.message
  }
}

// spec 8.2：合併來源的說明列；輸入來源查不到就寫「（已不存在）」
function mergeSummary(ds) {
  const nameOf = (id) => list.value.find((d) => d.id === id)?.name ?? '（已不存在）'
  const m = ds.merge
  return m.op === 'union'
    ? `${m.compare ? '對照' : '疊加'}：${m.inputs.map(nameOf).join('、')}`
    : `查表：${nameOf(m.main)} ← ${nameOf(m.lookup)}（${m.keys.map((k) => `${k.main} = ${k.lookup}`).join('、')}）`
}

// ---------- 欄位設定 ----------
async function toggleSensitive(ds, field, on) {
  busy.value = true
  try {
    const fields = ds.schema.filter((f) => (f.name === field ? on : f.sensitive)).map((f) => f.name)
    await api.setSensitive(ds.id, fields)
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
    rowsKey.value++
  } finally {
    busy.value = false
  }
}

async function saveLabel(ds, field, value) {
  const current = ds.schema.find((f) => f.name === field)
  if ((current.label ?? '') === value.trim()) return
  busy.value = true
  try {
    const labels = Object.fromEntries(ds.schema.filter((f) => f.label).map((f) => [f.name, f.label]))
    labels[field] = value.trim()
    await api.setLabels(ds.id, labels)
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
    rowsKey.value++
  } finally {
    busy.value = false
  }
}

// ---------- 比率（B3 spec 6.1） ----------
const ratioOpen = ref(false)
const ratioName = ref('')
const ratioNumerator = ref(null)
const ratioDenominator = ref(null)
const ratioError = ref('') // 刪除失敗：顯示在比率區
const ratioDialogError = ref('') // 新增失敗：顯示在對話框裡

const ratios = computed(() => selected.value?.ratios ?? [])
// 分子、分母的選單只列數字欄位，選項用顯示名稱
const numberFields = computed(() =>
  (selected.value?.schema ?? []).filter((f) => f.type === 'number').map((f) => ({ title: f.label || f.name, value: f.name })),
)
const canAddRatio = computed(
  () => !!ratioName.value.trim() && !!ratioNumerator.value && !!ratioDenominator.value && ratioNumerator.value !== ratioDenominator.value,
)
const shownName = (ds, field) => ds.schema.find((f) => f.name === field)?.label || field

function openRatio() {
  ratioName.value = ''
  ratioNumerator.value = ratioDenominator.value = null
  ratioDialogError.value = ''
  ratioOpen.value = true
}

// 整份清單送給後端，只送名稱、分子、分母；sensitive 是後端讀取時算的
async function saveRatios(ds, next) {
  busy.value = true
  try {
    await api.setRatios(ds.id, next.map((r) => ({ name: r.name, numerator: r.numerator, denominator: r.denominator })))
    await refresh()
  } finally {
    busy.value = false
  }
}

async function addRatio(ds) {
  ratioDialogError.value = ''
  try {
    await saveRatios(ds, [...ratios.value, { name: ratioName.value.trim(), numerator: ratioNumerator.value, denominator: ratioDenominator.value }])
    ratioOpen.value = false
    ratioError.value = ''
  } catch (e) {
    ratioDialogError.value = e.message
  }
}

// spec 6.1：按下去直接刪，不另外確認
async function removeRatio(ds, ratio) {
  try {
    await saveRatios(ds, ratios.value.filter((r) => r.name !== ratio.name))
    ratioError.value = ''
  } catch (e) {
    ratioError.value = e.message
  }
}
</script>

<template>
  <section class="cc-view">
    <header class="cc-head">
      <div>
        <div class="cc-eyebrow">01 · Data</div>
        <h1 class="cc-title">資料</h1>
        <p class="cc-desc">管理資料來源和欄位：顯示名稱會用在圖表上，標成敏感的欄位不會把內容送給 JEV。</p>
      </div>
      <v-btn color="primary" prepend-icon="mdi-plus" @click="openAdd">新增來源</v-btn>
    </header>

    <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>

    <div class="cc-split">
      <aside class="cc-panel cc-sticky">
        <div class="pa-3 pb-2">
          <v-text-field
            v-model="filter"
            placeholder="搜尋來源"
            aria-label="搜尋來源"
            prepend-inner-icon="mdi-magnify"
            hide-details
            clearable
          />
        </div>
        <div class="cc-label px-4 pb-1">來源 · {{ list.length }}</div>
        <v-list nav class="ds-list px-2 pb-2" aria-label="資料來源">
          <v-list-item
            v-for="ds in filtered"
            :key="ds.id"
            :active="ds.id === selectedId"
            :prepend-icon="KINDS[ds.kind]?.icon"
            :title="ds.name"
            :subtitle="`${ds.schema.length} 個欄位`"
            @click="emit('select', ds.id)"
          />
          <p v-if="!filtered.length" class="text-caption text-medium-emphasis pa-3">
            {{ list.length ? '沒有符合的來源' : '還沒有資料來源' }}
          </p>
        </v-list>
      </aside>

      <section v-if="selected" class="cc-panel ds-detail">
        <header class="cc-panel__head">
          <div class="ds-title">
            <div class="d-flex align-center ga-2">
              <h2 class="ds-name text-truncate">{{ selected.name }}</h2>
              <span class="tc-badge">{{ KINDS[selected.kind]?.text ?? selected.kind }}</span>
            </div>
            <div class="ds-meta cc-mono text-truncate">
              <template v-if="selected.kind === 'api'">{{ selected.apiUrl }} · </template><template v-else-if="selected.merge">{{ mergeSummary(selected) }} · </template>{{ selected.schema.length }} 個欄位 · {{ sensitiveCount }} 個敏感
            </div>
          </div>
          <v-btn append-icon="mdi-arrow-right" @click="emit('build', selected.id)">用這個來源製圖</v-btn>
        </header>
        <v-table class="ds-fields">
          <thead>
            <tr>
              <th>欄位</th>
              <th>型別</th>
              <th>樣本</th>
              <th>顯示名稱</th>
              <th class="text-center">敏感</th>
            </tr>
          </thead>
          <tbody :key="rowsKey">
            <tr v-for="f in selected.schema" :key="f.name">
              <td class="cc-mono">{{ f.name }}</td>
              <td>
                <span class="ds-type"><v-icon :icon="FIELD_TYPES[f.type]?.icon" size="14" />{{ FIELD_TYPES[f.type]?.text ?? f.type }}</span>
              </td>
              <td class="ds-samples cc-mono">{{ f.samples?.slice(0, 3).join('、') }}</td>
              <td class="ds-label">
                <!-- 按 Enter 或離開輸入框才存（change 事件）；打字過程不送 API -->
                <v-text-field
                  :model-value="f.label ?? ''"
                  :aria-label="`${f.name} 顯示名稱`"
                  :placeholder="f.name"
                  hide-details
                  :disabled="busy"
                  @change="(e) => saveLabel(selected, f.name, e.target.value)"
                  @keydown.enter="(e) => e.target.blur()"
                />
              </td>
              <!-- spec 6.3：繼承來的敏感設定在這裡關不掉，提示要回到哪個來源改 -->
              <td class="text-center" :title="f.sensitiveFrom ? `在『${f.sensitiveFrom}』標為敏感，要改請到那個來源` : undefined">
                <v-switch
                  :model-value="f.sensitive"
                  :aria-label="`${f.name} 敏感`"
                  hide-details
                  class="d-inline-flex"
                  :disabled="busy || !!f.sensitiveFrom"
                  @update:model-value="(on) => toggleSensitive(selected, f.name, on)"
                />
              </td>
            </tr>
          </tbody>
        </v-table>
        <p class="ds-foot">
          顯示名稱會用在圖表標題、座標軸和明細表頭，空白就顯示原始欄位名稱。
          標成敏感的欄位不會把樣本和相異值送給 JEV，用到它的卡片也不做異常檢查。
        </p>

        <!-- B3 spec 6.1：比率＝分子 ÷ 分母，存在來源上 -->
        <section class="ds-ratios" aria-label="比率">
          <div class="ds-ratios__head">
            <span class="cc-label">比率 · {{ ratios.length }}</span>
            <v-spacer />
            <v-btn size="small" variant="text" prepend-icon="mdi-plus" :disabled="busy" @click="openRatio">新增比率</v-btn>
          </div>
          <v-alert v-if="ratioError" type="error" density="compact" class="mx-5 mb-2">{{ ratioError }}</v-alert>
          <v-table v-if="ratios.length">
            <thead>
              <tr>
                <th>名稱</th>
                <th>公式</th>
                <th class="text-center">敏感</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in ratios" :key="r.name">
                <td>{{ r.name }}</td>
                <td>{{ shownName(selected, r.numerator) }} ÷ {{ shownName(selected, r.denominator) }}</td>
                <td class="text-center">
                  <span v-if="r.sensitive" class="tc-tag" title="分子或分母是敏感欄位">敏感</span>
                </td>
                <td class="text-right">
                  <v-btn
                    icon="mdi-delete-outline"
                    size="small"
                    variant="text"
                    :aria-label="`刪除比率 ${r.name}`"
                    :disabled="busy"
                    @click="removeRatio(selected, r)"
                  />
                </td>
              </tr>
            </tbody>
          </v-table>
          <p v-else class="ds-ratios__empty">比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。</p>
        </section>
      </section>

      <section v-else class="cc-panel cc-empty">
        <div class="cc-empty__icon"><v-icon icon="mdi-database-plus-outline" size="30" /></div>
        <h2>還沒有資料來源</h2>
        <p>上傳 CSV / Excel，或註冊一個外部 API，欄位會自動判斷型別。</p>
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openAdd">新增來源</v-btn>
      </section>
    </div>

    <v-dialog v-model="addOpen" :max-width="addTab === 'merge' ? 760 : 520" transition="tc-sheet">
      <v-card>
        <v-card-title class="pt-5 px-6">新增資料來源</v-card-title>
        <v-tabs v-model="addTab" class="px-4">
          <v-tab value="upload" prepend-icon="mdi-file-upload-outline">上傳檔案</v-tab>
          <v-tab value="api" prepend-icon="mdi-api">外部 API</v-tab>
          <v-tab value="merge" prepend-icon="mdi-set-merge">合併</v-tab>
        </v-tabs>
        <v-card-text class="px-6">
          <v-window v-model="addTab">
            <!-- spec 4.8：兩個分頁不是前後頁，切換時直接換內容，不左右滑 -->
            <v-window-item value="upload" :transition="false" :reverse-transition="false">
              <v-file-input v-model="file" label="CSV / Excel" accept=".csv,.xlsx,.xls" prepend-icon="" prepend-inner-icon="mdi-paperclip" />
              <v-text-field v-model="name" label="名稱（預設用檔名）" />
              <v-text-field v-model="sheet" label="工作表（Excel 選填）" />
            </v-window-item>
            <v-window-item value="api" :transition="false" :reverse-transition="false">
              <v-text-field v-model="apiName" label="名稱" />
              <v-text-field v-model="apiUrl" label="網址" />
              <v-textarea
                v-model="apiHeaders"
                label="headers（JSON，值只能寫 ${DS_環境變數名}）"
                hint='範例：{"Authorization":"${DS_ERP_AUTH}"}'
                persistent-hint
                rows="2"
              />
            </v-window-item>
            <v-window-item value="merge" :transition="false" :reverse-transition="false">
              <MergeForm :key="mergeKey" :sources="list" @created="onMerged" />
            </v-window-item>
          </v-window>
          <v-alert v-if="addError" type="error" density="compact" class="mt-3">{{ addError }}</v-alert>
        </v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <!-- v-card-actions 預設把按鈕變成 text，這裡明確用 flat -->
          <v-btn variant="flat" @click="addOpen = false">取消</v-btn>
          <v-btn v-if="addTab === 'upload'" variant="flat" color="primary" :loading="adding" @click="upload">上傳</v-btn>
          <v-btn v-else-if="addTab === 'api'" variant="flat" color="primary" :loading="adding" @click="register">註冊</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="ratioOpen" max-width="480" transition="tc-sheet">
      <v-card>
        <v-card-title class="pt-5 px-6">新增比率</v-card-title>
        <v-card-text class="px-6">
          <v-text-field v-model="ratioName" label="名稱" placeholder="例如退貨率" />
          <v-select v-model="ratioNumerator" label="分子" :items="numberFields" />
          <v-select v-model="ratioDenominator" label="分母" :items="numberFields" />
          <v-alert v-if="ratioDialogError" type="error" density="compact">{{ ratioDialogError }}</v-alert>
        </v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <v-btn variant="flat" @click="ratioOpen = false">取消</v-btn>
          <v-btn variant="flat" color="primary" :disabled="!canAddRatio" :loading="busy" @click="addRatio(selected)">新增</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </section>
</template>

<style scoped>
.ds-list { overflow-y: auto; }
.ds-detail { overflow: hidden; }
.ds-title { flex: 1; min-width: 0; }
.ds-name { margin: 0; font-size: 17px; font-weight: 600; }
.ds-meta { margin-top: 4px; font-size: 12px; color: var(--tc-muted); }
.ds-fields td { height: 52px; }
.ds-fields td.cc-mono { font-size: 13.5px; color: var(--tc-text); }
.ds-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--tc-text-2);
}
.ds-samples {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--tc-muted);
}
.ds-label { min-width: 180px; }
.ds-foot {
  margin: 0;
  padding: 14px 20px 18px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--tc-muted);
  border-top: 1px solid var(--tc-border-subtle);
}
/* B3 spec 6.1：欄位表下面的比率區 */
.ds-ratios { border-top: 1px solid var(--tc-border-subtle); }
.ds-ratios__head { display: flex; align-items: center; padding: 12px 20px 4px; }
.ds-ratios__empty { margin: 0; padding: 4px 20px 18px; font-size: 12.5px; color: var(--tc-muted); }
</style>
