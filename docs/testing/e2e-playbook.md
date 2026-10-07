# JEV 儀表板：Chrome MCP 測試劇本

日期：2026-10-02
對象：用 Chrome DevTools MCP 操作瀏覽器的 agent。照章節順序跑完，App 現有的每個功能都會走過一次。

S16～S18 測最後加上的三個功能：外部 API 允許清單和密鑰綁 host（`feat/api-secret-hosts`）、登入失敗鎖定（`feat/login-lockout`）、更新資料（`feat/datasource-reload`）。步驟裡的畫面文字和錯誤訊息都照這三個 branch 的程式寫。要測的 checkout 少了哪一個，對應的那一節就整節記「沒跑」。S19 測儀表板篩選器（`feat/dashboard-filters`，2026-10-07 加的），還沒全跑過。

第一次全跑在 2026-10-02，commit `79435c3`（integ：main 加上這三個 branch 和這份劇本）。241 步裡 236 PASS、4 FAIL，S10-5 條件沒成立所以沒跑。4 個 FAIL 都是已知的產品問題，那幾步的預期結果後面都標了「已知問題（backlog 2026-10-02）」。劇本跟 App 實際行為對不上的步驟，已經照那次的結果改了。

## 1. 怎麼跑

- 每次都開一個全新的環境（第 2 節），從 S00 跑到 S99。前一節留下的資料，後一節會接著用，所以不能跳著跑；要跳過某一節，先看它的「留下的狀態」，確定後面沒有用到。
- 每一節可以交給不同的 agent，只要在同一個環境裡接著跑。每節開頭寫了要用哪個帳號、哪個 context，以及開始前應該要有什麼。
- 檢查方式有三種：
  - 畫面：`take_snapshot` 或 `wait_for` 看得到預期的文字、按鈕、欄位。
  - API：用第 5 節的 helper 在頁面裡呼叫 API，比對回傳的資料。
  - 截圖：`take_screenshot` 存到這次的執行資料夾（第 2.4 節），存完一定要用 Read 打開來看，照第 6 節的規則判斷。沒打開看過的截圖，那一步不能記 PASS。
- 標〔JEV〕的預期取決於 JEV 的判斷（挑哪幾張圖、要不要標異常），沒出現記在備註，不算 FAIL；有出現的話，它的資料一樣要對。
- 每一步都要記結果（第 9 節的範本）。FAIL 不用停下來，記好之後繼續；後面的步驟因此跑不下去時，記「BLOCKED（因為 Sxx-n）」。
- 標了「已知問題（backlog 2026-10-02）」的步驟，問題在第一次全跑時就找到了，也已經記進 `docs/backlog.md`。看到的情況跟標註一樣，就記 FAIL，備註寫「已知問題（backlog 2026-10-02）」，不用再查原因；情況不一樣，才照第 10 節回報。問題修好以後，這一步應該要 PASS。

## 2. 環境

### 2.1 埠、schema 和程序

- 使用者自己的開發伺服器占著 8080（後端）和 5180（前端），Chrome 開在 9222。這三個程序不能停、不能 kill，也不能拿 8080 測。
- E2E 用自己的一組：後端 8081、前端 5181，網址 `http://localhost:5181`。
- 每次用新的 schema，名稱帶日期和時間，例如 `jev_e2e_20261002_0130`。不要沿用舊的，也不要碰 `jev_dashboard`。
- 指令都在要測的那份 checkout 裡跑（通常是 scratchpad 裡的 worktree），不是 `C:/GitHub/jev-form-dashboard`。worktree 裡沒有 `backend/.env` 時，從主 checkout 複製一份：`cp C:/GitHub/jev-form-dashboard/backend/.env backend/.env`。`.env` 裡有 DB 帳密和 `TYPESAFE_API_KEY`，不要印出來。
- 分析會真的呼叫 JEV（用 `.env` 的 `TYPESAFE_API_KEY`），每次要等幾秒到十幾秒。
- 背景程序一律用 Bash 的 `run_in_background` 開，結束時用 TaskStop 停掉自己開的那幾個。
- 等待用 Monitor（或同等的等待工具）跑 until 迴圈，最多 180 秒；逾時就印出背景程序輸出的最後幾十行，回報後停下來。

### 2.2 帳號

| 帳號 | 用途 | 密碼 |
|---|---|---|
| `e2e-admin` | 管理員，後端啟動時建立 | `E2eAdmin-2026`（`ADMIN_PASSWORD`） |
| `alice` | 資料和儀表板的擁有者 | S02 建立時 `Alice-init-01`，S02 被重設成 `Alice-reset-02`，S03 自己改成 `Alice-final-03` |
| `bob` | 被分享的檢視者 | `Bob-pass-01` |
| `carol` | 只給 S17 鎖定用，其他章節不要拿它登入失敗 | `Carol-pass-01`，S17 被重設成 `Carol-reset-02` |
| `dave` | S02 切換管理員用 | `Dave-pass-01` |

每個帳號用自己的 `isolatedContext`，名稱就用帳號名稱（`e2e-admin`、`alice`、`bob`、`carol`、`dave`）；沒登入的檢查用 `anon`，S16 的第二個後端用 `admin-8082`。同一個 context 裡的分頁共用 cookie，混用帳號會讓檢查假通過。

### 2.3 Chrome

- MCP 操作的是開在 `127.0.0.1:9222` 的自動化 Chrome。`curl -s http://127.0.0.1:9222/json/version` 沒回應，代表它沒開，用 Bash `run_in_background` 開起來：

  ```bash
  "/c/Program Files/Google/Chrome/Application/chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\selenum\AutomationProfile" about:blank
  ```

- 那個視窗裡也有使用者自己的分頁。一律用 `new_page` 開自己的分頁、記下 `pageId`，之後只操作自己的分頁；別的分頁不要 `select_page`、截圖、讀取或關掉。
- 每節開始時，用 `new_page`（帶那個帳號的 `isolatedContext`）開一個新分頁。同名 context 共用 cookie：已經登入就直接進到 App；看到登入頁就照 L1 登入。上一節自己開的分頁，用完就 `close_page`。

L1 登入：在登入頁 `fill`「帳號」和「密碼」，按「登入」，`wait_for`「帳號」（頂部導覽列的帳號鈕）出現。欄位裡已經有字時不要用 `fill`，改用 `jev.setField`（第 5 節）。

### 2.4 執行資料夾和固定資料

- 這次的執行資料夾：`<scratchpad>/e2e-runs/<YYYYMMDD-HHMM>/`，`<scratchpad>` 是 session 的 scratchpad 目錄。截圖、結果表、helper 的輸出都放這裡。
- 截圖命名 `S07-05.png`（章節-步驟），同一步有好幾張就加後綴，例如 `S07-08-c2.png`。
- `upload_file` 只收 MCP 工作目錄底下的檔案，固定資料要先複製到 `<scratchpad>/e2e-fixtures/`，上傳時用那裡的絕對路徑。

### 2.5 MCP 的幾個眉角

- `fill` 填到已經有值的 Vuetify 欄位，會接在原本的字後面，不會取代。要取代就用 `jev.setField(標籤, 值)`：它用原生的 `HTMLInputElement.prototype.value` setter 設值，再送一個 `input` 事件。只送 `input`、不送 `change`，所以靠 `change` 存檔的欄位（例如顯示名稱）用它改了也不會存。
- 很大的頁面（例如 500 列的明細表）會讓 `take_snapshot` 爆量，改用 `evaluate_script` 讀；輸出太長就帶 `filePath` 存檔再讀。
- 顯示名稱欄位是「按 Enter 或離開輸入框才存」，`fill` 完要 `press_key` Enter。要清空或改掉已經有的顯示名稱，不要用 `jev.setField`，改用真的按鍵：`click` 那一格，`press_key` Control+A、Backspace（要改成別的字就再 `type_text`），最後 Enter。
- 拖拉卡片一律用 `jev.dragBy`（第 5 節）。卡片的標題列 `.card-drag` 在 a11y 樹裡是 ignored，`take_snapshot` 拿不到它的 uid；就算對標題文字用 MCP `drag`，也觸發不了格線的 pointer 拖拉。
- 復原提示只留 6 秒，分開呼叫 MCP 工具會來不及，按 ✕ 和按「復原」要寫在同一個 `evaluate_script` 裡（S07-7）。
- 製圖頁往下捲之後，上方黏著的名稱列（儀表板名稱、捨棄、存成新儀表板）會蓋住卡片的標題列，MCP `click` 會點到名稱列。要點卡片上的按鈕，先跑 `() => window.scrollTo(0, 0)`。
- `resize_page` 最窄只能縮到約 500px。要更窄的畫面，改用 `emulate` 的 viewport（例如 `390x844x2`），用完再用 `emulate` 把 viewport 清掉。
- 對話框關掉後，Vuetify 的欄位可能還留在 DOM 裡；`take_snapshot` 找 uid 時，認明是目前打開的對話框裡的那一個。

## 3. S00 準備環境

- 目的：開一套跟使用者的開發伺服器分開的後端、前端和 schema。
- 帳號／context：不用。
- 前置條件：PostgreSQL 17 在跑；JDK 21、Node 已安裝。
- 步驟：

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S00-1 | `netstat -ano \| grep -E ":(8081\|5181\|8082) .*LISTENING"` | 沒有輸出。有的話不要停掉那個程序，整套換成沒人用的 port（例如 8091／5191／8092），後面的步驟照著改 | API |
| S00-2 | 決定名稱：`RUN=$(date +%Y%m%d-%H%M)`、`SCHEMA=jev_e2e_$(date +%Y%m%d_%H%M)`；建立 `<scratchpad>/e2e-runs/$RUN/`，記下 schema、port、`git rev-parse --short HEAD` | 資料夾建好，結果表開頭寫了這三樣 | 畫面 |
| S00-3 | 複製固定資料：`cp docs/testing/fixtures/*.csv docs/testing/fixtures/*.xlsx <scratchpad>/e2e-fixtures/`，再把 `products.csv` 另外複製成 `products.txt` | `e2e-fixtures/` 裡有 7 個 `.csv`、1 個 `.xlsx`、1 個 `.txt` | 畫面 |
| S00-4 | 在 `backend/` 打包：`export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot" PATH="$JAVA_HOME/bin:$PATH"; ./mvnw -q package -DskipTests` | 結束碼 0，`target/jev-dashboard-0.1.0-SNAPSHOT.jar` 存在 | 畫面 |
| S00-5 | 在 `backend/` 用 `run_in_background` 啟動後端（指令見下方） | log 最後是 `Started JevDashboardApplication`，有 Flyway 在新 schema 建表的訊息 | 畫面 |
| S00-6 | 等後端：until `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/auth/options` 回 `200` | 180 秒內等到 | API |
| S00-7 | 在 `frontend/`：沒有 `node_modules` 就先 `npm ci`；用 `run_in_background` 跑 `API_TARGET=http://127.0.0.1:8081 node node_modules/vite/bin/vite.js --port 5181 --strictPort` | Vite 印出 `Local: http://localhost:5181/`（不要用 `npx vite`，停不掉，會留下孤兒程序） | 畫面 |
| S00-8 | 等前端：until `curl -s -o /dev/null -w '%{http_code}' http://localhost:5181/` 回 `200`；再看 `curl -s http://localhost:5181/api/auth/options` | 回 `{"oidc":null}`，代表 proxy 有接到後端。接到的是不是 8081，S01-6 會用 `e2e-admin` 確認（這個帳號只存在新 schema） | API |
| S00-9 | `curl -s http://127.0.0.1:9222/json/version`；沒回應就照 2.3 開 Chrome | 回傳 JSON，有 `Browser` 欄位 | API |

後端啟動指令（S00-5，`<schema>` 換成 S00-2 的名稱）：

```bash
SPRING_DATASOURCE_HIKARI_SCHEMA=<schema> SPRING_FLYWAY_SCHEMAS=<schema> \
ADMIN_USERNAME=e2e-admin ADMIN_PASSWORD=E2eAdmin-2026 \
API_ALLOWED_HOSTS=example.com,www.example.com DS_E2E_TOKEN=dummy-token \
java -jar target/jev-dashboard-0.1.0-SNAPSHOT.jar --server.port=8081 '--jev.datasource.secret-hosts.[DS_E2E_TOKEN]=example.com'
```

- 允許清單有 `example.com` 和 `www.example.com` 兩個 host，`DS_E2E_TOKEN` 只綁 `example.com`。S16 要靠這個差別測「密鑰綁到別的 host」。
- `secret-hosts` 不能用作業系統環境變數設定（名稱裡的 `_` 會被當成 `.`），所以寫成命令列參數，方括號要留著。
- checkout 裡還沒有 `feat/api-secret-hosts` 時，`API_ALLOWED_HOSTS` 沒有作用。這時在同一行前面再加 `JEV_DATASOURCE_ALLOWED_HOSTS=example.com,www.example.com`，S15 才測得到。
- 環境變數優先於 `backend/.env`，所以 `.env` 裡的 `ADMIN_USERNAME`、`API_ALLOWED_HOSTS` 不會蓋掉這裡的值。
- `.env` 有設單一登入（`OIDC_ISSUER_URI`）的話，同一行前面再加 `OIDC_ISSUER_URI= OIDC_CLIENT_ID=` 關掉，不然 S01-1 會多一顆「用公司帳號登入」。

留下的狀態：後端 8081、前端 5181 在跑；schema 裡只有 `e2e-admin`；固定資料在 `<scratchpad>/e2e-fixtures/`。

## 4. 固定資料與預期數字

固定資料在 `docs/testing/fixtures/`，由同一個資料夾裡的 `make_fixtures.py` 產生（`python make_fixtures.py` 會重寫所有檔案，並印出 4.1、4.2 的數字）。改了資料就要回來更新這一節。

