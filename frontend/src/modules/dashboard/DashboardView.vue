<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import DashboardFilters from './DashboardFilters.vue'
import KpiForm from './KpiForm.vue'
import { api } from './api.js'
import { filterCount, filterKey } from './filters.js'
import { digest, SEVERITY_CLASS } from './highlights.js'
import { prefersReducedMotion } from './motion.js'
import './motion.css'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
  // 預覽等 JEV 回應時：圖表區變淡、標 aria-busy、「✕」停用
  busy: { type: Boolean, default: false },
  // 唯讀分享 spec 7.8：檢視者只能看
  readonly: { type: Boolean, default: false },
  // 匯出 spec 5.2：App 的訪客檢視（沒登入）傳 true，卡片沒有下載鈕
  guest: { type: Boolean, default: false },
})
// remove：預覽的「✕」交給製圖頁處理；updated：已存儀表板移除一張圖後，後端回傳的新設定
const emit = defineEmits(['remove', 'updated'])

// ponytail: 跟 motion.css 的 --tc-dur-1 同值，改一邊要一起改
const EXIT_MS = 120

const layout = ref([])
// 退場那 120ms 裡，消失的卡片還要用舊的 spec 畫，所以不能直接從 props.config 算
const specById = ref({})
const dataById = ref({})
// 本期重點 spec 5.1：/highlight 的回應；還沒回來、改了篩選、檢查失敗時是 null。卡片徽章和本期重點都從這裡算
const check = ref(null)
const highlightById = computed(() => Object.fromEntries((check.value?.highlights ?? []).map((h) => [h.chartId, h])))
const error = ref('')
const loadError = ref('')
const settled = ref(false)
const leaving = ref(new Set())
const entering = ref(new Set())
// 已存儀表板按「✕」之後等確認的卡片：{ chartId, title }
const confirmRemove = ref(null)
// 移除卡片的 PUT 還沒回來：「✕」停用、拖拉/縮放先不存版面，避免跟這次的移除互相蓋過去
const removing = ref(false)
// 篩選器 spec 2.3：目前的篩選，打開時是存著的預設（config.filters）；臨時改的不存檔
const filters = ref(null)
// 「存成預設」的 PUT 還沒回來：理由同 removing，拖拉和移除帶的還是舊的預設，會把它蓋回去
const savingFilters = ref(false)
// KPI spec 2.3：「加 KPI 卡」和 KPI 卡「設定」共用的表單；kpiSpec 是要改的那張，加新卡時是 null
const kpiOpen = ref(false)
const kpiSpec = ref(null)
// 自動更新 spec 2.4：最近一次成功抓到 /data 的時間；refreshFailed 是自動更新的 /data 失敗了（2.3）
const updatedAt = ref(null)
const refreshFailed = ref(false)
// 自動更新 spec 6.2：改間隔的 PUT 還沒回來，理由同 savingFilters
const savingRefresh = ref(false)

// 自動更新 spec 2.1：存的是分鐘，整點的寫成小時
const everyText = (m) => (m % 60 ? `每 ${m} 分鐘` : `每 ${m / 60} 小時`)
const REFRESH_ITEMS = [{ title: '不自動更新', value: null },
  ...[1, 5, 10, 15, 60, 180, 360, 720, 1440].map((m) => ({ title: everyText(m), value: m }))]

const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))
// 多來源 spec 3.2：圖來自兩個以上的來源時，卡片才標來源名稱
const multiSource = computed(() => new Set(props.config.layout.map((s) => s.datasourceId)).size > 1)

