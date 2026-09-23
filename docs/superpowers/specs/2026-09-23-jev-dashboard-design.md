# JEV 動態儀表板設計

日期：2026-09-23
範圍：儀表板（dashboard）。動態表單（form）另開一份 spec。

## 1. 目標

做一套可以搬到其他 Spring Boot + Vue 3 系統（主要是 ERP）的儀表板模組。資料接進來之後，由 JEV（https://jevtypesafeai.com）判斷要畫哪些圖、用哪些欄位，再由萬用轉換器把原始資料轉成統一格式，交給前端畫圖。

使用者流程：

1. 接資料：上傳 CSV/Excel，或註冊一個外部 API 端點。
2. 按「分析」手動觸發 JEV，產生一份儀表板配置並預覽。預覽時配置還沒存，資料走預覽端點。
3. 滿意就按「存為我的儀表板」。之後載入直接讀配置，不再做分析。
4. 可以拖拉、縮放卡片，調整結果存回配置。
5. 每次載入儀表板時，圖表先畫出來，再非同步呼叫 JEV 檢查每張卡片的資料有沒有異常，有的話在該卡片上標註。

## 2. 技術選型

| 層 | 選擇 |
|---|---|
| 前端 | Vue 3、Vite、Vuetify 3、ECharts（vue-echarts）、grid-layout-plus |
| 後端 | Spring Boot 3、Java 21、單一 Maven module |
| 資料庫 | PostgreSQL，schema `jev_dashboard`，Flyway 管理 migration |
| 檔案解析 | Apache POI（Excel）、commons-csv（CSV） |
| JEV | `POST https://api.typesafe.ai/v1/systemone`，Bearer token，模型固定 `jev-1.13.0` |

本機開發連線：`localhost:5432`，database `postgres`，帳號 `postgres`，密碼寫在 `.env`，不進 git。

## 3. 架構

```
Vue 3 前端
  DashboardView（可拖拉格線）→ ChartCard → toOption(chartData, mapping) → ECharts
        │ REST
Spring Boot 後端
  /api/datasources                上傳檔案、註冊 API、推斷 schema
  /api/jev/analyze                觸發分析，回傳配置預覽（不存檔）
  /api/jev/highlight/{dashboardId} 載入後的異常檢查，逐卡片回傳
  /api/dashboards                 個人儀表板 CRUD
  /api/dashboards/{id}/data       依已存配置取資料、轉換後回傳
  /api/dashboards/preview/data    body 帶配置，給預覽用
        │
PostgreSQL（jev_dashboard schema）
```

所有 `/api/dashboards/**` 和 `/api/jev/highlight/**` 都檢查 `created_by` 是否等於 `X-User-Id`，不符回 404。

### 後端 package

| package | 職責 |
|---|---|
| `datasource` | 解析上傳檔案、呼叫外部 API、推斷欄位型別（number / date / string）和相異值數量。對外定義 `DataSourceProvider` 介面，其他系統實作這個介面就能接自家 API。 |
| `jev` | `JevClient` 用 Spring `RestClient` 打 JEV；`AnalyzeService` 產生題目、呼叫 JEV；`ConfigAssembler` 是純函式，把 schema 和答案組成配置；`HighlightService` 做異常檢查。 |
| `transform` | `UniversalTransformer`：依 mapping 挑欄位、做時間分桶、聚合（sum / count / avg / none）和 group by，輸出 `ChartData`。 |
| `dashboard` | 儀表板配置的 CRUD，配置本身存在 JSONB 欄位。 |

### 前端結構

儀表板相關程式碼全放在 `src/modules/dashboard/`，整個資料夾可以直接搬到別的專案。前提是宿主專案已經用 Vuetify 3。

- `DashboardView`：格線版面，拖拉或縮放結束後把 `gridPos` 存回後端。圖表畫完後再呼叫 highlight。
- `ChartCard`：依 `type` 呼叫對應的 `toOption`，有標註時在卡片右上角顯示。
- `DatasourcePanel`：上傳檔案、填 API 端點、勾選不送樣本的敏感欄位。
- `AnalyzeButton`：觸發分析、顯示預覽、提供「存為我的儀表板」。
- `charts/*.js`：八種圖各一個 `toOption` 函式。

