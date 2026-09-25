# JEV 儀表板 Tactile Clean 外觀與通用動畫 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把前端從 Vuetify 預設的 Material 樣式換成 Tactile Clean，並補上 spec 第 4 節列的通用動畫（按鈕按壓、骨架、圖表動畫調校、分批進場、拖拉抬起、對話框轉場、標籤淡入、減少動態效果）。

**Architecture:** App 層（`frontend/src/styles/` 加 `main.js`）負責接上 Tactile CSS、覆寫 Vuetify 元件、設定 `createVuetify` 的 defaults 和 theme。`modules/dashboard/` 只用 `tc-*` class 和 `--tc-*` 變數，而且每個變數都寫 fallback；模組內的動畫集中在 `motion.css`。圖表的主題和動畫設定由 `ChartCard` 加在 `toOption` 結果的外層，`charts/*.js` 不動。

**Tech Stack:** Vue 3.5、Vuetify 3.13.5（`vite-plugin-vuetify` autoImport）、ECharts 6.1 + vue-echarts 8、grid-layout-plus 1.1、Vitest 5 + @vue/test-utils + happy-dom。

**Spec：** `docs/superpowers/specs/2026-09-25-jev-dashboard-tactile-clean-design.md`（下稱 spec）

## 跟 spec 不一樣的地方（寫計畫時查到的）

1. 兩個 class 撞名，要改名。`tactile-clean.css` 本身就定義了 `.tc-grid`（`display: grid` 兩欄）和 `.tc-toast`（`min-width: 288px`、2px 框線，還有一段帶 `translateX(-50%)` 的進場動畫）。模組現在也用這兩個名字：`DashboardView` 的圖表區外框叫 `.tc-grid`，「復原」提示叫 `.tc-toast`。直接接上 CSS，圖表區會被切成兩欄，復原提示也會歪掉。Task 1 把它們改名為 `dash-grid` 和 `undo-toast`，轉場名稱和外框一起改成 `undo-toast`、`undo-toast-region`。
2. `main.js` 的 import 順序。spec 3.1 規定 `vuetify/styles` → `tactile-clean.css` → `vuetify-overrides.css`，照做；spec 沒寫的是 `import App` 要排在兩份 Tactile 樣式前面。原因是 `vite-plugin-vuetify` 的 autoImport 讓 `VBtn.css` 這些元件樣式隨著 App 的 import 樹一起載入；App 排在後面的話，元件樣式會比覆寫檔晚出現，權重相同的規則就被蓋回 Vuetify 的值。
3. `v-main` 的底色不用另外寫。theme 的 `background` 設成 `#f1f5f9`，剛好等於 `--tc-canvas`，`.v-application` 本來就吃這個色，`v-main` 則是透明的。Task 7 會在畫面上確認。
4. 全螢幕對話框的卡片不套卡片外觀。覆寫 `.v-card` 會連預覽的全螢幕 `v-card` 一起加上框線和 8px 圓角，要另外寫一條例外，還原成無框、無圓角、無陰影。
5. 進場動畫的 fill mode 從 `both` 改成 `backwards`。已存儀表板的每張卡片都會帶著 `tc-card-enter`（spec 4.4）。如果用 `both`，動畫播完之後還會一直壓在 `transform` 上，拖拉時要往上抬 2px（spec 4.5）的效果就出不來。改成 `backwards` 後，延遲期間停在 `from`，播完就放掉。

## Global Constraints

- 不加任何新套件。全部用 CSS、Vue `<Transition>`、Vuetify 的 `transition` prop 和 ECharts 設定。（spec 第 2 節）
- 只有淺色一套 theme，色值：background `#f1f5f9`、surface `#ffffff`、primary `#005caf`、error `#cb1b45`、success `#166534`、warning `#92400e`、info `#004888`。不要用 `vuetify.md` 範例的 `#2563eb`、`#dc2626`。（spec 3.1）
- `src/styles/` 只有 `main.js` 引用。`modules/dashboard/` 不 import `src/styles/` 的任何檔案，只用 `tc-*` class 和 `--tc-*` 變數，寫 `var(--tc-x, 後備值)`。（spec 3.1）
- 模組自己的 class 名稱不能跟 `tactile-clean.css` 定義的 class 撞名；刻意使用的只有 `tc-badge`、`tc-badge--info|warn|err`、`tc-tag`、`tc-skeleton`。檢查指令在 Task 1 Step 7。
- 介面動畫 ≤ 200ms；圖表初次繪製 300ms，資料更新 250ms；只動 `transform` 和 `opacity`，抬起感用 `box-shadow`；會一直循環的只有骨架和按鈕轉圈。（spec 第 4 節）
- `:hover`、`:active`、`:focus` 底下不能改版面屬性（margin、padding、width、height、border-width、display、position…）。`check_tactile_css.py` 會擋。
- `charts/*.js` 不改，`charts.test.js` 的快照不變。（spec 3.7、4.3）
- Python 用 `python`，不要用 `python3`。丟棄輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 所有指令從 `frontend/` 執行，除非另外註明。
- commit 訊息結尾加上：`Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

## 檔案結構

| 檔案 | 責任 | Task |
|---|---|---|
| `frontend/src/styles/tactile-clean.css` | 新增，從 skill 原樣複製，檔頭加一行來源註解 | 1 |
| `frontend/src/styles/vuetify-overrides.css` | 新增：字型、按鈕、卡片、輸入框覆寫，spec 4.1、4.9 | 1 |
| `frontend/src/main.js` | 樣式載入順序、`createVuetify` 的 defaults 和 theme | 1 |
| `frontend/src/App.vue` | 側欄 `color="background"`、刪除鈕改 `size="small"` | 1 |
| `frontend/src/App.test.js` | 側欄底色、刪除鈕尺寸 | 1 |
| `frontend/src/modules/dashboard/DashboardView.vue` | `tc-grid` 改名（1）；格線間距、進場對象、畫面順序（4） | 1, 4 |
| `frontend/src/modules/dashboard/AnalyzeButton.vue` | `tc-toast` 改名（1）；對話框轉場、工具列、「調整」、復原提示外觀（5） | 1, 5 |
| `frontend/src/modules/dashboard/motion.css` | 改名（1）；spec 4.2、4.4 fill、4.5、4.6、4.7、減少動態效果（6） | 1, 6 |
| `frontend/src/modules/dashboard/ChartCard.vue` | 標題列、badge、tag、骨架、明細表數字（2）；主題與 option 包裝（3） | 2, 3 |
| `frontend/src/modules/dashboard/charts/themes/tactile-light.json` | 新增，從 skill 複製 | 3 |
| `frontend/src/modules/dashboard/DatasourcePanel.vue` | spec 4.8 | 5 |
| `docs/backlog.md` | 深色模式、刪除確認對話框改版，必要時加上散點圖標籤 | 7 |

---

### Task 1: App 層樣式接線，並把撞名的 class 改掉

**Files:**
- Create: `frontend/src/styles/tactile-clean.css`
- Create: `frontend/src/styles/vuetify-overrides.css`
- Modify: `frontend/src/main.js`（整檔）
- Modify: `frontend/src/App.vue`（`v-navigation-drawer` 那行、刪除鈕的 `size`）
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`、`AnalyzeButton.vue`、`motion.css`（只做改名）
- Test: `frontend/src/App.test.js`、`DashboardView.test.js`、`AnalyzeButton.test.js`（只改選擇器）

