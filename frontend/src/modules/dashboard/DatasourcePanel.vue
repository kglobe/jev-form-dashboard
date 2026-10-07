<script setup>
import { computed, onMounted, ref } from 'vue'
import ShareDialog from '../auth/ShareDialog.vue'
import { api } from './api.js'
import { FIELD_TYPES } from './fields.js'
import MergeForm from './MergeForm.vue'
import './motion.css'

const props = defineProps({
  selectedId: { type: String, default: null },
  // 唯讀分享 spec 2.6：只有管理員能註冊外部 API 來源
  admin: { type: Boolean, default: false },
  // 資料來源共享 spec 4.2：拿來跟每個來源的 createdBy 比，分出自己的和別人分享的
  username: String,
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

// 資料來源共享 spec 6.1：清單先列自己的，再列別人分享的；別人分享的只能拿來製圖，不能改
const isShared = (ds) => ds.createdBy !== props.username
const sharedCount = computed(() => list.value.filter(isShared).length)
const selectedShared = computed(() => !!selected.value && isShared(selected.value))
const sharing = ref(false)

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
// 寬表轉長表 spec 第 5 節：勾了才送 year、periodName、valueName；年份預設今年
const thisYear = () => String(new Date().getFullYear())
const unpivot = ref(false)
const unpivotYear = ref(thisYear())
const periodName = ref('')
const valueName = ref('')

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
    name.value = sheet.value = apiName.value = apiUrl.value = periodName.value = valueName.value = ''
    unpivot.value = false
    unpivotYear.value = thisYear()
    apiHeaders.value = '{}'
  } catch (e) {
    addError.value = e.message
  } finally {
    adding.value = false
  }
}

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

// v-file-input 在不同版本可能回傳 File 或 File[]
const firstFile = (v) => (Array.isArray(v) ? v[0] : v)
// 上傳和更新資料共用：沒選檔案、檔案太大時要顯示的錯誤，沒問題是空字串
function fileError(f) {
  if (!f) return '請先選擇檔案'
  return f.size > MAX_UPLOAD_BYTES ? '檔案太大，上限 50MB' : ''
}

function upload() {
  const f = firstFile(file.value)
  addError.value = fileError(f)
  if (addError.value) return
  const form = new FormData()
  form.append('file', f)
  form.append('name', name.value || f.name)
  if (sheet.value) form.append('sheet', sheet.value)
  if (unpivot.value) {
    form.append('unpivot', 'true')
    form.append('year', unpivotYear.value ?? '')
    form.append('periodName', periodName.value ?? '')
    form.append('valueName', valueName.value ?? '')
  }
  return run(async () => {
    const ds = await api.uploadDatasource(form)
    if (ds.unpivot) notice.value = unpivotNotice(ds)
    return ds
  })
}

// 超過 3 欄寫頭尾和欄數（「共 12 欄轉成」），3 欄以內全部列出（「Q1、Q2 轉成」）
const columnsText = (cols) => (cols.length > 3 ? `${cols[0]}～${cols.at(-1)} 共 ${cols.length} 欄` : `${cols.join('、')} `)
const unpivotSummary = (u) => `${columnsText(u.columns)}轉成「${u.periodName}」「${u.valueName}」兩欄`

