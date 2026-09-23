# JEV 動態儀表板設計

日期：2026-09-23
範圍：儀表板（dashboard）。動態表單（form）另開一份 spec。

## 1. 目標

做一套可以搬到其他 Spring Boot + Vue 3 系統（主要是 ERP）的儀表板模組。資料接進來之後，由 JEV（https://jevtypesafeai.com）判斷要畫哪些圖、用哪些欄位，再由萬用轉換器把原始資料轉成統一格式，交給前端畫圖。

使用者流程：

1. 接資料：上傳 CSV/Excel，或註冊一個外部 API 端點。
2. 按「分析」手動觸發 JEV，產生一份儀表板配置並預覽。
3. 滿意就按「存為我的儀表板」。之後載入直接讀配置，不再做分析。
4. 可以拖拉、縮放卡片，調整結果存回配置。
5. 每次載入儀表板時，額外呼叫一次 JEV 檢查資料有沒有異常，有的話在畫面上標註。

## 2. 技術選型

| 層 | 選擇 |
|---|---|
| 前端 | Vue 3、Vite、Vuetify 3、ECharts（vue-echarts）、grid-layout-plus |
| 後端 | Spring Boot 3、Java 21、單一 Maven module |
| 資料庫 | PostgreSQL，schema `jev_dashboard`，Flyway 管理 migration |
| 檔案解析 | Apache POI（Excel）、commons-csv（CSV） |
| JEV | `POST https://api.typesafe.ai/v1/systemone`，Bearer token |

本機開發連線：`localhost:5432`，database `postgres`，帳號 `postgres`，密碼寫在 `.env`，不進 git。

## 3. 架構

```
Vue 3 前端
  DashboardView（可拖拉格線）→ ChartCard → toOption(chartData, mapping) → ECharts
        │ REST
Spring Boot 後端
  /api/datasources       上傳檔案、註冊 API、推斷 schema
  /api/jev/analyze       觸發分析，回傳配置預覽
  /api/jev/highlight     載入時的異常檢查
  /api/dashboards        個人儀表板 CRUD
  /api/dashboards/{id}/data  依配置取資料、轉換後回傳
        │
PostgreSQL（jev_dashboard schema）
```

### 後端 package

| package | 職責 |
|---|---|
| `datasource` | 解析上傳檔案、呼叫外部 API、推斷欄位型別（number / date / string）。對外定義 `DataSourceProvider` 介面，其他系統實作這個介面就能接自家 API。 |
| `jev` | `JevClient` 用 Spring `RestClient` 打 JEV；`AnalyzeService` 產生題目並把答案組成配置；`HighlightService` 做異常檢查。 |
| `transform` | `UniversalTransformer`：依 mapping 挑欄位、做聚合（sum / count / avg）和 group by，輸出 `ChartData`。 |
| `dashboard` | 儀表板配置的 CRUD，配置本身存在 JSONB 欄位。 |

### 前端結構

儀表板相關程式碼全放在 `src/modules/dashboard/`，整個資料夾可以直接搬到別的專案。

