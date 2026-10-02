<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'
import { FIELD_TYPES } from './fields.js'
import './motion.css'

const props = defineProps({
  // 資料頁按「用這個來源製圖」時 App 給一個新的 { id }；每次都是新物件，同一個來源按第二次也會切過來
  request: { type: Object, default: null },
  // 製圖頁一直掛著（切頁不會丟掉預覽），切回來時重新抓資料來源，資料頁改過的顯示名稱才會跟上
  active: { type: Boolean, default: true },
  // 自己的儀表板清單（App 左側那份，最近更新的在最上面），給「加到既有儀表板」選
  dashboards: { type: Array, default: () => [] },
})
// saved：存好或加好了，帶儀表板 id；dashboards-changed：加圖時發現儀表板已經不在，請 App 重抓清單
const emit = defineEmits(['saved', 'dashboards-changed'])

const MAX_INTENTS = 10 // spec 3.2：需求紀錄最多 10 句
const TOAST_MS = 6000 // spec 3.2：「復原」提示停留 6 秒

const list = ref([])
const datasourceId = ref(null)
// 勾選想看的欄位；只屬於目前選的資料來源，換來源就清空
const picked = ref([])
const intent = ref('')
const preview = ref(null)
const busy = ref(false)
const saving = ref(false)
const appending = ref(false)
const error = ref('')
const adjustText = ref('')
const adjusting = ref(false)
const nameEdited = ref(false)
const toast = ref(null) // { spec, index }：剛移除的卡片和它在移除前的位置

const source = computed(() => list.value.find((ds) => ds.id === datasourceId.value) ?? null)
const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())
const history = computed(() => preview.value?.intents ?? [])
const historyFull = computed(() => history.value.length >= MAX_INTENTS)
const noFocus = computed(() => history.value.length > 0 && !preview.value.layout.some((s) => s.options?.focus))
const canAnalyze = computed(() => !!datasourceId.value && !busy.value && !adjusting.value && !saving.value && !appending.value)
// 名稱空白時 /preview/data 會拒絕，調整後的預覽就畫不出來
const canAdjust = computed(() => !!adjustText.value.trim() && !adjusting.value && !busy.value && !saving.value && !appending.value && !historyFull.value && !nameInvalid.value)

// 捨棄預覽、換來源之後才回來的分析或調整結果直接丟掉；兩者不會同時進行，共用一個序號
let pendingSeq = 0
let toastTimer = 0
const toastHold = { hover: false, focus: false }

async function loadList() {
  try {
    list.value = await api.listDatasources()
    // 原本選的來源不見了（或還沒選）：走 choose()，舊來源的欄位勾選和預覽一起清掉
    if (!source.value) choose(list.value[0]?.id ?? null)
  } catch (e) {
    error.value = e.message
  }
}

watch(() => props.active, (on) => on && loadList(), { immediate: true })
watch(() => props.request, (r) => r && choose(r.id))

// 換來源：欄位勾選清空；畫面上的預覽是舊來源的，一起捨棄，左邊設定跟右邊畫布才對得上
function choose(id) {
  if (id === datasourceId.value) return
  datasourceId.value = id
  picked.value = []
  discard()
}

async function analyze() {
  if (!canAnalyze.value) return
  const seq = ++pendingSeq
  busy.value = true
  error.value = ''
  const first = intent.value.trim()
  try {
    // 有勾欄位才多送 fields；沒填需求、沒勾欄位就只送 datasourceId
    const next = await (picked.value.length
      ? api.analyze(datasourceId.value, first ? [first] : undefined, undefined, picked.value)
      : first ? api.analyze(datasourceId.value, [first]) : api.analyze(datasourceId.value))
    if (seq !== pendingSeq) return
    closeToast() // 新的結果取代舊的預覽，之前移除的卡片不能再復原
    preview.value = next
    nameEdited.value = false
    adjustText.value = ''
  } catch (e) {
    if (seq === pendingSeq) error.value = e.message
  } finally {
    if (seq === pendingSeq) busy.value = false
  }
}