function unpivotNotice(ds) {
  const dropped = ds.unpivot.dropped.length ? `；沒有匯入：${ds.unpivot.dropped.join('、')}` : ''
  return `已把 ${unpivotSummary(ds.unpivot)}，共 ${ds.rowCount} 筆${dropped}`
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

// ---------- 刪除來源（對話框） ----------

const deleteOpen = ref(false)
const deleteTarget = ref(null) // 按下「刪除來源」時的來源；之後切換選取不影響要刪哪個
const deleteUsage = ref({ mergedBy: [], dashboards: [] })
const deleteError = ref('')
const checking = ref(false) // 查 usage 中
const deleting = ref(false)

async function openDelete(ds) {
  if (checking.value) return
  checking.value = true
  try {
    deleteUsage.value = await api.datasourceUsage(ds.id)
    error.value = ''
    deleteTarget.value = { id: ds.id, name: ds.name }
    deleteError.value = ''
    deleteOpen.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    checking.value = false
  }
}

async function removeDatasource(ds) {
  if (deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await api.deleteDatasource(ds.id)
    deleteOpen.value = false
    await refresh()
  } catch (e) {
    deleteError.value = e.message
  } finally {
    deleting.value = false
  }
}

// ---------- 更新資料（對話框，更新資料 spec 第 5 節） ----------

const reloadOpen = ref(false)
const reloadTarget = ref(null) // 按下「更新資料」時的來源；之後切換選取不影響更新哪個
const reloadFile = ref(null)
const reloadSheet = ref('')
const reloadMode = ref(null) // 沒有預設值，一定要自己選整份覆蓋或接在後面
const reloadError = ref('')
const reloading = ref(false)
const notice = ref('') // 更新成功、寬表轉長表的訊息，顯示在頁面上方

function openReload(ds) {
  reloadTarget.value = { id: ds.id, name: ds.name, rowCount: ds.rowCount, unpivot: ds.unpivot }
  reloadFile.value = null
  reloadSheet.value = reloadError.value = notice.value = ''
  reloadMode.value = null
  reloadOpen.value = true
}

async function reload() {
  if (reloading.value) return
  const f = firstFile(reloadFile.value)
  reloadError.value = fileError(f)
  if (reloadError.value) return
  const form = new FormData()
  form.append('file', f)
  form.append('mode', reloadMode.value)
  if (reloadSheet.value) form.append('sheet', reloadSheet.value)
  reloading.value = true
  try {
    const ds = await api.reloadDatasource(reloadTarget.value.id, form)
    reloadOpen.value = false
    notice.value = `已更新「${ds.name}」：共 ${ds.rowCount} 筆`
    // 已經更新成功了，重讀清單失敗不算更新失敗，錯誤放在頁面上
    await refresh().catch((e) => (error.value = e.message))
  } catch (e) {
    reloadError.value = e.message
  } finally {
    reloading.value = false
  }
}
</script>

<template>
  <section class="cc-view">
    <header class="cc-head">
      <div>
        <div class="cc-eyebrow">01 · Data</div>
        <h1 class="cc-title">資料</h1>
        <p class="cc-desc">
          {{ selectedShared ? `「${selected.createdBy}」分享給你的來源，可以拿來製圖，不能修改。` : '管理資料來源和欄位：顯示名稱會用在圖表上，標成敏感的欄位不會把內容送給 JEV。' }}
        </p>
      </div>
      <v-btn color="primary" prepend-icon="mdi-plus" @click="openAdd">新增來源</v-btn>
    </header>

    <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
    <v-alert v-if="notice" type="success" density="compact" closable @click:close="notice = ''">{{ notice }}</v-alert>

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
        <div class="cc-label px-4 pb-1">來源 · {{ list.length - sharedCount }}</div>
        <v-list nav class="ds-list px-2 pb-2" aria-label="資料來源">
          <v-list-item
            v-for="ds in filtered.filter((d) => !isShared(d))"
            :key="ds.id"
            :active="ds.id === selectedId"
            :prepend-icon="KINDS[ds.kind]?.icon"
            :title="ds.name"
            :subtitle="`${ds.schema.length} 個欄位`"
            @click="emit('select', ds.id)"
          />
          <p v-if="!filtered.some((d) => !isShared(d))" class="text-caption text-medium-emphasis pa-3">
            {{ list.length > sharedCount ? '沒有符合的來源' : '還沒有資料來源' }}
          </p>
        </v-list>
        <template v-if="sharedCount">
          <div class="cc-label px-4 pt-2 pb-1">分享給我的 · {{ sharedCount }}</div>
          <v-list nav class="ds-list px-2 pb-2" aria-label="分享給我的資料來源">
            <v-list-item
              v-for="ds in filtered.filter(isShared)"
              :key="ds.id"
              :active="ds.id === selectedId"
              :prepend-icon="KINDS[ds.kind]?.icon"
              :title="ds.name"
              :subtitle="`來自 ${ds.createdBy} · ${ds.schema.length} 個欄位`"
              @click="emit('select', ds.id)"
            />
          </v-list>
        </template>
      </aside>

      <section v-if="selected" class="cc-panel ds-detail">
        <header class="cc-panel__head">
          <div class="ds-title">
            <div class="d-flex align-center ga-2">
              <h2 class="ds-name text-truncate">{{ selected.name }}</h2>
              <span class="tc-badge">{{ KINDS[selected.kind]?.text ?? selected.kind }}</span>
            </div>
            <div class="ds-meta cc-mono text-truncate">
              <!-- 資料來源共享 spec 6.1：別人的外部 API 看不到網址；別人的合併來源多半看不到輸入來源，只寫種類 -->
              <template v-if="selectedShared && selected.kind !== 'upload'">{{ KINDS[selected.kind]?.text }} · </template><template v-else-if="selected.kind === 'api'">{{ selected.apiUrl }} · </template><template v-else-if="selected.merge">{{ mergeSummary(selected) }} · </template>{{ selected.schema.length }} 個欄位 · {{ sensitiveCount }} 個敏感
            </div>
          </div>
          <template v-if="!selectedShared">
            <v-btn prepend-icon="mdi-share-variant-outline" @click="sharing = true">分享</v-btn>
            <!-- 更新資料 spec 5.1：只有上傳的檔案能換資料；外部 API 每次載入都重抓，合併來源跟著輸入來源 -->
            <v-btn v-if="selected.kind === 'upload'" prepend-icon="mdi-database-refresh-outline" @click="openReload(selected)">更新資料</v-btn>
            <v-btn prepend-icon="mdi-delete-outline" :loading="checking" @click="openDelete(selected)">刪除來源</v-btn>
          </template>
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
                  :disabled="busy || selectedShared"
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
                  :disabled="busy || !!f.sensitiveFrom || selectedShared"
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
            <v-btn v-if="!selectedShared" size="small" variant="text" prepend-icon="mdi-plus" :disabled="busy" @click="openRatio">新增比率</v-btn>
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
                    v-if="!selectedShared"
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
        <p>上傳 CSV / Excel，{{ admin ? '或註冊一個外部 API，' : '' }}欄位會自動判斷型別。</p>
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openAdd">新增來源</v-btn>
      </section>
    </div>

    <v-dialog v-model="addOpen" :max-width="addTab === 'merge' ? 760 : 520" transition="tc-sheet">
      <v-card>
        <v-card-title class="pt-5 px-6">新增資料來源</v-card-title>
        <v-tabs v-model="addTab" class="px-4">
          <v-tab value="upload" prepend-icon="mdi-file-upload-outline">上傳檔案</v-tab>
          <v-tab v-if="admin" value="api" prepend-icon="mdi-api">外部 API</v-tab>
          <v-tab value="merge" prepend-icon="mdi-set-merge">合併</v-tab>
        </v-tabs>
        <v-card-text class="px-6">
          <v-window v-model="addTab">
            <!-- spec 4.8：兩個分頁不是前後頁，切換時直接換內容，不左右滑 -->
            <v-window-item value="upload" :transition="false" :reverse-transition="false">
              <v-file-input v-model="file" label="CSV / Excel" accept=".csv,.xlsx,.xls" prepend-icon="" prepend-inner-icon="mdi-paperclip" />
              <v-text-field v-model="name" label="名稱（預設用檔名）" />
              <v-text-field v-model="sheet" label="工作表（Excel 選填）" />
              <v-checkbox v-model="unpivot" label="把 1月、2月…這類欄位轉成兩欄（寬表轉長表）" density="compact" hide-details />
              <template v-if="unpivot">
                <v-text-field v-model="unpivotYear" label="年份" hint="標題沒寫年份時用，例如 1月、Q1" persistent-hint inputmode="numeric" class="mt-3" />
                <div class="d-flex ga-3 mt-3">
                  <v-text-field v-model="periodName" label="期間欄名稱" placeholder="月份" hint="預設：月份，季是「季」" persistent-hint />
                  <v-text-field v-model="valueName" label="數值欄名稱" placeholder="數值" hint="預設：數值" persistent-hint />
                </div>
              </template>
            </v-window-item>
            <v-window-item value="api" :transition="false" :reverse-transition="false">
              <v-text-field v-model="apiName" label="名稱" />
              <v-text-field v-model="apiUrl" label="網址" />
              <v-textarea
                v-model="apiHeaders"
                label="headers（JSON，值只能寫 ${DS_環境變數名}）"
                hint='範例：{"Authorization":"${DS_ERP_AUTH}"}。密鑰還要在 backend/.env 綁定這個網址的 host'
                persistent-hint
                rows="2"
              />
            </v-window-item>
            <v-window-item value="merge" :transition="false" :reverse-transition="false">
              <MergeForm :key="mergeKey" :sources="list" :username="username" @created="onMerged" />
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

    <v-dialog v-model="deleteOpen" max-width="480" transition="tc-sheet">
      <v-card v-if="deleteTarget">
        <template v-if="deleteUsage.mergedBy.length">
          <v-card-title class="pt-5 px-6">無法刪除</v-card-title>
          <v-card-text class="px-6">
            <p>「{{ deleteTarget.name }}」是下面這些合併來源的輸入，要先刪掉它們：</p>
            <div v-for="m in deleteUsage.mergedBy" :key="m.id">{{ m.name }}</div>
          </v-card-text>
          <v-card-actions class="px-6 pb-5">
            <v-spacer />
            <v-btn variant="flat" @click="deleteOpen = false">知道了</v-btn>
          </v-card-actions>
        </template>
        <template v-else>
          <v-card-title class="pt-5 px-6">刪除資料來源</v-card-title>
          <v-card-text class="px-6">
            <p>確定刪除「{{ deleteTarget.name }}」？刪除後無法復原。</p>
            <template v-if="deleteUsage.dashboards.length">
              <p class="mt-3">下面這些儀表板有用到它的圖，刪除後會顯示「資料來源已刪除」，可以再按 ✕ 移除：</p>
              <div v-for="d in deleteUsage.dashboards" :key="d.id">{{ d.name }}（{{ d.charts }} 張圖）</div>
            </template>
            <!-- 資料來源共享 spec 6.3 -->
            <p v-if="deleteUsage.shared" class="mt-3">這個來源有分享給別人，刪除後，別人用它做的圖和合併來源都會失效。</p>
            <v-alert v-if="deleteError" type="error" density="compact" class="mt-3">{{ deleteError }}</v-alert>
          </v-card-text>
          <v-card-actions class="px-6 pb-5">
            <v-spacer />
            <v-btn variant="flat" @click="deleteOpen = false">取消</v-btn>
            <v-btn variant="flat" color="error" :loading="deleting" @click="removeDatasource(deleteTarget)">刪除</v-btn>
          </v-card-actions>
        </template>
      </v-card>
    </v-dialog>

    <!-- 更新資料 spec 5.2：請求還沒回來就不能關，不然錯誤沒地方顯示，按了取消資料也照樣換掉 -->
    <v-dialog v-model="reloadOpen" max-width="520" transition="tc-sheet" :persistent="reloading">
      <v-card v-if="reloadTarget">
        <v-card-title class="pt-5 px-6">更新資料</v-card-title>
        <v-card-text class="px-6">
          <p v-if="reloadTarget.unpivot">
            「{{ reloadTarget.name }}」目前有 {{ reloadTarget.rowCount }} 筆資料。上傳時把 {{ unpivotSummary(reloadTarget.unpivot) }}。新檔案的欄位要跟第一次上傳的一樣，會照同樣方式轉。
          </p>
          <p v-else>「{{ reloadTarget.name }}」目前有 {{ reloadTarget.rowCount }} 筆資料。新檔案的欄位名稱要跟原本一樣。</p>
          <p class="mt-2 mb-4">更新後，用到它的圖和合併來源都會顯示新的資料。</p>
          <v-file-input v-model="reloadFile" label="CSV / Excel" accept=".csv,.xlsx,.xls" prepend-icon="" prepend-inner-icon="mdi-paperclip" />
          <v-text-field v-model="reloadSheet" label="工作表（Excel 選填）" />
          <v-radio-group v-model="reloadMode" inline hide-details aria-label="更新方式">
            <v-radio value="replace" label="整份覆蓋" />
            <v-radio value="append" label="接在後面" />
          </v-radio-group>
          <v-alert v-if="reloadError" type="error" density="compact" class="mt-3">{{ reloadError }}</v-alert>
        </v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <v-btn variant="flat" :disabled="reloading" @click="reloadOpen = false">取消</v-btn>
          <v-btn variant="flat" color="primary" :disabled="!reloadMode" :loading="reloading" @click="reload">更新</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 資料來源共享 spec 6.2：放在最後，前面幾個對話框的順序不變 -->
    <ShareDialog v-if="selected && !selectedShared" :key="selected.id" v-model="sharing" :datasource="selected" />
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