- `DashboardView`：格線版面，拖拉或縮放結束後把 `gridPos` 存回後端。
- `ChartCard`：依 `type` 呼叫對應的 `toOption`。
- `DatasourcePanel`：上傳檔案、填 API 端點。
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
      "type": "bar",
      "title": "月銷售額",
      "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },
      "mapping": { "xField": "order_month", "yField": "total_amount", "aggregate": "sum" },
      "options": {}
    }
  ]
}
```

`highlights` 不存在配置裡，每次載入時即時算。

### 4.2 圖表類型與必要 mapping

| 圖表 | type | 必要 mapping |
|---|---|---|
| 長條圖 | `bar` | xField, yField |
| 折線圖 | `line` | xField, yField |
| 圓餅圖 | `pie` | categoryField, valueField |
| 表格 | `table` | columns[] |
| 散佈圖 | `scatter` | xField, yField |
| 雷達圖 | `radar` | dimensions[], valueField |
| 熱力圖 | `heatmap` | xField, yField, valueField |
| 堆疊長條圖 | `stacked_bar` | xField, yField, groupBy |

數值欄位可以另外指定 `aggregate`（`sum` / `count` / `avg`），預設 `sum`。

### 4.3 轉換器輸出（ChartData）

後端只負責挑欄位和聚合，輸出格式直接對應 ECharts 的 `dataset`：

```json
{
  "chartId": "c1",
  "type": "bar",
  "dimensions": ["order_month", "total_amount"],
  "rows": [["2024-01", 15000], ["2024-02", 23000]]
}
```

前端每種圖只要一個小函式，把 `ChartData` 和 `mapping` 轉成 ECharts option。

## 5. JEV 整合

JEV 只回傳有型別的答案（`choice` / `score` / `noul`）。配置由後端用固定邏輯組出來，不讓模型生成 JSON。

### 5.1 分析（手動觸發，兩輪）

ERP 資料通常有 20 到 50 個欄位，全部兩兩組合一次問完題目會太多，所以拆成兩輪。

#### 第一輪：欄位評估

`state` 放 schema（欄位名、型別、5 筆樣本值）和總筆數。每個欄位問兩題：

- `score` 重要性：`["不重要，略過", "次要", "重要", "核心指標"]`
- `choice` 角色：`dimension`（分類維度）、`measure`（數值指標）、`time`（時間）、`ignore`（忽略）

另外問一題 `score` 建議的圖表張數（1 到 6 張）。

#### 第二輪：選圖

取重要性前 N 名的欄位（N 由張數決定），後端依角色組出候選組合（例如 time × measure、dimension × measure），每組問一題 `choice`，選項只列該組合合法的圖表類型。

#### 組裝

後端依答案填 mapping，照重要性由上往下排，每列兩張卡片（`w: 6`），產生 `gridPos`。

### 5.2 異常檢查（每次載入）

後端先把資料摘要成一段文字（本期與上期的主要指標、變化百分比），只送摘要，不送原始資料。問兩題：

- `noul`：有沒有值得標註的異常？
- `score`：嚴重程度 `["正常波動", "值得注意", "需要關注", "嚴重警示"]`

`noul` 大於 0.7 才在畫面上顯示標註。

## 6. 資料庫

schema `jev_dashboard`，Flyway migration 放在 `backend/src/main/resources/db/migration/`。

| 表 | 主要欄位 |
|---|---|
| `datasource` | id、name、kind（`upload` / `api`）、schema_json、api_url、created_by、created_at |
| `datasource_rows` | datasource_id、row_json（JSONB）。上傳檔案的內容存這裡。 |
| `dashboard` | id、name、datasource_id、created_by、config_json（JSONB）、updated_at |

`V1__init.sql` 建 schema 和表；如果需要示範資料，另放 `V2__seed_demo.sql`。

### 部署

`docker/postgres/` 放 `Dockerfile`（以官方 `postgres` image 為底）和 `init.sql`（建 schema）。搬到大型主機時直接 build 這個 image，表結構交給 Flyway 在應用程式啟動時建立。

## 7. 錯誤處理

| 狀況 | 處理 |
|---|---|
| 分析時 JEV 失敗或逾時 | 回傳錯誤給使用者，不產生配置 |
| 異常檢查時 JEV 失敗 | 略過，不顯示標註，圖表照常顯示 |
| JEV 選圖答案的 confidence 低於 0.5 | 那張圖不產生 |
| mapping 指到的欄位已不存在（資料源改版） | 該卡片顯示「欄位遺失」，其他卡片照常 |
| 上傳檔案格式錯誤 | 回傳錯誤訊息，註明出錯的行號 |

## 8. 設定

- `backend/.env.example`：`TYPESAFE_API_KEY=`、`DB_URL`、`DB_USER`、`DB_PASSWORD`
- `backend/.env`：實作時建立，由使用者填入 JEV key，列入 `.gitignore`

## 9. 測試

- `UniversalTransformer`：八種圖各一個單元測試。
- `AnalyzeService`：用錄好的 JEV 回應 JSON 當 fixture，不實際呼叫 API。
- 前端 `toOption`：每種圖一個 snapshot 測試。

## 10. 不在這次範圍內

- 動態表單：下一份 spec。
- 登入與權限：先用固定的 `X-User-Id` header 識別使用者，接 ERP 時再整合。