// 多來源 spec 3.3：依據一組一行。多來源時寫出來源名稱，名稱從 /data 回傳的 source 來，
// 每張卡片都有資料之前先不顯示；找不到名稱（例如來源載入失敗）就不加括號
const basisLines = computed(() => {
  const groups = props.preview ? [] : (props.config.basis ?? []).filter((b) => b.intents?.length)
  if (!multiSource.value) return groups.map((b) => `依據：${b.intents.join(' → ')}`)
  if (!props.config.layout.every((s) => dataById.value[s.chartId])) return []
  const names = {}
  for (const s of props.config.layout) names[s.datasourceId] ??= dataById.value[s.chartId].source
  return groups.map((b) => `依據${names[b.datasourceId] ? `（${names[b.datasourceId]}）` : ''}：${b.intents.join(' → ')}`)
})

// spec 4.4：錯開的順序照畫面位置（先 y 再 x）。使用者拖過之後，layout 陣列的順序不一定等於畫面順序
const byScreen = computed(() => [...layout.value].sort((a, b) => a.y - b.y || a.x - b.x))
const screenOrder = computed(() => Object.fromEntries(byScreen.value.map((l, n) => [l.i, n])))

// 本期重點 spec 2.1、2.4：同分照畫面順序；拿掉的卡片一開始退場就不列也不算張數，不用重新檢查
const periodDigest = computed(() => digest(check.value, byScreen.value
  .filter((l) => !leaving.value.has(l.i))
  .map((l) => ({ spec: specById.value[l.i], data: dataById.value[l.i] }))))

// 自動更新 spec 2.4：今天的寫「14:05」，不是今天的寫「10/6 14:05」
function clock(d) {
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return d.toDateString() === new Date().toDateString() ? hm : `${d.getMonth() + 1}/${d.getDate()} ${hm}`
}
const updatedText = computed(() => {
  if (!updatedAt.value) return ''
  const at = clock(updatedAt.value)
  if (refreshFailed.value) return `更新失敗，顯示的是 ${at} 的資料`
  // 擁有者旁邊就是選單，不另外寫間隔
  const every = props.readonly && props.config.refreshMinutes ? `${everyText(props.config.refreshMinutes)}自動更新，` : ''
  return `${every}最後更新 ${at}`
})

let loadSeq = 0
// removeCard 剛 emit('updated', ...) 之後，config 一定會馬上換成那份新設定；
// 這個旗標讓下一次 load() 知道「這次的變化是自己的 PUT 造成的」，才能用「只少了卡片」的捷徑。
// App 重新打開同一份儀表板不會經過這裡，旗標是 false，就一定重抓
let ownUpdatePending = false
// 還在等 JEV 回應的 highlights 是哪一次（seq），0 是沒有；先送出的舊請求先回來時不會把它清掉
let highlightsPending = 0
// dataById 是用哪一組篩選抓的；跟目前的篩選不一樣（新篩選的資料還沒回來）時，不能走「只少了卡片」的捷徑
let fetchedKey = filterKey(null)
// 同一份儀表板的寫入依操作順序送出，舊的版面存檔才不會在移除圖表、加 KPI 卡之後覆蓋新版面
let writeTail = Promise.resolve()
function queueWrite(send) {
  const write = writeTail.catch(() => {}).then(send)
  writeTail = write
  return write
}
const queueUpdate = (config) => queueWrite(() => api.updateDashboard(config.id, config))
// 預覽裡最近一次被拿掉的卡片：chartId → { spec, data }；按「復原」放回同一張圖時直接沿用資料
let removed = {}
const sameChart = (a, b) => a.type === b.type && JSON.stringify(a.mapping) === JSON.stringify(b.mapping)

