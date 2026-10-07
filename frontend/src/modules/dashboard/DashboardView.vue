<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import DashboardFilters from './DashboardFilters.vue'
import { api } from './api.js'
import { filterCount, filterKey } from './filters.js'
import { prefersReducedMotion } from './motion.js'
import './motion.css'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
  // 預覽等 JEV 回應時：圖表區變淡、標 aria-busy、「✕」停用
  busy: { type: Boolean, default: false },
  // 唯讀分享 spec 7.8：檢視者只能看
  readonly: { type: Boolean, default: false },
})
// remove：預覽的「✕」交給製圖頁處理；updated：已存儀表板移除一張圖後，後端回傳的新設定
const emit = defineEmits(['remove', 'updated'])

// ponytail: 跟 motion.css 的 --tc-dur-1 同值，改一邊要一起改
const EXIT_MS = 120

const layout = ref([])
// 退場那 120ms 裡，消失的卡片還要用舊的 spec 畫，所以不能直接從 props.config 算
const specById = ref({})
const dataById = ref({})
const highlightById = ref({})
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
const screenOrder = computed(() =>
  Object.fromEntries([...layout.value].sort((a, b) => a.y - b.y || a.x - b.x).map((l, n) => [l.i, n])),
)

let loadSeq = 0
// removeCard 剛 emit('updated', ...) 之後，config 一定會馬上換成那份新設定；
// 這個旗標讓下一次 load() 知道「這次的變化是自己的 PUT 造成的」，才能用「只少了卡片」的捷徑。
// App 重新打開同一份儀表板不會經過這裡，旗標是 false，就一定重抓
let ownUpdatePending = false
// 上一次的 highlights 還在等 JEV 回應
let highlightsInFlight = false
// dataById 是用哪一組篩選抓的；跟目前的篩選不一樣（新篩選的資料還沒回來）時，不能走「只少了卡片」的捷徑
let fetchedKey = filterKey(null)
// 同一份儀表板的 PUT 依操作順序送出，舊的版面存檔才不會在移除圖表之後覆蓋新版面
let writeTail = Promise.resolve()
function queueUpdate(config) {
  const write = writeTail.catch(() => {}).then(() => api.updateDashboard(config.id, config))
  writeTail = write
  return write
}
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
    highlightById.value = Object.fromEntries(Object.entries(highlightById.value).filter(([id]) => ids.has(id)))
    // 上一次的 highlights 還沒回來：那個回應會因為 loadSeq 換了被丟掉，這裡要重問一次才看得到標註
    if (props.preview || !highlightsInFlight) return
    await fetchHighlights(seq)
    return
  }
  await fetchData(seq)
}

// 篩選器 spec 6.1：篩選一變就重抓；跟 load() 一樣，卡片先留著舊資料，新資料回來再換（多來源 spec 10.3）
function setFilters(next) {
  filters.value = next
  loadError.value = ''
  fetchData(++loadSeq)
}

async function fetchData(seq) {
  highlightById.value = {}
  const f = filters.value
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id, f)
    if (seq !== loadSeq) return
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
    fetchedKey = filterKey(f)
  } catch (e) {
    if (seq !== loadSeq) return
    loadError.value = e.message
    settled.value = false // GridLayout 會被 v-if 拿掉，重新掛載時要再等一次 layout-ready
    return
  }
  if (props.preview) return
  await fetchHighlights(seq)
}

// 異常檢查失敗就不標註，不顯示錯誤；篩選器 spec 2.4：照篩選後的資料判斷
async function fetchHighlights(seq) {
  highlightsInFlight = true
  const found = await api.highlights(props.config.id, filters.value).catch(() => [])
  highlightsInFlight = false
  if (seq !== loadSeq) return
  highlightById.value = Object.fromEntries(found.map((h) => [h.chartId, h]))
}

watch(() => props.config, load, { immediate: true })

// 卸載後，還在等退場或資料的 load() 直接作廢，不再打 API
onBeforeUnmount(() => {
  loadSeq++
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
  if (props.preview || props.readonly || removing.value || savingFilters.value) return
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
    :saving="savingFilters"
    @update:model-value="setFilters"
    @save="saveFilters"
  />
  <div class="dash-grid" :class="{ 'is-busy': busy }" :aria-busy="busy || undefined">
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
          :data="dataById[item.i]"
          :highlight="highlightById[item.i]"
          :source="multiSource ? (dataById[item.i]?.source ?? null) : null"
          :removable="!readonly && (!preview || specById[item.i].type !== 'table')"
          :readonly="readonly"
          :remove-disabled="busy || removing || savingFilters"
          :filtered="filterCount(filters) > (dataById[item.i]?.filterSkipped?.length ?? 0)"
          :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
          :style="entering.has(item.i) ? { '--tc-i': screenOrder[item.i] } : undefined"
          @remove="preview ? emit('remove', item.i) : askRemove(item.i)"
        />
      </GridItem>
    </GridLayout>
  </div>
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
