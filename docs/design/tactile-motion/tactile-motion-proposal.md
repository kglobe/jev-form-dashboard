# JEV 儀表板：Tactile Clean 風格與動畫提案

- 日期：2026-09-25
- 範圍：`frontend/`（Vue 3.5.43、Vuetify 3.13.5、ECharts 6.1.0、vue-echarts 8.3.0、grid-layout-plus 1.1.1），以及 `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md` 要新增的「想看什麼」、「調整」、卡片「✕」、「符合需求」。
- 性質：只評估、只提案。沒有改任何專案檔案，沒有 commit，沒有安裝套件。

> **目前狀態（2026-09-25 更新）**：這份提案寫在「依需求分析與調整」（PR #2）合併之前。其中 3.A（飛移）、第 7 項（✕ 退場補位）、第 8 項（調整後換卡）、第 10 項（復原提示）、第 11 項（需求紀錄）已經在 PR #2 實作，見 `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md` 第 10 節；第 4 項只做了「後來出現的卡片」進場。其餘項目由 `docs/superpowers/specs/2026-09-25-jev-dashboard-tactile-clean-design.md` 接手。文中的行號對應的是 PR #2 之前的程式碼。

## 1. 結論

建議走 Vuetify 的「整合路線」，把外觀換成 Tactile Clean。動畫只加在狀態真的改變的地方，共提案 12 項（P0 1 項、P1 3 項、P2 5 項、P3 3 項），「數字變化」判定不做。全部用 CSS、Vue `<Transition>` 和 ECharts 設定就做得到，不需要新套件。

「活起來」的感覺，在這套風格裡主要來自三件事：按下去立刻下沉、拖拉時卡片抬起來、調整之後看得出哪張卡被換掉。

### 先修一個現有的問題

實測發現，現在每次打開儀表板，卡片會從畫面左側滑到自己的欄位；從 A 儀表板切到 B，卡片會從 A 的位置飛到 B 的位置。原因有兩個：grid-layout-plus 對格子位置預設有 `transform .2s ease` 轉場，而每份儀表板的 chartId 都是 `c1`～`c6`，元件被重用。這段動畫等於告訴使用者「這張圖是從那張變來的」，但兩張圖根本無關。之後加的進場動畫也會疊在它上面，所以排在第一（3.A）。

### 這些 skill 各自幫了什麼

| skill | 用在哪 | 幫得上多少 |
|---|---|---|
| `modern-erp-ui`（Tactile Clean） | 陰影 token、按鈕按壓、骨架、toast、對話框、badge、ECharts 主題、Vuetify 接法、reduced-motion 全域規則 | 外觀的主要依據。動畫方面著墨很少，只有幾個很短的時長（60–160ms）和「不接動畫圖示庫」的禁令 |
| `find-animation-opportunities` | 用「頻率、目的、時長、功能」四道關卡篩每個候選，並給確切數值 | 決定哪些做、哪些不做。它建議整個 app 5–7 項；這份為了涵蓋你列的主題寫到 12 項，P3 的 3 項都是可選的 |
| `animation-vocabulary` | 效果名稱 | 第 3 節的效果名稱照它的詞彙表寫 |
| `apple-design` | 沒有載入 | 這裡用得到的 reduced-motion 與可中斷原則，前兩份已經涵蓋 |
| `svg-gsap-animation` | 沒有載入 | 理由見 5.3 |
| `improve-animations` | 之後要把某一項交給別人實作時用 | `improve-animations plan <項目>` 可以產出獨立的實作計畫 |

### Tactile Clean 和「更多動畫」怎麼取捨

Tactile 的做法是：按鈕靜止時就看得出能按，按下去當場下沉 2px，其他地方盡量不動。操作員一天盯同一個畫面八小時，會自己動的東西就是干擾（`references/icon-guide.md` 最後一節）。所以這份提案把動畫預算訂得很緊：

- 介面動畫一律在 200ms 以內；圖表初次繪製 300ms，資料更新 250ms。
- 只動 `transform` 和 `opacity`，抬起感用 `box-shadow`。互動狀態不碰 `margin`、`padding`、`height` 這類屬性，守住 Tactile 的硬性禁令。
- 允許一直循環的只有載入中的骨架和按鈕裡的轉圈，資料一到就停。
- 高頻動作（按鈕、hover、tooltip）是瞬間或接近瞬間。200ms 等級的過場只留給低頻而且狀態真的改變的時刻，例如打開預覽、調整後換卡。

有一點跟 Tactile 的儀表板原則衝突，但不在這次範圍：`references/dashboard-design.md` 第一節主張不要讓使用者自己拖拉版面，JEV 卻是刻意讓使用者拖拉、縮放卡片。這份提案只改善現有拖拉的手感，不去動產品決定。

槓桿最高的是 3.A 和 3.8。3.A 拿掉錯誤的飛移，改動只有幾行；3.8 處理「調整」之後的退場、補位、進場，讓使用者一眼看出這一輪改了什麼。

## 2. 風格導入建議

### 2.1 選整合路線

- 專案還在 0.1.0，只有一個主畫面加兩個對話框，五個 `.vue` 檔合計約 470 行，就是 `references/vuetify.md` 說的「專案早期、頁面不多、想徹底換風格」。
- 漸進路線只換按鈕和輸入框，卡片、標籤、對話框、警示還是 Material 的樣子，畫面會變成一半 Tactile、一半 Material。
- 替換路線要重寫 `v-file-input`、`v-tabs`／`v-window`、`v-list-group`、`v-checkbox`、`v-dialog`（含焦點鎖定）和 `v-table`，而且 5 份元件測試都是用真的 Vuetify 掛載（`test-vuetify.js`），測試也得跟著重寫。付出這些成本，動畫上一點好處也拿不到。

接法照 vuetify.md：

```js
// main.js
import 'vuetify/styles'
import './styles/tactile-clean.css'      // 從 .claude/skills/modern-erp-ui/assets/ 複製過來，不要直接引用 skill 的路徑
import './styles/vuetify-overrides.css'  // vuetify.md 的覆寫，加上本提案的動態段落

createVuetify({
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
  theme: {
    themes: {
      // 色值取自 tactile-clean.css，不要照抄 vuetify.md 的範例（見 2.3 第 1 點）
      light: { colors: { background: '#f1f5f9', surface: '#ffffff', primary: '#005caf', error: '#cb1b45', success: '#166534', warning: '#92400e', info: '#004888' } },
      dark:  { dark: true, colors: { background: '#0b1220', surface: '#1e293b', primary: '#3f93e0', error: '#cb1b45', success: '#22c55e', warning: '#f59e0b', info: '#71ace1' } },
    },
  },
})
```

