<script setup>
import { computed } from 'vue'
import { useTheme } from 'vuetify'
import './charts/echarts.js'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'
// 深淺兩套圖表主題，跟著 Vuetify 目前的主題換。色盤是 dataviz 預設色盤的深色欄／淺色欄，各自對卡片底色驗證過；
// 放在模組裡，模組才不必依賴外面的檔案
import commandDark from './charts/themes/command-dark.json'
import commandLight from './charts/themes/command-light.json'
import { prefersReducedMotion } from './motion.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
  // 「✕」：哪些卡片有由 DashboardView 決定（預覽的明細表沒有）；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
  // 多來源的儀表板才傳：這張圖的來源名稱
  source: { type: String, default: null },
  // 唯讀分享 spec 7.8：檢視者看的卡片，沒有移動游標，錯誤訊息不叫他按 ✕、改成請聯絡擁有者
  readonly: { type: Boolean, default: false },
  // 篩選器 spec 6.4：有篩選對這張圖生效；沒有資料時用來分「篩選後沒有資料」和「沒有資料」
  filtered: { type: Boolean, default: false },
})
const emit = defineEmits(['remove'])

// spec 3.5：正常波動用中性灰、不加修飾子；好消息不染綠
const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }

// spec 4.3：一定要寫在 series 層。ECharts 6 的圓餅、折線在 series 預設裡有自己的時長和 easing，只寫在最外層蓋不掉
const SERIES_ANIMATION = { animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' }
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
// B3 spec 6.3：軸已經有自己的格式（比率的百分比）就不套數字縮寫
const compactValueAxis = (axis) =>
  axis?.type === 'value' && !axis.axisLabel?.formatter
    ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } }
    : axis

// 包在 toOption 結果的外層，charts/*.js 不動，快照不變；每次算 option 都重新問一次減少動態效果
function tunedOption(base) {
  const reduced = prefersReducedMotion()
  return {
    ...base,
    ...(reduced && { animation: false }),
    stateAnimation: { duration: reduced ? 0 : 120, easing: 'cubicOut' },
    tooltip: { ...base.tooltip, transitionDuration: 0 },
    // B3：dataset 的第一列沒有數字（比率第一期是 null）時，ECharts 會把它當成表頭丟掉；rows 永遠沒有表頭
    ...(base.dataset && { dataset: { ...base.dataset, sourceHeader: false } }),
    ...(base.xAxis && { xAxis: compactValueAxis(base.xAxis) }),
    // B2 spec 6.2：兩個數值差距大時 yAxis 是兩條軸的陣列
    ...(base.yAxis && { yAxis: Array.isArray(base.yAxis) ? base.yAxis.map(compactValueAxis) : compactValueAxis(base.yAxis) }),
    series: base.series.map((s) => ({ ...s, ...SERIES_ANIMATION })),
  }
}

const vuetifyTheme = useTheme()
// 換主題時 vue-echarts 會用新主題重畫圖表
const chartTheme = computed(() => (vuetifyTheme.current.value.dark ? commandDark : commandLight))