async function load() {
  const seq = ++loadSeq
  const next = props.config.layout
  const ids = new Set(next.map((s) => s.chartId))
  const shown = layout.value.map((l) => l.i)
  const gone = shown.filter((id) => !ids.has(id))
  // spec 10.5：消失的卡片先退場，退場結束才套用新版面；減少動態效果時不等
  if (gone.length && !prefersReducedMotion()) {
    leaving.value = new Set(gone)
    await new Promise((resolve) => setTimeout(resolve, EXIT_MS))
    if (seq !== loadSeq) return
  }
  leaving.value = new Set()
  // spec 4.4：已存的儀表板第一次顯示時每張都進場（App 用 :key 換儀表板，每次打開都是第一次）；
  // 預覽第一次顯示不進場，對話框自己有轉場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : props.preview ? [] : ids)
  // spec 10.7：按「復原」放回同一張圖時，沿用它被拿掉時的資料，不重抓、不閃「載入中」
  const restored = Object.fromEntries(next
    .filter((s) => removed[s.chartId] && sameChart(removed[s.chartId].spec, s))
    .map((s) => [s.chartId, removed[s.chartId].data]))
  removed = props.preview
    ? Object.fromEntries(gone.filter((id) => dataById.value[id])
      .map((id) => [id, { spec: specById.value[id], data: dataById.value[id] }]))
    : {}
  specById.value = Object.fromEntries(next.map((s) => [s.chartId, s]))
  layout.value = next.map((s) => ({ i: s.chartId, ...s.gridPos }))
  // spec 10.3：只清掉不在新版面裡的資料；留下來的卡片先用舊資料，等新資料回來再換
  dataById.value = {
    ...Object.fromEntries(Object.entries(dataById.value).filter(([id]) => ids.has(id))),
    ...restored,
  }
  error.value = ''
  loadError.value = ''
  // 只是少了卡片時，剩下的卡片都有資料了，不必再打 API：預覽的「✕」（spec 3.2：「✕」不呼叫 API），
  // 以及已存儀表板移除單張圖（多來源 spec 3.4：異常標註也留著）。App 重新打開同一份儀表板會換一個新物件，
  // 不能套這個捷徑，一定要重抓
  const ownRemoval = props.preview || ownUpdatePending
  ownUpdatePending = false
  // 篩選器 spec 6.3：App 換了一份設定（打開、重新打開）就回到存著的預設；自己的移除、存預設不動目前的篩選
  if (!ownRemoval) filters.value = props.config.filters ?? null
  if (ownRemoval && fetchedKey === filterKey(filters.value) && next.every((s) => dataById.value[s.chartId])) {
    // 上一次的 highlights 還沒回來：那個回應會因為 loadSeq 換了被丟掉，這裡要重問一次才看得到標註
    if (props.preview || !highlightsPending) return
    await fetchHighlights(seq)
    return
  }
  await fetchData(seq)
}

// 篩選器 spec 6.1：篩選一變就重抓；跟 load() 一樣，卡片先留著舊資料，新資料回來再換（多來源 spec 10.3）
function setFilters(next) {
  filters.value = next
  loadError.value = ''
  // 移除的卡片還在退場：等著的 load() 退場結束會套上新版面，再用這組篩選抓（資料是舊篩選的，不會走捷徑）；
  // 這裡換掉 loadSeq 的話，那次 load() 會整個作廢，新版面就套不上去
  if (leaving.value.size) return
  fetchData(++loadSeq)
}

// refresh：這次是自動更新（自動更新 spec 2.2、2.3）
async function fetchData(seq, refresh = false) {
  // 自動更新時篩選沒變，舊的標註留到新的回來；改篩選時舊標註判斷的是另一組資料，先清掉
  if (!refresh) check.value = null
  const f = filters.value
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id, f)
    if (seq !== loadSeq) return
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
    fetchedKey = filterKey(f)
    updatedAt.value = new Date()
    refreshFailed.value = false
    loadError.value = '' // 打開時就失敗的，自動更新成功後把格線放回來
  } catch (e) {
    if (seq !== loadSeq) return
    scheduleRefresh()
    // 自動更新失敗：卡片和標註都留著，到下一個間隔再試
    if (refresh && !loadError.value) {
      refreshFailed.value = true
      return
    }
    loadError.value = e.message
    settled.value = false // GridLayout 會被 v-if 拿掉，重新掛載時要再等一次 layout-ready
    return
  }
  if (props.preview) return
  scheduleRefresh()
  await fetchHighlights(seq)
}

