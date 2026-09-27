# JEV 儀表板：一份儀表板接多個資料來源（設計）

日期：2026-09-27
前提：主設計見 `docs/superpowers/specs/2026-09-23-jev-dashboard-design.md`，需求分析見 `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`。本文件只寫這次新增和改變的部分。這是多來源規劃的子專案 A；合併來源（B1）、對照（B2）、比率（B3）另有 spec。

## 1. 目的

高階主管看的儀表板通常同時有銷售、退貨、庫存等好幾個來源。目前來源綁在整份儀表板上（`dashboard.datasource_id`），每張圖都讀同一份資料，想把「銷售趨勢」和「退貨原因」放在一起就做不到。

這次改成每張圖各自記錄自己的來源。製圖頁仍然一次分析一個來源，存檔時可以選擇存成新的儀表板，或加到既有的儀表板。

## 2. 範圍

做：

- `ChartSpec` 加 `datasourceId`，拿掉儀表板層的來源欄位；Flyway V2 遷移舊資料。
- 製圖頁存檔區分成「存成新儀表板」和「加到既有儀表板 ▾」。
- 新 API `POST /api/dashboards/{id}/charts`：把一批圖接在既有儀表板最下面。
- 「依據」改成按每次分析分組記錄。
- 已存儀表板的每張卡片（含明細表）都有「✕」，按下後先確認，確認才存檔。
- 多來源的儀表板，卡片標題列顯示來源名稱。
- 載入資料和異常檢查改成依來源分組，敏感欄位依各自來源判斷。
- 順手解掉 backlog 第 42 行「PUT 更新儀表板時 body 的 `datasourceId` 被忽略」：來源改成記在每張圖上，PUT 也會檢查。

不做的見第 11 節。

## 3. 畫面與流程

### 3.1 製圖頁存檔區

預覽上方的操作列改成：

```
[儀表板名稱______]            [捨棄] [存成新儀表板] [加到既有儀表板 ▾]
```

- 「存為我的儀表板」改名「存成新儀表板」，行為不變：名稱空白時不能按。
- 「加到既有儀表板 ▾」打開選單，列出自己所有的儀表板，順序跟儀表板頁左側清單一樣（最近更新的在最上面）。清單由 App 用 prop 傳進來，不另外打 API。
- 一份儀表板都沒有時，這顆按鈕不顯示，只留「存成新儀表板」。
- 加到既有儀表板不會用到預覽的名稱，所以名稱空白時照樣可以按；分析或調整進行中不能按，跟「存成新儀表板」一樣。
- 選了一份儀表板之後，呼叫 6.3 的 API。成功就捨棄預覽，切到儀表板頁，打開那份儀表板，流程跟存成新儀表板相同（沿用 `saved` 事件）。
- 失敗時錯誤顯示在預覽上方，預覽保留。回 404（儀表板在別的分頁被刪了）時，另外通知 App 重新抓儀表板清單，選單裡就不會再出現那份。

「調整」送出時，`datasourceId` 改用左側選單目前選的來源。換來源時預覽本來就會被捨棄，兩者一定一致。原本讀的是 `preview.datasourceId`，這個欄位已經拿掉了。

### 3.2 已存儀表板：來源名稱

- 儀表板裡的圖來自兩個以上的來源時，每張卡片在標題右邊用次要文字色的小字標出來源名稱。名稱太長就截斷，滑鼠移上去看得到全名。
- 只有一個來源時不顯示，跟現在一樣。預覽一定只有一個來源，所以也不顯示。
- 是不是多來源，看 `layout` 裡不重複的 `datasourceId` 有幾個；名稱取自 `/data` 回傳的 `ChartData.source`（第 7 節）。

### 3.3 已存儀表板：依據

- 依據改成分組（4.2），一組一行。
- 單一來源的儀表板：每行跟現在一樣是「依據：a → b → c」。
- 多來源的儀表板：每行寫成「依據（R7 Excel 退貨）：各區退貨 → 不要圓餅圖」。來源名稱要等 `/data` 回來才知道，在那之前先不顯示這幾行；找不到名稱（例如來源載入失敗）時就不加括號。
- 沒有任何分組時不顯示，跟現在沒有需求紀錄的儀表板一樣。

