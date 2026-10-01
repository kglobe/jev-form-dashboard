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
| `ADMIN_USERNAME` | 第一個管理員的帳號，後端啟動時建立。用一個專用的名稱（例如 `admin`），不要用自己單一登入的帳號名稱（見「登入與帳號」） | 空，不建立管理員 |
| `ADMIN_PASSWORD` | 第一個管理員的初始密碼，至少 8 個字元，不能有空白和中文 | 空。要建立管理員時沒填，後端會起不來 |
| `OIDC_ISSUER_URI` | 單一登入（OIDC）的 issuer，例如 `https://login.microsoftonline.com/<tenant>/v2.0`。跟 `OIDC_CLIENT_ID` 都填了才啟用單一登入 | 空，不啟用 |
| `OIDC_CLIENT_ID` | 在 IdP 登記的 client id | 空，不啟用 |
| `OIDC_CLIENT_SECRET` | client secret | 空，當成 public client，靠 PKCE |
| `OIDC_LABEL` | 登入頁按鈕上「用」和「登入」中間的字，例如填 `Microsoft`，按鈕就是「用 Microsoft 登入」 | 「公司帳號」（寫在 `application.yml`） |
| `API_ALLOWED_HOSTS` | 允許註冊的外部 API host，逗號分隔，大小寫不分（見「外部 API 資料源的規則」） | 空，不開放外部 API |
| `jev.datasource.secret-hosts.[DS_名稱]` | 這把 `DS_` 密鑰可以送去的 host，逗號分隔。方括號要留著；不能用作業系統環境變數設定 | 空，每一把密鑰都送不出去 |

`application.yml` 裡設定了 `spring.config.import: optional:file:.env[.properties]`，啟動時（工作目錄在 `backend/`）會把 `.env` 當成 properties 檔讀進來，成為設定的一部分。檔案不存在也不會讓啟動失敗（`optional:` 的效果），只是上面幾個值會維持空字串或預設值。

