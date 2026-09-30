<script setup>
import { computed, ref, watch } from 'vue'
import { api } from './api.js'
import { FIELD_TYPES } from './fields.js'

// 「新增來源」對話框的「合併」分頁（spec 8.1）：疊加或查表，預覽成功而且顯示的是目前的定義，才能建立
const props = defineProps({
  sources: { type: Array, required: true },
})
const emit = defineEmits(['created'])

const op = ref('union')
const name = ref('')
const nameEdited = ref(false)
// 疊加
const inputs = ref([])
const columns = ref([]) // [{ key, name, from: { [來源 id]: 欄位名稱 | null } }]
const sourceColumn = ref('來源')
// 查表
const main = ref(null)
const lookup = ref(null)
const keys = ref([{ main: null, lookup: null }])
const bring = ref([]) // 主檔每個欄位一列：{ from, name, on }

const preview = ref(null)
const error = ref('')
const busy = ref(false)
const suggesting = ref(false)
const suggestNote = ref('')

let nextKey = 0
const byId = computed(() => Object.fromEntries(props.sources.map((ds) => [ds.id, ds])))
const nameOf = (id) => byId.value[id]?.name ?? '（已不存在）'
const fieldsOf = (id) => byId.value[id]?.schema ?? []
const typeOf = (id, field) => fieldsOf(id).find((f) => f.name === field)?.type
const fieldItems = (id) => fieldsOf(id).map((f) => ({ title: f.name, value: f.name, prependIcon: FIELD_TYPES[f.type]?.icon }))

// 同名的欄位自動對上；only 決定哪些欄位參加（對照時數字欄位另外依來源拆開）
function matchByName(ids, only = () => true) {
  const has = (id, n) => fieldsOf(id).some((f) => f.name === n && only(f))
  const names = [...new Set(ids.flatMap((id) => fieldsOf(id).filter(only).map((f) => f.name)))]
  return names.map((n) => ({ key: nextKey++, name: n, from: Object.fromEntries(ids.map((id) => [id, has(id, n) ? n : null])) }))
}

// B2 spec 6.1：對照時，每個來源的每個數字欄位各一列，名稱是「來源名·欄位」，只對應那一個來源
function splitNumbers(ids) {
  return ids.flatMap((id) => fieldsOf(id).filter((f) => f.type === 'number').map((f) => ({
    key: nextKey++,
    name: `${nameOf(id)}·${f.name}`,
    from: Object.fromEntries(ids.map((other) => [other, other === id ? f.name : null])),
  })))
}

// ponytail: 選的來源或方式一變就重排對應表，手動改過的對應會不見；要保留再比對新舊來源。切到查表時不動
watch([inputs, op], ([ids, mode]) => {
  suggestNote.value = ''
  if (mode === 'union') columns.value = matchByName(ids)
  if (mode === 'compare') columns.value = [...matchByName(ids, (f) => f.type !== 'number'), ...splitNumbers(ids)]
})

function columnProblem(c) {
  const used = inputs.value.filter((id) => c.from[id]).map((id) => ({ id, type: typeOf(id, c.from[id]) }))
  if (new Set(used.map((u) => u.type)).size >= 2) {
    return '型別不一致：' + used.map((u) => `${nameOf(u.id)}是${FIELD_TYPES[u.type]?.text ?? u.type}`).join('、')
  }
  if (op.value === 'compare' && used.length > 1 && used[0].type === 'number') return '對照時數字欄位只能對應一個來源'
  return ''
}

const MAX_INPUTS = 5 // B1 spec 4.2：疊加和對照最多 5 個來源
// 選滿 5 個之後，其他來源停用，不用等按預覽才由後端擋
const inputProps = (ds) => ({ disabled: inputs.value.length >= MAX_INPUTS && !inputs.value.includes(ds.id) })

const lookupItems = computed(() => props.sources.filter((ds) => ds.id !== main.value))

watch(main, (id) => {
  if (lookup.value === id) lookup.value = null
})

// 換主來源或主檔：對接欄位重選；補上的欄位預設用原本的名稱，跟主來源欄位同名時改成「主檔名·欄位」
watch([main, lookup], () => {
  keys.value = [{ main: null, lookup: null }]
  const taken = new Set(fieldsOf(main.value).map((f) => f.name))
  bring.value = fieldsOf(lookup.value).map((f) => ({
    from: f.name,
    name: taken.has(f.name) ? `${nameOf(lookup.value)}·${f.name}` : f.name,
    on: true,
  }))
})

// spec 8.1：對接欄位預設不勾。剛被選成對接欄位的主檔欄位取消勾選，之後使用者要勾回來也可以
watch(() => keys.value.map((k) => k.lookup), (now, before) => {
  for (const b of bring.value) if (now.includes(b.from) && !before.includes(b.from)) b.on = false
})