const ready = computed(() => props.data && !props.data.error)
const unsupportedType = computed(() => ready.value && !Object.hasOwn(toOption, props.spec.type))
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' && Object.hasOwn(toOption, props.spec.type)
    ? tunedOption(toOption[props.spec.type](props.data))
    : null,
)
// 比率的期間對齊：後端只在有排除期間時帶 ratioPeriod，from 比 to 晚是沒有重疊（月份是 2026-07，照字串比）
const ratioPeriodNote = computed(() => {
  const p = ready.value && props.data.ratioPeriod
  if (!p) return null
  if (p.from > p.to) return '分子、分母的資料期間沒有重疊'
  return `只算 ${p.from === p.to ? p.from : `${p.from}～${p.to}`}（分子、分母都有資料的月份）`
})
// 篩選器 spec 6.4：一列資料都沒有時不畫空的圖
const empty = computed(() => ready.value && props.data.rows?.length === 0)
const filterNote = computed(() =>
  ready.value && props.data.filterSkipped?.length
    ? `沒套用篩選：${props.data.filterSkipped.join('、')}（這張圖的來源沒有這些欄位）`
    : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="tc-card-title d-flex align-center" :class="{ 'card-drag': !readonly }">
      <span class="text-truncate">{{ data?.title ?? spec.title }}</span>
      <!-- 多來源 spec 3.2：名稱太長就截斷，滑鼠移上去看得到全名 -->
      <span v-if="source" class="tc-source text-truncate" :title="source">{{ source }}</span>
      <v-spacer />
      <span v-if="spec.options?.focus" class="tc-tag mr-1">符合需求</span>
      <span v-if="highlight" class="tc-badge tc-fade-in" :class="SEVERITY_CLASS[highlight.severity]">
        {{ highlight.severity }}
      </span>
      <!-- grid-layout-plus 的 dragIgnoreFrom 預設是 'a, button'，按「✕」不會被當成拖拉 -->
      <v-btn
        v-if="removable"
        icon="mdi-close"
        size="small"
        density="default"
        variant="text"
        class="tc-remove ml-1"
        aria-label="移除這張圖"
        :disabled="removeDisabled"
        @click="emit('remove')"
      />
    </v-card-title>
    <v-card-text class="flex-grow-1 card-body" :aria-busy="!data || undefined">
      <div v-if="!data" class="tc-skeleton card-skeleton" aria-hidden="true" />
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact" class="tc-fade-in">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-alert v-else-if="data.error === 'NOT_A_DATE'" type="warning" density="compact" class="tc-fade-in">
        欄位「{{ data.missingField }}」不是日期，不能按日、月或季分組
      </v-alert>
      <v-alert v-else-if="data.error === 'SOURCE_FAILED'" type="warning" density="compact" class="tc-fade-in">
        <template v-if="readonly">{{ data.source ? `資料來源「${data.source}」載入失敗，請聯絡儀表板擁有者` : '資料來源載入失敗，請聯絡儀表板擁有者' }}</template>
        <template v-else>{{ data.source ? `資料來源「${data.source}」載入失敗，請稍後重新整理` : '資料來源載入失敗，請稍後重新整理' }}</template>
      </v-alert>
      <v-alert v-else-if="data.error === 'SOURCE_DELETED'" type="warning" density="compact" class="tc-fade-in">
        {{ readonly ? '資料來源已刪除' : '資料來源已刪除，可以按右上角 ✕ 移除這張圖' }}
      </v-alert>
      <v-alert v-else-if="unsupportedType" type="warning" density="compact" class="tc-fade-in">
        不支援的圖表類型：{{ spec.type }}
      </v-alert>
      <div v-else-if="empty" class="tc-empty text-body-2 text-medium-emphasis tc-fade-in">
        {{ filtered ? '篩選後沒有資料' : '沒有資料' }}
      </div>
      <div v-else-if="table" class="table-wrap">
        <div v-if="data.truncated" class="text-caption text-medium-emphasis">僅顯示前 500 筆</div>
        <v-table density="compact" fixed-header height="100%" class="table-scroll tc-fade-in">
          <thead>
            <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in table.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j" :class="{ 'tc-num': typeof cell === 'number' }">{{ cell }}</td>
            </tr>
          </tbody>
        </v-table>
      </div>
      <div v-else class="chart-wrap">
        <VChart :option="option" :theme="chartTheme" autoresize class="chart-canvas" />
        <div v-if="ratioPeriodNote" class="tc-ratio-period text-caption text-medium-emphasis">{{ ratioPeriodNote }}</div>
      </div>
    </v-card-text>
    <div v-if="filterNote" class="tc-filter-note text-caption text-medium-emphasis">{{ filterNote }}</div>
  </v-card>
</template>

<style scoped>
/* 標題列：字級、字重和下框線；拖拉把手在這一列 */
.tc-card-title {
  gap: 6px;
  min-height: 48px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.01em;
  border-bottom: 1px solid var(--tc-border-subtle, rgba(var(--v-border-color), var(--v-border-opacity)));
}
.card-drag { cursor: move; }
.card-body { min-height: 0; padding: 8px 8px 0; }
/* 圖表撐滿內容區；比率有說明時，說明那一行排在圖下方 */
.chart-wrap { height: 100%; display: flex; flex-direction: column; }
.chart-canvas { flex: 1 1 0; min-height: 0; }
.tc-ratio-period { padding: 2px 4px 6px; }
/* 篩選器 spec 6.4：沒套用篩選的說明跟比率期間的說明一樣，排在卡片最下面（內容區左右各 8px，再縮 4px） */
.tc-filter-note { padding: 2px 12px 6px; }
.tc-empty { height: 100%; display: flex; align-items: center; justify-content: center; }
/* 明細表也撐滿內容區：v-table 有確定的高度，height="100%" 的捲動區才不會跟著列數長高、被卡片切掉 */
.table-wrap { height: 100%; display: flex; flex-direction: column; }
.table-scroll { flex: 1 1 0; min-height: 0; }
/* 多來源 spec 3.2：標題右邊的來源名稱，次要文字色的小字 */
.tc-source {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 40%;
  font-size: 12px;
  font-weight: 400;
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
}
/* spec 3.6：骨架撐滿內容區；淡入時機在 motion.css */
.card-skeleton { height: 100%; min-height: 120px; border-radius: var(--tc-radius, 4px); }
/* spec 3.8：數字寬度一致，上下好比對 */
.tc-num { font-variant-numeric: tabular-nums; text-align: right; }
/* spec 10.5：靜止時就看得到，hover 變主文字色，按下時往下 1px；size small + density default 是 40×40
   （28px 的 --v-btn-height 加 12px 的圖示留白，VBtn.css），超過 24×24；全域 density: 'compact'
   （main.js）會把它縮到 20×20，這裡才特別蓋回 default */
.tc-remove {
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
}
.tc-remove:hover { color: var(--tc-text, rgba(var(--v-theme-on-surface), var(--v-high-emphasis-opacity))); }
.tc-remove:active { transform: translateY(1px); }
</style>
