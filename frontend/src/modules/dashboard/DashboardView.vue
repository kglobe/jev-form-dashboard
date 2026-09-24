<script setup>
import { computed, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import { api } from './api.js'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
})

const layout = ref([])
const dataById = ref({})
const highlightById = ref({})
const error = ref('')
const loadError = ref('')

const specById = computed(() => Object.fromEntries(props.config.layout.map((s) => [s.chartId, s])))
const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))

let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  layout.value = props.config.layout.map((s) => ({ i: s.chartId, ...s.gridPos }))
  dataById.value = {}
  highlightById.value = {}
  error.value = ''
  loadError.value = ''
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id)
    if (seq !== loadSeq) return
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
  } catch (e) {
    if (seq !== loadSeq) return
    loadError.value = e.message
    return
  }
  if (props.preview) return
  // 異常檢查失敗就不標註，不顯示錯誤
  const found = await api.highlights(props.config.id).catch(() => [])
  if (seq !== loadSeq) return
  highlightById.value = Object.fromEntries(found.map((h) => [h.chartId, h]))
}

watch(() => props.config, load, { immediate: true })

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
  <GridLayout
    v-if="!loadError"
    v-model:layout="layout"
    :col-num="12"
    :row-height="80"
    :is-draggable="!preview"
    :is-resizable="!preview"
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
      <ChartCard :spec="specById[item.i]" :data="dataById[item.i]" :highlight="highlightById[item.i]" />
    </GridItem>
  </GridLayout>
</template>
