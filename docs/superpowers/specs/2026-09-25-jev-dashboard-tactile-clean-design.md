# JEV 儀表板：Tactile Clean 外觀與通用動畫（設計）

日期：2026-09-25
前提：評估與實測紀錄見 `docs/design/tactile-motion/tactile-motion-proposal.md`（下稱「提案」）。上一份 spec `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md` 第 10 節做的動態效果（飛移修正、✕ 退場補位、調整後換卡、復原提示、需求紀錄）這次不重做，只有 10.4 的「第一次打開不做進場」被本文件 4.4 取代。

## 1. 目的

現在的畫面是 Vuetify 預設的 Material 樣式：按鈕按下去有 ripple、反應軟；白卡片放在白底上；載入時只有一行「載入中…」；圖表用 ECharts 預設的彩虹色，進場要跑 1 秒。這次把外觀換成 Tactile Clean（微觸感極簡），並補上提案裡還沒做的通用動畫。

完成後，使用者最明顯的感受是按鈕按下去立刻下沉、載入中看得出在載入、拖拉時卡片會抬起來。

## 2. 範圍

做：

- 外觀：Tactile CSS 接線、按鈕、卡片與版面、輸入框、異常標籤與「符合需求」標籤、載入骨架、ECharts 淺色主題與數值格式、明細表數字對齊（第 3 節）。
- 動畫：按鈕按壓、骨架淡入、圖表動畫調校、打開已存儀表板時卡片分批進場、拖拉與縮放時抬起、預覽對話框開關、異常標籤淡入、資料源分頁不再左右滑、減少動態效果的統一做法（第 4 節）。

不做（寫進 `docs/backlog.md`）：

- 深色模式與主題切換。
- 刪除確認對話框改成 Tactile 的 header／body／footer 結構與「保留」／「刪除儀表板」文案。
- 異常標籤持續閃爍、卡片 hover 浮起、數字滾動、驗證錯誤抖動、圖型之間的變形等，理由見提案第 4 節。
- 不加任何新套件。全部用 CSS、Vue `<Transition>`、Vuetify 的 `transition` prop 和 ECharts 設定。

## 3. 外觀

### 3.1 接線

- 把 `.claude/skills/modern-erp-ui/assets/tactile-clean.css` 原樣複製到 `frontend/src/styles/tactile-clean.css`，檔頭加一行註解寫明來源和複製日期。之後 skill 更新時直接換檔。`.claude/` 在 gitignore 裡，所以一定要複製，不能引用 skill 的路徑。
- 新增 `frontend/src/styles/vuetify-overrides.css`，放 Vuetify 元件的覆寫（按鈕、卡片、輸入框）和全域的減少動態效果補充規則（4.9）。寫法照 skill 的 `references/vuetify.md`，實作計畫裡要把程式碼寫完整，不能只寫「照 vuetify.md」。
- `main.js` 的載入順序：`vuetify/styles` → `tactile-clean.css` → `vuetify-overrides.css`。
- `createVuetify` 加上：
  - `defaults`：全域 `density: 'compact'`；`VBtn` 用 `variant: 'flat'`、`ripple: false`；`VListItem`、`VTab`、`VCheckbox` 都設 `ripple: false`；`VTextField`、`VTextarea`、`VFileInput` 用 `variant: 'outlined'`。
  - `theme`：只有淺色一套，色值取自 `tactile-clean.css` 的 `:root`（background `#f1f5f9`、surface `#ffffff`、primary `#005caf`、error `#cb1b45`、success `#166534`、warning `#92400e`、info `#004888`）。不要照抄 `vuetify.md` 範例裡的舊色值（`#2563eb`、`#dc2626`）。
- 字型：`.v-application { font-family: var(--tc-font) }`，不再用 Vuetify 預設的 Roboto。
- 模組邊界：`src/styles/` 是 App 層，只有 `main.js` 引用。`modules/dashboard/` 裡的元件只使用 `tc-*` class 名稱和 `--tc-*` 變數，不 import `src/styles/` 的檔案；沒有這兩份樣式時，模組照樣能用，只是外觀比較陽春。

### 3.2 按鈕

