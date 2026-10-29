# JEV 儀表板：本期重點（設計）

日期：2026-10-23

前提：[KPI 大數字卡 spec](2026-10-07-kpi-card-design.md)（下稱「KPI spec」）、[儀表板篩選器 spec](2026-10-07-dashboard-filters-design.md)、[自動更新 spec](2026-10-07-dashboard-auto-refresh-design.md)。本文件只寫這次改變的部分。

## 1. 問題

儀表板有十幾張卡時，要一張一張看徽章，才知道這一期哪裡出了狀況。backlog「補齊」P2：JEV 替每張卡這一期的變化打分數，儀表板最上面列出前三名（參考 Tableau Pulse 的 digest）。

異常檢查（`/highlight`）已經對每張摘要得了的卡問 JEV 兩題：「有沒有值得標註的異常」（noul）和「這次變化的嚴重程度」（score，四級）。本期重點不多問 JEV，直接拿這兩題的答案排名。題目不變，現有的答案快取照樣命中，照 jev-1.13.0 調好的門檻也不用重調。

## 2. 規則

### 2.1 哪些卡會列進來

- 異常檢查有標出來（noul 大於 0.7），而且嚴重程度是「值得注意」以上的卡。「正常波動」的卡照舊有徽章，不列進本期重點。
- 排序：嚴重程度由高到低；同一級時 noul 大的先；再一樣就照畫面上的順序（先上後下、先左後右）。嚴重程度用取整後的等級比，不比 score 的小數（jevai.wiki 說期望值只適合判斷門檻）。
- 最多 3 筆。
- 已經從畫面上拿掉的卡不列，載入失敗的卡也不列。前三名的卡被拿掉時，第四名補上來。

### 2.2 每一筆寫什麼

```
本期重點
[需要關注] 營收 依 區域   2026-09 比 2026-08，南區 ▼ 18%
[值得注意] 退貨率         2026-09 比 2026-08 ▲ 0.8 個百分點
[值得注意] 數量           2026-Q3 比 2026-Q2 ▲ 12%
```

- 徽章：嚴重程度，顏色跟卡片上的徽章一樣。
- 標題：卡片上看到的標題（`/data` 的 `title`，沒有就用卡片設定的 `title`）。
- 期別：異常檢查比的那兩期，也就是最後一個已經結束的期和它前一個有資料的期，跟 KPI 卡同一套規則。
- 系列：卡片有好幾條系列時，寫變化最大的那一條，「變化最大」是兩期相減的絕對值最大。送給 JEV 的摘要超過 10 條系列時，也是用這個標準留下 10 條。挑的一定是卡片上畫得出來的系列，不會是合併出來的「其他」。兩個數值的圖和對照圖，系列名稱用卡片圖例上的顯示名稱。只有一條系列的卡不寫系列。
- 變化：寫法跟 KPI 卡一模一樣，用同一段程式（KPI spec 2.1）。一般數字寫百分比到小數一位；比率寫百分點；前一期是 0 時寫「增加 1,200」；格式化後是 0 寫「持平」。箭頭用中性色。
- 按一筆會捲到那張卡，焦點也移到那張卡上。

### 2.3 沒有可列的卡、檢查失敗

- 檢查了 N 張卡，沒有一張符合 2.1：寫「本期沒有需要注意的變化（檢查了 N 張卡）」。N 是送去 JEV 的卡，扣掉之後從畫面上拿掉的。
- 沒有任何卡送去 JEV（沒有摘要得了的卡，或都用到敏感欄位）：整塊不顯示。
- JEV 失敗、資料載入失敗、`/highlight` 還沒回來：整塊不顯示。沒有檢查過，就不能說「沒有需要注意的變化」。

### 2.4 跟其他功能

- 誰看得到：跟卡片徽章一樣。擁有者、被分享的檢視者、沒登入的訪客打開已存的儀表板都看得到。製圖頁的預覽不做異常檢查，所以沒有本期重點。
- 篩選器：照篩選後的資料（異常檢查本來就是）。改篩選時本期重點先消失，新的結果回來再出現，跟徽章一樣。
- 自動更新：舊的本期重點留著，新的結果回來才換，跟徽章一樣。
- 拿掉卡片：那一筆馬上消失，N 少一，不重新檢查。
- 敏感欄位：用到敏感欄位的卡本來就不送 JEV，所以不會出現在本期重點，也不算進 N。

