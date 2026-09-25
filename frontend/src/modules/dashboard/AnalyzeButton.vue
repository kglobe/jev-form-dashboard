<script setup>
import { computed, ref, watch } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'
import './motion.css'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const MAX_INTENTS = 10 // spec 3.2：需求紀錄最多 10 句

const intent = ref('')
const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const adjustText = ref('')
const adjusting = ref(false)
const nameEdited = ref(false)

const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())
const history = computed(() => preview.value?.intents ?? [])
const historyFull = computed(() => history.value.length >= MAX_INTENTS)
const noFocus = computed(() => history.value.length > 0 && !preview.value.layout.some((s) => s.options?.focus))
const canAdjust = computed(() => !!adjustText.value.trim() && !adjusting.value && !historyFull.value)

// 關掉預覽之後才回來的調整結果直接丟掉
let adjustSeq = 0

async function analyze() {
  busy.value = true
  error.value = ''
  const first = intent.value.trim()
  try {
    // 沒填需求就跟原本一樣只送 datasourceId
    preview.value = await (first ? api.analyze(props.datasourceId, [first]) : api.analyze(props.datasourceId))
    nameEdited.value = false
    adjustText.value = ''
    open.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function adjust() {
  if (!canAdjust.value) return
  const seq = ++adjustSeq
  adjusting.value = true
  error.value = ''
  try {
    const next = await api.analyze(preview.value.datasourceId, [...history.value, adjustText.value.trim()],
      preview.value.layout)
    if (seq !== adjustSeq) return
    // spec 3.2：使用者改過名稱就保留他改的，沒改過就用新結果的預設名稱
    preview.value = { ...next, name: nameEdited.value ? preview.value.name : next.name }
    adjustText.value = ''
  } catch (e) {
    if (seq === adjustSeq) error.value = e.message
  } finally {
    if (seq === adjustSeq) adjusting.value = false
  }
}

// 關掉預覽（取消、存檔、Esc）時，還在等的調整作廢；錯誤訊息也清掉，才不會跑到左側面板
watch(open, (isOpen) => {
  if (isOpen) return
  adjustSeq++
  adjusting.value = false
  error.value = ''
})

async function save() {
  if (saving.value || nameInvalid.value) return
  saving.value = true
  try {
    const saved = await api.createDashboard(preview.value)
    open.value = false
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-textarea
    v-model="intent"
    label="想看什麼？（選填）"
    placeholder="例如：各區每月營收、哪個產品退貨最多"
    hint="這段文字會送給 JEV 做分析"
    persistent-hint
    counter="200"
    persistent-counter
    maxlength="200"
    rows="2"
    density="compact"
    class="mb-2"
  />
  <v-btn color="primary" block :loading="busy" :disabled="!datasourceId" @click="analyze">分析</v-btn>
  <v-alert v-if="error && !open" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
  <v-dialog v-model="open" fullscreen>
    <v-card>
      <v-toolbar title="儀表板預覽">
        <v-text-field
          v-if="preview"
          v-model="preview.name"
          label="儀表板名稱"
          density="compact"
          hide-details="auto"
          :error-messages="nameInvalid ? ['請輸入儀表板名稱'] : []"
          class="mx-4"
          style="max-width: 320px"
          @update:model-value="nameEdited = true"
        />
        <v-btn @click="open = false">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          class="mr-2"
          :loading="saving"
          :disabled="nameInvalid || saving"
          @click="save"
        >存為我的儀表板</v-btn>
      </v-toolbar>
      <v-card-text>
        <v-alert v-if="error" type="error" density="compact" class="mb-2">{{ error }}</v-alert>
        <template v-if="preview">
          <v-alert v-if="noFocus" type="info" density="compact" class="mb-2">找不到跟需求直接相關的欄位，先顯示一般的分析結果。</v-alert>
          <!-- 用 v-show 不用 v-if：TransitionGroup 一直掛著，第一句出現時才有淡入 -->
          <div v-show="history.length" class="tc-history mb-2">
            <div class="text-caption text-medium-emphasis">需求紀錄</div>
            <!-- spec 10.8：新的一句淡入；key 用序號，舊的句子不會重播 -->
            <TransitionGroup name="tc-intent" tag="ol" class="tc-intents pl-5">
              <li v-for="(sentence, i) in history" :key="i">{{ sentence }}</li>
            </TransitionGroup>
          </div>
          <div class="d-flex align-start ga-2 mb-2">
            <v-text-field
              v-model="adjustText"
              label="想怎麼調整？"
              placeholder="例如：不要圓餅圖、加各產品的數量"
              maxlength="200"
              density="compact"
              :disabled="historyFull || adjusting"
              :messages="historyFull ? ['需求紀錄最多 10 句'] : []"
            />
            <v-btn color="primary" :loading="adjusting" :disabled="!canAdjust" @click="adjust">調整</v-btn>
          </div>
          <DashboardView :config="preview" preview :busy="adjusting" />
        </template>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
