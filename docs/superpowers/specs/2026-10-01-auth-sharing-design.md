# JEV 儀表板：登入驗證與唯讀分享（設計）

日期：2026-10-01

前提：[主 spec](2026-09-23-jev-dashboard-design.md)（身分只靠 `X-User-Id` header，第 47、268、282 行）、[多來源 spec](2026-09-27-multi-source-dashboard-design.md)。本文件只寫這次改變的部分。

## 1. 問題

- 身分只靠 `X-User-Id` header。誰都能改這個 header，看到、動到別人的儀表板，連空字串都會被當成合法使用者 `""`（backlog「正式上線前」P1）。
- 儀表板只有建立的人看得到，沒辦法給主管看（backlog「補齊」P1 唯讀分享）。

## 2. 規則

### 2.1 帳號

- 帳號由管理員建立。第一個管理員寫在 `backend/.env`（`ADMIN_USERNAME`、`ADMIN_PASSWORD`），後端啟動時這樣處理：
  - `ADMIN_USERNAME` 沒填：不建立。資料庫裡也沒有任何啟用中的管理員時，寫一行 WARN「沒有管理員帳號：在 backend/.env 設定 ADMIN_USERNAME、ADMIN_PASSWORD」。
  - `ADMIN_USERNAME` 先照下面的帳號規則轉換，不合規則就啟動失敗，訊息寫明原因。
  - 資料庫已經有這個帳號：不看密碼，什麼都不改，寫一行 INFO「管理員帳號「x」已經存在，不會改它的密碼」。這個帳號停用了或不是管理員時，改成寫 WARN。所以管理員自己改過的密碼不會被 `.env` 蓋回去，第一次登入改完密碼後，也可以把 `ADMIN_PASSWORD` 從 `.env` 拿掉。
  - 資料庫沒有這個帳號：檢查 `ADMIN_PASSWORD`。沒填、不合密碼規則，或有 0x21～0x7E 以外的字元（空白、非 ASCII）時，啟動失敗，訊息寫明原因。通過就建立一個啟用中的管理員。
