<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useTheme } from 'vuetify'
import { ChartBuilder, DashboardView, DatasourcePanel, api, onUnauthorized } from './modules/dashboard/index.js'
import { LoginPage, ReloginDialog, ShareDialog, UserMenu } from './modules/auth/index.js'
import { DASHBOARD_HASH, restoreSavedHash } from './modules/auth/session.js'

const VIEWS = [
  { id: 'data', label: '資料' },
  { id: 'build', label: '製圖' },
  { id: 'dashboards', label: '儀表板' },
]
const view = ref('dashboards')
// 資料頁選中的來源
const datasourceId = ref(null)
// 資料頁按「用這個來源製圖」：每次給新物件，製圖頁才認得出是新的一次
const buildRequest = ref(null)

const dashboards = ref([])
// 唯讀分享 spec 7.7：分享給我的
const shared = ref([])
const current = ref(null)
const error = ref('')
const confirmDelete = ref(null)
// 唯讀分享 spec 7.6：分享對話框
const sharing = ref(false)

// 唯讀分享 spec 7.1：me 拿到之前，App 的其他元件都不掛；needLogin 是 me 回 401，bootError 是其他錯誤
const me = ref(null)
const needLogin = ref(false)
const bootError = ref(null)
// spec 7.2：登入逾時另外用一個 ref，不動 me，畫面上的元件都不卸載；多個 401 同時回來也只是設同一個 true
const relogin = ref(false)
onUnauthorized(() => {
  if (me.value) relogin.value = true
})

// 深淺主題：Vuetify 換元件配色，<html data-theme> 換 command-center.css 的 token，localStorage 記住下次用哪套
const THEME_KEY = 'jev-theme' // index.html 的 inline script 讀同一個 key
const vuetifyTheme = useTheme()
const isLight = computed(() => !vuetifyTheme.current.value.dark)

function toggleTheme() {
  vuetifyTheme.toggle()
  const mode = vuetifyTheme.current.value.dark ? 'dark' : 'light'
  document.documentElement.dataset.theme = mode
  try {
    localStorage.setItem(THEME_KEY, mode)
  } catch {
    // 私密視窗或封鎖網站資料時存不了，這次切換照樣有效
  }
}

function startBuild(id) {
  buildRequest.value = { id }
  view.value = 'build'
}

async function refresh() {
  [dashboards.value, shared.value] = await Promise.all([api.listDashboards(), api.sharedDashboards()])
}

// 製圖頁加圖時發現儀表板已經不在（在別的分頁刪了）：重抓清單，選單裡就不會再有它
function reloadDashboards() {
  refresh().catch((e) => (error.value = e.message))
}

async function boot() {
  needLogin.value = false
  bootError.value = null
  try {
    me.value = await api.me()
  } catch (e) {
    if (e.status === 401) needLogin.value = true
    else bootError.value = e.message
    return
  }
  refresh().catch((e) => (error.value = e.message))
  openFromHash()
}

// spec 7.2：重新登入的是同一個帳號；剛才失敗的動作不會自動重送
function afterRelogin() {
  relogin.value = false
  reloadDashboards()
  if (current.value) open(current.value.id)
}

const NOT_FOUND = '找不到這份儀表板，可能已經刪除或取消分享'
// 唯讀分享 spec 7.8：打開的不是自己的，就是檢視者
const readonly = computed(() => !!current.value && current.value.createdBy !== me.value?.username)

// 最後一次 open() 的 id（plan「實作上的決定」第 23 點）
let lastOpened = null

// spec 7.9：只認 #/d/<uuid>；格式不對的直接說找不到，不打 API；其他 hash 不理
function openFromHash() {
  if (!me.value || !location.hash.startsWith('#/d/')) return
  // open() 自己改的 hash：happy-dom 的 replaceState 會補發 hashchange（瀏覽器不會），不再打開一次
  if (location.hash === `#/d/${lastOpened}`) return
  view.value = 'dashboards'
  const m = location.hash.match(DASHBOARD_HASH)
  if (m) return open(m[1])
  current.value = null
  error.value = NOT_FOUND
}