| 檔案 | 上傳成 | 內容 |
|---|---|---|
| `sales-h1.csv` | 銷售 | 2026-01～06，北區／中區／南區 × P01／P02／P03，每月 9 筆，共 54 筆。欄位：日期、區域、產品代碼、營收、毛利、數量。北區 6 月掉到 3 成，給異常檢查用 |
| `returns.csv` | 退貨 | 2026-02～06 的退貨，11 筆。欄位：日期、區域、產品代碼、退貨金額。1 月沒有退貨；有一筆產品代碼 P09 不在產品主檔 |
| `products.csv` | 產品主檔 | 4 筆。欄位：產品代碼、產品名稱、類別。P02 出現兩次（「檯燈」在前、「檯燈（舊款）」在後） |
| `stores.xlsx` | 台中店、高雄店 | 兩個工作表。「台中店」（第一個）欄位是日期、產品代碼、營收；「高雄店」的金額欄叫「金額」。各 6 筆，2026-01～06 每月一筆 |
| `sales-07.csv` | （S18 更新資料） | 2026-07，欄位跟銷售一樣，9 筆 |
| `sales-07-missing-column.csv` | （S18 錯誤） | 跟 `sales-07.csv` 一樣，但少了「毛利」欄 |
| `sales-07-text-number.csv` | （S18 錯誤） | 跟 `sales-07.csv` 一樣，但第一筆的營收是 `N/A` |
| `bad-rows.csv` | （S04 錯誤） | 標題列 4 欄，第 3 行只有 3 欄 |
| `products.txt` | （S04 錯誤） | S00-3 從 `products.csv` 複製，副檔名不支援 |

### 4.1 銷售

每月（1～6 月）：

| 月份 | 營收 | 毛利 | 數量 | 毛利率 |
|---|---|---|---|---|
| 2026-01 | 46000 | 15870 | 1035 | 34.5% |
| 2026-02 | 50600 | 17963 | 1138 | 35.5% |
| 2026-03 | 55200 | 20148 | 1242 | 36.5% |
| 2026-04 | 59800 | 22425 | 1345 | 37.5% |
| 2026-05 | 64400 | 24794 | 1449 | 38.5% |
| 2026-06 | 45000 | 17775 | 1012 | 39.5% |
| 合計 | 321000 | 118975 | 7221 | 37.1% |

各區每月營收：

| 月份 | 北區 | 中區 | 南區 |
|---|---|---|---|
| 2026-01 | 20000 | 16000 | 10000 |
| 2026-02 | 22000 | 17600 | 11000 |
| 2026-03 | 24000 | 19200 | 12000 |
| 2026-04 | 26000 | 20800 | 13000 |
| 2026-05 | 28000 | 22400 | 14000 |
| 2026-06 | 6000 | 24000 | 15000 |
| 合計 | 126000 | 120000 | 75000 |

各產品：P01 營收 160500、數量 1605；P02 營收 96300、數量 2406；P03 營收 64200、數量 3210。

接上七月之後（S18）：共 63 筆；2026-07 營收 73600（北區 32000、中區 25600、南區 16000）、毛利 29808、數量 1656、毛利率 40.5%；合計營收 394600、毛利 148783、數量 8877。各區合計：北區 158000、中區 145600、南區 91000。各產品營收：P01 197300、P02 118380、P03 78920。

### 4.2 退貨

退貨金額合計 5150，11 筆。各區：北區 3500、中區 150、南區 1500。各產品：P01 3500、P02 1500、P09 150。

對照來源（S11）上的每月退貨率＝退貨金額 ÷ 銷售營收：

| 月份 | 退貨金額 | 銷售營收 | 退貨率 |
|---|---|---|---|
| 2026-01 | （空） | 46000 | （空） |
| 2026-02 | 700 | 50600 | 1.4% |
| 2026-03 | 850 | 55200 | 1.5% |
| 2026-04 | 1150 | 59800 | 1.9% |
| 2026-05 | 1150 | 64400 | 1.8% |
| 2026-06 | 1300 | 45000 | 2.9% |

依區域的退貨率只算 2026-02～06（分子、分母都有資料的月份）：北區 3500 ÷ 106000＝3.3%，中區 150 ÷ 104000＝0.1%，南區 1500 ÷ 65000＝2.3%。接上七月之後，這三個數字不變。

### 4.3 合併來源

- 疊加「台中店＋高雄店」：12 筆，營收合計 34500（台中店 21000、高雄店 13500）。每月：5000、5300、5600、5900、6200、6500。
- 查表「退貨（補產品主檔）」：11 筆。依類別：家電 5000，空白（P09）150。依產品名稱：電鍋 3500、檯燈 1500、空白 150；不會出現「檯燈（舊款）」。
- 對照「銷售／退貨 對照」：65 筆（54＋11）。每月的「營收」就是 4.1 的營收；退貨金額 1 月是空的。

## 5. 資料檢查 helper

每次打開或重新整理分頁之後，用 `evaluate_script` 跑一次下面這段（`function` 參數貼整段）。跑完頁面上會多一個 `window.jev`，之後的檢查都呼叫它；App 自己打的 `/api` 回應也會記下來，所以要在按「分析」之前先跑。

```js
() => {
  const xsrf = () => decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '')
  const api = async (method, url, body) => {
    const form = body instanceof URLSearchParams || body instanceof FormData
    const headers = {}
    if (body !== undefined && !form) headers['Content-Type'] = 'application/json'
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf()
    const res = await (window.__jevFetch ?? fetch)(url, { method, headers, body: body === undefined ? undefined : form ? body : JSON.stringify(body) })
    const text = await res.text()
    let json = text
    try { json = text ? JSON.parse(text) : null } catch {}
    return { status: res.status, body: json }
  }
  const summary = (cards, config) => (Array.isArray(cards) ? cards : []).map((d) => {
    const spec = config?.layout?.find((s) => s.chartId === d.chartId)
    const dims = d.dimensions ?? []
    const rows = d.rows ?? []
    const sums = {}
    dims.forEach((name, i) => {
      const vals = rows.map((r) => r[i]).filter((v) => v != null && v !== '')
      if (vals.length && vals.every((v) => typeof v === 'number')) sums[name] = Math.round(vals.reduce((a, b) => a + b, 0) * 1000) / 1000
    })
    return {
      chartId: d.chartId, type: d.type, title: d.title ?? spec?.title, source: d.source, error: d.error, missingField: d.missingField,
      mapping: spec?.mapping, dims, rows: rows.length, nulls: rows.reduce((n, r) => n + r.filter((v) => v === null).length, 0),
      x: d.type === 'table' ? undefined : [...new Set(rows.map((r) => r[0]))],
      sums, percent: d.percent, ratioPeriod: d.ratioPeriod, truncated: d.truncated, filterSkipped: d.filterSkipped,
      data: d.type !== 'table' && rows.length <= 24 ? rows : undefined,
    }
  })
  const jev = {
    api,
    summary,
    seen: window.jev?.seen ?? [],
    last: (part) => [...jev.seen].reverse().find((e) => e.url.includes(part)),
    login: async (username, password) => {
      await api('GET', '/api/auth/me') // 登出會清掉 XSRF-TOKEN，先拿一個，不然 POST 會被當成過期頁面擋下
      const r = await api('POST', '/api/auth/login', new URLSearchParams({ username, password }))
      await api('GET', '/api/auth/me') // 登入後 XSRF-TOKEN 會換，先拿一個新的
      return r
    },
    source: async (name) => {
      const ds = (await api('GET', '/api/datasources')).body.find((d) => d.name === name)
      return ds && {
        id: ds.id, kind: ds.kind, rowCount: ds.rowCount, merge: ds.merge,
        schema: ds.schema.map((f) => ({ name: f.name, type: f.type, label: f.label, sensitive: f.sensitive, sensitiveFrom: f.sensitiveFrom })),
        ratios: ds.ratios,
      }
    },
    dashId: async (name) => {
      const [own, shared] = await Promise.all([api('GET', '/api/dashboards'), api('GET', '/api/dashboards/shared')])
      return [...own.body, ...shared.body].find((d) => d.name === name)?.id
    },
    // filters 照篩選器 spec 3 的格式，例如 { period: 'last_quarter', fields: [{ field: '區域', values: ['北區'] }] }；不帶就是不篩選
    dashboard: async (id = location.hash.slice(4), filters) => {
      const config = (await api('GET', `/api/dashboards/${id}`)).body
      const data = await api('GET', `/api/dashboards/${id}/data${filters ? `?filters=${encodeURIComponent(JSON.stringify(filters))}` : ''}`)
      return {
        id, name: config.name, owner: config.createdBy, basis: config.basis, filters: config.filters, status: data.status,
        layout: config.layout.map((s) => ({ chartId: s.chartId, type: s.type, gridPos: s.gridPos, datasourceId: s.datasourceId })),
        cards: data.status === 200 ? summary(data.body, config) : data.body,
      }
    },
    preview: async (config = jev.last('/api/jev/analyze')?.body) => {
      const r = await api('POST', '/api/dashboards/preview/data', config)
      return { status: r.status, name: config.name, intents: config.intents, cards: r.status === 200 ? summary(r.body, config) : r.body }
    },
    card: async (datasourceId, mapping, type = 'bar') => {
      const config = { name: '檢查', layout: [{ chartId: 'c1', type, title: '檢查', gridPos: { x: 0, y: 0, w: 6, h: 4 }, mapping: { aggregate: 'sum', ...mapping }, datasourceId }] }
      const r = await api('POST', '/api/dashboards/preview/data', config)
      return r.status === 200 ? { ...summary(r.body, config)[0], data: r.body[0].rows } : r
    },
    cards: () => [...document.querySelectorAll('[data-chart-id]')].map((el) => ({
      chartId: el.dataset.chartId,
      title: el.querySelector('.tc-card-title .text-truncate')?.textContent.trim(),
      source: el.querySelector('.tc-source')?.textContent.trim() ?? null,
      focus: !!el.querySelector('.tc-tag'),
      badge: el.querySelector('.tc-badge')?.textContent.trim() ?? null,
      removable: !!el.querySelector('[aria-label="移除這張圖"]'),
      draggable: !!el.querySelector('.card-drag'),
      alert: el.querySelector('.v-alert')?.textContent.trim() ?? null,
      note: el.querySelector('.tc-ratio-period')?.textContent.trim() ?? null,
      filterNote: el.querySelector('.tc-filter-note')?.textContent.trim() ?? null,
      empty: el.querySelector('.tc-empty')?.textContent.trim() ?? null,
      canvas: !!el.querySelector('canvas, svg'),
    })),
    setField: (label, value) => {
      const visible = (el) => el && el.offsetParent !== null
      const ids = [...new Set([...document.querySelectorAll('label[for]')].filter((l) => visible(l) && l.textContent.trim() === label).map((l) => l.htmlFor))]
      const els = ids.length ? ids.map((id) => document.getElementById(id)) : [...document.querySelectorAll(`[aria-label="${label}"]`)].filter(visible)
      if (els.length !== 1) throw new Error(`「${label}」找到 ${els.length} 個欄位`)
      const el = els[0]
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value)
      el.dispatchEvent(new Event('input', { bubbles: true }))
      return el.value
    },
    // 拖拉卡片一律用這個（MCP drag 拖不動格線）；用 pointer 事件把 el 的中心點拖 dx、dy 像素
    dragBy: async (el, dx, dy) => {
      const r = el.getBoundingClientRect()
      const x = r.left + r.width / 2
      const y = r.top + r.height / 2
      const fire = (target, type, cx, cy) => target.dispatchEvent(new PointerEvent(type, {
        bubbles: true, cancelable: true, composed: true, clientX: cx, clientY: cy,
        pointerId: 1, pointerType: 'mouse', isPrimary: true, button: 0, buttons: type === 'pointerup' ? 0 : 1,
      }))
      fire(el, 'pointerdown', x, y)
      for (let i = 1; i <= 10; i++) {
        fire(document, 'pointermove', x + (dx * i) / 10, y + (dy * i) / 10)
        await new Promise((ok) => setTimeout(ok, 20))
      }
      fire(document, 'pointerup', x + dx, y + dy)
      await new Promise((ok) => setTimeout(ok, 500))
    },
  }
  window.jev = jev
  // 記下 App 自己打的 /api 回應（分析結果、/data、highlight）；重新整理或換頁之後要再跑一次這段
  if (!window.__jevFetch) {
    const orig = window.fetch.bind(window)
    window.__jevFetch = orig
    window.fetch = async (...args) => {
      const res = await orig(...args)
      const url = String(args[0])
      if (url.startsWith('/api/')) {
        res.clone().text().then((t) => {
          let body = t
          try { body = JSON.parse(t) } catch {}
          jev.seen.push({ method: args[1]?.method ?? 'GET', url, status: res.status, body })
        }, () => {})
      }
      return res
    }
  }
  return 'jev helper ready'
}
```

常用的呼叫（都是 `evaluate_script` 的 `function`）。章節的表格裡寫 `jev.xxx(…)` 的地方，意思都是 `async () => window.jev.xxx(…)`；參數裡的 `jev.source('…')` 要加 `await`，例如 `jev.card((await jev.source('銷售')).id, …)`。

| 要做什麼 | function |
|---|---|
| 呼叫 API（POST／PUT／PATCH／DELETE 會自動帶 `X-XSRF-TOKEN`；body 是 `FormData` 時原樣送出） | `async () => window.jev.api('PATCH', '/api/admin/accounts/e2e-admin', { enabled: false })` |
| 剛才的分析結果送去 `/preview/data`，每張卡片摘要 | `async () => window.jev.preview()` |
| 目前打開的儀表板：設定、版面、每張卡片摘要 | `async () => window.jev.dashboard()` |
| 同上，但 `/data` 帶著篩選 | `async () => window.jev.dashboard(undefined, { period: null, fields: [{ field: '區域', values: ['北區'] }] })` |
| 用名稱找來源 | `async () => window.jev.source('銷售')` |
| 用名稱找儀表板 id | `async () => window.jev.dashId('E2E 銷售總覽')` |
| 自己組一張卡片驗數字（不經過 JEV） | `async () => { const ds = await window.jev.source('退貨（補產品主檔）'); return window.jev.card(ds.id, { x: '類別', value: '退貨金額' }) }` |
| 畫面上每張卡片的標題、來源標籤、異常標註、✕、警告 | `() => window.jev.cards()` |
| 取代欄位內容（只送 `input`；顯示名稱這類靠 `change` 存檔的欄位不能用，見 2.5） | `() => window.jev.setField('密碼', 'E2eAdmin-2026')` |
| 拖拉或縮放卡片 | `async () => window.jev.dragBy(document.querySelectorAll('.card-drag')[0], 0, 400)` |

