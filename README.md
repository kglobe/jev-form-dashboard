# JEV 動態儀表板

使用者上傳 CSV/Excel，或是註冊一個外部 API 當資料源，交給 JEV（[jevtypesafeai.com](https://jevtypesafeai.com)）分析後，自動產生一份可拖拉、可縮放版面的個人儀表板。存檔後每次載入都直接用同一份配置畫圖，不會重新分析，畫完圖再非同步跑一次異常檢查。完整設計請看 [`docs/superpowers/specs/2026-09-23-jev-dashboard-design.md`](docs/superpowers/specs/2026-09-23-jev-dashboard-design.md)；JEV 怎麼判斷欄位、怎麼組出圖表，則寫在 [`docs/JEV智慧化產製儀表板的流程與方法.md`](docs/JEV智慧化產製儀表板的流程與方法.md)。

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
| `server.address` | `127.0.0.1` | 只聽本機連線；對外開放前看下面的身分識別警語 |
| `jev.api-url` | `https://api.typesafe.ai/v1/systemone` | JEV 分析 API 端點 |
| `jev.model` | `jev-1.13.0` | 呼叫 JEV 用的模型版本。信心門檻是照 `jev-1.13.0` 調的；換成別的版本（包括 `jev-latest`），啟動時會印警告，門檻也要重新調校 |
| `jev.upload.max-rows` | `100000` | 上傳檔案或外部 API 回應可接受的最大列數 |
| `jev.datasource.allowed-hosts` | `[]`（空清單） | 允許註冊的外部 API host，預設空＝不開放註冊外部 API |
| `spring.servlet.multipart.max-file-size` | `50MB` | 單一上傳檔案大小上限 |
| `spring.servlet.multipart.max-request-size` | `50MB` | 整個上傳請求大小上限 |
| `spring.datasource.hikari.schema` | `jev_dashboard` | 應用程式使用的資料庫 schema |
| `spring.flyway.schemas` | `jev_dashboard` | Flyway 管理的 schema |
| `spring.flyway.create-schemas` | `true` | 啟動時自動建立 schema，不用手動下 `CREATE SCHEMA` |
| `spring.flyway.table` | `jev_flyway_history` | Flyway history table 名稱 |

`server.address` 比較特別：預設 `127.0.0.1`，只有本機連得到。要讓區網其他機器連，啟動時加 `--server.address=0.0.0.0`，或設定環境變數 `SERVER_ADDRESS=0.0.0.0`。開放前請注意，身分目前只靠 `X-User-Id` header（見下面「前端設定」），不是真的登入驗證，對外開放等於讓任何人都能改這個 header 冒充別人的儀表板。

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
- 網址不能帶帳密（`https://user:pass@host/...`）或帳密類的 query 參數（`access_token`、`token`、`api_key`、`apikey`、`secret`、`password`、`passwd`、`pwd`、`auth`、`authorization`、`signature`、`sig`，不分大小寫），這些一律改放 headers。
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

在「資料」頁的欄位表把某個欄位的「敏感」打開（`PATCH /api/datasources/{id}/sensitive`）之後，呼叫 JEV 分析時這個欄位只會送出欄位名稱和型別，不會送實際的相異值數量或樣本內容。用到敏感欄位的卡片，異常檢查會直接跳過；異常檢查本身也只摘要 `line`、`bar`、`stacked_bar`、`heatmap` 這幾種有做彙總的卡片，原始資料列不會送到 JEV。

異常檢查比較的是最後兩個「已經結束」的期間：今天所在的那一期還沒結束，不拿來比，不然月初資料還沒累積完就會被標成異常下跌。一張卡片的 series 超過 10 個時，只送變化最大的 10 個給 JEV，其餘加總成「其他」，避免請求太大。

打開儀表板時，異常檢查直接用畫圖那次讀到的資料，不會再讀一次來源，外部 API 也不會多打一次。送給 JEV 的內容（每張卡的摘要和題目）跟上次一樣時，直接用上次的結果；改了標題、資料變了，或已經換到下一期，才會重新問 JEV。結果只存在後端記憶體裡，最多 500 份，後端重啟就清空。

## 前端設定

| 變數 | 預設值 | 說明 |
|---|---|---|
| `VITE_USER_ID` | `demo-user` | 每個 API 請求都會帶在 `X-User-Id` header 裡 |

要改的話在 `frontend/.env` 加一行 `VITE_USER_ID=xxx`，或啟動前用 shell 設定同名環境變數（Vite 只會把 `VITE_` 開頭的變數暴露給前端程式碼）。這只是示範用的使用者識別，後端拿它來判斷「這是誰的儀表板」，並不是真正的登入驗證，換一個字串就能看到別人的資料。

開發伺服器（`vite.config.js`）把 `/api` proxy 到 `http://127.0.0.1:8080`（不是 `localhost`：後端現在只聽 `127.0.0.1`，`localhost` 在某些系統會先解析成 `::1`，反而連不到），本機開發不用另外處理 CORS。

介面分成三個工作區，用左側導覽列切換，也可以按 Alt+1／2／3：
- 「資料」：新增來源（上傳 CSV／Excel、註冊外部 API，或把現有的來源合併），右邊的欄位表可以改顯示名稱、設定敏感欄位，下面的「比率」區可以加比率。按「用這個來源製圖」會帶著這個來源切到製圖頁。
- 「製圖」：選來源、點選想看的欄位、寫一句需求，按「分析」（或在需求框按 Ctrl+Enter），圖表會出現在右邊。滿意就「存成新儀表板」，或用「加到既有儀表板」接在某份儀表板的最下面。切到別頁再回來，預覽還在。
- 「儀表板」：列出存好的儀表板，卡片可以拖拉、縮放，版面會自動存。按卡片右上角的「✕」可以移除單張圖，會先跳確認。

導覽列最下面的按鈕可以切換深色和淺色主題，圖表配色會跟著換。瀏覽器會記住上次選的主題，預設是深色。

幾個介面上的小提示：
- 上傳檔案送出前會先在瀏覽器檢查大小，超過 50MB 直接顯示「檔案太大，上限 50MB」，不會先送到後端才被拒絕。
- 明細表最多顯示 500 筆，超過時卡片上方會出現「僅顯示前 500 筆」。
- 「儀表板」頁刪除儀表板前會先跳確認對話框，按「刪除」才會真的呼叫 API。

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

5. 開瀏覽器到 `http://localhost:5173`，會先看到「儀表板」頁；到「資料」頁就能開始上傳資料或註冊外部 API。

## 依需求分析與調整

按「分析」之前，可以在「製圖」頁的「想看什麼？」寫一句需求，例如「各區每月營收」，最多 200 字。JEV 判斷跟這句話直接相關的欄位，一定會列入挑圖的候選；用到這些欄位的圖排在最前面，卡片標題旁有「符合需求」標籤，後面才補一般性的圖。沒填的話，分析結果跟以前一樣。

分析結果出現之後，左邊的「調整」區會列出「需求紀錄」。在「想怎麼調整？」寫下一句，例如「不要圓餅圖，加各產品的數量」，再按「調整」。後端會把目前的圖和整串需求紀錄一起送給 JEV，由 JEV 判斷每張圖要保留還是移除（拿不準就保留），再補上跟這句話有關的新圖，總數最多 6 張。需求紀錄最多 10 句。

不想要某張圖，也可以直接按卡片右上角的「✕」，不會呼叫 JEV。畫面下方接著出現「已移除「…」」的提示，6 秒內按「復原」就能放回原位；滑鼠停在提示上，或鍵盤焦點在提示裡時，不會倒數。

需求紀錄會跟著儀表板一起存檔。之後打開這份儀表板，圖表上方會多一行「依據：各區每月營收 → 不要圓餅圖，加各產品的數量」。

送給 JEV 的內容只多了使用者寫的句子；調整時再加上目前每張圖的標題、圖型和欄位名稱，一樣不含任何資料值。完整規則見 [`docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`](docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md)，JEV 多問了哪些題目見方法說明文件第 13 節。

## 合併來源

「新增來源」對話框的「合併」分頁，可以把現有的來源合成一份新的來源（`kind: merged`）：

- 疊加：欄位一樣的幾份資料接成一份，例如北區接南區，或每月一個檔。欄位名稱不一樣時，在對應表裡對起來就好；另外會多一欄「來源」，標出每一列來自哪一份。最多 5 個來源，同一欄的型別要一樣。
- 查表：用主檔替主來源補欄位，例如退貨單拿產品代碼去對產品主檔，補上產品類別。對接欄位最多 3 組，每一組都相等才算對上。主來源一列不增也不減；主檔的對接值重複時，用排最前面的那一列。
- 對照：把兩個以上的來源放在同一張圖上比，例如每月銷售額對退貨額。日期和文字欄位照疊加的方式合併，數字欄位一律依來源分開，預設名稱是「來源名·欄位」（`銷售·amount`、`退貨·amount`），所以任何圖都不會把兩個來源的數字加在一起。分析對照來源時，JEV 會先考慮「一個時間或分類欄位配兩個數值」的折線圖和長條圖；兩個數值差超過 10 倍時改用左右兩條 Y 軸，某一期只有一邊有資料，另一邊就留白。

建立前一定要先按「預覽」，看筆數、提示（例如「12 筆對不到主檔，補上的欄位是空的」）和前 20 列。合併來源不存資料列，每次載入才照定義重新合併，所以外部 API 的輸入更新了，合併來源也會跟著更新。合併來源可以再拿去合併，最多 3 層；合併後的筆數一樣受 `jev.upload.max-rows` 限制。

敏感設定從輸入來源繼承：輸入來源標成敏感的欄位，在合併來源上也是敏感的，而且關不掉，要改得回到那個來源。顯示名稱在建立時沿用輸入來源的設定，之後可以自己改。

合併定義建立之後不能編輯，定義錯了就刪掉重建。完整規則見 [`docs/superpowers/specs/2026-09-27-merged-datasource-design.md`](docs/superpowers/specs/2026-09-27-merged-datasource-design.md)。

對照的規則見 [`docs/superpowers/specs/2026-09-28-compare-datasource-design.md`](docs/superpowers/specs/2026-09-28-compare-datasource-design.md)。計數（`count`）只算有值的列，對照來源裡一邊沒有數值的列不會被算進去。

## 比率

退貨率、達成率、毛利率這種指標不能逐列算好再加總，要每一組先把分子、分母各自加總再相除，例如 9 月的退貨率＝9 月退貨金額合計 ÷ 9 月銷售金額合計。在「資料」頁欄位表下面的「比率」區按「新增比率」，取個名稱，再選分子和分母（都要是數字欄位）就好，任何來源都能加。後端是 `PATCH /api/datasources/{id}/ratios`，每次送整份清單。

分析時，JEV 把比率當成一個數值，配時間欄位畫折線圖或長條圖，配分類欄位畫長條圖，Y 軸和 tooltip 都顯示成百分比（最多一位小數）。某一組沒有分子或分母的資料，或分母加總是 0，那一組會留空，不是 0%。例如對照來源上退貨只有 7～9 月，1～6 月的退貨率就是空白。分子、分母的資料期間不一樣時，比率只算兩邊都有資料的月份，圖下方會寫出算的是哪幾個月。例如退貨只有 7～9 月、銷售有 1～9 月，依產品算的退貨率就是 7～9 月的退貨 ÷ 7～9 月的銷售，1～6 月的銷售不會算進分母。規則見 [`docs/superpowers/specs/2026-09-29-ratio-period-alignment-design.md`](docs/superpowers/specs/2026-09-29-ratio-period-alignment-design.md)。在對照來源上，比率只會配分子、分母兩邊來源都有的欄位。比率圖的異常檢查比的是百分點。

分子或分母是敏感欄位（包括從輸入來源繼承來的）時，比率也算敏感，用到它的卡片不做異常檢查。比率不能編輯，要改就刪掉重加；刪掉之後，用到它的卡片會顯示欄位遺失，同名重加就恢復。合併來源不會帶著輸入來源的比率，要用就在合併來源上自己加。比率存在 `datasource.ratios_json`（Flyway `V4__ratio_metric.sql`），舊的來源是空清單，不用搬資料。完整規則見 [`docs/superpowers/specs/2026-09-28-ratio-metric-design.md`](docs/superpowers/specs/2026-09-28-ratio-metric-design.md)。

## 一份儀表板放多個來源

每張圖各自記得自己讀哪個資料來源，所以同一份儀表板可以放不同來源的圖，例如「銷售趨勢」來自銷售 API、「退貨原因」來自 R7 Excel。

製圖頁一次還是只分析一個來源。預覽滿意後可以「存成新儀表板」，也可以從「加到既有儀表板」選一份儀表板，這批圖會接在那份儀表板的最下面，彼此的相對位置不變。加到既有儀表板不會用到預覽上的名稱，儀表板名稱維持原樣。

圖來自兩個以上的來源時，每張卡片標題旁會標出來源名稱；「依據」也改成每次分析一行，前面註明來源，例如「依據（R7 Excel 退貨）：各區退貨 → 不要圓餅圖」。某個來源載入失敗（例如外部 API 連不上）時，只有那個來源的卡片顯示「資料來源「…」載入失敗」，其他卡片照常顯示。

已存的儀表板每張卡片（包括明細表）右上角都有「✕」。按下去會先確認，確認後馬上存檔，不能復原，要加回來得重新分析。某個來源的圖全部移除後，那個來源的依據也會跟著拿掉。最後一張圖也可以移除，之後再用「加到既有儀表板」把圖加回來。

舊的儀表板會在後端啟動時由 Flyway 的 `V2__per_chart_datasource.sql` 自動轉成新格式，不用重建。完整規則見 [`docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`](docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md)。

## 刪除來源

資料頁選好來源後，右上角「用這個來源製圖」旁邊有「刪除來源」。按下去會先查這個來源有誰在用。

- 被合併來源當輸入：不能刪，對話框會列出那些合併來源，要先刪掉它們。
- 被儀表板的圖用到：對話框列出儀表板和圖的張數，確認後照刪。那些卡片會顯示「資料來源已刪除」，可以按右上角 ✕ 移除，版面照樣能存。
- 沒有人用：確認後直接刪。

刪除後無法復原。萬一合併來源的輸入還是被刪掉了（例如刪除的同時，有人拿它建了新的合併來源），繼承來的欄位一律當作敏感，`sensitiveFrom` 會顯示「（已刪除的來源）」。完整規則見 [`docs/superpowers/specs/2026-09-30-delete-datasource-design.md`](docs/superpowers/specs/2026-09-30-delete-datasource-design.md)。

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
| `503`、`JEV 目前請求太多，請稍後再試` | 超過 JEV 的速率限制（每分鐘 1,200 次請求、每秒 250,000 tokens）。後端已經照 `retry-after` 等過並重試一次；要等太久（分析超過 10 秒、異常檢查超過 2 秒）就不等，直接回這個訊息 | 等一下再按一次 |
| `413`、`送給 JEV 的資料太大…` | 欄位太多或樣本太長，state 加上最長的一題，估算超過 JEV 單次 32k tokens 的上限（題目加總超過 64k 的話，後端會自己拆成幾次送，不會報錯） | 減少欄位，或把長文字欄位標成敏感（敏感欄位不送樣本） |
| 想看 JEV 每一題的完整機率（例如 Choice 第二名是誰） | 預設不記錄 | 啟動後端時加 `--logging.level.jev.dashboard.jev.JevClient=DEBUG`，log 會印出每題的 `probabilities` 和 `legend` |
| `未開放註冊外部 API：jev.datasource.allowed-hosts 是空的` | `jev.datasource.allowed-hosts` 預設是空清單 | 用命令列參數或環境變數加上要開放的 host（見「`application.yml` 常用設定」） |
| `header「...」的值只能寫 ${DS_環境變數名}` | 註冊外部 API 時 header 值寫了字面字串，不是 `${DS_XXX}` 格式 | 改成 `${DS_你的變數名}`，實際值透過同名環境變數提供 |
| `網址不能包含帳密或 token，請改放在 headers，值寫 ${DS_環境變數名}` | 註冊外部 API 時網址裡帶了 `user:pass@`，或帶了 `token`／`api_key`／`password` 之類的 query 參數 | 把驗證資訊改放進 headers，值寫成 `${DS_你的變數名}` |
| `CSV 第 N 行格式錯誤` | 引號沒對齊等，CSV 格式跟 commons-csv 的預期不符 | 用試算表軟體另存一次，或檢查該行的逗號、引號有沒有用對 |
| `第 N 行有 X 欄，標題列是 Y 欄` | 該行的欄位數量跟標題列對不起來 | 補齊或刪掉多餘的欄位，讓每一行的欄數跟標題列一致 |
| 日期欄位在欄位表裡顯示成「文字」或「數字」 | 日期只認得這幾種寫法：`2024-09-24`、`2024/9/24`（後面可以帶時間）、民國年 `113/09/24`、8 碼 `20240924`（限 1900～2099 年）。整欄每個值都要是日期，混進一個別的寫法，整欄就不算日期 | 改成上面其中一種寫法再上傳。2026-09-30 起才認得民國年和 8 碼日期，在那之前上傳的來源要重新上傳，外部 API 要重新註冊 |
| `欄位名稱不能空白` / `欄位名稱重複：<名稱>` | CSV 或 Excel 的標題列有空白欄名，或兩個欄位同名 | 補上欄名，或把重複的欄名改成不同名稱 |
| `檔案太大，上限 50MB` | 上傳的檔案超過 50MB（前端會先擋一次，後端也會再擋一次） | 拆成多個檔案，或調整 `spring.servlet.multipart.max-file-size`（見上面設定表） |
| 存檔或預覽儀表板時出現中文錯誤（例如 `儀表板名稱不能空白`、`圖表 c1：line 需要 mapping.x`） | 卡片設定不合法：mapping 缺欄位、`gridPos` 超出格線、chartId 重複或空白、名稱空白等 | 照訊息修正對應欄位再存一次；預覽時也會擋，不會存進一份壞掉的設定 |
| 找不到 `java` 或 `JAVA_HOME` | mvnw 印出 `The JAVA_HOME environment variable is not defined correctly, so mvnw cannot run.` 或 `The java/javac command does not exist in PATH nor is JAVA_HOME set, so mvnw cannot run.` | 確認 JDK 21 已安裝，`export JAVA_HOME` 指到正確路徑；已開啟的 Git Bash 視窗看不到新設的系統環境變數，要關掉重開 |
| 連不上資料庫 | PostgreSQL 沒開，或 `.env` 裡的 `DB_USER`／`DB_PASSWORD` 跟本機資料庫不符 | 確認 PostgreSQL 17 服務有在跑，`.env` 帳密正確 |