`assets/theme-switch.js`、`assets/app-nav.js` 是 demo 的鷹架，不要複製進來。

### 2.2 要改的地方與工作量

| 項目 | 現況（截圖 01～04） | 改法 | 工作量 |
|---|---|---|---|
| 基礎接線 | `main.js` 只有 `createVuetify()`，字型是 Vuetify 預設的 Roboto | 上面的三個 import 與 defaults、theme；`.v-application { font-family: var(--tc-font) }` | 0.5 天 |
| 按鈕 | Material 樣式：ripple、0.28s 轉場；刪除鈕是 `x-small`，只有 20px | vuetify.md「按鈕的鍵帽厚度」那段；Primary 另外補背景漸層和白字（2.3 第 2 點）；`App.vue:74` 改 `size="small"`；預覽裡只留「存為我的儀表板」一顆 Primary，「調整」用 Secondary | 0.5 天 |
| 卡片 | Vuetify elevated、4px 圓角，白卡片放在白底上 | `.v-card` 覆寫（1px `--tc-border-subtle`、8px 圓角、`--tc-panel-shadow`）；主區底色換成 `--tc-canvas`；`ChartCard.vue:27` 的標題列照 `.tc-card__header`（15px、字重 600、下框線，整條仍是拖拉把手）；圖表區 padding 改成 `8px 8px 0`（`.tc-chart__body`）；grid 間距 `:margin="[14, 14]"` 對齊 `.tc-dashboard-grid` | 0.5 天 |
| 輸入框 | filled 灰底 | outlined 加 `--tc-carve` 內凹，focus 走 3px outline（vuetify.md「輸入框」）；「想看什麼」用 `counter="200" persistent-counter persistent-hint`；浮動標籤這次保留，改成上方固定標籤得自己接 `for`／`id` | 0.5 天 |
| 標籤 | `v-chip` tonal；「正常波動」是綠色 | 改成 `<span class="tc-badge tc-badge--…">`：正常波動不加修飾子（中性灰）、值得注意 `--info`、需要關注 `--warn`、嚴重警示 `--err`。Tactile 的規則是好消息不染綠（`dashboard-design.md` 第三節）。「符合需求」用中性的 `.tc-tag` | 0.25 天 |
| 對話框 | Vuetify 預設 | 刪除確認照 `.tc-dialog` 的 header／body／footer 排（footer 用 `--tc-subtle` 底、按鈕靠右），按鈕文案照 Tactile 寫動作：「保留」／「刪除儀表板」；預覽的轉場見 3.6 | 0.25 天 |
| 骨架 | 一行「載入中…」小字 | `.tc-skeleton` 撐滿卡片內容區，見 3.2 | 0.25 天 |
| Toast | 沒有 | 決定做「✕ 移除＋復原」時才加，見 3.10 | 0.5 天（含復原邏輯） |
| 色盤與 ECharts 主題 | ECharts 6 預設主題：飽和的彩虹色；Windows 上預設字型是簡中的 Microsoft YaHei；散點圖 x 軸標籤疊在一起（截圖 03） | 把 `echarts-theme-*.json` 複製到 `src/modules/dashboard/charts/themes/`，用 vue-echarts 的 `THEME_KEY` 在 App 層 provide；長條、圓餅、堆疊長條開 `aria.decal`（堆疊最多 10 個系列，主題只有 6 色，會重複）；數值軸用 `Intl.NumberFormat('zh-TW', { notation: 'compact' })`，顯示成「120萬」 | 0.5 天 |
| 深色模式 | 沒有 | 使用者自己切換，存在 `localStorage`（Tactile 規定不准跟著系統）；同步 `<html>` 的 `data-theme`／`.dark` 和 Vuetify 的 theme 名；圖表主題跟著換。先做冷石板／冷夜兩套，要六套就再補四個 Vuetify theme | 兩套 0.5 天，六套 1 天 |
| 格線細節 | 佔位是紅色 20%；縮放把手寫死 `#444`；縮放時整張卡變 60% 透明 | 見 3.5 | 0.1 天 |
| 版面 | 側欄吃 `surface`（白），跟內容區不同底 | Tactile 要側欄跟內容區同底色、只用一條右框線分開：`v-navigation-drawer` 加 `color="background"` | 0.1 天 |

不含 toast、深色只做兩套的話，合計約 4 人天。

### 2.3 容易踩的點

1. vuetify.md 的範例色值已經跟 CSS 脫鉤。文件裡寫的 `#2563eb`、`#dc2626` 是舊色盤，現在 `tactile-clean.css` 的淺色 primary 是 `#005caf`、danger 是 `#cb1b45`。文件自己也說以 CSS 為準。
2. 深色模式的 primary 要同時當文字色和按鈕底色，Vuetify 只給一個 `primary`。深色要填 `#3f93e0`，當文字壓在 `#1e293b` 上才夠亮；但同一個顏色當按鈕底配白字只有約 2.9:1。所以 Primary 按鈕的底色要在覆寫檔另外寫成 `linear-gradient(180deg, var(--tc-primary-fill-hi), var(--tc-primary-fill))` 配白字，不能交給 Vuetify 算，不然深色模式的主要按鈕會變成淺藍底黑字。
3. ECharts 6 已經有 `setTheme()`。`dashboard-design.md` 5.2 說換主題只能 `dispose()` 後重建，那是 ECharts 5 的限制。vue-echarts 8 在 `theme` prop 或 `THEME_KEY` 改變時會自己呼叫 `setTheme`（`node_modules/vue-echarts/dist/index.js`），不用手動重建。實作時切一次深淺色，確認座標軸線的顏色有換過去，Tactile 文件記錄過 ECharts 5 會殘留舊色。
4. 全域 `density: 'compact'` 會連勾選框的觸控區一起縮小（vuetify.md「檢查」一節）。`DatasourcePanel.vue:137` 的敏感欄位勾選框要實際量一次，確認有 24×24。

## 3. 動畫清單

### 3.0 共用的動態 token

放在 `vuetify-overrides.css` 開頭。Tactile 本身沒有動態 token，數值對齊它已有的 `tc-toast-in`（160ms）和 grid-layout-plus 的 200ms：