卡片摘要的欄位：`dims` 是維度名稱（已套用顯示名稱）；`rows` 是列數；`nulls` 是空值格數；`x` 是第一欄出現過的值，依時間的圖就是月份清單；`sums` 是每個數字欄的總和；`data` 在 24 列以內時附上全部的列；`percent`、`ratioPeriod` 只有比率才有；`filterSkipped` 只有帶了篩選、而且有篩選對這張圖沒生效時才有。

判斷規則：

- 每張卡片 `error` 是空的，`rows` 大於 0。
- `nulls` 只能出現在預期會空的地方：對照來源 1 月的退貨金額、退貨率 1 月。其他卡片都是 0。
- 聚合方式是 `sum` 的卡片（看 `mapping.aggregate`），同一個數字欄的 `sums` 等於第 4 節的合計，跟圖型和分組方式無關。散佈圖（`none`）和沒被截斷的明細表是原始資料列，總和也一樣。`percent: true` 的比率卡片，`sums` 不用看，直接比 `data`。
- `x` 是月份時，要跟預期的期間一模一樣，第一個不能少（以前出過第一期被吃掉的問題）。

## 6. 截圖規則

- 每個標「截圖」的步驟都要存檔，存完用 Read 打開來看。以前有幾次 agent 回報 PASS，截圖裡卻是：對照圖只畫了一邊的長條、散佈圖是空的、比率折線的 X 軸少了第一期。資料檢查抓不到這種畫面層的問題。
- 圖表卡片要一張一張截：`take_snapshot` 找到卡片的 uid，`take_screenshot` 帶 `uid` 和 `filePath`。整頁截圖只用來看版面。
- 每張圖要看到：

| 圖型 | 要看到什麼 |
|---|---|
| 所有圖 | 有畫出東西，不是空白卡片，也不是只有座標軸；沒有「欄位遺失」之類的警告；標題和座標軸用的是顯示名稱（例如「營收（元）」） |
| 折線、長條（X 軸是日期） | X 軸標籤跟預期的月份一樣，從第一個月開始，中間沒少；每一期都有點或長條（預期是空的那期除外） |
| 兩個數值（對照） | 圖例有兩個名稱，兩組都有畫出來；兩邊都有資料的月份，兩組都看得到；差距超過 10 倍時左右各有一條 Y 軸 |
| 有 series 的長條（堆疊）、熱力圖 | 圖例或色階有北區、中區、南區；2026-06 北區那一段明顯很小 |
| 圓餅 | 每塊有標籤，塊數跟分類數一樣 |
| 散佈 | 看得到點，點數大約等於資料列數，不是空的座標平面 |
| 比率 | Y 軸和 tooltip 是百分比；空的那期是缺口，不是畫成 0%；有排除期間時，圖下方有「只算 …（分子、分母都有資料的月份）」 |
| 明細表 | 表頭是顯示名稱，列數對；沒有「僅顯示前 500 筆」 |

- X 軸標籤擠到被省略時，用 `resize_page` 把視窗拉寬再截一次，不要猜。1440px 寬的視窗裡，半寬（w6）卡片的 7 個月份會隔一個省略一個，拉到 1900px 寬才會全部顯示。

## 7. 章節

### S01 登入、登出、密碼錯誤

- 目的：帳密登入、錯誤訊息、登出、帳號名稱不分大小寫。
- 帳號／context：`e2e-admin`；`anon`。
- 前置條件：S00 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S01-1 | `new_page` `http://localhost:5181`，`isolatedContext: "e2e-admin"`，記下 pageId；跑第 5 節的 helper | 登入頁：卡片標題「JEV 儀表板」，欄位「帳號」「密碼」，按鈕「登入」；沒有「用公司帳號登入」（沒設 OIDC）；登入卡片的右上角有切換主題的按鈕 | 畫面、截圖 |
| S01-2 | 帳號 `e2e-admin`、密碼 `wrong-pass-1`，按「登入」 | 卡片裡出現「帳號或密碼錯誤」，還在登入頁 | 畫面 |
| S01-3 | `jev.api('GET', '/api/auth/me')` | 401，`{"error":"請先登入"}` | API |
| S01-4 | `new_page` `http://localhost:5181`，`isolatedContext: "anon"`；帳號 `nobody`、密碼 `whatever-123`，按「登入」 | 一樣是「帳號或密碼錯誤」，看不出帳號存不存在 | 畫面 |
| S01-5 | 回到 e2e-admin 分頁，`jev.setField('密碼', 'E2eAdmin-2026')`，按「登入」 | 進到 App：頂部有「資料」「製圖」「儀表板」、「帳號」和「深色」；標題「儀表板」；空白狀態「還沒有儀表板」，第一步寫著「到「資料」上傳 CSV / Excel，或註冊外部 API，順手設好欄位的顯示名稱。」 | 畫面、截圖 |
| S01-6 | 重跑 helper；`jev.api('GET', '/api/auth/me')` | 200，`{"username":"e2e-admin","admin":true,"password":true}`。這證明前端接的是 8081 的新 schema | API |
| S01-7 | 按「帳號」 | 選單：「e2e-admin · 管理員」（灰色）、「改密碼」、「帳號管理」、「登出」 | 畫面 |
| S01-8 | 按「登出」 | 回到登入頁；`jev.api('GET', '/api/auth/me')`（先重跑 helper）回 401 | 畫面、API |
| S01-9 | 帳號填 `E2E-Admin`（大寫）、密碼 `E2eAdmin-2026`，登入 | 登入成功，帳號選單第一行是「e2e-admin · 管理員」（轉成小寫） | 畫面 |
| S01-10 | `press_key` Alt+1，再 Alt+2、Alt+3 | 依序切到「資料」「製圖」「儀表板」，導覽列選中的那顆變成實心 | 畫面 |
| S01-11 | `anon` 分頁 `close_page` | 只關自己開的那個分頁 | 畫面 |

留下的狀態：`e2e-admin` 分頁已登入，在儀表板頁。

### S02 帳號管理

- 目的：建立帳號、輸入檢查、停用與啟用、切換管理員、重設密碼，以及權限改變後的「請重新登入」。
- 帳號／context：`e2e-admin`（操作）；`alice`、`bob`、`dave`（被操作的那一方）。
- 前置條件：S01 完成，`e2e-admin` 已登入。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S02-1 | `e2e-admin`：「帳號」→「帳號管理」 | 對話框「帳號管理」：欄位「帳號」「初始密碼」（有眼睛圖示）、勾選框「管理員」、按鈕「新增帳號」；表頭「帳號／身分／登入方式／狀態／動作」；只有一列「e2e-admin（你）」管理員、密碼、啟用，動作欄是空的 | 畫面、截圖 |
| S02-2 | 帳號 `alice`、初始密碼 `Alice-init-01`，不勾管理員，按「新增帳號」 | 多一列 alice：一般、密碼、啟用，動作有「重設密碼」「停用」「設成管理員」；表單清空 | 畫面 |
| S02-3 | 照樣建 `bob`／`Bob-pass-01`、`carol`／`Carol-pass-01`、`dave`／`Dave-pass-01` | 表格共 5 列 | 畫面 |
| S02-4 | 帳號 `Alice`、初始密碼 `Whatever-01`，按「新增帳號」 | 「帳號「alice」已經存在」 | 畫面 |
| S02-5 | 帳號換成 `x`（用 setField），按「新增帳號」 | 「帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64」 | 畫面 |
| S02-6 | 帳號 `eve01`、初始密碼 `short7c`，按「新增帳號」 | 「密碼至少要 8 個字元」 | 畫面 |
| S02-7 | 帳號 `evelyn01`、初始密碼 `evelyn01`，按「新增帳號」 | 「密碼不能跟帳號一樣」 | 畫面 |
| S02-8 | `jev.api('GET', '/api/admin/accounts')` | 5 個帳號（e2e-admin、alice、bob、carol、dave），沒有 eve01、evelyn01 | API |
| S02-9 | `jev.api('PATCH', '/api/admin/accounts/e2e-admin', { enabled: false })`；`{ admin: false }`；`jev.api('PUT', '/api/admin/accounts/e2e-admin/password', { password: 'Another-pass-1' })` | 都是 400：「不能停用自己的帳號」「不能取消自己的管理員」「自己的密碼請用「改密碼」」 | API |
| S02-10 | `new_page`，`isolatedContext: "alice"`，L1 登入 alice／`Alice-init-01`；按「帳號」 | 選單：「alice」、「改密碼」、「登出」，沒有「帳號管理」；空白狀態第一步沒有「或註冊外部 API，」 | 畫面 |
| S02-11 | alice 分頁跑 helper；`jev.api('GET', '/api/admin/accounts')` | 403，`{"error":"沒有權限"}` | API |
| S02-12 | `e2e-admin`：bob 那列按「停用」 | 狀態變「停用」，按鈕變「啟用」 | 畫面 |
| S02-13 | `new_page`，`isolatedContext: "bob"`，用 `Bob-pass-01` 登入 | 「帳號已停用，請聯絡管理員」 | 畫面 |
| S02-14 | 同一個分頁，密碼改成 `wrong-pass-1`（setField），登入 | 「帳號或密碼錯誤」（密碼錯時不透露帳號停用了） | 畫面 |
| S02-15 | `e2e-admin`：bob 那列按「啟用」；bob 分頁密碼改回 `Bob-pass-01` 登入 | 狀態「啟用」；bob 登入成功 | 畫面 |
| S02-16 | `new_page`，`isolatedContext: "dave"`，L1 登入 dave／`Dave-pass-01` | 登入成功，選單沒有「帳號管理」 | 畫面 |
| S02-17 | `e2e-admin`：dave 那列按「設成管理員」 | 身分變「管理員」，按鈕變「取消管理員」 | 畫面 |
| S02-18 | dave 分頁按導覽列「資料」 | 跳出「請重新登入」：「登入已經逾時，請重新登入。畫面上的東西會留著。」，帳號欄已經填好 dave；點對話框外面關不掉 | 畫面、截圖 |
| S02-19 | 在對話框輸入 `Dave-pass-01`，按「登入」 | 對話框關掉，還在資料頁；「帳號」選單第一行變成「dave · 管理員」，多了「帳號管理」；頁面上紅色的「登入逾時，重新登入後請再操作一次」收起來，剛才失敗的 `GET /api/datasources` 重新抓一次。已知問題（backlog 2026-10-02）：紅色提示還在，`/api/datasources` 也沒有重抓 | 畫面、API |
| S02-20 | `e2e-admin`：dave 那列按「取消管理員」 | 身分變回「一般」 | 畫面 |
| S02-21 | `e2e-admin`：alice 那列按「重設密碼」，下方出現「新密碼」欄，輸入 `short`，按「儲存」 | 對話框上方「密碼至少要 8 個字元」 | 畫面 |
| S02-22 | 新密碼改成 `Alice-reset-02`（setField），按「儲存」 | 綠色訊息「已重設「alice」的密碼」，那一列收起來 | 畫面 |
| S02-23 | alice 分頁按導覽列「製圖」 | 跳出「請重新登入」，帳號是 alice | 畫面 |
| S02-24 | 對話框輸入舊密碼 `Alice-init-01` 登入 | 對話框裡「帳號或密碼錯誤」 | 畫面 |
| S02-25 | 改成 `Alice-reset-02`（setField）登入 | 對話框關掉，製圖頁還在；紅色的「登入逾時」提示收起來。已知問題（backlog 2026-10-02）：同 S02-19，提示還在 | 畫面 |
| S02-26 | `e2e-admin` 的帳號管理整個截圖 | 5 列：e2e-admin（你）管理員；alice、bob、carol、dave 都是一般、密碼、啟用 | 截圖 |

留下的狀態：5 個帳號；alice 的密碼是 `Alice-reset-02`；`e2e-admin`、`alice`、`bob`、`dave` 都已登入。

### S03 改自己的密碼

- 目的：改密碼對話框的檢查、成功後要重新登入。
- 帳號／context：`alice`。
- 前置條件：S02 完成，alice 的密碼是 `Alice-reset-02`。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S03-1 | 「帳號」→「改密碼」 | 對話框「改密碼」：「目前的密碼」「新密碼」「再輸入一次新密碼」，按鈕「取消」「更新密碼」。提示「至少 8 個字元」要點進「新密碼」才會出現，剛打開時看不到 | 畫面 |
| S03-2 | 目前的密碼 `wrong-pass-9`，新密碼兩欄都填 `Alice-final-03`，按「更新密碼」 | 「目前的密碼不對」 | 畫面 |
| S03-3 | 目前的密碼改成 `Alice-reset-02`、再輸入一次改成 `Alice-final-04`（setField），按「更新密碼」 | 「兩次輸入的新密碼不一樣」；`list_network_requests` 沒有新的 `POST /api/auth/password`（前端就擋下了） | 畫面、API |
| S03-4 | 新密碼兩欄都改成 `short`，按「更新密碼」 | 「密碼至少要 8 個字元」，由後端檢查，`list_network_requests` 有 `POST /api/auth/password` 回 400。前端只擋 S03-3 那種兩次輸入不一樣的情況 | 畫面、API |
| S03-5 | 按「取消」，再打開「改密碼」 | 三個欄位都是空的 | 畫面 |
| S03-6 | 目前的密碼 `Alice-reset-02`，新密碼兩欄 `Alice-final-03`，按「更新密碼」 | 「密碼已更新，請用新密碼重新登入」，只剩「重新登入」按鈕；點對話框外面關不掉 | 畫面、截圖 |
| S03-7 | 按「重新登入」 | 整頁重新載入，回到登入頁 | 畫面 |
| S03-8 | 用 `Alice-reset-02` 登入 | 「帳號或密碼錯誤」 | 畫面 |
| S03-9 | 用 `Alice-final-03` 登入（setField） | 進到 App | 畫面 |