async function adjust() {
  if (!canAdjust.value) return
  closeToast() // spec 3.2：按「調整」時提示立即關閉，已移除的圖不能再復原
  const seq = ++pendingSeq
  adjusting.value = true
  error.value = ''
  try {
    // 換來源時預覽會被捨棄，左邊選的來源一定就是這份預覽的來源
    const next = await api.analyze(datasourceId.value, [...history.value, adjustText.value.trim()],
      preview.value.layout, ...(picked.value.length ? [picked.value] : []))
    if (seq !== pendingSeq) return
    // spec 3.2：使用者改過名稱就保留他改的，沒改過就用新結果的預設名稱
    preview.value = { ...next, name: nameEdited.value ? preview.value.name : next.name }
    adjustText.value = ''
  } catch (e) {
    if (seq === pendingSeq) error.value = e.message
  } finally {
    if (seq === pendingSeq) adjusting.value = false
  }
}

// 捨棄預覽（按「捨棄」、存檔後、換來源）：還在等的分析和調整作廢，錯誤訊息和提示一併清掉
function discard() {
  pendingSeq++
  busy.value = false
  adjusting.value = false
  error.value = ''
  closeToast()
  preview.value = null
}

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
  if (saving.value || appending.value || nameInvalid.value) return
  saving.value = true
  try {
    const saved = await api.createDashboard(preview.value)
    discard()
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

// 多來源 spec 3.1：加到既有儀表板不用預覽的名稱，名稱空白也能加
async function append(id) {
  if (saving.value || appending.value || busy.value || adjusting.value) return
  appending.value = true
  error.value = ''
  try {
    await api.appendCharts(id, { layout: preview.value.layout, intents: preview.value.intents })
    discard()
    emit('saved', id)
  } catch (e) {
    error.value = e.message
    // 儀表板在別的分頁被刪了：請 App 重抓清單，選單裡就不會再有它
    if (e.status === 404) emit('dashboards-changed')
  } finally {
    appending.value = false
  }
}
</script>

<template>
  <section class="cc-view">
    <header class="cc-head">
      <div>
        <div class="cc-eyebrow">02 · Compose</div>
        <h1 class="cc-title">製圖</h1>
        <p class="cc-desc">選來源、挑欄位、說一句想看什麼，JEV 會挑圖表；右邊預覽滿意再存成儀表板。</p>
      </div>
    </header>

    <div class="cb-grid">
      <aside class="cc-panel cc-sticky cb-config">
        <div class="cb-scroll">
          <!-- 欄位沒有浮動 label（步驟標題就是 label），Vuetify 仍會掛 aria-labelledby 指向不存在的 id；改指向步驟標題 -->
          <div class="cb-step"><span class="cb-step__n">01</span><span id="cb-source-label">資料來源</span></div>
          <v-select
            :model-value="datasourceId"
            :items="list"
            item-title="name"
            item-value="id"
            aria-label="資料來源"
            aria-labelledby="cb-source-label"
            placeholder="選一個資料來源"
            prepend-inner-icon="mdi-database-outline"
            hide-details
            @update:model-value="choose"
          />

          <div class="cb-step">
            <span class="cb-step__n">02</span>欄位
            <span class="cb-step__aside">{{ picked.length ? `已選 ${picked.length}` : '不選就由 JEV 挑' }}</span>
            <v-btn v-if="picked.length" variant="text" size="small" class="cb-clear" @click="picked = []">清除</v-btn>
          </div>
          <v-chip-group v-if="source" v-model="picked" multiple column selected-class="cc-chip--on" aria-label="想看的欄位">
            <v-chip
              v-for="f in source.schema"
              :key="f.name"
              :value="f.name"
              :title="`${f.name}（${FIELD_TYPES[f.type]?.text ?? f.type}）`"
              role="checkbox"
              :aria-checked="String(picked.includes(f.name))"
              variant="outlined"
              filter
            >
              <v-icon :icon="FIELD_TYPES[f.type]?.icon" size="13" start />{{ f.label || f.name }}
            </v-chip>
            <!-- B3 spec 6.2：比率接在欄位後面，勾了一樣當焦點欄位 -->
            <v-chip
              v-for="r in source.ratios ?? []"
              :key="`ratio:${r.name}`"
              :value="r.name"
              :title="`${r.name}（比率：${r.numerator} ÷ ${r.denominator}）`"
              role="checkbox"
              :aria-checked="String(picked.includes(r.name))"
              variant="outlined"
              filter
            >
              <v-icon icon="mdi-percent" size="13" start />{{ r.name }}
            </v-chip>
          </v-chip-group>
          <p v-else class="text-caption text-medium-emphasis">先選資料來源。</p>

          <div class="cb-step"><span class="cb-step__n">03</span><span id="cb-intent-label">想看什麼？（選填）</span></div>
          <v-textarea
            v-model="intent"
            aria-label="想看什麼？（選填）"
            aria-labelledby="cb-intent-label"
            placeholder="例如：各區每月營收、哪個產品退貨最多"
            hint="這段文字會送給 JEV 做分析"
            persistent-hint
            counter="200"
            persistent-counter
            maxlength="200"
            rows="3"
            @keydown.enter.ctrl.prevent="analyze"
            @keydown.enter.meta.prevent="analyze"
          />
          <v-btn
            color="primary"
            block
            size="large"
            class="mt-3"
            prepend-icon="mdi-creation"
            :loading="busy"
            :disabled="!canAnalyze"
            @click="analyze"
          >
            {{ preview ? '重新分析' : '分析' }}
            <kbd class="cb-kbd" aria-hidden="true">Ctrl ⏎</kbd>
          </v-btn>

          <template v-if="preview">
            <div class="cb-step"><span class="cb-step__n">04</span>調整</div>
            <!-- 用 v-show 不用 v-if：TransitionGroup 一直掛著，第一句出現時才有淡入 -->
            <div v-show="history.length" class="tc-history mb-3">
              <div class="cc-label mb-1">需求紀錄</div>
              <!-- spec 10.8：新的一句淡入；key 用序號，舊的句子不會重播 -->
              <TransitionGroup name="tc-intent" tag="ol" class="tc-intents">
                <li v-for="(sentence, i) in history" :key="i">{{ sentence }}</li>
              </TransitionGroup>
            </div>
            <div class="d-flex align-start ga-2">
              <v-text-field
                v-model="adjustText"
                label="想怎麼調整？"
                placeholder="例如：不要圓餅圖、加各產品的數量"
                maxlength="200"
                :disabled="historyFull || adjusting"
                :messages="historyFull ? ['需求紀錄最多 10 句'] : []"
                @keydown.enter="adjust"
              />
              <v-btn :loading="adjusting" :disabled="!canAdjust" @click="adjust">調整</v-btn>
            </div>
          </template>
        </div>
      </aside>

      <section class="cb-canvas">
        <div v-if="preview" class="cc-panel cb-bar">
          <v-text-field
            v-model="preview.name"
            label="儀表板名稱"
            hide-details="auto"
            :error-messages="nameInvalid ? ['請輸入儀表板名稱'] : []"
            class="cb-name"
            @update:model-value="nameEdited = true"
          />
          <v-spacer />
          <v-btn variant="text" prepend-icon="mdi-close" @click="discard">捨棄</v-btn>
          <v-btn
            color="primary"
            prepend-icon="mdi-content-save-outline"
            :loading="saving"
            :disabled="nameInvalid || saving || appending || adjusting || busy"
            @click="save"
          >存成新儀表板</v-btn>
          <!-- 多來源 spec 3.1：一份儀表板都沒有時不顯示；分析或調整進行中不能按 -->
          <v-menu v-if="dashboards.length" location="bottom end">
            <template #activator="{ props: activator }">
              <v-btn
                v-bind="activator"
                variant="tonal"
                append-icon="mdi-menu-down"
                :loading="appending"
                :disabled="saving || appending || adjusting || busy"
              >加到既有儀表板</v-btn>
            </template>
            <v-list aria-label="加到既有儀表板">
              <v-list-item v-for="d in dashboards" :key="d.id" :title="d.name" @click="append(d.id)" />
            </v-list>
          </v-menu>
        </div>
        <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
        <template v-if="preview">
          <v-alert v-if="noFocus" type="info" density="compact">找不到跟需求直接相關的欄位，先顯示一般的分析結果。</v-alert>
          <DashboardView :config="preview" preview :busy="adjusting || busy" @remove="remove" />
        </template>
        <div v-else-if="busy" class="cc-panel cb-scan" aria-busy="true">
          <div class="cb-scan__grid" aria-hidden="true"><i /><i /><i /><i /></div>
          <p class="cb-scan__text">JEV 正在分析「{{ source?.name }}」…</p>
        </div>
        <div v-else class="cc-panel cc-empty cb-empty">
          <div class="cc-empty__icon"><v-icon icon="mdi-chart-box-plus-outline" size="30" /></div>
          <h2>圖表會出現在這裡</h2>
          <p>左邊選好資料來源，想指定欄位就點起來，寫一句需求（也可以不寫），按「分析」。</p>
        </div>
      </section>
    </div>

    <!-- 固定在畫面下方中央，捲動時也看得到；role="status" 放在一直存在的外框上，提示出現時螢幕報讀器才念得到 -->
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
          <v-btn variant="text" size="small" density="default" @click="undo">復原</v-btn>
        </div>
      </Transition>
    </div>
  </section>
</template>

<style scoped>
.cb-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
@media (max-width: 960px) {
  .cb-grid { grid-template-columns: minmax(0, 1fr); }
}

.cb-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 20px 20px; }