## 3. 資料：`GET /api/jev/highlight/{dashboardId}`

網址和權限不變。回應從陣列改成物件：

```json
{
  "checked": ["c1", "c2", "c4"],
  "highlights": [
    {
      "chartId": "c1", "severity": "需要關注", "level": 2, "noul": 0.85,
      "change": {
        "previousPeriod": "2026-08", "period": "2026-09",
        "measure": null, "series": "南區",
        "previous": 1250000.0, "current": 1025000.0, "percent": false
      }
    }
  ]
}
```

- `checked`：這次送去 JEV 的卡片，照 `layout` 的順序。
- `highlights`：跟以前一樣只有 noul 超過門檻的卡，多了 `level`（`SEVERITY` 裡的位置，0～3）和 `change`。
- `change`：
  - `measure`：兩個數值的圖和對照圖是 `mapping.measures` 裡的位置（從 0 算），其他是 null。前端拿 `/data` 的 `dimensions[measure + 1]` 當名稱，跟圖例一樣。
  - `series`：有系列欄位的圖是系列的值（例如「南區」），其他是 null。
  - `measure` 和 `series` 都是 null：卡片只有一條系列，不寫名稱。
  - `previous`、`current`：這條系列兩期的原始值。比率是小數（0.052），跟 `/data` 一樣，沒有四捨五入。
- JEV 失敗：回 204，沒有 body（`api.js` 會轉成 `null`）。不回 5xx，因為沒設 `TYPESAFE_API_KEY` 的環境每次打開儀表板，瀏覽器 console 都會多一條錯誤。
- 沒有卡片摘要得了：回 200 `{"checked":[],"highlights":[]}`，不問 JEV。
- 資料載入失敗：跟以前一樣回錯誤，檢視者看到通用訊息。

## 4. 後端改動

- `HighlightSummarizer`：
  - `summarize` 改回傳 `Summary(Map<String, Object> state, Highlight.Change change)`。`state` 是送給 JEV 的摘要，內容一個字都不變。
  - 在超過 10 條合併成「其他」之前挑 `change` 的系列，跟 `keepBiggestChanges` 共用同一個排序：變化絕對值大的先，同分照名稱。
  - 比率前後兩期有空值的系列照舊先拿掉，所以挑不到它。
- `Highlight`：`record Highlight(String chartId, String severity, int level, double noul, Change change)`，`Change` 是巢狀的 record。
- `HighlightService`：
  - `check` 改回傳 `record Check(List<String> checked, List<Highlight> highlights)`。JEV 失敗照舊回 null，不快取。
  - 快取改存 JEV 的答案（`Map<String, Answer>`），key 不變。標註和 `change` 每次用這次的資料重組。比率的摘要是四捨五入過的百分點，原始值差在四捨五入以下時 key 一樣，數字還是要用這次的。
- `JevController.highlight`：回 `ResponseEntity<HighlightService.Check>`。沒有卡片摘要得了時回空的 `Check`；`check` 回 null 時回 204。
- 不改資料庫，不加新的 JEV 題目，不加新的 endpoint。新 endpoint 要重新讀一次資料來源，外部 API 會被多打一次，因為 `/data` 留給 `/highlight` 的資料（`DashboardDataService.recent`）用一次就丟。

## 5. 畫面

### 5.1 前端程式

- `kpi.js`：把 `change()` 算變化的部分抽成 `export function delta(value, before, percent)`，回傳「▲ 18%」「▼ 0.8 個百分點」「增加 1,200」「持平」這幾種。`change()` 改成 `比 ${period} ${delta(…)}`，KPI 卡的字不變。
- 新增 `highlights.js`：
  - `SEVERITY_CLASS` 從 `ChartCard.vue` 搬過來，卡片徽章和本期重點共用。
  - `digest(check, cards)`：`check` 是 `/highlight` 的回應（沒有結果時是 null）；`cards` 是畫面上的卡片 `[{ spec, data }]`，照畫面順序。回傳 null 時整塊不顯示，否則回傳 `{ checkedCount: N, entries: [{ chartId, severity, title, text }] }`。2.1～2.3 的規則都在這個函式裡。