## 4. 資料格式

### 4.1 儀表板配置（Dashboard Config）

JEV 分析後由後端組出來，前端照它畫圖，使用者儲存的也是這一份。

```json
{
  "id": "uuid",
  "name": "銷售總覽",
  "datasourceId": "uuid",
  "createdBy": "user-id",
  "layout": [
    {
      "chartId": "c1",
      "type": "line",
      "title": "月銷售額",
      "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },
      "mapping": { "x": "order_date", "timeGrain": "month", "value": "total_amount", "aggregate": "sum" },
      "options": {}
    }
  ]
}
```

`highlights` 不存在配置裡，每次載入時即時算。

### 4.2 圖表類型與 mapping

mapping 的欄位名統一用下面幾個：`x`、`y`、`series`、`value`、`measures[]`、`columns[]`。

| 圖表 | type | 必要 mapping | 說明 |
|---|---|---|---|
| 長條圖 | `bar` | x, value | x 是分類或時間 |
| 折線圖 | `line` | x, value | x 是時間 |
| 圓餅圖 | `pie` | x, value | x 是分類，相異值不超過 8 個 |
| 表格 | `table` | columns[] | 明細，不聚合 |
| 散佈圖 | `scatter` | x, y | 兩個都是數值，不聚合 |
| 雷達圖 | `radar` | series, measures[] | measures 是雷達的軸（3 到 8 個數值欄位），series 的每個值畫一條線（相異值不超過 5 個） |
| 熱力圖 | `heatmap` | x, y, value | x、y 是分類或時間 |
| 堆疊長條圖 | `stacked_bar` | x, series, value | x 是分類或時間，series 相異值不超過 10 個 |

`aggregate` 可以是 `sum` / `count` / `avg` / `none`。預設值依圖表類型：`scatter` 和 `table` 是 `none`，其他是 `sum`。`count` 時 `value` 可以省略，算筆數。

x 是時間欄位時，mapping 帶 `timeGrain`（`day` / `month` / `quarter`）。組裝時依資料的時間跨度自動決定：90 天內用 `day`，3 年內用 `month`，更長用 `quarter`。

### 4.3 轉換器輸出（ChartData）

後端只負責挑欄位、分桶和聚合，輸出格式直接對應 ECharts 的 `dataset`：

```json
{
  "chartId": "c1",
  "type": "line",
  "dimensions": ["order_date", "total_amount"],
  "rows": [["2024-01", 15000], ["2024-02", 23000]]
}
```

前端每種圖只要一個小函式，把 `ChartData` 和 `mapping` 轉成 ECharts option。

## 5. JEV 整合

JEV 只回傳有型別的答案（`choice` / `score` / `noul`）。配置由後端用固定邏輯組出來，不讓模型生成 JSON。

共通規則：

- 模型版本固定 `jev-1.13.0`，寫在設定檔 `jev.model`。0.7、0.5 這些門檻都依賴模型校準，換版本前要重新驗證。
- `state` 直接送 JSON 物件。
- question key 一律用索引（`imp_0`、`role_0`、`combo_3`），不用欄位名。欄位名可能有中文、空白或符號，後端自己對回欄位。
- `score` 回的是從 0 起算的小數（例如 4 個選項回 0 到 3 之間的值），不是選項文字。
- 單次請求的上限是 state 加所有題目 64k token，不限題數。同一輪的題目一次送完。

### 5.1 分析（手動觸發，兩輪）

ERP 資料通常有 20 到 50 個欄位。50 個欄位兩兩組合就有 1225 組，每組一題會超過 64k token，所以拆成兩輪。

#### 第一輪：欄位評估

`state` 放 schema（欄位名、型別、相異值數量、5 筆樣本值）和總筆數。使用者勾選為敏感的欄位只送名稱和型別，不送樣本。每個欄位問兩題：