const definition = computed(() => op.value !== 'lookup'
  ? {
      op: 'union',
      ...(op.value === 'compare' && { compare: true }),
      inputs: inputs.value,
      columns: columns.value.map((c) => ({
        name: c.name.trim(),
        from: Object.fromEntries(Object.entries(c.from).filter(([, f]) => f)),
      })),
      sourceColumn: sourceColumn.value.trim(),
    }
  : {
      op: 'lookup',
      main: main.value,
      lookup: lookup.value,
      keys: keys.value.map((k) => ({ main: k.main, lookup: k.lookup })),
      bring: bring.value.filter((b) => b.on).map((b) => ({ name: b.name.trim(), from: b.from })),
    })
const definitionKey = computed(() => JSON.stringify(definition.value))

const defaultName = computed(() => {
  if (op.value === 'union') return inputs.value.map(nameOf).join('＋')
  if (op.value === 'compare') return inputs.value.length ? `${inputs.value.map(nameOf).join('／')} 對照` : ''
  return main.value && lookup.value ? `${nameOf(main.value)}（補${nameOf(lookup.value)}）` : ''
})
watch(defaultName, (n) => {
  if (!nameEdited.value) name.value = n
}, { immediate: true })

// spec 8.1：定義一改，預覽就清掉，「建立」要重新預覽才能按
watch(definitionKey, () => {
  preview.value = null
  error.value = ''
})

const canPreview = computed(() => !busy.value && (op.value !== 'lookup'
  ? inputs.value.length >= 2
  : !!main.value && !!lookup.value && keys.value.every((k) => k.main && k.lookup)))
const canCreate = computed(() => !busy.value && !!preview.value && !!name.value.trim())

async function runPreview() {
  const key = definitionKey.value
  busy.value = true
  error.value = ''
  try {
    const result = await api.previewMerged({ name: name.value.trim(), merge: definition.value })
    // 等回應的時候定義又改了：這份預覽已經不是目前的定義，丟掉
    if (key === definitionKey.value) preview.value = result
  } catch (e) {
    // 舊預覽的錯誤：定義已經改了，不要蓋掉現在的畫面
    if (key === definitionKey.value) error.value = e.message
  } finally {
    busy.value = false
  }
}