- `DashboardView.vue`：
  - `highlightById` 從 ref 改成從 `check`（新的 ref，存 `/highlight` 的回應）算出來。卡片徽章和本期重點都從 `check` 來。
  - 原本拿掉卡片時另外過濾 `highlightById` 的那一行刪掉，兩邊都照目前的 `layout` 算。
  - 改篩選時 `check` 設成 null，`/highlight` 失敗也是 null。
  - `focusCard(chartId)`：用 `data-chart-id` 找到卡片，`scrollIntoView`（減少動態效果時不平滑捲動），再把焦點移到卡片上（卡片加 `tabindex="-1"`）。
- `ChartCard.vue`：`SEVERITY_CLASS` 改從 `highlights.js` 匯入。

### 5.2 版面

- 位置：篩選列下面、卡片格線上面，不放進格線裡（格線的卡片可以拖拉）。左右邊對齊格線裡的卡片。
- 一塊跟卡片同材質的面板（表面色、同樣的圓角），標題「本期重點」，下面一筆一列，列和列之間用細線隔開。
- 每一列是一顆按鈕：徽章、標題（粗體，太長就截斷）、變化（次要文字色，`font-variant-numeric: tabular-nums`）。窄螢幕放不下時，變化換到下一行。
- 不加單邊的色條，箭頭和變化用中性色。
- 沒有可列的卡時，標題下面只寫 2.3 那句。
- 不先佔位置，結果回來才出現。不讀給螢幕閱讀器聽，因為自動更新時每次都會唸一次。
- 2026-10-23 在真的儀表板上比過三種排法：清單、三格、一行。選清單，因為三格看起來像可以拖拉的卡片，窄螢幕上高度是清單的三倍；一行的三筆擠在一起換行，不好掃讀。

## 6. 測試

### 6.1 後端

- `HighlightSummarizerTest`：
  - 只有一條系列的卡：`change` 的 `measure`、`series` 都是 null，兩期跟 `state` 一樣。
  - 有系列的卡挑變化絕對值最大的系列，同分照名稱，資料列順序打亂也一樣。
  - 超過 10 條、合併出來的「其他」比任何一條變得多：挑的還是真的系列。資料裡本來就有一條叫「其他」的系列時，挑到它的話數字是它自己的，不含合併進去的部分。
  - 兩個數值的圖：`measure` 是 `mapping.measures` 的位置。
  - 比率：`previous`、`current` 是原始小數，`percent` 是 true；有空值的系列挑不到。
  - KPI 卡跟一樣資料的折線圖得到一樣的 `change`。
  - 原本對摘要內容的斷言改成 `.state()` 之後全部通過，送 JEV 的內容不變。
- `HighlightServiceTest`：
  - `checked` 是摘要得了的卡，照 `layout` 順序，圓餅圖不在裡面。
  - 沒有卡片摘要得了：`checked` 是空的，不問 JEV。
  - JEV 失敗回 null，下次再問。
  - 快取：兩次請求的資料只差在四捨五入以下（`state` 一樣），只問 JEV 一次，但兩次回應的 `change` 各自是那次的原始值。
  - 沒有 score 時等級是 1（值得注意），score 夾在 0～3。
- `JevControllerTest`：
  - 回應是 `{checked, highlights}`，標註帶 `level` 和 `change`。
  - JEV 失敗回 204，擁有者、檢視者、訪客都一樣。
  - 沒有卡片摘要得了回 200 `{"checked":[],"highlights":[]}`。
  - 用到敏感欄位的卡不在 `checked`，也不在 `highlights`。

### 6.2 前端

- `kpi.test.js` 原本的測試不改，全部通過（`delta` 抽出來之後 KPI 卡的字不變）。
- `highlights.test.js`（新增）：
  - `check` 是 null 回 null；`checked` 裡的卡都不在畫面上也回 null。
  - 排序：等級、noul、畫面順序；正常波動不列；最多 3 筆。
  - 前三名裡有一張被拿掉，第四名補上，N 少一。
  - 標題用 `data.title`，沒有就用 `spec.title`；兩個數值的圖用 `dimensions[measure + 1]`；`series` 照原樣寫；都是 null 不寫名稱也不加逗號。
  - 文字跟 KPI 卡一樣：一般百分比、比率百分點（原始值 5.24% → 6.06% 寫 0.8 個百分點，不是四捨五入後相減的 0.9）、前一期是 0、持平、季的期別。
  - 卡片這次的資料有 `error` 時不列，也不丟例外。
  - 檢查了 N 張、沒有符合的：回 `{ checkedCount: N, entries: [] }`。