### 3.4 已存儀表板：移除單張圖

- 每張卡片都有「✕」，包括明細表。每次「加到既有儀表板」都會多一張明細表，要能拿掉。預覽裡明細表照舊沒有「✕」。
- 按「✕」跳出確認對話框，樣式沿用 App 的「刪除儀表板」：
  - 標題「移除圖表」
  - 內容「確定從「{儀表板名稱}」移除「{卡片標題}」？移除後無法復原，要加回來得重新分析。」
  - 按鈕「取消」「移除」
- 確認後送 PUT，`layout` 是目前格線上的版面（使用者拖過的位置照樣保留）再拿掉這張。成功後 DashboardView 通知 App 換成回傳的設定，卡片照需求分析 spec 10.5 的方式退場。
- 移除之後，其他卡片不重抓資料、不閃載入中，異常標註也保留。預覽裡「只是少了卡片就不打 API」的規則延伸到已存儀表板。
- PUT 失敗：錯誤顯示在儀表板上方，卡片留著。
- 不做復原提示。

### 3.5 空的儀表板

最後一張圖也可以移除。儀表板沒有任何圖時，不顯示「找不到適合的圖表」，改顯示：「這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。」

## 4. 資料模型

### 4.1 `ChartSpec`

```java
public record ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                        Map<String, Object> options, UUID datasourceId) {
    // 沿用 DashboardConfig 的寫法，保留 6 個參數的建構子（datasourceId = null），ConfigAssembler 和測試不必全改
    public ChartSpec withDatasourceId(UUID value) { … }
}
```

- `ConfigAssembler` 產生的圖沒有來源，由 `AnalyzeService` 在最後把這次分析的來源寫進每一張，包含調整模式留下來的圖。
- `ConfigAssembler.at()`、`withFocus()` 重建 `ChartSpec` 時要把 `datasourceId` 一併帶過去。

### 4.2 `DashboardConfig` 與依據分組

```java
public record DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout,
                              List<String> intents, List<Basis> basis) {
    public record Basis(UUID datasourceId, List<String> intents) {}
}
```

- 拿掉 `datasourceId`。分析結果、預覽、存檔都只看每張圖自己的來源。
- `intents`：一次分析的需求紀錄。分析回傳的預覽帶它；建立儀表板和「加到既有儀表板」的 request 也帶它。已存的儀表板不回傳這個欄位。
- `basis`：已存儀表板的依據，每組是「一次分析的來源＋需求紀錄」，照加入順序排。
  - 建立：`intents` 不是空的，就產生一組 `{這批圖的來源, intents}`；空的就不產生，`basis` 是空的。
  - 加到既有儀表板：`intents` 不是空的，就在最後追加一組。同一個來源加第二次，也是新增一組，不併進舊的那組。
  - PUT：用 body 送來的 `basis`（前端拿到什麼就原樣送回），驗證後刪掉「`datasourceId` 在新 `layout` 裡已經沒有任何圖」的分組。
  - 已知限制：分組是依來源刪的。同一個來源加過兩次、其中一批圖全部移除時，那一組依據會留著；要做到精準，得在分組裡記 `chartIds`。
- JSON 照舊省略空值。

### 4.3 `DashboardSummary`

改成 `DashboardSummary(UUID id, String name, OffsetDateTime updatedAt)`。前端只用 `id` 和 `name`，拿掉 `datasourceId` 不影響畫面。

### 4.4 `config_json`

```json
{
  "layout": [ { "chartId": "c1", "type": "line", "datasourceId": "…", "gridPos": {…}, "mapping": {…} } ],
  "basis": [ { "datasourceId": "…", "intents": ["各區每月營收", "不要圓餅圖"] } ]
}
```

`DashboardRepository.Stored` 改成 `Stored(List<ChartSpec> layout, List<Basis> basis)`，insert、update 不再寫 `datasource_id`，list、find 也不再讀。

## 5. 資料庫遷移

新增 `V2__per_chart_datasource.sql`：