留下的狀態：alice 的密碼是 `Alice-final-03`。

### S04 上傳 CSV 和 Excel

- 目的：上傳 CSV、Excel（預設工作表和指定工作表）、型別判斷，以及上傳錯誤。
- 帳號／context：`alice`。
- 前置條件：S03 完成；固定資料在 `<scratchpad>/e2e-fixtures/`。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S04-1 | 切到「資料」 | 標題「資料」；左邊「來源 · 0」「還沒有資料來源」；右邊「上傳 CSV / Excel，欄位會自動判斷型別。」（沒有「或註冊一個外部 API，」） | 畫面 |
| S04-2 | 按「新增來源」 | 對話框「新增資料來源」，分頁只有「上傳檔案」「合併」，沒有「外部 API」；欄位「CSV / Excel」「名稱（預設用檔名）」「工作表（Excel 選填）」 | 畫面、截圖 |
| S04-3 | 不選檔案直接按「上傳」 | 「請先選擇檔案」 | 畫面 |
| S04-4 | `upload_file` 到「CSV / Excel」：`sales-h1.csv`；名稱 `銷售`；按「上傳」 | 對話框關掉；左邊「來源 · 1」，「銷售」「6 個欄位」被選中；右邊標題「銷售」、標籤「上傳檔案」、「6 個欄位 · 0 個敏感」 | 畫面 |
| S04-5 | 看欄位表 | 日期＝日期、區域＝文字、產品代碼＝文字、營收＝數字、毛利＝數字、數量＝數字；樣本欄有值 | 畫面、截圖 |
| S04-6 | 跑 helper；`jev.source('銷售')` | `kind: "upload"`、`rowCount: 54` | API |
| S04-7 | 新增來源：`returns.csv`，名稱 `退貨` | `rowCount: 11`；日期＝日期、退貨金額＝數字 | 畫面、API |
| S04-8 | 新增來源：`products.csv`，名稱 `產品主檔` | `rowCount: 4`；三欄都是文字 | 畫面、API |
| S04-9 | 新增來源：`stores.xlsx`，名稱 `台中店`，工作表留空 | `rowCount: 6`；欄位：日期（日期）、產品代碼（文字）、營收（數字） | 畫面、API |
| S04-10 | 新增來源：`stores.xlsx`，名稱 `高雄店`，工作表 `高雄店` | `rowCount: 6`；第三欄是「金額」（數字），證明讀的是第二個工作表 | 畫面、API |
| S04-11 | 新增來源：`stores.xlsx`，名稱 `台北店`，工作表 `台北店` | 對話框裡「找不到工作表：台北店」，沒有新增來源 | 畫面 |
| S04-12 | 同一個對話框：檔案換成 `bad-rows.csv`，名稱改 `壞檔`，工作表清空（setField） | 「第 3 行有 3 欄，標題列是 4 欄」 | 畫面 |
| S04-13 | 檔案換成 `products.txt`，名稱改 `文字檔` | 「只支援 .csv、.xlsx、.xls」 | 畫面 |
| S04-14 | 按「取消」；`jev.api('GET', '/api/datasources')` | 只有 5 個來源：銷售、退貨、產品主檔、台中店、高雄店 | API |
| S04-15 | 「搜尋來源」輸入 `店` | 清單只剩台中店、高雄店；改成 `zzz`（setField）時是「沒有符合的來源」；清空後回到 5 個 | 畫面 |
| S04-16 | 截整頁 | 左邊 5 個來源，右邊是目前選的來源 | 截圖 |

留下的狀態：alice 有 5 個上傳來源。

### S05 欄位設定：顯示名稱、敏感

- 目的：顯示名稱的儲存、重複檢查、清除；敏感開關。
- 帳號／context：`alice`。
- 前置條件：S04 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S05-1 | 選「銷售」；在「營收 顯示名稱」輸入 `營收（元）`，`press_key` Enter | 欄位顯示「營收（元）」；`jev.source('銷售')` 的營收 `label: "營收（元）"` | 畫面、API |
| S05-2 | 「產品代碼 顯示名稱」輸入 `區域`，按 Enter | 頁面上方紅色訊息「顯示名稱「區域」重複」；那一格變回空的 | 畫面 |
| S05-3 | 「毛利 顯示名稱」輸入 `毛利額`，按 Enter；再用真的按鍵清空：`click` 那一格，`press_key` Control+A、Backspace、Enter（不要用 setField，它不會觸發存檔，見 2.5） | 先存成「毛利額」，清空時送出 PATCH 回 200，清空後 `label` 變回 null，欄位只剩淡色的 placeholder「毛利」 | 畫面、API |
| S05-4 | 打開「數量 敏感」 | 標題下方變成「6 個欄位 · 1 個敏感」；`jev.source('銷售')` 的數量 `sensitive: true` | 畫面、API |
| S05-5 | 截欄位表 | 營收的顯示名稱是「營收（元）」，數量的敏感開關是開的 | 截圖 |

留下的狀態：銷售的營收顯示成「營收（元）」，數量是敏感欄位。

### S06 比率

- 目的：新增比率的檢查、敏感比率、刪除比率。
- 帳號／context：`alice`。
- 前置條件：S05 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S06-1 | 選「銷售」，看欄位表下面 | 「比率 · 0」，說明「比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。」 | 畫面 |
| S06-2 | 按「新增比率」 | 對話框「新增比率」：「名稱」（placeholder「例如退貨率」）「分子」「分母」；「新增」是停用的 | 畫面 |
| S06-3 | 打開「分子」下拉 | 只有數字欄位，用顯示名稱：營收（元）、毛利、數量 | 畫面 |
| S06-4 | 名稱 `毛利率`，分子、分母都選「毛利」 | 「新增」還是停用 | 畫面 |
| S06-5 | 分母改成「營收（元）」，按「新增」 | 比率表多一列：「毛利率｜毛利 ÷ 營收（元）」，沒有「敏感」標籤；「比率 · 1」 | 畫面 |
| S06-6 | 再按「新增比率」：名稱 `營收`，分子「毛利」，分母「營收（元）」，按「新增」 | 對話框裡「比率名稱「營收」跟欄位或其他比率重複」；按「取消」 | 畫面 |
| S06-7 | 新增比率：名稱 `每件毛利`，分子「毛利」，分母「數量」 | 新的那列有「敏感」標籤（數量是敏感欄位） | 畫面 |
| S06-8 | 按「刪除比率 每件毛利」 | 不用確認，直接不見；「比率 · 1」 | 畫面 |
| S06-9 | `jev.source('銷售')` | `ratios` 只有 `{ name: "毛利率", numerator: "毛利", denominator: "營收", sensitive: false }` | API |
| S06-10 | 截比率區 | 只有毛利率一列 | 截圖 |

留下的狀態：銷售有比率「毛利率」。

### S07 製圖：依需求分析、調整、存檔

- 目的：寫一句需求分析、預覽的移除與復原、調整、存成儀表板。
- 帳號／context：`alice`。
- 前置條件：S06 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S07-1 | 資料頁選「銷售」，按「用這個來源製圖」 | 切到「製圖」，資料來源是「銷售」；欄位標籤：日期、區域、產品代碼、營收（元）、毛利、數量、毛利率（百分比圖示）；右邊「圖表會出現在這裡」 | 畫面 |
| S07-2 | 跑 helper（之後要用分析結果） | `jev helper ready` | API |
| S07-3 | 「想看什麼？（選填）」輸入 `各區每月營收`，按「分析」 | 先出現「JEV 正在分析「銷售」…」；`wait_for`「存成新儀表板」，最多 60 秒 | 畫面 |
| S07-4 | 看預覽 | 至少一張卡片有「符合需求」；沒有「找不到跟需求直接相關的欄位，先顯示一般的分析結果。」；「儀表板名稱」是「銷售 總覽」；左邊出現「04 調整」和「需求紀錄」，只有一句「各區每月營收」；按鈕變「重新分析」 | 畫面、截圖 |
| S07-5 | `jev.preview()` | 每張卡片沒有 `error`、`rows` 大於 0、`nulls` 是 0；X 軸是日期的卡片，`x` 正好是 2026-01～2026-06；`sum` 卡片的總和：營收（元）321000、毛利 118975、數量 7221；明細表 54 列，`dims` 裡是「營收（元）」 | API |
| S07-6 | 每張圖卡各截一張 | 照第 6 節的表檢查；營收的圖標題和座標軸是「營收（元）」 | 截圖 |
| S07-7 | 跑下方的「移除和復原」，它會在一張不是明細表的卡片按「移除這張圖」，接著按「復原」；再跑 `jev.cards()` | `removed` 少了那一張；`toast` 是「已移除「<title>」」，`title` 是卡片上的標題（有套顯示名稱，例如「營收（元） 依 區域」）；明細表那張 `removable: false`。已知問題（backlog 2026-10-02）：提示用的是原始標題，例如「已移除「營收 依 區域」」 | 畫面 |
| S07-8 | 看同一次的回傳值 | `after` 跟 `before` 一樣（卡片回到原位），`toastGone: true` | 畫面 |
| S07-9 | 「想怎麼調整？」輸入 `不要圓餅圖，加毛利率的趨勢`，按「調整」 | 調整完，需求紀錄變兩句；原本有圓餅圖的話，現在沒有了〔JEV〕；多一張毛利率的折線圖〔JEV〕；圖表最多 6 張，加上明細表 | 畫面 |
| S07-10 | `jev.preview()` | 規則跟 S07-5 一樣；有毛利率卡片的話，`percent: true`，`data` 依序是 0.345、0.355、0.365、0.375、0.385、0.395 | API |
| S07-11 | 截毛利率那張 | Y 軸是百分比，X 軸從 2026-01 開始，6 個點都在，往上爬 | 截圖 |
| S07-12 | 用 setField 把「儀表板名稱」清空 | 欄位下「請輸入儀表板名稱」，「存成新儀表板」停用 | 畫面 |
| S07-13 | 名稱改成 `E2E 銷售總覽`，按「存成新儀表板」 | 切到儀表板頁，標題「E2E 銷售總覽」；左邊「我的儀表板 · 1」；圖表上方「依據：各區每月營收 → 不要圓餅圖，加毛利率的趨勢」；網址變成 `#/d/<uuid>` | 畫面、截圖 |
| S07-14 | 回到「製圖」 | 預覽清掉了，又是「圖表會出現在這裡」，「加到既有儀表板」不見；「想看什麼？」的框還留著「各區每月營收」 | 畫面 |
| S07-15 | 點一下「想看什麼？」的框，`press_key` Control+A、Backspace 清空，再 `press_key` Control+Enter | 框是空的也一樣開始分析；結果出來後，名稱改成 `E2E 暫存`，按「存成新儀表板」 | 畫面 |
| S07-16 | 儀表板頁左邊 | 「我的儀表板 · 2」：E2E 暫存、E2E 銷售總覽 | 畫面 |

移除和復原（S07-7、S07-8，用一個 `evaluate_script` 跑完；復原只有 6 秒，分開呼叫工具會來不及）：

```js
async () => {
  window.scrollTo(0, 0)
  const wait = (ms) => new Promise((ok) => setTimeout(ok, ms))
  const ids = () => [...document.querySelectorAll('[data-chart-id]')].map((el) => el.dataset.chartId)
  const before = ids()
  const card = [...document.querySelectorAll('[data-chart-id]')].find((el) => el.querySelector('[aria-label="移除這張圖"]'))
  const title = card.querySelector('.tc-card-title .text-truncate').textContent.trim()
  card.querySelector('[aria-label="移除這張圖"]').click()
  await wait(500)
  const removed = ids()
  const toast = document.body.innerText.match(/已移除「[^」]*」/)?.[0] ?? null
  ;[...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '復原')?.click()
  await wait(1000)
  return { title, toast, before, removed, after: ids(), toastGone: !document.body.innerText.includes('已移除「') }
}
```

留下的狀態：儀表板「E2E 銷售總覽」和「E2E 暫存」。

### S08 已存的儀表板：重開、拖拉、縮放、移除、異常標註