// 自動更新 spec 6.3：一個 setTimeout，每次抓完 /data 從那時重新計時；預覽和沒有卡片時不排
const refreshMs = computed(() =>
  props.preview || !props.config.layout.length ? 0 : (props.config.refreshMinutes ?? 0) * 60_000)
let refreshTimer = 0
// 自動更新 spec 2.1：工作階段閒置 30 分鐘就過期。間隔超過 15 分鐘時，中間每 15 分鐘打一次 me 續住，
// 跟 15 分鐘以內的間隔一樣，掛在電視上的儀表板不會跳「登入逾時」
const KEEP_ALIVE_MS = 15 * 60_000
let keepAliveTimer = 0
// 時間到了但分頁在背景，等回到前景再抓
let refreshOverdue = false

function scheduleRefresh() {
  clearTimeout(refreshTimer)
  clearTimeout(keepAliveTimer)
  if (refreshMs.value) refreshTimer = setTimeout(refresh, refreshMs.value)
  if (refreshMs.value > KEEP_ALIVE_MS) keepAliveTimer = setTimeout(keepAlive, KEEP_ALIVE_MS)
}

// 背景分頁不續，跟自動更新一樣暫停；沒登入的訪客 me 回 401，不理它
function keepAlive() {
  keepAliveTimer = setTimeout(keepAlive, KEEP_ALIVE_MS)
  if (document.visibilityState === 'visible') api.me().catch(() => {})
}

// 先排好下一次：這次的回應被丟掉（例如移除卡片走了不重抓的捷徑）時，計時也不會斷
function refresh() {
  scheduleRefresh()
  refreshOverdue = false
  if (!refreshMs.value) return
  if (document.visibilityState === 'hidden') {
    refreshOverdue = true
    return
  }
  // 卡片還在退場：退場結束的 load() 會自己抓
  if (leaving.value.size) return
  fetchData(++loadSeq, true)
}

function onVisibilityChange() {
  if (refreshOverdue && document.visibilityState === 'visible') refresh()
}
document.addEventListener('visibilitychange', onVisibilityChange)

// 擁有者改了間隔、最後一張卡片拿掉了：從現在重新計時，或停掉
watch(refreshMs, scheduleRefresh)

// 異常檢查失敗就不標註，不顯示錯誤；篩選器 spec 2.4：照篩選後的資料判斷。JEV 失敗是 204，api.js 轉成 null
async function fetchHighlights(seq) {
  highlightsPending = seq
  const found = await api.highlights(props.config.id, filters.value).catch(() => null)
  if (highlightsPending === seq) highlightsPending = 0
  if (seq !== loadSeq) return
  check.value = found
}

const gridEl = ref(null)
// 本期重點 spec 5.1：捲到那張卡，焦點也移過去（卡片有 tabindex="-1"），鍵盤和螢幕閱讀器才跟得上
function scrollToCard(chartId) {
  const card = gridEl.value.querySelector(`[data-chart-id="${CSS.escape(chartId)}"]`)
  card.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'nearest' })
  card.focus({ preventScroll: true })
}

watch(() => props.config, load, { immediate: true })

// 卸載後，還在等退場或資料的 load() 直接作廢，不再打 API；自動更新也停掉
onBeforeUnmount(() => {
  loadSeq++
  clearTimeout(refreshTimer)
  clearTimeout(keepAliveTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

// spec 10.2：layout-ready 那個 task 裡，grid-layout-plus 量完寬度但這一幀還沒 recalc 版面；
// 用兩層 rAF 等到下一幀才打開位移轉場，才不會跟卡片定位同一次生效，飛過畫面
function onLayoutReady() {
  requestAnimationFrame(() => requestAnimationFrame(() => (settled.value = true)))
}

// 格線上目前的位置套回每張圖；拖拉、縮放、移除都照這份存
function currentLayout() {
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  return props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] }))
}