```sql
update dashboard d
set config_json = jsonb_set(d.config_json - 'intents', '{layout}',
        coalesce((select jsonb_agg(e || jsonb_build_object('datasourceId', d.datasource_id) order by n)
                  from jsonb_array_elements(d.config_json -> 'layout') with ordinality as t(e, n)),
                 '[]'::jsonb))
    || case when jsonb_typeof(d.config_json -> 'intents') = 'array' and d.config_json -> 'intents' <> '[]'::jsonb
            then jsonb_build_object('basis', jsonb_build_array(
                     jsonb_build_object('datasourceId', d.datasource_id, 'intents', d.config_json -> 'intents')))
            else '{}'::jsonb
       end;

alter table dashboard drop column datasource_id;
```

要點：

- `layout` 是空陣列時，`jsonb_agg` 回 NULL，用 `coalesce` 補回 `[]`。
- `with ordinality` 搭配 `order by n`，卡片順序不變。
- 舊的 `intents` 轉成只有一組的 `basis`，然後刪掉 `intents`。沒有 `intents`、或是空陣列時不產生 `basis`。判斷用 `jsonb_typeof`，不用 `jsonb_array_length`，遇到不是陣列的值也不會出錯。
- 外鍵跟著欄位一起刪。目前沒有刪除資料來源的 API，少了外鍵不會出現指到不存在來源的圖；之後要做刪除來源時，再決定用到它的圖怎麼處理。

## 6. API

### 6.1 分析 `POST /api/jev/analyze`

request 不變。回傳的 `DashboardConfig` 沒有 `datasourceId`，`layout` 裡每張圖都有 `datasourceId`，值就是這次分析的來源。調整模式送來的 `current` 也要每張都有 `datasourceId`（共用卡片驗證，見 6.6）。

### 6.2 建立 `POST /api/dashboards`

body：`{ name, layout, intents? }`。`layout` 至少一張，而且必須來自同一個來源（一批圖就是一次分析的結果），依 4.2 產生 `basis`。回傳存好的設定。分析結果至少有一張明細表，所以從製圖頁存檔不會碰到空的 `layout`。

### 6.3 加到既有儀表板 `POST /api/dashboards/{id}/charts`

body：

```json
{
  "layout": [ /* ChartSpec[]：預覽裡的圖，含明細表 */ ],
  "intents": ["各區退貨", "不要圓餅圖"]
}
```

處理順序：

1. 找不到儀表板，或不是自己的：404「找不到儀表板」。
2. 驗證這批圖：至少一張、來自同一個來源、來源是自己的、每張卡片都合法、`intents` 合法（6.6）。這批圖自己的 chartId 不能重複，但可以跟既有的圖重複，下一步會重新編號。
3. chartId 接續編號：`n` 取既有 `layout` 的 `ConfigAssembler.maxChartNumber`，新圖依序改成 `c{n+1}`、`c{n+2}`…，不管它原本叫什麼。`maxChartNumber` 目前是 package-private，要改成 public。
4. 排版：`offset` 取既有圖 `y + h` 的最大值（沒有圖時是 0）。新圖的 `y` 改成 `y − 這批圖的最小 y + offset`，`x`、`w`、`h` 不變，所以這批圖保持原本的相對位置，整批接在最下面。
5. `intents` 不是空的，就依 4.2 追加一組 `basis`。
6. 名稱不變，body 就算帶了 `name` 也不理；`updated_at` 更新。
7. 回傳更新後的完整設定，格式跟 `GET /api/dashboards/{id}` 一樣。

讀出既有設定到寫回，在同一個 transaction 裡做，讀的時候用 `select … for update` 鎖住那一列，兩次同時加入時不會吃掉其中一批。PUT 還是跟現在一樣，後寫的蓋掉先寫的。

### 6.4 更新 `PUT /api/dashboards/{id}`

body：`{ name, layout, basis }`，帶了 `intents` 也不理。拖拉版面、移除單張圖都走這支。`layout` 可以是空的（移除最後一張圖），也可以混著不同來源；每個不重複的 `datasourceId` 都要是自己的來源，否則 404「找不到資料源」。這就是 backlog 第 42 行的修正。`basis` 依 4.2 驗證和清理。

### 6.5 預覽與資料

- `POST /api/dashboards/preview/data`：body 驗證只有 `requireComplete`（名稱、卡片），不檢查「同一個來源」和「至少一張」。來源是不是自己的，交給載入時的 404 處理，跟現在一樣。
- `GET /api/dashboards/{id}/data`：載入規則見第 7 節。