**Interfaces:**
- Consumes: 無
- Produces:
  - 全域 Vuetify defaults：`VBtn` 預設 `variant: 'flat'`、`ripple: false`；輸入框預設 `variant: 'outlined'`。之後的 task 寫 `<v-btn>` 不帶 `color` 就是 Secondary 外觀，帶 `color="primary"` 就是 Primary。
  - 覆寫檔讓 `.v-card` 帶 `box-shadow: var(--tc-panel-shadow) !important`。Task 6 的拖拉抬起要用 `!important` 加上更高的權重才蓋得過。
  - 圖表區外框 class：`dash-grid`（原 `tc-grid`）。復原提示：`undo-toast`、`undo-toast-region`，轉場名稱 `undo-toast`（原 `tc-toast`、`tc-toast-region`）。
  - 載入順序：模組的 CSS（含 `motion.css`、元件的 scoped style）比 `tactile-clean.css` 早載入。Task 6 蓋 `tactile-clean.css` 的規則時，權重必須比它高。

- [ ] **Step 1: 寫失敗的測試（App.test.js）**

在 `frontend/src/App.test.js` 的 `describe('App', …)` 裡，第一個 `it` 前面加：

```js
  it('side drawer shares the canvas colour, and the delete button is size small so it is big enough to hit', async () => {
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    expect(wrapper.get('.v-navigation-drawer').classes()).toContain('bg-background')
    expect(findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]').classes()).toContain('v-btn--size-small')
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/App.test.js`
Expected: FAIL，新測試找不到 `bg-background`

- [ ] **Step 3: 改 App.vue**

`frontend/src/App.vue`：

```html
    <v-navigation-drawer permanent width="380">
```
改成
```html
    <!-- spec 3.3：側欄跟主區同底色，只靠右框線分開 -->
    <v-navigation-drawer permanent width="380" color="background">
```

刪除鈕的 `size="x-small"` 改成 `size="small"`（x-small 只有 20px 高，不到 24×24）：

```html
            <v-btn
              icon="mdi-delete"
              size="small"
              variant="text"
              aria-label="刪除儀表板"
              @click.stop="askDelete(d)"
            />
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/App.test.js`
Expected: PASS

- [ ] **Step 5: 複製 tactile-clean.css**

從 repo 根目錄執行（`.claude/` 在 gitignore 裡，一定要複製過來，不能引用 skill 的路徑）：

```bash
mkdir -p frontend/src/styles
{ echo '/* 來源：.claude/skills/modern-erp-ui/assets/tactile-clean.css，2026-09-25 原樣複製。skill 更新時整檔替換，不要在這裡改。 */'; cat .claude/skills/modern-erp-ui/assets/tactile-clean.css; } > frontend/src/styles/tactile-clean.css
```

確認：`diff <(tail -n +2 frontend/src/styles/tactile-clean.css) .claude/skills/modern-erp-ui/assets/tactile-clean.css` 沒有任何輸出。

- [ ] **Step 6: 改掉撞名的 class**

在 `frontend/` 執行：

```bash
sed -i 's/tc-grid/dash-grid/g' src/modules/dashboard/DashboardView.vue src/modules/dashboard/DashboardView.test.js src/modules/dashboard/motion.css
sed -i 's/tc-toast/undo-toast/g' src/modules/dashboard/AnalyzeButton.vue src/modules/dashboard/AnalyzeButton.test.js src/modules/dashboard/motion.css
grep -rn "tc-grid\|tc-toast" src/modules/dashboard
```

Expected：`grep` 沒有輸出。`motion.css` 裡應該出現 `.dash-grid`、`.dash-grid.is-busy`、`.undo-toast-enter-active`、`.undo-toast-leave-active`、`.undo-toast-enter-from`、`.undo-toast-leave-to`；`AnalyzeButton.vue` 應該出現 `class="undo-toast-region"`、`<Transition name="undo-toast">`、`class="undo-toast"`，scoped style 裡是 `.undo-toast-region`、`.undo-toast`。

接著在 `AnalyzeButton.vue` 的 `<style scoped>` 開頭，也就是 `/* spec 3.2：預覽畫面下方中央…` 那段上面，加一行註解：

```css
/* 不叫 tc-toast：tactile-clean.css 有同名的 class（fixed 定位、translateX(-50%)），會把這個提示拉歪 */
```

在 `motion.css` 的 `/* 10.6：等 JEV 回應時圖表區變淡 */` 那行下面加：

```css
/* 不叫 tc-grid：tactile-clean.css 的 .tc-grid 是兩欄的 display: grid */
```

- [ ] **Step 7: 確認沒有撞名**

在 `frontend/` 執行：

```bash
node -e "
const fs = require('fs'), path = require('path')
const css = fs.readFileSync('src/styles/tactile-clean.css', 'utf8')
const defined = new Set([...css.matchAll(/\.((?:tc|is)-[\w-]+)/g)].map((m) => m[1]))
const allowed = new Set(['tc-badge', 'tc-badge--info', 'tc-badge--warn', 'tc-badge--err', 'tc-tag', 'tc-skeleton'])
const dir = 'src/modules/dashboard'
const used = new Set(fs.readdirSync(dir).filter((f) => /\.(vue|css)$/.test(f))
  .flatMap((f) => [...fs.readFileSync(path.join(dir, f), 'utf8').matchAll(/(?<![\w-])((?:tc|is)-[\w-]+)/g)].map((m) => m[1])))
const hit = [...used].filter((c) => defined.has(c) && !allowed.has(c))
console.log(hit.length ? 'COLLIDE: ' + hit.join(', ') : 'no collisions')
"
```

Expected: `no collisions`

- [ ] **Step 8: 寫 vuetify-overrides.css**

Create `frontend/src/styles/vuetify-overrides.css`：