// 篩選器 spec 6.3：存的是整份設定，filters 是 config 裡存著的預設，不是臨時的篩選
async function saveLayout() {
  if (props.preview || props.readonly || removing.value || savingFilters.value || savingRefresh.value) return
  error.value = ''
  await queueUpdate({ ...props.config, layout: currentLayout() }).catch((e) => {
    if (!removing.value) error.value = e.message
  })
}

function askRemove(chartId) {
  confirmRemove.value = { chartId, title: dataById.value[chartId]?.title ?? specById.value[chartId].title }
}

// 多來源 spec 3.4：用格線上目前的版面（使用者拖過的位置照樣保留）拿掉這張再存；成功後交給 App 換成回傳的設定
async function removeCard() {
  if (!confirmRemove.value) return // 對話框關閉前又按了一次「移除」
  const { chartId } = confirmRemove.value
  confirmRemove.value = null
  error.value = ''
  removing.value = true
  try {
    const next = { ...props.config, layout: currentLayout().filter((s) => s.chartId !== chartId) }
    const reply = await queueUpdate(next)
    error.value = ''
    ownUpdatePending = true
    emit('updated', reply)
  } catch (e) {
    error.value = e.message
  } finally {
    removing.value = false
  }
}

// 篩選器 spec 2.3、6.1：目前的篩選連同格線上目前的版面一起存；成功後交給 App 換成回傳的設定，
// 篩選沒變、資料也都在，load() 走「只少了卡片」的捷徑，不重抓
async function saveFilters() {
  error.value = ''
  savingFilters.value = true
  try {
    const reply = await queueUpdate({ ...props.config, layout: currentLayout(), filters: filters.value })
    ownUpdatePending = true
    emit('updated', reply)
  } catch (e) {
    error.value = e.message
  } finally {
    savingFilters.value = false
  }
}

// 自動更新 spec 6.2：選了就存，用格線上目前的版面，filters 是存著的預設；成功後交給 App 換設定，
// 走「只少了卡片」的捷徑不重抓，計時由 watch(refreshMs) 從現在重新開始
async function saveRefresh(minutes) {
  error.value = ''
  savingRefresh.value = true
  try {
    const reply = await queueUpdate({ ...props.config, layout: currentLayout(), refreshMinutes: minutes })
    ownUpdatePending = true
    emit('updated', reply)
  } catch (e) {
    error.value = e.message
  } finally {
    savingRefresh.value = false
  }
}

function openKpi(spec) {
  kpiSpec.value = spec
  kpiOpen.value = true
}

// KPI spec 2.3：加新卡走「加到既有儀表板」（一批一張，後端接在最下面）；改設定用格線上目前的版面換掉那張再存，
// filters 是存著的預設。成功後交給 App 換設定，臨時的篩選不變；失敗丟回表單顯示
async function saveKpi(card) {
  const editing = kpiSpec.value
  const reply = editing
    ? await queueUpdate({
      ...props.config,
      layout: currentLayout().map((s) => (s.chartId === card.chartId ? { ...card, gridPos: s.gridPos } : s)),
    })
    : await queueWrite(() => api.appendCharts(props.config.id, { layout: [card] }))
  // 改過的卡片要重抓資料，不能走「只少了卡片」的捷徑
  if (editing) delete dataById.value[card.chartId]
  ownUpdatePending = true
  emit('updated', reply)
}