```css
:root {
  --tc-ease-out: cubic-bezier(0.23, 1, 0.32, 1); /* 進場、回應使用者；退場也用它，只是時間更短 */
  --tc-dur-1: 120ms;  /* 淡入淡出、退場、標籤 */
  --tc-dur-2: 160ms;  /* 抬起、toast，跟 Tactile 的 tc-toast-in 同值 */
  --tc-dur-3: 200ms;  /* 卡片進場、對話框，跟 grid-layout-plus 的位移同值 */
}
```

ECharts 在 canvas 裡，吃不到 CSS 變數，用 JS 數字：初次 300、更新 250、hover 120、tooltip 0。

### 總表

| # | 項目 | 優先 | 頻率 | 目的 | 時長 |
|---|---|---|---|---|---|
| A | 拿掉開啟、切換儀表板時的卡片飛移 | P0 | 每次開儀表板 | 修正錯誤的空間連續 | 0（移除） |
| 1 | 按鈕按壓（鍵帽下沉） | P1 | 每天數十次 | 回饋 | transform 0ms、陰影 60ms |
| 2 | 載入骨架 | P1 | 每次載入 | 避免空白和突兀的替換 | 延遲 150ms 後淡入 120ms；shimmer 1.4s 循環 |
| 3 | ECharts 初次繪製、更新、hover、tooltip | P1 | 每次開儀表板、每次 hover | 顯示狀態，又不擋閱讀 | 300／250／120／0ms |
| 4 | 卡片分批進場 | P2 | 開儀表板、打開預覽 | 避免一次全部冒出來 | 每張 200ms，間隔 30ms，最多延遲 150ms |
| 5 | 拖拉與縮放的抬起感和佔位 | P2 | 偶爾（整理版面時） | 回饋、空間連續 | 抬起 160ms、佔位 100ms、歸位 200ms |
| 6 | 預覽對話框開關 | P2 | 每次分析 | 空間連續（蓋在主畫面上的一層） | 開 200ms、關 120ms |
| 7 | 「✕」移除：退場加補位 | P2 | 預覽中偶爾 | 空間連續 | 退場 120ms、補位 200ms |
| 8 | 「調整」後的退場、補位、進場，加上等待狀態 | P2 | 每輪調整 | 看得出改了什麼 | 約 320–470ms |
| 9 | 嚴重程度標籤淡入 | P3 | 每次開儀表板 | 避免突然冒出來 | 120ms |
| 10 | Toast（只在做「復原」時） | P3 | 偶爾 | 空間連續 | 進 160ms、出 120ms |
| 11 | 需求紀錄新增一句 | P3 | 每輪調整 | 確認那句話被收下 | 160ms |
| 12 | 數字（統計值）變化 | 不做 | — | 資料要能直接讀 | — |

### A. 拿掉開啟、切換儀表板時的卡片飛移（P0）

- 位置：`DashboardView.vue:60-82`（`GridLayout`、`GridItem`）、`App.vue:86`（`<DashboardView>`）
- 觸發：第一次打開儀表板、切換到另一份儀表板、打開預覽對話框
- 現況（實測，方法見第 7 節）：
  - 第一次打開：`GridItem` 先用還沒量到的寬度算 x，右欄那張先擺在 `translate3d(5px, …)`，下一拍才跳到 `translate3d(752px, …)`；`.vgl-item--transform { transition: transform .2s ease }` 讓它一路滑過去。
  - 切換：三份儀表板的 chartId 都是 `c1`～`c6`，`:key="item.i"` 讓 `GridItem` 被重用。從「E2E 銷售」切到「E2E 採購」，約 180ms 時 c3 停在 (178, 563)，正好介於舊位置 (134, 550) 和新位置 (752, 730) 中間。
- 效果名稱：移除錯誤的 Layout animation
- 做法：

  ```vue
  <!-- App.vue:86：換一份儀表板就重建，不要假裝兩張圖有關係 -->
  <DashboardView v-if="current" :key="current.id" :config="current" />
  ```

  ```js
  // DashboardView.vue：等套件量完寬度、卡片就定位的下一幀，才打開位移轉場
  const settled = ref(false)
  const onReady = () => requestAnimationFrame(() => (settled.value = true))
  // <GridLayout :class="{ 'is-settled': settled }" @layout-ready="onReady" …>
  ```

  ```css
  .vgl-layout:not(.is-settled),
  .vgl-layout:not(.is-settled) .vgl-item { transition: none !important; }
  ```

  `layout-ready` 只在套件第一次量到寬度時觸發一次（`node_modules/grid-layout-plus/src/components/grid-layout.vue:173`）。預覽用的 `DashboardView` 不要加 key：對話框關掉後，Vuetify 會把內容整個移除（`composables/lazy.js` 的 `onAfterLeave`），下次打開本來就是重新掛載；而「調整」時保留下來的卡片，正需要滑到新位置（3.8）。
- 屬性、時長、easing：開啟和切換時 0ms。之後的拖拉、補位沿用套件的 `transform 200ms ease`。
- 可中斷：不適用
- reduced-motion：不用另外處理
- 關卡：頻率高；這段動畫沒有正當目的，反而製造錯誤的空間連續；干擾閱讀，所以拿掉。
- 為什麼：這是現在畫面「亂動」的主要來源，之後的進場動畫也會疊在它上面。

### 1. 按鈕按壓（P1）

- 位置：所有 `v-btn`，包括 `App.vue:72-78, 95-96`、`AnalyzeButton.vue:46, 61-69`、`DatasourcePanel.vue:102, 115`，以及新的「調整」和「✕」。在 `vuetify-overrides.css` 一次處理。
- 觸發：`:active`（滑鼠按下、按住空白鍵）
- 效果名稱：Press / Tap feedback。Tactile 的版本是鍵帽下沉（Translate），不是縮小（scale）。
- 屬性：Primary、Secondary 用 `transform: translateY(2px)`，`box-shadow` 從 `var(--tc-bevel), 0 2px 0 var(--tc-btn-edge), var(--tc-drop)` 換成 `0 0 0 var(--tc-btn-edge), var(--tc-press)`；文字按鈕和圖示按鈕用 `translateY(1px)`，沒有陰影。
- 時長、easing：`transform` 不加轉場（0ms）；`box-shadow .06s ease`、`background-color` 和 `border-color .08s ease`，都是 Tactile 原值。
- 可中斷：是，純狀態切換
- reduced-motion：不用降級。2px 的狀態位移本來就是瞬間的，不屬於會讓人不舒服的那種動態。
- 關卡：每天數十次；回饋；比 100–160ms 的上限還快；有助於操作。
- 為什麼：Vuetify 的 `.v-btn` 預設 `transition-property: box-shadow, transform, opacity, background; transition-duration: .28s`（`VBtn.css:17-19`），按壓位移被拖成 280ms，再加上 ripple，按下去是軟的，Tactile 要的鍵帽感出不來。覆寫時一定要把 `transform` 從轉場屬性裡拿掉，vuetify.md 那段寫法已經是對的。
- 附帶：全域關掉 ripple（VBtn、VListItem、VTab、VCheckbox，見 2.1）。資料源和「我的儀表板」這兩個清單按下去不做位移，只保留 hover 底色和選中態。