- `DashboardView.test.js`：
  - `/highlight` 的假資料從 `[]` 改成 `{ checked: [], highlights: [] }`。
  - 已存的儀表板：本期重點照順序列出；按一筆會對那張卡呼叫 `scrollIntoView` 並把焦點移過去，減少動態效果時不平滑捲動。
  - `/highlight` 失敗或回 204：沒有本期重點，也沒有徽章。
  - 檢查了 N 張、沒有符合的：寫出那句話和 N。
  - 改篩選：本期重點先消失，新結果回來才出現，中間不會閃出「沒有需要注意的變化」。
  - 自動更新：舊的留著，新的回來才換。
  - 拿掉一張被列出的卡：那一筆消失、N 少一，不重打 `/highlight`。
  - 舊的回應晚到：不蓋掉新的。
  - 唯讀檢視、訪客：看得到。預覽：沒有。

### 6.3 瀏覽器

照 `.claude/skills/verify-jev-dashboard` 跑，由沒有寫這段程式的 agent 做：

- 打開有異常標註的已存儀表板：本期重點的筆數、順序跟 `/highlight` 對得上（等級 1 以上、照等級排），標題跟卡片標題一樣，數字跟卡片上的資料對得上。
- 對照圖的那一筆寫的是顯示名稱。
- 按一筆會捲到那張卡。
- 改篩選、拿掉一張列出的卡、用檢視者和訪客的身分看。
- 桌機寬和 390 寬各截一張，沒有橫向捲動，console 沒有錯誤。
- 自己讀每一張截圖。

## 7. 文件

- `README.md`：補一段本期重點。
- `docs/backlog.md`：「本期重點」劃掉，寫上完成說明；第 8 節列進 backlog。
- `docs/testing/e2e-playbook.md`：S08-4、S18-14 改成新的回應格式；加一節 S25 涵蓋 6.3。
- `.claude/skills/verify-jev-dashboard/features/`：`saved-dashboard.md` 的異常標註改成新格式，新增 `period-digest.md`。

## 8. 不做

- 多問 JEV 一題「重要性」。
- 製圖頁預覽裡的本期重點。
- 結果回來前先佔位置。
- 「看全部」、收合、關掉本期重點。
- 用百分比挑「變化最大」的系列。
- 修 `HighlightSummarizer` 把數值的空值當 0 的行為（對照來源有一邊還沒更新時，JEV 看到的數值會掉到 0）。改了會動到送 JEV 的內容，門檻要重調，另外記進 backlog。

## 9. 決定（2026-10-23）

| 題目 | 選擇 | 沒選的 |
|---|---|---|
| 哪些卡 | 只有被標異常、值得注意以上的卡，最多 3 張（使用者選） | 所有能比較的卡，永遠列 3 張 |
| 每一筆寫什麼 | 嚴重程度、標題和數字，按了捲到卡片（使用者選） | 只寫嚴重程度和標題 |
| 排名依據 | 現有的嚴重程度答案，取整後比 | 多問 JEV 一題「重要性」（要重調門檻，快取全部失效） |
| 版面 | 清單面板 | 三格、一行 |
| JEV 失敗 | 回 204，不顯示 | 回 502（console 每次都有錯）、回空結果（跟沒有卡能檢查分不開） |
| 變化最大 | 絕對值最大，跟送 JEV 時留 10 條的標準一樣 | 百分比最大（小基數會排到前面） |
| 排名和文字在哪裡做 | 前端：顯示名稱和 KPI 的寫法都在前端 | 後端排好、寫好文字（要在 Java 複製一份 KPI 的寫法） |
| 兩個數值的圖怎麼對名稱 | 回 `measures` 的位置 | 回原始欄位名稱，前端反查 |