// 匯出 spec 5.2：帶著目前的篩選（包括臨時改的）下載這張卡；檔名用後端給的，讀不到就用畫面上的標題。
// 失敗時訊息放在最上面那條錯誤提示；401 由 api.js 跳出重新登入
async function download(chartId, format) {
  error.value = ''
  try {
    const { blob, filename } = await api.exportChart(props.config.id, chartId, format, filters.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename ?? `${dataById.value[chartId]?.title ?? specById.value[chartId].title}.${format}`
    a.click()
    // 等瀏覽器開始下載再放掉；Safari、舊版 Firefox 立刻放掉會下載失敗，所以等 1 秒
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    error.value = `下載失敗：${e.message}`
  }
}
</script>

<template>
  <v-alert v-if="loadError || error" type="error" class="mb-4">{{ loadError || error }}</v-alert>
  <!-- 多來源 spec 3.5：已存的儀表板連最後一張圖也拿掉了 -->
  <v-alert v-if="!preview && !config.layout.length" type="info" class="mb-4">
    {{ readonly ? '這份儀表板沒有圖表。' : '這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。' }}
  </v-alert>
  <v-alert v-else-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <!-- spec 3.3：已存的儀表板顯示這份設定是依據哪些需求做的，每次分析一行 -->
  <div v-for="(line, n) in basisLines" :key="n" class="dash-basis text-caption text-medium-emphasis mb-2">{{ line }}</div>
  <!-- 篩選器 spec 6.1、6.2：已存的儀表板有卡片時才有篩選列，在依據下面、格線上面；檢視者沒有「存成預設」 -->
  <DashboardFilters
    v-if="!preview && config.layout.length"
    :model-value="filters"
    :saved="config.filters ?? null"
    :dashboard-id="config.id"
    :owner="!readonly"
    :saving="savingFilters || savingRefresh"
    @update:model-value="setFilters"
    @save="saveFilters"
    @add-kpi="openKpi(null)"
  >
    <!-- 自動更新 spec 6.1：更新失敗只讀給螢幕閱讀器一次，每次更新的時間不讀 -->
    <span class="dash-updated text-caption" :class="refreshFailed ? 'text-warning' : 'text-medium-emphasis'">{{ updatedText }}</span>
    <span class="d-sr-only" role="status">{{ refreshFailed ? updatedText : '' }}</span>
    <v-select
      v-if="!readonly"
      :model-value="config.refreshMinutes ?? null"
      :items="REFRESH_ITEMS"
      label="自動更新"
      hide-details
      class="dash-refresh"
      :disabled="savingRefresh || savingFilters || removing"
      @update:model-value="saveRefresh"
    />
  </DashboardFilters>
  <!-- 本期重點 spec 5.2：篩選列下面、格線上面，不放進格線（卡片可以拖拉）；結果回來才出現，不讀給螢幕閱讀器聽 -->
  <section v-if="periodDigest" class="dash-digest cc-panel tc-fade-in">
    <h2 class="dash-digest__title">本期重點</h2>
    <p v-if="!periodDigest.entries.length" class="dash-digest__empty">
      本期沒有需要注意的變化（檢查了 {{ periodDigest.checked }} 張卡）
    </p>
    <button
      v-for="e in periodDigest.entries"
      :key="e.chartId"
      type="button"
      class="dash-digest__item"
      @click="scrollToCard(e.chartId)"
    >
      <span class="tc-badge" :class="SEVERITY_CLASS[e.severity]">{{ e.severity }}</span>
      <span class="dash-digest__name text-truncate">{{ e.title }}</span>
      <span class="dash-digest__change">{{ e.text }}</span>
    </button>
  </section>
  <div ref="gridEl" class="dash-grid" :class="{ 'is-busy': busy }" :aria-busy="busy || undefined">
    <GridLayout
      v-if="!loadError"
      v-model:layout="layout"
      :class="{ 'is-settled': settled }"
      :col-num="12"
      :row-height="80"
      :margin="[14, 14]"
      :is-draggable="!preview && !readonly"
      :is-resizable="!preview && !readonly"
      @layout-ready="onLayoutReady"
    >
      <GridItem
        v-for="item in layout"
        :key="item.i"
        :i="item.i"
        :x="item.x"
        :y="item.y"
        :w="item.w"
        :h="item.h"
        drag-allow-from=".card-drag"
        @moved="saveLayout"
        @resized="saveLayout"
      >
        <!-- spec 10.4：動的是 GridItem 裡面的卡片；GridItem 自己的 transform 是格子位置 -->
        <ChartCard
          :spec="specById[item.i]"
          :data-chart-id="item.i"
          tabindex="-1"
          :data="dataById[item.i]"
          :highlight="highlightById[item.i]"
          :source="multiSource ? (dataById[item.i]?.source ?? null) : null"
          :removable="!readonly && (!preview || specById[item.i].type !== 'table')"
          :configurable="!preview && !readonly && specById[item.i].type === 'kpi'"
          :download="preview || guest ? null : (format) => download(item.i, format)"
          :readonly="readonly"
          :remove-disabled="busy || removing || savingFilters || savingRefresh"
          :filtered="filterCount(filters) > (dataById[item.i]?.filterSkipped?.length ?? 0)"
          :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
          :style="entering.has(item.i) ? { '--tc-i': screenOrder[item.i] } : undefined"
          @remove="preview ? emit('remove', item.i) : askRemove(item.i)"
          @configure="openKpi(specById[item.i])"
        />
      </GridItem>
    </GridLayout>
  </div>
  <!-- KPI spec 2.3、2.5：只有擁有者看已存的儀表板時能加、能改 KPI 卡；來源選單照擁有者標「（來自 帳號）」 -->
  <KpiForm v-if="!preview && !readonly" v-model="kpiOpen" :spec="kpiSpec" :username="config.createdBy" :save="saveKpi" />
  <!-- 多來源 spec 3.4：已存儀表板移除單張圖前先確認，樣式跟 App 的「刪除儀表板」一樣 -->
  <v-dialog :model-value="!!confirmRemove" max-width="420" @update:model-value="(v) => !v && (confirmRemove = null)">
    <v-card v-if="confirmRemove">
      <v-card-title class="pt-5 px-6">移除圖表</v-card-title>
      <v-card-text class="px-6">確定從「{{ config.name }}」移除「{{ confirmRemove.title }}」？移除後無法復原，要加回來得重新分析。</v-card-text>
      <v-card-actions class="px-6 pb-5">
        <v-spacer />
        <!-- v-card-actions 預設把按鈕變成 text，這裡明確用 flat，移除才看得出是按鈕 -->
        <v-btn variant="flat" @click="confirmRemove = null">取消</v-btn>
        <v-btn variant="flat" color="error" @click="removeCard">移除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
/* 自動更新 spec 6.1：選單寬約 150px */
.dash-refresh { flex: 0 0 150px; }
/* 本期重點 spec 5.2：跟卡片同材質（cc-panel）；左右對齊格線裡的卡片（GridLayout 的 margin 是 14px），
   跟卡片之間的 14px 由格線自己留 */
.dash-digest { margin: 0 14px; padding: 12px 12px 4px; }
.dash-digest__title { margin: 0 8px 4px; font-size: 14px; font-weight: 600; }
.dash-digest__empty { margin: 0 8px 8px; font-size: 13px; color: var(--tc-muted); }
/* 一筆一列，hover 跟清單項目一樣；窄螢幕放不下時，變化換到下一行 */
.dash-digest__item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 12px;
  width: 100%;
  padding: 8px;
  text-align: left;
}
.dash-digest__item + .dash-digest__item { border-top: 1px solid var(--tc-border-subtle); }
.dash-digest__item:hover { background: var(--tc-hover); }
.dash-digest__item:focus-visible { outline: 2px solid var(--tc-ring-color); outline-offset: -2px; }
.dash-digest__name { min-width: 0; font-size: 14px; font-weight: 600; }
/* 變化不分好壞，箭頭跟數字一樣用中性色 */
.dash-digest__change { font-size: 13px; color: var(--tc-muted); font-variant-numeric: tabular-nums; }
/* 按本期重點捲過來的卡片拿到焦點；只有鍵盤操作時才畫外框，樣式跟按鈕一樣 */
.dash-grid :deep([data-chart-id]:focus-visible) { outline: 2px solid var(--tc-ring-color); outline-offset: 2px; }
</style>