async function open(id) {
  lastOpened = id
  // spec 7.8、7.9：先改網址再抓，404 時 hash 才是失敗的那份，重新整理會再試它
  history.replaceState(null, '', `#/d/${id}`)
  try {
    current.value = await api.getDashboard(id)
    error.value = ''
  } catch (e) {
    if (e.status === 404) current.value = null
    error.value = e.status === 404 ? NOT_FOUND : e.message
  }
}

// 存檔後切到儀表板頁，直接打開剛存的那份
async function onSaved(id) {
  view.value = 'dashboards'
  try {
    await refresh()
  } catch (e) {
    error.value = e.message
    return
  }
  await open(id)
}

function askDelete(d) {
  confirmDelete.value = d
}

async function confirmRemove() {
  const d = confirmDelete.value
  confirmDelete.value = null
  try {
    await api.deleteDashboard(d.id)
    if (current.value?.id === d.id) {
      current.value = null
      // spec 7.9：只有刪除目前那份才拿掉網址的 hash
      lastOpened = null
      history.replaceState(null, '', location.pathname + location.search)
    }
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

// Alt+1／2／3 切換工作區
function onKey(e) {
  if (!e.altKey || e.ctrlKey || e.metaKey) return
  const target = VIEWS[Number(e.key) - 1]
  if (!target) return
  e.preventDefault()
  view.value = target.id
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  window.addEventListener('hashchange', openFromHash)
  restoreSavedHash()
  boot()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('hashchange', openFromHash))
</script>

<template>
  <v-app>
    <!-- 唯讀分享 spec 7.1：開機先確認登入。下面的既有標記刻意不重排縮排，跟 feat/industrial-theme 合併時衝突才少 -->
    <div v-if="!me && !needLogin && !bootError" class="boot"><v-progress-circular indeterminate color="primary" /></div>
    <LoginPage v-else-if="!me" :error="bootError" :is-light="isLight" @logged-in="boot" @retry="boot" @toggle-theme="toggleTheme" />
    <template v-else>
    <header class="cc-topbar">
      <div class="cc-logo" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="26" height="26">
          <rect width="16" height="16" rx="4" fill="currentColor" />
          <path d="M4 12V8m4 4V4m4 8V7" stroke="#121315" stroke-width="2" stroke-linecap="round" />
        </svg>
        <span>JEV</span>
      </div>
      <nav class="cc-topbar__nav" aria-label="主要導覽">
        <button
          v-for="(v, n) in VIEWS"
          :key="v.id"
          type="button"
          class="cc-topbar__item"
          :class="{ 'is-active': view === v.id }"
          :aria-current="view === v.id ? 'page' : undefined"
          :aria-keyshortcuts="`Alt+${n + 1}`"
          :title="`${v.label}（Alt+${n + 1}）`"
          @click="view = v.id"
        >
          {{ v.label }}
        </button>
      </nav>
      <UserMenu :me="me">
        <template #activator="{ props }">
          <button type="button" class="cc-topbar__item cc-topbar__account" v-bind="props" :title="me.username">
            <v-icon icon="mdi-account-circle-outline" size="18" />
            <span>帳號</span>
          </button>
        </template>
      </UserMenu>
      <button
        type="button"
        class="cc-topbar__item cc-topbar__theme"
        :aria-label="isLight ? '切換成深色主題' : '切換成淺色主題'"
        :title="isLight ? '切換成深色主題' : '切換成淺色主題'"
        @click="toggleTheme"
      >
        <v-icon :icon="isLight ? 'mdi-weather-night' : 'mdi-white-balance-sunny'" size="18" />
        <span>{{ isLight ? '深色' : '淺色' }}</span>
      </button>
    </header>

    <v-main>
      <DatasourcePanel v-if="view === 'data'" :selected-id="datasourceId" @select="(id) => (datasourceId = id)" @build="startBuild" />
      <!-- 製圖頁用 v-show 一直掛著：去資料頁改個顯示名稱再回來，預覽和調整紀錄都還在 -->
      <ChartBuilder
        v-show="view === 'build'"
        :active="view === 'build'"
        :request="buildRequest"
        :dashboards="dashboards"
        @saved="onSaved"
        @dashboards-changed="reloadDashboards"
      />
      <section v-if="view === 'dashboards'" class="cc-view">
        <header class="cc-head">
          <div>
            <div class="cc-eyebrow">03 · Dashboards</div>
            <h1 class="cc-title">{{ current ? current.name : '儀表板' }}</h1>
            <p class="cc-desc">{{ readonly ? `「${current.createdBy}」分享給你的儀表板，只能檢視。` : '卡片拖標題列可以移動、拉右下角可以縮放，版面會自動存。' }}</p>
          </div>
          <!-- 唯讀分享 spec 7.6、7.8：只有擁有者打開自己的儀表板才有「分享」 -->
          <div class="d-flex ga-2">
          <v-btn v-if="current && !readonly" variant="tonal" prepend-icon="mdi-share-variant-outline" @click="sharing = true">分享</v-btn>
          <v-btn color="primary" prepend-icon="mdi-plus" @click="view = 'build'">新增儀表板</v-btn>
          </div>
        </header>
        <v-alert v-if="error" type="error" density="compact">{{ error }}</v-alert>
        <div class="cc-split">
          <aside class="cc-panel cc-sticky">
            <div class="cc-label px-4 pt-4 pb-1">我的儀表板 · {{ dashboards.length }}</div>
            <v-list nav class="dash-list px-2 pb-2" aria-label="我的儀表板">
              <v-list-item
                v-for="d in dashboards"
                :key="d.id"
                :title="d.name"
                :active="current?.id === d.id"
                prepend-icon="mdi-view-dashboard-outline"
                @click="open(d.id)"
              >
                <template #append>
                  <v-btn
                    icon="mdi-delete-outline"
                    size="small"
                    density="default"
                    variant="text"
                    aria-label="刪除儀表板"
                    @click.stop="askDelete(d)"
                  />
                </template>
              </v-list-item>
              <p v-if="!dashboards.length" class="text-caption text-medium-emphasis pa-3">還沒有存過儀表板</p>
            </v-list>
            <!-- 唯讀分享 spec 7.7：分享給我的，副標是擁有者，沒有刪除鈕；沒有就整段不顯示 -->
            <template v-if="shared.length">
              <div class="cc-label px-4 pt-4 pb-1">分享給我的 · {{ shared.length }}</div>
              <v-list nav class="dash-list px-2 pb-2" aria-label="分享給我的">
                <v-list-item
                  v-for="d in shared"
                  :key="d.id"
                  :title="d.name"
                  :subtitle="`來自 ${d.owner}`"
                  :active="current?.id === d.id"
                  prepend-icon="mdi-view-dashboard-outline"
                  @click="open(d.id)"
                />
              </v-list>
            </template>
          </aside>
          <div class="dash-main">
            <!-- spec 10.2：換一份儀表板就整個重建，卡片不會從上一份的位置滑過來；預覽裡的 DashboardView 不加 key -->
            <!-- 移除單張圖存檔後，換成後端回傳的設定；同一份儀表板，key 不變、不重建 -->
            <DashboardView v-if="current" :key="current.id" :config="current" :readonly="readonly" @updated="(c) => (current = c)" />
            <div v-else class="cc-panel cc-empty empty-start">
              <div class="cc-empty__icon"><v-icon icon="mdi-view-dashboard-outline" size="30" /></div>
              <h2>{{ dashboards.length || shared.length ? '從左邊選一份儀表板' : '還沒有儀表板' }}</h2>
              <ol v-if="!dashboards.length && !shared.length" class="empty-steps">
                <li>到「資料」上傳 CSV / Excel，或註冊外部 API，順手設好欄位的顯示名稱。</li>
                <li>到「製圖」選來源、挑欄位、寫一句需求，按「分析」。</li>
                <li>預覽滿意就存成儀表板，會出現在這一頁。</li>
              </ol>
              <div class="d-flex ga-2">
                <v-btn prepend-icon="mdi-database-outline" @click="view = 'data'">前往資料</v-btn>
                <v-btn color="primary" prepend-icon="mdi-chart-box-plus-outline" @click="view = 'build'">開始製圖</v-btn>
              </div>
            </div>
          </div>
        </div>
      </section>
    </v-main>

    <v-dialog :model-value="!!confirmDelete" max-width="420" @update:model-value="(v) => !v && (confirmDelete = null)">
      <v-card v-if="confirmDelete">
        <v-card-title class="pt-5 px-6">刪除儀表板</v-card-title>
        <v-card-text class="px-6">確定刪除「{{ confirmDelete.name }}」？刪除後無法復原。</v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <!-- v-card-actions 預設把按鈕變成 text，這裡明確用 flat，刪除才看得出是按鈕 -->
          <v-btn variant="flat" @click="confirmDelete = null">取消</v-btn>
          <v-btn variant="flat" color="error" @click="confirmRemove">刪除</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <ReloginDialog v-model="relogin" :me="me" @done="afterRelogin" />
    <ShareDialog v-if="current" v-model="sharing" :dashboard="current" />
    </template>
  </v-app>
</template>

<style scoped>
/* 頂部導覽列：畫布色半透明加霧化，黏在最上面，高度是 --tc-bar-h（sticky 面板照它讓位）。
   工作區是外框膠囊，選中的換成實心膠囊、前面一顆橘點 */
.cc-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 12px;
  height: var(--tc-bar-h);
  padding: 0 32px;
  background: var(--tc-bar-bg);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--tc-border-subtle);
}
.cc-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-right: 12px;
  font: 400 15px/1 var(--tc-display);
  letter-spacing: 0.06em;
  color: var(--tc-text);
}
/* 標誌兩套主題都用亮橘，長條固定深色 */
.cc-logo svg { color: rgb(var(--tc-accent-rgb)); }
.cc-topbar__nav { display: flex; gap: 8px; min-width: 0; overflow-x: auto; }
/* 帳號鈕和主題鈕放在最右邊（唯讀分享 spec 7.3） */
.cc-topbar__account { margin-left: auto; }
.cc-topbar__item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  height: 40px;
  padding: 0 20px;
  border: 1px solid var(--tc-border-subtle);
  border-radius: 999px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--tc-text-2);
  transition: color 0.12s ease, background-color 0.12s ease, border-color 0.12s ease;
}
.cc-topbar__item:hover { color: var(--tc-text); background: var(--tc-hover); border-color: var(--tc-border); }
.cc-topbar__item:focus-visible { outline: 2px solid var(--tc-ring-color); outline-offset: 2px; }
.cc-topbar__item.is-active { color: var(--tc-on-cta); background: var(--tc-cta); border-color: transparent; }
.cc-topbar__item.is-active::before {
  content: "";
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgb(var(--tc-accent-rgb));
}
@media (max-width: 960px) {
  .cc-topbar { padding: 0 16px; }
}
/* 手機寬度：收掉標誌文字，主題切換只留圖示（aria-label 還在），三個工作區才放得下 */
@media (max-width: 600px) {
  .cc-topbar { gap: 8px; }
  .cc-logo { margin-right: 0; }
  .cc-logo span,
  .cc-topbar__account span,
  .cc-topbar__theme span { display: none; }
  .cc-topbar__item { padding: 0 14px; }
  .cc-topbar__account, .cc-topbar__theme { padding: 0 10px; }
}

.dash-list { overflow-y: auto; }
.dash-main { min-width: 0; }
.empty-start { min-height: 420px; }
.empty-steps {
  margin: 0;
  padding-left: 20px;
  text-align: left;
  line-height: 1.9;
  color: var(--tc-text-2);
}
.boot { min-height: 100vh; display: grid; place-items: center; }
</style>
