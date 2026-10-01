<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useTheme } from 'vuetify'
import { ChartBuilder, DashboardView, DatasourcePanel, api, onUnauthorized } from './modules/dashboard/index.js'
import { LoginPage, ReloginDialog } from './modules/auth/index.js'
import { restoreSavedHash } from './modules/auth/session.js'

const VIEWS = [
  { id: 'data', label: '資料', icon: 'mdi-database-outline' },
  { id: 'build', label: '製圖', icon: 'mdi-chart-box-plus-outline' },
  { id: 'dashboards', label: '儀表板', icon: 'mdi-view-dashboard-outline' },
]
const view = ref('dashboards')
// 資料頁選中的來源
const datasourceId = ref(null)
// 資料頁按「用這個來源製圖」：每次給新物件，製圖頁才認得出是新的一次
const buildRequest = ref(null)

const dashboards = ref([])
const current = ref(null)
const error = ref('')
const confirmDelete = ref(null)

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
  dashboards.value = await api.listDashboards()
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
}

// spec 7.2：重新登入的是同一個帳號；剛才失敗的動作不會自動重送
function afterRelogin() {
  relogin.value = false
  reloadDashboards()
  if (current.value) open(current.value.id)
}

async function open(id) {
  try {
    current.value = await api.getDashboard(id)
    error.value = ''
  } catch (e) {
    error.value = e.message
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
    if (current.value?.id === d.id) current.value = null
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
  restoreSavedHash()
  boot()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <v-app>
    <!-- 唯讀分享 spec 7.1：開機先確認登入。下面的既有標記刻意不重排縮排，跟 feat/industrial-theme 合併時衝突才少 -->
    <div v-if="!me && !needLogin && !bootError" class="boot"><v-progress-circular indeterminate color="primary" /></div>
    <LoginPage v-else-if="!me" :error="bootError" :is-light="isLight" @logged-in="boot" @retry="boot" @toggle-theme="toggleTheme" />
    <template v-else>
    <v-navigation-drawer permanent width="76" color="transparent" class="cc-rail">
      <div class="cc-logo" aria-hidden="true">JEV</div>
      <nav class="cc-rail__nav" aria-label="主要導覽">
        <button
          v-for="(v, n) in VIEWS"
          :key="v.id"
          type="button"
          class="cc-rail__item"
          :class="{ 'is-active': view === v.id }"
          :aria-current="view === v.id ? 'page' : undefined"
          :aria-keyshortcuts="`Alt+${n + 1}`"
          :title="`${v.label}（Alt+${n + 1}）`"
          @click="view = v.id"
        >
          <v-icon :icon="v.icon" size="22" />
          <span>{{ v.label }}</span>
        </button>
      </nav>
      <button
        type="button"
        class="cc-rail__item cc-rail__theme"
        :aria-label="isLight ? '切換成深色主題' : '切換成淺色主題'"
        :title="isLight ? '切換成深色主題' : '切換成淺色主題'"
        @click="toggleTheme"
      >
        <v-icon :icon="isLight ? 'mdi-weather-night' : 'mdi-white-balance-sunny'" size="22" />
        <span>{{ isLight ? '深色' : '淺色' }}</span>
      </button>
    </v-navigation-drawer>

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
            <p class="cc-desc">卡片拖標題列可以移動、拉右下角可以縮放，版面會自動存。</p>
          </div>
          <v-btn color="primary" prepend-icon="mdi-plus" @click="view = 'build'">新增儀表板</v-btn>
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
          </aside>
          <div class="dash-main">
            <!-- spec 10.2：換一份儀表板就整個重建，卡片不會從上一份的位置滑過來；預覽裡的 DashboardView 不加 key -->
            <!-- 移除單張圖存檔後，換成後端回傳的設定；同一份儀表板，key 不變、不重建 -->
            <DashboardView v-if="current" :key="current.id" :config="current" @updated="(c) => (current = c)" />
            <div v-else class="cc-panel cc-empty empty-start">
              <div class="cc-empty__icon"><v-icon icon="mdi-view-dashboard-outline" size="30" /></div>
              <h2>{{ dashboards.length ? '從左邊選一份儀表板' : '還沒有儀表板' }}</h2>
              <ol class="empty-steps">
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
    </template>
  </v-app>
</template>

<style scoped>
/* 左側導覽列：半透明底、右邊一條細線；選中的項目左邊一條發光的線 */
.cc-rail {
  border-right: 1px solid var(--tc-border-subtle) !important;
  background: var(--tc-rail-bg) !important;
  backdrop-filter: blur(12px);
}
.cc-rail :deep(.v-navigation-drawer__content) {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  padding: 18px 0;
}
.cc-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font: 700 12px/1 var(--tc-mono);
  letter-spacing: 0.04em;
  color: #031018; /* 標誌兩套主題都用亮色漸層，字固定深色 */
  background: linear-gradient(135deg, #5ee6f7, #22d3ee 40%, #818cf8);
  box-shadow: 0 0 28px -6px rgba(var(--tc-accent-rgb), 0.8);
}
.cc-rail__nav { display: flex; flex-direction: column; gap: 6px; }
/* 主題切換放在導覽列最下面 */
.cc-rail__theme { margin-top: auto; }
.cc-rail__item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 60px;
  padding: 10px 0 8px;
  border-radius: 10px;
  font-size: 11.5px;
  color: var(--tc-muted);
  transition: color 0.12s ease, background-color 0.12s ease;
}
.cc-rail__item:hover { color: var(--tc-text); background: var(--tc-hover); }
.cc-rail__item:focus-visible { outline: 2px solid var(--tc-ring-color); outline-offset: 2px; }
.cc-rail__item.is-active { color: var(--tc-primary); background: var(--tc-active); }
.cc-rail__item.is-active::before {
  content: "";
  position: absolute;
  left: -8px;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--tc-primary);
  box-shadow: 0 0 12px var(--tc-primary);
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
