<script setup>
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
})

const SEVERITY_COLOR = { 正常波動: 'success', 值得注意: 'info', 需要關注: 'warning', 嚴重警示: 'error' }

const ready = computed(() => props.data && !props.data.error)
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' ? toOption[props.spec.type](props.data, props.spec.mapping) : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag d-flex align-center text-subtitle-1">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <v-chip v-if="highlight" size="small" :color="SEVERITY_COLOR[highlight.severity]">
        {{ highlight.severity }}
      </v-chip>
    </v-card-title>
    <v-card-text class="flex-grow-1 card-body">
      <div v-if="!data" class="text-medium-emphasis">載入中…</div>
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-table v-else-if="table" density="compact" fixed-header height="100%">
        <thead>
          <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in table.rows" :key="i">
            <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
          </tr>
        </tbody>
      </v-table>
      <VChart v-else :option="option" autoresize />
    </v-card-text>
  </v-card>
</template>

<style scoped>
.card-drag { cursor: move; }
.card-body { min-height: 0; }
</style>