- `score` 重要性：`["不重要，略過", "次要", "重要", "核心指標"]`，回 0 到 3。
- `choice` 角色：`dimension`（分類維度）、`measure`（數值指標）、`time`（時間）、`ignore`（忽略）。confidence 低於 0.5 當作 `ignore`。

另外問一題 `score` 建議的圖表張數，選項是 `["1 張", "2 張", "3 張", "4 張", "5 張", "6 張"]`，張數 K = `round(score) + 1`。

#### 第二輪：選圖

1. 候選欄位：角色不是 `ignore`、重要性 `round(score) >= 1` 的欄位，依重要性取前 8 個。
2. 後端依下表列出所有合法組合，每組的分數是組內欄位重要性加總，取分數最高的 2K 組。
3. 每組問一題 `choice`，選項是該組合法的圖表類型，再加一個 `none`（「這組欄位不值得畫圖」）。

| 角色組合 | 合法圖表 |
|---|---|
| time × measure | `line`、`bar` |
| dimension × measure | `bar`、`pie`（相異值 ≤ 8） |
| measure × measure | `scatter` |
| dimension × 3 到 8 個 measure | `radar`（dimension 相異值 ≤ 5） |
| time × dimension × measure | `stacked_bar`、`heatmap` |
| dimension × dimension × measure | `stacked_bar`、`heatmap` |

`table` 不經過 JEV：每份儀表板最後固定加一張明細表，columns 是候選欄位依重要性排序。

#### 組裝

`ConfigAssembler` 依答案組配置：

1. 丟掉回答 `none` 或 confidence 低於 0.5 的組合。
2. 剩下的依組合分數排序，取前 K 張。
3. 依 4.2 的規則填 mapping、`aggregate` 預設值和 `timeGrain`。
4. 每列兩張卡片（`w: 6`），由上往下排，產生 `gridPos`。明細表放最後，佔整列（`w: 12`）。

如果 JEV 選出來的圖一張都不剩，預覽只會有明細表，上方顯示「找不到適合的圖表」。

### 5.2 異常檢查（每次載入，非同步）

前端先畫完圖表，再呼叫 `/api/jev/highlight/{dashboardId}`。失敗或逾時就不顯示標註，圖表不受影響。

後端為每張卡片產生摘要，只處理 x 是時間欄位的卡片，比較最後一期和前一期（期間依 `timeGrain`）的 value，每個 series 列出兩期數值和變化百分比。沒有時間欄位的卡片不檢查。只送摘要，不送原始資料。

所有卡片的題目併成一次請求，每張卡片兩題：

- `noul`（key `anomaly_c1`）：這張卡片有沒有值得標註的異常？
- `score`（key `severity_c1`）：嚴重程度 `["正常波動", "值得注意", "需要關注", "嚴重警示"]`

`noul` 大於 0.7 才在該卡片上顯示標註，標註文字用 `round(score)` 對應的嚴重程度。

結果依（dashboardId、資料指紋）用 Spring `@Cacheable` 快取在記憶體，資料沒變就不重打 JEV。資料指紋是各卡片 ChartData 的雜湊值。

## 6. 資料來源

### 6.1 上傳檔案

- CSV：先試 UTF-8（有沒有 BOM 都可以），解碼失敗就改用 MS950。第一列是標題。
- Excel：預設讀第一個 sheet，上傳時可以指定 sheet 名稱。第一列是標題。日期儲存格轉成 ISO 日期字串。
- 上限 100,000 筆（設定 `jev.upload.max-rows`），超過就拒絕並告知筆數。轉換器在 Java 記憶體裡聚合，這是它的上限；之後資料量變大，要改成用 SQL 的 `jsonb` 運算聚合。

### 6.2 外部 API

- 只允許打設定檔 `jev.datasource.allowed-hosts` 裡的 host，清單為空時不開放註冊 API。解析 DNS 後，如果是私有 IP 或 loopback 也拒絕，避免被拿來掃內網。
- 驗證資訊不存進資料庫。`api_headers_json` 裡的值只能寫 `${環境變數名}`，呼叫時從 Spring Environment 取值，例如 `{"Authorization": "${ERP_API_TOKEN}"}`。
- 回應必須是 JSON 陣列，每個元素是一筆資料。
- v1 每次載入都重打外部 API，不做快取，讀取逾時 10 秒。