.cb-step {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 20px 0 10px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tc-text, inherit);
}
.cb-step__n {
  font: 400 10.5px/1 var(--tc-display, monospace);
  letter-spacing: 0.06em;
  color: var(--tc-primary, currentColor);
}
.cb-step__aside { margin-left: auto; font-size: 12px; font-weight: 400; color: var(--tc-muted, inherit); }
.cb-clear { margin-right: -8px; }
.cb-kbd { position: absolute; right: 12px; opacity: 0.55; }

.tc-intents {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--tc-text-2, inherit);
}

.cb-canvas { display: flex; flex-direction: column; gap: 14px; min-width: 0; }

.cb-bar {
  position: sticky;
  top: calc(var(--tc-bar-h, 0px) + 12px); /* 讓出頂部導覽列 */
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  backdrop-filter: blur(10px);
}
.cb-name { max-width: 360px; }

.cb-empty { min-height: 420px; }

/* 分析中：四張空卡片，一條主色掃描線由上往下掃 */
.cb-scan { position: relative; overflow: hidden; padding: 20px; }
.cb-scan__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.cb-scan__grid i {
  height: 200px;
  border: 1px dashed var(--tc-border-subtle, rgba(128, 128, 128, 0.3));
  border-radius: var(--tc-radius-lg, 8px);
}
.cb-scan::after {
  content: "";
  position: absolute;
  inset: 0 0 auto;
  height: 120px;
  background: linear-gradient(180deg, transparent, rgba(var(--tc-accent-rgb, 34, 211, 238), 0.14) 80%, rgba(var(--tc-accent-rgb, 34, 211, 238), 0.6));
  border-bottom: 1px solid var(--tc-primary, #22d3ee);
  animation: cb-scan 1.8s cubic-bezier(0.45, 0, 0.55, 1) infinite;
}
@keyframes cb-scan {
  from { transform: translateY(-120px); }
  to { transform: translateY(520px); }
}
.cb-scan__text {
  position: absolute;
  z-index: 1;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  margin: 0;
  padding: 6px 16px;
  white-space: nowrap;
  font-size: 14px;
  color: var(--tc-text-2, inherit);
  background: var(--tc-panel, transparent);
  border: 1px solid var(--tc-border-subtle, transparent);
  border-radius: 999px;
}
@media (prefers-reduced-motion: reduce) {
  .cb-scan::after { animation: none; opacity: 0.4; }
}

/* spec 3.2：畫面下方中央，外框不擋點擊，只有提示本身可以按 */
.undo-toast-region {
  position: fixed;
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
  padding: 6px 8px 6px 16px;
  border-radius: var(--tc-radius, 4px);
  border: 1px solid var(--tc-border-subtle, transparent);
  background: var(--tc-subtle, rgb(var(--v-theme-surface-variant)));
  color: var(--tc-text, rgb(var(--v-theme-on-surface-variant)));
  box-shadow: var(--tc-overlay-shadow, 0 3px 5px -1px rgba(0, 0, 0, 0.2), 0 6px 10px 0 rgba(0, 0, 0, 0.14));
  pointer-events: auto;
}
</style>