async function create() {
  busy.value = true
  error.value = ''
  try {
    emit('created', await api.createMerged({ name: name.value.trim(), merge: definition.value }))
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

// C2 spec 5.3：格子的值還是 JEV 填的那個，才標「JEV 建議」
const jevPicked = (c, id) => !!c.suggested?.[id] && c.suggested[id] === c.from[id]

// C2 spec 5.2：基準欄位 to 那一列的 input 格子是空的，field 自己那一列只有 input 格子有值，兩列都在才套用；
// 任何一列找不到，就是使用者已經改過，跳過
function applyMatch({ input, field, to }) {
  const target = columns.value.find((c) => c.from[inputs.value[0]] === to && !c.from[input])
  const own = columns.value.find((c) => c.from[input] === field && inputs.value.every((id) => id === input || !c.from[id]))
  if (!target || !own) return false
  target.from[input] = field
  target.suggested = { ...target.suggested, [input]: field }
  columns.value.splice(columns.value.indexOf(own), 1)
  return true
}

const sourcesKey = computed(() => JSON.stringify([op.value, inputs.value]))

async function suggest() {
  if (suggesting.value) return // v-btn 的 loading 不會擋點擊
  const key = sourcesKey.value
  suggesting.value = true
  suggestNote.value = ''
  error.value = ''
  try {
    const { matches } = await api.suggestMapping(inputs.value, op.value === 'compare')
    // 等回應的時候來源或方式換了，對應表已經重排過，這份結果丟掉
    if (key !== sourcesKey.value) return
    let n = 0
    for (const m of matches) if (applyMatch(m)) n++
    suggestNote.value = n ? `JEV 對上了 ${n} 個欄位，建立前請先預覽確認` : 'JEV 沒有找到可以對上的欄位'
  } catch (e) {
    if (key === sourcesKey.value) error.value = e.message
  } finally {
    suggesting.value = false
  }
}
</script>

<template>
  <div>
    <v-radio-group v-model="op" inline hide-details aria-label="合併方式" class="mb-2">
      <v-radio value="union" label="疊加（欄位一樣的資料接起來）" />
      <v-radio value="compare" label="對照（數值依來源分開比較）" />
      <v-radio value="lookup" label="查表（用主檔補欄位）" />
    </v-radio-group>

    <template v-if="op !== 'lookup'">
      <v-select
        v-model="inputs"
        :items="sources"
        item-title="name"
        item-value="id"
        label="來源"
        multiple
        chips
        closable-chips
        :item-props="inputProps"
        hint="2～5 個，順序就是資料列的順序"
        persistent-hint
      />
      <template v-if="inputs.length">
        <div v-if="inputs.length >= 2" class="mt-3">
          <v-btn variant="tonal" size="small" prepend-icon="mdi-auto-fix" :loading="suggesting" @click="suggest">
            請 JEV 對欄位
          </v-btn>
          <div v-if="suggestNote" class="text-caption mt-1">{{ suggestNote }}</div>
        </div>
        <v-table density="compact" class="mf-map mt-3">
          <thead>
            <tr>
              <th>輸出欄位</th>
              <th v-for="id in inputs" :key="id">{{ nameOf(id) }}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in columns" :key="c.key">
              <td>
                <v-text-field
                  v-model="c.name"
                  :aria-label="`第 ${i + 1} 欄的輸出名稱`"
                  density="compact"
                  hide-details="auto"
                  :error-messages="columnProblem(c) || []"
                />
              </td>
              <td v-for="id in inputs" :key="id">
                <div class="d-flex align-center ga-1">
                  <v-select
                    v-model="c.from[id]"
                    :items="fieldItems(id)"
                    item-props
                    :aria-label="`第 ${i + 1} 欄在${nameOf(id)}的欄位`"
                    placeholder="（無）"
                    persistent-placeholder
                    clearable
                    density="compact"
                    hide-details
                  />
                  <v-chip v-if="jevPicked(c, id)" size="x-small" label>JEV 建議</v-chip>
                </div>
              </td>
              <td>
                <v-btn icon="mdi-close" variant="text" size="small" :aria-label="`刪除第 ${i + 1} 欄`" @click="columns.splice(i, 1)" />
              </td>
            </tr>
          </tbody>
        </v-table>
        <v-btn variant="text" size="small" prepend-icon="mdi-plus" class="mt-1" @click="columns.push({ key: nextKey++, name: '', from: {} })">
          新增一欄
        </v-btn>
      </template>
      <v-text-field v-model="sourceColumn" label="來源欄名稱" class="mt-3" />
    </template>

    <template v-else>
      <div class="d-flex ga-3">
        <v-select v-model="main" :items="sources" item-title="name" item-value="id" label="主來源" />
        <v-select v-model="lookup" :items="lookupItems" item-title="name" item-value="id" label="主檔" />
      </div>
      <div class="cc-label mb-1">對接欄位</div>
      <div v-for="(k, i) in keys" :key="i" class="d-flex align-center ga-2 mb-2">
        <v-select
          v-model="k.main"
          :items="fieldItems(main)"
          item-props
          :aria-label="`第 ${i + 1} 組主來源欄位`"
          density="compact"
          hide-details
        />
        <span aria-hidden="true">=</span>
        <v-select
          v-model="k.lookup"
          :items="fieldItems(lookup)"
          item-props
          :aria-label="`第 ${i + 1} 組主檔欄位`"
          density="compact"
          hide-details
        />
        <v-btn
          v-if="keys.length > 1"
          icon="mdi-close"
          variant="text"
          size="small"
          :aria-label="`刪除第 ${i + 1} 組對接欄位`"
          @click="keys.splice(i, 1)"
        />
      </div>
      <v-btn v-if="keys.length < 3" variant="text" size="small" prepend-icon="mdi-plus" @click="keys.push({ main: null, lookup: null })">
        新增對接欄位
      </v-btn>
      <template v-if="bring.length">
        <div class="cc-label mt-3 mb-1">補上的欄位</div>
        <div v-for="b in bring" :key="b.from" class="d-flex align-center ga-2">
          <v-checkbox-btn v-model="b.on" :aria-label="`補上 ${b.from}`" />
          <v-text-field v-model="b.name" :aria-label="`${b.from} 的輸出名稱`" :disabled="!b.on" density="compact" hide-details />
        </div>
      </template>
    </template>

    <v-text-field v-model="name" label="名稱" class="mt-3" @update:model-value="nameEdited = true" />

    <v-alert v-if="error" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
    <div v-if="preview" class="mt-3">
      <div class="cc-label mb-1">預覽 · 共 {{ preview.rowCount }} 筆</div>
      <v-alert v-for="w in preview.warnings" :key="w" type="warning" density="compact" class="mb-2">{{ w }}</v-alert>
      <div class="mf-preview">
        <v-table density="compact">
          <thead>
            <tr><th v-for="f in preview.schema" :key="f.name">{{ f.label || f.name }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in preview.rows" :key="i">
              <td v-for="f in preview.schema" :key="f.name">{{ row[f.name] ?? '' }}</td>
            </tr>
          </tbody>
        </v-table>
      </div>
    </div>

    <div class="d-flex justify-end ga-2 mt-3">
      <v-btn variant="flat" :loading="busy" :disabled="!canPreview" @click="runPreview">預覽</v-btn>
      <v-btn variant="flat" color="primary" :disabled="!canCreate" @click="create">建立</v-btn>
    </div>
  </div>
</template>

<style scoped>
.mf-preview { max-height: 320px; overflow: auto; }
.mf-preview :deep(th),
.mf-preview :deep(td) { white-space: nowrap; }
</style>
