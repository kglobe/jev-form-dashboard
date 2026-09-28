<script setup>
import { computed } from 'vue'
import { useTheme } from 'vuetify'
import 'echarts'
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
})
const emit = defineEmits(['remove'])

// spec 3.5：正常波動用中性灰、不加修飾子；好消息不染綠
const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }

// spec 4.3：一定要寫在 series 層。ECharts 6 的圓餅、折線在 series 預設裡有自己的時長和 easing，只寫在最外層蓋不掉
const SERIES_ANIMATION = { animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' }
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
const compactValueAxis = (axis) =>
  axis?.type === 'value' ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } } : axis

// 包在 toOption 結果的外層，charts/*.js 不動，快照不變；每次算 option 都重新問一次減少動態效果
function tunedOption(base) {
  const reduced = prefersReducedMotion()
  return {
    ...base,
    ...(reduced && { animation: false }),
    stateAnimation: { duration: reduced ? 0 : 120, easing: 'cubicOut' },
    tooltip: { ...base.tooltip, transitionDuration: 0 },
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
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag tc-card-title d-flex align-center">
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
      <v-alert v-else-if="data.error === 'SOURCE_FAILED'" type="warning" density="compact" class="tc-fade-in">
        {{ data.source ? `資料來源「${data.source}」載入失敗，請稍後重新整理` : '資料來源載入失敗，請稍後重新整理' }}
      </v-alert>
      <v-alert v-else-if="unsupportedType" type="warning" density="compact" class="tc-fade-in">
        不支援的圖表類型：{{ spec.type }}
      </v-alert>
      <template v-else-if="table">
        <div v-if="data.truncated" class="text-caption text-medium-emphasis">僅顯示前 500 筆</div>
        <v-table density="compact" fixed-header height="100%" class="tc-fade-in">
          <thead>
            <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in table.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j" :class="{ 'tc-num': typeof cell === 'number' }">{{ cell }}</td>
            </tr>
          </tbody>
        </v-table>
      </template>
      <VChart v-else :option="option" :theme="chartTheme" autoresize />
    </v-card-text>
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
