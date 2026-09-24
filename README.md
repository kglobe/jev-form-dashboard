# JEV 動態儀表板

使用者上傳 CSV/Excel，或是註冊一個外部 API 當資料源，交給 JEV（[jevtypesafeai.com](https://jevtypesafeai.com)）分析後，自動產生一份可拖拉、可縮放版面的個人儀表板。存檔後每次載入都直接用同一份配置畫圖，不會重新分析，畫完圖再非同步跑一次異常檢查。完整設計請看 [`docs/superpowers/specs/2026-09-23-jev-dashboard-design.md`](docs/superpowers/specs/2026-09-23-jev-dashboard-design.md)。

## 目錄結構

- `frontend/`：Vue 3 前端，儀表板頁面、圖表元件、資料源設定面板。
- `backend/`：Spring Boot 後端，資料源解析、JEV 分析、儀表板 CRUD 的 API。
- `docs/`：規格文件與相關筆記。

## 前置需求

- JDK 21，Windows 可用 `winget install --id Microsoft.OpenJDK.21` 安裝，裝完要設定 `JAVA_HOME`。已經開著的 Git Bash 視窗看不到新設的 `JAVA_HOME`，要關掉重開，或在同一個視窗裡手動 `export`。
- Node，`package.json` 沒有寫 `engines`，但目前鎖定的 vite 8 需要 `^20.19.0` 或 `>=22.12.0`，vitest 5 需要 `^22.12.0 || ^24.0.0 || >=26.0.0`，交集大致落在 22.12+ 或 24.x。本機用 Node 23 開發時，`npm install` 會印出 EBADENGINE 警告，可以忽略，`dev`／`test`／`build` 都能正常跑。
- PostgreSQL 17，本機跑成服務，預設監聽 `5432`（對應 `.env.example` 裡的 `DB_URL`）。
- Windows 建議用 Git Bash 執行本文件裡的指令。

## 後端設定

### `backend/.env`

從範例複製一份，不要進版控（已經在 `.gitignore` 裡）：

```bash
cp backend/.env.example backend/.env
```

| Key | 說明 | 預設值 |
|---|---|---|
| `TYPESAFE_API_KEY` | 呼叫 JEV 用的金鑰，沒填的話「分析」會回 503（`未設定 TYPESAFE_API_KEY`）；「異常檢查」不會報錯，只是不顯示任何標註 | 空 |
| `DB_URL` | PostgreSQL JDBC 連線字串 | `jdbc:postgresql://localhost:5432/postgres` |
| `DB_USER` | 資料庫帳號 | `postgres` |
| `DB_PASSWORD` | 資料庫密碼 | 空 |

`application.yml` 裡設定了 `spring.config.import: optional:file:.env[.properties]`，啟動時（工作目錄在 `backend/`）會把 `.env` 當成 properties 檔讀進來，成為設定的一部分。檔案不存在也不會讓啟動失敗（`optional:` 的效果），只是上面幾個值會維持空字串或預設值。

### `application.yml` 常用設定

以下是使用者可能要調整的 key，都可以在啟動時用命令列參數或環境變數覆寫，不用改檔案：

| Key | 預設值 | 說明 |
|---|---|---|
| `jev.api-url` | `https://api.typesafe.ai/v1/systemone` | JEV 分析 API 端點 |
| `jev.model` | `jev-1.13.0` | 呼叫 JEV 用的模型版本 |
| `jev.upload.max-rows` | `100000` | 上傳檔案或外部 API 回應可接受的最大列數 |
| `jev.datasource.allowed-hosts` | `[]`（空清單） | 允許註冊的外部 API host，預設空＝不開放註冊外部 API |
| `spring.servlet.multipart.max-file-size` | `50MB` | 單一上傳檔案大小上限 |
| `spring.servlet.multipart.max-request-size` | `50MB` | 整個上傳請求大小上限 |
| `spring.datasource.hikari.schema` | `jev_dashboard` | 應用程式使用的資料庫 schema |
| `spring.flyway.schemas` | `jev_dashboard` | Flyway 管理的 schema |
| `spring.flyway.create-schemas` | `true` | 啟動時自動建立 schema，不用手動下 `CREATE SCHEMA` |
| `spring.flyway.table` | `jev_flyway_history` | Flyway history table 名稱 |

命令列覆寫範例（開放 `erp.example.com` 這個外部 API host）：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--jev.datasource.allowed-hosts=erp.example.com
```

或用環境變數（Spring 的寬鬆綁定規則：`.` 換成 `_`、全大寫），多個 host 用逗號分隔：

```bash
export JEV_DATASOURCE_ALLOWED_HOSTS=erp.example.com,another.example.com
```

### 外部 API 資料源的規則

註冊外部 API（`POST /api/datasources/api`）時，後端會做以下檢查：

- 網址只能是 `http` 或 `https`。
- host 一定要在 `jev.datasource.allowed-hosts` 裡（大小寫不分）；清單是空的就直接拒絕（`未開放註冊外部 API：jev.datasource.allowed-hosts 是空的`）。
- 解析出來如果是 loopback 位址（例如 `127.0.0.1`）一律拒絕，避免打到後端自己或本機其他服務；私有網段（`10.x`、`192.168.x` 等）不擋，因為 ERP 之類的 API 通常在內網。
- 不會跟隨 redirect。
- 連線逾時 5 秒、讀取逾時 10 秒。
- header 的值只能寫 `${DS_環境變數名}`，不能寫死字串。這是為了避免使用者不小心把 `DB_PASSWORD` 之類的機敏設定當成 header 值送出去，程式只認得 `DS_` 開頭的環境變數，實際值在呼叫當下才去讀。

完整範例，註冊時的 body：

```json
{
  "name": "ERP 訂單",
  "url": "https://erp.example.com/orders",
  "headers": { "Authorization": "${DS_ERP_AUTH}" }
}
```

啟動後端前設定對應的環境變數（也可以直接寫進 `backend/.env`，因為兩者是同一套讀取機制）：

```bash
export DS_ERP_AUTH="Bearer xxx"
```

### 敏感欄位

在資料源列表裡把某個欄位勾選為敏感（`PATCH /api/datasources/{id}/sensitive`）之後，呼叫 JEV 分析時這個欄位只會送出欄位名稱和型別，不會送實際的相異值數量或樣本內容。用到敏感欄位的卡片，異常檢查會直接跳過；異常檢查本身也只摘要 `line`、`bar`、`stacked_bar`、`heatmap` 這幾種有做彙總的卡片，原始資料列不會送到 JEV。

## 前端設定

| 變數 | 預設值 | 說明 |
|---|---|---|
| `VITE_USER_ID` | `demo-user` | 每個 API 請求都會帶在 `X-User-Id` header 裡 |

要改的話在 `frontend/.env` 加一行 `VITE_USER_ID=xxx`，或啟動前用 shell 設定同名環境變數（Vite 只會把 `VITE_` 開頭的變數暴露給前端程式碼）。這只是示範用的使用者識別，後端拿它來判斷「這是誰的儀表板」，並不是真正的登入驗證，換一個字串就能看到別人的資料。

開發伺服器（`vite.config.js`）把 `/api` proxy 到 `http://localhost:8080`，本機開發不用另外處理 CORS。

## 啟動方式

1. 確認 PostgreSQL 17 服務在跑，監聽 `5432`。
2. 準備 `backend/.env`（見上）。
3. 啟動後端：

   ```bash
   export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
   export PATH="$JAVA_HOME/bin:$PATH"
   cd backend
   ./mvnw spring-boot:run
   ```

   第一次啟動，Flyway 會自動在 `jev_dashboard` schema 建表，log 裡會看到 Flyway 的 migrate 訊息，最後停在 `Started JevDashboardApplication in ... seconds`，服務會聽 `8080` port。

4. 啟動前端：

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

   `npm install` 若印出 EBADENGINE 警告可以忽略（見「前置需求」）。裝完後 `npm run dev` 會印出 Vite 的 `Local: http://localhost:5173/`。

5. 開瀏覽器到 `http://localhost:5173`，會看到儀表板頁面，可以開始上傳資料或註冊外部 API。

## 測試

後端（用同一個本機 PostgreSQL，接 `jev_dashboard_test` schema，需要 `.env` 裡的 `DB_USER`／`DB_PASSWORD`；測試不會真的呼叫 JEV 或外部 API）：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
cd backend
./mvnw test
```

前端：

```bash
cd frontend
npm test
npm run build
```

`npm test` 跑 vitest，`npm run build` 用 Vite 打包，成功的話會在 `frontend/dist/` 產生輸出。

## 常見問題

| 訊息 | 原因 | 處理方式 |
|---|---|---|
| `503`、`未設定 TYPESAFE_API_KEY` | `backend/.env` 裡 `TYPESAFE_API_KEY` 是空的 | 申請 JEV 金鑰後填入 `.env`，重啟後端 |
| `未開放註冊外部 API：jev.datasource.allowed-hosts 是空的` | `jev.datasource.allowed-hosts` 預設是空清單 | 用命令列參數或環境變數加上要開放的 host（見「`application.yml` 常用設定」） |
| `header「...」的值只能寫 ${DS_環境變數名}` | 註冊外部 API 時 header 值寫了字面字串，不是 `${DS_XXX}` 格式 | 改成 `${DS_你的變數名}`，實際值透過同名環境變數提供 |
| 找不到 `java` 或 `JAVA_HOME` | mvnw 印出 `The JAVA_HOME environment variable is not defined correctly, so mvnw cannot run.` 或 `The java/javac command does not exist in PATH nor is JAVA_HOME set, so mvnw cannot run.` | 確認 JDK 21 已安裝，`export JAVA_HOME` 指到正確路徑；已開啟的 Git Bash 視窗看不到新設的系統環境變數，要關掉重開 |
| 連不上資料庫 | PostgreSQL 沒開，或 `.env` 裡的 `DB_USER`／`DB_PASSWORD` 跟本機資料庫不符 | 確認 PostgreSQL 17 服務有在跑，`.env` 帳密正確 |