- 帳號名稱：去掉前後空白、轉成小寫後，要符合 `^[a-z0-9][a-z0-9._@-]{1,63}$`。也就是第一個字是英文字母或數字，後面可以有英文字母、數字和 `.` `_` `@` `-`，總長 2～64。登入、分享、建立帳號、OIDC 都先做同樣的轉換。帳號名稱建立後不能改。
- 密碼：至少 8 個字元，最多 72 個位元組（UTF-8，約 24 個中文字；BCrypt 的上限），不能跟帳號名稱一樣。用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()` 存（`{bcrypt}…`）。
- 管理員可以新增帳號（可勾「管理員」）、停用或重新啟用帳號、把帳號設成或取消管理員、設定或重設密碼。不能停用自己、不能取消自己的管理員，也不能從帳號管理重設自己的密碼（自己用「改密碼」）。
- 每個人可以自己改密碼。只用單一登入、沒有密碼的帳號不能改。
- 停用帳號、設成或取消管理員、管理員設定或重設密碼、自己改密碼，都會讓這個帳號所有登入中的工作階段失效，下一個請求回 401，重新登入後才拿到新的身分。
  - 作法：帳號記 `sessions_valid_after`，有上面這些變動時，寫入後端當下的時間（`Instant.now()`，不用資料庫的 `now()`，才跟登入用同一個時鐘）。開始登入的時間（讀帳號之前，帳密和 OIDC 都一樣）放在 Authentication 的 details。每個請求檢查四件事：帳號還在、還啟用、工作階段裡的管理員身分跟資料庫一樣、開始登入的時間晚於 `sessions_valid_after`。有一件不成立，就讓工作階段失效、回 401。比的是開始登入的時間，不是工作階段的建立時間，所以登入時讀帳號、跑 BCrypt 的那段時間裡有變動，這次登入一樣會失效；同一個工作階段的另一個請求同時讓它失效，也不會漏掉。登入時照樣換一個新的工作階段（`sessionFixation().newSession()`）。
  - ponytail：每個請求多查一次帳號（主鍵查詢）。真的變成瓶頸，再把帳號在記憶體裡快取幾秒。
- 不做刪除帳號：資料靠帳號名稱認擁有者，刪掉會留下沒人能管的資料。要讓人不能用，就停用。

### 2.2 登入與工作階段

- 帳密登入用 Spring Security 的 `formLogin`，回傳 JSON，不轉址。輪替 CSRF token、存 SecurityContext，這兩件事 `formLogin` 會自己處理；換新的工作階段見 2.1。
- 帳密登入自己宣告 `DaoAuthenticationProvider`：
  - 查帳號前先照 2.1 轉換帳號名稱。回傳的 `UserDetails` 用資料庫裡的帳號名稱，`Principal.getName()` 才會是小寫的那個。
  - 找不到帳號，或 `password_hash` 是 null（只用單一登入），都丟 `UsernameNotFoundException`，走 Spring 內建的防時間差處理。
  - 停用的檢查從 pre-authentication 移到 post-authentication：密碼對了才會看到「帳號已停用」；密碼錯一律回「帳號或密碼錯誤」，不透露帳號存不存在、有沒有停用。2026-10-02 起有一個例外：同一個帳號密碼連續錯 5 次以後回 429，等於說出這個帳號存在，見[登入鎖定 spec](2026-10-02-login-lockout-design.md) 2.3。
  - `ROLE_ADMIN` 或 `ROLE_USER` 照 `account.admin` 給。
- 工作階段存在記憶體（`HttpSession`）。閒置 30 分鐘登出（Spring 預設）。後端重啟時所有人都要重新登入。
- CSRF：照 Spring Security 6.5 文件的 SPA 作法（`CookieCsrfTokenRepository.withHttpOnlyFalse()` 加 `SpaCsrfTokenRequestHandler`）。前端從 cookie `XSRF-TOKEN` 讀出 token，每個非 GET 請求都放進 `X-XSRF-TOKEN` header，上傳檔案也一樣。
- 錯誤：
  - `/api/**` 沒登入回 401 `{"error":"請先登入"}`，不轉址到登入頁。
  - 沒權限回 403 `{"error":"沒有權限"}`。
  - CSRF token 不對回 403 `{"error":"頁面已過期，請重新整理"}`。
  - 這些錯誤，加上登入失敗和 2.1 帳號檢查擋下來的 401，都由安全設定裡同一個 helper 寫出：Content-Type 是 `application/json;charset=UTF-8`，用 `ObjectMapper` 寫 `{"error": …}`，中文才不會變成問號。帳號檢查查資料庫失敗時，自己回 500 `{"error":"伺服器錯誤"}`，不把例外往外丟。錯誤格式跟現在一樣是 `{"error": "..."}`。
- `server.address` 預設還是 `127.0.0.1`。要對外開，照舊用 `SERVER_ADDRESS`。

### 2.3 單一登入（OIDC）

- 標準 OIDC（authorization code），設定都寫在 `backend/.env`（見第 3 節）。`OIDC_ISSUER_URI` 和 `OIDC_CLIENT_ID` 都有填才啟用，登入頁才出現單一登入按鈕；少填一個就沒有這顆按鈕，後端也不碰 OIDC。
- 只接公司自己的單一租戶 IdP。IdP 上的應用程式要設成只有被指派的人能登入（Entra ID：Assignment required = Yes）。IdP 能登入的每一個人都會自動有帳號，看得到所有公開連結。
- `ClientRegistration`：
  - 用 `ClientRegistrations.fromIssuerLocation(issuer)`，固定 `registrationId("oidc")`、`scope("openid", "profile", "email")`，一律 `requireProofKey(true)`（PKCE）。
  - `OIDC_CLIENT_SECRET` 有填用 `client_secret_basic`，沒填用 `none`。
  - `oauth2Login` 只接 OIDC 的 user service（下面的帳號對應），沒有 `openid` 的 OAuth2 登入不接受。
  - issuer 要用 https；本機測試才用 `http://127.0.0.1`。
- 第一次有人按這顆按鈕時，後端才去讀 issuer 的 discovery 文件，讀到就記住。IdP 掛掉時，帳密登入不受影響，後端也照常啟動。
  - ponytail：IdP 掛掉時按按鈕，會看到 Spring 的錯誤頁，下次再按會重試。真的常發生，再自己接 `OAuth2AuthorizationRequestRedirectFilter` 的失敗處理。
- 帳號對應（包在 `OidcUserService` 外面）：
  1. 用 `(issuer, sub)` 找本機帳號，找到就用那個帳號。
  2. 找不到時，帳號名稱取 `preferred_username`。沒有的話，`email_verified` 是 true 才取 `email`，不然當作沒有帳號名稱。再照 2.1 轉換。
  3. 這個名稱還沒有帳號，而且 `dashboard`、`datasource` 都沒有 `created_by` 是這個名稱的舊資料：自動建立一個一般帳號（沒有密碼），記下 `(issuer, sub)`。
  4. 這個名稱已經有帳號（不是綁這個 `(issuer, sub)`），或已經有同名的舊資料：擋下來，轉到 `username_taken`。用帳號名稱自動接上既有帳號或舊資料，等於讓 IdP 上改得到 `preferred_username` 的人冒用別人（OIDC Core 5.7：只有 `iss`＋`sub` 是穩定的）。舊資料要由管理員建同名的密碼帳號接手（2.5）。
  5. 沒有帳號名稱，或名稱不合 2.1 規則：轉到 `no_username`。帳號停用：轉到 `account_disabled`。
  - 回傳的 `OidcUser` 用本機帳號名稱當 name attribute，`Principal.getName()` 才跟帳密登入一致。authority 照 `account.admin` 給 `ROLE_ADMIN` 或 `ROLE_USER`。
- 登入成功轉回 `/`。失敗轉回 `/?login_error=<代碼>`，前端照代碼顯示訊息（7.1）。
- 開發時，Vite 要把 `/oauth2` 和 `/login/oauth2` 轉給後端，而且要 `changeOrigin: false`，後端算出來的 redirect URI 才會是瀏覽器看到的網址（`http://localhost:5173/login/oauth2/code/oidc`）。`/api` 照舊。
- 在 IdP 上要登記的 redirect URI：`<網站網址>/login/oauth2/code/oidc`。
- 部署在 HTTPS 反向代理後面時，一定要設 `server.forward-headers-strategy: native`，代理也要帶 `X-Forwarded-Proto` 和 `X-Forwarded-Host`（或保留 Host）。只保留 Host 不夠，scheme 會是 http，redirect URI 和 cookie 的 Secure 都會錯。

### 2.4 分享

- 擁有者可以把儀表板分享給指定帳號，也可以打開「公開連結」，兩者可以同時開。
  - 指定帳號：對方登入後在「分享給我的」看得到。
  - 公開連結：任何有帳號的人拿到連結就能看，但不會出現在別人的清單裡。
- 只能分享給已經存在的帳號。用單一登入的人要先登入過一次才會有帳號；還沒登入過的人，可以先用公開連結。
- 連結是 `<網站網址>/#/d/<儀表板 id>`。擁有者、被分享的人、公開連結都用同一個網址。id 是隨機 UUID，猜不到。
  - ponytail：關掉公開連結之後再打開，舊連結又能用。要讓舊連結永久失效，再加一個可以換新的分享 token。
- 不做免登入的公開連結。
- 被分享的人（以下稱「檢視者」）只能看：圖表、異常標註、「依據」那一行都看得到，不能拖拉、縮放、移除卡片，不能刪除，看不到擁有者的資料來源清單和設定（卡片上的來源名稱看得到）。
- 檢視者看到的資料跟擁有者一樣，包括明細表和散佈圖的原始資料列、標成敏感的欄位值。敏感標記只管不送 JEV（主 spec），不是權限。要限定對方能看哪些資料列，是之後的「列層級權限」。
- 資料用擁有者的身分載入：`/data`、`/api/jev/highlight` 對檢視者都改用擁有者的帳號名稱去讀資料來源。檢視者打開時，擁有者的外部 API 一樣會被抓，異常標註一樣用這個系統的 JEV key。
- 擁有者帳號停用後，他分享出去的儀表板對檢視者一律當作不存在（404），也不會出現在「分享給我的」。
- 擁有者刪除儀表板時，分享紀錄一起刪掉。
- 撤銷：擁有者把對方從清單移掉或關掉公開連結，對方下一次打開或重新整理就回 404。已經開著的畫面不會自己消失。

### 2.5 既有資料

- `created_by` 照舊存帳號名稱文字，不加 foreign key。管理員建一個跟舊 user ID 同名的帳號，這個帳號就接手原本的資料，例如 `demo-user`（11 個來源、4 份儀表板）。
- 舊資料只能由同名的密碼帳號接手。用單一登入的人接不到（2.3 第 4 點），要等「綁定單一登入」（第 10 節）。
- 舊 ID 都是小寫，轉小寫的規則不影響。`created_by = ''` 的舊資料沒人能接手（空字串不是合法帳號）。
- migration 不建立任何帳號。

### 2.6 外部 API 來源

- 只有管理員能註冊外部 API 來源（`POST /api/datasources/api`），其他帳號回 403「沒有權限」，「新增資料來源」也不顯示「外部 API」分頁。原因是 header 可以寫 `${DS_任何名稱}`，誰能註冊，誰就能借用伺服器上的密鑰（第 10 節）。
- 已經註冊好的外部 API 來源不受影響：擁有者一樣能載入、合併、刪除，也能改顯示名稱、敏感欄位和比率。
- 管理員註冊的來源只有他自己能用。資料來源不共用，要給別人看，就分享用到它的儀表板（2.4）。

## 3. 設定（`backend/.env`）

| 變數 | 用途 | 沒填時 |
|---|---|---|
| `ADMIN_USERNAME` | 第一個管理員的帳號。用一個專用的名稱（例如 `admin`），不要用自己單一登入的帳號名稱 | 不建立管理員 |
| `ADMIN_PASSWORD` | 第一個管理員的初始密碼 | 要建立帳號時，啟動失敗 |
| `OIDC_ISSUER_URI` | IdP 的 issuer，例如 `https://login.microsoftonline.com/<tenant>/v2.0` | 不啟用 OIDC |
| `OIDC_CLIENT_ID` | client id | 不啟用 OIDC |
| `OIDC_CLIENT_SECRET` | client secret | 用 `none`（public client，靠 PKCE） |
| `OIDC_LABEL` | 按鈕上「用」和「登入」中間的字 | 「公司帳號」（預設值寫在 `application.yml`） |

- `backend/.env` 是照 `.properties` 規則、用 ISO-8859-1 讀的：
  - 只能寫英數字和符號。`OIDC_LABEL` 要用中文時，寫成 `\uXXXX`，或直接改 `application.yml` 的預設值。
  - `\` 是跳脫字元，`${…}` 會被當成變數替換。管理員密碼不要用 `\` 和 `${`；client secret 裡有 `\` 時寫成 `\\`。
- `application.yml` 的 `jev:` 區塊加 `auth`，`server:` 區塊加 `tomcat.max-swallow-size`。要併進既有的區塊，不要另起一個同名的頂層 key（YAML 不允許重複 key，後端會起不來）：

  ```yaml
  server:
    tomcat:
      max-swallow-size: 50MB   # 沒登入時上傳大檔，401 才回得到前端，不會變成連線被切斷
  jev:
    auth:
      admin-username: ${ADMIN_USERNAME:}
      admin-password: ${ADMIN_PASSWORD:}
      oidc:
        issuer-uri: ${OIDC_ISSUER_URI:}
        client-id: ${OIDC_CLIENT_ID:}
        client-secret: ${OIDC_CLIENT_SECRET:}
        label: ${OIDC_LABEL:公司帳號}
  ```

- 設定放在新的 `AuthProperties`（`@ConfigurationProperties("jev.auth")`），不加進 `JevProperties`（9 個測試直接 new 它）。是否啟用 OIDC 用 `StringUtils.hasText` 判斷，不用 `@ConditionalOnProperty`（`${X:}` 的空字串也算有設）。
- `application-test.yml` 固定 `jev.auth.admin-username`、`admin-password` 的值，並設 `jev.auth.oidc.issuer-uri: ""`，這樣開發者自己的 `.env` 裡有 OIDC 設定時，測試也不會跟著開。

## 4. API

### 4.1 登入

- `GET /api/auth/options`（不用登入）

  ```json
  { "oidc": { "label": "公司帳號" } }
  ```

  沒啟用 OIDC 時是 `{ "oidc": null }`。

- `POST /api/auth/login`（`application/x-www-form-urlencoded`：`username`、`password`）
  - 成功回 204，同一個回應會帶新的 session cookie 和新的 `XSRF-TOKEN` cookie。
  - 帳密錯誤、沒有這個帳號、帳號沒有密碼、帳號停用但密碼錯：401「帳號或密碼錯誤」。
  - 帳號停用而且密碼正確：401「帳號已停用，請聯絡管理員」。
- `POST /api/auth/logout` → 204。
- `GET /api/auth/me`

  ```json
  { "username": "demo-user", "admin": true, "password": true }
  ```

  - 三個值都讀資料庫。`password` 是這個帳號有沒有密碼，前端靠它決定要不要顯示「改密碼」，以及重新登入時要顯示哪一種（7.2）。
  - 沒登入回 401「請先登入」。這個回應也會帶 `XSRF-TOKEN` cookie，所以開機時先打這支，登入表單送出時就有 token。

- `POST /api/auth/password`

  ```json
  { "current": "舊密碼", "password": "新密碼" }
  ```

  - 成功回 204，這個帳號所有工作階段都失效，包括目前這個。
  - 400「目前的密碼不對」、400「這個帳號用單一登入，沒有密碼可以改」，以及 4.4 的密碼規則訊息。

### 4.2 帳號管理（管理員；不是管理員回 403「沒有權限」）

帳號物件：

```json
{ "username": "alice", "admin": false, "enabled": true, "password": true, "sso": false, "createdAt": "2026-10-01T08:00:00Z" }
```

- `GET /api/admin/accounts` → 帳號物件陣列，依帳號名稱排序。
- `POST /api/admin/accounts`

  ```json
  { "username": "alice", "password": "初始密碼", "admin": false }
  ```

  - 成功回 201 和帳號物件。
  - 409「帳號「alice」已經存在」，以及 4.4 的帳號、密碼規則訊息。

- `PATCH /api/admin/accounts/{username}`：`{ "enabled": false }` 或 `{ "admin": true }`，兩個欄位都可以省略。
  - 成功回帳號物件。`enabled` 或 `admin` 真的有變時，這個帳號所有工作階段失效。
  - 400「不能停用自己的帳號」、400「不能取消自己的管理員」、404「找不到帳號」。
- `PUT /api/admin/accounts/{username}/password`：`{ "password": "新密碼" }`
  - 成功回 204，這個帳號所有工作階段失效。
  - 400「自己的密碼請用「改密碼」」、404「找不到帳號」，以及 4.4 的密碼規則訊息。
  - 用單一登入建立的帳號也能設密碼，設了之後兩種方式都能登入。

### 4.3 分享

- `GET /api/dashboards/{id}/share`（只有擁有者）

  ```json
  { "publicLink": false, "users": ["alice", "bob"] }
  ```

  不是擁有者回 404「找不到儀表板」。

- `PUT /api/dashboards/{id}/share`（只有擁有者）：body 跟上面同一個形狀，整份取代，回傳存好的結果。
  - `users` 先照 2.1 轉換、去重複，再依名稱排序。
  - 400「找不到帳號「x」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結」。停用的帳號可以加，對方重新啟用後就看得到。
  - 400「不用分享給自己」。
- `GET /api/dashboards/shared`：分享給我的（只算指定帳號，不算公開連結）。擁有者停用的不列。依 `updatedAt` 由新到舊。

  ```json
  [{ "id": "…", "name": "各區營收", "owner": "demo-user", "updatedAt": "…" }]
  ```

### 4.4 共用的錯誤訊息

- 帳號規則：400「帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64」。
- 密碼規則：400「密碼至少要 8 個字元」、400「密碼太長，最多 72 個位元組（約 24 個中文字）」、400「密碼不能跟帳號一樣」。

### 4.5 既有 API 的改變

- 所有 API 都拿掉 `X-User-Id`，身分改成登入的帳號名稱（`Principal.getName()`）。service、repository 的 `String userId` 參數不變。
- `GET /api/dashboards/{id}`、`GET /api/dashboards/{id}/data`、`GET /api/jev/highlight/{id}`：擁有者、被指定的帳號，以及（公開連結打開時）任何登入的人都能用；其他人照舊 404「找不到儀表板」。
  - 回傳內容不變。`GET /{id}` 本來就有 `createdBy`，前端拿它跟 `me.username` 比，判斷是不是檢視者。
  - `/data`、`/highlight` 用擁有者的帳號名稱載入資料。
  - 檢視者呼叫 `/data` 時，載入資料如果丟出錯誤，狀態碼不變，訊息一律改成「資料來源載入失敗，請聯絡儀表板擁有者」，不讓「環境變數 DS_X 未設定」這類擁有者那邊的細節露給檢視者。單張卡片的 `SOURCE_FAILED`、`SOURCE_DELETED` 照舊回傳，前端顯示時改字（7.8）。
- 其他所有 API（修改、刪除、加圖、預覽、資料來源、分析、欄位對應）照舊只認擁有者，檢視者拿到的回應跟現在的「別人」一樣（404）。
- `GET /api/dashboards` 照舊只列自己的。分享給我的另外走 4.3，「加到既有儀表板」的選單才不會出現別人的儀表板。

## 5. 資料庫（Flyway `V6__auth_sharing.sql`）

```sql
create table account (
    username             text primary key,
    password_hash        text,
    admin                boolean     not null default false,
    enabled              boolean     not null default true,
    oidc_issuer          text,
    oidc_subject         text,
    sessions_valid_after timestamptz,
    created_at           timestamptz not null default now(),
    unique (oidc_issuer, oidc_subject)
);

alter table dashboard add column public_link boolean not null default false;

create table dashboard_share (
    dashboard_id uuid not null references dashboard (id) on delete cascade,
    username     text not null references account (username),
    primary key (dashboard_id, username)
);

create index dashboard_share_username on dashboard_share (username);
```

- `password_hash` 是 null 代表只能用單一登入。`unique (oidc_issuer, oidc_subject)` 在兩個都是 null 時不互相衝突（Postgres 的 null 不算相等），密碼帳號可以有很多個。
- 正式資料庫第一次跑 V6 之前，照慣例先 `pg_dump` 備份。

## 6. 後端其他改動

### 6.1 套件

- 加 `spring-boot-starter-security`、`spring-boot-starter-oauth2-client`，測試加 `spring-security-test`（Spring Boot 3.5.16 管的是 Spring Security 6.5.11）。這次需要新套件，以前 plan 裡「不新增套件」的規則不適用。

### 6.2 安全設定

- 一個 `SecurityFilterChain`：
  - `/api/auth/options` 和 `ERROR`、`FORWARD` dispatch 不用登入。
  - `/api/admin/**` 要 `ROLE_ADMIN`。
  - `POST /api/datasources/api` 要 `ROLE_ADMIN`（2.6）。
  - 其他 `/api/**` 要登入。
  - 其餘路徑不擋（之後放前端靜態檔用）。
- `formLogin`：`loginProcessingUrl("/api/auth/login")`，成功回 204、失敗回 401 JSON（2.2）。`logout`：`/api/auth/logout` 回 204。
- `sessionManagement`：`sessionFixation().newSession()`（2.1）。
- `exceptionHandling` 照 2.2 寫 401、403 JSON。設了 entry point 之後，Spring 就不會自己產生 `/login` 頁。
- `requestCache` 用 `NullRequestCache`，沒登入的請求不會因此建立工作階段。
- 2.1 的帳號檢查：一個繼承 `OncePerRequestFilter` 的 filter（預設跳過 ERROR dispatch）。不要把它註冊成 Spring bean，不然 Boot 會把它再掛成一般的 servlet filter，跑在安全設定前面。在 `SecurityConfig` 裡 new 出來，用 `addFilterAfter(…, AnonymousAuthenticationFilter.class)` 放進去，只檢查已登入的請求。不通過時，自己讓工作階段失效、清掉 SecurityContext、寫 401「請先登入」。
- OIDC 啟用時才呼叫 `oauth2Login`。`ClientRegistrationRepository` 自己寫一個 lazy 的（2.3），成功轉 `/`、失敗轉 `/?login_error=…`。
- 權限只靠 URL 規則控管，不用 `@PreAuthorize`。不然 `ApiExceptionHandler` 的 catch-all 會把 `AccessDeniedException` 變成 500。

### 6.3 分享

- `DashboardService` 加一個唯讀的存取檢查。照 4.5 的規則判斷，回傳 config，config 裡的 `createdBy` 就是擁有者。`get(userId, id)` 照舊只認擁有者，`update`、`append` 還在用它。
- `DashboardController.get`、`data` 和 `JevController.highlight` 改成：先用唯讀檢查拿到 config，再用 `config.createdBy()` 載入資料、讀敏感欄位。
- `DashboardDataService.recent` 的 key 照舊是「載入身分:儀表板 id」。檢視者的 `/data` 用擁有者的 key 存，緊接著的 `/highlight` 也用擁有者的 key 取，兩邊共用，資料本來就一樣。預覽照舊用呼叫者自己的 key，蓋不到擁有者那份。

### 6.4 啟動時建立管理員

- 一個 `ApplicationRunner` 照 2.1 處理。

### 6.5 順手處理

- 刪掉 `ApiExceptionHandlerTest` 裡的測試用 controller（`/api/test/echo-user`，backlog「測試補強」第 139 行）。它唯一的用途是測缺 header，這個情境已經不存在。
- `application.yml` 第 1 行和 `ApplicationTest` 裡講 `X-User-Id` 的註解改掉。

### 6.6 不用改的地方

- 資料來源的 repository 和 service、`MergeEngine`、`HighlightService` 的快取（key 是內容，不是使用者）、所有 transformer。
- 純單元測試（`DashboardServiceTest`、`DashboardDataServiceTest`、`MergedDatasourceServiceTest` 等）直接傳 `String userId`，不用改。

## 7. 畫面

新元件放在 `frontend/src/modules/auth/`。`App.vue` 只多掛幾個元件，好跟同時在做的 `feat/industrial-theme`（導覽列改到頂部）合併。

### 7.1 開機與登入頁

- 開機順序：
  1. 網址沒有 hash，而 `sessionStorage` 有 7.1 存的 `#/d/<id>`：補回網址，再刪掉那筆。不管等一下登入成功還是失敗，都只用一次。
  2. 打 `GET /api/auth/me`。等它回來之前，畫面中央只有一個 `v-progress-circular`，App 的其他元件都不掛，`refresh()` 也等 `me` 成功才打。
  3. 回 401：整頁顯示登入頁。回其他錯誤（例如「無法連線到伺服器」）：顯示同一張卡片，內容是錯誤訊息加一顆「重試」按鈕，不顯示登入表單。
  4. 成功：照原本的流程載入。網址有 `#/d/<id>` 就切到「儀表板」並打開那份。
- 登入頁版面：畫面正中間一張卡片，上面是 logo 和「JEV 儀表板」，接著是「帳號」「密碼」兩個輸入框和「登入」按鈕（primary，按 Enter 也能送出）。卡片右上角放主題切換鈕。
- 有 OIDC 時，下面多一條分隔線和一顆單一登入按鈕（`mdi-login`，`variant="tonal"`）。
  - 按鈕文字是「用」＋ label ＋「登入」。label 開頭或結尾是英數字時，那一側加一個空格，例如「用公司帳號登入」「用 Microsoft 登入」。
  - 點下去整頁導到 `/oauth2/authorization/oidc`。導過去之前，先把網址的 `#/d/<id>` 存進 `sessionStorage`，登入回來再補回去（開機第 1 步），分享連結才不會掉。
- 錯誤用卡片裡的 `v-alert type="error"`：
  - 帳密登入失敗：直接顯示後端的訊息。
  - `?login_error=` 的代碼：
    - `account_disabled`：「帳號已停用，請聯絡管理員」
    - `username_taken`：「這個帳號名稱已經有人用，不能再用單一登入建立，請聯絡管理員」
    - `no_username`：「單一登入沒有提供帳號名稱，請聯絡管理員」
    - 其他：「單一登入失敗，請再試一次」
  - 顯示之後用 `history.replaceState` 把 `login_error` 拿掉，hash 留著。
- 登入成功後打 `me`，接著照開機第 4 步。

### 7.2 登入逾時

- 使用中只要任何 API 回 401（`/api/auth/login` 和 `/api/auth/me` 除外），就跳出一個點外面也關不掉的對話框。
  - 對話框用 App 另外一個 ref 控制，不動 `me`，畫面上的元件都不卸載，「製圖」頁的預覽留著。
  - 多個 401 同時回來，只開一次。
- 內容依開機時拿到的 `me.password`：
  - 有密碼：標題「請重新登入」，內文「登入已經逾時，請重新登入。畫面上的東西會留著。」。下面是跟 7.1 一樣的帳密表單，帳號欄預先填好目前的帳號，焦點放在密碼欄。OIDC 啟用時，表單下面也放單一登入按鈕。
  - 沒有密碼：內文「你用單一登入，請按下面的按鈕重新登入。「製圖」頁還沒存的預覽會不見。」，加上單一登入按鈕（一樣先存 `#/d/<id>`）。
  - 對話框左下角一律放一顆文字按鈕「重新整理」（`location.reload()`），停用的帳號才有出路。
- 登入成功後打 `me`，拿 `me.username` 跟原本的比（不比輸入框的字）：
  - 同一個帳號：關掉對話框，重新抓儀表板清單和分享給我的，重新打開目前那份。剛才失敗的動作不會自動重送。
  - 換了另一個帳號：拿掉 hash、刪掉 `sessionStorage` 存的那筆，再 `location.reload()`，不留前一個人的畫面。

### 7.3 使用者選單

- 帳號鈕放在主題切換鈕旁邊，外觀跟主題鈕一樣是圖示（`mdi-account-circle-outline`）加文字「帳號」，`title` 是帳號名稱。原本把主題鈕推到最後的 margin 移到帳號鈕：左側導覽列的版本放在主題鈕上面；頂部導覽列的版本（`feat/industrial-theme`）放在主題鈕左邊，600px 以下跟主題鈕一樣只留圖示。
- 點開是 `v-menu`：
  - 第一行是帳號名稱，管理員後面加「· 管理員」，不能點。
  - 「改密碼」（帳號有密碼才出現）
  - 「帳號管理」（管理員才出現）
  - 「登出」：登出後拿掉 hash、刪掉 `sessionStorage` 存的那筆，再 `location.reload()`。

### 7.4 改密碼（對話框，max-width 420）

- 標題「改密碼」。三個密碼框：「目前的密碼」「新密碼」「再輸入一次新密碼」，新密碼的提示是「至少 8 個字元」。錯誤顯示在標題下面的 `v-alert`。
- 兩次新密碼不一樣時，不送出，顯示「兩次輸入的新密碼不一樣」。
- 按鈕「取消」「更新密碼」，在最後一個欄位按 Enter 也會送出。
- 成功後對話框變成 `persistent`，內容換成「密碼已更新，請用新密碼重新登入」，只剩一顆「重新登入」按鈕，按了就 `location.reload()`。

### 7.5 帳號管理（對話框，max-width 720）

- 標題「帳號管理」。訊息顯示在標題下面：錯誤用 `v-alert type="error"`，成功用 `v-alert type="success"`（例如「已重設「x」的密碼」）。
- 上面一列新增：「帳號」「初始密碼」、勾選框「管理員」、按鈕「新增帳號」，按 Enter 等於按「新增帳號」。新增成功就清空這一列。
- 下面是表格，欄位：帳號、身分（管理員／一般）、登入方式（密碼／單一登入／兩者）、狀態（啟用／停用），最後一欄是動作：
  - 「重設密碼」：在這一列下面展開一個密碼框，加「儲存」「取消」，按 Enter 等於「儲存」。沒有密碼的帳號，按鈕文字是「設定密碼」。成功後收起那一列。
  - 「停用」或「啟用」，不另外確認（停用了可以再啟用）。
  - 「設成管理員」或「取消管理員」。
  - 自己那一列帳號後面加「（你）」，不顯示停用、取消管理員、重設密碼。

### 7.6 分享（對話框，max-width 480）

- 儀表板頁的標題列，「新增儀表板」左邊多一顆「分享」按鈕（`mdi-share-variant-outline`，`variant="tonal"`）。只有擁有者打開自己的儀表板時才出現。
- 標題「分享「名稱」」。
- 第一段「公開連結」：一個開關，旁邊的說明是「有帳號的人拿到連結就能看」。
- 第二段「分享給」：
  - 輸入框（placeholder「輸入對方的帳號」）加「加入」按鈕。在輸入框按 Enter 等於「加入」，加入後焦點留在輸入框。輸入框下面的提示是「對方的帳號在他帳號選單的第一行」。
  - 已加入的帳號列成 chip，每個可以按 ✕ 移掉（`close-label="移除「帳號」"`）。沒有人時顯示「還沒有分享給任何人」。
- 連結：一個唯讀輸入框顯示 `<網站網址>/#/d/<id>`，旁邊一顆「複製連結」按鈕（`mdi-link-variant`）。
  - 複製成功後，按鈕文字變成「已複製」2 秒。
  - 沒有 `navigator.clipboard`（例如用 http 開在區網上），或 `writeText` 失敗時，選取輸入框裡的文字，下面顯示「瀏覽器不讓網頁直接複製，請按 Ctrl+C」。
- 提醒文字（`cc-desc`）：「對方看到的資料跟你一樣，包括明細表和敏感欄位。」
- 按鈕「取消」「儲存」。儲存成功就關掉對話框。儲存失敗時，錯誤顯示在對話框裡（例如「找不到帳號「x」…」），對話框不關。

### 7.7 分享給我的

- 儀表板頁左側「我的儀表板 · N」下面多一段「分享給我的 · N」。項目格式一樣，副標是「來自 帳號名稱」，沒有刪除按鈕。沒有任何分享時，這一段整個不顯示。
- 右邊的空白狀態：自己的或分享給我的，只要有一份，就顯示「從左邊選一份儀表板」，不顯示製圖步驟。

### 7.8 唯讀檢視

- 打開的儀表板 `createdBy` 不是自己時：
  - 標題列不顯示「分享」。
  - 說明文字改成「「擁有者」分享給你的儀表板，只能檢視。」。
  - 卡片不能拖拉、不能縮放，沒有 ✕，標題列不顯示移動游標。
  - 沒有圖表時顯示「這份儀表板沒有圖表。」。
  - `SOURCE_DELETED` 的卡片顯示「資料來源已刪除」，不提 ✕。
  - `SOURCE_FAILED` 的卡片顯示「資料來源「X」載入失敗，請聯絡儀表板擁有者」。
- 打開失敗回 404 時，清掉目前的畫面，顯示「找不到這份儀表板，可能已經刪除或取消分享」。網址的 hash 留著，重新整理會再試一次。

### 7.9 網址

- 只認 `^#/d/[0-9a-f-]{36}$`。
- 打開任何一份儀表板時，用 `history.replaceState` 把網址改成 `#/d/<id>`，網址列隨時就是可以分享的連結。只有刪除目前那份時才拿掉。
- 監聽 `hashchange`：
  - 變成合法的 `#/d/<id>`：把畫面切到「儀表板」，打開那份。
  - 格式不對的 `#/d/…`（例如從聊天軟體貼上時被截斷）：直接顯示 7.8 的「找不到這份儀表板…」，不打 API。
  - 變成空的或其他 hash：什麼都不做。
- 切到「資料」「製圖」時 hash 不動。

### 7.10 `api.js`

- 拿掉 `X-User-Id` 和 `VITE_USER_ID`。
- 非 GET 請求從 `document.cookie` 讀 `XSRF-TOKEN`，放進 `X-XSRF-TOKEN` header。讀之前先判斷 `typeof document`，vitest 的 node 環境才不會出錯。
- `URLSearchParams` 跟 `FormData` 一樣原樣送出，不設 `Content-Type`。
- 加一個 `onUnauthorized(handler)`。`request()` 收到 401 時（`/api/auth/login`、`/api/auth/me` 除外），先呼叫 handler，再丟出錯誤，訊息是「登入逾時，重新登入後請再操作一次」。這樣重新登入後，還留在畫面上的錯誤訊息才說得通。`/api/auth/login` 的 401 照舊丟後端的訊息。

### 7.11 鍵盤與自動填入

- 對話框打開時，焦點放在第一個輸入框（7.2 有預填帳號時放在密碼欄）。
- 送出中的按鈕用 `:loading`，同時停用，避免連按兩次。
- `autocomplete`：
  - 7.1、7.2 的帳號 `username`，密碼 `current-password`。
  - 7.4 目前的密碼 `current-password`，兩個新密碼 `new-password`。
  - 7.5 的帳號 `off`，初始密碼和重設密碼 `new-password`，避免瀏覽器把管理員自己的帳密自動填進去。這兩個密碼框旁邊有顯示／隱藏的眼睛圖示（`mdi-eye-outline`／`mdi-eye-off-outline`，aria-label「顯示密碼」／「隱藏密碼」），管理員才看得出有沒有打錯。

## 8. 測試

### 8.1 後端

- 測試基礎：
  - `TestData` 加 `public static RequestPostProcessor as(String username)`：`user(username)` 加上 `csrf()`。用 sed 把所有 `.header("X-User-Id", x)` 換成 `.with(TestData.as(x))`。這是唯一大量改既有測試的地方。
  - `TestData.clean` 改成一句 `truncate dashboard_share, account, dashboard, datasource_rows, datasource`，再插入 u1、u2、u3 三個啟用中的一般帳號。`password_hash` 用一個寫死的 `{bcrypt}` 測試密碼雜湊，不要每次 clean 都重算。
  - `ApiExceptionHandlerTest` 加 `@BeforeEach` 呼叫 `TestData.clean`。
- 登入：
  - 帳密正確回 204，之後 `me` 拿得到帳號，`admin`、`password` 讀的是資料庫。
  - 密碼錯、沒有這個帳號、帳號沒密碼、停用的帳號配錯密碼，都回 401「帳號或密碼錯誤」。停用的帳號配對的密碼，回「帳號已停用，請聯絡管理員」。
  - 帳號名稱大小寫和前後空白不影響，登入後 `me.username` 是小寫。
  - 登出之後 401。
  - 沒登入打 `/api/dashboards` 回 401 `{"error":"請先登入"}`，中文沒有變成問號。
  - 沒帶 CSRF 的 POST 回 403「頁面已過期，請重新整理」。
- 工作階段失效：這組不用 `TestData.as`，因為它每次都會建一個新的 session。先用 `formLogin("/api/auth/login").user(..).password(..)` 真的登入，從結果拿 `MockHttpSession`，之後每個請求都帶 `.session(session)`。停用、設成或取消管理員、管理員重設密碼、自己改密碼之後，用同一個 session 打 `/api/auth/me` 要回 401。取消管理員之後，舊 session 打 `/api/admin/accounts` 也要回 401。
- 帳號管理：
  - 一般帳號打 `/api/admin/**` 回 403。
  - 新增、重複（409）、帳號規則（含 `..`、開頭是符號）、三種密碼規則。
  - 停用自己、取消自己的管理員、從帳號管理重設自己的密碼，都回 400。
  - 重設密碼後可以用新密碼登入。
- 管理員建立：直接 new 這個 runner（接測試的 repository 和 encoder）呼叫 `run()`：
  - 沒有帳號時建立。
  - 已經有了就不動，原本的密碼還能登入，密碼沒填也不會失敗。
  - 帳號名稱不合規則、密碼不合規則、密碼有空白或非 ASCII，都用 `assertThrows` 驗證會失敗。
- OIDC：
  - 預設 context 裡，`options` 回 `oidc: null`。
  - 另開一個 context，接 `FakeOidcServer`（照 `FakeJevServer` 的寫法：JDK `HttpServer`，用 nimbus 簽 RS256 的 id_token）。discovery 只給 issuer（跟設定完全一樣）、`authorization_endpoint`、`token_endpoint`、`jwks_uri`，不給 `userinfo_endpoint`。流程：
    1. `GET /oauth2/authorization/oidc`，拿回 302 和 session。
    2. 從 Location 取 `state` 和 `nonce`，把 nonce（原樣）和要給的 claims 登記到 `FakeOidcServer` 的一個 code。
    3. 用同一個 session `GET /login/oauth2/code/oidc?code=…&state=…`，預期 302 到 `/`。
    4. 再用同一個 session 打 `me`，拿到自動建立的帳號。
    5. 用同一組 `(iss, sub)` 再跑一次，還是同一個帳號，不會另外建立。
  - 擋下來的情況：同名的密碼帳號 → `username_taken`；同名的舊資料（`created_by`）→ `username_taken`；停用的帳號 → `account_disabled`；只有 email 而且 `email_verified` 不是 true → `no_username`。
  - Location 裡的 authorize URL 有 `code_challenge`（PKCE）。
- 分享（照 `crudIsScopedToTheOwner` 的寫法，跟「沒分享回 404」的測試放在一起）：
  - u1 分享給 u2 後，u2 打 `GET /{id}`、`/data`、`/highlight` 都拿得到 u1 的資料，不是「找不到資料源」。
  - u2 打 PUT、DELETE、`POST /{id}/charts`、`GET`／`PUT /{id}/share` 都回 404。
  - u3 沒被分享，回 404。打開公開連結之後 u3 看得到，但不在 u3 的 `shared` 裡。
  - 撤銷後回 404。u1 停用後，u2 回 404，`shared` 也不列。
  - 分享給不存在的帳號、分享給自己，都回 400。
  - 刪除儀表板時，分享紀錄一起刪。
  - 檢視者的 `/data` 載入失敗時，訊息換成「資料來源載入失敗，請聯絡儀表板擁有者」。
- 既有資料：先寫一筆 `created_by = 'u9'` 的儀表板，再建立 u9 帳號，u9 登入後就看得到。
- `ApiExceptionHandlerTest` 的 413 測試（真的 Tomcat）：
  1. 先 `GET /api/auth/me` 拿 `XSRF-TOKEN` cookie。
  2. 帶這個 cookie 和 `X-XSRF-TOKEN` header POST `/api/auth/login`（u1 加測試密碼）。
  3. 從回應取 `JSESSIONID`，以及最後一個 `XSRF-TOKEN`（登入的回應會先清掉舊的、再設新的，所以有兩個）。
  4. 上傳時帶這兩個 cookie 和 `X-XSRF-TOKEN` header。
- 全部跑過：`./mvnw test`（用這個 branch 自己的測試 schema）。

### 8.2 前端

- `api.test.js`：
  - 用 `vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=t' })` 測：非 GET 會帶 `X-XSRF-TOKEN`，GET 不帶。
  - 不再有 `X-User-Id`。
  - 401 會呼叫 `onUnauthorized`，錯誤訊息是「登入逾時…」；`/api/auth/login`、`/api/auth/me` 不會。
  - `URLSearchParams` 不設 `Content-Type`，送出的 body 就是原本那個物件。
- 登入頁：送出時呼叫 `login`，失敗顯示訊息；有 OIDC 才有按鈕，按鈕文字的空格規則；`?login_error=` 對到正確的訊息，而且拿掉參數後 hash 還在。
- `App.test.js`：
  - 所有既有測試先 mock `me` 成已登入。
  - `me` 還沒回來時只有 spinner；回 401 時只顯示登入頁；回其他錯誤時顯示「重試」。
  - 「分享給我的」清單；只有分享給我的時，空白狀態是「從左邊選一份儀表板」。
  - 檢視者看不到「分享」和刪除。
  - `#/d/<id>` 開機就打開那份；打開失敗回 404 時 hash 還在；格式不對的 hash 不打 API。
  - 401 跳出重新登入對話框，同時回來兩個 401 只開一次；沒有密碼的帳號只看到單一登入按鈕。
- `DashboardView.test.js`：`readonly` 時不能拖拉、不能縮放、沒有 ✕、`saveLayout` 不送 PUT。
- `ChartCard.test.js`：不能移除時，`SOURCE_DELETED`、`SOURCE_FAILED` 的字不提 ✕、改成請聯絡擁有者。
- 分享對話框（加入、移除、儲存失敗不關、複製失敗的提示）、帳號管理（自己那一列沒有三個按鈕、沒有密碼的帳號顯示「設定密碼」）、改密碼（兩次不一樣不送出、成功後只剩「重新登入」）：主要流程各一個測試。
- `npm test` 全過，`npm run build` 成功。

### 8.3 瀏覽器（主 session 自己做）

用新的 E2E schema（`jev_dashboard_e2e_auth`）、`ADMIN_USERNAME=e2e-admin`，前端用 5180。

- 每個帳號用 Chrome MCP 的 `new_page` 開一個自己的 `isolatedContext`（admin、owner、viewer、other、sso 各一個），之後一直用同一個 context。同一個瀏覽器 context 的 cookie 是共用的，混用會讓測試假通過。
- 每一步先用 `evaluate_script` 打 `/api/auth/me`，確認現在是誰。
- 每一步截圖，主 session 自己看過才算過。

1. 打開網站：先看到 spinner，接著只看到登入頁，沒有 OIDC 按鈕，主題切換鈕可以用。打錯密碼看到「帳號或密碼錯誤」。
2. 用 e2e-admin 登入，在帳號管理新增 e2e-owner、e2e-viewer、e2e-other。試一次帳號重複和密碼太短的錯誤訊息。對 e2e-other 按「重設密碼」、「設成管理員」再「取消管理員」。自己那一列有「（你）」，沒有停用、取消管理員、重設密碼。
3. e2e-owner 上傳 `docs/superpowers/e2e/compare-datasource/sales.csv`，分析後存成儀表板。
4. e2e-owner 分享給 e2e-viewer，按「複製連結」，看到「已複製」。
5. e2e-viewer：
   - 先不登入，直接貼上連結，看到登入頁。用密碼登入後，直接在那份儀表板上。
   - 圖表和異常標註正常，看得到「依據」。卡片拖不動、沒有 ✕，看不到「分享」和刪除。
   - 「分享給我的」有這份。「資料」頁沒有 e2e-owner 的來源，「製圖」的「加到既有儀表板」選單沒有這份。
6. e2e-other 打開連結，看到「找不到這份儀表板…」，網址的 hash 還在。e2e-owner 打開公開連結後，e2e-other 重新整理就看得到，但他的「分享給我的」裡沒有這份。
7. e2e-owner 把 e2e-viewer 移掉，e2e-viewer 重新整理看到 404 訊息。
8. e2e-admin 停用 e2e-other，e2e-other 的下一個動作跳出重新登入，登不進去，按「重新整理」可以離開。
9. e2e-viewer 改密碼後只剩「重新登入」，舊密碼不行、新密碼可以。
10. 登入逾時：e2e-owner 在「製圖」頁有一份預覽時重啟後端（工作階段在記憶體），按「調整」，跳出「請重新登入」。登入後預覽和需求紀錄都還在，再按一次「調整」會成功（順便驗證 CSRF token 換了之後還能送）。
11. 舊資料：先用 psql 在 E2E schema 寫一筆 `created_by='e2e-legacy'` 的儀表板。e2e-admin 建立 e2e-legacy 帳號，登入後看得到這份。
12. OIDC：
    - 本機模擬 server 用 `oauth2-mock-server`，裝在 scratchpad，不進 repo，綁 127.0.0.1。用一支小腳本啟動：`issuer.url` 設成跟 `OIDC_ISSUER_URI` 完全一樣的 `http://127.0.0.1:<port>`；`beforeTokenSigning` 和 `beforeUserinfo` 都補上同一組 `sub`、`preferred_username`、`email`、`email_verified: true`。
    - 後端加上四個 OIDC 設定後重啟。登入頁出現「用公司帳號登入」。
    - 在 sso context 從 `#/d/<id>` 連結進來（e2e-owner 已打開公開連結），按按鈕，回來就登入了，帳號自動建立，而且還在那份儀表板上。選單沒有「改密碼」。
    - e2e-admin 先建一個跟 mock 另一個 `preferred_username` 同名的密碼帳號，換那個使用者重啟 mock，再按按鈕，看到 `username_taken` 的訊息。
13. 深色、淺色主題下，登入頁和四個對話框（重新登入、改密碼、帳號管理、分享）都看一次。

## 9. 文件

- README：
  - `.env` 表格補第 3 節的變數，加上 `.env` 的字元限制。
  - 拿掉 `X-User-Id`、`VITE_USER_ID` 的說明。
  - 加「登入與帳號」一節：
    - 第一次啟動怎麼設管理員：`ADMIN_USERNAME` 用專用的名稱，第一次登入改完密碼後可以把 `ADMIN_PASSWORD` 拿掉。
    - 要讓單一登入的人當管理員：他先用單一登入登入一次，再由管理員在帳號管理按「設成管理員」。
    - 舊資料怎麼接手（建同名的密碼帳號）。
    - OIDC：只接單一租戶、要開「只有被指派的人能登入」、在 IdP 登記的 redirect URI。
    - 部署在 HTTPS 反向代理後面：`/oauth2`、`/login/oauth2` 要轉給後端，要設 `server.forward-headers-strategy: native` 並帶 `X-Forwarded-Proto`、`X-Forwarded-Host`。可以用 curl 打 `/oauth2/authorization/oidc` 確認 Location 裡的 redirect_uri 是 https。
  - 加「分享」一節：兩種分享方式，檢視者看到的資料跟擁有者一樣，用單一登入的人要先登入過一次才能被加進清單。
  - 常見問題補「請先登入」「忘記管理員密碼」（由另一位管理員重設，或在 `.env` 換一個新的 `ADMIN_USERNAME` 重啟）。
- `backend/.env.example` 補變數。
- backlog：
  - 兩項 P1 劃掉，寫上做了什麼。
  - 「測試補強」第 139 行（測試用 controller）和第 136 行（`api.test.js` 假設 `VITE_USER_ID`）一起標掉。
  - 補上第 10 節留下來的項目。
  - 「正式上線前」第 75 行（`DS_` 密鑰沒綁 host）補一句：有了帳號以後，任何帳號都能借用伺服器上的 `DS_` 密鑰（第 10 節）。

## 10. 不做

- 免登入的公開連結。
- 登入失敗次數限制或鎖帳號（列進 backlog「正式上線前」P2）。2026-10-02 起同一個帳號密碼連續錯 5 次會鎖 15 分鐘，見[登入鎖定 spec](2026-10-02-login-lockout-design.md)。
- 管理員重設密碼後，強制對方下次登入就改密碼。
- 把既有的密碼帳號綁到單一登入（要的話，之後在帳號管理加「綁定單一登入」）。也因為這樣，舊資料只能由密碼帳號接手。
- 刪除帳號、改帳號名稱。
- 記住檢視者打開過的公開連結。
- 工作階段存資料庫（Spring Session JDBC）、多台後端。
- 在畫面上設定 OIDC、登出時一起登出 IdP、兩步驟驗證、操作紀錄。
- 限制 OIDC 帳號的網域（靠 IdP 端只讓被指派的人登入）。
- `DS_` 密鑰跟帳號綁定：header 可以寫 `${DS_任何名稱}`，借用伺服器上的密鑰去打允許清單裡的 host。2026-10-01 起只有管理員能註冊外部 API 來源（2.6），但 `DS_` 密鑰還是沒有綁定 host，管理員註冊時哪一把都能用（backlog「正式上線前」）。
- 列層級權限（backlog P3，要等這次做完）。

## 11. 決定（2026-10-01）

| 題目 | 選擇 | 沒選的 |
|---|---|---|
| 帳號怎麼建立 | 管理員建帳號，第一個管理員寫在 `.env`（使用者選） | 開放自行註冊、只寫在設定檔 |
| 分享方式 | 指定帳號，加上擁有者自己決定要不要打開公開連結（要登入）（使用者選） | 免登入的公開連結 |
| OIDC 設定在哪 | `backend/.env`（使用者選） | 管理畫面 |
| OIDC 新使用者 | 自動建帳號（使用者選） | 要先由管理員建好 |
| 舊資料 | 同名的密碼帳號接手 | 全部歸給第一個管理員 |
| 同名的既有帳號或舊資料遇到 OIDC | 擋下來，防冒用 | 用帳號名稱自動接上 |
| OIDC 只有 email 時 | `email_verified` 是 true 才用 | 直接用 |
| 密碼長度 | 至少 8 字元（使用者選，2026-10-01） | 12（OWASP ASVS，原本的預設）、4、15（NIST 單一因素的建議） |
| 停用、改權限、重設密碼 | 馬上讓對方登出（每個請求查一次帳號） | 等工作階段自己逾時 |
| 帳號停用的提示 | 密碼對了才說「已停用」 | 密碼錯也說（會透露帳號存在） |
| 擁有者停用後的分享 | 對檢視者當作不存在 | 照常可以看 |
| 檢視者看「依據」 | 看得到，可以知道這些圖是照什麼需求做的 | 藏起來 |
| 檢視者看到的錯誤 | 換成通用訊息 | 顯示擁有者那邊的原始錯誤 |
| 登入方式 | `formLogin` 加 JSON 回應 | 自己寫 JSON 登入 controller（安全處理要自己補） |
| 工作階段 | 記憶體，閒置 30 分鐘，登入時換新的 | Spring Session JDBC |
| `DS_` 密鑰共用 | 先寫進 backlog 讓使用者決定，使用者後來選了只有管理員能註冊（見下一列）；2026-10-02 起密鑰也綁定 host（`2026-10-02-api-secret-hosts-design.md`） | 照舊讓所有帳號註冊 |
| 誰能註冊外部 API 來源 | 只有管理員（使用者選，2026-10-01） | 所有帳號（任何人都能借用 DS_ 密鑰） |