```css
/* Vuetify 元件的 Tactile Clean 覆寫。寫法參考 .claude/skills/modern-erp-ui/references/vuetify.md，
   依 spec 3.2–3.4、4.1、4.9 調整。main.js 要在 tactile-clean.css 之後、App 的元件樣式之後載入這份。 */

.v-application {
  font-family: var(--tc-font);
  -webkit-font-smoothing: antialiased;
}

/* ---------- 按鈕 ---------- */
.v-btn {
  --tc-btn-edge: var(--tc-border-strong);
  --tc-btn-press: inset 0 1px 2px rgba(0, 0, 0, 0.12); /* 白底吃不住深內凹 */
  border-radius: var(--tc-radius) !important;
  text-transform: none !important;
  letter-spacing: normal !important;
  font-weight: 500;
  /* spec 4.1：transform 不加轉場。Vuetify 的 .v-btn 預設對 transform 有 0.28s 轉場，整條換掉 */
  transition: background-color 0.08s ease, border-color 0.08s ease, box-shadow 0.06s ease !important;
}

/* 位移取代 ripple；hover 的 overlay 一起拿掉，底色改由下面各自的 :hover 給 */
.v-btn .v-btn__overlay,
.v-btn .v-ripple__container {
  display: none !important;
}

.v-btn:focus-visible {
  outline: 3px solid var(--tc-ring-color);
  outline-offset: 1px;
}

/* Secondary：沒帶 color 的 flat 按鈕 */
.v-btn--variant-flat {
  background: var(--tc-panel);
  color: var(--tc-text-2);
  border: 1px solid var(--tc-border) !important;
  box-shadow: var(--tc-bevel), 0 2px 0 var(--tc-btn-edge), var(--tc-drop) !important;
}
.v-btn--variant-flat:hover {
  background: var(--tc-subtle);
  color: var(--tc-text);
}
/* spec 4.1：按下去整顆下沉 2px，厚度歸零，外影翻成內凹 */
.v-btn--variant-flat:active {
  background: var(--tc-active);
  transform: translateY(2px);
  box-shadow: 0 0 0 var(--tc-btn-edge), var(--tc-btn-press) !important;
}

/* Primary：底色自己寫漸層，不交給 Vuetify 算（spec 3.2）。
   .bg-primary 的 background-color 帶 !important，漸層走 background-image 疊在上面 */
.v-btn--variant-flat.bg-primary {
  --tc-btn-edge: var(--tc-primary-edge);
  --tc-btn-press: var(--tc-press);
  background-image: linear-gradient(180deg, var(--tc-primary-fill-hi), var(--tc-primary-fill)) !important;
  color: #fff !important;
  border-color: var(--tc-primary-lo) !important;
}
.v-btn--variant-flat.bg-primary:hover {
  background-image: linear-gradient(180deg, var(--tc-primary-fill), var(--tc-primary-lo)) !important;
}

/* Danger */
.v-btn--variant-flat.bg-error {
  --tc-btn-edge: var(--tc-danger-edge);
  --tc-btn-press: var(--tc-press);
  border-color: var(--tc-danger-lo) !important;
}
.v-btn--variant-flat.bg-error:hover {
  background-color: var(--tc-danger-lo) !important;
}

/* 停用的按鈕不留鍵帽厚度，看起來就不能按 */
.v-btn--disabled {
  box-shadow: none !important;
}

/* 文字按鈕、圖示按鈕：沒有厚度，按下去只沉 1px */
.v-btn--variant-text,
.v-btn--variant-plain,
.v-btn--variant-outlined {
  box-shadow: none !important;
}
.v-btn--variant-text:hover {
  background-color: var(--tc-hover);
}
.v-btn--variant-text:active,
.v-btn--variant-plain:active {
  transform: translateY(1px);
}

/* ---------- 卡片（spec 3.3） ---------- */
.v-card {
  border: 1px solid var(--tc-border-subtle) !important;
  border-radius: var(--tc-radius-lg) !important;
  box-shadow: var(--tc-panel-shadow) !important;
}
/* 全螢幕的預覽對話框是整個畫面，不是一張卡片 */
.v-dialog--fullscreen > .v-overlay__content > .v-card {
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
}

/* ---------- 輸入框（spec 3.4）：內凹；focus 走 3px outline，不用 Material 的加粗框線 ---------- */
.v-field--variant-outlined {
  border-radius: var(--tc-radius);
  box-shadow: var(--tc-carve);
}
.v-field--variant-outlined .v-field__outline {
  --v-field-border-opacity: 1;
  color: var(--tc-border);
}
.v-field--variant-outlined:hover .v-field__outline {
  color: var(--tc-border-strong);
}
.v-field--variant-outlined.v-field--focused {
  outline: 3px solid var(--tc-ring-color);
  outline-offset: 0;
}
.v-field--variant-outlined.v-field--focused .v-field__outline {
  --v-field-border-width: 1px;
  color: var(--tc-primary);
}
/* 要寫在 focused 後面：有錯又在 focus 時，框線維持錯誤色 */
.v-field--variant-outlined.v-field--error .v-field__outline {
  color: var(--tc-err);
}

/* ---------- 減少動態效果（spec 4.9） ----------
   tactile-clean.css 檔尾只把時長縮成 .01ms，沒有停掉循環，也沒有清掉延遲：
   骨架每一幀落在隨機位置，看起來就是閃爍。這裡補上。 */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-iteration-count: 1 !important;
    animation-delay: 0s !important;
    transition-delay: 0s !important;
  }
}
```

- [ ] **Step 9: 改 main.js**

`frontend/src/main.js` 整檔換成：

```js
import { createApp } from 'vue'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
// App 要排在兩份 Tactile 樣式前面：vite-plugin-vuetify 的 autoImport 讓 VBtn.css 這些元件樣式跟著 App 的 import 進來，
// App 排在後面的話，元件樣式會比覆寫檔晚載入，同權重的規則就被蓋回去
import App from './App.vue'
import './styles/tactile-clean.css'
import './styles/vuetify-overrides.css'

const vuetify = createVuetify({
  defaults: {
    global: { density: 'compact' },
    VBtn: { variant: 'flat', ripple: false },
    VListItem: { ripple: false },
    VTab: { ripple: false },
    VCheckbox: { ripple: false },
    VTextField: { variant: 'outlined' },
    VTextarea: { variant: 'outlined' },
    VFileInput: { variant: 'outlined' },
  },
  // spec 3.1：只有淺色一套，色值取自 tactile-clean.css 的 :root；改那邊的色盤要回來同步
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          background: '#f1f5f9',
          surface: '#ffffff',
          primary: '#005caf',
          error: '#cb1b45',
          success: '#166534',
          warning: '#92400e',
          info: '#004888',
        },
      },
    },
  },
})

createApp(App).use(vuetify).mount('#app')
```

- [ ] **Step 10: 跑 CSS 檢查、全部測試和 build**

從 repo 根目錄：

```bash
python .claude/skills/modern-erp-ui/scripts/check_tactile_css.py frontend/src/styles/vuetify-overrides.css
```
Expected: 通過（exit code 0，沒有列出違規）

從 `frontend/`：

```bash
npm test
npm run build
```
Expected: 全部 PASS（改名後的 `.dash-grid`、`.undo-toast` 選擇器都找得到）；build 成功

- [ ] **Step 11: Commit**

```bash
git add frontend/src/styles frontend/src/main.js frontend/src/App.vue frontend/src/App.test.js frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js frontend/src/modules/dashboard/motion.css
git commit -m "feat(frontend): wire Tactile Clean CSS and Vuetify overrides; rename classes that clash with it

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: ChartCard 外觀（標題列、標籤、骨架、明細表數字）

**Files:**
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`

**Interfaces:**
- Consumes: Task 1 的 `tactile-clean.css`（只在執行時需要，測試不載 CSS）
- Produces（Task 6 的 `motion.css` 會用到）：
  - 骨架元素：`<div class="tc-skeleton card-skeleton" aria-hidden="true">`
  - 淡入 class `tc-fade-in`：掛在異常標籤、卡片裡的 `v-alert`、`v-table` 上
  - 標題列 class：`card-drag tc-card-title`（`card-drag` 仍是拖拉把手）