- 目的：重開不重新分析、版面存檔、移除單張圖、刪除儀表板、異常標註。
- 帳號／context：`alice`。
- 前置條件：S07 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S08-1 | 打開「E2E 銷售總覽」；`navigate_page` 重新整理 | 直接回到這份儀表板（網址有 `#/d/<id>`）；副標「卡片拖標題列可以移動、拉右下角可以縮放，版面會自動存。」；標題列有「分享」「新增儀表板」 | 畫面 |
| S08-2 | `list_network_requests` | 有 `GET /api/dashboards/<id>`、`/api/dashboards/<id>/data`、`/api/jev/highlight/<id>`；沒有 `/api/jev/analyze` | API |
| S08-3 | 跑 helper；`jev.dashboard()` | 數字跟 S07-10 一樣（X 軸月份、總和） | API |
| S08-4 | `jev.api('GET', '/api/jev/highlight/' + location.hash.slice(4))`，再 `jev.cards()` | 回傳陣列，每一筆的 `severity` 是「正常波動」「值得注意」「需要關注」「嚴重警示」其中一個，`noul` 大於 0.7；畫面上有標註的卡片和陣列一一對應，文字一樣。預期至少一張被標註（北區 6 月掉了 79%）〔JEV〕。用到「數量」（敏感）的卡片一定沒有標註 | API、截圖 |
| S08-5 | 記下 `jev.dashboard()` 的 `layout`；把第一張卡片往下拖：`async () => window.jev.dragBy(document.querySelectorAll('.card-drag')[0], 0, 400)`。不要用 MCP `drag`，它拖不動（見 2.5） | 卡片移動了 | 畫面 |
| S08-6 | `list_network_requests`；`jev.dashboard()` | 有 `PUT /api/dashboards/<id>` 回 200；`gridPos` 跟拖之前不一樣。沒有 PUT 就記 FAIL，附上截圖 | 畫面、API |
| S08-7 | 縮放：`async () => window.jev.dragBy(document.querySelectorAll('.vgl-item__resizer')[0], 200, 120)` | 有 PUT；那張卡片的 `gridPos.w` 或 `h` 變大 | API |
| S08-8 | 重新整理，跑 helper，`jev.dashboard()` | `gridPos` 跟 S08-7 之後一樣 | API、截圖 |
| S08-9 | 在明細表按「移除這張圖」 | 對話框「移除圖表」：「確定從「E2E 銷售總覽」移除「<標題>」？移除後無法復原，要加回來得重新分析。」；按「取消」，卡片還在 | 畫面 |
| S08-10 | 再按一次，按「移除」 | 卡片不見；PUT 回 200；重新整理後還是不見；`layout` 少一張 | 畫面、API |
| S08-11 | 左邊「E2E 暫存」那列按「刪除儀表板」 | 對話框「刪除儀表板」：「確定刪除「E2E 暫存」？刪除後無法復原。」 | 畫面 |
| S08-12 | 按「刪除」 | 清單剩「E2E 銷售總覽」，「我的儀表板 · 1」 | 畫面 |
| S08-13 | 截整頁 | 版面是拖過、縮放過的樣子，沒有明細表 | 截圖 |

留下的狀態：「E2E 銷售總覽」少了明細表，版面動過；「E2E 暫存」已刪除。

### S09 一份儀表板放多個來源

- 目的：分析另一個來源，加到既有儀表板；來源標籤和分來源的依據。
- 帳號／context：`alice`。
- 前置條件：S08 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S09-1 | 「製圖」，資料來源選「退貨」 | 欄位標籤：日期、區域、產品代碼、退貨金額 | 畫面 |
| S09-2 | 跑 helper；需求 `各區退貨金額`，按「分析」，等「存成新儀表板」 | 預覽出來；有「符合需求」〔JEV〕 | 畫面 |
| S09-3 | `jev.preview()` | `sum` 卡片的退貨金額總和 5150；依月份的 `x` 是 2026-02～2026-06（退貨沒有 1 月）；`nulls` 0 | API |
| S09-4 | 打開「加到既有儀表板」 | 選單只有「E2E 銷售總覽」 | 畫面 |
| S09-5 | 點「E2E 銷售總覽」 | 切到儀表板頁打開它；新卡片接在最下面；名稱還是「E2E 銷售總覽」 | 畫面 |
| S09-6 | `jev.cards()` | 每張卡片都有來源標籤，「銷售」或「退貨」 | 畫面 |
| S09-7 | 看依據 | 兩行：「依據（銷售）：各區每月營收 → 不要圓餅圖，加毛利率的趨勢」「依據（退貨）：各區退貨金額」 | 畫面 |
| S09-8 | `jev.dashboard()` | 每張卡片的 `source` 跟標籤一樣；銷售卡片的數字照 S07，退貨卡片照 S09-3 | API |
| S09-9 | 截整頁，再截每張退貨圖卡 | 照第 6 節；來源標籤看得到。明細表卡片裡的表格可以捲動，表頭固定，捲到底看得到退貨明細的第 11 列（S11-14 的 E2E 對照 明細也一樣，捲到底是第 65 列） | 截圖 |

留下的狀態：「E2E 銷售總覽」有銷售和退貨兩個來源的圖。

### S10 合併來源：疊加、查表

- 目的：疊加的欄位對應（含請 JEV 對欄位）、查表的對接和提示、建立前一定要預覽。
- 帳號／context：`alice`。
- 前置條件：S09 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S10-1 | 「資料」→「新增來源」→「合併」 | 三個選項：「疊加（欄位一樣的資料接起來）」（預設）「對照（數值依來源分開比較）」「查表（用主檔補欄位）」；「預覽」「建立」都停用 | 畫面 |
| S10-2 | 「來源」選台中店、高雄店 | 對應表：輸出欄位｜台中店｜高雄店，4 列：日期、產品代碼、營收（高雄店是「（無）」）、金額（台中店是「（無）」）；名稱自動填「台中店＋高雄店」；「來源欄名稱」是「來源」 | 畫面、截圖 |
| S10-3 | 按「預覽」 | 「預覽 · 共 12 筆」，兩個提示：「「營收」只有台中店有，其他來源的列這欄是空的」「「金額」只有高雄店有，其他來源的列這欄是空的」 | 畫面 |
| S10-4 | 按「請 JEV 對欄位」 | 「JEV 對上了 1 個欄位，建立前請先預覽確認」，營收那列的高雄店是「金額」，下面有「JEV 建議」，金額那列不見；預覽被清掉，「建立」停用〔JEV〕 | 畫面 |
| S10-5 | JEV 沒對上（「JEV 沒有找到可以對上的欄位」）時，手動：「第 3 欄在高雄店的欄位」選「金額」，按「刪除第 4 欄」 | 對應表變成 3 列：日期、產品代碼、營收 | 畫面 |
| S10-6 | 按「預覽」 | 「共 12 筆」，沒有提示；表頭：日期、產品代碼、營收、來源；台中店的列在前面 | 畫面、截圖 |
| S10-7 | 按「建立」 | 對話框關掉，選中「台中店＋高雄店」，標籤「合併」，「疊加：台中店、高雄店 · 4 個欄位 · 0 個敏感」 | 畫面 |
| S10-8 | 跑 helper；`async () => { const ds = await window.jev.source('台中店＋高雄店'); return window.jev.card(ds.id, { x: '來源', value: '營收' }) }` | `data`：台中店 21000、高雄店 13500 | API |
| S10-9 | 新增來源 →「合併」→「查表」；主來源「退貨」，打開「主檔」下拉 | 主檔清單裡沒有「退貨」 | 畫面 |
| S10-10 | 主檔「產品主檔」；第 1 組對接：「第 1 組主來源欄位」選產品代碼、「第 1 組主檔欄位」選產品代碼 | 「補上的欄位」三列：「產品主檔·產品代碼」（選了對接之後自動取消勾選）、產品名稱、類別；名稱自動填「退貨（補產品主檔）」 | 畫面 |
| S10-11 | 勾回「補上 產品代碼」，把它的輸出名稱改成 `產品代碼`，按「預覽」 | 「欄位名稱「產品代碼」跟主來源的欄位重複」 | 畫面 |
| S10-12 | 取消勾選「補上 產品代碼」，按「預覽」 | 「共 11 筆」；提示「主檔有 1 個對接值重複，用排最前面的那一列」「1 筆對不到主檔，補上的欄位是空的」；P01 是電鍋／家電，P02 是檯燈／家電（不是檯燈（舊款）），P09 那列產品名稱和類別是空的 | 畫面、截圖 |
| S10-13 | 按「建立」 | 選中「退貨（補產品主檔）」，「查表：退貨 ← 產品主檔（產品代碼 = 產品代碼） · 6 個欄位 · 0 個敏感」 | 畫面 |
| S10-14 | `jev.card((await jev.source('退貨（補產品主檔）')).id, { x: '類別', value: '退貨金額' })` | 兩組：家電 5000、「（空白）」150（P09 對不到主檔，類別是空值），合計 5150 | API |

留下的狀態：合併來源「台中店＋高雄店」「退貨（補產品主檔）」。

### S11 合併來源：對照和退貨率

- 目的：對照來源的對應規則、繼承的敏感和顯示名稱、對照圖、跨來源的比率和期間對齊。
- 帳號／context：`alice`。
- 前置條件：S10 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S11-1 | 新增來源 →「合併」→「對照」，來源選銷售、退貨 | 對應表 7 列：日期、區域、產品代碼（兩邊都對上），銷售·營收、銷售·毛利、銷售·數量、退貨·退貨金額（各自只對一個來源）；名稱「銷售／退貨 對照」 | 畫面 |
| S11-2 | 在「銷售·營收」那列，退貨那格選「退貨金額」 | 那列的輸出名稱下面紅字「對照時數字欄位只能對應一個來源」；把那格清掉，紅字消失 | 畫面 |
| S11-3 | 按「預覽」 | 「共 65 筆」，沒有提示 | 畫面 |
| S11-4 | 按「建立」 | 「對照：銷售、退貨 · 8 個欄位 · 1 個敏感」 | 畫面 |
| S11-5 | 看欄位表；`() => [...document.querySelectorAll('td[title]')].map((td) => td.title)` | 「銷售·數量」的敏感開關是開的、停用；那一格的 `title` 是「在『銷售』標為敏感，要改請到那個來源」（瀏覽器原生的提示，`wait_for`、`take_snapshot`、截圖都看不到，只能讀 `td[title]`）；「銷售·營收」的顯示名稱沿用「營收（元）」 | 畫面、截圖 |
| S11-6 | 新增比率：名稱 `退貨率`，分子「退貨·退貨金額」，分母「營收（元）」 | 比率表「退貨率｜退貨·退貨金額 ÷ 營收（元）」 | 畫面 |
| S11-7 | 跑 helper；`jev.card((await jev.source('銷售／退貨 對照')).id, { x: '日期', value: '退貨率', timeGrain: 'month' }, 'line')` | `percent: true`；`data`：2026-01 是 null，02～06 約 0.0138、0.0154、0.0192、0.0179、0.0289 | API |
| S11-8 | `jev.card((await jev.source('銷售／退貨 對照')).id, { x: '區域', value: '退貨率' })` | 北區約 0.0330、中區約 0.0014、南區約 0.0231；`ratioPeriod` 是 `{ from: "2026-02", to: "2026-06" }` | API |
| S11-9 | 按「用這個來源製圖」；跑 helper；需求用 `jev.setField('想看什麼？（選填）', '每月營收和退貨金額對照')` 填（框裡還留著 S09 的「各區退貨金額」，`fill` 會接在後面），按「分析」 | 預覽出來；有一張營收和退貨金額依月份的雙數值圖〔JEV〕。JEV 可能只做依區域、依產品代碼的雙數值圖，依月份那張到 S11-12 調整後才出現 | 畫面 |
| S11-10 | `jev.preview()`；預覽裡還沒有依月份的雙數值卡片時，改跑 `jev.card((await jev.source('銷售／退貨 對照')).id, { x: '日期', measures: ['銷售·營收', '退貨·退貨金額'], timeGrain: 'month' })`，S11-12 之後再跑一次 `jev.preview()` | 依月份的雙數值卡片〔JEV〕6 列：2026-01 [46000, null]，之後 [50600, 700]、[55200, 850]、[59800, 1150]、[64400, 1150]、[45000, 1300]（兩個數值的順序可能對調）；`nulls` 1。`jev.card` 的結果也一樣 | API |
| S11-11 | 截依月份的雙數值那張〔JEV〕（S11-9 沒有的話，等 S11-12 之後再截） | 圖例兩個名稱（「營收（元）」和「退貨·退貨金額」）；兩組都有畫；左右各一條 Y 軸；X 軸 2026-01～06，1 月只有營收 | 截圖 |
| S11-12 | 「想怎麼調整？」輸入 `加每月退貨率`，按「調整」 | 多一張退貨率的圖〔JEV〕；依月份的雙數值圖可能這時才出現，出現了就回頭補 S11-10、S11-11 | 畫面 |
| S11-13 | 截退貨率那張（有的話） | Y 軸是百分比；X 軸從 2026-01 開始，1 月是空的（缺口，不是 0%）；依區域的話，圖下方有「只算 2026-02～2026-06（分子、分母都有資料的月份）」 | 截圖 |
| S11-14 | 名稱改成 `E2E 對照`，存成新儀表板 | 儀表板頁打開「E2E 對照」，「我的儀表板 · 2」 | 畫面 |

留下的狀態：對照來源「銷售／退貨 對照」有比率「退貨率」；儀表板「E2E 對照」。

### S12 刪除來源

- 目的：被合併來源用到時不能刪；被儀表板用到時確認後照刪，卡片顯示已刪除，版面照樣能存。
- 帳號／context：`alice`。
- 前置條件：S11 完成。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S12-1 | 製圖頁選「台中店＋高雄店」，不寫需求按「分析」，名稱 `E2E 分店`，存成新儀表板 | 儀表板頁打開「E2E 分店」；`jev.dashboard()` 的營收總和 34500，每月 5000～6500 | 畫面、API |
| S12-2 | 資料頁選「台中店」，按「刪除來源」 | 對話框「無法刪除」：「「台中店」是下面這些合併來源的輸入，要先刪掉它們：」，下面一行「台中店＋高雄店」；只有「知道了」 | 畫面、截圖 |
| S12-3 | 按「知道了」；`jev.api('DELETE', '/api/datasources/' + (await jev.source('台中店')).id)` | 來源還在；API 回 409，「「台中店」是這些合併來源的輸入，要先刪掉：台中店＋高雄店」 | API |
| S12-4 | 選「台中店＋高雄店」，按「刪除來源」 | 對話框「刪除資料來源」：「確定刪除「台中店＋高雄店」？刪除後無法復原。」，加上「下面這些儀表板有用到它的圖，刪除後會顯示「資料來源已刪除」，可以再按 ✕ 移除：」和「E2E 分店（N 張圖）」，N 是那份儀表板的卡片數 | 畫面、截圖 |
| S12-5 | 按「取消」 | 來源還在 | 畫面 |
| S12-6 | 再按「刪除來源」→「刪除」 | 對話框關掉，清單裡沒有它，右邊改選第一個來源 | 畫面 |
| S12-7 | 儀表板頁打開「E2E 分店」 | 整份打得開，沒有紅色錯誤；每張卡片都是「資料來源已刪除，可以按右上角 ✕ 移除這張圖」 | 畫面、截圖 |
| S12-8 | 拖一張卡片（S08-5 的 `jev.dragBy`） | PUT 回 200 | API |
| S12-9 | 在一張卡片按 ✕ →「移除」 | 卡片不見，PUT 回 200 | 畫面、API |
| S12-10 | 資料頁選「台中店」，按「刪除來源」 | 這次是「刪除資料來源」，沒有儀表板那一段；按「刪除」，台中店不見 | 畫面 |
| S12-11 | `jev.api('GET', '/api/datasources/' + (await jev.source('退貨')).id + '/usage')` | `mergedBy` 是「退貨（補產品主檔）」和「銷售／退貨 對照」；`dashboards` 有「E2E 銷售總覽」，張數是退貨卡片數 | API |

