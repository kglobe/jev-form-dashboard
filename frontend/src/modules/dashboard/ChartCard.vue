<script setup>
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'
// 從 .claude/skills/modern-erp-ui/assets/echarts-theme-default.json 複製；放在模組裡，模組才不必依賴外面的檔案
import tactileLight from './charts/themes/tactile-light.json'
import { prefersReducedMotion } from './motion.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
  // 預覽裡才有「✕」；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
})
const emit = defineEmits(['remove'])

// spec 3.5：正常波動用中性灰、不加修飾子；Tactile 的規則是好消息不染綠
const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }

// spec 3.7：主題只有 6 色，堆疊長條最多 10 個系列會撞色，用花紋區分
const DECAL_TYPES = new Set(['bar', 'pie', 'stacked_bar'])
// spec 4.3：一定要寫在 series 層。ECharts 6 的圓餅、折線在 series 預設裡有自己的時長和 easing，只寫在最外層蓋不掉
const SERIES_ANIMATION = { animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' }
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
const compactValueAxis = (axis) =>
  axis?.type === 'value' ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } } : axis

// 包在 toOption 結果的外層，charts/*.js 不動，快照不變；每次算 option 都重新問一次減少動態效果
function tactileOption(base, type) {
  const reduced = prefersReducedMotion()
  return {
    ...base,
    ...(reduced && { animation: false }),
    stateAnimation: { duration: reduced ? 0 : 120, easing: 'cubicOut' },
    tooltip: { ...base.tooltip, transitionDuration: 0 },
    ...(DECAL_TYPES.has(type) && { aria: { enabled: true, decal: { show: true } } }),
    ...(base.xAxis && { xAxis: compactValueAxis(base.xAxis) }),
    ...(base.yAxis && { yAxis: compactValueAxis(base.yAxis) }),
    series: base.series.map((s) => ({ ...s, ...SERIES_ANIMATION })),
  }
}

const ready = computed(() => props.data && !props.data.error)
const unsupportedType = computed(() => ready.value && !Object.hasOwn(toOption, props.spec.type))
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' && Object.hasOwn(toOption, props.spec.type)
    ? tactileOption(toOption[props.spec.type](props.data, props.spec.mapping), props.spec.type)
    : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag tc-card-title d-flex align-center">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <span v-if="spec.options?.focus" class="tc-tag mr-1">符合需求</span>
      <span v-if="highlight" class="tc-badge tc-fade-in" :class="SEVERITY_CLASS[highlight.severity]">
        {{ highlight.severity }}
      </span>
      <!-- grid-layout-plus 的 dragIgnoreFrom 預設是 'a, button'，按「✕」不會被當成拖拉 -->
      <v-btn
        v-if="removable && spec.type !== 'table'"
        icon="mdi-close"
        size="x-small"
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
      <VChart v-else :option="option" :theme="tactileLight" autoresize />
    </v-card-text>
  </v-card>
</template>

<style scoped>
/* spec 3.3：標題列同 .tc-card__header 的字級、字重和下框線 */
.tc-card-title {
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid var(--tc-border-subtle, rgba(var(--v-border-color), var(--v-border-opacity)));
}
.card-drag { cursor: move; }
.card-body { min-height: 0; padding: 8px 8px 0; }
/* spec 3.6：tactile 的 .tc-skeleton 預設只有 12px 高，這裡撐滿內容區；淡入時機在 motion.css */
.card-skeleton { height: 100%; min-height: 120px; border-radius: var(--tc-radius, 4px); }
/* spec 3.8：數字寬度一致，上下好比對 */
.tc-num { font-variant-numeric: tabular-nums; text-align: right; }
/* spec 10.5：靜止時就看得到，hover 變主文字色，按下時往下 1px；x-small 的圖示按鈕是 32×32，超過 24×24 */
.tc-remove {
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
  transition: none;
}
.tc-remove:hover { color: var(--tc-text, rgba(var(--v-theme-on-surface), var(--v-high-emphasis-opacity))); }
.tc-remove:active { transform: translateY(1px); }
</style>