- [ ] **Step 1: 寫失敗的測試**

`frontend/src/modules/dashboard/ChartCard.test.js`：

把 `it('shows a loading message while data is null', …)` 整段換成：

```js
  it('while data is null the body shows an aria-hidden skeleton and says aria-busy; both go once data arrives', async () => {
    const card = mountCard({ spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }, data: null, highlight: null })
    expect(card.get('.tc-skeleton').attributes('aria-hidden')).toBe('true')
    expect(card.get('.card-body').attributes('aria-busy')).toBe('true')

    await card.setProps({ data: { chartId: 'c1', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 1]] } })
    expect(card.find('.tc-skeleton').exists()).toBe(false)
    expect(card.get('.card-body').attributes('aria-busy')).toBeUndefined()
  })
```

把 `it('shows the severity chip when a highlight is present, and none when it is null', …)` 整段換成：

```js
  it('severity badge: each level maps to its tc-badge modifier (正常波動 stays plain grey), fades in, and there is none without a highlight', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const expected = { 正常波動: [], 值得注意: ['tc-badge--info'], 需要關注: ['tc-badge--warn'], 嚴重警示: ['tc-badge--err'] }
    for (const [severity, modifiers] of Object.entries(expected)) {
      const badge = mountCard({ spec, data: null, highlight: { chartId: 'c1', severity, noul: 0.9 } }).get('.tc-badge')
      expect(badge.text()).toBe(severity)
      expect(badge.classes().filter((c) => c.startsWith('tc-badge--'))).toEqual(modifiers)
      expect(badge.classes()).toContain('tc-fade-in')
    }
    expect(mountCard({ spec, data: null, highlight: null }).find('.tc-badge').exists()).toBe(false)
  })
```

在檔尾的 `})`（關掉 `describe`）前面加：

```js
  it('符合需求 is a neutral tc-tag, not a chip', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {}, options: { focus: true } },
      data: null,
      highlight: null,
    })
    expect(card.get('.tc-tag').text()).toBe('符合需求')
    expect(card.find('.v-chip').exists()).toBe(false)
  })

  it('table: number cells get tc-num (tabular figures, right-aligned), text cells do not; the table fades in', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'table', title: 'T', mapping: { columns: ['region', 'amount'] } },
      data: { chartId: 'c1', type: 'table', dimensions: ['region', 'amount'], rows: [['北區', 1200]] },
      highlight: null,
    })
    const [text, num] = card.findAll('tbody td')
    expect(num.classes()).toContain('tc-num')
    expect(text.classes()).not.toContain('tc-num')
    expect(card.get('.v-table').classes()).toContain('tc-fade-in')
  })

  it('the FIELD_MISSING alert fades in', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'bar', error: 'FIELD_MISSING', missingField: 'profit' },
      highlight: null,
    })
    expect(card.get('.v-alert').classes()).toContain('tc-fade-in')
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js`
Expected: 5 個新測試或改過的測試 FAIL（找不到 `.tc-skeleton`、`.tc-badge`、`.tc-tag`、`tc-num`、`tc-fade-in`）

- [ ] **Step 3: 改 ChartCard.vue**

`<script setup>` 裡：

```js
const SEVERITY_COLOR = { 正常波動: 'success', 值得注意: 'info', 需要關注: 'warning', 嚴重警示: 'error' }
```
換成
```js
// spec 3.5：正常波動用中性灰、不加修飾子；Tactile 的規則是好消息不染綠
const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }
```

`<template>` 整段換成：

```html
<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag tc-card-title d-flex align-center">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <span v-if="spec.options?.focus" class="tc-tag mr-1">符合需求</span>
      <span v-if="highlight" class="tc-badge tc-fade-in" :class="SEVERITY_CLASS[highlight.severity]">
        {{ highlight.severity }}
      </span>
      <!-- grid-layout-plus 的 dragIgnoreFrom 預設是 'a, button'，按「✕」不會被當成拖拉 -->
      <v-btn
        v-if="removable && spec.type !== 'table'"
        icon="mdi-close"
        size="x-small"
        variant="text"
        class="tc-remove ml-1"
        aria-label="移除這張圖"
        :disabled="removeDisabled"
        @click="emit('remove')"
      />
    </v-card-title>
    <v-card-text class="flex-grow-1 card-body" :aria-busy="!data || undefined">
      <!-- spec 3.6：骨架撐滿內容區；資料一到就拿掉，不等動畫跑完 -->
      <div v-if="!data" class="tc-skeleton card-skeleton" aria-hidden="true" />
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact" class="tc-fade-in">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-alert v-else-if="unsupportedType" type="warning" density="compact" class="tc-fade-in">
        不支援的圖表類型：{{ spec.type }}
      </v-alert>
      <template v-else-if="table">
        <div v-if="data.truncated" class="text-caption text-medium-emphasis">僅顯示前 500 筆</div>
        <v-table density="compact" fixed-header height="100%" class="tc-fade-in">
          <thead>
            <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in table.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j" :class="{ 'tc-num': typeof cell === 'number' }">{{ cell }}</td>
            </tr>
          </tbody>
        </v-table>
      </template>
      <VChart v-else :option="option" autoresize />
    </v-card-text>
  </v-card>
</template>
```

`<style scoped>` 裡：

```css
.card-body { min-height: 0; }
```
換成
```css
/* spec 3.3：標題列同 .tc-card__header 的字級、字重和下框線 */
.tc-card-title {
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid var(--tc-border-subtle, rgba(var(--v-border-color), var(--v-border-opacity)));
}
.card-body { min-height: 0; padding: 8px 8px 0; }
/* spec 3.6：tactile 的 .tc-skeleton 預設只有 12px 高，這裡撐滿內容區；淡入時機在 motion.css */
.card-skeleton { height: 100%; min-height: 120px; border-radius: var(--tc-radius, 4px); }
/* spec 3.8：數字寬度一致，上下好比對 */
.tc-num { font-variant-numeric: tabular-nums; text-align: right; }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js`
Expected: 全部 PASS（`shows 符合需求 when options.focus is true` 這個舊測試是比對文字，照樣會過）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js
git commit -m "feat(frontend): ChartCard gets Tactile header, badges, tag, skeleton and tabular numbers

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: ChartCard 的圖表主題、數值格式和動畫調校

**Files:**
- Create: `frontend/src/modules/dashboard/charts/themes/tactile-light.json`
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`（script 的 import 和 `option` computed、`<VChart>` 那行）
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`

**Interfaces:**
- Consumes: `prefersReducedMotion()`，來自 `./motion.js`（`() => boolean`）；`toOption[type](data, mapping)`，回傳的 option 都有陣列形式的 `series`，`xAxis`、`yAxis` 如果有就是物件
- Produces: 傳給 `VChart` 的 `option` 和 `theme`（沒有其他 task 會用到）