### 6.6 驗證訊息

驗證失敗回 400 `{"error": …}`，來源或儀表板找不到回 404：

| 情況 | 狀態 | 訊息 |
|---|---|---|
| 建立、PUT、預覽缺 `name` 或 `layout` | 400 | name、layout 都必填（原本是「name、datasourceId、layout 都必填」） |
| 某張卡片沒有 `datasourceId` | 400 | 圖表 {chartId}：缺少 datasourceId |
| 建立或加入的這批圖來自不同來源 | 400 | 一次存入的圖表要來自同一個資料來源 |
| 建立或加入時 `layout` 是空的 | 400 | 沒有要存入的圖表 |
| `basis` 某一組沒有 `datasourceId` | 400 | 依據缺少 datasourceId |
| `basis` 某一組的 `intents` 不合規 | 400 | 沿用 `requireValidIntents` 的訊息 |
| 其他卡片問題 | 400 | 沿用現有的卡片驗證訊息 |
| 來源不存在或不是自己的 | 404 | 找不到資料源 |
| 儀表板不存在或不是自己的 | 404 | 找不到儀表板 |

「缺少 datasourceId」放進 `DashboardService.cardProblem`，存檔、預覽和調整模式的 `current` 共用。

## 7. 載入與異常檢查

`DashboardDataService.display()` 和 `load()`：

- 把 `layout` 依 `datasourceId` 分組，每個來源只載入一次，各組用自己的資料列和自己的 schema 算「欄位還在不在」和顯示名稱。
- 回傳的 `ChartData` 照 `layout` 原本的順序排。
- `ChartData` 加 `source`（來源名稱），給 3.2、3.3 用。
- 部分來源載入失敗（外部 API 掛掉、超過筆數上限等）：用到這些來源的卡片回 `ChartData`，`error = "SOURCE_FAILED"`，`source` 是來源名稱（知道的話），其他卡片照常顯示。伺服器記一筆 warn log，內容是錯誤訊息。
- 全部來源都失敗：直接丟出第一個錯誤，跟現在一樣，畫面顯示整份的錯誤訊息。單一來源的儀表板和預覽，行為都跟現在相同。
- 卡片遇到 `SOURCE_FAILED` 時顯示警告：「資料來源「{source}」載入失敗，請稍後重新整理」；不知道名稱時寫「資料來源載入失敗，請稍後重新整理」。

`JevController.highlight`：

- 敏感欄位依每張圖自己的來源判斷：先把每個來源的敏感欄位集合各取一次，再對每張圖用它自己來源的集合檢查。兩個來源有同名欄位、只有其中一邊標成敏感時，只會跳過那一邊的卡片。
- `SOURCE_FAILED` 的卡片，`HighlightSummarizer.summarize` 看到 `error` 就回 null，本來就不會送出，不用另外處理。
- `HighlightService` 的 cache key（`dashboardId:指紋:日期`）不變。加圖、移除圖都會讓資料的指紋改變，自然會重算。

## 8. 錯誤處理與邊界情況

| 情況 | 結果 |
|---|---|
| 加到的儀表板已經被刪掉 | 404，製圖頁顯示錯誤、保留預覽，App 重新抓儀表板清單 |
| 同一個來源加兩次 | 圖不去重，兩批都加進去；使用者用「✕」自己拿掉不要的 |
| 某個來源載入失敗 | 只有那個來源的卡片顯示錯誤（第 7 節）；全部失敗才整份顯示錯誤 |
| 移除最後一張圖 | 允許，顯示 3.5 的空狀態 |
| 移除某個來源的最後一張圖 | 那個來源的依據分組跟著刪掉（4.2） |
| 舊儀表板 | V2 遷移後照常打開，依據顯示方式不變 |
| 圖指到自己另一個來源的欄位（手動 PUT） | 允許存檔；欄位不存在時卡片照現有規則顯示「欄位遺失」 |

## 9. 測試

**後端**