properties 檔是用 ISO-8859-1 讀的，所以 `.env` 有幾個限制：
- 只能寫英數字和符號。`OIDC_LABEL` 要用中文，就寫成 `\uXXXX`（「公司帳號」是 `\u516C\u53F8\u5E33\u865F`），或直接改 `application.yml` 的預設值。
- `\` 是跳脫字元，`${…}` 會被當成變數替換。管理員密碼不要用 `\` 和 `${`；client secret 裡有 `\` 的話，寫成 `\\`。

### `application.yml` 常用設定

以下是使用者可能要調整的 key，都可以在啟動時用命令列參數或環境變數覆寫，不用改檔案：

| Key | 預設值 | 說明 |
|---|---|---|
| `server.address` | `127.0.0.1` | 只聽本機連線；對外開放前先設好管理員帳號（見「登入與帳號」） |
| `jev.api-url` | `https://api.typesafe.ai/v1/systemone` | JEV 分析 API 端點 |
| `jev.model` | `jev-1.13.0` | 呼叫 JEV 用的模型版本。信心門檻是照 `jev-1.13.0` 調的；換成別的版本（包括 `jev-latest`），啟動時會印警告，門檻也要重新調校 |
| `jev.upload.max-rows` | `100000` | 上傳檔案或外部 API 回應可接受的最大列數 |
| `jev.datasource.allowed-hosts` | `${API_ALLOWED_HOSTS:}`（空清單） | 允許註冊的外部 API host，預設空＝不開放註冊外部 API。平常在 `.env` 設 `API_ALLOWED_HOSTS` 就好 |
| `jev.datasource.secret-hosts` | 空 | `DS_` 密鑰可以送去哪些 host（見「外部 API 資料源的規則」）。這一項不能用環境變數覆寫 |
| `spring.servlet.multipart.max-file-size` | `50MB` | 單一上傳檔案大小上限 |
| `spring.servlet.multipart.max-request-size` | `50MB` | 整個上傳請求大小上限 |
| `spring.datasource.hikari.schema` | `jev_dashboard` | 應用程式使用的資料庫 schema |
| `spring.flyway.schemas` | `jev_dashboard` | Flyway 管理的 schema |
| `spring.flyway.create-schemas` | `true` | 啟動時自動建立 schema，不用手動下 `CREATE SCHEMA` |
| `spring.flyway.table` | `jev_flyway_history` | Flyway history table 名稱 |

`server.address` 比較特別：預設 `127.0.0.1`，只有本機連得到。要讓區網其他機器連，啟動時加 `--server.address=0.0.0.0`，或設定環境變數 `SERVER_ADDRESS=0.0.0.0`。對外開放前先設好管理員帳號（見「登入與帳號」）。

命令列覆寫範例（開放 `erp.example.com` 這個外部 API host）：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--jev.datasource.allowed-hosts=erp.example.com
```

平常寫在 `backend/.env` 的 `API_ALLOWED_HOSTS` 就好（見「外部 API 資料源的規則」）。也可以用同名的環境變數，多個 host 用逗號分隔：

```bash
export API_ALLOWED_HOSTS=erp.example.com,another.example.com
```

### 外部 API 資料源的規則

註冊外部 API（`POST /api/datasources/api`）時，後端會做以下檢查：

- 只有管理員能註冊，其他帳號會拿到 403「沒有權限」。已經註冊好的外部 API 來源，擁有者照常能用。
- 網址只能是 `http` 或 `https`。
- host 一定要在允許清單（`backend/.env` 的 `API_ALLOWED_HOSTS`）裡，大小寫不分；清單是空的就直接拒絕（`未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS`）。
- 解析出來如果是 loopback 位址（例如 `127.0.0.1`）一律拒絕，避免打到後端自己或本機其他服務；私有網段（`10.x`、`192.168.x` 等）不擋，因為 ERP 之類的 API 通常在內網。
- 不會跟隨 redirect。
- 連線逾時 5 秒、讀取逾時 10 秒。
- 網址不能帶帳密（`https://user:pass@host/...`）或帳密類的 query 參數（`access_token`、`token`、`api_key`、`apikey`、`secret`、`password`、`passwd`、`pwd`、`auth`、`authorization`、`signature`、`sig`，不分大小寫），這些一律改放 headers。
- header 的值只能寫 `${DS_環境變數名}`，不能寫死字串。這是為了避免使用者不小心把 `DB_PASSWORD` 之類的機敏設定當成 header 值送出去，程式只認得 `DS_` 開頭的環境變數，實際值在呼叫當下才去讀。
- header 用到的每一把 `DS_` 密鑰，都要在 `jev.datasource.secret-hosts` 綁定網址的 host，不然回 400（`密鑰 DS_ERP_AUTH 沒有開放給 erp.example.com：請在 backend/.env 加上 jev.datasource.secret-hosts.[DS_ERP_AUTH]=erp.example.com`）。註冊時和之後每次載入都會檢查，拿掉綁定、重啟後端之後，已經註冊的來源就抓不到了。綁定不會讓 host 變得連得到，host 還是要在允許清單裡。

完整範例，註冊時的 body：

```json
{
  "name": "ERP 訂單",
  "url": "https://erp.example.com/orders",
  "headers": { "Authorization": "${DS_ERP_AUTH}" }
}
```

`backend/.env` 要有這三行，改完重啟後端：

```properties
API_ALLOWED_HOSTS=erp.example.com
DS_ERP_AUTH=Bearer xxx
jev.datasource.secret-hosts.[DS_ERP_AUTH]=erp.example.com
```

- `API_ALLOWED_HOSTS` 和 `secret-hosts` 的 host 都用逗號分隔，前後空白不算，大小寫不分。同一把密鑰要開放給好幾個 host，就接在同一行後面，不要再加一行 `[DS_ERP_AUTH]=`：同一個 key 只認最後一行，前一行綁的 host 會失效。
- `secret-hosts` 的 key 建議加方括號，Spring 只保證加了方括號的 key 原樣保留。這一項不能用作業系統環境變數設定（名稱裡的 `_` 會被當成 `.`），只能寫在 `.env`、`application.yml`（`"[DS_ERP_AUTH]": [erp.example.com]`）或命令列參數。
- `DS_ERP_AUTH` 可以改用作業系統環境變數，例如 `export DS_ERP_AUTH="Bearer xxx"`。

### 敏感欄位

在「資料」頁的欄位表把某個欄位的「敏感」打開（`PATCH /api/datasources/{id}/sensitive`）之後，呼叫 JEV 分析時這個欄位只會送出欄位名稱和型別，不會送實際的相異值數量或樣本內容。用到敏感欄位的卡片，異常檢查會直接跳過；異常檢查本身也只摘要 `line`、`bar`、`stacked_bar`、`heatmap` 這幾種有做彙總的卡片，原始資料列不會送到 JEV。

異常檢查比較的是最後兩個「已經結束」的期間：今天所在的那一期還沒結束，不拿來比，不然月初資料還沒累積完就會被標成異常下跌。一張卡片的 series 超過 10 個時，只送變化最大的 10 個給 JEV，其餘加總成「其他」，避免請求太大。

打開儀表板時，異常檢查直接用畫圖那次讀到的資料，不會再讀一次來源，外部 API 也不會多打一次。送給 JEV 的內容（每張卡的摘要和題目）跟上次一樣時，直接用上次的結果；改了標題、資料變了，或已經換到下一期，才會重新問 JEV。結果只存在後端記憶體裡，最多 500 份，後端重啟就清空。

## 前端設定

開發伺服器（`vite.config.js`）把 `/api`、`/oauth2`、`/login/oauth2` proxy 到 `http://127.0.0.1:8080`（不是 `localhost`：後端現在只聽 `127.0.0.1`，`localhost` 在某些系統會先解析成 `::1`，反而連不到），本機開發不用另外處理 CORS。`/oauth2`、`/login/oauth2` 是單一登入用的，兩條都設成 `changeOrigin: false`，不改 Host，後端算出來的 redirect URI 才會是瀏覽器看到的網址（`http://localhost:5173/login/oauth2/code/oidc`）。

介面分成三個工作區，用頂部導覽列切換，也可以按 Alt+1／2／3：
- 「資料」：新增來源（上傳 CSV／Excel、註冊外部 API，或把現有的來源合併；「外部 API」分頁只有管理員看得到），右邊的欄位表可以改顯示名稱、設定敏感欄位，下面的「比率」區可以加比率。按「用這個來源製圖」會帶著這個來源切到製圖頁。
- 「製圖」：選來源、點選想看的欄位、寫一句需求，按「分析」（或在需求框按 Ctrl+Enter），圖表會出現在右邊。滿意就「存成新儀表板」，或用「加到既有儀表板」接在某份儀表板的最下面。切到別頁再回來，預覽還在。
- 「儀表板」：列出存好的儀表板，卡片可以拖拉、縮放，版面會自動存。按卡片右上角的「✕」可以移除單張圖，會先跳確認。標題列的「分享」可以把儀表板分享出去（見「分享」）。別人分享給你的儀表板列在左邊的「分享給我的」，只能看。

導覽列最右邊的按鈕可以切換淺色和深色主題，圖表配色會跟著換。瀏覽器會記住上次選的主題，預設是淺色。

主題鈕左邊的「帳號」會打開帳號選單。第一行是你的帳號名稱，管理員後面會標「· 管理員」；下面是「改密碼」（用密碼登入的帳號才有）、「帳號管理」（管理員才有）和「登出」。螢幕寬度不到 600px 時，帳號鈕和主題鈕只留圖示。

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

5. 開瀏覽器到 `http://localhost:5173`，會先看到登入頁。第一次用 `.env` 裡的 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 登入（見「登入與帳號」），進去是「儀表板」頁；到「資料」頁就能開始上傳資料或註冊外部 API。

## 登入與帳號

每個人都要登入才能用。帳號由管理員在帳號選單的「帳號管理」建立，也可以接公司的單一登入（OIDC）。帳號名稱一律轉成小寫，第一個字是英文字母或數字，後面可以用英文字母、數字和 `.` `_` `@` `-`，長度 2～64，建立後不能改。密碼至少 8 個字元，不能跟帳號名稱一樣。閒置 30 分鐘會自動登出，後端重啟後所有人都要重新登入。

### 密碼輸錯太多次

同一個帳號的密碼連續輸錯 5 次，這個帳號會鎖 15 分鐘。鎖住期間用密碼登入，不管密碼對不對，都會看到「登入失敗太多次，請 N 分鐘後再試」。用單一登入不受影響。

- 要提早解鎖，請管理員在「帳號管理」幫他重設密碼，鎖會馬上解開。
- 密碼輸對一次就從 0 重新算，鎖過期後也從 0 重新算。
- 改密碼時「目前的密碼」輸錯也會算進去。帳號鎖住期間按「改密碼」，也會看到同一句話，密碼不會改，已經登入的畫面不受影響。
- 沒有這個帳號，或帳號只用單一登入，不會被鎖，照舊顯示「帳號或密碼錯誤」。
- 帳號鎖住時，後端 log 會寫一行 WARN，只有帳號名稱。
- 鎖的狀態存在後端的記憶體，後端重啟就全部解開。

完整規則見 [`docs/superpowers/specs/2026-10-02-login-lockout-design.md`](docs/superpowers/specs/2026-10-02-login-lockout-design.md)。

### 第一個管理員

第一次啟動前，在 `backend/.env` 填 `ADMIN_USERNAME`、`ADMIN_PASSWORD`。後端啟動時，資料庫還沒有這個帳號就建立一個管理員；已經有了就不動它，也不看 `ADMIN_PASSWORD`。所以第一次登入、用「改密碼」換掉初始密碼之後，可以把 `ADMIN_PASSWORD` 從 `.env` 拿掉，重啟也不會被改回去。

`ADMIN_USERNAME` 用一個專用的名稱，例如 `admin`，不要用自己單一登入的帳號名稱：已經有同名帳號時，單一登入會被擋下來（見下面「單一登入（OIDC）」）。

`ADMIN_USERNAME` 沒填就不會建立管理員。資料庫裡也沒有任何管理員時，log 會寫一行 WARN「沒有管理員帳號：在 backend/.env 設定 ADMIN_USERNAME、ADMIN_PASSWORD」。

### 讓單一登入的人當管理員

請他先用單一登入登入一次，系統才會有他的帳號。接著由管理員打開「帳號管理」，在他那一列按「設成管理員」。他目前的登入會馬上失效，重新登入後就是管理員。

### 舊資料

登入功能上線前，資料是用 user ID 記擁有者的，例如 `demo-user`。管理員建一個同名的密碼帳號，這個帳號登入後就看得到原本的來源和儀表板。升級時不會自動建任何帳號。

只有密碼帳號接得到舊資料。用單一登入的人，帳號名稱跟舊資料同名時會被擋下來，這時要由管理員建同名的密碼帳號，請他改用密碼登入。擁有者是空字串的舊資料，沒有人能接手。

### 單一登入（OIDC）

`backend/.env` 的 `OIDC_ISSUER_URI` 和 `OIDC_CLIENT_ID` 都填了，登入頁才會出現「用公司帳號登入」的按鈕，少一個就沒有。IdP 那邊要注意三件事：
- 只接公司自己的單一租戶 IdP，issuer 要用 https。
- IdP 上的應用程式要設成只有被指派的人能登入（Entra ID 是 Assignment required = Yes）。能登入 IdP 的每個人，第一次登入都會自動有一個一般帳號，也看得到所有公開連結。
- 在 IdP 登記的 redirect URI 是 `<網站網址>/login/oauth2/code/oidc`，本機開發是 `http://localhost:5173/login/oauth2/code/oidc`。

帳號名稱取 IdP 給的 `preferred_username`，沒有的話用驗證過的 `email`。同名的帳號或舊資料已經存在時會擋下來，不會自動接上。IdP 上的人可能改得了自己的 `preferred_username`，靠名稱對應，等於讓他冒用別人的帳號。

IdP 連不上時，帳密登入照常能用，後端也照常啟動。

### 部署在 HTTPS 反向代理後面

- 代理要把 `/api`、`/oauth2`、`/login/oauth2` 都轉給後端。
- 後端要設 `server.forward-headers-strategy: native`（或啟動時加 `--server.forward-headers-strategy=native`），代理要帶 `X-Forwarded-Proto` 和 `X-Forwarded-Host`（或保留原本的 Host）。只保留 Host 不夠：後端會以為是 http，redirect URI 和 cookie 的 Secure 都會錯。
- 設好之後用 curl 打一次，看 Location 裡的 `redirect_uri` 是不是 `https://dashboard.example.com/login/oauth2/code/oidc`：

  ```bash
  curl -s -o /dev/null -D - https://dashboard.example.com/oauth2/authorization/oidc | grep -i '^location'
  ```

## 分享

打開自己的儀表板，按標題列「新增儀表板」左邊的「分享」。有兩種方式，可以同時開，設好之後按「儲存」：
- 分享給指定帳號：輸入對方的帳號名稱，按「加入」。對方登入後，在儀表板頁左邊的「分享給我的」就看得到。對方的帳號名稱在他帳號選單的第一行。
- 公開連結：打開開關後，有帳號的人拿到連結就能看，但不會出現在別人的「分享給我的」。沒有免登入的連結。

連結是 `<網站網址>/#/d/<儀表板 id>`，對話框裡可以按「複製連結」。用 http 開在區網上時，瀏覽器可能不讓網頁直接複製，這時連結會被選取起來，按 Ctrl+C 就好。打開任何一份儀表板時，網址列也會變成這個連結。

被分享的人只能看：圖表、異常標註和「依據」都看得到，不能拖拉、縮放、移除卡片，也不能刪除，看不到你的資料來源清單。他看到的資料跟你一樣，包括明細表和散佈圖的原始資料列，還有標成敏感的欄位值。「敏感」只管不送 JEV，不是權限。他打開時，後端用你的身分載入資料，你的外部 API 來源一樣會去抓。

還沒用單一登入登入過的人沒有帳號，加不進清單，可以先給他公開連結。

把對方從清單移掉，或關掉公開連結，對方下次打開或重新整理就看不到了，已經開著的畫面不會自己消失。關掉公開連結再打開，同一個連結又能用。擁有者的帳號被停用，或儀表板被刪掉，分享出去的也一起看不到。完整規則見 [`docs/superpowers/specs/2026-10-01-auth-sharing-design.md`](docs/superpowers/specs/2026-10-01-auth-sharing-design.md)。

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

欄位名稱對不上時（例如一份中文、一份英文），可以按對應表上方的「請 JEV 對欄位」。JEV 只會把型別相同的欄位對起來，名稱已經一樣的欄位不問，三個以上來源時都跟第一個來源比。JEV 填的格子，下拉選單下面標著「JEV 建議」；手動設好的對應和改過名稱的列，JEV 都不會動。JEV 也會對錯，它填的對應要自己看過，一樣要預覽過才能建立。規則見 [`docs/superpowers/specs/2026-09-30-jev-column-mapping-design.md`](docs/superpowers/specs/2026-09-30-jev-column-mapping-design.md)。

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

刪除後無法復原。萬一合併來源的輸入還是被刪掉了（例如刪除的同時，在另一個分頁拿它建了新的合併來源），用到那個輸入來源的欄位一律當作敏感，`sensitiveFrom` 會顯示「（已刪除的來源）」。完整規則見 [`docs/superpowers/specs/2026-09-30-delete-datasource-design.md`](docs/superpowers/specs/2026-09-30-delete-datasource-design.md)。

## 更新來源的資料

上傳的來源可以換成新的資料，不必另外建一個來源。資料頁選好上傳來源後，右上角有「更新資料」：選好檔案（Excel 可以指定工作表），再選更新方式。

- 整份覆蓋：資料換成新檔案的內容。
- 接在後面：新檔案的資料接在原本的後面。每月一個檔的話，每個月接一次就好。

新檔案的欄位名稱要跟原本是同一組，順序可以不同；數字和日期欄位的型別也不能變。不符合時，對話框會寫出哪裡不一樣。更新後的總筆數一樣受 `jev.upload.max-rows` 限制，接在後面時算原本加上新的；只有標題列、沒有資料的檔案會被擋下。顯示名稱、敏感設定和比率都不會動，樣本和日期範圍則用更新後的資料重算。

用到它的圖和合併來源（包括疊了好幾層的）下次打開就是新的資料，不用重建。外部 API 每次載入都會重抓，合併來源每次載入都會重新合併，所以這兩種來源沒有這顆按鈕。後端是 `POST /api/datasources/{id}/rows`（`mode=replace` 或 `append`）。完整規則見 [`docs/superpowers/specs/2026-10-02-datasource-reload-design.md`](docs/superpowers/specs/2026-10-02-datasource-reload-design.md)。

## 測試

後端（用同一個本機 PostgreSQL，接 `jev_dashboard_test` schema，需要 `.env` 裡的 `DB_USER`／`DB_PASSWORD`；測試不會真的呼叫 JEV 或外部 API）：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
cd backend
./mvnw test
```

測試的管理員帳號和密碼固定寫在 `application-test.yml`，不受 `.env` 的 `ADMIN_*` 影響，也不會開單一登入。有 migration 的 branch 要用自己的 schema（Flyway 會自己建），不要跟其他 branch 共用 `jev_dashboard_test`：

```bash
export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_auth SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_auth
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
| `401`、`請先登入`，或畫面跳出「請重新登入」 | 沒登入，或登入已經失效：閒置超過 30 分鐘、後端重啟過，或管理員停用了帳號、改了權限、重設了密碼（自己改密碼也一樣） | 重新登入。跳出「請重新登入」時畫面上的東西會留著，但剛才失敗的動作要再做一次 |
| 忘記管理員密碼 | 密碼查不回來；`ADMIN_PASSWORD` 只在建立帳號時用一次，改 `.env` 不會重設已經存在的帳號 | 請另一位管理員在「帳號管理」按「重設密碼」。沒有別的管理員時，在 `backend/.env` 換一個新的 `ADMIN_USERNAME`（加上 `ADMIN_PASSWORD`）重啟後端，用新的管理員登入，再替原本的帳號重設密碼 |
| `503`、`未設定 TYPESAFE_API_KEY` | `backend/.env` 裡 `TYPESAFE_API_KEY` 是空的 | 申請 JEV 金鑰後填入 `.env`，重啟後端 |
| `503`、`JEV 目前請求太多，請稍後再試` | 超過 JEV 的速率限制（每分鐘 1,200 次請求、每秒 250,000 tokens）。後端已經照 `retry-after` 等過並重試一次；要等太久（分析超過 10 秒、異常檢查超過 2 秒）就不等，直接回這個訊息 | 等一下再按一次 |
| `413`、`送給 JEV 的資料太大…` | 欄位太多或樣本太長，state 加上最長的一題，估算超過 JEV 單次 32k tokens 的上限（題目加總超過 64k 的話，後端會自己拆成幾次送，不會報錯） | 減少欄位，或把長文字欄位標成敏感（敏感欄位不送樣本） |
| 想看 JEV 每一題的完整機率（例如 Choice 第二名是誰） | 預設不記錄 | 啟動後端時加 `--logging.level.jev.dashboard.jev.JevClient=DEBUG`，log 會印出每題的 `probabilities` 和 `legend` |
| `未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS` | 允許清單預設是空的，`backend/.env` 沒有設 `API_ALLOWED_HOSTS` | 在 `backend/.env` 加上 `API_ALLOWED_HOSTS=要開放的 host`，重啟後端（見「外部 API 資料源的規則」） |
| `密鑰 DS_... 沒有開放給 ...：請在 backend/.env 加上 ...` | header 用的 `DS_` 密鑰沒有綁定這個網址的 host。已經註冊的來源突然抓不到，也可能是綁定被拿掉了 | 照訊息在 `backend/.env` 加上那一行，重啟後端。已經有 `[DS_...]=` 那一行的話，把 host 用逗號接在後面，不要再加一行（同一個 key 只認最後一行） |
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