### 6.3 資料外送

送給 JEV 的內容只有兩種：第一輪的 schema 和樣本值（敏感欄位不送樣本），以及異常檢查的摘要。原始資料列不送出。

## 7. 資料庫

schema `jev_dashboard`，Flyway migration 放在 `backend/src/main/resources/db/migration/`。

| 表 | 主要欄位 |
|---|---|
| `datasource` | id、name、kind（`upload` / `api`）、schema_json（含型別、相異值數量、是否敏感）、api_url、api_headers_json、created_by、created_at |
| `datasource_rows` | datasource_id、row_json（JSONB）。上傳檔案的內容存這裡。 |
| `dashboard` | id、name、datasource_id、created_by、config_json（JSONB）、updated_at |

`V1__init.sql` 建表；如果需要示範資料，另放 `V2__seed_demo.sql`。

搬進宿主系統時，宿主多半已經有自己的 Flyway，所以設定要獨立：

```yaml
spring.flyway:
  schemas: jev_dashboard
  create-schemas: true
  table: jev_flyway_history
```

### 部署

`docker/postgres/` 放 `Dockerfile`，以官方 `postgres` image 為底。schema 和表都交給 Flyway 在應用程式啟動時建立，不另放 `init.sql`。搬到大型主機時直接 build 這個 image。

## 8. 錯誤處理

| 狀況 | 處理 |
|---|---|
| 分析時 JEV 失敗或逾時 | 回傳錯誤給使用者，不產生配置 |
| 異常檢查時 JEV 失敗或逾時 | 略過，不顯示標註，圖表照常顯示 |
| JEV 選圖答案是 `none` 或 confidence 低於 0.5 | 那張圖不產生 |
| 一張圖都沒選出來 | 只產生明細表，預覽顯示「找不到適合的圖表」 |
| mapping 指到的欄位已不存在（資料源改版） | 該卡片顯示「欄位遺失」，其他卡片照常 |
| 上傳檔案格式錯誤 | 回傳錯誤訊息，註明出錯的行號 |
| 上傳超過筆數上限 | 拒絕，告知實際筆數和上限 |
| 外部 API 的 host 不在允許清單 | 註冊時拒絕 |

JEV 逾時：連線 5 秒；讀取時分析用 30 秒、異常檢查用 10 秒。JEV 的速率上限是每分鐘 1200 次請求，異常檢查的快取就是為了不碰到這個上限。

## 9. 設定

- `backend/.env.example`：`TYPESAFE_API_KEY=`、`DB_URL`、`DB_USER`、`DB_PASSWORD`
- `backend/.env`：實作時建立，由使用者填入 JEV key，列入 `.gitignore`
- Spring Boot 不會自己讀 `.env`，`application.yml` 要加 `spring.config.import: optional:file:.env[.properties]`
- 其他設定：`jev.model`、`jev.upload.max-rows`、`jev.datasource.allowed-hosts`

## 10. 測試

- `UniversalTransformer`：八種圖各一個單元測試，另外測時間分桶和 `aggregate: none`。
- `ConfigAssembler`：純函式測試。給定 schema 和一組答案，檢查候選組合、`none` 和低 confidence 的過濾、張數換算、`gridPos` 排版。
- `AnalyzeService`：用錄好的 JEV 回應 JSON 當 fixture，不實際呼叫 API。
- CSV 解析：UTF-8 和 MS950 各一個檔案。
- 外部 API 註冊：不在允許清單的 host、私有 IP 都要被拒絕。
- 前端 `toOption`：每種圖一個 snapshot 測試。

## 11. 不在這次範圍內

- 動態表單：下一份 spec。
- 登入與權限：先用固定的 `X-User-Id` header 識別使用者，接 ERP 時再整合。
- 一份儀表板接多個資料源。
- 在畫面上調整 mapping 或 `timeGrain`，要改就重新分析。