- 靜止時有鍵帽厚度：Primary 和 Secondary 的 `box-shadow` 用 `var(--tc-bevel), 0 2px 0 var(--tc-btn-edge), var(--tc-drop)`。
- Primary 的底色在覆寫檔裡另外寫 `linear-gradient(180deg, var(--tc-primary-fill-hi), var(--tc-primary-fill))` 配白字，不交給 Vuetify 算。現在只有淺色，但這樣寫之後加深色模式時不用改（提案 2.3 第 2 點）。
- `App.vue` 的刪除儀表板按鈕從 `size="x-small"` 改成 `size="small"`，點擊範圍才夠。
- 預覽工具列只保留「存為我的儀表板」一顆 Primary；「調整」改成 Secondary（不帶 `color="primary"`）。左側的「分析」維持 Primary。
- 按壓的動態見 4.1。

### 3.3 卡片與版面

- `.v-card`：1px `--tc-border-subtle` 框線、8px 圓角、`--tc-panel-shadow`，取代 Vuetify 的 elevation。
- 主區（`v-main`）和預覽的內容區底色用 `--tc-canvas`，卡片是 `--tc-panel`，白卡片不再貼在白底上。
- 側欄跟主區同底色，只用一條右框線分開：`v-navigation-drawer` 加 `color="background"`。
- `ChartCard` 標題列照 `.tc-card__header` 的樣子：15px、字重 600、下框線；整條仍是拖拉把手（`.card-drag`）。圖表區 padding 改成 `8px 8px 0`（同 `.tc-chart__body`）。
- 格線間距：`GridLayout` 加 `:margin="[14, 14]"`。
- 預覽工具列（`v-toolbar`）改成 `color="surface"` 加下框線；「復原」提示的底色、文字色、陰影改用 `--tc-panel`、`--tc-text`、`--tc-overlay-shadow`，外框加 1px `--tc-border`。

### 3.4 輸入框

- outlined 加 `--tc-carve` 內凹；focus 時走 3px outline（`--tc-ring-color`），不是 Material 的底線動畫。
- 浮動標籤這次保留。

### 3.5 標籤

- 異常標籤從 `v-chip` 改成 `<span class="tc-badge …">`，文字不變：

  | 嚴重程度 | class |
  |---|---|
  | 正常波動 | `tc-badge`（中性灰，不加修飾子） |
  | 值得注意 | `tc-badge tc-badge--info` |
  | 需要關注 | `tc-badge tc-badge--warn` |
  | 嚴重警示 | `tc-badge tc-badge--err` |

  Tactile 的規則是好消息不染綠，所以異常標籤不再用綠色。
- 「符合需求」從 `v-chip color="primary"` 改成 `<span class="tc-tag">`，中性外觀，跟嚴重程度區分開。

### 3.6 載入骨架

`ChartCard` 裡的「載入中…」換成撐滿內容區的骨架：`<div class="tc-skeleton card-skeleton" aria-hidden="true">`，`v-card-text` 在沒有資料時標 `aria-busy="true"`。骨架高度 100%、最少 120px、圓角 `--tc-radius`。淡入的時機見 4.2。

### 3.7 圖表主題與數值格式

- 把 `echarts-theme-default.json` 複製到 `frontend/src/modules/dashboard/charts/themes/tactile-light.json`，`ChartCard` import 之後直接傳給 `VChart` 的 `theme` prop。放在模組裡，模組才不必依賴外面的檔案。
- 這套主題只有 6 色，堆疊長條最多 10 個系列會重複，所以長條、圓餅、堆疊長條開啟 `aria.decal`（`aria: { enabled: true, decal: { show: true } }`），靠花紋區分重複的顏色。
- 數值軸的標籤用 `Intl.NumberFormat('zh-TW', { notation: 'compact' })`，顯示成「120萬」。
- 以上都在 `ChartCard` 裡、包在 `toOption` 的結果外層加上，不改 `charts/*.js`，`charts.test.js` 的快照不變。散點圖 x 軸標籤重疊（提案截圖 03）的問題，用 compact 格式後重新確認；還是疊在一起的話，記進 backlog，這次不另外處理。

### 3.8 明細表的數字

