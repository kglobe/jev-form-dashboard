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

const preview = ref(null)
const error = ref('')
const busy = ref(false)

let nextKey = 0
const byId = computed(() => Object.fromEntries(props.sources.map((ds) => [ds.id, ds])))
const nameOf = (id) => byId.value[id]?.name ?? '（已不存在）'
const fieldsOf = (id) => byId.value[id]?.schema ?? []
const typeOf = (id, field) => fieldsOf(id).find((f) => f.name === field)?.type
const fieldItems = (id) => fieldsOf(id).map((f) => ({ title: f.name, value: f.name, prependIcon: FIELD_TYPES[f.type]?.icon }))

// ponytail: 選的來源一變就重排對應表，手動改過的對應會不見；要保留再比對新舊來源
watch(inputs, (ids) => {
  const names = [...new Set(ids.flatMap((id) => fieldsOf(id).map((f) => f.name)))]
  columns.value = names.map((n) => ({
    key: nextKey++,
    name: n,
    from: Object.fromEntries(ids.map((id) => [id, typeOf(id, n) ? n : null])),
  }))
})

function typeProblem(c) {
  const used = inputs.value.filter((id) => c.from[id]).map((id) => ({ id, type: typeOf(id, c.from[id]) }))
  if (new Set(used.map((u) => u.type)).size < 2) return ''
  return '型別不一致：' + used.map((u) => `${nameOf(u.id)}是${FIELD_TYPES[u.type]?.text ?? u.type}`).join('、')
}

const definition = computed(() => ({
  op: 'union',
  inputs: inputs.value,
  columns: columns.value.map((c) => ({
    name: c.name.trim(),
    from: Object.fromEntries(Object.entries(c.from).filter(([, f]) => f)),
  })),
  sourceColumn: sourceColumn.value.trim(),
}))
const definitionKey = computed(() => JSON.stringify(definition.value))

const defaultName = computed(() => inputs.value.map(nameOf).join('＋'))
watch(defaultName, (n) => {
  if (!nameEdited.value) name.value = n
}, { immediate: true })

// spec 8.1：定義一改，預覽就清掉，「建立」要重新預覽才能按
watch(definitionKey, () => {
  preview.value = null
  error.value = ''
})

const canPreview = computed(() => !busy.value && inputs.value.length >= 2)
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
    error.value = e.message
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
</script>

<template>
  <div>
    <v-radio-group v-model="op" inline hide-details aria-label="合併方式" class="mb-2">
      <v-radio value="union" label="疊加（欄位一樣的資料接起來）" />
      <v-radio value="lookup" label="查表（用主檔補欄位）" />
    </v-radio-group>

    <template v-if="op === 'union'">
      <v-select
        v-model="inputs"
        :items="sources"
        item-title="name"
        item-value="id"
        label="來源"
        multiple
        chips
        closable-chips
        hint="2～5 個，順序就是資料列的順序"
        persistent-hint
      />
      <template v-if="inputs.length">
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
                  :error-messages="typeProblem(c) || []"
                />
              </td>
              <td v-for="id in inputs" :key="id">
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