### 2. 載入骨架（P1）

- 位置：`ChartCard.vue:35`（`<div v-if="!data">載入中…</div>`）
- 觸發：卡片已經有 spec、資料還沒到。包括開儀表板、預覽第一次載入、調整後新增的卡片。
- 效果名稱：Skeleton / Shimmer，加上延遲的 Fade in
- 做法：

  ```vue
  <v-card-text class="flex-grow-1 card-body" :aria-busy="!data">
    <div v-if="!data" class="tc-skeleton card-skeleton" aria-hidden="true" />
  ```

  ```css
  .card-skeleton {
    height: 100%; min-height: 120px; border-radius: var(--tc-radius);
    animation: tc-shimmer 1.4s ease-in-out infinite,
               tc-fade-in var(--tc-dur-1) var(--tc-ease-out) 150ms both;
  }
  @keyframes tc-fade-in { from { opacity: 0; } }
  ```

  `tc-shimmer` 是 Tactile 原本就有的 keyframes。資料一到就用 `v-if` 直接拿掉，不等動畫跑完。表格和警示這兩種內容出現時，加 `@starting-style { opacity: 0 }` 配 `transition: opacity var(--tc-dur-1)`；圖表不用加，ECharts 自己會進場。
- 時長、easing：前 150ms 看不見，接著 120ms 淡入，之後 shimmer 每 1.4s 循環一次（Tactile 原值）。
- 可中斷：資料到了就整個換掉
- reduced-motion：shimmer 停成靜態底色。這要靠 5.2 的修正，不然實測會亂跳。
- 關卡：每次載入；避免空白，而且 Tactile 規定載入中一定要有骨架；淡入只要 120ms；讓人知道是「在載入」，不是「壞掉了」。
- 為什麼：現在載入時是一張 300px 高的空白卡片，左上角一行灰字（截圖 02）。本機 API 13–24ms 就回來，延遲 150ms 才淡入，資料快的時候骨架根本不會出現，也就不會閃一下；接外部 API 的資料源、正式環境比較慢時才看得到。

### 3. ECharts 初次繪製與資料更新（P1）

- 位置：`ChartCard.vue:18-22`（`option` computed）、`ChartCard.vue:55`（`VChart`）
- 觸發：初次繪製（`VChart` 掛載）、資料更新（option 換新）、hover 時的強調效果、tooltip 跟著游標移動
- 效果名稱：初次繪製時，長條從座標軸長出來（Scale）、折線由左往右 Reveal、圓餅以 expansion 掃開、散點縮放出現；更新時是 Interpolation / Tween。
- 做法：

  ```js
  // ChartCard.vue
  // ponytail: 只在載入時讀一次，使用者改了系統設定要重新整理才生效
  const REDUCE = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const SERIES_MOTION = {
    animationDuration: 300, animationEasing: 'cubicOut',
    animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut',
  }

  const option = computed(() => {
    const o = /* 原本的 toOption[props.spec.type](props.data, props.spec.mapping) */
    return o && {
      ...o,
      ...(REDUCE && { animation: false }),
      stateAnimation: { duration: REDUCE ? 0 : 120, easing: 'cubicOut' },
      tooltip: { transitionDuration: 0, ...o.tooltip },
      series: o.series.map((s) => ({ ...SERIES_MOTION, ...s })),
    }
  })
  ```

  寫在 `ChartCard`，不動 `charts/*.js`，`charts.test.js` 的快照就不用重產。
- 為什麼要寫在 series 層：ECharts 6 的圓餅在自己的 series 預設裡寫了 `animationDuration: 1000`、`animationDurationUpdate: 500`、`animationEasing: 'cubicInOut'`（`echarts/lib/chart/pie/PieSeries.js:211-217`），折線寫了 `animationEasing: 'linear'`（`LineSeries.js:142`）。series 的預設值會蓋過最外層的設定，只寫在最外層的話，圓餅還是會跑 1 秒。
- 時長：初次 300ms；更新 250ms；hover 強調 120ms（預設 300ms）；tooltip 0（預設 0.4 秒，會拖在游標後面）。
- easing：`cubicOut`
- 可中斷：是。ECharts 收到新的 option 時，會從目前的中間值接著走。
- reduced-motion：`animation: false`、`stateAnimation` 0。Tactile 的 CSS 規則管不到 canvas（`dashboard-design.md` 第八節）。
- 關卡：每次開儀表板；顯示資料從哪裡長出來；300ms；預設的 1000ms 會擋住閱讀，所以選擇縮短，不是全關。
- 為什麼：ECharts 6 的全域預設是初次 1000ms、更新 500ms、`cubicInOut`（`echarts/lib/model/globalDefault.js`）。一份儀表板五張圖一起長一秒，想讀數字就得等。另外，現在的圖表完全沒有接 reduced-motion。

### 4. 卡片分批進場（P2）

- 位置：`DashboardView.vue:68-81`（`GridItem` 的 `v-for` 裡的 `ChartCard`）
- 觸發：卡片第一次掛載。有了 3.A 的 key，每次開儀表板都是新掛載；另外還有打開預覽、調整後新增的卡片。資料更新不會觸發。
- 效果名稱：Stagger（Fade in 加 Translate）
- 做法：

  ```vue
  <GridItem v-for="(item, index) in layout" …>
    <Transition name="card" appear>
      <ChartCard v-if="!leaving.has(item.i)" :style="{ '--i': index }" … />
    </Transition>
  </GridItem>
  ```

  ```css
  .card-enter-active {
    transition: opacity var(--tc-dur-3) var(--tc-ease-out), transform var(--tc-dur-3) var(--tc-ease-out);
    transition-delay: calc(min(var(--i, 0), 5) * 30ms);
  }
  .card-enter-from { opacity: 0; transform: translateY(6px); }
  ```

  動的是 `GridItem` 裡面的 `v-card`。`.vgl-item` 自己的 inline `transform` 是格子位置，不能拿來做動畫。`leaving` 是 3.7 會用到的 Set，這裡先一起放進來。