明細表裡型別是數字的格子（`typeof cell === 'number'`）加上 `font-variant-numeric: tabular-nums` 並靠右對齊，數字寬度一致，上下好比對。

## 4. 動態效果

原則沿用上一份 spec 第 10 節：只在狀態真的改變時才動；介面動畫在 200ms 以內，圖表初次繪製 300ms、資料更新 250ms；只動 `transform` 和 `opacity`，抬起感用 `box-shadow`；只有骨架和按鈕的轉圈可以一直循環。共用的 `--tc-ease-out`、`--tc-dur-1/2/3` 和 `prefersReducedMotion()` 已經在模組的 `motion.css`、`motion.js` 裡，直接用。

模組內的動畫（4.2、4.4、4.5、4.6、4.7）寫在 `motion.css`；按鈕按壓（4.1）和全域補充規則（4.9）寫在 `vuetify-overrides.css`。

### 4.1 按鈕按壓

- `:active` 時，Primary 和 Secondary 往下 `translateY(2px)`，`box-shadow` 換成 `0 0 0 var(--tc-btn-edge), var(--tc-press)`；文字按鈕和圖示按鈕往下 `translateY(1px)`，沒有陰影。
- `transform` 不加轉場（0ms），`box-shadow` 60ms、`background-color` 和 `border-color` 80ms，都是 Tactile 原值。Vuetify 的 `.v-btn` 預設對 `transform` 有 0.28s 轉場，覆寫時一定要把 `transform` 從 `transition-property` 拿掉。
- 全域關掉 ripple（3.1 的 defaults）。「資料源」和「我的儀表板」兩個清單按下去不位移，只保留 hover 底色和選中態。
- 減少動態效果時不降級：2px 的位移本來就是瞬間的。

### 4.2 骨架淡入

- 骨架先等 150ms 才開始淡入（120ms，`--tc-dur-1`），接著 shimmer 每 1.4s 循環一次（Tactile 原值）。本機 API 13–24ms 就回來，資料快的時候骨架根本不會出現，也就不會閃一下。
- 資料一到就用 `v-if` 拿掉骨架，不等動畫跑完。
- 明細表和錯誤警示（`v-alert`）出現時用 `@starting-style { opacity: 0 }` 配 `transition: opacity var(--tc-dur-1)` 淡入；圖表不必，ECharts 自己會進場。

### 4.3 圖表動畫調校

在 `ChartCard` 的 `option` computed 裡加，不改 `charts/*.js`：

- 每個 series 都加上 `animationDuration: 300`、`animationEasing: 'cubicOut'`、`animationDurationUpdate: 250`、`animationEasingUpdate: 'cubicOut'`。一定要寫在 series 層：ECharts 6 的圓餅、折線在自己的 series 預設裡寫了 1000ms、`linear` 之類的值，只寫在最外層蓋不過去。現有的 `toOption` 回傳的 `series` 都是陣列。
- 最外層加 `stateAnimation: { duration: 120, easing: 'cubicOut' }`（hover 強調，預設 300ms），`tooltip.transitionDuration: 0`（預設 0.4 秒，會拖在游標後面），保留原本 tooltip 的其他設定。
- 減少動態效果時：最外層 `animation: false`，`stateAnimation.duration` 為 0。每次算 option 時呼叫 `prefersReducedMotion()`。

### 4.4 打開已存儀表板時，卡片分批進場

- 對象：打開已存的儀表板時，每張卡片都進場。`App.vue` 的 `DashboardView` 帶 `:key="current.id"`，每次打開、切換都會重新掛載，所以每次都播一次。
- 預覽第一次顯示時卡片不進場：對話框本身已經有 4.6 的轉場，不再疊一層。預覽裡調整新增、復原放回的卡片照舊進場（上一份 spec 10.4）。
- 效果沿用現有的 `tc-card-enter`：200ms 淡入加 `translateY(6px)`，每張晚 30ms，第 6 張以後不再往後延（最多延遲 150ms），全部在 350ms 內到位。
- 錯開順序改成畫面順序：`--tc-i` 是這張卡片在「依 `(y, x)` 排序後的版面」裡的名次，不再是 `layout` 陣列的索引。使用者拖過版面之後，陣列順序不一定等於畫面順序。調整時保留的卡片仍排在前面、新增的排在後面，所以新增的卡片還是會在補位差不多完成時才到位。
- 資料還沒到的卡片照樣進場，裡面是骨架（4.2）或空白；資料到了，圖表再照 4.3 繪製。

