<script setup>
import { computed, ref } from 'vue'
import { api } from './api.js'
import { PERIODS, TOO_LONG, filterCount, filterKey, tooLong } from './filters.js'

// 篩選器 spec 6.1：已存儀表板的篩選列。modelValue 是目前的篩選（沒有篩選是 null），saved 是存著的預設
const props = defineProps({
  modelValue: { type: Object, default: null },
  saved: { type: Object, default: null },
  dashboardId: { type: String, required: true },
  // spec 6.2：只有擁有者有「存成預設」；saving 是存檔的 PUT 還沒回來
  owner: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'save'])

const MAX_FIELDS = 3
const fields = computed(() => props.modelValue?.fields ?? [])
const unchanged = computed(() => filterKey(props.modelValue) === filterKey(props.saved))

// 「地區：北區、南區」，超過 3 個值寫「地區：北區、南區等 5 個」
const chipText = (f) =>
  `${f.field}：${f.values.length > 3 ? `${f.values.slice(0, 2).join('、')}等 ${f.values.length} 個` : f.values.join('、')}`

function change(period, list) {
  emit('update:modelValue', period || list.length ? { period, fields: list } : null)
}

// spec 6.1：選單第一次打開才抓 filter-options，結果留到換儀表板（App 換儀表板會重建整個 DashboardView）為止；失敗下次打開再抓
const options = ref(null)
const loading = ref(false)
const loadError = ref('')
const menu = ref(false)
// 選單貼在按下去的那個按鈕或 chip 旁邊
const target = ref(null)
// 正在改的是哪個 chip 的欄位；「加篩選」是 null
const editing = ref(null)
const field = ref(null)
const values = ref([])
const search = ref('')

// 還沒加的欄位；改 chip 時它自己的欄位也列（存著的預設裡的欄位可能已經不在來源裡）
const fieldItems = computed(() => {
  const used = new Set(fields.value.map((f) => f.field).filter((name) => name !== editing.value))
  const names = (options.value ?? []).map((f) => f.field).filter((name) => !used.has(name))
  return editing.value && !names.includes(editing.value) ? [editing.value, ...names] : names
})
// 值直接列成勾選清單，不再疊一層下拉（下拉會蓋住「套用」）；已經勾了、但不在選項裡的值也列，才取消得掉
const valueItems = computed(() => {
  const items = options.value?.find((f) => f.field === field.value)?.values ?? []
  return [...items, ...values.value.filter((v) => !items.includes(v))]
})
const shownValues = computed(() => valueItems.value.filter((v) => v.includes(search.value)))

// 按「套用」會送出的篩選：改 chip 就換掉原來那一個、位置不變；加篩選接在最後面
const pending = computed(() => {
  const entry = { field: field.value, values: values.value }
  const list = editing.value ? fields.value.map((f) => (f.field === editing.value ? entry : f)) : [...fields.value, entry]
  return { period: props.modelValue?.period ?? null, fields: list }
})
const pendingTooLong = computed(() => values.value.length > 0 && tooLong(pending.value))

async function openMenu(event, name) {
  target.value = event.currentTarget
  editing.value = name
  field.value = name
  values.value = name ? [...fields.value.find((f) => f.field === name).values] : []
  search.value = ''
  menu.value = true
  if (options.value || loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    options.value = (await api.filterOptions(props.dashboardId)).fields
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

function pickField(name) {
  field.value = name
  values.value = []
  search.value = ''
}

function apply() {
  change(pending.value.period, pending.value.fields)
  menu.value = false
}
</script>

<template>
  <div class="dash-filters d-flex flex-wrap align-center ga-2 mb-3">
    <v-select
      :model-value="modelValue?.period ?? null"
      :items="PERIODS"
      label="期間"
      hide-details
      class="dash-filters__period"
      @update:model-value="(p) => change(p, fields)"
    />
    <v-chip
      v-for="f in fields"
      :key="f.field"
      closable
      :close-label="`移除「${f.field}」篩選`"
      @click="openMenu($event, f.field)"
      @click:close="change(modelValue?.period ?? null, fields.filter((x) => x.field !== f.field))"
    >
      {{ chipText(f) }}
    </v-chip>
    <v-btn v-if="fields.length < MAX_FIELDS" variant="text" prepend-icon="mdi-filter-plus-outline" @click="openMenu($event, null)">
      加篩選
    </v-btn>
    <v-btn v-if="filterCount(modelValue)" variant="text" @click="emit('update:modelValue', null)">清除篩選</v-btn>
    <v-btn
      v-if="owner"
      variant="tonal"
      prepend-icon="mdi-content-save-outline"
      :disabled="unchanged || saving || tooLong(modelValue)"
      @click="emit('save')"
    >
      存成預設
    </v-btn>
    <v-menu v-model="menu" :target="target" :close-on-content-click="false" location="bottom start">
      <v-card width="320">
        <v-progress-linear v-if="loading" indeterminate />
        <v-card-text>
          <v-alert v-if="loadError" type="error" density="compact">{{ loadError }}</v-alert>
          <p v-else-if="options && !fieldItems.length" class="text-body-2 text-medium-emphasis">這份儀表板沒有可以篩選的文字欄位</p>
          <template v-else-if="options">
            <v-select :model-value="field" :items="fieldItems" label="欄位" hide-details class="mb-2" @update:model-value="pickField" />
            <template v-if="field">
              <v-text-field v-model="search" label="找值" prepend-inner-icon="mdi-magnify" hide-details class="mb-1" />
              <!-- 清單自己捲動，「取消」「套用」固定在下面 -->
              <div class="dash-filters__values">
                <v-checkbox v-for="v in shownValues" :key="v" v-model="values" :value="v" :label="v" hide-details />
              </div>
            </template>
          </template>
          <p v-if="pendingTooLong" class="text-caption text-error mt-2">{{ TOO_LONG }}</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="menu = false">取消</v-btn>
          <v-btn variant="flat" color="primary" :disabled="!values.length || pendingTooLong" @click="apply">套用</v-btn>
        </v-card-actions>
      </v-card>
    </v-menu>
  </div>
</template>

<style scoped>
/* spec 6.1：期間選單寬約 140px */
.dash-filters__period { flex: 0 0 140px; }
.dash-filters__values { max-height: 240px; overflow-y: auto; }
</style>