- [ ] **Step 1: 複製主題檔**

從 repo 根目錄：

```bash
mkdir -p frontend/src/modules/dashboard/charts/themes
cp .claude/skills/modern-erp-ui/assets/echarts-theme-default.json frontend/src/modules/dashboard/charts/themes/tactile-light.json
```

JSON 裡不能寫註解。來源寫在 Step 4 的 import 註解。

- [ ] **Step 2: 寫失敗的測試**

`frontend/src/modules/dashboard/ChartCard.test.js`：

第 2 行 `import { describe, it, expect, vi } from 'vitest'` 改成

```js
import { describe, it, expect, vi, afterEach } from 'vitest'
```

在 `import { vuetify } from './test-vuetify.js'` 下面加：

```js
import tactileLight from './charts/themes/tactile-light.json'
```

`vue-echarts` 的 mock 裡，`props: ['option', 'autoresize'],` 改成 `props: ['option', 'autoresize', 'theme'],`

`function mountCard` 下面加：

```js
const barSpec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
const barData = { chartId: 'c1', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 1200000]] }
const chartOf = (card) => card.getComponent({ name: 'VChart' })
const optionFor = (type) => chartOf(mountCard({ spec: { ...barSpec, type }, data: { ...barData, type }, highlight: null })).props('option')
```

`describe('ChartCard', () => {` 下面第一行加：

```js
  afterEach(() => {
    vi.unstubAllGlobals()
  })
```

在檔尾的 `})`（關掉 `describe`）前面加：

```js
  it('passes the tactile theme; every series, the hover state and the tooltip get the tuned animation, and the tooltip keeps its settings', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'stacked_bar', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'stacked_bar', dimensions: ['month', 'region', 'amount'], rows: [['1月', '北區', 1], ['1月', '南區', 2]] },
      highlight: null,
    })
    expect(chartOf(card).props('theme')).toBe(tactileLight)
    const option = chartOf(card).props('option')
    expect(option.series).toHaveLength(2)
    for (const s of option.series) {
      expect(s).toMatchObject({ animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' })
    }
    expect(option.stateAnimation).toEqual({ duration: 120, easing: 'cubicOut' })
    expect(option.tooltip).toEqual({ trigger: 'axis', transitionDuration: 0 })
    expect(option.animation).toBeUndefined()
    expect(option.aria).toEqual({ enabled: true, decal: { show: true } })
  })

  it('bar and pie turn on aria decals too, since the theme has only 6 colours; line does not', () => {
    expect(optionFor('bar').aria).toEqual({ enabled: true, decal: { show: true } })
    expect(optionFor('pie').aria).toEqual({ enabled: true, decal: { show: true } })
    expect(optionFor('line').aria).toBeUndefined()
  })

  it('value axes label numbers in compact zh-TW form; category axes are left as they were', () => {
    const option = optionFor('bar')
    expect(option.yAxis.type).toBe('value')
    expect(option.yAxis.axisLabel.formatter(1200000)).toBe('120萬')
    expect(option.xAxis).toEqual({ type: 'category' })
  })

  it('reduced motion: the chart does not animate and the hover state is instant', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }))
    const option = optionFor('bar')
    expect(option.animation).toBe(false)
    expect(option.stateAnimation.duration).toBe(0)
  })
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js`
Expected: 4 個新測試 FAIL（`theme` 是 undefined、series 上沒有 `animationDuration`…）

- [ ] **Step 4: 改 ChartCard.vue**

`<script setup>` 的 import 區塊換成：

```js
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'
// 從 .claude/skills/modern-erp-ui/assets/echarts-theme-default.json 複製；放在模組裡，模組才不必依賴外面的檔案
import tactileLight from './charts/themes/tactile-light.json'
import { prefersReducedMotion } from './motion.js'
```

`const SEVERITY_CLASS = …` 那行下面加：

```js
// spec 3.7：主題只有 6 色，堆疊長條最多 10 個系列會撞色，用花紋區分
const DECAL_TYPES = new Set(['bar', 'pie', 'stacked_bar'])
// spec 4.3：一定要寫在 series 層。ECharts 6 的圓餅、折線在 series 預設裡有自己的時長和 easing，只寫在最外層蓋不掉
const SERIES_ANIMATION = { animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' }
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
const compactValueAxis = (axis) =>
  axis?.type === 'value' ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } } : axis

// 包在 toOption 結果的外層，charts/*.js 不動，快照不變；每次算 option 都重新問一次減少動態效果
function tactileOption(base, type) {
  const reduced = prefersReducedMotion()
  return {
    ...base,
    ...(reduced && { animation: false }),
    stateAnimation: { duration: reduced ? 0 : 120, easing: 'cubicOut' },
    tooltip: { ...base.tooltip, transitionDuration: 0 },
    ...(DECAL_TYPES.has(type) && { aria: { enabled: true, decal: { show: true } } }),
    ...(base.xAxis && { xAxis: compactValueAxis(base.xAxis) }),
    ...(base.yAxis && { yAxis: compactValueAxis(base.yAxis) }),
    series: base.series.map((s) => ({ ...s, ...SERIES_ANIMATION })),
  }
}
```

`option` computed 換成：

```js
const option = computed(() =>
  ready.value && props.spec.type !== 'table' && Object.hasOwn(toOption, props.spec.type)
    ? tactileOption(toOption[props.spec.type](props.data, props.spec.mapping), props.spec.type)
    : null,
)
```

template 裡的 `<VChart v-else :option="option" autoresize />` 換成：

```html
      <VChart v-else :option="option" :theme="tactileLight" autoresize />
```

- [ ] **Step 5: 跑測試，確認通過，快照不變**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js src/modules/dashboard/charts/charts.test.js`
Expected: 全部 PASS；`git status` 裡 `charts/__snapshots__/` 沒有變動

- [ ] **Step 6: Commit**

```bash
git add frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js frontend/src/modules/dashboard/charts/themes/tactile-light.json
git commit -m "feat(frontend): tactile ECharts theme, decals, compact value labels and tuned chart animation

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: DashboardView 的格線間距和分批進場

**Files:**
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`
- Test: `frontend/src/modules/dashboard/DashboardView.test.js`

**Interfaces:**
- Consumes: `motion.css` 裡現有的 `.vgl-item .tc-card-enter`（讀 `--tc-i`，每張延遲 `min(--tc-i, 5) × 30ms`）
- Produces: 已存儀表板第一次顯示時，每張卡片都帶 `tc-card-enter` class，`--tc-i` 是它在「依 `(y, x)` 排序的版面」裡的名次。Task 6 會把這個動畫的 fill mode 改成 `backwards`。

- [ ] **Step 1: 寫失敗的測試**

`frontend/src/modules/dashboard/DashboardView.test.js`：

`grid-layout-plus` mock 的 `GridLayout` 裡，`props: ['layout', 'colNum', 'rowHeight', 'isDraggable', 'isResizable'],` 改成

```js
    props: ['layout', 'colNum', 'rowHeight', 'isDraggable', 'isResizable', 'margin'],