- 時長：每張 200ms，間隔 30ms，第 6 張以後不再往後延（最多延遲 150ms），最慢 350ms 全部到位。
- easing：`--tc-ease-out`
- 可中斷：是。這是 CSS transition，Vue 在 leave 開始時會取消 enter。沒有加 `pointer-events: none`，不擋互動。
- reduced-motion：瞬間出現，延遲也歸零（5.2），不會變成一張一張蹦出來。
- 關卡：一天幾次到十幾次；避免五、六張卡片和五、六張圖在同一幀一起冒出來；30ms 是 skill 建議範圍（30–80ms）的下限；不擋閱讀。
- 為什麼：所有卡片同時冒出來時，視線找不到起點；由左上往右下的短波浪給了一個順序。這一項本質上偏裝飾，所以放在 P2、數值取最小。嫌多餘的話，可以只留 200ms 淡入，拿掉間隔。
- 備註：`index` 是 layout 陣列的順序。使用者拖過版面之後，陣列順序不一定等於畫面順序；要照畫面順序，就用 `(y, x)` 排序來算 `--i`，多一個 computed 而已。

### 5. 拖拉與縮放的抬起感和佔位（P2）

- 位置：`DashboardView.vue`（目前沒有 `<style>`），覆寫 grid-layout-plus 的 `.vgl-item--dragging`、`.vgl-item--resizing`、`.vgl-item--placeholder`、`.vgl-item__resizer`
- 觸發：按住標題列拖拉（`drag-allow-from=".card-drag"`）、拉右下角縮放、放開
- 效果名稱：Drag（抬起），Drag to reorder 的佔位，放開後以 Layout animation 歸位
- 做法：

  ```css
  .vgl-layout {
    --vgl-placeholder-bg: color-mix(in srgb, var(--tc-primary) 10%, transparent);
    --vgl-placeholder-opacity: 100%;
    --vgl-item-resizing-opacity: 100%;
    --vgl-resizer-border-color: var(--tc-border-strong);
  }
  .vgl-item--placeholder { border: 1px dashed var(--tc-primary); border-radius: var(--tc-radius-lg); }
  .vgl-item > .v-card {
    transition: box-shadow var(--tc-dur-2) var(--tc-ease-out), transform var(--tc-dur-2) var(--tc-ease-out);
  }
  .vgl-item--dragging > .v-card,
  .vgl-item--resizing > .v-card { box-shadow: var(--tc-overlay-shadow) !important; transform: translateY(-2px); }
  .vgl-item--dragging .card-drag { cursor: grabbing; }
  ```

  加 `!important` 是因為 vuetify.md 的 `.v-card` 覆寫本身就帶 `!important`。
- 時長：抬起和放下 160ms。佔位在格子間移動 100ms、放開後滑回格子 200ms `ease`、其他卡片讓位 200ms，這三個都是套件預設，不改。
- easing：抬起用 `--tc-ease-out`
- 可中斷：是。全部都是 CSS transition，中途改變時會從目前位置重新瞄準。
- reduced-motion：陰影照樣加深（那是層次，不是動態），位移和轉場歸零。
- 關卡：偶爾；回饋加空間連續；160ms；看得出「我拿起了這張」。
- 為什麼：套件預設的佔位是紅色 20%，而紅色在這套系統代表錯誤；縮放時整張卡變成 60% 透明，看不清自己在縮什麼；縮放把手寫死 `#444`，深色模式下看不到。抬起用 2px，是為了跟按壓下沉的 2px 對稱。
- 不做：spring 和慣性。要加套件才做得到，而且 Tactile 不要回彈。

### 6. 預覽對話框開關（P2）

- 位置：`AnalyzeButton.vue:48`（`<v-dialog v-model="open" fullscreen>`）
- 觸發：分析完成後打開；按「取消」、存檔、按 Esc 時關閉
- 現況：這個對話框沒有 activator，Vuetify 會退回 CSS 的 `dialog-transition`：`scale(.9)` 加淡入，打開 225ms `cubic-bezier(0, 0, .2, 1)`、關閉 125ms `cubic-bezier(.4, 0, 1, 1)`。全螢幕從 90% 放大，等於整個畫面在縮放。
- 效果名稱：Fade in 加 Translate，像一片面板浮上來，不縮放
- 做法：`<v-dialog v-model="open" fullscreen transition="tc-sheet">`

  ```css
  .tc-sheet-enter-active { transition: opacity var(--tc-dur-3) var(--tc-ease-out), transform var(--tc-dur-3) var(--tc-ease-out); }
  .tc-sheet-leave-active { transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out); }
  .tc-sheet-enter-from { opacity: 0; transform: translateY(12px); }
  .tc-sheet-leave-to   { opacity: 0; transform: translateY(6px); }
  ```

- 時長：開 200ms、關 120ms，退場比進場快
- 可中斷：是。打開到一半按 Esc，Vue 會從目前的狀態接著退場。
- reduced-motion：瞬間開關
- 關卡：每次分析一次；空間連續（這是蓋在主畫面上的一層）；200ms，落在對話框 200–500ms 的範圍內；不擋操作。
- 為什麼：分析要等 JEV 好幾秒，結果出來時需要一個清楚的「好了」，但用不著整個畫面放大。刪除確認那個小對話框維持 Vuetify 預設就好（225／125ms，已經內建 reduced-motion）。

### 7. 「✕」移除：退場加補位（P2）

- 位置：新功能，`ChartCard` 標題列右上角（規格 3.2）；明細表沒有「✕」
- 觸發：按「✕」
- 效果名稱：Exit（Fade out 加 Scale），接著其餘卡片以 Layout animation 補位
- 做法：跟 3.8 共用同一套機制，寫在 `DashboardView` 的 `watch(config)` 裡。比對新舊 config 的 chartId，消失的先放進 `leaving`，讓 `<Transition name="card">` 播完退場，120ms 後才套用新版面。上層只負責換掉 config（照規格 6.6 重新排好），動畫全交給 `DashboardView`。以下是示意：

  ```js
  const leaving = reactive(new Set())
  watch(() => props.config, async (next, prev) => {
    if (props.preview && prev && !REDUCE) {
      const ids = new Set(next.layout.map((s) => s.chartId))
      const gone = prev.layout.filter((s) => !ids.has(s.chartId))
      if (gone.length) {
        gone.forEach((s) => leaving.add(s.chartId))
        await new Promise((r) => setTimeout(r, 120)) // ponytail: 跟 --tc-dur-1 同值
      }
    }
    leaving.clear()
    load()
  }, { immediate: true })
  ```

  ```css
  .card-leave-active { transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out); }
  .card-leave-to { opacity: 0; transform: scale(0.98); }
  ```

  前提：`load()` 現在一開始就把 `dataById` 清空（`DashboardView.vue:26`），config 一換，剩下的卡片會全部閃回骨架、圖表重播進場。要改成只清掉不在新版面裡的 chartId。
