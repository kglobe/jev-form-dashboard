<script setup>
import { computed, ref, watch } from 'vue'
import { api } from './api.js'
import { sourceTitle } from './fields.js'

// KPI spec 2.4：「加 KPI 卡」和「KPI 卡設定」共用的對話框。spec 是要改的 KPI 卡，加新卡時是 null；
// save 收組好的卡片設定，丟出錯誤時訊息留在對話框裡，成功才關掉
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  spec: { type: Object, default: null },
  // 儀表板的擁有者：來源選單裡別人分享的來源後面加「（來自 帳號）」
  username: { type: String, default: null },
  save: { type: Function, required: true },
})
const emit = defineEmits(['update:modelValue'])

const AGGREGATES = [
  { title: '加總', value: 'sum' },
  { title: '平均', value: 'avg' },
  { title: '筆數', value: 'count' },
]
const GRAINS = [
  { title: '月', value: 'month' },
  { title: '季', value: 'quarter' },
]
const OPS = [
  { title: '不設', value: null },
  { title: '低於', value: 'below' },
  { title: '高於', value: 'above' },
]

const sources = ref(null)
const error = ref('')
const saving = ref(false)
const datasourceId = ref(null)
const value = ref(null)
const aggregate = ref('sum')
const x = ref(null)
const grain = ref('month')
const title = ref('')
const op = ref(null)
// 門檻輸入框的文字；比率時是百分比
const limit = ref('')

const label = (f) => f.label ?? f.name
const sourceItems = computed(() => (sources.value ?? []).map((d) => ({ title: sourceTitle(d, props.username), value: d.id })))
const source = computed(() => sources.value?.find((d) => d.id === datasourceId.value) ?? null)
const valueItems = computed(() => [
  ...(source.value?.schema ?? []).filter((f) => f.type === 'number').map((f) => ({ title: label(f), value: f.name })),
  ...(source.value?.ratios ?? []).map((r) => ({ title: r.name, value: r.name })),
])
const dateItems = computed(() =>
  (source.value?.schema ?? []).filter((f) => f.type === 'date').map((f) => ({ title: label(f), value: f.name })),
)
// 比率固定是每組先加總再相除，不問加總方式
const ratio = computed(() => !!source.value?.ratios?.some((r) => r.name === value.value))
const limitValue = computed(() => (String(limit.value).trim() === '' ? NaN : Number(limit.value)))
const ready = computed(() => !!(source.value && value.value && x.value && (!op.value || Number.isFinite(limitValue.value))))

// 每次打開都重抓來源；改設定時帶入這張卡現在的設定，標題就是數值的名稱時留空白
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    const s = props.spec
    const t = s?.options?.threshold
    sources.value = null
    error.value = ''
    datasourceId.value = s?.datasourceId ?? null
    value.value = s?.mapping.value ?? null
    aggregate.value = s?.mapping.aggregate ?? 'sum'
    x.value = s?.mapping.x ?? null
    grain.value = s?.mapping.timeGrain ?? 'month'
    title.value = s && s.title !== s.mapping.value ? s.title : ''
    op.value = t?.op ?? null
    limit.value = ''
    try {
      sources.value = await api.listDatasources()
    } catch (e) {
      error.value = e.message
      return
    }
    // 比率的門檻存成小數（0.05），畫面上是百分比（5）；來源回來才知道是不是比率
    if (t) limit.value = String(ratio.value ? Number((t.value * 100).toPrecision(12)) : t.value)
  },
  { immediate: true },
)

// 換來源時數值清掉，日期欄位預設第一個
function pickSource(id) {
  datasourceId.value = id
  value.value = null
  x.value = dateItems.value[0]?.value ?? null
}

// KPI spec 3 的卡片設定。加新卡時 chartId、位置先隨便給，後端加到儀表板最下面時會重新編號、往下移
function card() {
  const s = props.spec
  const options = { ...s?.options }
  delete options.threshold
  if (op.value) options.threshold = { op: op.value, value: ratio.value ? limitValue.value / 100 : limitValue.value }
  return {
    chartId: s?.chartId ?? 'c1',
    type: 'kpi',
    title: title.value.trim() || value.value,
    gridPos: s?.gridPos ?? { x: 0, y: 0, w: 4, h: 2 },
    mapping: { x: x.value, value: value.value, aggregate: ratio.value ? 'sum' : aggregate.value, timeGrain: grain.value },
    options,
    datasourceId: datasourceId.value,
  }
}

async function submit() {
  saving.value = true
  error.value = ''
  try {
    await props.save(card())
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="480" transition="tc-sheet" @update:model-value="(v) => emit('update:modelValue', v)">
    <v-card>
      <v-card-title class="pt-5 px-6">{{ spec ? 'KPI 卡設定' : '加 KPI 卡' }}</v-card-title>
      <v-card-text class="px-6">
        <v-progress-linear v-if="!sources && !error" indeterminate class="mb-3" />
        <v-select :model-value="datasourceId" :items="sourceItems" label="來源" :disabled="!!spec" @update:model-value="pickSource" />
        <template v-if="source">
          <v-select v-model="value" :items="valueItems" label="數值" />
          <v-select v-if="!ratio" v-model="aggregate" :items="AGGREGATES" label="加總方式" />
          <v-select v-if="dateItems.length" v-model="x" :items="dateItems" label="日期欄位" />
          <p v-else class="text-body-2 text-medium-emphasis mb-4">這個來源沒有日期欄位，不能做 KPI 卡</p>
          <v-select v-model="grain" :items="GRAINS" label="期別" />
          <v-text-field v-model="title" label="標題" placeholder="空白時用數值的名稱" />
          <div class="d-flex ga-2">
            <v-select v-model="op" :items="OPS" label="門檻" class="kpi-form__op" />
            <v-text-field v-model="limit" type="number" label="門檻值" :suffix="ratio ? '%' : undefined" :disabled="!op" />
          </div>
        </template>
        <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
      </v-card-text>
      <v-card-actions class="px-6 pb-5">
        <v-spacer />
        <v-btn variant="flat" @click="emit('update:modelValue', false)">取消</v-btn>
        <v-btn variant="flat" color="primary" :disabled="!ready" :loading="saving" @click="submit">{{ spec ? '儲存' : '加入' }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
/* 門檻的條件選單窄一點，數字輸入框拿剩下的寬度 */
.kpi-form__op { flex: 0 0 120px; }
</style>