```

測試名稱 `'cards that show up later get the enter class and their layout position for the stagger; nothing enters on the first display'` 改成

```js
  it('preview: nothing enters on the first display (the dialog has its own transition); cards that show up later enter, staggered after the kept cards', async () => {
```

（測試內容不變。）

在檔尾的 `})`（關掉 `describe`）前面加：

```js
  it('saved dashboard, first display: every card enters, staggered in on-screen (y, x) order rather than array order', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue([])
    // 陣列順序跟畫面順序不同：c1 在右上、c2 在左上，好比使用者拖過之後存的版面
    const config = { ...makeConfig(), layout: [bar('c1', 1), bar('c2', 0), table('c3', 4)] }
    const wrapper = mountView({ config, preview: false })
    await flushPromises()

    const card = (id) => wrapper.get(`[data-chart-id="${id}"]`)
    for (const id of ['c1', 'c2', 'c3']) expect(card(id).classes()).toContain('tc-card-enter')
    expect(['c2', 'c1', 'c3'].map((id) => card(id).element.style.getPropertyValue('--tc-i'))).toEqual(['0', '1', '2'])
  })

  it('cards sit 14px apart', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: makeConfig(), preview: true })
    await flushPromises()
    expect(wrapper.getComponent({ name: 'GridLayout' }).props('margin')).toEqual([14, 14])
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/DashboardView.test.js`
Expected: 兩個新測試 FAIL（已存儀表板的卡片沒有 `tc-card-enter`；`margin` 是 undefined）

- [ ] **Step 3: 改 DashboardView.vue**

`<script setup>` 裡，`const onlyTable = computed(…)` 下面加：

```js
// spec 4.4：錯開的順序照畫面位置（先 y 再 x）。使用者拖過之後，layout 陣列的順序不一定等於畫面順序
const screenOrder = computed(() =>
  Object.fromEntries([...layout.value].sort((a, b) => a.y - b.y || a.x - b.x).map((l, n) => [l.i, n])),
)
```

`load()` 裡：

```js
  // spec 10.4：第一次顯示不做進場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : [])
```
換成
```js
  // spec 4.4：已存的儀表板第一次顯示時每張都進場（App 用 :key 換儀表板，每次打開都是第一次）；
  // 預覽第一次顯示不進場，對話框自己有轉場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : props.preview ? [] : ids)
```

`<template>` 裡，`<GridLayout` 的屬性加上 `:margin="[14, 14]"`：

```html
    <GridLayout
      v-if="!loadError"
      v-model:layout="layout"
      :class="{ 'is-settled': settled }"
      :col-num="12"
      :row-height="80"
      :margin="[14, 14]"
      :is-draggable="!preview"
      :is-resizable="!preview"
      @layout-ready="onLayoutReady"
    >