### 4.5 拖拉與縮放時抬起

- 拖拉或縮放中（`.vgl-item--dragging`、`.vgl-item--resizing`），裡面的卡片 `box-shadow` 變成 `--tc-overlay-shadow`，並往上 `translateY(-2px)`；轉場 160ms（`--tc-dur-2`）、`--tc-ease-out`。往上 2px 跟按壓下沉的 2px 對稱。因為 `.v-card` 的覆寫帶 `!important`，這裡也要加。
- 拖拉中標題列游標改成 `grabbing`。
- 佔位框改成主色 10% 底加 1px 主色虛線、圓角 `--tc-radius-lg`，不再用套件預設的紅色 20%（紅色在這套系統代表錯誤）。
- 縮放時卡片維持不透明（`--vgl-item-resizing-opacity: 100%`），看得清自己在縮什麼。縮放把手顏色改成 `--tc-border-strong`，不再寫死 `#444`。
- 佔位移動 100ms、放開後歸位 200ms、其他卡片讓位 200ms，都是套件預設，不改。不做 spring 和慣性。
- 減少動態效果時：陰影照樣加深（那是層次，不是動態），位移和轉場歸零。

### 4.6 預覽對話框開關

- `AnalyzeButton` 的 `v-dialog` 加 `transition="tc-sheet"`，取代 Vuetify 預設的「從 90% 放大」。
- 打開：`opacity` 0 → 1、`translateY(12px)` → 0，200ms（`--tc-dur-3`）。關閉：`opacity` 1 → 0、`translateY(0)` → `translateY(6px)`，120ms（`--tc-dur-1`）。easing 都是 `--tc-ease-out`。
- 刪除確認的小對話框維持 Vuetify 預設（225／125ms，已經內建減少動態效果的處理）。

### 4.7 異常標籤淡入

異常檢查的結果比圖表資料晚到，標籤會在卡片畫好之後才冒出來。`.tc-badge` 在卡片裡用 `@starting-style { opacity: 0 }` 配 `transition: opacity var(--tc-dur-1) var(--tc-ease-out)` 淡入，不縮放、不彈跳、不閃爍。「符合需求」跟卡片同時出現，不另外做動畫。

### 4.8 資料源分頁不再左右滑

「上傳檔案」和「外部 API」不是前後頁，`DatasourcePanel` 的兩個 `v-window-item` 加 `:transition="false" :reverse-transition="false"`，切換時直接換內容。

### 4.9 減少動態效果

- `tactile-clean.css` 檔尾已經有 `* { transition-duration: .01ms !important; animation-duration: .01ms !important; }`，但它只縮短時長，沒有停掉循環，也沒有清掉延遲。實測只縮時長的骨架每一幀落在隨機位置，看起來就是閃爍（提案 5.2）。所以 `vuetify-overrides.css` 補上：

  ```css
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-iteration-count: 1 !important;
      animation-delay: 0s !important;
      transition-delay: 0s !important;
    }
  }
  ```

- `motion.css` 現有的 `@media (prefers-reduced-motion: reduce)` 區塊把 4.2、4.5、4.6、4.7 新加的轉場和動畫一併列進去，模組在沒有 App 層樣式時也守得住。
- ECharts 在 canvas 裡，CSS 管不到，照 4.3 用 option 關掉。
- 檢查方式：Chrome DevTools → Rendering → Emulate CSS media feature `prefers-reduced-motion: reduce`。

## 5. 會動到的檔案

