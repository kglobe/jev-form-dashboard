<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import { api } from './api.js'
import { prefersReducedMotion } from './motion.js'
import './motion.css'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
  // 預覽等 JEV 回應時：圖表區變淡、標 aria-busy、「✕」停用
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['remove'])

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

const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))

// spec 4.4：錯開的順序照畫面位置（先 y 再 x）。使用者拖過之後，layout 陣列的順序不一定等於畫面順序
const screenOrder = computed(() =>
  Object.fromEntries([...layout.value].sort((a, b) => a.y - b.y || a.x - b.x).map((l, n) => [l.i, n])),
)

let loadSeq = 0
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
  highlightById.value = {}
  error.value = ''
  loadError.value = ''
  // 預覽裡只是少了卡片時，剩下的卡片都有資料了，不必再打 API（spec 3.2：「✕」不呼叫 API）
  if (props.preview && next.every((s) => dataById.value[s.chartId])) return
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id)
    if (seq !== loadSeq) return
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
  } catch (e) {
    if (seq !== loadSeq) return
    loadError.value = e.message
    settled.value = false // GridLayout 會被 v-if 拿掉，重新掛載時要再等一次 layout-ready
    return
  }
  if (props.preview) return
  // 異常檢查失敗就不標註，不顯示錯誤
  const found = await api.highlights(props.config.id).catch(() => [])
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

async function saveLayout() {
  if (props.preview) return
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  const next = { ...props.config, layout: props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] })) }
  error.value = ''
  await api.updateDashboard(props.config.id, next).catch((e) => (error.value = e.message))
}
</script>

<template>
  <v-alert v-if="loadError || error" type="error" class="mb-4">{{ loadError || error }}</v-alert>
  <v-alert v-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <!-- spec 3.3：已存的儀表板顯示這份設定是依據哪些需求做的 -->
  <div v-if="!preview && config.intents?.length" class="text-caption text-medium-emphasis mb-2">
    依據：{{ config.intents.join(' → ') }}
  </div>
  <div class="dash-grid" :class="{ 'is-busy': busy }" :aria-busy="busy || undefined">
    <GridLayout
      v-if="!loadError"
      v-model:layout="layout"
      :class="{ 'is-settled': settled }"
      :col-num="12"
      :row-height="80"
      :margin="[14, 14]"
      :is-draggable="!preview"
      :is-resizable="!preview"
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
          :removable="preview"
          :remove-disabled="busy"
          :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
          :style="entering.has(item.i) ? { '--tc-i': screenOrder[item.i] } : undefined"
          @remove="emit('remove', item.i)"
        />
      </GridItem>
    </GridLayout>
  </div>
</template>