留下的狀態：來源剩銷售、退貨、產品主檔、高雄店、退貨（補產品主檔）、銷售／退貨 對照；儀表板有「E2E 銷售總覽」「E2E 對照」「E2E 分店」（卡片都顯示已刪除）。

### S13 分享

- 目的：分享給帳號、輸入檢查、檢視者只能看、公開連結、取消分享。
- 帳號／context：`alice`（擁有者）；`bob`（檢視者）；`anon`。
- 前置條件：S12 完成；bob 已登入（S02）。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S13-1 | alice 打開「E2E 銷售總覽」，按「分享」 | 對話框「分享「E2E 銷售總覽」」：「公開連結」開關（關）「有帳號的人拿到連結就能看」；「分享給」欄位（placeholder「輸入對方的帳號」，提示「對方的帳號在他帳號選單的第一行」）和「加入」；「還沒有分享給任何人」；連結 `http://localhost:5181/#/d/<id>`、「複製連結」；「對方看到的資料跟你一樣，包括明細表和敏感欄位。」；「取消」「儲存」 | 畫面、截圖 |
| S13-2 | 輸入 `nobody`，按「加入」，按「儲存」 | 對話框裡「找不到帳號「nobody」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結」，對話框沒關 | 畫面 |
| S13-3 | 按「移除「nobody」」；輸入 `alice`，加入，儲存 | 移除 nobody 之後，「找不到帳號「nobody」…」跟著消失；儲存後是「不用分享給自己」。已知問題（backlog 2026-10-02）：nobody 的錯誤訊息一直留著，等到儲存才被換掉 | 畫面 |
| S13-4 | 移除 alice；輸入 `BOB`，加入 | 標籤是「bob」（轉成小寫） | 畫面 |
| S13-5 | 按「儲存」 | 對話框關掉；`jev.api('GET', '/api/dashboards/' + location.hash.slice(4) + '/share')` 是 `{ publicLink: false, users: ["bob"] }` | 畫面、API |
| S13-6 | 再按一次「分享」（S13-5 儲存時對話框關掉了），按「複製連結」；看完按「取消」 | 按鈕變「已複製」；瀏覽器不讓複製時，連結被選取，欄位下方「瀏覽器不讓網頁直接複製，請按 Ctrl+C」。兩種都算 PASS | 畫面 |
| S13-7 | bob：`new_page`（`isolatedContext: "bob"`）`http://localhost:5181` | 左邊「我的儀表板 · 0」、「分享給我的 · 1」，裡面「E2E 銷售總覽」，副標「來自 alice」 | 畫面 |
| S13-8 | bob 打開它；跑 helper；`jev.cards()`；`document.querySelectorAll('.vgl-item__resizer').length` | 副標「「alice」分享給你的儀表板，只能檢視。」；沒有「分享」，只有「新增儀表板」；每張卡片 `removable: false`、`draggable: false`；resizer 是 0 個；依據兩行看得到；異常標註跟 alice 看到的一樣 | 畫面、API、截圖 |
| S13-9 | bob：檢視者的卡片沒有 `.card-drag`，改拖標題：`async () => window.jev.dragBy(document.querySelector('[data-chart-id] .tc-card-title'), 0, 400)` | 卡片不動；`list_network_requests` 沒有 PUT | 畫面、API |
| S13-10 | bob：`jev.dashboard()`；把讀到的設定原樣 PUT 回去：`jev.api('PUT', '/api/dashboards/' + location.hash.slice(4), (await jev.api('GET', '/api/dashboards/' + location.hash.slice(4))).body)` | 卡片數字跟 alice 的一模一樣，`gridPos` 也沒變；PUT 回 404「找不到儀表板」（body 不完整會先回 400，所以要送完整的設定） | API |
| S13-11 | bob 切到「資料」 | 「還沒有資料來源」，看不到 alice 的來源 | 畫面 |
| S13-12 | bob：`navigate_page` 到 `http://localhost:5181/#/d/<E2E 對照 的 id>`（id 用 alice 分頁的 `jev.dashId('E2E 對照')` 查） | 「找不到這份儀表板，可能已經刪除或取消分享」 | 畫面 |
| S13-13 | alice 打開「E2E 對照」→「分享」，打開「公開連結」，儲存 | 對話框關掉 | 畫面 |
| S13-14 | bob 重新整理同一個網址 | 打開「E2E 對照」，唯讀；左邊「分享給我的」還是只有 1 份（公開連結不會列進來） | 畫面、截圖 |
| S13-15 | `anon`：`new_page` 同一個網址（`isolatedContext: "anon"`） | 登入頁，看不到儀表板（沒有免登入的連結） | 畫面 |
| S13-16 | bob 先從「分享給我的」打開「E2E 銷售總覽」（S13-14 之後 bob 停在「E2E 對照」，沒先打開的話這一步等於沒測）；alice：「E2E 銷售總覽」的分享移除 bob，儲存；bob 的畫面先不動 | bob 已經開著的畫面不會自己消失 | 畫面 |
| S13-17 | bob 打開「E2E 銷售總覽」的連結後重新整理 | 「找不到這份儀表板，可能已經刪除或取消分享」；「分享給我的」整段不見 | 畫面 |
| S13-18 | alice 再把 bob 加回「E2E 銷售總覽」，儲存 | bob 重新整理後又看得到（S18 要用） | 畫面 |

留下的狀態：「E2E 銷售總覽」分享給 bob；「E2E 對照」開了公開連結。

### S14 深淺主題

- 目的：切換主題、記住選擇、登入頁的主題鈕、窄螢幕。
- 帳號／context：`bob`；`anon`。
- 前置條件：S13 完成；bob 打開「E2E 銷售總覽」。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S14-1 | 看導覽列最右邊 | 按鈕「深色」，aria-label「切換成深色主題」；`document.documentElement.dataset.theme` 不是 `dark` | 畫面 |
| S14-2 | 按「深色」 | 整頁變深色，按鈕變「淺色」；`dataset.theme` 是 `dark`；`localStorage.getItem('jev-theme')` 是 `dark` | 畫面、API、截圖 |
| S14-3 | 截一張圖卡 | 圖表跟著換成深色配色，文字和座標軸看得清楚 | 截圖 |
| S14-4 | 重新整理 | 還是深色，載入時沒有閃一下淺色 | 畫面 |
| S14-5 | 按「淺色」 | 回到淺色；`localStorage` 是 `light` | 畫面 |
| S14-6 | `anon` 分頁（登入頁）按登入卡片右上角的「切換成深色主題」 | 登入頁變深色 | 畫面、截圖 |
| S14-7 | 記下 `innerWidth`、`innerHeight`；bob 分頁 `emulate` viewport `390x844x2`（`resize_page` 最窄只到約 500px），看導覽列和儀表板 | `innerWidth` 是 390；「資料」「製圖」「儀表板」都放得下；帳號鈕和主題鈕只剩圖示；`document.documentElement.scrollWidth <= innerWidth`；卡片排成一欄，標題和異常標註看得清楚。已知問題（backlog 2026-10-02）：卡片還是兩欄、每張約 150px，標題被擠成「毛.」或整個不見，「嚴重警示」標註超出畫面被切掉 | 畫面、截圖 |
| S14-8 | `emulate` 把 viewport 清掉，再 `resize_page` 回 S14-7 記下的大小 | 版面恢復，按鈕文字回來 | 畫面 |

留下的狀態：bob 是淺色，anon 是深色（之後沒有用到）。

### S15 外部 API 只有管理員能註冊

- 目的：外部 API 分頁只給管理員；註冊時的網址和 header 檢查。
- 帳號／context：`alice`；`e2e-admin`。
- 前置條件：S14 完成；後端啟動時允許 `example.com`（S00 的說明）。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S15-1 | alice：資料頁「新增來源」 | 分頁只有「上傳檔案」「合併」 | 畫面 |
| S15-2 | alice：`jev.api('POST', '/api/datasources/api', { name: 'x', url: 'https://example.com/', headers: {} })` | 403，「沒有權限」 | API |
| S15-3 | `e2e-admin`：切到「資料」 | 空白狀態「上傳 CSV / Excel，或註冊一個外部 API，欄位會自動判斷型別。」 | 畫面 |
| S15-4 | 按「新增來源」 | 分頁「上傳檔案」「外部 API」「合併」 | 畫面 |
| S15-5 | 「外部 API」分頁 | 欄位「名稱」「網址」「headers（JSON，值只能寫 ${DS_環境變數名}）」，預設 `{}`，提示 `範例：{"Authorization":"${DS_ERP_AUTH}"}。密鑰還要在 backend/.env 綁定這個網址的 host`；按鈕「註冊」 | 畫面、截圖 |
| S15-6 | 名稱 `E2E API`、網址 `https://example.com/`、headers 改成 `not json`，按「註冊」 | 「headers 必須是 JSON 物件」 | 畫面 |
| S15-7 | headers 改成 `[]` | 一樣是「headers 必須是 JSON 物件」 | 畫面 |
| S15-8 | headers 改回 `{}`，網址 `ftp://example.com/x` | 「只允許 http 或 https」 | 畫面 |
| S15-9 | 網址 `https://example.com/?token=abc` | 「網址不能包含帳密或 token，請改放在 headers，值寫 ${DS_環境變數名}」 | 畫面 |
| S15-10 | 網址 `https://evil.example.org/data` | 「host 不在允許清單：evil.example.org」 | 畫面 |
| S15-11 | 網址 `http://127.0.0.1:8081/api/auth/options` | 「host 不在允許清單：127.0.0.1」 | 畫面 |
| S15-12 | 網址 `https://example.com/`，headers `{"Authorization":"Bearer abc"}` | 「header「Authorization」的值只能寫 ${DS_環境變數名}」 | 畫面 |
| S15-13 | headers `{"Authorization":"${DS_E2E_TOKEN}"}` | 過了檢查、真的去抓，等幾秒後「外部 API 呼叫失敗（回應必須是 JSON 陣列）」（example.com 回的是 HTML）。這台機器連不到外網時會是「外部 API 無法連線」，記在備註 | 畫面 |
| S15-14 | 按「取消」；`jev.api('GET', '/api/datasources')` | `e2e-admin` 沒有任何來源 | API |

留下的狀態：沒有新增任何東西。外部 API 在本機抓不到資料（loopback 會被擋，也不能把 mock 開在區網），所以這一節只驗誰看得到這個分頁，以及各種錯誤訊息。

### S16 外部 API 允許清單、DS_ 密鑰綁 host

- 目的：允許清單（`API_ALLOWED_HOSTS`）和密鑰綁 host（`jev.datasource.secret-hosts.[DS_X]`）。要測的情況有：密鑰沒綁、綁到別的 host、兩個 header 有一個沒綁、host 大小寫不同，以及允許清單是空的。
- 帳號／context：`e2e-admin`；`admin-8082`。
- 前置條件：S15 完成；S00 照指令啟動，允許清單是 `example.com,www.example.com`，`DS_E2E_TOKEN` 只綁 `example.com`；這台機器查得到 `example.com` 的 DNS。查不到的話，S16-1～4 都會先回「無法解析 host：…」，記 BLOCKED。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S16-1 | `e2e-admin`：資料頁「新增來源」，會直接停在「外部 API」分頁，三個欄位還留著 S15-13 的值，所以都用 setField 改：名稱 `E2E API`、網址 `https://example.com/`、headers `{"Authorization":"${DS_E2E_UNBOUND}"}`（這個變數沒設，也沒綁 host），按「註冊」 | 對話框裡「密鑰 DS_E2E_UNBOUND 沒有開放給 example.com：請在 backend/.env 加上 jev.datasource.secret-hosts.[DS_E2E_UNBOUND]=example.com」，對話框沒關。不是「環境變數 DS_E2E_UNBOUND 未設定」，代表沒綁的密鑰在檢查階段就被擋下，值也沒讀 | 畫面、截圖 |
| S16-2 | headers 改成 `{"Authorization":"${DS_E2E_TOKEN}","X-Trace":"${DS_E2E_UNBOUND}"}`（用 setField） | 一樣是 DS_E2E_UNBOUND 那句，只要有一把沒綁就整個擋下 | 畫面 |
| S16-3 | 網址改成 `https://www.example.com/`，headers 改成 `{"Authorization":"${DS_E2E_TOKEN}"}` | 「密鑰 DS_E2E_TOKEN 沒有開放給 www.example.com：請在 backend/.env 加上 jev.datasource.secret-hosts.[DS_E2E_TOKEN]=www.example.com」。`www.example.com` 在允許清單裡，但密鑰只綁 `example.com` | 畫面 |
| S16-4 | 網址改成 `https://EXAMPLE.com/`，headers 不變 | 過了綁定檢查，等幾秒後「外部 API 呼叫失敗（回應必須是 JSON 陣列）」，跟 S15-13 一樣，可見 host 大小寫不分 | 畫面 |
| S16-5 | 按「取消」；`jev.api('GET', '/api/datasources')` | `e2e-admin` 還是沒有任何來源 | API |
| S16-6 | 在 `backend/` 用 `run_in_background` 開第二個後端（同一個 schema，允許清單留空）：`SPRING_DATASOURCE_HIKARI_SCHEMA=<schema> SPRING_FLYWAY_SCHEMAS=<schema> ADMIN_USERNAME= API_ALLOWED_HOSTS= java -jar target/jev-dashboard-0.1.0-SNAPSHOT.jar --server.port=8082`（S00 有加 OIDC 那兩個變數的話，這裡也要加）；等 `http://127.0.0.1:8082/api/auth/options` 回 200 | 啟動成功 | API |
| S16-7 | `new_page` `http://127.0.0.1:8082/api/auth/options`（`isolatedContext: "admin-8082"`）；跑 helper；`jev.login('e2e-admin', 'E2eAdmin-2026')` | 頁面是 `{"oidc":null}`；登入回 204 | API |
| S16-8 | `jev.api('POST', '/api/datasources/api', { name: 'x', url: 'https://example.com/', headers: {} })` | 400，`{"error":"未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS"}` | API |
| S16-9 | `close_page` 這個分頁；TaskStop 停掉 8082 的後端；`netstat` 確認 8082 沒人聽 | 8082 釋放 | API |