```

`<GridItem v-for="(item, index) in layout"` 改成 `<GridItem v-for="item in layout"`。`ChartCard` 的 `:style` 那行改成：

```html
          :style="entering.has(item.i) ? { '--tc-i': screenOrder[item.i] } : undefined"
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/DashboardView.test.js`
Expected: 全部 PASS（包括原本的 `c5` → `'2'`、`c6` → `'3'`：`(y, x)` 排序依序是 c1、c2、c5、c6、c3）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js
git commit -m "feat(frontend): saved dashboards stagger their cards in on-screen order; 14px grid gaps

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 預覽對話框、工具列、「調整」按鈕、復原提示，以及資料源分頁

**Files:**
- Modify: `frontend/src/modules/dashboard/AnalyzeButton.vue`
- Modify: `frontend/src/modules/dashboard/DatasourcePanel.vue`（兩個 `v-window-item`）
- Test: `frontend/src/modules/dashboard/AnalyzeButton.test.js`、`DatasourcePanel.test.js`

**Interfaces:**
- Consumes: Task 1 改好的名字：`undo-toast`、`undo-toast-region`
- Produces: `v-dialog` 的 `transition="tc-sheet"`。Vue 會在 `.v-overlay__content` 上加 `tc-sheet-enter-active`、`tc-sheet-enter-from`、`tc-sheet-leave-active`、`tc-sheet-leave-to`，這幾個 class 由 Task 6 在 `motion.css` 裡定義。

- [ ] **Step 1: 寫失敗的測試**

`frontend/src/modules/dashboard/AnalyzeButton.test.js`：在 `describe('AnalyzeButton', …)` 裡、第一個 `it` 前面加（`api.analyze` 的回傳值照檔案裡其他測試的寫法，用 `layout4()` 組）：

```js
  it('Tactile look: the preview comes in as a sheet on the canvas colour, the toolbar is a surface bar, and only 存為我的儀表板 is primary inside it', async () => {
    api.analyze.mockResolvedValue({ name: 'n', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    expect(wrapper.findComponent({ name: 'VDialog' }).props('transition')).toBe('tc-sheet')
    expect(document.body.querySelector('.v-dialog .v-card').classList.contains('bg-background')).toBe(true)
    expect(document.body.querySelector('.v-dialog .v-toolbar').classList.contains('bg-surface')).toBe(true)
    // 「分析」在元件本身裡，wrapper 沒掛到 document.body，要從 wrapper 找；對話框的內容才會被 teleport 到 body
    expect(wrapper.get('button').classes()).toContain('bg-primary')
    expect(button('存為我的儀表板').classList.contains('bg-primary')).toBe(true)
    expect(button('調整').classList.contains('bg-primary')).toBe(false)
  })
```

`frontend/src/modules/dashboard/DatasourcePanel.test.js`：在 `describe('DatasourcePanel', …)` 的檔尾 `})` 前面加：

```js
  it('上傳檔案 and 外部 API are not pages in a sequence: switching swaps the content in place, without the window slide', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()
    const items = wrapper.findAllComponents({ name: 'VWindowItem' })
    expect(items.map((w) => [w.props('transition'), w.props('reverseTransition')])).toEqual([[false, false], [false, false]])
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/AnalyzeButton.test.js src/modules/dashboard/DatasourcePanel.test.js`
Expected: 兩個新測試 FAIL

- [ ] **Step 3: 改 AnalyzeButton.vue**

`<v-dialog v-model="open" fullscreen scrollable>` 到 `</v-toolbar>` 這段換成：

```html
  <!-- spec 4.6：往上浮入、往下淡出，取代 Vuetify 預設的「從 90% 放大」；轉場在 motion.css -->
  <v-dialog v-model="open" fullscreen scrollable transition="tc-sheet">
    <!-- spec 3.3：預覽的內容區用畫布底色，白卡片才不會貼在白底上 -->
    <v-card color="background">
      <v-toolbar title="儀表板預覽" color="surface" class="tc-preview-toolbar">
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
```

（`scrollable` 那行上面原本的註解 `<!-- scrollable：捲動的是 v-card-text… -->` 保留，放在新的 spec 4.6 註解上面。）

「調整」按鈕拿掉 `color="primary"`（spec 3.2：預覽裡只留一顆 Primary）：

```html
            <v-btn :loading="adjusting" :disabled="!canAdjust" @click="adjust">調整</v-btn>
```

`<style scoped>` 裡：

在 `.undo-toast-region { … }` 前面加：

```css
/* spec 3.3：工具列跟內容區用一條下框線分開 */
.tc-preview-toolbar {
  border-bottom: 1px solid var(--tc-border-subtle, rgba(var(--v-border-color), var(--v-border-opacity)));
}
```

`.undo-toast { … }` 裡的這四行：

```css
  border-radius: 4px;
  background: rgb(var(--v-theme-surface-variant));
  color: rgb(var(--v-theme-on-surface-variant));
  box-shadow: 0 3px 5px -1px rgba(0, 0, 0, 0.2), 0 6px 10px 0 rgba(0, 0, 0, 0.14);
```
換成
```css
  border-radius: 4px;
  /* spec 3.3：Tactile 的浮層外觀；沒有 App 層樣式時退回原本的深色提示 */
  border: 1px solid var(--tc-border, transparent);
  background: var(--tc-panel, rgb(var(--v-theme-surface-variant)));
  color: var(--tc-text, rgb(var(--v-theme-on-surface-variant)));
  box-shadow: var(--tc-overlay-shadow, 0 3px 5px -1px rgba(0, 0, 0, 0.2), 0 6px 10px 0 rgba(0, 0, 0, 0.14));
```

- [ ] **Step 4: 改 DatasourcePanel.vue**

```html
      <v-window-item value="upload">
```
改成
```html
      <!-- spec 4.8：兩個分頁不是前後頁，切換時直接換內容，不左右滑 -->
      <v-window-item value="upload" :transition="false" :reverse-transition="false">
```

```html
      <v-window-item value="api">
```
改成
```html
      <v-window-item value="api" :transition="false" :reverse-transition="false">
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/AnalyzeButton.test.js src/modules/dashboard/DatasourcePanel.test.js`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js frontend/src/modules/dashboard/DatasourcePanel.vue frontend/src/modules/dashboard/DatasourcePanel.test.js
git commit -m "feat(frontend): preview sheet transition and Tactile toolbar; 調整 is secondary; datasource tabs stop sliding

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: motion.css 裡模組自己的動畫

**Files:**
- Modify: `frontend/src/modules/dashboard/motion.css`

**Interfaces:**
- Consumes: Task 2 的 `.tc-skeleton.card-skeleton`、`.tc-fade-in`、`.card-drag`；Task 4 讓已存儀表板的卡片都帶 `tc-card-enter`；Task 5 的 `tc-sheet` 轉場名稱；Task 1 的覆寫檔讓 `.v-card` 帶 `box-shadow … !important`；grid-layout-plus 的 `.vgl-item--dragging`、`.vgl-item--resizing`、`.vgl-item--placeholder` 和 `--vgl-*` 變數（套件把這些變數定義在 `.vgl-layout` 上）
- Produces: 無（只有 CSS）

這個 task 沒有單元測試：測試環境不載 CSS。驗證方式是 `check_tactile_css.py`，加上 Task 7 的手動檢查。

- [ ] **Step 1: 進場動畫的 fill mode 改成 backwards**

```css
/* 10.4：調整後新增、按「復原」放回的卡片淡入加位移，依版面位置每張晚 30ms，最多晚 150ms */
.vgl-item .tc-card-enter {
  animation: tc-card-enter var(--tc-dur-3) var(--tc-ease-out) calc(min(var(--tc-i, 0), 5) * 30ms) both;
}
```
換成
```css
/* 10.4、Tactile spec 4.4：打開已存儀表板時每張卡片都進場，調整後新增、按「復原」放回的也進場；
   依畫面位置每張晚 30ms，最多晚 150ms。fill 用 backwards 不用 both：延遲期間停在 from，播完就放掉，
   不然播完的動畫會一直壓著 transform，拖拉時的抬起（4.5）就出不來 */
.vgl-item .tc-card-enter {
  animation: tc-card-enter var(--tc-dur-3) var(--tc-ease-out) calc(min(var(--tc-i, 0), 5) * 30ms) backwards;
}
```

- [ ] **Step 2: 加骨架、淡入、拖拉抬起、佔位框、對話框轉場**

在 `/* 10.6：等 JEV 回應時圖表區變淡 */` 那段前面插入：

```css
/* Tactile spec 4.2：骨架先等 150ms 才淡入，資料很快回來時骨架根本不會出現；接著 shimmer 每 1.4s 一輪。
   tc-shimmer 定義在 tactile-clean.css，沒有 App 層樣式時就只剩淡入。
   選擇器要寫兩個 class：tactile-clean.css 比這個檔案晚載入，只寫一個 class 的話會被它的 .tc-skeleton 蓋掉 */
.tc-skeleton.card-skeleton {
  animation: tc-fade-in var(--tc-dur-1) var(--tc-ease-out) 150ms both, tc-shimmer 1.4s ease-in-out 150ms infinite;
}

@keyframes tc-fade-in {
  from {
    opacity: 0;
  }
}

/* Tactile spec 4.2、4.7：明細表、卡片裡的警示、異常標籤出現時淡入。異常標籤比圖表晚到，不縮放、不彈跳、不閃爍 */
.tc-fade-in {
  transition: opacity var(--tc-dur-1) var(--tc-ease-out);
}

@starting-style {
  .tc-fade-in {
    opacity: 0;
  }
}

/* Tactile spec 4.5：拖拉、縮放中的卡片往上抬 2px（跟按鈕下沉的 2px 對稱），陰影換成浮層。
   覆寫檔裡的 .v-card 帶 !important，這裡也要加，靠較高的權重蓋過去 */
.vgl-item > .v-card {
  transition: box-shadow var(--tc-dur-2) var(--tc-ease-out), transform var(--tc-dur-2) var(--tc-ease-out);
}

.vgl-item--dragging > .v-card,
.vgl-item--resizing > .v-card {
  box-shadow: var(--tc-overlay-shadow, 0 16px 32px -8px rgba(15, 23, 42, 0.24), 0 4px 8px -2px rgba(15, 23, 42, 0.12)) !important;
  transform: translateY(-2px);
}

/* 多一層 .vgl-layout：ChartCard scoped 的 .card-drag { cursor: move } 權重跟這條一樣，載入順序又不一定 */
.vgl-layout .vgl-item--dragging .card-drag {
  cursor: grabbing;
}

/* 佔位框用主色：套件預設的紅色在這套系統代表錯誤。縮放時不變淡，看得清楚自己在縮什麼。
   套件把這些變數定義在 .vgl-layout 上，要多一層 .dash-grid 才蓋得過 */
.dash-grid .vgl-layout {
  --vgl-placeholder-bg: color-mix(in srgb, var(--tc-primary, #005caf) 10%, transparent);
  --vgl-placeholder-opacity: 100%;
  --vgl-item-resizing-opacity: 100%;
  --vgl-resizer-border-color: var(--tc-border-strong, #737980);
}

.dash-grid .vgl-item--placeholder {
  border: 1px dashed var(--tc-primary, #005caf);
  border-radius: var(--tc-radius-lg, 8px);
}

/* Tactile spec 4.6：預覽對話框往上浮入 200ms、往下淡出 120ms，不縮放 */
.tc-sheet-enter-active {
  transition: opacity var(--tc-dur-3) var(--tc-ease-out), transform var(--tc-dur-3) var(--tc-ease-out);
}
.tc-sheet-leave-active {
  transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out);
}
.tc-sheet-enter-from {
  opacity: 0;
  transform: translateY(12px);
}
.tc-sheet-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

```

- [ ] **Step 3: 補減少動態效果的區塊**

檔尾的 `@media (prefers-reduced-motion: reduce) { … }` 整段換成：

```css
/* 10.9、Tactile spec 4.9：減少動態效果時全部瞬間完成，補位也不滑。JS 那邊用 motion.js 的 prefersReducedMotion()。
   拖拉抬起的陰影照樣加深，那是層次不是動態；位移和轉場歸零 */
@media (prefers-reduced-motion: reduce) {
  .vgl-layout,
  .vgl-layout .vgl-item,
  .vgl-item > .v-card,
  .dash-grid,
  .undo-toast-enter-active,
  .undo-toast-leave-active,
  .tc-intent-enter-active,
  .tc-fade-in,
  .tc-sheet-enter-active,
  .tc-sheet-leave-active {
    transition: none !important;
  }

  .vgl-item .tc-card-enter,
  .vgl-item .tc-card-leave,
  .tc-skeleton.card-skeleton {
    animation: none !important;
  }

  .vgl-item--dragging > .v-card,
  .vgl-item--resizing > .v-card {
    transform: none;
  }
}
```

- [ ] **Step 4: 跑 CSS 檢查、撞名檢查、全部測試和 build**

從 repo 根目錄：

```bash
python .claude/skills/modern-erp-ui/scripts/check_tactile_css.py frontend/src/modules/dashboard/motion.css
python .claude/skills/modern-erp-ui/scripts/check_tactile_css.py frontend/src/styles/vuetify-overrides.css
```
Expected: 兩個都通過

從 `frontend/`：再跑一次 Task 1 Step 7 的撞名檢查（Expected: `no collisions`），然後：

```bash
npm test
npm run build
```
Expected: 全部 PASS；build 成功

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/motion.css
git commit -m "feat(frontend): skeleton and badge fade-in, drag lift, primary placeholder, preview sheet transition, reduced-motion coverage

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 手動驗證和 backlog

**Files:**
- Modify: `docs/backlog.md`

**Interfaces:**
- Consumes: Task 1–6 全部完成
- Produces: 無

- [ ] **Step 1: 開 dev server**

照 README 啟動後端（`127.0.0.1:8080`），再從 `frontend/` 執行 `npm run dev`。用 Chrome DevTools MCP 開自己的分頁操作（`--browserUrl 127.0.0.1:9222`，不要動使用者的分頁）。先上傳一份資料源、按「分析」、存一份儀表板，後面的檢查要用到。

- [ ] **Step 2: 逐項檢查（spec 第 6 節「手動」）**

每一項都記下結果：

1. 在按鈕上按住空白鍵，按鈕立刻下沉 2px，旁邊的按鈕不動；放開立刻彈回。用 `getComputedStyle(btn).transitionProperty` 確認裡面沒有 `transform`。
2. 打開、切換已存儀表板：卡片由左上到右下依序淡入，沒有從別的位置滑過來。
3. DevTools 網路節流設 Slow 4G，重新打開儀表板：先看到骨架淡入、有 shimmer；資料到了換成圖表，圖表在 300ms 內長完。
4. 拖拉、縮放卡片：卡片抬起，佔位是主色虛線，縮放時卡片不透明，游標是 `grabbing`。
5. 打開、取消預覽：面板往上浮入、往下淡出，沒有縮放。
6. DevTools → Rendering → Emulate CSS media feature `prefers-reduced-motion: reduce`：上面每一項都瞬間完成，骨架靜止不閃，圖表沒有進場動畫。
7. 在 console 跑 `getComputedStyle(document.querySelector('.v-main').parentElement).backgroundColor`，應該是 `rgb(241, 245, 249)`。側欄的底色也一樣，右邊有一條框線。
8. 預覽裡的白卡片放在灰底上；工具列是白底，下面有一條框線；「復原」提示是白底、1px 框、浮層陰影，位置在下方中央。
9. 散點圖（打開一份含 scatter 的儀表板）的 x 軸標籤用 compact 格式之後，還會不會疊在一起。有疊的話，記在 Step 3。
10. 用 `.claude/skills/modern-erp-ui/references/wcag-checks.md`「在跑起來的畫面上量」那節的 console 腳本，掃一次主畫面和預覽，確認沒有低於 24×24 的可點元素（特別看刪除鈕、「✕」、敏感欄位勾選框，全域 `density: 'compact'` 會把勾選框縮小）：

```js
[...document.querySelectorAll('button,a[href],input:not([type=hidden]),select,[role=button]')]
  .filter(el => el.offsetParent)
  .filter(el => { const r = el.getBoundingClientRect(), b = getComputedStyle(el, '::before');
    return Math.max(r.width, parseFloat(b.width) || 0) < 24
        || Math.max(r.height, parseFloat(b.height) || 0) < 24 })
```

列出來的元素，如果外面有 `<label>` 或其他夠大的可點區包著，就算豁免，要自己看。勾選框的 `<input>` 被 Vuetify 包在 `.v-selection-control__input` 裡面，量的時候看外層的尺寸。任何一項不合格，就回到對應的 task 修正，修完重跑那個 task 的測試。

- [ ] **Step 3: 更新 backlog**

在 `docs/backlog.md` 的「## 功能構想」清單最後加：

```markdown
- P3 · 深色模式與主題切換：這次只做淺色一套。`tactile-clean.css` 已經有六套色盤，但 Vuetify 的 theme 每一套都要各寫一個條目，也要另外設計切換入口，理由見 skill 的 `references/color-cognition.md` 第一節。（spec `docs/superpowers/specs/2026-09-25-jev-dashboard-tactile-clean-design.md` 第 2 節）
- P3 · 刪除確認對話框改成 Tactile 的 header／body／footer 結構，按鈕文案改成「保留」／「刪除儀表板」。（同上，第 2 節）
```

如果 Step 2 第 9 項的散點圖標籤還是疊在一起，在「## 穩定度與效能」清單最後再加：

```markdown
- P3 · 散點圖 x 軸標籤改用 compact 格式（「120萬」）之後仍會重疊，要調 `axisLabel.interval` 或 `hideOverlap`。（spec `docs/superpowers/specs/2026-09-25-jev-dashboard-tactile-clean-design.md` 3.7）
```

- [ ] **Step 4: 最後一次全部驗證**

從 `frontend/`：`npm test` 和 `npm run build`
Expected: 全部 PASS；build 成功；`git status` 裡 `charts/__snapshots__/` 沒有變動

- [ ] **Step 5: Commit**

```bash
git add docs/backlog.md
git commit -m "docs(backlog): dark mode and Tactile delete dialog left for later

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