- 遷移：開一個獨立 schema，先用 Flyway `target("1")` 跑到 V1，插入舊格式的儀表板，至少三筆：有 `intents`、沒有 `intents`、`layout` 是空陣列。再跑到最新版，檢查三件事：每張圖都有 `datasourceId` 而且順序不變；`intents` 轉成一組 `basis`；空的 `layout` 還是 `[]`。最後確認 `datasource_id` 欄位已經不在。
- `DashboardService`／`DashboardControllerTest`：
  - 建立：依 `intents` 產生 `basis`、沒有 `intents` 就沒有分組、混著不同來源回 400、別人的來源回 404。
  - PUT：別人的或不存在的來源回 404（backlog 第 42 行）、混合來源可以存、刪掉已經沒有圖的依據分組。
  - 加入：chartId 接續（既有 c1–c5，新的一批變成 c6 以後）、y 位移和相對位置、`basis` 追加、沒有 `intents` 不追加、名稱不變、空 layout 400、混合來源 400、儀表板不存在 404、別人的來源 404。
  - 建立：空 layout 400；PUT：空 layout 可以存。
  - 清單的回傳不再有 `datasourceId`。
- `DashboardDataService`：兩個來源各只載入一次（計算 provider 被呼叫的次數）、回傳順序照 layout、顯示名稱各用各的 schema、`source` 有值、部分失敗時只有那些卡片是 `SOURCE_FAILED`、全部失敗時丟出錯誤。
- `JevController.highlight`：兩個來源有同名欄位，只有一邊標成敏感，只跳過那一邊的卡片。
- `AnalyzeService`：每張輸出的圖都有這次的 `datasourceId`，調整模式留下來的圖也有；回傳的設定沒有儀表板層的 `datasourceId`。
- 現有測試配合建構子和 JSON 格式改動更新，其餘照舊通過。

**前端**

- `ChartBuilder`：兩顆存檔按鈕；選單列出 prop 傳進來的儀表板；沒有儀表板時不顯示選單按鈕；名稱空白時仍能加到既有儀表板；加入時呼叫 `api.appendCharts(id, { layout, intents })`，成功後送出帶目標 id 的 `saved`；失敗時保留預覽，404 時通知 App 重新抓清單；「調整」送的是左側選中的來源 id。
- `DashboardView`：
  - 已存儀表板每張卡片都有「✕」，包括明細表。現有的「saved dashboards: no ✕」測試要改寫。
  - 按「✕」出現確認對話框；取消什麼都不做；確認後 PUT 少了那張的設定，並送出 `updated`。
  - 移除後其他卡片不重抓資料、異常標註保留。
  - 空儀表板的提示文字。
  - 依據：單一來源的格式、多來源的格式、多組依據。
  - 只有多來源時卡片才顯示來源名稱。
- `ChartCard`：來源名稱的顯示和截斷、`SOURCE_FAILED` 的警告文字。
- `App`：把儀表板清單傳給 `ChartBuilder`、收到 `updated` 就換掉目前的設定、收到重新抓清單的通知就更新清單。
- `api.test`：`appendCharts` 的 method、URL、body。

**端對端**（自己起前後端，用 Chrome MCP 操作）

1. 準備兩個來源，分析 A 存成「高階主管週報」；分析 B 選「加到既有儀表板 → 高階主管週報」。畫面切到那份儀表板，B 的圖排在 A 下面，卡片有來源名稱，依據分兩行。
2. 拖一張卡片，重新整理後位置還在。
3. 按「✕」、確認，那張圖不見了，重新整理後也沒回來；把 B 的圖全部移除，B 那行依據跟著消失。
4. V2 遷移前就存在的儀表板照常打開，依據照舊顯示。
5. 異常標註照常出現。

## 10. 文件

- README：功能說明加上「一份儀表板可以放不同來源的圖」「加到既有儀表板」「移除單張圖」。
- backlog：第 42 行標為已修正，註明由這份 spec 處理。
- 主設計 spec 第 11 節「一份儀表板接多個資料源」註明已由這份 spec 處理。

## 11. 不在這次範圍內

- 合併來源（疊加、查表）：B1 spec。
- 同一張圖對照兩個來源的數值：B2 spec。
- 跨來源的比率指標：B3 spec。
- 製圖頁一次拼多個來源的預覽。
- 已存儀表板改名、把卡片移到另一份儀表板。
- 已存儀表板的對話式修改。
- 刪除資料來源，以及刪除後用到它的圖怎麼處理。
- 移除卡片後的復原提示。