- 「✕」按鈕本身：ghost 圖示按鈕，靜止時就用 `--tc-muted` 顯示，不要 hover 才出現（Tactile 的 signifier 規則）；hover 升成 `--tc-text`；按下 `translateY(1px)`；至少 24×24（`size="small"`）；`aria-label="移除這張圖"`。grid-layout-plus 的 `dragIgnoreFrom` 預設是 `'a, button'`（`grid-item.vue:43`），按「✕」不會誤觸拖拉。
- 時長：退場 120ms，接著補位 200ms（套件的 transform 轉場）。按下去的那一刻就有按壓回饋。
- 可中斷：退場那 120ms 不中斷；補位可以。
- reduced-motion：不等 120ms，直接換版面，也不滑動。
- 關卡：偶爾；空間連續；合計 320ms；看得出哪張不見了、誰補了上來。
- 為什麼：移除後會照規格 6.6 重新排成每列兩張。卡片瞬間消失、版面瞬間重排的話，使用者看不出剛剛發生了什麼。

### 8. 「調整」後的退場、補位、進場，加上等待狀態（P2）

- 位置：新功能。`AnalyzeButton.vue` 預覽裡的「調整」按鈕和需求紀錄；`DashboardView.vue` 接收新的 config。
- 觸發：按「調整」，等 JEV 回應（數秒），新結果取代預覽。
- 等待中：預覽網格降到 `opacity: .6`（`transition: opacity var(--tc-dur-2)`），加 `aria-busy="true"`，同時停用「✕」；「調整」按鈕用 `:loading`。停用「✕」屬於行為上的建議：等待期間移除的卡片會被回來的結果蓋掉，使用者會以為沒移除成功。
- 結果回來後，依 chartId 比對（機制同 3.7）：
  - 被拿掉的（舊的有、新的沒有）：退場 120ms，opacity 到 0、`scale(.98)`。
  - 保留的（chartId 不變，規格 6.5 保證）：不重播進場、不閃骨架；位置有變就用 200ms 滑到新格子。
  - 新增的：跟 3.4 一樣進場。它們排在保留的卡片後面，`--i` 比較大，自然晚 60–150ms 出現，差不多在保留的卡片滑完時到位。「符合需求」標籤跟著卡片一起進場，不另外做動畫。
- 效果名稱：Orchestration（Exit → Layout animation → Enter）
- 時長：約 320–470ms
- 可中斷：等待中可以按「取消」直接關掉對話框；「調整」在處理中是停用的，不會連發。
- reduced-motion：全部瞬間完成，JS 也不等那 120ms。
- 關卡：每輪調整一次；顯示這一輪改了什麼；500ms 以內；幫助理解。
- 為什麼：送出「不要圓餅圖，加各產品的數量」之後，使用者最想知道的是改了哪裡。整片卡片同時換掉，他得自己一張一張比對；有了退場、補位、進場，一眼就看完。這是這次新功能裡動畫價值最高的地方。
- 可以分兩次做：第一版先做「保留的不閃」和「新增的進場」，退場之後再補。

### 9. 嚴重程度標籤淡入（P3）

- 位置：`ChartCard.vue:30-32`
- 觸發：`highlights` API 比圖表資料晚到（`DashboardView.vue:41`），標籤會在卡片畫好之後才出現。
- 效果名稱：Fade in
- 做法：照 2.2 改成 `.tc-badge` 之後，加上：

  ```css
  .card-sev { transition: opacity var(--tc-dur-1) var(--tc-ease-out); }
  @starting-style { .card-sev { opacity: 0; } }
  ```

  `@starting-style` 讓元素插入時從透明開始，不用再包一層 `<Transition>`。支援度：Chrome、Edge 117 以上，Safari 17.5 以上，Firefox 129 以上。
- 時長：120ms。不縮放、不彈跳、不閃爍。
- 可中斷：是
- reduced-motion：瞬間出現
- 關卡：每次開儀表板；避免突然冒出來；120ms；不擋閱讀。
- 為什麼：眼角餘光突然冒出一個紅點，看起來像剛發生了警報；120ms 的淡入一樣看得到，只是不會像閃了一下。
- 「符合需求」跟卡片資料同時到，不需要單獨的動畫。

### 10. Toast（P3，有條件才做）

- 前提：目前整個前端沒有 toast，錯誤都用常駐的 `v-alert`，這是對的；Tactile 的原則是，需要回頭再看的訊息不要放在會自己消失的東西裡。只有決定做「✕ 移除＋復原」時才需要 toast。對這種高頻、可以復原的移除，Tactile 的建議是不跳確認框，改用帶「復原」的 toast，停留 5–7 秒（`references/toast-positioning.md` 第九節）。
- 位置：`AnalyzeButton.vue` 預覽裡放一個 `v-snackbar`
- 觸發：按「✕」後出現「已移除「{title}」」和「復原」；6 秒後、按下復原、或開始下一次調整時消失。
- 效果名稱：Slide in／Fade out，從同一個邊進、同一個邊出（空間連續）
- 做法：`<v-snackbar location="bottom" :timeout="6000" transition="toast-motion">`

  ```css
  .toast-motion-enter-active { transition: opacity var(--tc-dur-2) ease-out, transform var(--tc-dur-2) ease-out; }
  .toast-motion-leave-active { transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out); }
  .toast-motion-enter-from,
  .toast-motion-leave-to { opacity: 0; transform: translateY(6px); }
  ```

  進場數值和 Tactile 的 `tc-toast-in`（160ms、ease-out、6px）一樣。Tactile 的 demo 退場時直接移除節點，這裡補上同方向的 120ms 退場。
- 時長：進 160ms、出 120ms
- 可中斷：是。Vuetify 的 snackbar 在滑鼠移上去時會暫停計時（`VSnackbar.js` 的 `onPointerenter`），但鍵盤焦點不會暫停；WCAG 2.2.1 要求能延長時限，這點要另外補。
- reduced-motion：瞬間出現、瞬間消失
- 關卡：偶爾；空間連續；160ms；不擋操作。
- 為什麼：只有需要復原時才值得做。單純的成功訊息（例如存檔成功）不需要 toast，存好的儀表板被打開，本身就是回饋。