留下的狀態：沒有新增任何東西；8082 已停。外部 API 在本機抓不到資料，所以「拿掉綁定、重啟後端之後，已經註冊的來源會被擋」這條規則只有後端測試驗得到。

### S17 登入失敗鎖定

- 目的：同一個帳號的密碼連續錯 5 次就鎖 15 分鐘，改密碼時輸錯「目前的密碼」也算一次。鎖住時密碼對了也進不去，改密碼也改不了，但已經登入的畫面照常能用。不存在的帳號和別的帳號都不受影響。管理員重設密碼就解鎖，登入成功後次數從 0 重新算。
- 帳號／context：`carol`（已經登入的分頁）；`anon`（送登入請求）；`e2e-admin`（重設密碼）。
- 前置條件：S16 完成；carol 從來沒登入失敗過。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S17-1 | `new_page` `http://localhost:5181`（`isolatedContext: "carol"`），L1 登入 carol／`Carol-pass-01` | 進到 App | 畫面 |
| S17-2 | `new_page` `http://localhost:5181`（`isolatedContext: "anon"`），停在登入頁；跑 helper；`async () => { const out = []; for (let n = 1; n <= 4; n++) out.push(await window.jev.login('carol', 'wrong-pass-' + n)); return out }` | 4 次都是 401，`{"error":"帳號或密碼錯誤"}` | API |
| S17-3 | carol 分頁：「帳號」→「改密碼」；目前的密碼 `wrong-pass-5`，新密碼兩欄都填 `Carol-new-03`，按「更新密碼」 | 「目前的密碼不對」。這是第 5 次錯，帳號從這時開始鎖；後端背景程序的輸出多一行 WARN「帳號「carol」密碼連續錯了 5 次，鎖住 15 分鐘」 | 畫面、API |
| S17-4 | 同一個對話框：目前的密碼改成 `Carol-pass-01`（對的，用 setField），按「更新密碼」 | 對話框裡「登入失敗太多次，請 15 分鐘後再試」，對話框沒關，密碼沒改。按「取消」，再按導覽列「資料」：照常切過去，沒有跳出「請重新登入」 | 畫面、截圖 |
| S17-5 | anon 分頁：`jev.login('carol', 'Carol-pass-01')`（密碼是對的） | 429，`{"error":"登入失敗太多次，請 15 分鐘後再試"}`。N 是剩下的分鐘數無條件進位，離 S17-3 超過 1 分鐘才送的話是 14 | API |
| S17-6 | anon 分頁的登入頁：帳號 `carol`、密碼 `Carol-pass-01`，按「登入」 | 卡片裡「登入失敗太多次，請 N 分鐘後再試」，還在登入頁；欄位留著，「登入」按鈕還能按 | 畫面、截圖 |
| S17-7 | `async () => { const out = []; for (let n = 1; n <= 6; n++) out.push(await window.jev.login('ghost', 'wrong-pass-' + n)); return out }` | 6 次都是 401「帳號或密碼錯誤」，沒有 429（不存在的帳號不會被鎖） | API |
| S17-8 | `jev.login('dave', 'Dave-pass-01')`；`jev.api('GET', '/api/auth/me')`；`jev.api('POST', '/api/auth/logout')` | 登入 204，`me` 的 `username` 是 `dave`，登出 204。別的帳號不受影響 | API |
| S17-9 | `e2e-admin`：「帳號」→「帳號管理」，carol 那列「重設密碼」，新密碼 `Carol-reset-02`，按「儲存」 | 綠色訊息「已重設「carol」的密碼」 | 畫面 |
| S17-10 | anon 分頁：`jev.login('carol', 'Carol-reset-02')`；`jev.api('POST', '/api/auth/logout')` | 登入 204，鎖馬上解開（還沒過 15 分鐘）；登出 204 | API |
| S17-11 | `jev.login('carol', 'Carol-pass-01')`（舊密碼） | 401「帳號或密碼錯誤」 | API |
| S17-12 | 再錯 3 次（`wrong-pass-1`～`3`），用 `Carol-reset-02` 登入、登出；接著錯 4 次（`wrong-pass-4`～`7`）；最後用 `Carol-reset-02` 登入、登出 | 前 3 次 401，登入、登出都是 204；後 4 次都是 401，沒有 429；最後的登入、登出也是 204。登入成功會把次數歸零；沒歸零的話，後 4 次的第 2 次就會變成 429 | API |
| S17-13 | `close_page` carol 和 anon 這兩個分頁 | 只關自己開的分頁 | 畫面 |

留下的狀態：carol 的密碼是 `Carol-reset-02`，沒有被鎖，失敗次數是 0。

### S18 更新資料

- 目的：上傳來源的「更新資料」，包括對話框、接在後面、整份覆蓋和 Excel 指定工作表，以及各種錯誤。用到它的儀表板和合併來源要跟著更新。
- 帳號／context：`alice`；`bob`。
- 前置條件：S17 完成；「E2E 銷售總覽」分享給 bob（S13-18）。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S18-1 | alice 資料頁依序選「銷售」「高雄店」「退貨（補產品主檔）」「銷售／退貨 對照」 | 上傳來源（銷售、高雄店）的標題列是「更新資料」「刪除來源」「用這個來源製圖」；兩個合併來源沒有「更新資料」 | 畫面 |
| S18-2 | 跑 helper；`jev.source('銷售')`、`jev.source('銷售／退貨 對照')`、`jev.source('退貨（補產品主檔）')` | `rowCount` 依序是 54、65、11；記下銷售的 `id`（S18-18 要用） | API |
| S18-3 | 選「銷售」，按「更新資料」 | 對話框「更新資料」，說明兩行：「「銷售」目前有 54 筆資料。新檔案的欄位名稱要跟原本一樣。」「更新後，用到它的圖和合併來源都會顯示新的資料。」；欄位「CSV / Excel」「工作表（Excel 選填）」；選項「整份覆蓋」「接在後面」，兩個都沒選；按鈕「取消」和「更新」，「更新」是停用的 | 畫面、截圖 |
| S18-4 | 選「接在後面」，不選檔案就按「更新」 | 選了方式之後「更新」才能按；按下去對話框裡出現「請先選擇檔案」 | 畫面 |
| S18-5 | `upload_file` 到對話框裡的「CSV / Excel」（uid 認明是這個對話框的，不是「新增來源」留在 DOM 裡的那個）：`sales-07-missing-column.csv`；按「更新」 | 對話框裡「欄位跟原本的不一樣：少了 毛利」，對話框沒關 | 畫面、截圖 |
| S18-6 | 檔案換成 `sales-07-text-number.csv`，按「更新」；`jev.source('銷售')` | 「欄位「營收」原本是數字，新檔案裡有不是數字的值」，用的是欄位名稱，不是顯示名稱；`rowCount` 還是 54 | 畫面、API |
| S18-7 | 檔案換成 `sales-07.csv`，按「更新」 | 對話框關掉；頁面上方綠色訊息「已更新「銷售」：共 63 筆」，右邊有 ✕ | 畫面、截圖 |
| S18-8 | `jev.source('銷售')`；`(await jev.api('GET', '/api/datasources')).body.find((d) => d.name === '銷售').schema.find((f) => f.name === '日期')` | `rowCount: 63`；營收的 `label` 還是「營收（元）」，數量還是 `sensitive: true`，`ratios` 還是只有毛利率；日期的 `min` 是 `2026-01-05`，`max` 是 `2026-07-25` | API |
| S18-9 | `jev.source('銷售／退貨 對照')`、`jev.source('退貨（補產品主檔）')` | 對照的 `rowCount` 是 null（輸入來源更新過，筆數清掉了）；退貨（補產品主檔）沒用到銷售，還是 11 | API |
| S18-10 | 再按一次「更新資料」 | 上方的成功訊息收起來了；說明變成「「銷售」目前有 63 筆資料。…」；沒有檔案、沒選方式、沒有錯誤訊息 | 畫面 |
| S18-11 | 按「取消」；`jev.source('銷售')` | 對話框關掉，`rowCount` 還是 63 | 畫面、API |
| S18-12 | 打開「E2E 銷售總覽」；跑 helper；`jev.dashboard()` | 依月份的銷售卡片 `x` 是 2026-01～2026-07；`sum` 卡片的營收（元）394600；用到毛利的 `sum` 卡片或散佈圖（`aggregate: none`，原始資料列），`sums` 裡的毛利是 148783〔JEV〕；有毛利率卡片的話，`data` 最後一個是 0.405；退貨卡片跟 S09-3 一樣（5150）。這份儀表板沒有用到數量的卡片（數量是敏感欄位），數量 8877 在 S18-15 的明細表驗 | API |
| S18-13 | 先 `resize_page` 拉到 1900px 寬（第 6 節），每張銷售圖卡各截一張 | X 軸多了 2026-07，排在最後，前面 6 期一個都沒少 | 截圖 |
| S18-14 | `jev.api('GET', '/api/jev/highlight/' + location.hash.slice(4))` | 200，陣列每一筆只有 `chartId`、`severity`、`noul`，沒有期間欄位，看不出比的是哪兩期。後端只比最後兩個已經結束的期間，接上 7 月之後就是 2026-06 和 2026-07，依月份卡片的標註可能跟 S08-4 不一樣〔JEV〕（第一次全跑時 c2 從「嚴重警示」變成「需要關注」）。記下回傳的陣列和畫面上的標註就好 | API |
| S18-15 | 打開「E2E 對照」；`jev.dashboard()`；再跑 S11-7、S11-8 那兩個 `jev.card` | 雙數值卡片 7 列，多一列 2026-07 [73600, null]，`nulls` 2；明細表 74 列（63＋11），「銷售·數量」的 `sums` 是 8877；每月退貨率多一期 2026-07，是 null；依區域的退貨率跟 S11-8 一樣（北區約 0.0330、中區約 0.0014、南區約 0.0231），`ratioPeriod` 還是 `{ from: "2026-02", to: "2026-06" }` | API |
| S18-16 | 一樣在 1900px 寬的視窗截雙數值那張，截完 `resize_page` 拉回原本大小 | X 軸到 2026-07，每個月份標籤都看得到，7 月只有營收 | 截圖 |
| S18-17 | bob 重新整理「E2E 銷售總覽」；跑 helper；`jev.dashboard()` | 跟 S18-12 一樣的數字 | API |
| S18-18 | bob：`async () => { const form = new FormData(); form.append('file', new File(['日期\n2026-08-05\n'], 'check.csv')); form.append('mode', 'append'); return window.jev.api('POST', '/api/datasources/<S18-2 的銷售 id>/rows', form) }` | 404，`{"error":"找不到資料源"}`，別人的來源不能更新 | API |
| S18-19 | alice：跑下方的「更新資料 API 檢查」 | `noMode` 400「缺少參數：mode」；`noFile` 400「缺少參數：file」；`badMode` 400「更新方式只能是 replace（整份覆蓋）或 append（接在後面）」；`merged` 400「只有上傳的檔案可以更新資料」；`headerOnly` 400「沒有資料列」；`rowCount` 還是 63 | API |
| S18-20 | 新增來源：`sales-h1.csv`，名稱 `覆蓋測試`；選它，按「更新資料」，檔案 `sales-07.csv`，選「整份覆蓋」，按「更新」 | 「已更新「覆蓋測試」：共 9 筆」 | 畫面 |
| S18-21 | `jev.card((await jev.source('覆蓋測試')).id, { x: '日期', value: '營收', timeGrain: 'month' })` | `x` 只有 2026-07，營收總和 73600 | API |
| S18-22 | 「覆蓋測試」按「更新資料」，檔案 `sales-07-missing-column.csv`，選「整份覆蓋」，按「更新」；按「取消」；`jev.source('覆蓋測試')` | 「欄位跟原本的不一樣：少了 毛利」，整份覆蓋也要同一組欄位；`rowCount` 還是 9 | 畫面、API |
| S18-23 | 選「高雄店」，按「更新資料」，檔案 `stores.xlsx`，工作表留空，選「整份覆蓋」，按「更新」 | 「欄位跟原本的不一樣：少了 金額；多了 營收」。沒指定工作表就讀第一個（台中店） | 畫面 |
| S18-24 | 工作表填 `台北店`，按「更新」 | 「找不到工作表：台北店」 | 畫面 |
| S18-25 | 工作表改成 `高雄店`（setField），按「更新」；`jev.card((await jev.source('高雄店')).id, { x: '產品代碼', value: '金額' })` | 「已更新「高雄店」：共 6 筆」；金額總和 13500 | 畫面、API |

更新資料 API 檢查（S18-19，在 alice 分頁用 `evaluate_script` 跑）：

