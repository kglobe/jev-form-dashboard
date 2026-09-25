<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'
import './motion.css'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const MAX_INTENTS = 10 // spec 3.2：需求紀錄最多 10 句
const TOAST_MS = 6000 // spec 3.2：「復原」提示停留 6 秒

const intent = ref('')
const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const adjustText = ref('')
const adjusting = ref(false)
const nameEdited = ref(false)
const toast = ref(null) // { spec, index }：剛移除的卡片和它在移除前的位置

const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())
const history = computed(() => preview.value?.intents ?? [])
const historyFull = computed(() => history.value.length >= MAX_INTENTS)
const noFocus = computed(() => history.value.length > 0 && !preview.value.layout.some((s) => s.options?.focus))
// 名稱空白時 /preview/data 會拒絕，調整後的預覽就畫不出來
const canAdjust = computed(() => !!adjustText.value.trim() && !adjusting.value && !historyFull.value && !nameInvalid.value)

// 關掉預覽之後才回來的調整結果直接丟掉
let adjustSeq = 0
let toastTimer = 0
const toastHold = { hover: false, focus: false }

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
  closeToast() // spec 3.2：按「調整」時提示立即關閉，已移除的圖不能再復原
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

// 關掉預覽（取消、存檔、Esc）時，還在等的調整作廢；錯誤訊息也清掉，才不會跑到左側面板；提示一併關掉
watch(open, (isOpen) => {
  if (isOpen) return
  adjustSeq++
  adjusting.value = false
  error.value = ''
  closeToast()
})

// spec 6.6：依順序每列兩張（6×4），明細表放最後（12×5），跟後端的 ConfigAssembler.layOut 同一套
function relayout(layout) {
  const charts = layout.filter((s) => s.type !== 'table')
  const y = Math.ceil(charts.length / 2) * 4
  return [
    ...charts.map((s, n) => ({ ...s, gridPos: { x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 } })),
    ...layout.filter((s) => s.type === 'table').map((s) => ({ ...s, gridPos: { x: 0, y, w: 12, h: 5 } })),
  ]
}

// spec 3.2：只在前端拿掉，不呼叫 API；下次「調整」送出的 current 也不會有這張
function remove(chartId) {
  const layout = preview.value.layout
  const index = layout.findIndex((s) => s.chartId === chartId)
  if (index < 0) return // 退場那 120ms 裡又用鍵盤按了一次「✕」
  preview.value = { ...preview.value, layout: relayout(layout.filter((s) => s.chartId !== chartId)) }
  showToast({ spec: layout[index], index })
}

function undo() {
  if (!toast.value) return // 提示退場那 120ms 裡「復原」還在，又按了一次
  const { spec, index } = toast.value
  const layout = [...preview.value.layout]
  layout.splice(index, 0, spec)
  preview.value = { ...preview.value, layout: relayout(layout) }
  closeToast()
  // 「復原」按鈕跟著提示消失，焦點移到放回來的那張卡片的「✕」
  nextTick(() => document.querySelector(`[data-chart-id="${CSS.escape(spec.chartId)}"] [aria-label="移除這張圖"]`)?.focus())
}

function showToast(t) {
  closeToast() // 一次只有一個提示，前一張就不能再復原
  toast.value = t
  toastTimer = setTimeout(closeToast, TOAST_MS)
}

function closeToast() {
  clearTimeout(toastTimer)
  toast.value = null
  toastHold.hover = false
  toastHold.focus = false
}

// ponytail: 滑鼠和焦點都離開後重新計滿 6 秒，不記剩下的時間；要精準暫停再記 Date.now()
function holdToast(kind, on) {
  if (!toast.value) return
  toastHold[kind] = on
  clearTimeout(toastTimer)
  if (!toastHold.hover && !toastHold.focus) toastTimer = setTimeout(closeToast, TOAST_MS)
}

onBeforeUnmount(() => clearTimeout(toastTimer))

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
  <!-- scrollable：捲動的是 v-card-text，工具列和下面的提示都固定不動 -->
  <v-dialog v-model="open" fullscreen scrollable>
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
          :disabled="nameInvalid || saving || adjusting"
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
          <DashboardView :config="preview" preview :busy="adjusting" @remove="remove" />
        </template>
      </v-card-text>
      <!-- 放在 v-card 底下、v-card-text 後面：內容捲動時提示還在預覽下方中央，也還在對話框的焦點範圍裡。
           role="status" 放在一直存在的外框上，提示出現時螢幕報讀器才念得到 -->
      <div class="undo-toast-region" role="status">
        <Transition name="undo-toast">
          <div
            v-if="toast"
            class="undo-toast"
            @mouseenter="holdToast('hover', true)"
            @mouseleave="holdToast('hover', false)"
            @focusin="holdToast('focus', true)"
            @focusout="holdToast('focus', false)"
          >
            <span>已移除「{{ toast.spec.title }}」</span>
            <v-btn variant="text" size="small" @click="undo">復原</v-btn>
          </div>
        </Transition>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
/* 不叫 tc‑toast：tactile-clean.css 有同名的 class（fixed 定位、translateX(-50%)），會把這個提示拉歪 */
/* spec 3.2：預覽畫面下方中央，內容捲動時也不動；外框不擋點擊，只有提示本身可以按。
   .v-card 是 position: relative；不用 position: fixed，因為全螢幕對話框的 .v-overlay__content 有 contain: layout */
.undo-toast-region {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 24px;
  z-index: 10;
  display: flex;
  justify-content: center;
  pointer-events: none;
}

.undo-toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 16px;
  border-radius: 4px;
  background: rgb(var(--v-theme-surface-variant));
  color: rgb(var(--v-theme-on-surface-variant));
  box-shadow: 0 3px 5px -1px rgba(0, 0, 0, 0.2), 0 6px 10px 0 rgba(0, 0, 0, 0.14);
  pointer-events: auto;
}
</style>