### 11. 需求紀錄新增一句（P3）

- 位置：新功能，預覽上方的「需求紀錄」
- 觸發：調整成功，新的一句加在最後
- 效果名稱：Enter（Fade in 加 Translate）
- 做法：`<TransitionGroup name="intent" tag="ol">`，CSS 是 `.intent-enter-active { transition: opacity var(--tc-dur-2) var(--tc-ease-out), transform var(--tc-dur-2) var(--tc-ease-out); }` 和 `.intent-enter-from { opacity: 0; transform: translateY(4px); }`。這是一般清單，可以放心用 `TransitionGroup`，跟 5.1 提到的卡片網格不一樣。
- 時長：160ms
- 可中斷：是
- reduced-motion：瞬間出現
- 關卡：每輪調整一次；確認剛才那句話被收下了；160ms；不擋操作。
- 為什麼：輸入框清空的同時，視線需要一個落點，知道那句話去了哪裡。

### 12. 數字（統計值）變化：不做

- 現況：產品裡沒有 KPI 數字卡。數字只出現在座標軸、tooltip、明細表和字數計數。
- 決定：不做 Number ticker（數字滾動、從 0 數上去）。滾動過程中的每個中間值都是不存在的數字，而 ERP 使用者要讀的是精確值；每次開儀表板還得等它滾完。
- 要做的是靜態的 Tabular numbers：明細表的數值欄設 `font-variant-numeric: tabular-nums` 並靠右對齊（Tactile 的 `.num` 規則），字數計數也一樣，數字變動時寬度不會跳。
- 如果以後加統計卡：直接顯示最終值；資料更新交給第 3 項的 250ms 更新動畫，不另外做滾動。
- 關卡：在「功能」這關被刷掉，資料必須能直接讀。

## 4. 不要做的動畫

| 候選 | 位置 | 被哪一關刷掉 | 理由 |
|---|---|---|---|
| 「嚴重警示」持續閃爍或脈動（Pulse、Loop） | `ChartCard.vue:30` | 頻率、功能 | 整天在眼角閃，視線會一直被拉走。嚴重程度靠文字、顏色和圓點（`.tc-badge`）表達，SC 1.4.1 要的也是文字 |
| 大型視差（Parallax） | 全頁 | 目的 | 沒有捲動敘事可講，只會讓資料錯位 |
| 預設 1000ms 的圖表進場、每次切換都重播 | `ChartCard.vue:55` | 時長、功能 | 讀數要等一秒。改成 300ms（第 3 項），不是全關 |
| 數字滾動（Number ticker） | 未來的統計卡 | 功能 | 中間值是假資料（第 12 項） |
| 卡片 hover 時浮起來 | `ChartCard` | 頻率、功能 | 卡片本身不能點，浮起來會暗示可以點，是錯誤的 signifier；hover 又是高頻動作 |
| 切換儀表板的頁面轉場、左右方向性轉場 | `App.vue` | 目的 | 儀表板之間沒有先後順序，方向性轉場會暗示一個不存在的順序；而且這是高頻動作 |
| 會自己動的圖示、「✕」變形（lucide-animated、MorphSVG） | 「✕」、刪除鈕 | Tactile 禁令 | `icon-guide.md`「不要接動畫圖示庫」 |
| Ripple | 所有按鈕、清單 | Tactile 規則 | 由按壓位移取代（vuetify.md「停用 Ripple」） |
| 圖表型態之間的變形（`universalTransition`） | 調整後 | 功能 | 裝飾性強，變形時讀不到資料；而且調整後保留的圖本來就不會換圖型 |
| 深色切換時全畫面淡入淡出（View Transition） | 深色開關 | 頻率低、不帶資訊 | canvas 會跟著重畫，過場也蓋不住 |
| 驗證錯誤時抖動（Shake） | 名稱欄、「想看什麼」 | 目的 | 錯誤靠文字和框線顏色表達；抖動屬於容易讓人不舒服的動態 |
| 存檔成功的慶祝動畫 | 「存為我的儀表板」 | 目的 | 存好的儀表板被打開，就是回饋 |
| 聚焦時才滑出來的字數計數 | 「想看什麼」 | 頻率 | Vuetify 的計數預設在 focus 時才用 slide-y（300ms）滑出來；加 `persistent-counter` 讓它一直顯示，上限事先就看得到 |
| 資料源表單的分頁左右滑動 | `DatasourcePanel.vue:97-117` | 目的 | `v-window` 預設左右滑 300ms，但「上傳檔案」和「外部 API」不是前後頁；建議在 `v-window-item` 加 `:transition="false" :reverse-transition="false"` |
| 錯誤訊息展開（動高度） | 各個 `v-alert` | Tactile 規則、功能 | 動高度會讓版面每一幀重算；錯誤應該立刻被看到 |

## 5. 技術做法

### 5.1 現有的工具就夠

- Vue `<Transition>`：卡片的進出場（第 4、7、8 項）；Vuetify 元件的 `transition` prop（第 6、10 項）。
- `<TransitionGroup>`：只用在一般清單（第 11 項）。不要把它包在 `GridItem` 外面：它的 move 動畫（FLIP）會直接改元素的 inline `transform`，而 grid-layout-plus 正是靠 inline `translate3d` 擺放格子，兩邊會互相覆蓋。卡片的位移交給套件自己的 `transform 200ms` 轉場。
- CSS transition 優先於 keyframes：transition 可以從目前位置重新瞄準，也就是可中斷；keyframes 只用在骨架的循環。
- `@starting-style`：只需要「插入時淡入」的元素（標籤、表格、警示），不必多包一層元件。
- ECharts option：圖表內部的動畫（第 3 項）。
- 不加任何新套件。

### 5.2 reduced-motion 的統一做法

四種來源，各用一條規則處理：

