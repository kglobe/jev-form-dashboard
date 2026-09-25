<script setup>
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
  // 預覽裡才有「✕」；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
})
const emit = defineEmits(['remove'])

const SEVERITY_COLOR = { 正常波動: 'success', 值得注意: 'info', 需要關注: 'warning', 嚴重警示: 'error' }

const ready = computed(() => props.data && !props.data.error)
const unsupportedType = computed(() => ready.value && !Object.hasOwn(toOption, props.spec.type))
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' && Object.hasOwn(toOption, props.spec.type)
    ? toOption[props.spec.type](props.data, props.spec.mapping)
    : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag d-flex align-center text-subtitle-1">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <v-chip v-if="spec.options?.focus" size="small" color="primary" class="mr-1">符合需求</v-chip>
      <v-chip v-if="highlight" size="small" :color="SEVERITY_COLOR[highlight.severity]">
        {{ highlight.severity }}
      </v-chip>
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
    <v-card-text class="flex-grow-1 card-body">
      <div v-if="!data" class="text-medium-emphasis">載入中…</div>
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-alert v-else-if="unsupportedType" type="warning" density="compact">
        不支援的圖表類型：{{ spec.type }}
      </v-alert>
      <template v-else-if="table">
        <div v-if="data.truncated" class="text-caption text-medium-emphasis">僅顯示前 500 筆</div>
        <v-table density="compact" fixed-header height="100%">
          <thead>
            <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in table.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
            </tr>
          </tbody>
        </v-table>
      </template>
      <VChart v-else :option="option" autoresize />
    </v-card-text>
  </v-card>
</template>

<style scoped>
.card-drag { cursor: move; }
.card-body { min-height: 0; }
/* spec 10.5：靜止時就看得到，hover 變主文字色，按下時往下 1px；x-small 的圖示按鈕是 32×32，超過 24×24 */
.tc-remove {
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
  transition: none;
}
.tc-remove:hover { color: var(--tc-text, rgba(var(--v-theme-on-surface), var(--v-high-emphasis-opacity))); }
.tc-remove:active { transform: translateY(1px); }
</style>