```js
async () => {
  const send = async (name, mode, csv) => {
    const form = new FormData()
    if (csv !== null) form.append('file', new File([csv], 'check.csv'))
    if (mode) form.append('mode', mode)
    return window.jev.api('POST', `/api/datasources/${(await window.jev.source(name)).id}/rows`, form)
  }
  const head = '日期,區域,產品代碼,營收,毛利,數量\n'
  const row = '2026-08-05,北區,P01,1,1,1\n'
  return {
    noMode: await send('銷售', null, head + row),
    noFile: await send('銷售', 'append', null),
    badMode: await send('銷售', 'merge', head + row),
    merged: await send('銷售／退貨 對照', 'append', head + row),
    headerOnly: await send('銷售', 'append', head),
    rowCount: (await window.jev.source('銷售')).rowCount,
  }
}
```

留下的狀態：銷售 63 筆；銷售／退貨 對照的 `rowCount` 是 null；覆蓋測試 9 筆；高雄店 6 筆。

### S19 儀表板篩選器

- 目的：篩選列的期間、分類 chip、清除；照顯示名稱對到不同來源的欄位；沒套用的篩選；篩選後沒有資料；存成預設；檢視者可以臨時改、不能存。規則見 `docs/superpowers/specs/2026-10-07-dashboard-filters-design.md`。
- 帳號／context：`alice`；`bob`。
- 前置條件：S18 完成；「E2E 銷售總覽」已分享給 bob（S13-18）。

期間的範圍照執行當天算。下面「上季」的預期，以在 2026-10-01～12-31 之間執行為準：上季是 2026-07～09，資料裡只有 2026-07。在別的日期跑，就照第 4 節的資料，自己算那一季在資料裡有哪幾個月。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S19-1 | alice 打開「E2E 銷售總覽」 | 「依據」下面、格線上面有一列篩選列：「期間」選單顯示「不限」、「加篩選」、「存成預設」（停用）；沒有 chip，也沒有「清除篩選」 | 畫面、截圖 |
| S19-2 | 跑 helper；跑下方的「建分店業績來源」；重新整理，再跑一次 helper | `upload`、`labels`、`append` 都是 200；最下面多一張「amount 依 區域」，來源標籤「分店業績」 | API、畫面 |
| S19-3 | 按「加篩選」 | 選單裡有「欄位」「值」「取消」「套用」，「套用」停用；`list_network_requests` 有一次 `GET /api/dashboards/<id>/filter-options`；`jev.last('/filter-options').body` 是 `{ fields: [{ field: "區域", values: ["中區", "北區", "南區"] }, { field: "產品代碼", values: ["P01", "P02", "P03", "P09"] }] }`。分店業績的 `region` 顯示名稱是「區域」，併進同一個欄位 | 畫面、API |
| S19-4 | 「欄位」選「區域」，「值」選「北區」，按「套用」 | 選單關掉；chip「區域：北區」；多了「清除篩選」；「存成預設」可以按；`list_network_requests` 有帶 `filters=` 的 `/data` 和 `/api/jev/highlight` | 畫面、API |
| S19-5 | `jev.dashboard(undefined, { period: null, fields: [{ field: '區域', values: ['北區'] }] })` | 每張卡片都沒有 `filterSkipped`；`sum` 卡片的營收（元）是 158000；依月份的銷售卡片 `x` 是 2026-01～2026-07，每月 20000、22000、24000、26000、28000、6000、32000；有區域分組的卡片 `x` 只有北區；退貨卡片的退貨金額是 3500；分店業績的卡片 `data` 是 `[["北區", 107]]` | API |
| S19-6 | 每張圖卡各截一張 | 照第 6 節；有區域的圖只剩北區（堆疊長條、圓餅都只有北區）；分店業績只有北區一根長條；數字跟 S19-5 一樣 | 截圖 |
| S19-7 | 點 chip「區域：北區」 | 打開同一個選單，「欄位」是「區域」，「值」已經選了「北區」 | 畫面 |
| S19-8 | 「值」再選「南區」，按「套用」；`jev.dashboard(undefined, { period: null, fields: [{ field: '區域', values: ['北區', '南區'] }] })` | chip「區域：北區、南區」；`sum` 卡片的營收（元）是 249000；分店業績的卡片 `data` 是 `[["北區", 107], ["南區", 40]]` | 畫面、API |
| S19-9 | 按 chip 上的「移除「區域」篩選」；按「加篩選」，「欄位」選「產品代碼」，「值」選「P01」，按「套用」 | 「欄位」的清單又有「區域」；chip「產品代碼：P01」；分店業績的卡片最下面一行小字「沒套用篩選：產品代碼（這張圖的來源沒有這些欄位）」，圖照樣畫；其他卡片沒有這行（`jev.cards()` 的 `filterNote`） | 畫面、截圖 |
| S19-10 | `jev.dashboard(undefined, { period: null, fields: [{ field: '產品代碼', values: ['P01'] }] })` | 分店業績的卡片 `filterSkipped: ["產品代碼"]`，數字沒變（147）；其他卡片沒有 `filterSkipped`；`sum` 卡片的營收（元）是 197300；退貨卡片是 3500 | API |
| S19-11 | 按「清除篩選」；`jev.dashboard()` | chip 不見，「期間」是「不限」，「清除篩選」不見；數字跟 S18-12 一樣，分店業績是 147 | 畫面、API |
| S19-12 | 「期間」選「上季」；`jev.cards()` | 依月份的銷售卡片只剩 2026-07（營收 73600）；退貨卡片的 `empty` 是「篩選後沒有資料」，`canvas` 是 false；分店業績的卡片只算 7 月（北區 100、南區 40）；沒有任何異常標註（只剩一期，比不出來） | 畫面、API |
| S19-13 | 截依月份的銷售圖和一張退貨圖 | 銷售圖的 X 軸只有 2026-07；退貨卡片中間寫「篩選後沒有資料」，沒有空的座標軸 | 截圖 |
| S19-14 | 「期間」選回「不限」；加篩選「區域：北區」；按「存成預設」 | `PUT /api/dashboards/<id>` 回 200；「存成預設」變成停用；PUT 之後沒有再打 `/data` | 畫面、API |
| S19-15 | 重新整理；跑 helper；`jev.dashboard()` | chip「區域：北區」還在，「存成預設」停用；`filters` 是 `{ fields: [{ field: "區域", values: ["北區"] }] }`；`list_network_requests` 的第一個 `/data` 就帶著 `filters=` | 畫面、API |
| S19-16 | bob 打開「E2E 銷售總覽」；跑 helper；`jev.last('/data').body` | 篩選列有 chip「區域：北區」，沒有「存成預設」；數字跟 S19-5 一樣 | 畫面、API、截圖 |
| S19-17 | bob 按「加篩選」，看「欄位」的清單，看完按「取消」；`jev.last('/filter-options').body`；再跑 `jev.dashboard(undefined, { period: null, fields: [{ field: '產品代碼', values: ['P01'] }] })` | 只列卡片用到的文字欄位，加上預設篩選的「區域」，是 S19-3 的子集；沒有卡片用到產品代碼時〔JEV〕，清單裡沒有「產品代碼」，`/data` 帶著它時每張卡的 `filterSkipped` 都有「產品代碼」，數字跟沒篩選一樣（營收（元）394600） | 畫面、API |
| S19-18 | bob 按「移除「區域」篩選」；重新整理 | 移除後卡片回到全部資料（營收（元）394600）；重新整理又回到預設，chip「區域：北區」 | 畫面 |
| S19-19 | alice 重新整理 | 還是預設「區域：北區」，沒被 bob 影響 | 畫面 |
| S19-20 | alice：跑下方的「篩選 API 檢查」 | `period` 400「期間只能是 本月、上月、本季、上季、今年、去年」；`tooMany` 400「分類篩選最多 3 個」；`noValues` 400「每個篩選要選 1～200 個值」；`notJson` 400「篩選的格式不對」；`put` 400「同一個欄位只能篩選一次」；`saved` 還是 S19-15 的預設 | API |

建分店業績來源（S19-2，在 alice 分頁用 `evaluate_script` 跑，要停在「E2E 銷售總覽」）：

```js
async () => {
  const form = new FormData()
  form.append('file', new File(['date,region,amount\n2026-05-02,北區,7\n2026-07-03,北區,100\n2026-07-10,南區,40\n'], '分店業績.csv'))
  form.append('name', '分店業績')
  const up = await window.jev.api('POST', '/api/datasources/upload', form)
  const labels = await window.jev.api('PATCH', `/api/datasources/${up.body.id}/labels`, { labels: { region: '區域' } })
  const card = { chartId: 'c1', type: 'bar', title: 'amount 依 region', gridPos: { x: 0, y: 0, w: 6, h: 4 },
    mapping: { x: 'region', value: 'amount', aggregate: 'sum' }, datasourceId: up.body.id }
  const append = await window.jev.api('POST', `/api/dashboards/${location.hash.slice(4)}/charts`, { layout: [card] })
  return { upload: up.status, labels: labels.status, append: append.status }
}
```

篩選 API 檢查（S19-20）：

```js
async () => {
  const id = location.hash.slice(4)
  const data = (f) => window.jev.api('GET', `/api/dashboards/${id}/data?filters=${encodeURIComponent(f)}`)
  const field = (name) => ({ field: name, values: ['x'] })
  const config = (await window.jev.api('GET', `/api/dashboards/${id}`)).body
  return {
    period: await data('{"period":"yesterday"}'),
    tooMany: await data(JSON.stringify({ fields: ['a', 'b', 'c', 'd'].map(field) })),
    noValues: await data('{"fields":[{"field":"區域","values":[]}]}'),
    notJson: await data('{"period":'),
    put: await window.jev.api('PUT', `/api/dashboards/${id}`, { ...config, filters: { fields: [field('區域'), field('區域')] } }),
    saved: (await window.jev.api('GET', `/api/dashboards/${id}`)).body.filters,
  }
}
```

留下的狀態：「E2E 銷售總覽」多了一張分店業績的卡片，預設篩選是「區域：北區」。

### S99 收尾

- 目的：停掉自己開的程序，整理結果。
- 帳號／context：不用。
- 前置條件：前面的章節跑完（或確定停在這裡）。

| 編號 | 操作 | 預期結果 | 檢查方式 |
|---|---|---|---|
| S99-1 | `close_page` 自己開的每個分頁 | 使用者的分頁一個都沒動 | 畫面 |
| S99-2 | TaskStop 停掉 S00 開的後端、前端（和 S16 的 8082，如果還在） | `netstat -ano \| grep -E ":(8081\|5181\|8082) .*LISTENING"` 沒有輸出。停不掉的話，記下 PID 給使用者，不要自己 taskkill | API |
| S99-3 | 確認 8080、5180、9222 還在聽 | 跟開始時一樣 | API |
| S99-4 | 結果表補上 PASS／FAIL 的數量、沒跑的步驟 | 結果表完整 | 畫面 |

schema 留著不刪，之後要查資料可以直接看；下次一樣開新的。

## 8. 在哪一節驗到什麼

| 功能 | 章節 |
|---|---|
| 登入、登出、密碼錯誤 | S01 |
| 帳號管理：建立、停用、啟用、重設密碼、切換管理員 | S02 |
| 改自己的密碼 | S03 |
| 上傳 CSV、Excel（選工作表） | S04 |
| 欄位設定：敏感、顯示名稱 | S05 |
| 比率 | S06、S11 |
| 依需求分析、調整、存檔 | S07 |
| 拖拉、縮放、移除卡片、重開、刪除儀表板、異常標註 | S08 |
| 一份儀表板放多個來源 | S09 |
| 合併來源：疊加、查表、對照，預覽 | S10、S11 |
| 刪除來源和使用情況 | S12 |
| 分享給帳號、公開連結、唯讀、分享給我的 | S13 |
| 深淺主題 | S14 |
| 外部 API 只有管理員看得到 | S15 |
| 外部 API 允許清單、DS_ 密鑰綁 host | S16 |
| 登入失敗鎖定 | S17 |
| 更新資料：接在後面、整份覆蓋、Excel 工作表、下游跟著更新 | S18 |
| 儀表板篩選器：期間、分類、照顯示名稱對欄位、沒套用的篩選、存成預設、檢視者臨時改 | S19 |

## 9. 結果範本

放在執行資料夾的 `results.md`：

```markdown
# E2E 結果 <YYYYMMDD-HHMM>

- commit：<短 SHA>
- schema：<jev_e2e_…>
- 後端／前端：8081／5181
- 跑的人：<agent 或 session>

| 步驟 | PASS/FAIL | 截圖檔 | 備註 |
|---|---|---|---|
| S00-1 | PASS | | |
| S01-1 | PASS | S01-01.png | |
| S07-9 | PASS | | 〔JEV〕沒有圓餅圖可以拿掉 |
| S08-6 | FAIL | S08-06.png | dragBy 拖不動，沒有 PUT |
| S09-9 | FAIL | S09-09.png | 已知問題（backlog 2026-10-02）：退貨明細第 11 列被切掉 |

合計：PASS n／FAIL n／BLOCKED n／沒跑 n
```

## 10. 失敗時怎麼回報

每個 FAIL 寫一段，放在結果表下面：

- 步驟編號和那一步的「預期結果」。
- 實際看到的：畫面上的文字原文（中文照抄，不要翻譯或改寫）、API 的狀態碼和 body、截圖檔名。
- 怎麼重現：從哪個狀態開始、做了哪幾步。前面的章節有沒有 FAIL 或跳過。
- 相關的 log：後端背景程序輸出裡同一時間的 ERROR／WARN，`list_console_messages` 的錯誤。
- 判斷：產品的問題、劇本寫錯，還是環境問題（JEV 逾時、port 被占用）。分不出來就寫「不確定」，不要猜。

截圖裡看到的問題，就算資料檢查都過了，一樣記 FAIL。