| 來源 | 現況 | 做法 |
|---|---|---|
| CSS（Tactile、本專案的覆寫、grid-layout-plus） | Tactile 檔尾有 `* { transition-duration: .01ms !important; animation-duration: .01ms !important; }` | 保留，並在覆寫檔補上下面這段 |
| Vuetify 內建轉場 | 已經包在 `@media (prefers-reduced-motion: no-preference)` 裡；有 activator 的對話框會改成 125ms 的純淡入 | 不用動 |
| ECharts（canvas） | CSS 管不到 | `animation: false`、`stateAnimation` 設 0（第 3 項） |
| JS 計時（第 7、8 項的 120ms 等待） | — | 讀同一個 `matchMedia`，reduce 時不等 |

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-iteration-count: 1 !important;
    animation-delay: 0s !important;
    transition-delay: 0s !important;
  }
}
```

為什麼要補這段：Tactile 那條規則只縮短時長，沒有停掉循環。無限循環的 shimmer 被縮成 .01ms 之後，每一幀都會落在隨機的位置，看起來就是閃爍。我在瀏覽器實測過：只縮時長的骨架，連續取樣 `background-position` 得到 8.2%、33.2%、91.8%；加上 `animation-iteration-count: 1` 之後固定在 0%。延遲也要歸零，不然第 4 項的卡片會先空等最多 150ms，再一口氣跳出來。另外，`*` 選不到 `::before`、`::after`，所以補的規則把它們一起列上。

JS 那邊，`ChartCard.vue` 和 `DashboardView.vue` 各會用到一次 `matchMedia`，抽成 `src/modules/dashboard/motion.js` 裡的一行 export 即可。測試環境的 `test-vuetify.js` 已經把 `matchMedia` stub 成 `matches: false`，現有測試不受影響。

手動驗證：Chrome DevTools → Rendering → Emulate CSS media feature `prefers-reduced-motion: reduce`。

### 5.3 為什麼不用 GSAP

- 這裡每段動畫都是 200ms 以內的狀態切換，CSS transition 和 Vue `<Transition>` 就寫得出來。沒有時間軸編排、捲動驅動、SVG 路徑變形或物理模擬的需求，GSAP 的強項在這裡用不上。
- 多一個 JS 動畫引擎，就多一套不受 CSS reduced-motion 規則管轄的動畫，得另外用 `gsap.matchMedia()` 接，很容易漏掉。
- Tactile 的 `icon-guide.md` 明確表示不接 GSAP MorphSVG 這類變形引擎。
- 拖拉的 spring 手感也是一樣的道理：grid-layout-plus 用 CSS transition 歸位，想要 spring 就得換掉它的定位方式或另外加動畫套件，換來的回彈偏偏又是 Tactile 不要的。
- 所以沒有載入 `svg-gsap-animation`。

### 5.4 怎麼檢查

- `python .claude/skills/modern-erp-ui/scripts/check_tactile_css.py frontend/src/styles/vuetify-overrides.css`：確認互動狀態沒有動到版面屬性。
- 用 `references/wcag-checks.md` 附的 console 腳本量對比和 24×24 命中區，特別是刪除鈕和勾選框。
- 用 Performance 面板錄一次開儀表板的過程：除了 grid-layout-plus 自己調整容器高度，動畫期間不應該出現 Layout 區塊。

## 6. 建議的實作分階段

優先順序和階段不完全一樣：有些 P3 項目很小，跟著相關的樣式一起做比較省事。

### 第一階段：不亂動、按下去有手感（約 2.5 天）

- 範圍：
  - 樣式：基礎接線、按鈕、卡片、骨架、ECharts 主題（先做淺色）、版面底色。
  - 動畫：3.A、第 1、2、3 項，加上 5.2 的 reduced-motion 統一做法。
- 驗證：
  - 開儀表板、切換儀表板時，卡片不再滑動。可以用第 7 節的取樣方式，確認沒有落在新舊位置之間的中間值。
  - 按住空白鍵，按鈕立刻下沉 2px，旁邊的按鈕不動。
  - DevTools 模擬 reduce：圖表沒有進場動畫，骨架是靜態的。
  - `check_tactile_css.py` 通過，`npm test` 全綠，`charts.test.js` 的快照不變。

### 第二階段：跟「依需求分析與調整」一起做（約 2 天）

- 範圍：
  - 樣式：輸入框（「想看什麼」本來就要新增）、標籤改成 badge，順手做第 9 項。
  - 動畫：第 4、5、6、7、8 項，以及 `load()` 保留資料的前提改動。
- 驗證：
  - Vitest：config 換新時，消失的 chartId 先進 `leaving`，120ms 後才從 layout 拿掉；保留的卡片元件沒有重新掛載（可以數 `onMounted` 的次數）；reduce 時不等待。
  - 手動：DevTools 網路設 Slow 4G、CPU 降速 4 倍，照規格第 10 節端對端測試的第 2、3 步操作，看退場、補位、進場的順序是否正確。
  - 拖拉：佔位是主色虛線，縮放時卡片不透明，深色模式看得到縮放把手。

### 第三階段：可選（約 1.5 天）

- 範圍：對話框結構與文案、深色模式切換、第 10 項（先決定要不要做「復原」）、第 11 項。
- 驗證：
  - 冷石板、冷夜各切一輪，圖表座標軸線的顏色有跟著換。
  - toast 的 `role`、`aria-live` 正確，滑鼠移上去會暫停計時。
  - `wcag-checks.md` 的對比腳本在兩套色盤各跑一次。

## 7. 截圖與實測紀錄

截圖都放在本報告所在的資料夾（`docs/design/tactile-motion/`）：

- `01-home-empty.png`：首頁，還沒選儀表板
- `02-dashboard-loading.png`：打開「E2E 銷售儀表板」時的載入狀態（網路模擬 Slow 3G）
- `03-dashboard-loaded.png`：同一份儀表板載入完成
- `04-dashboard-r10.png`：「R10 銷售 650 筆 總覽」

怎麼看的：

- `http://localhost:5173` 那個 dev server 目前無法編譯 `.vue`，畫面是 Vite 的錯誤頁「Install @vitejs/plugin-vue to handle .vue files」（指在 `App.vue:52`），`/api` 也沒有被代理，直接回 `index.html`。看起來它沒有讀到 `frontend/vite.config.js`，可能是從別的目錄啟動的。我沒有動它。
- 改用現成的 `frontend/dist`（01:40 建置，跟最後一個前端 commit `4d9d2fb` 同一時間），在 `127.0.0.1:4317` 開 `vite preview`，`/api` 照 `vite.config.js` 代理到 8080。看完就關掉了，專案的 `git status` 仍然是乾淨的。
- 在自己開的分頁裡只做了打開儀表板、截圖和唯讀的量測，沒有分析、存檔、刪除或拖拉。
- 實測一（3.A）：切換儀表板時用 `requestAnimationFrame` 取樣 `.vgl-item` 的 transform；第一次打開時用 MutationObserver 記錄 inline `transform` 的變化。
- 實測二（5.2）：在自己的分頁插入一個暫時的骨架元素，比較「只縮時長」和「再加 `animation-iteration-count: 1`」兩種情況的 `background-position`，量完就移除。
- 本機 API 耗時：`/data` 13–24ms，`/highlight` 13–27ms。