| 檔案 | 改什麼 |
|---|---|
| `frontend/src/styles/tactile-clean.css` | 新增，從 skill 原樣複製 |
| `frontend/src/styles/vuetify-overrides.css` | 新增：按鈕、卡片、輸入框覆寫，4.1、4.9 |
| `frontend/src/main.js` | 樣式載入順序、`createVuetify` 的 defaults 和 theme |
| `frontend/src/App.vue` | 側欄底色、主區底色、刪除鈕尺寸 |
| `frontend/src/modules/dashboard/ChartCard.vue` | 標題列、badge、tag、骨架、主題、4.3 的 option 包裝、明細表數字 |
| `frontend/src/modules/dashboard/charts/themes/tactile-light.json` | 新增，從 skill 複製 |
| `frontend/src/modules/dashboard/DashboardView.vue` | 格線間距、4.4 的進場對象與畫面順序 |
| `frontend/src/modules/dashboard/AnalyzeButton.vue` | 對話框轉場、工具列、「調整」改 Secondary、復原提示外觀 |
| `frontend/src/modules/dashboard/DatasourcePanel.vue` | 4.8 |
| `frontend/src/modules/dashboard/motion.css` | 4.2、4.5、4.6、4.7 與減少動態效果的補充 |

## 6. 測試

Vitest（元件測試照現有做法，用 `test-vuetify.js` 掛真的 Vuetify；測試環境不載入 CSS，所以只驗 class、屬性和傳給 ECharts 的 option）：

- `ChartCard`
  - 沒有資料時有 `.tc-skeleton`，內容區 `aria-busy="true"`；資料到了骨架消失、`aria-busy` 拿掉。
  - 四種嚴重程度各自對應 3.5 表格裡的 class；「正常波動」沒有任何 `tc-badge--*` 修飾子。
  - 有 `options.focus` 時出現 `.tc-tag`「符合需求」。
  - 傳給 `VChart` 的 option：每個 series 都有 `animationDuration: 300`、`animationDurationUpdate: 250`；`tooltip.transitionDuration` 是 0 且原本的 tooltip 設定還在；`theme` prop 是 tactile 主題。
  - `matchMedia` 回傳 `matches: true` 時，option 有 `animation: false`、`stateAnimation.duration` 為 0。
  - 明細表的數字格子有 tabular-nums 的 class，文字格子沒有。
- `DashboardView`
  - 已存儀表板第一次顯示：每張卡片都有 `tc-card-enter`，`--tc-i` 照 `(y, x)` 排序的名次給。
  - 預覽第一次顯示：沒有卡片帶 `tc-card-enter`。
  - 調整新增的卡片照舊進場，且 `--tc-i` 排在保留的卡片之後。上一份 spec 為「第一次打開不做進場」寫的測試，改成只驗預覽。
- `DatasourcePanel`：兩個 `v-window-item` 的轉場關掉。
- `charts.test.js` 的快照不變；`npm test` 全綠；`npm run build` 通過。

手動（開 dev server，在自己的分頁操作）：

- 按住空白鍵，按鈕立刻下沉 2px，旁邊的按鈕不動；放開立刻彈回。
- 打開、切換已存儀表板：卡片由左上到右下依序淡入，沒有從別的位置滑過來。
- 網路設 Slow 4G：先看到骨架淡入並有 shimmer；資料到了換成圖表，圖表 300ms 內長完。
- 拖拉、縮放卡片：卡片抬起、佔位是主色虛線、縮放時不透明。
- 打開、取消預覽：面板往上浮、往下淡出，沒有縮放。
- DevTools 模擬 `prefers-reduced-motion: reduce`：以上全部瞬間完成，骨架靜止不閃，圖表沒有進場動畫。
- `python .claude/skills/modern-erp-ui/scripts/check_tactile_css.py <檔案>` 對 `frontend/src/styles/vuetify-overrides.css` 和 `frontend/src/modules/dashboard/motion.css` 各跑一次（腳本一次只吃一個檔案），都要通過，確認互動狀態沒有動到版面屬性。
- 用 `references/wcag-checks.md` 的 console 腳本量對比和 24×24 點擊範圍，特別是刪除鈕、「✕」和敏感欄位勾選框（全域 `density: 'compact'` 會把勾選框縮小）。

## 7. 文件

`docs/backlog.md`：加入第 2 節「不做」的深色模式、刪除確認對話框改版；如果 3.7 之後散點圖標籤還是重疊，也記進去。README 沒有描述外觀，不用改。
