# JEV 儀表板：登入驗證與唯讀分享 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec 的登入驗證和唯讀分享。`X-User-Id` header 換成真的登入（帳密，加上選用的 OIDC 單一登入）。管理員管帳號，第一個管理員寫在 `backend/.env`。擁有者可以把儀表板分享給指定帳號，或打開「公開連結」，被分享的人只能看。

**Architecture:** 後端加 Spring Security。一個 `SecurityFilterChain` 處理 `formLogin`（回 JSON）、SPA 的 CSRF（cookie `XSRF-TOKEN`、header `X-XSRF-TOKEN`）、JSON 的 401/403，還有每個請求查一次帳號的 filter。帳號放新的 `account` 表，程式放新的 `jev.dashboard.auth` 套件。controller 改從 `Principal.getName()` 拿身分，service、repository 的 `String userId` 參數不變。分享多一張 `dashboard_share` 表和 `dashboard.public_link` 欄位。`DashboardService.view` 是唯讀的存取檢查，`GET /{id}`、`/data`、`/highlight` 改用它，資料用擁有者的身分載入。前端 `api.js` 拿掉 `X-User-Id`，改帶 CSRF header，401 時通知 App。新元件放 `frontend/src/modules/auth/`。`App.vue` 只加開機的登入檢查、幾個元件、「分享給我的」清單和 `#/d/<id>` 網址。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Spring Security 6.5.11（`spring-boot-starter-security`、`spring-boot-starter-oauth2-client`、`spring-security-test`）、JdbcClient、Flyway、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、Vitest + @vue/test-utils + happy-dom。

**Spec:** `docs/superpowers/specs/2026-10-01-auth-sharing-design.md`

## Global Constraints

- 兩條線平行做：
  - Track B（後端，B1～B7）：在這個 worktree `C:/GitHub/jev-form-dashboard/.claude/worktrees/auth-sharing`，branch `feat/auth-sharing`。不碰 `frontend/`。
  - Track F（前端，F1～F8）：在另一個 worktree `C:/GitHub/jev-form-dashboard/.claude/worktrees/auth-sharing-fe`，branch `feat/auth-sharing-fe`，從加入這份 plan 的 commit 開出來，之後合併回 `feat/auth-sharing`。只碰 `frontend/`（`frontend/vite.config.js` 的 proxy 也在 Track F）。只依賴 spec 第 4 節的 API 契約，不依賴後端程式碼。測試一律 mock `api.js`，不需要起後端。
  - 合併（M1，有條件）：Track F 合併回來之後、D1 之前，檢查 `feat/industrial-theme` 進 `main` 了沒。進了才做 M1，把 `main`（含頂部導覽列）合併進來；還沒進就跳過 M1，這次照左側導覽列的版本合併，之後誰後進 `main` 誰解衝突（`feat/industrial-theme` 還在等使用者看過，順序不歸這份 plan 管）。
  - Track D（文件，D1）：M1（或跳過 M1）之後，在 `feat/auth-sharing` 做。
  - 順序：Track F 進 `feat/auth-sharing` →（M1）→ D1 → `feat/auth-sharing` 進 `main`。
- 同一條線裡的 task 照順序做。每個 task 結束時，這條線的全部測試都要過，然後一個 commit。
- 不要跑 `git switch`、`git checkout`。不要印出 `backend/.env` 的內容。不要起伺服器（瀏覽器驗證由主 session 自己做，見最後一節）。
- 後端指令，在 `backend/` 跑，前面一律加：

  ```bash
  export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_auth SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_auth;
  ```

  然後 `./mvnw test -Dtest=<類別>`，全部跑是 `./mvnw test`。不要跑 `./mvnw clean`。這個 branch 有 migration，所以用自己的 schema `jev_dashboard_test_auth`（Flyway 會自己建）。本機 `~/.m2` 沒有 Spring Security 的 jar，B1 第一次 build 要連 Maven Central。
- 測試裡的 SQL 也照 repository 的規則：時間參數綁 `instant.atOffset(ZoneOffset.UTC)`，不要直接綁 `Instant`（spring-jdbc 6.2 和 PgJDBC 42.7 都不認 `Instant`，會丟「Can't infer the SQL type… java.time.Instant」）。
- 前端指令，在 `frontend/` 跑：`npx vitest run <檔案>`，全部是 `npm test`，打包是 `npm run build`。
- 新的元件測試檔第一行一律是 `// @vitest-environment happy-dom`，跟既有的元件測試一樣：`LoginPage.test.js`、`ReloginDialog.test.js`、`UserMenu.test.js`、`PasswordDialog.test.js`、`AccountsDialog.test.js`、`ShareDialog.test.js`（`session.test.js` 也是）。`vite.config.js` 預設 `environment: 'node'`，少這行，掛載時會 `document is not defined`。
- `App.test.js` 的新測試不用 `.cc-rail*` 和 `railItem`，`feat/industrial-theme` 會把它們改名成 `.cc-topbar*`、`navItem`，M1 合併後就紅了。改用不看版面的寫法：
  - 導覽列：`nav[aria-label="主要導覽"]`。
  - 帳號鈕：`button[title="u1"]`。主題鈕：`button[aria-label^="切換成"]`。
  - 切工作區：既有的 `pressKey({ altKey: true, key: '1' })`。看目前的工作區：既有的 `currentView`（`feat/industrial-theme` 只改它的內容，沒改名字）。
- 本機是 Windows 11 + Git Bash：不要用 `python3`；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 套件： 這次要加三個 Maven 套件（spec 6.1），以前 plan「不新增套件」的規則這次不適用。不新增 npm 套件。
- 既有測試照舊通過，只加新的測試、helper 和 import。例外只有這些：
  - B1：5 個測試檔的 `.header("X-User-Id", x)` 用 sed 換成 `.with(TestData.as(x))`；`TestData` 加 helper、改 `clean`；`ApiExceptionHandlerTest` 刪掉測試用 controller 和缺 header 的測試、加 `@BeforeEach`、改用預設 context，413 測試搬到新的 `UploadLimitTest`；`ApplicationTest` 第 21 行的註解，`flywayCreatesTablesInTestSchema` 改讀實際的 schema、多查 V6 的兩張表。
  - F1：`api.test.js` 第 17 行那個 `X-User-Id` 的測試換掉。
  - F3、F4、F7、F8：`App.test.js` 的 mock 多幾個函式和元件 stub，`beforeEach` 多 mock `me`（F7 再加 `sharedDashboards`），`DashboardView` stub 的 props 多 `readonly`（F7）。F7 另外在檔案最上面加 `enableAutoUnmount(afterEach)`（`@vue/test-utils` 的 import 多一個 `enableAutoUnmount`），每個測試結束就卸載 App，前一個測試的 `hashchange` listener 才不會留在 `window` 上。
  - F6：`DashboardView.test.js` 的 `ChartCard` stub 的 props 多 `readonly`。
  - M1：合併 `main` 時收下 `feat/industrial-theme` 對 `App.test.js` 的改名。
- 後端的字一字不差（spec 2、4）：
  - 401「請先登入」、403「沒有權限」、403「頁面已過期，請重新整理」、500「伺服器錯誤」。
  - 登入：401「帳號或密碼錯誤」、401「帳號已停用，請聯絡管理員」。
  - 帳號規則：400「帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64」。
  - 密碼規則：400「密碼至少要 12 個字元」、400「密碼太長，最多 72 個位元組（約 24 個中文字）」、400「密碼不能跟帳號一樣」。
  - 改密碼：400「目前的密碼不對」、400「這個帳號用單一登入，沒有密碼可以改」。
  - 帳號管理：409「帳號「alice」已經存在」（`alice` 換成轉換後的帳號名稱）、400「不能停用自己的帳號」、400「不能取消自己的管理員」、400「自己的密碼請用「改密碼」」、404「找不到帳號」。
  - 分享：400「找不到帳號「x」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結」、400「不用分享給自己」、404 沿用「找不到儀表板」。
  - 檢視者的 `/data` 錯誤：「資料來源載入失敗，請聯絡儀表板擁有者」。
  - log：WARN「沒有管理員帳號：在 backend/.env 設定 ADMIN_USERNAME、ADMIN_PASSWORD」、INFO「管理員帳號「x」已經存在，不會改它的密碼」。
  - OIDC 失敗轉到 `/?login_error=<代碼>`，代碼是 `username_taken`、`no_username`、`account_disabled`。
- 前端的字一字不差（spec 7），在各 task 列出；`api.js` 的 401 訊息是「登入逾時，重新登入後請再操作一次」。
- 錯誤回應固定是 `{"error": "訊息"}`。
- 新的前端元件放 `frontend/src/modules/auth/`（spec 7）。`App.vue` 的既有標記不重排、不改縮排，只插入新的行，跟同時在做的 `feat/industrial-theme`（導覽列改到頂部）合併時衝突才少。
- 程式碼註解用繁中，寫上 spec 的節次，例如 `// 唯讀分享 spec 2.1：…`。刻意簡化的地方加 `ponytail:` 註解。
- 每個 commit：`git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`。訊息用 Conventional Commits 加繁體中文，每個 task 最後一步寫了確切的訊息。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. 套件和檔案：後端的新程式都放 `jev.dashboard.auth`。帳號名稱和密碼的規則寫成 `Account` record 上的 static 方法（`normalize`、`requireUsername`、`requirePassword`），不另開類別。`SpaCsrfTokenRequestHandler` 是 `SecurityConfig` 的巢狀類別。`AccountCheckFilter` 自己一個檔（有自己的單元測試），但不是 Spring bean。
2. controller 改身分用兩個 sed（B1 Step 5）：參數 `@RequestHeader("X-User-Id") String userId` 換成 `Principal principal`，方法本體的 `userId` 換成 `principal.getName()`。已經在 scratch 複本上跑過，三個 controller 的 `userId`、`RequestHeader` 都換乾淨，`import java.security.Principal;` 排在第一個 `java.` import。
3. `DaoAuthenticationProvider` 用不 deprecated 的寫法：6.5.11 的原始碼裡，`DaoAuthenticationProvider(PasswordEncoder)` 和 `setUserDetailsService` 都標了 `@Deprecated`。改用 `new DaoAuthenticationProvider(userDetailsService)` 加 `setPasswordEncoder(encoder)`。宣告成 `@Bean`，Spring 會把唯一的 `AuthenticationProvider` bean 接進全域的 `AuthenticationManager`，Boot 也就不會產生預設的 in-memory 使用者（啟動 log 不該出現 `Using generated security password`）。
4. 測試密碼寫死：`TestData.PASSWORD = "test-password-1"`，`TestData.PASSWORD_HASH = "{bcrypt}$2a$04$YjwA8WPDUz9gUv6VAUQbOudjRmUno73kxXve8EeD/tOS7NvU546tO"`。這是用 Python `bcrypt` 事先算好、驗證過的值，cost 4，登入測試才不慢。程式自己設的密碼照舊用 `createDelegatingPasswordEncoder()`（cost 10）。
5. 413 測試搬到新的 `UploadLimitTest`：spring-security-test 的 `csrf()` 第一次用的時候，會用反射把共用的 `CsrfFilter.tokenRepository` 換成 `TestCsrfTokenRepository(new HttpSessionCsrfTokenRepository())`（6.5.11 的 `SecurityMockMvcRequestPostProcessors.CsrfRequestPostProcessor` 和 `WebTestUtils.setCsrfTokenRepository`，兩個都查過原始碼）。同一個 context 裡跑過任何 `TestData.as(...)` 之後，真的 Tomcat 就拿不到 `XSRF-TOKEN` cookie，測試結果會看執行順序。所以 413 測試放進自己的類別，不加 `@AutoConfigureMockMvc`，拿到自己的 context。`ApiExceptionHandlerTest` 剩下的都是 MockMvc 測試，改成跟其他 controller 測試一樣的預設 context。
6. 工作階段失效的比較：`AccountCheckFilter` 在 `session != null`、`sessions_valid_after != null`、而且 `Instant.ofEpochMilli(session.getCreationTime()).isBefore(sessionsValidAfter)` 時擋下。用完整精度比較，舊的工作階段一定會失效。代價是變動之後同一毫秒內的新登入也會被擋一次，但帳密登入至少要跑一次 BCrypt，實際上碰不到。沒有 session 的已登入請求（正式環境不會有）只看帳號在不在、有沒有啟用。寫入一律用 `Instant.now()`（spec 2.1），不用 `Clock` bean。
7. 密碼規則的細節：「12 個字元」用 code point 算（`codePointCount`），「72 個位元組」用 UTF-8 算。檢查順序是長度不夠、太長、跟帳號一樣。「跟帳號一樣」不分大小寫（`equalsIgnoreCase`），因為帳號名稱一律是小寫。
8. 檢查的順序。自己改密碼：先看有沒有密碼（單一登入），再看目前的密碼（`current` 是 null 也算不對），最後才看新密碼的規則。PATCH：先檢查「不能停用自己」「不能取消自己的管理員」，再查帳號（404）。值真的有變才寫入、才更新 `sessions_valid_after`。PUT 密碼：先檢查是不是自己，再 404，再密碼規則。
9. 管理員建立失敗時的訊息（spec 只說「寫明原因」），一律丟 `IllegalStateException`：
   - 帳號名稱不合規則：`ADMIN_USERNAME 不合規則：` 加帳號規則的訊息。
   - 密碼沒填：`要建立管理員帳號「x」，但 ADMIN_PASSWORD 沒填`。
   - 有 0x21～0x7E 以外的字元：`ADMIN_PASSWORD 只能用英數字和符號，不能有空白或中文（backend/.env 是用 ISO-8859-1 讀的）`。
   - 密碼規則：`ADMIN_PASSWORD 不合規則：` 加密碼規則的訊息。
   - 帳號已經存在但停用了或不是管理員時，WARN 的內容是「管理員帳號「x」已經存在，但已經停用或不是管理員，不會改它」。建立成功時 INFO「已建立管理員帳號「x」」。
10. OIDC：
    - 啟用 OIDC 時，`OIDC_ISSUER_URI` 用 `URI` 解析：scheme 是 `https`，或 scheme 是 `http` 而且 host 正好是 `127.0.0.1`，才能啟動，否則啟動失敗（spec 2.3「issuer 要用 https」）。不用字串開頭比對，`http://127.0.0.1.evil.example` 才不會過。
    - `userInfoEndpoint` 的 `userService`（沒有 `openid` 的 OAuth2 登入）設成一律丟 `OAuth2AuthenticationException`。scope 固定有 `openid`，照理走不到這裡，多一行把門關死。
    - 失敗時 `login_error` 帶 `OAuth2AuthenticationException` 的 error code（URL encode），其他例外帶 `failed`。前端認得的三個以外，一律顯示「單一登入失敗，請再試一次」。
    - 兩個人同時用同一個新名稱第一次登入，主鍵衝突會變成 500。ponytail：不處理，下次再按就是 `username_taken`。
    - `FakeOidcServer` 的 discovery 除了 spec 列的四個欄位，還要給 `subject_types_supported`、`response_types_supported`、`id_token_signing_alg_values_supported`。nimbus 的 `OIDCProviderMetadata.parse` 用必填的 getter 讀 `subject_types_supported`，少了就解析失敗。照樣不給 `userinfo_endpoint`。
    - OIDC 測試的 context 設 `client-secret`（走 `client_secret_basic`）。沒有 secret 時 Spring 本來就會加 PKCE，有 secret 時 authorize 網址還有 `code_challenge`，才證明 `requireProofKey(true)` 有作用。
11. `/api/auth/options` 用 record 回傳，`{"oidc": null}` 的 null 才寫得出來（`Map.of` 不收 null）。
12. 分享：
    - `users` 整個沒給（null）當作空的清單。再丟掉 null 和空白的項目，照 2.1 轉換、去重複、排序。
    - 存分享不改 `updated_at`，`updated_at` 只代表內容的更新時間。
    - `setShare` 在 `DashboardService` 上加 `@Transactional`（更新 `public_link`、刪掉舊的、寫入新的，三句一起成功）。
    - 唯讀檢查（`DashboardRepository.findViewable`）在 SQL 裡 `join account` 擁有者並要求 `enabled`，「擁有者停用就當作不存在」寫在同一句裡。擁有者自己一定是啟用的（帳號檢查 filter 擋過），所以對他沒有影響。
    - 檢視者的 `/data` 和 `/highlight` 只把 `ApiException` 的訊息換掉，狀態碼照舊。其他例外本來就是 500「伺服器錯誤」。`/highlight` 的錯誤前端不顯示，但會出現在回應裡，所以一樣要換（spec 11「檢視者看到的錯誤」）。
13. 前端的 API 都加在 `modules/dashboard/api.js` 的 `api` 物件上，`onUnauthorized` 也從 `api.js` 匯出，`modules/dashboard/index.js` 一起轉出去。`modules/auth/` 的元件從 `../dashboard/api.js` 拿 `api`，測試 mock 同一個路徑。
14. App 的測試：`App.test.js` 用 `vi.mock('./modules/auth/index.js', …)` 把 auth 的元件換成 stub，跟現在 stub `ChartBuilder` 的寫法一樣。元件本身在自己的測試檔測。`modules/auth/session.js` 是純函式，App 直接 import，不 mock。
15. 開機的登入檢查留在 `App.vue`（spec 7.1、7.2 都說「App 另外一個 ref」）。既有的導覽列、`v-main`、刪除對話框外面包一層 `<template v-else>`，裡面的行不重排縮排，`App.vue` 的 diff 就只有新增的行。
16. `ChartCard` 多一個 `readonly` prop 來決定檢視者的字和游標，不靠 `removable`。預覽的明細表 `removable` 本來就是 false，用 `removable` 判斷的話，擁有者在預覽裡會看到「請聯絡儀表板擁有者」。spec 8.2 寫的「不能移除時」照這個意思測。
17. 空白狀態：只要有任何一份（自己的或分享給我的），就只藏 `ol.empty-steps`，「前往資料」「開始製圖」兩顆按鈕留著，既有的測試還用得到。
18. `sessionStorage` 的 key 是 `jev-return-hash`：開機第 1 步一律刪掉這筆，網址沒有 hash 時才補回去。
19. 帳號鈕的外觀寫在 `App.vue`：`UserMenu` 開一個 `activator` slot，App 在裡面放跟主題鈕同樣 class 的 `<button>`，導覽列的樣式（scoped）才套得到。合併 `feat/industrial-theme` 時只要改這一段和幾行樣式（M1）。
20. 登入頁的 logo 先複製 `App.vue` 的 `.cc-logo` 樣式到 `LoginPage.vue`（scoped）。不搬到共用 CSS，避免跟 `feat/industrial-theme` 的 logo 改動衝突。M1 合併之後，換成新的 SVG 標誌和樣式。
21. 帳號管理的成功訊息：重設是「已重設「x」的密碼」（spec 的例子），沒有密碼的帳號按「設定密碼」成功後是「已設定「x」的密碼」。
22. 重新登入對話框：沒有密碼的帳號，標題一樣是「請重新登入」。
23. 網址的 hash 在 `open()` 一開始就改，不等抓回來（spec 7.8：404 時 hash 是失敗的那份，重新整理才會再試它）。`openFromHash` 遇到 hash 正好是最後一次 `open()` 的那份就不理。瀏覽器的 `replaceState` 不會觸發 `hashchange`，但 happy-dom 20 會（`Location.js` 的 `setURL` 用 `setTimeout` 補發），少了這個判斷，`open()` 會自己再打開一次，id 不是 UUID 的既有測試還會被當成「找不到」。

## Review Focus

- 停用的帳號配錯密碼，回的是「帳號或密碼錯誤」，不是「帳號已停用」；沒有密碼的帳號也一樣（B1 測）。
- 401 的中文不能變成問號：Content-Type 要是 `application/json;charset=UTF-8`（B1 測）。
- 真的 Tomcat 走一次 cookie 版的 CSRF：拿 `XSRF-TOKEN`、登入、用登入回應裡最後一個 `XSRF-TOKEN` 上傳（B1 `UploadLimitTest`）。
- `AccountCheckFilter` 不能是 Spring bean（不能有 `@Component`，`SecurityConfig` 裡也不能用 `@Bean` 回傳它），而且要在 `AnonymousAuthenticationFilter` 後面（B2）。
- 取消管理員之後，舊的工作階段打 `/api/admin/accounts` 要回 401，不能是 200（B3 測）。
- 管理員改自己：不能停用、不能取消管理員、不能從帳號管理重設密碼（B3 測，F5 不顯示按鈕）。
- `.env` 的管理員密碼不會蓋掉資料庫裡已經改過的密碼（B4 測）。
- OIDC 不能用帳號名稱接上既有帳號或舊資料，只認 `(issuer, sub)`（B5 測）。
- 檢視者只多拿到 `GET /{id}`、`/data`、`/highlight`；改、刪、加圖、分享設定、資料來源 API 一律照舊 404（B7 測）。檢視者的 `/data`、`/highlight` 都不會露出「環境變數 DS_X 未設定」（B7 測）。
- OIDC 的 issuer 檢查要用 `URI` 的 scheme 和 host，`http://127.0.0.1.evil.example` 不能過（B5 測）。
- `DashboardDataService.recent` 的 key 不能改成只用儀表板 id，否則別人的預覽 body 帶同一個 id 就能蓋掉擁有者那份（B7 不改這段，review 時確認）。
- `/api/auth/login`、`/api/auth/me` 的 401 不能觸發重新登入對話框，否則開機和登入失敗會進迴圈（F1 測）。
- 登入逾時時，畫面上的元件不能卸載，「製圖」的預覽要留著；換了帳號要整頁重新載入，不留前一個人的畫面（F3 測）。
- 打開失敗回 404 時，網址的 hash 是失敗的那份（不是前一份），重新整理才會再試它（F7 測）。`open()` 自己改的 hash 不能讓 `openFromHash` 再打開一次（F7 測，happy-dom 會補發 `hashchange`）。
- 檢視者拖拉、縮放、移除卡片都不能送出 PUT（F6 測）。
- 沒有 `navigator.clipboard` 時，複製連結要退回「請按 Ctrl+C」（F8 測）。
- `App.vue` 的 diff 只有新增的行，既有標記沒有被重排（F3、F4、F7、F8 review 時看 `git diff`）。
- M1 合併後，帳號鈕在頂部導覽列、緊貼主題鈕左邊，class 是 `cc-topbar__*`，600px 以下只剩圖示；`App.vue`、`App.test.js` 沒有 `cc-rail`、`railItem`。

## File Structure

```
backend/
  pom.xml                                                    三個套件（B1）
  src/main/resources/application.yml                         第 1 行註解、server.tomcat.max-swallow-size（B1）；jev.auth（B4）
  src/main/resources/db/migration/V6__auth_sharing.sql       新檔（B1）
  src/main/java/jev/dashboard/ApiExceptionHandler.java      刪掉 MissingRequestHeaderException 那一支（B1）
  src/main/java/jev/dashboard/auth/
    Account.java                                             新檔：record、normalize（B1）；requireUsername、requirePassword（B2）；View（B3）
    AccountRepository.java                                   新檔：find（B1）；setPassword（B2）；list、insert、setEnabled、setAdmin（B3）；anyEnabledAdmin（B4）；findByOidc、hasLegacyData（B5）
    SecurityConfig.java                                      新檔（B1）；帳號檢查 filter（B2）；/api/admin/**（B3）；/api/auth/options（B4）；oauth2Login（B5）
    AuthController.java                                      新檔：GET /api/auth/me（B1）；POST /api/auth/password（B2）；GET /api/auth/options（B4）
    AccountCheckFilter.java                                  新檔（B2）
    AccountService.java                                      新檔：changeOwnPassword（B2）；create、update、resetPassword（B3）；forOidc（B5）
    AccountController.java                                   新檔（B3）
    AuthProperties.java、AdminBootstrap.java                  新檔（B4）
  src/main/java/jev/dashboard/dashboard/DashboardController.java   sed（B1）；分享 API（B6）；唯讀存取（B7）
  src/main/java/jev/dashboard/dashboard/DashboardService.java      share、setShare、shared（B6）；view（B7）
  src/main/java/jev/dashboard/dashboard/DashboardRepository.java   Share、SharedDashboard、share、setShare、sharedWith（B6）；findViewable（B7）
  src/main/java/jev/dashboard/datasource/DatasourceController.java sed（B1）
  src/main/java/jev/dashboard/jev/JevController.java               sed（B1）；highlight 用擁有者、檢視者的錯誤換字（B7）
  src/test/resources/application-test.yml                    jev.auth，含固定的 oidc.label（B4）
  src/test/java/jev/dashboard/
    TestData.java                                            as、addAccount、PASSWORD、clean（B1）；asAdmin（B3）
    ApiExceptionHandlerTest.java、ApplicationTest.java        手動改（B1）
    UploadLimitTest.java                                     新檔（B1）
    FakeOidcServer.java                                      新檔（B5）
    dashboard/DashboardControllerTest.java                   sed（B1）；分享測試（B6、B7）
    datasource/DatasourceControllerTest.java                 sed（B1）
    jev/JevControllerTest.java                               sed（B1）；檢視者的 highlight（B7）
    auth/AuthControllerTest.java                             新檔（B1）；加測試（B2、B4）
    auth/AccountCheckFilterTest.java                         新檔（B2）
    auth/AccountControllerTest.java                          新檔（B3）
    auth/AdminBootstrapTest.java                             新檔（B4）
    auth/OidcLoginTest.java                                  新檔（B5）
frontend/
  vite.config.js                                             /oauth2、/login/oauth2 的 proxy（F1）
  src/App.vue、src/App.test.js                                開機、重新登入（F3）；帳號鈕（F4）；分享給我的、唯讀、網址（F7）；分享鈕（F8）；頂部導覽列的帳號鈕（M1）
  src/modules/dashboard/api.js、api.test.js、index.js         CSRF、401、新的 API（F1，index.js 只改第 5 行）
  src/modules/dashboard/DashboardView.vue、DashboardView.test.js、ChartCard.vue、ChartCard.test.js   readonly（F6）
  src/modules/auth/index.js                                  新檔（F2）；之後每個 task 加匯出
  src/modules/auth/session.js、session.test.js               新檔（F2）
  src/modules/auth/LoginForm.vue、LoginPage.vue、LoginPage.test.js        新檔（F2）；LoginPage 換新的 logo（M1）
  src/modules/auth/ReloginDialog.vue、ReloginDialog.test.js               新檔（F3）
  src/modules/auth/UserMenu.vue、UserMenu.test.js、PasswordDialog.vue、PasswordDialog.test.js   新檔（F4）；選單往下開（M1）
  src/modules/auth/AccountsDialog.vue、AccountsDialog.test.js             新檔（F5）
  src/modules/auth/ShareDialog.vue、ShareDialog.test.js                   新檔（F8）
README.md、backend/.env.example、docs/backlog.md                （D1）
```

---

### Task B1: 後端：Spring Security 上線，既有 API 改用登入的帳號

**Files:**
- Modify: `backend/pom.xml`、`backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V6__auth_sharing.sql`
- Create: `backend/src/main/java/jev/dashboard/auth/Account.java`、`AccountRepository.java`、`SecurityConfig.java`、`AuthController.java`
- Modify（sed）: `backend/src/main/java/jev/dashboard/dashboard/DashboardController.java`、`datasource/DatasourceController.java`、`jev/JevController.java`
- Modify: `backend/src/main/java/jev/dashboard/ApiExceptionHandler.java`（刪掉走不到的那一支）
- Modify: `backend/src/test/java/jev/dashboard/TestData.java`、`ApiExceptionHandlerTest.java`、`ApplicationTest.java`
- Modify（sed）: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`、`datasource/DatasourceControllerTest.java`、`jev/JevControllerTest.java`
- Create: `backend/src/test/java/jev/dashboard/UploadLimitTest.java`、`auth/AuthControllerTest.java`

**Interfaces:**
- Produces:
  - `account`、`dashboard_share` 表和 `dashboard.public_link`（V6，SQL 照 spec 第 5 節一字不差）
  - `Account(String username, String passwordHash, boolean admin, boolean enabled, String oidcIssuer, String oidcSubject, Instant sessionsValidAfter, Instant createdAt)`（public record）、`static String Account.normalize(String raw)`（null 回 null；否則 `strip().toLowerCase(Locale.ROOT)`）
  - `AccountRepository.find(String username): Optional<Account>`
  - `SecurityConfig.writeError(HttpServletResponse res, ObjectMapper json, int status, String message)`（package-private static）
  - `POST /api/auth/login`（form：`username`、`password`）→ 204／401；`POST /api/auth/logout` → 204；`GET /api/auth/me` → `{"username","admin","password"}`
  - 測試：`TestData.PASSWORD`、`TestData.PASSWORD_HASH`、`TestData.as(String): RequestPostProcessor`、`TestData.addAccount(JdbcClient, String username, boolean admin)`；`TestData.clean` 之後有 u1、u2、u3 三個啟用中的一般帳號
- Consumes: 無

- [ ] **Step 1: 加套件和 migration**

`pom.xml` 在 `spring-boot-starter-jdbc` 後面加 `spring-boot-starter-security`、`spring-boot-starter-oauth2-client`，在 `spring-boot-starter-test` 後面加 `org.springframework.security:spring-security-test`（`<scope>test</scope>`）。版本都不寫，由 Boot 3.5.16 管（6.5.11）。

`V6__auth_sharing.sql` 照 spec 第 5 節的 SQL 原樣貼上。

- [ ] **Step 2: 寫失敗的測試**

`TestData` 先加這些（測試 helper，這一步就要寫，下面的測試才編得過）：

```java
/** 測試帳號的密碼；PASSWORD_HASH 是事先算好的 bcrypt（cost 4），clean 不必每次重算。 */
public static final String PASSWORD = "test-password-1";
public static final String PASSWORD_HASH = "{bcrypt}$2a$04$YjwA8WPDUz9gUv6VAUQbOudjRmUno73kxXve8EeD/tOS7NvU546tO";

public static void clean(JdbcClient jdbc) {
    jdbc.sql("truncate dashboard_share, account, dashboard, datasource_rows, datasource").update();
    for (String u : List.of("u1", "u2", "u3")) addAccount(jdbc, u, false);
}

public static void addAccount(JdbcClient jdbc, String username, boolean admin) {
    jdbc.sql("insert into account (username, password_hash, admin) values (:u, :h, :a)")
        .param("u", username).param("h", PASSWORD_HASH).param("a", admin).update();
}

/** 以這個帳號登入加上 CSRF token（唯讀分享 spec 8.1）。帳號要先在 account 表裡。 */
public static RequestPostProcessor as(String username) {
    return req -> csrf().postProcessRequest(user(username).postProcessRequest(req));
}
```

`auth/AuthControllerTest`（`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`，`@BeforeEach` 呼叫 `TestData.clean`）。登入一律用 `SecurityMockMvcRequestBuilders.formLogin("/api/auth/login").user(..).password(..)`，從 `result.getRequest().getSession(false)` 拿 `MockHttpSession`，之後的請求帶 `.session(s)`：

```java
@Test void passwordLoginReturns204AndMeReadsTheDatabase()
// addAccount("boss", admin=true)；formLogin boss / PASSWORD → 204、authenticated().withUsername("boss")
// 帶 session 打 GET /api/auth/me → {"username":"boss","admin":true,"password":true}
// update account set admin = false where username = 'boss'；同一個 session 再打 me → admin false（讀資料庫，不是讀 session 裡的權限）

@Test void wrongPasswordUnknownAccountNoPasswordAndDisabledWithWrongPasswordAllSayTheSame()
// u1 配錯密碼、nobody、insert 一個 password_hash 是 null 的 sso-only 配 PASSWORD、
// 停用的 u2（update enabled = false）配錯密碼：都是 401、$.error == "帳號或密碼錯誤"

@Test void aDisabledAccountWithTheRightPasswordIsToldSo()
// 停用 u2；formLogin u2 / PASSWORD → 401、$.error == "帳號已停用，請聯絡管理員"

@Test void usernameIsTrimmedAndLowercased()
// formLogin "  U1 " / PASSWORD → 204；帶 session 打 me → $.username == "u1"

@Test void logoutEndsTheSession()
// 登入 u1；post("/api/auth/logout").session(s).with(csrf()) → 204；同一個 session 打 me → 401

@Test void apiWithoutLoginIs401JsonInUtf8()
// GET /api/dashboards（不帶身分）→ 401、Content-Type "application/json;charset=UTF-8"、
// content().string("{\"error\":\"請先登入\"}")（中文沒有變成問號）

@Test void aPostWithoutCsrfIs403PageExpired()
// post("/api/dashboards").with(user("u1"))，不帶 csrf → 403、$.error == "頁面已過期，請重新整理"

@Test void legacyDataBelongsToTheAccountWithTheSameName()   // spec 8.1「既有資料」
// 先用 SQL 寫一筆 created_by = 'u9' 的儀表板（config_json '{"layout":[]}'），再 addAccount("u9")
// as("u9") GET /api/dashboards 有這一份；GET /api/dashboards/{id} → 200
```

`UploadLimitTest`（新檔，在 `jev.dashboard`）：

```java
// 真的起 Tomcat：上傳大小限制只有真的 multipart 解析才會觸發，MockMvc 模擬不出來。
// 不加 @AutoConfigureMockMvc：MockMvc 的 csrf() 會用反射把共用的 CsrfFilter 換成 session 版的 token repository，
// 同一個 context 裡真的 Tomcat 就拿不到 XSRF-TOKEN cookie（見 plan「實作上的決定」第 5 點）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.servlet.multipart.max-file-size=1KB")
@ActiveProfiles("test")
class UploadLimitTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;

    @BeforeEach void clean() { TestData.clean(jdbc); }

    @Test void uploadOverTheLimitIs413WithTheConfiguredLimit()
    // 1. GET /api/auth/me → 401，從 Set-Cookie 取 XSRF-TOKEN（t1）
    // 2. POST /api/auth/login（form：username=u1、password=TestData.PASSWORD；Cookie: XSRF-TOKEN=t1；X-XSRF-TOKEN: t1）→ 204
    // 3. 從登入的回應取 JSESSIONID 和最後一個 XSRF-TOKEN（t2）；t2 不是空的，也跟 t1 不一樣（登入時換了 token）
    // 4. 照原本的寫法上傳 2048 bytes 的 big.csv，帶 Cookie: JSESSIONID=…; XSRF-TOKEN=t2 和 X-XSRF-TOKEN: t2
    //    → 413、$.error == "檔案太大，上限 1KB"
}
```

取 cookie 的 helper（同名的取最後一個，登入的回應會先清掉舊的 `XSRF-TOKEN` 再設新的）：

```java
static String cookie(HttpHeaders headers, String name) {
    String value = null;
    for (String c : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
        if (c.startsWith(name + "=")) value = c.substring(name.length() + 1, c.indexOf(';'));
    }
    return value;
}
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest='AuthControllerTest,UploadLimitTest'`
Expected: FAIL。這時還是 Boot 預設的安全設定：登入不會回 204，401 不是 JSON，`/api/auth/me` 不存在。既有的 controller 測試這時也會大量回 401／403，Step 5 之後才會回來。

- [ ] **Step 4: 實作**

- `Account`：record 加 `static String normalize(String raw)`（見 Interfaces）。
- `AccountRepository`（`@Repository`，建構子收 `JdbcClient`，寫法照 `DashboardRepository`）：`find` 用 `select * from account where username = :u`。時間欄位讀 `rs.getObject(col, OffsetDateTime.class)` 再 `toInstant()`，null 照樣是 null。之後寫入時間的參數一律用 `instant.atOffset(ZoneOffset.UTC)`。
- `AuthController`（`@RestController @RequestMapping("/api/auth")`）：`record Me(String username, boolean admin, boolean password)`；`GET /me` 用 `principal.getName()` 查 `AccountRepository.find`，三個值都從資料庫來，查不到丟 `ApiException(UNAUTHORIZED, "請先登入")`。
- `SecurityConfig`（`@Configuration`，不用加 `@EnableWebSecurity`）：

```java
@Bean
PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // 存成 {bcrypt}…
}

/** 唯讀分享 spec 2.2：帳密登入。停用的檢查移到密碼比對之後，密碼錯一律「帳號或密碼錯誤」。 */
@Bean
DaoAuthenticationProvider passwordLogin(AccountRepository accounts, PasswordEncoder encoder) {
    DaoAuthenticationProvider p = new DaoAuthenticationProvider(username -> accounts.find(Account.normalize(username))
        .filter(a -> a.passwordHash() != null) // 只用單一登入的帳號當作不存在，走 Spring 內建的防時間差處理
        .map(a -> User.withUsername(a.username()).password(a.passwordHash())
            .roles(a.admin() ? "ADMIN" : "USER").disabled(!a.enabled()).build())
        .orElseThrow(() -> new UsernameNotFoundException(username)));
    p.setPasswordEncoder(encoder);
    p.setPreAuthenticationChecks(user -> {});
    p.setPostAuthenticationChecks(user -> {
        if (!user.isEnabled()) throw new DisabledException("帳號已停用，請聯絡管理員");
    });
    return p;
}

@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
    http
        .authorizeHttpRequests(a -> a
            .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll()) // 之後放前端靜態檔
        .csrf(c -> c
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
        .formLogin(f -> f
            .loginProcessingUrl("/api/auth/login")
            .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
            .failureHandler((req, res, e) -> writeError(res, json, 401,
                e instanceof DisabledException ? "帳號已停用，請聯絡管理員" : "帳號或密碼錯誤")))
        .logout(l -> l
            .logoutUrl("/api/auth/logout")
            .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .sessionManagement(s -> s.sessionFixation(f -> f.newSession())) // 工作階段的建立時間就是登入時間（spec 2.1）
        .requestCache(r -> r.requestCache(new NullRequestCache()))
        .exceptionHandling(e -> e // 設了 entry point，Spring 就不會自己產生 /login 頁
            .authenticationEntryPoint((req, res, ex) -> writeError(res, json, 401, "請先登入"))
            .accessDeniedHandler((req, res, ex) -> writeError(res, json, 403,
                ex instanceof CsrfException ? "頁面已過期，請重新整理" : "沒有權限")));
    return http.build();
}

/** spec 2.2：安全設定裡的錯誤都從這裡寫，charset 一定要是 UTF-8，中文才不會變成問號。 */
static void writeError(HttpServletResponse res, ObjectMapper json, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(json.writeValueAsString(Map.of("error", message)));
}

/** Spring Security 6.5 文件的 SPA 作法，原樣照抄（7.0 才有 csrf.spa()）。 */
static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        csrfToken.get(); // 讓 deferred token 載入，每個回應都會寫 XSRF-TOKEN cookie（沒登入的 401 也有）
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
```

`CsrfException` 是 `org.springframework.security.web.csrf.CsrfException`，`InvalidCsrfTokenException` 和 `MissingCsrfTokenException` 都繼承它。`DispatcherType` 是 `jakarta.servlet.DispatcherType`。

- `application.yml`：
  - 第 1 行換成 `# 預設只聽本機；要對外開用 --server.address=0.0.0.0（或 SERVER_ADDRESS），開之前先設好管理員帳號（README「登入與帳號」）`。
  - 在既有的 `server:` 區塊裡（`address` 下面）加上，不要另起一個 `server:`：

    ```yaml
      tomcat:
        max-swallow-size: 50MB   # 沒登入時上傳大檔，401 才回得到前端，不會變成連線被切斷
    ```

- [ ] **Step 5: 把既有的 controller 和測試換成登入的帳號**

controller（在 `backend/src/main/java/jev/dashboard/` 跑）：

```bash
sed -i -E \
  -e 's/@RequestHeader\("X-User-Id"\) String userId/Principal principal/g' \
  -e 's/\buserId\b/principal.getName()/g' \
  -e '/^import org\.springframework\.web\.bind\.annotation\.RequestHeader;$/d' \
  -e '0,/^import java\./s//import java.security.Principal;\nimport java./' \
  dashboard/DashboardController.java datasource/DatasourceController.java jev/JevController.java
grep -n "X-User-Id\|userId\|RequestHeader" dashboard/DashboardController.java datasource/DatasourceController.java jev/JevController.java
```

最後的 grep 應該沒有輸出。

`ApiExceptionHandler`：`handleExceptionInternal` 的 `case MissingRequestHeaderException e -> "缺少 header：" + e.getHeaderName();` 那一行和 `import org.springframework.web.bind.MissingRequestHeaderException;` 刪掉。controller 都沒有 `@RequestHeader` 了，測試用的 controller 也刪了，這一支走不到。`grep -rn "RequestHeader" backend/src/main` 應該沒有輸出。

測試（在 `backend/src/test/java/jev/dashboard/` 跑）：

```bash
sed -i -E 's/\.header\("X-User-Id", ([^)]+)\)/.with(TestData.as(\1))/g' \
  TestData.java ApiExceptionHandlerTest.java dashboard/DashboardControllerTest.java \
  datasource/DatasourceControllerTest.java jev/JevControllerTest.java
grep -rn "X-User-Id" .
```

grep 只會剩三行，手動處理：
- `ApiExceptionHandlerTest`：
  - 刪掉 `missingRequestHeaderReturnsErrorBody` 和 `TestOnlyController`（backlog「測試補強」第 139 行）。
  - 刪掉 `uploadOverTheLimitIs413WithTheConfiguredLimit`，它已經搬到 `UploadLimitTest`，連同 `@Autowired TestRestTemplate http`。
  - 檔頭的註解和 `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = …)` 改成跟 `DashboardControllerTest` 一樣的 `@SpringBootTest`，`@ActiveProfiles("test")`、`@AutoConfigureMockMvc` 照舊。
  - 加 `@Autowired JdbcClient jdbc;` 和 `@BeforeEach void clean() { TestData.clean(jdbc); }`。
  - 刪掉因此用不到的 import。
- `ApplicationTest`：
  - 第 21 行的註解改成 `// 預設只聽本機；要對外開用 --server.address=0.0.0.0`。
  - `flywayCreatesTablesInTestSchema` 原本寫死 `table_schema = 'jev_dashboard_test'`。這個 branch 跑的是 `jev_dashboard_test_auth`，舊的 schema 剛好存在才會過，也沒驗到 V6。改成讀實際的 schema，再多查兩張表：

    ```java
    String schema = env.getProperty("spring.flyway.schemas"); // 環境變數 SPRING_FLYWAY_SCHEMAS 會蓋過 application-test.yml
    List<String> tables = jdbc.sql("""
            select table_name from information_schema.tables
            where table_schema = :s order by table_name""")
        .param("s", schema).query(String.class).list();
    assertThat(tables).contains("account", "dashboard", "dashboard_share", "datasource", "datasource_rows", "jev_flyway_history");
    ```

- [ ] **Step 6: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。啟動 log 沒有 `Using generated security password`。

- [ ] **Step 7: Commit**

`feat(backend): 加上 Spring Security 帳密登入與 CSRF，既有 API 改用登入的帳號`

---

### Task B2: 後端：每個請求檢查帳號，自己改密碼

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/auth/Account.java`、`AccountRepository.java`、`SecurityConfig.java`、`AuthController.java`
- Create: `backend/src/main/java/jev/dashboard/auth/AccountCheckFilter.java`、`AccountService.java`
- Test: `backend/src/test/java/jev/dashboard/auth/AuthControllerTest.java`、Create `AccountCheckFilterTest.java`

**Interfaces:**
- Produces:
  - `Account.USERNAME`（`Pattern`，`^[a-z0-9][a-z0-9._@-]{1,63}$`）、`static String Account.requireUsername(String raw)`（回轉換後的名稱，不合規則丟 400）、`static void Account.requirePassword(String username, String password)`（三條規則，見「實作上的決定」第 7 點）
  - `AccountRepository.setPassword(String username, String passwordHash, Instant sessionsValidAfter): boolean`
  - `AccountService.changeOwnPassword(String username, String current, String password): void`
  - `POST /api/auth/password`（`{"current","password"}`）→ 204
  - `AccountCheckFilter(AccountRepository accounts, ObjectMapper json)`（package-private，不是 bean）
- Consumes: B1 的 `AccountRepository.find`、`SecurityConfig.writeError`、`TestData`

- [ ] **Step 1: 寫失敗的測試**

`AuthControllerTest` 加：

```java
@Test void changingYourOwnPasswordEndsEverySessionIncludingThisOne()
// u1 登入兩次拿 s1、s2；POST /api/auth/password（s1 + csrf）{"current":PASSWORD,"password":"new-password-123"} → 204
// s1、s2 打 me 都是 401 {"error":"請先登入"}；舊密碼登入 401；新密碼登入 204

@Test void changePasswordRejectsAWrongCurrentPasswordAndSsoAccounts()
// as("u1") {"current":"wrong-password-1","password":"new-password-123"} → 400 「目前的密碼不對」
// insert 一個 password_hash 是 null 的 sso-a；as("sso-a") → 400 「這個帳號用單一登入，沒有密碼可以改」

@Test void changePasswordChecksThePasswordRules()
// as("u1")：11 個字 → 「密碼至少要 12 個字元」；"營".repeat(25)（75 bytes）→ 「密碼太長，最多 72 個位元組（約 24 個中文字）」
// addAccount("long-account-1")；as("long-account-1") 新密碼 "LONG-ACCOUNT-1" → 「密碼不能跟帳號一樣」
// 最後才 as("u1") "營".repeat(24)（72 bytes）→ 204（成功會讓 u1 的工作階段失效，放最後）

@Test void aDisabledAccountsSessionGets401AndStaysDeadAfterReEnabling()
// u1 登入拿 s；update account set enabled = false → s 打 me 401
// update account set enabled = true（只改 enabled）→ s 打 me 還是 401（session 在上一次就失效了）

@Test void aSessionCreatedBeforeSessionsValidAfterGets401()
// u1 登入拿 s；update account set sessions_valid_after = :t，
// .param("t", Instant.ofEpochMilli(s.getCreationTime() + 1).atOffset(ZoneOffset.UTC))（不能直接綁 Instant，見 Global Constraints）
// s 打 me → 401；Thread.sleep(2) 之後重新登入拿 s2 → me 200
```

同一毫秒的問題：帳號有變動之後，馬上用 `TestData.PASSWORD`（cost 4，很快）重新登入，新的工作階段可能跟變動落在同一毫秒，被當成舊的（「實作上的決定」第 6 點）。重新登入前先 `Thread.sleep(2)`。用程式設的新密碼登入（cost 10）不用。B3 照同樣的規則。

`AccountCheckFilterTest`（純單元測試，不起 Spring）：

```java
@Test void aDatabaseErrorIs500JsonAndStopsTheChain()
// new AccountRepository(null) { @Override public Optional<Account> find(String u) { throw new DataAccessResourceFailureException("down"); } }
// SecurityContextHolder 放 UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of())（測完 clearContext）
// filter.doFilter(new MockHttpServletRequest(), res, chain) → res 500、content type application/json;charset=UTF-8、
// body {"error":"伺服器錯誤"}、chain.getRequest() 是 null（沒往下走）

@Test void anonymousRequestsPassWithoutQueryingTheDatabase()
// SecurityContext 是空的；find 一被呼叫就丟例外 → chain.getRequest() 不是 null、res 200
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest='AuthControllerTest,AccountCheckFilterTest'`
Expected: 新測試 FAIL（`/api/auth/password` 不存在、停用後 session 照樣能用），B1 的測試照舊 PASS。

- [ ] **Step 3: 實作**

- `Account.requireUsername`：先 `normalize`，null 或不符合 `USERNAME` 就丟 `ApiException(BAD_REQUEST, <帳號規則>)`。`requirePassword(username, password)`：password 是 null 或 `codePointCount < 12`、UTF-8 bytes > 72、`equalsIgnoreCase(username)`，各丟對應的 400（訊息見 Global Constraints）。
- `AccountRepository.setPassword`：`update account set password_hash = :h, sessions_valid_after = :t where username = :u`。
- `AccountService`（`@Service`，建構子收 `AccountRepository`、`PasswordEncoder`）的 `changeOwnPassword`：順序見「實作上的決定」第 8 點，最後 `setPassword(username, encoder.encode(password), Instant.now())`。
- `AuthController`：`record PasswordChange(String current, String password)`；`POST /password` 加 `@ResponseStatus(NO_CONTENT)`。
- `AccountCheckFilter`：

```java
/**
 * 唯讀分享 spec 2.1、6.2：已登入的請求檢查帳號還在、還啟用、工作階段不早於 sessions_valid_after。
 * 不註冊成 bean：Boot 會把 Filter bean 再掛成一般的 servlet filter，跑在安全設定前面。由 SecurityConfig new 出來。
 * OncePerRequestFilter 預設跳過 ERROR dispatch。
 */
final class AccountCheckFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccountCheckFilter.class);
    private final AccountRepository accounts;
    private final ObjectMapper json;

    AccountCheckFilter(AccountRepository accounts, ObjectMapper json) {
        this.accounts = accounts;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(req, res);
            return;
        }
        // ponytail: 每個請求多查一次帳號（主鍵查詢）。真的變成瓶頸時，再在記憶體裡放幾秒
        Optional<Account> account;
        try {
            account = accounts.find(auth.getName());
        } catch (RuntimeException e) {
            log.error("帳號檢查查資料庫失敗", e);
            SecurityConfig.writeError(res, json, 500, "伺服器錯誤");
            return;
        }
        HttpSession session = req.getSession(false);
        boolean ok = account.isPresent() && account.get().enabled() && !(session != null
            && account.get().sessionsValidAfter() != null
            && Instant.ofEpochMilli(session.getCreationTime()).isBefore(account.get().sessionsValidAfter()));
        if (ok) {
            chain.doFilter(req, res);
            return;
        }
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityConfig.writeError(res, json, 401, "請先登入");
    }
}
```

- `SecurityConfig.securityFilterChain` 多收一個 `AccountRepository accounts`，鏈上加 `.addFilterAfter(new AccountCheckFilter(accounts, json), AnonymousAuthenticationFilter.class)`。

- [ ] **Step 4: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。既有的 controller 測試用的是 `TestData.clean` 種好的 u1、u2、u3，帳號檢查會過。

- [ ] **Step 5: Commit**

`feat(backend): 每個請求檢查帳號，自己改密碼後所有工作階段失效`

---

### Task B3: 後端：管理員的帳號管理 API

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/auth/Account.java`、`AccountRepository.java`、`AccountService.java`、`SecurityConfig.java`
- Create: `backend/src/main/java/jev/dashboard/auth/AccountController.java`
- Modify: `backend/src/test/java/jev/dashboard/TestData.java`（加 `asAdmin`）
- Test: Create `backend/src/test/java/jev/dashboard/auth/AccountControllerTest.java`

**Interfaces:**
- Produces:
  - `Account.View(String username, boolean admin, boolean enabled, boolean password, boolean sso, Instant createdAt)`（public record）、`static View Account.View.of(Account a)`（`password` = 有 `password_hash`，`sso` = 有 `oidc_subject`）
  - `AccountRepository.list(): List<Account>`（`order by username`）、`insert(String username, String passwordHash, boolean admin, String oidcIssuer, String oidcSubject): void`（主鍵重複時 JdbcClient 丟 `DuplicateKeyException`）、`setEnabled(String username, boolean enabled, Instant validAfter): boolean`、`setAdmin(String username, boolean admin, Instant validAfter): boolean`
  - `AccountService.create(String username, String password, boolean admin): Account`、`update(String self, String username, Boolean enabled, Boolean admin): Account`、`resetPassword(String self, String username, String password): void`
  - `GET /api/admin/accounts`、`POST /api/admin/accounts`（201）、`PATCH /api/admin/accounts/{username}`、`PUT /api/admin/accounts/{username}/password`（204），形狀照 spec 4.2
  - 測試：`TestData.asAdmin(String): RequestPostProcessor`（`user(username).roles("ADMIN")` 加 `csrf()`）
- Consumes: B2 的 `Account.requireUsername`、`requirePassword`、`AccountService`、`AccountCheckFilter`

- [ ] **Step 1: 寫失敗的測試（`AccountControllerTest`）**

`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`。`@BeforeEach` 先 `TestData.clean`，再 `TestData.addAccount(jdbc, "boss", true)`。管理員的請求用 `TestData.asAdmin("boss")`。

```java
@Test void nonAdminsGet403()
// as("u1") GET /api/admin/accounts → 403 {"error":"沒有權限"}；沒登入 → 401

@Test void listIsSortedAndShowsHowEachAccountLogsIn()
// 另外 insert sso-a（hash null、oidc_issuer/subject 有值）、both-a（hash 和 oidc 都有）
// GET → username 依序 boss、both-a、sso-a、u1、u2、u3；sso-a 的 password false、sso true；both-a 兩個都是 true；
// u1 的 admin false、enabled true、password true、sso false、createdAt 存在

@Test void createsAnAccountThatCanLogIn()
// POST {"username":" Alice ","password":"alice-password-1","admin":false} → 201、$.username == "alice"、$.password == true
// formLogin alice / alice-password-1 → 204

@Test void createRejectsDuplicatesAndBadNames()
// 再建一次 alice（大寫 "ALICE" 也算）→ 409 「帳號「alice」已經存在」
// ".."、"-boss"、"a"、"a".repeat(65)、"中文名" → 400 帳號規則的訊息

@Test void createChecksThePasswordRules()
// 11 個字、"營".repeat(25)、跟帳號一樣（username "long-account-1"、password "long-account-1"）→ 三種 400 訊息

@Test void adminsCannotLockThemselvesOut()
// PATCH boss {"enabled":false} → 400 「不能停用自己的帳號」；{"admin":false} → 400 「不能取消自己的管理員」
// PUT /boss/password → 400 「自己的密碼請用「改密碼」」
// PATCH /nobody {"enabled":false}、PUT /nobody/password → 404 「找不到帳號」

@Test void patchTogglesEnabledAndAdminAndReturnsTheAccount()
// PATCH u1 {"enabled":false} → $.enabled false；{"enabled":true} → true；{"admin":true} → $.admin true；{} → 200 不變

@Test void everyChangeEndsThatAccountsSessions()   // spec 8.1「工作階段失效」
// u1 formLogin 拿 s1；PATCH u1 {"enabled":false} → s1 打 me 401
// PATCH u1 {"enabled":true}；重新登入拿 s2；PATCH u1 {"admin":true} → s2 打 me 401
// 重新登入拿 s3（me.admin true）；PATCH u1 {"admin":false} → s3 打 /api/admin/accounts 401（不是 200，也不是 403）
// 重新登入拿 s4；PUT /u1/password {"password":"reset-password-1"} → 204；s4 打 me 401；
// 新密碼登入 204，舊密碼 401
// 每次用 TestData.PASSWORD 重新登入前都先 Thread.sleep(2)（見 B2「同一毫秒的問題」）

@Test void unchangedValuesKeepTheSessions()
// u1 登入拿 s；PATCH u1 {"enabled":true}（本來就是）、{"admin":false} → s 打 me 還是 200

@Test void ssoAccountsCanGetAPassword()
// insert sso-a（hash null）；PUT /sso-a/password {"password":"sso-password-12"} → 204
// formLogin sso-a 成功；GET 清單裡 sso-a 的 password、sso 都是 true
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=AccountControllerTest`
Expected: FAIL（`/api/admin/accounts` 回 404、一般帳號沒被擋）。

- [ ] **Step 3: 實作**

- `AccountRepository`：`setEnabled`、`setAdmin` 照 `setPassword` 的寫法，同時寫 `sessions_valid_after`。
- `AccountService`：
  - `create`：`requireUsername`、`requirePassword`，`insert(u, encoder.encode(password), admin, null, null)`。接 `DuplicateKeyException` 轉成 409。
  - `update`：照「實作上的決定」第 8 點，路徑上的名稱先 `Account.normalize`。
  - `resetPassword`：同上。
  - 三個都用 `Instant.now()`，名稱比對都用轉換後的名稱。
- `AccountController`（`@RestController @RequestMapping("/api/admin/accounts")`）：
  - `record NewAccount(String username, String password, boolean admin)`、`record Changes(Boolean enabled, Boolean admin)`、`record NewPassword(String password)`。
  - 回傳一律 `Account.View`。
  - 身分用 `Principal`。
  - 不用 `@PreAuthorize`：`ApiExceptionHandler` 的 catch-all 會把 `AccessDeniedException` 變成 500（spec 6.2）。
- `SecurityConfig`：在 `/api/**` 那條規則前面加 `.requestMatchers("/api/admin/**").hasRole("ADMIN")`。
- `TestData.asAdmin`。

- [ ] **Step 4: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(backend): 管理員的帳號管理 API，停用、改權限、重設密碼後對方要重新登入`

---

### Task B4: 後端：設定、啟動時建立第一個管理員、登入選項

**Files:**
- Modify: `backend/src/main/resources/application.yml`、`backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/java/jev/dashboard/auth/AuthProperties.java`、`AdminBootstrap.java`
- Modify: `backend/src/main/java/jev/dashboard/auth/AccountRepository.java`、`AuthController.java`、`SecurityConfig.java`
- Test: Create `backend/src/test/java/jev/dashboard/auth/AdminBootstrapTest.java`；`AuthControllerTest.java` 加一個測試

**Interfaces:**
- Produces:
  - `AuthProperties(String adminUsername, String adminPassword, Oidc oidc)`、`AuthProperties.Oidc(String issuerUri, String clientId, String clientSecret, String label)`，`Oidc.enabled()` 是 `StringUtils.hasText(issuerUri) && StringUtils.hasText(clientId)`（`@ConfigurationProperties("jev.auth")`，既有的 `@ConfigurationPropertiesScan` 會掃到）
  - `AdminBootstrap(AuthProperties props, AccountRepository accounts, PasswordEncoder encoder)`（`@Component`，`implements ApplicationRunner`，建構子 package-private）
  - `AccountRepository.anyEnabledAdmin(): boolean`
  - `GET /api/auth/options`（不用登入）→ `{"oidc": null}` 或 `{"oidc": {"label": "…"}}`
- Consumes: B3 的 `AccountRepository.insert`；B2 的 `Account.requireUsername`、`requirePassword`

- [ ] **Step 1: 設定檔**

- `application.yml` 的 `server:`、`jev:` 各自併進既有的區塊（YAML 不允許重複 key）。`server.tomcat` B1 已經加了。`jev:` 下面照 spec 第 3 節加 `auth:` 整段，`label` 的預設值是 `公司帳號`。
- `application-test.yml` 的 `jev:` 區塊加：

  ```yaml
    auth:
      admin-username: test-admin
      admin-password: test-admin-password
      oidc:
        issuer-uri: ""
        label: 公司帳號   # 開發者的 .env 有 OIDC_LABEL 時，OidcLoginTest 的按鈕文字才不會跟著變
  ```

- [ ] **Step 2: 寫失敗的測試**

`AdminBootstrapTest`（`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`，`@BeforeEach` 呼叫 `TestData.clean`，注入 `AccountRepository`、`PasswordEncoder`，每個測試自己 `new AdminBootstrap(new AuthProperties(...), accounts, encoder).run(null)`）：

```java
@Test void createsAnEnabledAdminWhenTheAccountIsMissing()
// ("Boss", "boss-password-1", null) → account boss 存在、admin、enabled；formLogin boss / boss-password-1 → 204

@Test void leavesAnExistingAccountAlone()
// addAccount("boss", true)；("boss", "another-password-1", null) → 不丟例外；password_hash 還是 TestData.PASSWORD_HASH，
// formLogin boss / PASSWORD → 204；("boss", null, null) 也不丟例外；停用的 boss、不是管理員的 boss 也不丟例外、不改任何欄位

@Test void doesNothingWithoutAUsername()
// ("", "", null)、(null, null, null) → 不丟例外，account 表只有 u1～u3

@Test void failsOnABadUsernameOrPassword()
// assertThrows(IllegalStateException)：("-boss", "boss-password-1")、("boss", null)、("boss", "short")、
// ("boss", "has space 12345")、("boss", "密碼密碼密碼密碼密碼密碼密碼")、("long-account-1", "long-account-1")
// 每個都沒有建立帳號
```

`AuthControllerTest` 加：

```java
@Test void optionsNeedNoLoginAndHaveNoOidcByDefault()
// GET /api/auth/options（不帶身分）→ 200、content().json("{\"oidc\":null}", true)
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest='AdminBootstrapTest,AuthControllerTest'`
Expected: 編譯失敗（`AdminBootstrap`、`AuthProperties` 不存在）。

- [ ] **Step 4: 實作**

- `AdminBootstrap.run`（spec 2.1；訊息見「實作上的決定」第 9 點，log 的字見 Global Constraints）：
  1. `adminUsername` 沒有字：資料庫裡沒有啟用中的管理員時寫 WARN，然後結束。
  2. `Account.requireUsername`，丟 `ApiException` 就轉成 `IllegalStateException`。
  3. 帳號已經存在：啟用中而且是管理員寫 INFO，否則寫 WARN，然後結束。不看密碼。
  4. 密碼是 null 或空字串、有 0x21～0x7E 以外的字元、不合 `requirePassword`：丟 `IllegalStateException`。
  5. `insert(username, encoder.encode(password), true, null, null)`，寫 INFO。
- `AccountRepository.anyEnabledAdmin`：`select exists(select 1 from account where admin and enabled)`。
- `AuthController`：收 `AuthProperties`；`record Options(OidcOption oidc)`、`record OidcOption(String label)`；`GET /options` 在 `props.oidc() != null && props.oidc().enabled()` 時回 label，否則 `new Options(null)`。
- `SecurityConfig`：規則最前面加 `.requestMatchers("/api/auth/options").permitAll()`（排在 `dispatcherTypeMatchers` 後面）。

- [ ] **Step 5: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。每個 context 啟動時都會建立或略過 `test-admin`，`TestData.clean` 會清掉它，不影響其他測試。

- [ ] **Step 6: Commit**

`feat(backend): 啟動時建立第一個管理員，加上登入選項 API`

---

### Task B5: 後端：OIDC 單一登入

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/auth/SecurityConfig.java`、`AccountService.java`、`AccountRepository.java`
- Create: `backend/src/test/java/jev/dashboard/FakeOidcServer.java`
- Test: Create `backend/src/test/java/jev/dashboard/auth/OidcLoginTest.java`

**Interfaces:**
- Produces:
  - `AccountRepository.findByOidc(String issuer, String subject): Optional<Account>`、`hasLegacyData(String username): boolean`（`select exists(select 1 from dashboard where created_by = :u) or exists(select 1 from datasource where created_by = :u)`）
  - `AccountService.forOidc(String issuer, String subject, String rawUsername): Account`（擋下時丟 `OAuth2AuthenticationException(new OAuth2Error(<代碼>))`）
  - 啟用 OIDC 時：`GET /oauth2/authorization/oidc` → 302 到 IdP；`GET /login/oauth2/code/oidc` → 302 到 `/` 或 `/?login_error=<代碼>`
  - 測試：`FakeOidcServer.start()`、`issuer()`、`code(String nonce, Map<String, Object> claims): String`、`close()`、`CLIENT_ID`
- Consumes: B4 的 `AuthProperties`、B3 的 `AccountRepository.insert`

- [ ] **Step 1: 寫 `FakeOidcServer`**

照 `FakeJevServer` 的寫法：JDK `HttpServer` 綁 `127.0.0.1:0`，nimbus（`spring-boot-starter-oauth2-client` 帶進來的 `nimbus-jose-jwt`）簽 RS256。

```java
/** 測試用的 OIDC IdP。discovery 不給 userinfo_endpoint，OidcUserService 就不會去打 userinfo。 */
public final class FakeOidcServer implements AutoCloseable {
    public static final String CLIENT_ID = "jev-test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Grant(String nonce, Map<String, Object> claims) {}

    private final HttpServer server;
    private final RSAKey key;                                   // new RSAKeyGenerator(2048).keyID("k1").generate()
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public static FakeOidcServer start() { /* HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)、createContext("/", this::handle)、start() */ }
    public String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** nonce 照 authorize 網址上的原樣（那已經是 Spring 算好的雜湊）；claims 至少要有 sub。 */
    public String code(String nonce, Map<String, Object> claims) { /* UUID 當 code，放進 grants */ }

    private void handle(HttpExchange ex) throws IOException {
        // "/.well-known/openid-configuration" → issuer（跟設定一字不差）、authorization_endpoint（issuer + "/authorize"）、
        //   token_endpoint（+ "/token"）、jwks_uri（+ "/jwks"）、response_types_supported ["code"]、
        //   subject_types_supported ["public"]、id_token_signing_alg_values_supported ["RS256"]
        // "/jwks" → new JWKSet(key.toPublicJWK()).toJSONObject()
        // "/token" → 從 form body 取 code，grants.remove(code)；
        //   id_token 的 claims：iss = issuer()、aud = CLIENT_ID、iat = 現在、exp = 現在 + 5 分鐘、nonce、再加上登記的 claims；
        //   new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims).sign(new RSASSASigner(key))
        //   → {"access_token":"at","token_type":"Bearer","expires_in":300,"id_token":jwt.serialize()}
        // 其他路徑、找不到的 code → 404。回應都是 application/json。
    }

    @Override public void close() { server.stop(0); }
}
```

- [ ] **Step 2: 寫失敗的測試（`OidcLoginTest`）**

```java
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class OidcLoginTest {
    static final FakeOidcServer OIDC = FakeOidcServer.start();

    @DynamicPropertySource static void oidc(DynamicPropertyRegistry r) {
        r.add("jev.auth.oidc.issuer-uri", OIDC::issuer);
        r.add("jev.auth.oidc.client-id", () -> FakeOidcServer.CLIENT_ID);
        r.add("jev.auth.oidc.client-secret", () -> "test-secret"); // 有 secret 也要有 PKCE（requireProofKey）
    }
    @AfterAll static void stop() { OIDC.close(); }
    // @BeforeEach TestData.clean
```

跑一次登入的 helper（spec 8.1 的五步）：

```java
MockHttpSession login(Map<String, Object> claims, String expectedRedirect) throws Exception {
    MvcResult start = mvc.perform(get("/oauth2/authorization/oidc")).andExpect(status().isFound()).andReturn();
    MultiValueMap<String, String> q = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
        .build().getQueryParams();
    String state = URLDecoder.decode(q.getFirst("state"), UTF_8);   // state 有 base64 的 =，網址上是 %3D
    String code = OIDC.code(URLDecoder.decode(q.getFirst("nonce"), UTF_8), claims);
    MvcResult back = mvc.perform(get("/login/oauth2/code/oidc").param("code", code).param("state", state)
            .session((MockHttpSession) start.getRequest().getSession(false)))
        .andExpect(redirectedUrl(expectedRedirect)).andReturn();
    return (MockHttpSession) back.getRequest().getSession(false); // 登入時換了新的 session
}
```

```java
@Test void optionsShowTheButtonLabel()
// GET /api/auth/options → {"oidc":{"label":"公司帳號"}}（label 在 application-test.yml 固定，不看 .env）

@Test void issuerMustBeHttpsOrLoopback()   // 純單元測試，不用 mvc
// SecurityConfig.requireSecureIssuer：
// "https://idp.example"、"http://127.0.0.1:9000" 不丟例外；
// "http://idp.example"、"http://127.0.0.1.evil.example"、"ftp://127.0.0.1" → IllegalStateException

@Test void theAuthorizeUrlUsesPkceAndTheOidcScopes()
// GET /oauth2/authorization/oidc 的 Location：以 issuer + "/authorize" 開頭，有 code_challenge、code_challenge_method=S256、
// scope 含 openid、profile、email、有 nonce

@Test void firstLoginCreatesAnAccountAndTheSameSubjectKeepsIt()
// claims {sub:"s-1", preferred_username:" Alice@Corp "} → redirectedUrl "/"；帶回來的 session 打 me →
// {"username":"alice@corp","admin":false,"password":false}；account 的 oidc_issuer = issuer()、oidc_subject = "s-1"
// 同一個 sub、preferred_username 換成 "renamed" 再登入一次 → me.username 還是 "alice@corp"，account 只多一筆

@Test void aVerifiedEmailIsUsedWhenThereIsNoPreferredUsername()
// {sub:"s-2", email:"bob@corp", email_verified:true} → "/"，me.username == "bob@corp"

@Test void blockedLogins()
// 1. addAccount("carol")；{sub:"s-3", preferred_username:"carol"} → "/?login_error=username_taken"
// 2. SQL 寫一筆 created_by = 'dave' 的儀表板；{sub:"s-4", preferred_username:"dave"} → "/?login_error=username_taken"
// 3. {sub:"s-5", preferred_username:"erin"} 先登入成功；update account set enabled = false where username = 'erin'；
//    同一個 sub 再登入 → "/?login_error=account_disabled"
// 4. {sub:"s-6", email:"frank@corp", email_verified:false} → "/?login_error=no_username"；
//    {sub:"s-7", email:"gina@corp"}（沒有 email_verified）→ no_username；{sub:"s-8", preferred_username:"-bad"} → no_username
// 擋下來的都沒有建立帳號
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest=OidcLoginTest`
Expected: 編譯失敗（`SecurityConfig.requireSecureIssuer` 不存在）。實作之前的行為：`/oauth2/authorization/oidc` 回 404「找不到這個 API」，`optionsShowTheButtonLabel` 已經會過（B4 的 options 在這個 context 會回 label）。

- [ ] **Step 4: 實作**

- `AccountService.forOidc`（spec 2.3 帳號對應）：
  1. `findByOidc` 找到：停用就丟 `account_disabled`，否則回傳。
  2. `Account.normalize(rawUsername)` 是 null 或不符合 `Account.USERNAME`：丟 `no_username`。
  3. `find(u)` 有，或 `hasLegacyData(u)`：丟 `username_taken`。
  4. `insert(u, null, false, issuer, subject)`，回傳新帳號。
- `SecurityConfig.securityFilterChain` 多收 `AuthProperties props`、`AccountService accountService`。`props.oidc() != null && props.oidc().enabled()` 時才呼叫 `oauth2Login`：

```java
AuthProperties.Oidc oidc = props.oidc();
requireSecureIssuer(oidc.issuerUri());
http.oauth2Login(o -> o
    .clientRegistrationRepository(lazyRegistration(oidc))
    .userInfoEndpoint(u -> u
        .oidcUserService(localAccounts(accountService))
        .userService(r -> { throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request")); })) // 只收 OIDC
    .defaultSuccessUrl("/", true)
    .failureHandler((req, res, e) -> res.sendRedirect("/?login_error=" + URLEncoder.encode(
        e instanceof OAuth2AuthenticationException o2 ? o2.getError().getErrorCode() : "failed", UTF_8))));
```

```java
/** spec 2.3：issuer 要用 https，本機測試才放行 http://127.0.0.1。看解析後的 scheme 和 host，不比字串開頭。 */
static void requireSecureIssuer(String issuer) {
    URI u = URI.create(issuer);
    boolean ok = "https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost()));
    if (!ok) throw new IllegalStateException("OIDC_ISSUER_URI 要用 https（本機測試才用 http://127.0.0.1）：" + issuer);
}

/**
 * spec 2.3：第一次有人按按鈕才去讀 issuer 的 discovery，讀到就記住；IdP 掛掉時帳密登入和啟動都不受影響。
 * ponytail: IdP 掛掉時按按鈕會看到 Spring 的錯誤頁，下次再按會重試；真的常發生，再自己接 OAuth2AuthorizationRequestRedirectFilter 的失敗處理
 */
static ClientRegistrationRepository lazyRegistration(AuthProperties.Oidc p) {
    AtomicReference<ClientRegistration> cached = new AtomicReference<>();
    return id -> {
        if (!"oidc".equals(id)) return null;
        ClientRegistration r = cached.get();
        if (r == null) {
            boolean secret = StringUtils.hasText(p.clientSecret());
            ClientRegistration.Builder b = ClientRegistrations.fromIssuerLocation(p.issuerUri()) // 會檢查 issuer 一字不差
                .registrationId("oidc")
                .clientId(p.clientId())
                .clientAuthenticationMethod(secret ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC : ClientAuthenticationMethod.NONE)
                .scope("openid", "profile", "email")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()); // 6.5 才有
            if (secret) b.clientSecret(p.clientSecret());
            r = b.build(); // redirectUri 預設 {baseUrl}/{action}/oauth2/code/{registrationId}
            cached.set(r);
        }
        return r;
    };
}

/** spec 2.3：包在 OidcUserService 外面，對應到本機帳號；name attribute 是本機帳號名稱，Principal.getName() 才跟帳密登入一致。 */
static OAuth2UserService<OidcUserRequest, OidcUser> localAccounts(AccountService accounts) {
    OidcUserService delegate = new OidcUserService();
    return req -> {
        OidcUser idp = delegate.loadUser(req); // 驗 id_token；沒有 userinfo_endpoint 就不打 userinfo
        String username = StringUtils.hasText(idp.getPreferredUsername()) ? idp.getPreferredUsername()
            : Boolean.TRUE.equals(idp.getEmailVerified()) ? idp.getEmail() : null;
        Account a = accounts.forOidc(idp.getIssuer().toString(), idp.getSubject(), username);
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList(a.admin() ? "ROLE_ADMIN" : "ROLE_USER"),
            idp.getIdToken(), new OidcUserInfo(Map.of("jev_username", a.username())), "jev_username");
    };
}
```

查過 6.5.11 的原始碼：
- `ClientRegistration.ClientSettings`（`@since 6.5`）有 `builder().requireProofKey(boolean)`，`ClientRegistration.Builder.clientSettings(...)` 也有。
- `DefaultOAuth2AuthorizationRequestResolver` 在 `NONE` 或 `isRequireProofKey()` 時加 PKCE，網址上的 `nonce` 是雜湊過的值。
- `OAuth2LoginConfigurer` 遇到不是 `Iterable` 的 repository 時，登入連結是空的，啟動時不會呼叫 `findByRegistrationId`。

- [ ] **Step 5: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。預設 context 的 `optionsNeedNoLoginAndHaveNoOidcByDefault` 照舊是 `{"oidc":null}`。

- [ ] **Step 6: Commit**

`feat(backend): OIDC 單一登入，第一次登入自動建帳號，同名帳號或舊資料擋下`

---

### Task B6: 後端：分享設定和「分享給我的」

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`DashboardService.java`、`DashboardController.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Produces:
  - `DashboardRepository.Share(boolean publicLink, List<String> users)`、`DashboardRepository.SharedDashboard(UUID id, String name, String owner, OffsetDateTime updatedAt)`（public record）
  - `DashboardRepository.share(UUID id): Share`、`setShare(UUID id, Share s): void`、`sharedWith(String userId): List<SharedDashboard>`
  - `DashboardService.share(String userId, UUID id): Share`、`setShare(String userId, UUID id, Share s): Share`（`@Transactional`）、`shared(String userId): List<SharedDashboard>`
  - `GET /api/dashboards/{id}/share`、`PUT /api/dashboards/{id}/share`、`GET /api/dashboards/shared`，形狀照 spec 4.3
- Consumes: B1 的 `AccountRepository.find`、`Account.normalize`（`DashboardService` 的建構子多收 `AccountRepository`，沒有測試直接 `new DashboardService`，已經 grep 過）

- [ ] **Step 1: 寫失敗的測試（`DashboardControllerTest`，放在 `crudIsScopedToTheOwner` 後面）**

```java
@Test void theOwnerSharesAndReadsTheShareBack()
// u1 建儀表板；GET /{id}/share → {"publicLink":false,"users":[]}
// PUT {"publicLink":true,"users":[" U3 ","u2","u2",null,"  "]} → {"publicLink":true,"users":["u2","u3"]}；GET 回一樣
// PUT {"publicLink":false}（沒有 users）→ 200 {"publicLink":false,"users":[]}，不是 500

@Test void sharingRejectsUnknownAccountsAndYourself()
// users ["Nobody"] → 400 「找不到帳號「nobody」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結」
// users ["U1"] → 400 「不用分享給自己」
// 停用 u3（SQL）之後 users ["u3"] → 200（停用的帳號可以加）

@Test void onlyTheOwnerSeesOrChangesTheShare()
// u1 分享給 u2 之後：u2 GET /{id}/share、PUT /{id}/share → 404 「找不到儀表板」；u1 的分享沒變

@Test void sharedListsNamedSharesNewestFirstAndSkipsDisabledOwners()
// u1 依序建 A、B，都分享給 u2；u3 建 C，只開公開連結
// u2 GET /api/dashboards/shared → [B, A]，每筆有 id、name、owner "u1"、updatedAt；C 不在裡面
// u2 GET /api/dashboards 還是空的
// update account set enabled = false where username = 'u1' → u2 GET /shared 是 []

@Test void deletingADashboardDeletesItsShares()
// 分享給 u2、u3 → DELETE /{id} → select count(*) from dashboard_share 是 0
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=DashboardControllerTest`
Expected: 新測試 FAIL（`/share`、`/shared` 不存在；`/shared` 被當成 `{id}` 回 400「參數格式錯誤：id」），既有測試照舊 PASS。

- [ ] **Step 3: 實作**

- `DashboardRepository`：
  - `share`：讀 `public_link` 和 `select username from dashboard_share where dashboard_id = :id order by username`。
  - `setShare`：`update dashboard set public_link = :p where id = :id`（不動 `updated_at`）、刪掉這份的分享、逐筆 insert。
  - `sharedWith`：

    ```sql
    select d.id, d.name, d.created_by, d.updated_at
    from dashboard d
    join dashboard_share s on s.dashboard_id = d.id and s.username = :by
    join account o on o.username = d.created_by and o.enabled
    order by d.updated_at desc
    ```

- `DashboardService`：
  - `share`、`setShare` 先 `get(userId, id)`（只認擁有者，別人 404）。
  - `setShare` 照「實作上的決定」第 12 點整理 `users`，再檢查自己（400）、帳號不存在（400）。
  - `setShare` 回傳 `repo.share(id)`。
- `DashboardController`：三支都用 `Principal`。`@GetMapping("/shared")` 是固定字串，Spring 會優先配對它，不會被 `/{id}` 吃掉。

- [ ] **Step 4: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(backend): 儀表板的分享設定和「分享給我的」清單`

---

### Task B7: 後端：被分享的人和公開連結唯讀打開儀表板

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`DashboardService.java`、`DashboardController.java`
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`、`jev/JevControllerTest.java`

**Interfaces:**
- Produces:
  - `DashboardRepository.findViewable(UUID id, String userId): Optional<DashboardConfig>`
  - `DashboardService.view(String userId, UUID id): DashboardConfig`（找不到 404「找不到儀表板」；`get(userId, id)` 照舊只認擁有者）
  - `GET /api/dashboards/{id}`、`/{id}/data`、`GET /api/jev/highlight/{id}` 照 spec 4.5 開放給檢視者
- Consumes: B6 的 `PUT /{id}/share`（測試用）

- [ ] **Step 1: 寫失敗的測試**

`DashboardControllerTest`（放在 B6 的分享測試旁邊）：

```java
@Test void aViewerReadsTheDashboardAndItsDataAsTheOwner()
// u1 上傳 sales、建儀表板、分享給 u2
// u2 GET /{id} → 200、$.createdBy == "u1"；GET /{id}/data → 200、$.length() == 4、$[0].error 不存在（不是 404「找不到資料源」）

@Test void aViewerCannotChangeAnything()
// 分享給 u2；u2 PUT /{id}、DELETE /{id}、POST /{id}/charts、GET /{id}/share、PUT /{id}/share → 404 「找不到儀表板」
// u1 GET /{id} 的 layout 沒變；u2 GET /api/datasources 是空的

@Test void notSharedIs404AndThePublicLinkOpensItToAnyAccount()
// u3 GET /{id}、/data → 404；u1 PUT {"publicLink":true,"users":[]} → u3 GET /{id}、/data → 200；u3 GET /shared 是 []

@Test void revokingOrDisablingTheOwnerMakesIt404Again()
// 分享給 u2 → 200；PUT users [] → u2 GET /{id} 404；再分享給 u2 → 200；
// update account set enabled = false where username = 'u1' → u2 GET /{id}、/data 都是 404

@Test void aViewerSeesAGenericMessageWhenTheOwnersSourcesFail()
// SQL 寫一個 u1 的外部 API 來源：kind 'api'、schema_json '[]'、api_url 'http://10.0.0.5/sales'、
// api_headers_json '{"Authorization":"${DS_JEV_TEST_UNSET}"}'（測試的允許清單有 10.0.0.5；抓之前就因為環境變數不存在而失敗，不會連網路）
// u1 用 TestData.dashboardJson(這個來源) 建儀表板、分享給 u2
// u1 GET /data → 500 「環境變數 DS_JEV_TEST_UNSET 未設定」；u2 GET /data → 500 「資料來源載入失敗，請聯絡儀表板擁有者」
// GET /api/jev/highlight/{id} 也一樣：u1 看到原本的訊息，u2 看到通用訊息（抓來源就失敗，不會打 JEV）
```

`JevControllerTest`（放在 `highlightFlagsAnomalousCardsAndCachesTheResult` 後面，資料照它的寫法）：

```java
@Test void aViewersHighlightUsesTheOwnersSources()
// u1 建儀表板、分享給 u2；u2 GET /api/jev/highlight/{id} → 200、$.length() == 1、$[0].chartId == "c1"、$[0].severity == "需要關注"
// u3 → 404 「找不到儀表板」
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest='DashboardControllerTest,JevControllerTest'`
Expected: 新測試 FAIL（檢視者拿到 404），既有測試照舊 PASS。

- [ ] **Step 3: 實作**

- `DashboardRepository.findViewable`（見「實作上的決定」第 12 點）：

  ```sql
  select d.* from dashboard d
  join account o on o.username = d.created_by and o.enabled
  where d.id = :id
    and (d.created_by = :by or d.public_link
         or exists (select 1 from dashboard_share s where s.dashboard_id = d.id and s.username = :by))
  ```

- `DashboardController`：

```java
@GetMapping("/{id}")
public DashboardConfig get(Principal principal, @PathVariable UUID id) {
    return service.view(principal.getName(), id);
}

/** 唯讀分享 spec 4.5：用擁有者的身分載入；檢視者看到的錯誤換成通用訊息，不露出擁有者那邊的細節。 */
@GetMapping("/{id}/data")
public List<ChartData> data(Principal principal, @PathVariable UUID id) {
    DashboardConfig c = service.view(principal.getName(), id);
    try {
        return data.display(c.createdBy(), c);
    } catch (ApiException e) {
        if (c.createdBy().equals(principal.getName())) throw e;
        throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
    }
}
```

- `JevController.highlight`：
  - `dashboards.get` 換成 `dashboards.view`；`String owner = c.createdBy();`，`datasources.list(...)` 和 `dashboardData.load(...)` 改用 `owner`。註解寫「唯讀分享 spec 6.3：敏感欄位和資料都照擁有者的來源；recent 的 key 跟 /data 一樣是擁有者」。
  - `dashboardData.load(owner, …)` 包一層跟 `/data` 一樣的 catch（「實作上的決定」第 12 點）：

    ```java
    List<ChartData> data;
    try {
        data = dashboardData.load(owner, new DashboardConfig(c.id(), c.name(), c.createdBy(), eligible));
    } catch (ApiException e) {
        if (owner.equals(principal.getName())) throw e;
        throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
    }
    ```
- `DashboardDataService` 不改。

- [ ] **Step 4: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(backend): 被分享的人和公開連結可以唯讀打開儀表板，資料用擁有者的身分載入`

---

### Task F1: 前端：api.js 改用工作階段和 CSRF，加上新的 API

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`、`index.js`、`frontend/vite.config.js`
- Test: `frontend/src/modules/dashboard/api.test.js`

**Interfaces:**
- Produces（全部照 spec 4 的路徑和形狀）：
  - `api.me()` → GET `/api/auth/me`；`api.authOptions()` → GET `/api/auth/options`
  - `api.login(username, password)` → POST `/api/auth/login`，body 是 `new URLSearchParams({ username, password })`；`api.logout()` → POST `/api/auth/logout`
  - `api.changePassword(current, password)` → POST `/api/auth/password` `{ current, password }`
  - `api.listAccounts()` → GET `/api/admin/accounts`；`api.createAccount({ username, password, admin })` → POST；`api.updateAccount(username, patch)` → PATCH `/api/admin/accounts/${encodeURIComponent(username)}`；`api.setAccountPassword(username, password)` → PUT `…/${encodeURIComponent(username)}/password` `{ password }`
  - `api.getShare(id)` → GET `/api/dashboards/${id}/share`；`api.setShare(id, { publicLink, users })` → PUT；`api.sharedDashboards()` → GET `/api/dashboards/shared`
  - `onUnauthorized(handler)`（從 `api.js` 匯出，`index.js` 也轉出去）
- Consumes: spec 4 的 API 契約

- [ ] **Step 0: 準備 Track F 的 worktree**

worktree 是 `C:/GitHub/jev-form-dashboard/.claude/worktrees/auth-sharing-fe`（主 session 用 `git worktree add -b feat/auth-sharing-fe .claude/worktrees/auth-sharing-fe <加入 plan 的 commit>` 建好）。`frontend/node_modules` 不在 git 裡，新的 worktree 沒有，先在 `frontend/` 跑 `npm ci`（要連 npm registry）。沒裝好的話，`npx vitest` 會跑去下載 vitest。

- [ ] **Step 1: 寫失敗的測試（`api.test.js`）**

第 17 行的 `'sends X-User-Id: demo-user …'` 換成下面第一個。`afterEach` 加 `vi.unstubAllGlobals()` 和 `onUnauthorized(() => {})`。

```js
it('非 GET 請求從 cookie 帶 X-XSRF-TOKEN，GET 不帶，也不再有 X-User-Id')
// vi.stubGlobal('document', { cookie: 'a=1; XSRF-TOKEN=t%3D1' })
// listDatasources 的 headers 沒有 X-XSRF-TOKEN、沒有 X-User-Id；analyze('ds1') 的 headers['X-XSRF-TOKEN'] === 't=1'

it('沒有 document 的時候（vitest 的 node 環境）照樣送出，不帶 token')

it('401 先呼叫 onUnauthorized，再丟「登入逾時，重新登入後請再操作一次」，status 是 401')
// fetch 回 jsonResponse({ error: '請先登入' }, { status: 401 })；listDashboards() reject，message 一字不差；handler 呼叫一次

it('/api/auth/login、/api/auth/me 的 401 不呼叫 onUnauthorized，訊息照後端')
// login 回 { error: '帳號或密碼錯誤' }、me 回 { error: '請先登入' }：handler 沒被呼叫，message 是後端的字

it('login 用 URLSearchParams 原樣送出，不設 Content-Type')
// init.body instanceof URLSearchParams、init.body.toString() === 'username=u1&password=pw'、headers['Content-Type'] 是 undefined

it('帳號和分享的 API 用對 method 和網址')
// 逐一呼叫 F1 Interfaces 列的函式，檢查 method、URL、body；updateAccount('a@b', { admin: true }) 的網址是 /api/admin/accounts/a%40b
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/dashboard/api.test.js`
Expected: 新測試 FAIL，其他既有測試照舊 PASS。

- [ ] **Step 3: 實作**

`api.js` 的 `request()`（spec 7.10）：

```js
// 唯讀分享 spec 7.10：401 時通知 App 跳出重新登入；登入、開機的 me 自己處理 401
const SELF_HANDLED_401 = ['/api/auth/login', '/api/auth/me']
let unauthorized = () => {}
export function onUnauthorized(handler) {
  unauthorized = handler
}

function xsrfToken() {
  if (typeof document === 'undefined') return null // vitest 的 node 環境沒有 document
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return m ? decodeURIComponent(m[1]) : null
}

async function request(method, url, body) {
  // FormData、URLSearchParams 原樣送出，瀏覽器自己設 Content-Type
  const raw = body instanceof FormData || body instanceof URLSearchParams
  const headers = {}
  if (body != null && !raw) headers['Content-Type'] = 'application/json'
  // 每次都重讀：登入時 token 會換
  const token = method === 'GET' ? null : xsrfToken()
  if (token) headers['X-XSRF-TOKEN'] = token
  // fetch 照舊，body: body == null ? undefined : raw ? body : JSON.stringify(body)
  if (!res.ok) {
    if (res.status === 401 && !SELF_HANDLED_401.includes(url)) {
      unauthorized()
      throw Object.assign(new Error('登入逾時，重新登入後請再操作一次'), { status: 401 })
    }
    // 其餘照舊
  }
}
```

刪掉第 1 行的 `USER_ID`。`index.js` 只改第 5 行：`export { api } from './api.js'` 換成 `export { api, onUnauthorized } from './api.js'`，其他五行（`DashboardView`、`ChartCard`、`DatasourcePanel`、`ChartBuilder`、`toOption`）不動，App 還要從這裡拿元件。

`vite.config.js` 的 proxy（spec 2.3）：

```js
proxy: {
  '/api': 'http://127.0.0.1:8080',
  // 唯讀分享 spec 2.3：單一登入的路徑不改 Host，後端算出來的 redirect URI 才是瀏覽器看到的網址
  '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
  '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
},
```

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`，然後 `npm run build`
Expected: 全部 PASS，build 成功。

- [ ] **Step 5: Commit**

`feat(frontend): api.js 改用登入工作階段和 CSRF token，加上帳號與分享的 API`

---

### Task F2: 前端：登入頁

**Files:**
- Create: `frontend/src/modules/auth/session.js`、`LoginForm.vue`、`LoginPage.vue`、`index.js`
- Test: Create `frontend/src/modules/auth/session.test.js`、`LoginPage.test.js`

**Interfaces:**
- Produces:
  - `session.js`：
    - `DASHBOARD_HASH`（`/^#\/d\/([0-9a-f-]{36})$/`）
    - `restoreSavedHash()`：開機第 1 步，一律刪掉 `jev-return-hash`，網址沒有 hash 時才補回去
    - `startOidc()`：網址是合法的 `#/d/<id>` 就存進 `sessionStorage`，再 `location.href = '/oauth2/authorization/oidc'`
    - `resetAndReload()`：刪掉存的那筆，`history.replaceState(null, '', location.pathname)`，再 `location.reload()`
    - `oidcButtonText(label)`
    - `takeLoginError()`：讀 `?login_error=`，用 `replaceState` 拿掉參數、留著 hash，回傳要顯示的訊息，沒有參數回 null
  - `LoginForm.vue`：props `username`（預填）、`oidc`（`{ label }` 或 null）；emits `done`（`api.login` 成功後）
  - `LoginPage.vue`：props `error`（開機失敗的訊息，沒有是 null）、`isLight`；emits `logged-in`、`retry`、`toggle-theme`
  - `index.js`：`export { default as LoginPage } from './LoginPage.vue'`
- Consumes: F1 的 `api.login`、`api.authOptions`

- [ ] **Step 1: 寫失敗的測試**

`session.test.js`（`// @vitest-environment happy-dom`；`afterEach` 用 `history.replaceState(null, '', '/')` 還原網址、`sessionStorage.clear()`、`vi.unstubAllGlobals()`、`vi.restoreAllMocks()`）：

```js
it('按鈕文字：label 開頭或結尾是英數字時那一側加空格')
// '公司帳號' → '用公司帳號登入'；'Microsoft' → '用 Microsoft 登入'；'Entra 帳號' → '用 Entra 帳號登入'；'帳號 SSO' → '用帳號 SSO 登入'

it('login_error 對到訊息，拿掉參數之後 hash 還在')
// history.replaceState(null, '', '/?login_error=username_taken#/d/<uuid>') → '這個帳號名稱已經有人用，不能再用單一登入建立，請聯絡管理員'；
// location.search === ''、location.hash === '#/d/<uuid>'
// account_disabled → '帳號已停用，請聯絡管理員'；no_username → '單一登入沒有提供帳號名稱，請聯絡管理員'；
// 'whatever' → '單一登入失敗，請再試一次'；沒有參數 → null

it('restoreSavedHash：網址沒有 hash 才補回去，存的那筆一律刪掉')

it('startOidc：先存合法的 #/d/<id> 再導過去；不合法的不存')
// vi.stubGlobal('location', { hash: '#/d/<uuid>', href: '' }) → sessionStorage 有那筆、location.href === '/oauth2/authorization/oidc'

it('resetAndReload：拿掉 hash 和存的那筆，再重新載入')
// vi.spyOn(window.location, 'reload').mockImplementation(() => {})
```

`LoginPage.test.js`（第一行 `// @vitest-environment happy-dom`，之後每個元件測試檔都一樣，見 Global Constraints；`vi.mock('../dashboard/api.js', () => ({ api: { login: vi.fn(), authOptions: vi.fn() } }))`；掛載時 `global: { plugins: [vuetify] }`，`vuetify` 從 `../dashboard/test-vuetify.js` 拿）：

```js
it('有「JEV 儀表板」、帳號、密碼和「登入」；按登入呼叫 api.login，成功 emit logged-in')
// 帳號 autocomplete="username"、密碼 autocomplete="current-password"
it('登入失敗直接顯示後端的訊息')
// login reject new Error('帳號或密碼錯誤') → v-alert 有這段字，沒有 emit
it('有 OIDC 才有單一登入按鈕')
// authOptions → { oidc: { label: '公司帳號' } }：有「用公司帳號登入」；{ oidc: null }：沒有；authOptions reject：也沒有
it('網址有 login_error 時顯示對應的訊息')
it('error prop 有值時只顯示訊息和「重試」，不顯示表單；按「重試」emit retry')
it('右上角的主題鈕 emit toggle-theme')
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/auth`
Expected: FAIL（檔案不存在）。

- [ ] **Step 3: 實作**

- `session.js`：見 Interfaces；`LOGIN_ERRORS` 的三個訊息和「單一登入失敗，請再試一次」照 spec 7.1 一字不差；`sessionStorage` 的讀寫都包 try/catch（私密視窗）。
- `LoginForm.vue`（spec 7.1、7.11）：
  - `v-form @submit.prevent`：「帳號」（`autocomplete="username"`）、「密碼」（`type="password"`、`autocomplete="current-password"`）、`type="submit"` 的「登入」（`color="primary"`、`block`、`:loading`、`:disabled`）。
  - 失敗時 `v-alert type="error"` 顯示 `e.message`。
  - 有預填的 `username` 時，`autofocus` 放在密碼欄，否則放在帳號欄。
  - `oidc` 有值時，下面多一條 `v-divider` 和 `v-btn variant="tonal" prepend-icon="mdi-login" block @click="startOidc"`，文字是 `oidcButtonText(oidc.label)`。
  - 送出中再按一次直接 return。
- `LoginPage.vue`：
  - 畫面正中間一張 `v-card`（`max-width` 400）。上面是 logo（`.cc-logo`，樣式從 `App.vue` 複製）和「JEV 儀表板」。
  - 右上角放主題鈕：`mdi-weather-night`／`mdi-white-balance-sunny`，`aria-label` 跟 App 一樣是「切換成深色主題」／「切換成淺色主題」。
  - `onMounted`：`message = takeLoginError()`，`api.authOptions().catch(() => ({ oidc: null }))`。
  - `error` prop 有值時，卡片裡只有 `v-alert type="error"` 加「重試」按鈕；否則是 login_error 的 alert 加 `LoginForm`。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(frontend): 登入頁，支援帳密和單一登入按鈕`

---

### Task F3: 前端：開機先確認登入，逾時跳出重新登入

**Files:**
- Create: `frontend/src/modules/auth/ReloginDialog.vue`
- Modify: `frontend/src/modules/auth/index.js`、`frontend/src/App.vue`
- Test: Create `frontend/src/modules/auth/ReloginDialog.test.js`；Modify `frontend/src/App.test.js`

**Interfaces:**
- Produces:
  - `ReloginDialog.vue`：props `modelValue: Boolean`、`me: Object`；emits `done`（重新登入的是同一個帳號）。換了帳號時自己呼叫 `resetAndReload()`。
  - App：`me` ref（開機後不再改）、`relogin` ref
- Consumes: F1 的 `api.me`、`api.authOptions`、`onUnauthorized`；F2 的 `LoginPage`、`LoginForm`、`session.js`

- [ ] **Step 1: 寫失敗的測試**

`ReloginDialog.test.js`（mock `../dashboard/api.js` 的 `login`、`me`、`authOptions`；`vi.mock('./session.js', async (orig) => ({ ...(await orig()), resetAndReload: vi.fn() }))`；對話框 teleport 到 `document.body`）：

```js
it('有密碼的帳號：標題、說明、帳號欄預先填好目前的帳號')
// me { username: 'u1', password: true }：「請重新登入」「登入已經逾時，請重新登入。畫面上的東西會留著。」，帳號欄的值是 u1
it('登入後 me 還是同一個帳號：emit done，不重新載入')
it('換了另一個帳號：呼叫 resetAndReload，不 emit done')
it('沒有密碼的帳號只看到單一登入按鈕')
// me { username: 'sso-a', password: false }、authOptions { oidc: { label: '公司帳號' } }：
// 有「你用單一登入，請按下面的按鈕重新登入。「製圖」頁還沒存的預覽會不見。」和「用公司帳號登入」，沒有帳號、密碼欄
it('左下角「重新整理」呼叫 location.reload')
it('對話框是 persistent，點外面不會關')
```

`App.test.js`：
- mock 的 `api` 加 `me: vi.fn()`，`./modules/dashboard/index.js` 的 mock 加 `onUnauthorized: vi.fn()`。
- 新的 mock：

  ```js
  vi.mock('./modules/auth/index.js', () => ({
    LoginPage: { name: 'LoginPage', props: ['error', 'isLight'], emits: ['logged-in', 'retry', 'toggle-theme'], template: '<div class="login-page-stub">{{ error }}</div>' },
    ReloginDialog: { name: 'ReloginDialog', props: ['modelValue', 'me'], emits: ['done'], template: '<div v-if="modelValue" class="relogin-stub" />' },
  }))
  ```

- `beforeEach` 加 `api.me.mockResolvedValue({ username: 'u1', admin: false, password: true })`。
- `afterEach` 加 `history.replaceState(null, '', '/')`。

```js
it('me 還沒回來時只有 spinner，其他都不掛，也還沒抓清單')
// api.me 回一個不會 resolve 的 promise：有 .v-progress-circular，沒有 nav[aria-label="主要導覽"]、LoginPage；listDashboards 沒被呼叫
it('me 回 401 時只顯示登入頁；登入後再打 me，接著照原本的流程載入')
// me 先 reject { status: 401 }：只有 LoginPage stub；stub emit logged-in，me 第二次 resolve → 有 nav[aria-label="主要導覽"]，listDashboards 呼叫一次
it('me 回其他錯誤時，登入頁拿到錯誤訊息；retry 會再打一次 me')
// reject new Error('無法連線到伺服器') → LoginPage 的 error prop 是這段字；emit retry → api.me 呼叫兩次
it('API 回 401 時跳出重新登入，兩個 401 同時回來只開一次，畫面上的元件不卸載')
// const handler = onUnauthorized.mock.calls[0][0]；handler(); handler() → .relogin-stub 只有一個；ChartBuilder stub 還在
it('重新登入的是同一個帳號：關掉對話框，重抓清單，重新打開目前那份')
// 先打開 d1；handler()；ReloginDialog emit done → .relogin-stub 不見、listDashboards 第二次、getDashboard('d1') 第二次
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/App.test.js src/modules/auth/ReloginDialog.test.js`
Expected: 新測試 FAIL；既有的 App 測試因為 `me` 還沒接上，可能也會 FAIL（mock 已經加了，實作後要全部回到 PASS）。

- [ ] **Step 3: 實作**

`ReloginDialog.vue`（spec 7.2）：
- `v-dialog :model-value="modelValue" persistent max-width="420"`，打開時抓一次 `api.authOptions()`。
- 內容依 `me.password` 分兩種，文字見測試。有密碼時用 `LoginForm :username="me.username" :oidc="oidc"`。
- `LoginForm` emit `done` 後打 `api.me()`，拿 `me.username` 跟 `props.me.username` 比：一樣就 emit `done`，不一樣就 `resetAndReload()`。
- `v-card-actions` 左邊一顆 `variant="text"` 的「重新整理」（`location.reload()`）。

`App.vue`，只加新的行：

```js
import { LoginPage, ReloginDialog } from './modules/auth/index.js'
import { restoreSavedHash } from './modules/auth/session.js'
// api 那行加上 onUnauthorized

// 唯讀分享 spec 7.1：me 拿到之前，App 的其他元件都不掛；needLogin 是 me 回 401，bootError 是其他錯誤
const me = ref(null)
const needLogin = ref(false)
const bootError = ref(null)
// spec 7.2：登入逾時另外用一個 ref，不動 me，畫面上的元件都不卸載
const relogin = ref(false)
onUnauthorized(() => {
  if (me.value) relogin.value = true
})

async function boot() {
  needLogin.value = false
  bootError.value = null
  try {
    me.value = await api.me()
  } catch (e) {
    if (e.status === 401) needLogin.value = true
    else bootError.value = e.message
    return
  }
  refresh().catch((e) => (error.value = e.message))
}

// spec 7.2：剛才失敗的動作不會自動重送
function afterRelogin() {
  relogin.value = false
  reloadDashboards()
  if (current.value) open(current.value.id)
}
```

- `onMounted` 的 `refresh().catch(...)` 換成 `restoreSavedHash()` 加 `boot()`。

template：`<v-app>` 後面加三行，`</v-app>` 前面加兩行。中間的既有標記不動、不重排：

```html
    <!-- 唯讀分享 spec 7.1：開機先確認登入。下面的既有標記刻意不重排縮排，跟 feat/industrial-theme 合併時衝突才少 -->
    <div v-if="!me && !needLogin && !bootError" class="boot"><v-progress-circular indeterminate color="primary" /></div>
    <LoginPage v-else-if="!me" :error="bootError" :is-light="isLight" @logged-in="boot" @retry="boot" @toggle-theme="toggleTheme" />
    <template v-else>
    …（既有的 v-navigation-drawer、v-main、刪除對話框）…
    <ReloginDialog v-model="relogin" :me="me" @done="afterRelogin" />
    </template>
```

`.boot { min-height: 100vh; display: grid; place-items: center; }` 加在 scoped style 最後。

`index.js` 加 `export { default as ReloginDialog } from './ReloginDialog.vue'`。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`，然後 `npm run build`
Expected: 全部 PASS（既有的 App 測試靠 `beforeEach` 的 `me` mock 照舊通過），build 成功。

- [ ] **Step 5: Commit**

`feat(frontend): 開機先確認登入，登入逾時跳出重新登入對話框`

---

### Task F4: 前端：帳號選單和改密碼

**Files:**
- Create: `frontend/src/modules/auth/UserMenu.vue`、`PasswordDialog.vue`
- Modify: `frontend/src/modules/auth/index.js`、`frontend/src/App.vue`
- Test: Create `frontend/src/modules/auth/UserMenu.test.js`、`PasswordDialog.test.js`；Modify `frontend/src/App.test.js`

**Interfaces:**
- Produces:
  - `UserMenu.vue`：props `me`；slot `activator`，slot props `{ props }`（給 activator 用的 `v-bind`）；裡面掛 `PasswordDialog`（F5 再掛 `AccountsDialog`）
  - `PasswordDialog.vue`：`v-model`（Boolean）
- Consumes: F1 的 `api.logout`、`api.changePassword`；F2 的 `resetAndReload`

- [ ] **Step 1: 寫失敗的測試**

`UserMenu.test.js`（activator 用一顆測試自己的 `<button class="open">`；選單內容 teleport 到 `document.body`；mock `../dashboard/api.js` 和 `./session.js` 的 `resetAndReload`）：

```js
it('第一行是帳號名稱，管理員後面加「· 管理員」，不能點')
// me { username: 'boss', admin: true, password: true } → 'boss · 管理員'，那一項是 disabled
it('有密碼才有「改密碼」，管理員才有「帳號管理」')
// { admin: false, password: false } → 只有帳號名稱和「登出」
it('「登出」呼叫 api.logout，接著 resetAndReload；logout 失敗也一樣 reload')
it('「改密碼」打開改密碼對話框')
```

`PasswordDialog.test.js`：

```js
it('三個欄位、提示和 autocomplete')
// 「目前的密碼」current-password；「新密碼」new-password，提示「至少 12 個字元」；「再輸入一次新密碼」new-password
it('兩次新密碼不一樣時不送出，顯示「兩次輸入的新密碼不一樣」')
it('失敗時錯誤顯示在標題下面')
// changePassword reject new Error('目前的密碼不對') → v-alert 有這段字，對話框還開著
it('成功後只剩「密碼已更新，請用新密碼重新登入」和「重新登入」，按了 location.reload')
// 成功後 v-dialog 的 persistent 是 true
it('在最後一個欄位按 Enter 也會送出')
```

`App.test.js`：`./modules/auth/index.js` 的 mock 加

```js
UserMenu: { name: 'UserMenu', props: ['me'], template: '<div class="user-menu-stub"><slot name="activator" :props="{}" /></div>' },
```

再加一個測試：

```js
it('導覽列的帳號鈕排在主題鈕前面，title 是帳號名稱')
// button[title="u1"] 存在、文字「帳號」；用 compareDocumentPosition 確認它在 button[aria-label^="切換成"] 前面
// （不用 .cc-rail*：M1 合併頂部導覽列後，兩種版面帳號鈕都在主題鈕前面，這個測試照樣成立）
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/App.test.js src/modules/auth`
Expected: 新測試 FAIL。

- [ ] **Step 3: 實作**

`UserMenu.vue`（spec 7.3）：

```html
<template>
  <v-menu location="end">
    <template #activator="{ props }"><slot name="activator" :props="props" /></template>
    <v-list density="compact">
      <v-list-item :title="me.admin ? `${me.username} · 管理員` : me.username" disabled />
      <v-list-item v-if="me.password" title="改密碼" @click="changing = true" />
      <v-list-item title="登出" @click="logout" />
    </v-list>
  </v-menu>
  <PasswordDialog v-model="changing" />
</template>
```

`logout`：`api.logout().catch(() => {}).finally(resetAndReload)`。

`PasswordDialog.vue`（spec 7.4、7.11）：
- `max-width="420"`、標題「改密碼」，錯誤放標題下面的 `v-alert type="error"`。
- 按鈕「取消」「更新密碼」（`variant="flat"`，「更新密碼」`color="primary"`、`:loading`、`:disabled`）。
- 欄位包在 `v-form @submit.prevent`，最後一個欄位按 Enter 就是送出。打開時焦點在第一個欄位。
- 成功後 `:persistent="done"`，內容換成「密碼已更新，請用新密碼重新登入」和「重新登入」（`location.reload()`）。

`App.vue`：
- import 加 `UserMenu`。
- 在主題鈕（`cc-rail__theme`）的 `<button` 前面插入下面這段。
- style 的 `.cc-rail__theme { margin-top: auto; }` 和上面那行註解改成 `.cc-rail__account`，註解寫「帳號鈕和主題鈕放在導覽列最下面（唯讀分享 spec 7.3）」。

```html
      <UserMenu :me="me">
        <template #activator="{ props }">
          <button type="button" class="cc-rail__item cc-rail__account" v-bind="props" :title="me.username">
            <v-icon icon="mdi-account-circle-outline" size="22" />
            <span>帳號</span>
          </button>
        </template>
      </UserMenu>
```

`index.js` 加 `UserMenu`。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`
Expected: 全部 PASS（既有的「三個工作區」測試只數 `.cc-rail__nav` 裡的項目，帳號鈕不在裡面）。

- [ ] **Step 5: Commit**

`feat(frontend): 帳號選單、登出和改密碼`

---

### Task F5: 前端：帳號管理對話框

**Files:**
- Create: `frontend/src/modules/auth/AccountsDialog.vue`
- Modify: `frontend/src/modules/auth/UserMenu.vue`
- Test: Create `frontend/src/modules/auth/AccountsDialog.test.js`；`UserMenu.test.js` 加一個測試

**Interfaces:**
- Produces: `AccountsDialog.vue`：`v-model`（Boolean）、props `me`；打開時 `api.listAccounts()`
- Consumes: F1 的 `listAccounts`、`createAccount`、`updateAccount`、`setAccountPassword`

- [ ] **Step 1: 寫失敗的測試（`AccountsDialog.test.js`）**

清單用 `[boss（自己，admin、password）、sso-a（password false、sso true）、u1（一般、password）]`。

```js
it('表格的欄位和每一列的身分、登入方式、狀態')
// 管理員／一般、密碼／單一登入／兩者、啟用／停用
it('自己那一列加「（你）」，沒有停用、取消管理員、重設密碼')
it('沒有密碼的帳號按鈕文字是「設定密碼」')
it('新增帳號：送出 { username, password, admin }，成功清空這一列並重抓清單；按 Enter 等於按「新增帳號」')
// 帳號欄 autocomplete="off"，初始密碼 autocomplete="new-password"
it('新增失敗時錯誤顯示在標題下面')
// createAccount reject new Error('帳號「alice」已經存在')
it('重設密碼：展開一列，儲存後收起來，顯示「已重設「u1」的密碼」')
// 展開的密碼框 autocomplete="new-password"；setAccountPassword('u1', 'reset-password-1')；sso-a 成功是「已設定「sso-a」的密碼」
it('停用／啟用、設成管理員／取消管理員用 PATCH，回來的帳號換掉那一列，不另外確認')
it('密碼框的眼睛圖示切換顯示，aria-label 是「顯示密碼」／「隱藏密碼」')
```

`UserMenu.test.js` 加：`it('管理員的「帳號管理」打開帳號管理對話框')`。

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/auth`
Expected: 新測試 FAIL。

- [ ] **Step 3: 實作**

`AccountsDialog.vue`（spec 7.5、7.11）：
- `max-width="720"`、標題「帳號管理」，下面一個訊息區：錯誤 `v-alert type="error"`，成功 `v-alert type="success"`。
- 新增列是 `v-form @submit.prevent`：「帳號」（`autocomplete="off"`、`autofocus`，打開時焦點在這裡）、「初始密碼」（`autocomplete="new-password"`）、`v-checkbox`「管理員」、`type="submit"` 的「新增帳號」。
- 表格用 `v-table`，每個帳號一個 `<tr>`。展開時下面多一個 `<tr><td colspan="5">`，放密碼框（`autocomplete="new-password"`）加「儲存」「取消」，也包在 `v-form` 裡，按 Enter 等於儲存。
- 動作按鈕的字：「重設密碼」／「設定密碼」、「停用」／「啟用」、「設成管理員」／「取消管理員」。
- 自己那一列（`a.username === me.username`）加「（你）」，三顆按鈕都不顯示。
- 兩個密碼框用 `#append-inner` slot 放 `v-btn icon variant="text"`：顯示時 `mdi-eye-off-outline`、aria-label「隱藏密碼」，隱藏時 `mdi-eye-outline`、aria-label「顯示密碼」。
- 送出中的按鈕 `:loading`、`:disabled`，函式開頭再擋一次。

`UserMenu.vue`：選單在「登出」前面加 `<v-list-item v-if="me.admin" title="帳號管理" @click="managing = true" />`，下面掛 `<AccountsDialog v-model="managing" :me="me" />`。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(frontend): 帳號管理對話框`

---

### Task F6: 前端：儀表板和卡片的唯讀模式

**Files:**
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`、`ChartCard.vue`
- Test: `frontend/src/modules/dashboard/DashboardView.test.js`、`ChartCard.test.js`

**Interfaces:**
- Produces: `DashboardView` prop `readonly: Boolean`；`ChartCard` prop `readonly: Boolean`
- Consumes: 無

- [ ] **Step 1: 寫失敗的測試**

`DashboardView.test.js`（`ChartCard` stub 的 props 加 `'readonly'`）：

```js
it('readonly 時不能拖拉、不能縮放、卡片沒有 ✕，也把 readonly 傳給卡片')
// grid-layout-stub 的 data-is-draggable、data-is-resizable 是 false；每個 ChartCard 的 removable false、readonly true
it('readonly 時 moved、resized 不送 PUT')
it('readonly 而且沒有圖表時顯示「這份儀表板沒有圖表。」')
```

`ChartCard.test.js`：

```js
it('readonly 時 SOURCE_DELETED 只說「資料來源已刪除」，不提 ✕')
it('readonly 時 SOURCE_FAILED 改成請聯絡擁有者')
// source '銷售' → '資料來源「銷售」載入失敗，請聯絡儀表板擁有者'；沒有 source → '資料來源載入失敗，請聯絡儀表板擁有者'
it('readonly 時標題列沒有 card-drag（沒有移動游標）')
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/dashboard/DashboardView.test.js src/modules/dashboard/ChartCard.test.js`
Expected: 新測試 FAIL，既有測試照舊 PASS。

- [ ] **Step 3: 實作**

- `DashboardView.vue`：
  - props 加 `readonly`，註解「唯讀分享 spec 7.8：檢視者只能看」。
  - `:is-draggable="!preview && !readonly"`、`:is-resizable` 同樣。
  - `ChartCard` 的 `:removable="!readonly && (!preview || specById[item.i].type !== 'table')"`，再加 `:readonly="readonly"`。
  - `saveLayout` 開頭改成 `if (props.preview || props.readonly || removing.value) return`。
  - 沒有圖表的 `v-alert` 內文改成 `{{ readonly ? '這份儀表板沒有圖表。' : '這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。' }}`。
- `ChartCard.vue`：
  - props 加 `readonly`（見「實作上的決定」第 16 點）。
  - 標題的 `class` 拿掉 `card-drag`，改成 `:class="{ 'card-drag': !readonly }"`。
  - `SOURCE_FAILED`、`SOURCE_DELETED` 兩個 alert 依 `readonly` 換字，非 readonly 的字不變。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

`feat(frontend): 儀表板和卡片的唯讀模式`

---

### Task F7: 前端：「分享給我的」、唯讀檢視和 `#/d/<id>` 網址

**Files:**
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/App.test.js`

**Interfaces:**
- Produces: App 的 `shared` 清單、`readonly` computed、`openFromHash()`；`DashboardView` 拿到 `:readonly`
- Consumes: F1 的 `api.sharedDashboards`；F2 的 `DASHBOARD_HASH`；F6 的 `readonly` prop

- [ ] **Step 1: 寫失敗的測試（`App.test.js`）**

- mock 的 `api` 加 `sharedDashboards: vi.fn()`，`beforeEach` 加 `api.sharedDashboards.mockResolvedValue([])`。
- `DashboardView` stub 的 props 改成 `['config', 'readonly']`。
- 檔案最上面（import 下面）加 `enableAutoUnmount(afterEach)`。少了它，前面測試的 App 沒卸載，它們的 `hashchange` listener 會在後面的測試裡多打 `getDashboard`。
- 測試裡的 UUID 用 `const ID = '11111111-2222-3333-4444-555555555555'`。
- happy-dom 20 的 `history.replaceState` 會用 `setTimeout` 補發 `hashchange`（瀏覽器不會）。數 `getDashboard` 次數之前先等它發完：`const settle = async () => { await new Promise((r) => setTimeout(r)); await flushPromises() }`。
- 切工作區用 `pressKey`，看目前的工作區用 `currentView`（見 Global Constraints，不用 `railItem`）。

```js
it('「分享給我的 · N」列出分享給我的儀表板，副標是「來自 擁有者」，沒有刪除鈕；沒有時整段不顯示')
// sharedDashboards → [{ id: ID, name: '各區營收', owner: 'boss', updatedAt: '…' }]
it('只有分享給我的時，空白狀態是「從左邊選一份儀表板」，沒有製圖步驟')
// listDashboards → []：h2 是「從左邊選一份儀表板」，沒有 .empty-steps，「前往資料」「開始製圖」還在
it('檢視者：說明文字換掉，DashboardView 是 readonly')
// getDashboard → { id: ID, name: '各區營收', createdBy: 'boss', layout: [] }：
// 有「「boss」分享給你的儀表板，只能檢視。」，DashboardView 的 readonly 是 true
it('擁有者：說明文字照舊，DashboardView 不是 readonly')
it('打開儀表板時網址變成 #/d/<id>；刪除目前那份才拿掉')
// 點 Dash One → location.hash === '#/d/d1'；settle() 之後 getDashboard 還是只呼叫一次、DashboardView 還在、沒有「找不到」的字
// （d1 不是 UUID：open() 自己改的 hash 不能被 openFromHash 當成格式不對）
it('網址有 #/d/<id> 時，開機就打開那份')
// 掛載前 history.replaceState(null, '', `/#/d/${ID}`)；settle() → getDashboard 呼叫一次、參數是 ID，currentView 是「儀表板」
it('打開失敗回 404 時清掉畫面，顯示「找不到這份儀表板，可能已經刪除或取消分享」，hash 是失敗的那份')
// listDashboards → [Dash One（d1）, Dash Two（d2）]；先開 d1，再讓 getDashboard reject { status: 404 } 點 Dash Two：
// DashboardView 不見、location.hash === '#/d/d2'（重新整理會再試 d2，不是回到 d1）
it('格式不對的 #/d/… 不打 API，直接顯示找不到')
// '/#/d/abc' → getDashboard 沒被呼叫，有那段字
it('hashchange 到合法的 #/d/<id> 時切到儀表板頁並打開；變成空的或其他 hash 什麼都不做')
// pressKey({ altKey: true, key: '1' }) 切到「資料」；replaceState 再 window.dispatchEvent(new HashChangeEvent('hashchange'))
// （手動發一次是照瀏覽器的行為；happy-dom 會再補發一次）→ settle() 之後 currentView 是「儀表板」、getDashboard 只呼叫一次
// 再切回「資料」，換成 '' 和 '#/other'（一樣手動發）：settle() 之後 currentView 還是「資料」、getDashboard 沒再被呼叫
it('卸載時拿掉 hashchange 的 listener')
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/App.test.js`
Expected: 新測試 FAIL，既有測試照舊 PASS。

- [ ] **Step 3: 實作（`App.vue`，只加新的行，例外是下面列出要改的幾行）**

```js
import { DASHBOARD_HASH, restoreSavedHash } from './modules/auth/session.js'

const shared = ref([])
const NOT_FOUND = '找不到這份儀表板，可能已經刪除或取消分享'
// 唯讀分享 spec 7.8：打開的不是自己的，就是檢視者
const readonly = computed(() => !!current.value && current.value.createdBy !== me.value?.username)

// 最後一次 open() 的 id（「實作上的決定」第 23 點）
let lastOpened = null

// spec 7.9：只認 #/d/<uuid>；格式不對的直接說找不到，不打 API；其他 hash 不理
function openFromHash() {
  if (!me.value || !location.hash.startsWith('#/d/')) return
  // open() 自己改的 hash：happy-dom 的 replaceState 會補發 hashchange（瀏覽器不會），不再打開一次
  if (location.hash === `#/d/${lastOpened}`) return
  view.value = 'dashboards'
  const m = location.hash.match(DASHBOARD_HASH)
  if (m) return open(m[1])
  current.value = null
  error.value = NOT_FOUND
}
```

- `refresh()` 改成 `[dashboards.value, shared.value] = await Promise.all([api.listDashboards(), api.sharedDashboards()])`。
- `open(id)`：
  - `try` 前面加兩行：`lastOpened = id` 和 ``history.replaceState(null, '', `#/d/${id}`)``。先改網址再抓，404 時 hash 才是失敗的那份（spec 7.8）。
  - 失敗時 `if (e.status === 404) current.value = null`，`error.value = e.status === 404 ? NOT_FOUND : e.message`。
- `confirmRemove`：刪掉的是目前那份時，加 `lastOpened = null` 和 `history.replaceState(null, '', location.pathname + location.search)`。
- `boot()`：`refresh()` 之後呼叫 `openFromHash()`。
- `onMounted` 加 `window.addEventListener('hashchange', openFromHash)`，`onBeforeUnmount` 拿掉。

template：
- `cc-desc` 那行的內容改成：

  ```html
  {{ readonly ? `「${current.createdBy}」分享給你的儀表板，只能檢視。` : '卡片拖標題列可以移動、拉右下角可以縮放，版面會自動存。' }}
  ```

- 「我的儀表板」的 `v-list` 後面加一段，`v-if="shared.length"`：`<div class="cc-label px-4 pt-4 pb-1">分享給我的 · {{ shared.length }}</div>` 加一個 `v-list nav`（`aria-label="分享給我的"`）。項目跟上面一樣，但多一個副標 ``:subtitle="`來自 ${d.owner}`"``，沒有 `#append`。
- `DashboardView` 加 `:readonly="readonly"`。
- 空白狀態的 `h2` 改成 `dashboards.length || shared.length ? …`，`<ol class="empty-steps">` 加 `v-if="!dashboards.length && !shared.length"`。

- [ ] **Step 4: 跑測試確認通過**

Run: `npm test`，然後 `npm run build`
Expected: 全部 PASS，build 成功。

- [ ] **Step 5: Commit**

`feat(frontend): 分享給我的清單、唯讀檢視和 #/d/<id> 分享網址`

---

### Task F8: 前端：分享對話框

**Files:**
- Create: `frontend/src/modules/auth/ShareDialog.vue`
- Modify: `frontend/src/modules/auth/index.js`、`frontend/src/App.vue`
- Test: Create `frontend/src/modules/auth/ShareDialog.test.js`；Modify `frontend/src/App.test.js`

**Interfaces:**
- Produces: `ShareDialog.vue`：`v-model`（Boolean）、props `dashboard`（`{ id, name }`）；打開時 `api.getShare(id)`，儲存用 `api.setShare`
- Consumes: F1 的 `getShare`、`setShare`；F7 的 `readonly`

- [ ] **Step 1: 寫失敗的測試**

`ShareDialog.test.js`（`getShare` 回 `{ publicLink: false, users: ['alice'] }`）：

```js
it('標題、公開連結的開關和說明、分享給的輸入框、提示和提醒文字')
// 「分享「各區營收」」「公開連結」「有帳號的人拿到連結就能看」「分享給」、placeholder「輸入對方的帳號」、
// 提示「對方的帳號在他帳號選單的第一行」、「對方看到的資料跟你一樣，包括明細表和敏感欄位。」
it('加入：按 Enter 等於「加入」，加入後焦點留在輸入框；重複的不加')
it('chip 的 ✕ 移除，close-label 是「移除「alice」」；沒有人時顯示「還沒有分享給任何人」')
it('儲存送出 { publicLink, users }，成功關掉對話框')
it('儲存失敗時錯誤留在對話框裡，對話框不關')
// setShare reject new Error('找不到帳號「x」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結')
it('連結是 <網站網址>/#/d/<id>；複製成功後按鈕變成「已複製」2 秒')
// vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockResolvedValue() } })；vi.useFakeTimers()
it('沒有 navigator.clipboard 或 writeText 失敗時，選取輸入框的文字並提示「瀏覽器不讓網頁直接複製，請按 Ctrl+C」')
// vi.stubGlobal('navigator', {})；另一次 writeText reject
```

`App.test.js`：`./modules/auth/index.js` 的 mock 加 `ShareDialog: { name: 'ShareDialog', props: ['modelValue', 'dashboard'], template: '<div v-if="modelValue" class="share-stub" />' }`。

```js
it('擁有者打開自己的儀表板時「新增儀表板」左邊有「分享」，按了打開分享對話框')
it('檢視者和還沒打開儀表板時沒有「分享」')
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/App.test.js src/modules/auth/ShareDialog.test.js`
Expected: 新測試 FAIL。

- [ ] **Step 3: 實作**

`ShareDialog.vue`（spec 7.6、7.11）：
- `max-width="480"`。
- `v-switch` 的 label 是「有帳號的人拿到連結就能看」，上面有一個小標「公開連結」。
- 「分享給」是一個 `v-form @submit.prevent="add"`：輸入框（`autofocus`，打開時焦點在這裡，spec 7.11）加「加入」，加入時 `trim().toLowerCase()`，重複的不加，加完 `focus()`。
- ``v-chip closable :close-label="`移除「${u}」`"``。
- 連結：唯讀的 `v-text-field`，值是 `${location.origin}${location.pathname}#/d/${dashboard.id}`，旁邊「複製連結」（`prepend-icon="mdi-link-variant"`）。成功時文字變「已複製」，2 秒後變回。
- 複製失敗（沒有 `navigator.clipboard`，或 `writeText` reject）：`select()` 輸入框並顯示提示。
- 提醒文字放在 `<p class="cc-desc">`。
- 按鈕「取消」「儲存」，「儲存」`color="primary"`、`:loading`、`:disabled`。

`App.vue`：
- import 加 `ShareDialog`，加 `const sharing = ref(false)`。
- 標題列的「新增儀表板」外面包一個 `<div class="d-flex ga-2">`，前面加：

  ```html
  <v-btn v-if="current && !readonly" variant="tonal" prepend-icon="mdi-share-variant-outline" @click="sharing = true">分享</v-btn>
  ```

- `ReloginDialog` 旁邊掛 `<ShareDialog v-if="current" v-model="sharing" :dashboard="current" />`。

`index.js` 加 `ShareDialog`。

- [ ] **Step 4: 跑前端全部測試和打包**

Run: `npm test`，然後 `npm run build`
Expected: 全部 PASS，build 成功。

- [ ] **Step 5: Commit**

`feat(frontend): 分享對話框，可以分享給指定帳號、打開公開連結和複製連結`

---

### Task M1: 合併 `main` 的頂部導覽列（`feat/industrial-theme`）

前提：Track F 已經合併回 `feat/auth-sharing`，`feat/industrial-theme` 已經進 `main`。在 `feat/auth-sharing` 的 worktree 做。`feat/industrial-theme` 還沒進 `main` 時，整個 M1 跳過（見 Global Constraints），D1 的 README 照左側導覽列寫。

**Files:**
- Modify（解衝突）: `frontend/src/App.vue`、`frontend/src/App.test.js`，可能還有 `frontend/src/modules/dashboard/ChartCard.test.js`
- Modify: `frontend/src/modules/auth/UserMenu.vue`、`frontend/src/modules/auth/LoginPage.vue`

- [ ] **Step 1: 確認順序，合併但先不 commit**

```bash
git merge-base --is-ancestor feat/industrial-theme main || echo "feat/industrial-theme 還沒進 main"
git merge --no-ff --no-commit main
```

第一行有輸出就停下來回報，不要直接合併 `feat/industrial-theme`。用 `--no-commit`：帳號鈕那段 git 會自己合進去、不報衝突，但合出來是錯的（Step 2），要改完才 commit。

預期的衝突（review 時在 scratch 用 `git merge-file` 模擬過）：
- `App.vue` template：F3 加的 `<template v-else>` 那幾行，對上 `<v-navigation-drawer>` 換成 `<header class="cc-topbar">`。
- `App.vue` style：F4 改的 `.cc-rail__account { margin-top: auto; }`，對上 `.cc-topbar__*` 的樣式。
- `ChartCard.test.js`：F6 的測試如果加在 `main` 加對比度測試的同一個位置，兩邊都留。

- [ ] **Step 2: 解衝突，順便修好合得進去、但結果不對的地方**

`App.vue`：
- template 用 `main` 的 `<header class="cc-topbar">`。F3 的 spinner、`LoginPage`、`<template v-else>`、`ReloginDialog`、F8 的 `ShareDialog` 照留，既有標記照舊不重排。
- `UserMenu` 那段放在 header 裡主題鈕的 `<button` 正前面（spec 7.3：緊貼主題鈕左邊），按鈕換成頂部導覽列的 class 和圖示大小：

  ```html
        <UserMenu :me="me">
          <template #activator="{ props }">
            <button type="button" class="cc-topbar__item cc-topbar__account" v-bind="props" :title="me.username">
              <v-icon icon="mdi-account-circle-outline" size="18" />
              <span>帳號</span>
            </button>
          </template>
        </UserMenu>
  ```

- style：
  - 拿掉 `.cc-rail__account` 那條規則和它的註解。
  - `/* 主題切換放在最右邊 */` 和 `.cc-topbar__theme { margin-left: auto; }` 換成 `/* 帳號鈕和主題鈕放在最右邊（唯讀分享 spec 7.3） */` 和 `.cc-topbar__account { margin-left: auto; }`。不搬的話，主題鈕會被推到最右邊，帳號鈕留在工作區旁邊。
  - `@media (max-width: 600px)` 裡：`.cc-topbar__theme span` 那組選擇器加上 `.cc-topbar__account span`；`.cc-topbar__theme { padding: 0 10px; }` 改成 `.cc-topbar__account, .cc-topbar__theme { padding: 0 10px; }`。600px 以下帳號鈕跟主題鈕一樣只留圖示，`title` 還在。

`UserMenu.vue`：`<v-menu location="end">` 改成 `location="bottom end"`，選單從頂部導覽列往下開。

`LoginPage.vue`：logo 換成合併後 `App.vue` 的版本。標記照抄（`<svg>` 加 `<span>JEV</span>`），`.cc-logo`、`.cc-logo svg` 兩條樣式也照抄，舊的漸層樣式刪掉（「實作上的決定」第 20 點）。

`App.test.js`：收下 `main` 的改名（`railItem` → `navItem`、`.cc-rail*` → `.cc-topbar*`）。F3、F4、F7、F8 的新測試照 Global Constraints 寫，不用改。

檢查：`grep -n "cc-rail\|railItem" frontend/src/App.vue frontend/src/App.test.js frontend/src/modules/auth/*.vue` 沒有輸出；`git diff --name-only --diff-filter=U` 沒有輸出。

- [ ] **Step 3: 跑前端全部測試和打包**

在 `frontend/` 跑 `npm test`，然後 `npm run build`。
Expected: 全部 PASS，build 成功。`feat/industrial-theme` 沒有動後端，後端測試留到 D1 Step 1 一起跑；`main` 如果還多了別的後端 commit，這裡也跑一次 `./mvnw test`。

- [ ] **Step 4: Commit**

`git add` Step 2 改過的檔案，訊息：`chore: 合併 main 的頂部導覽列，帳號鈕放到主題鈕左邊`

---

### Task D1: 文件（M1 之後）

**Files:**
- Modify: `README.md`、`backend/.env.example`、`docs/backlog.md`

- [ ] **Step 1: 先確認合併後的程式**

在 `backend/` 跑 `./mvnw test`（用 Global Constraints 的前綴），在 `frontend/` 跑 `npm test` 和 `npm run build`。有失敗就停下來回報，不在這個 task 修。

- [ ] **Step 2: README（spec 第 9 節）**

- `.env` 表格補 `ADMIN_USERNAME`、`ADMIN_PASSWORD`、`OIDC_ISSUER_URI`、`OIDC_CLIENT_ID`、`OIDC_CLIENT_SECRET`、`OIDC_LABEL`，內容照 spec 第 3 節的表。表格下面補 `.env` 的字元限制（ISO-8859-1、`\uXXXX`、不要用 `\` 和 `${`、secret 裡的 `\` 寫成 `\\`）。
- 拿掉 `X-User-Id`、`VITE_USER_ID` 的說明：
  - 第 43 行設定表 `server.address` 那列的「對外開放前看下面的身分識別警語」，改成「對外開放前先設好管理員帳號（見「登入與帳號」）」。那段警語下面就刪了，不能再指過去。
  - 第 55 行 `server.address` 那段的後半句，改成「對外開放前先設好管理員帳號（見「登入與帳號」）」。
  - 「前端設定」的 `VITE_USER_ID` 表格和下面那段。
  - proxy 那句補上 `/oauth2`、`/login/oauth2`，兩條都是 `changeOrigin: false`。
- 「啟動方式」第 5 步改成「會先看到登入頁」。
- 加「登入與帳號」一節，寫 spec 第 9 節列的五點：第一次設管理員、單一登入的人當管理員、舊資料接手、OIDC 的三個條件、HTTPS 反向代理（含用 curl 打 `/oauth2/authorization/oidc` 看 Location 裡的 redirect_uri）。
- 加「分享」一節：兩種分享方式、檢視者看到的資料跟擁有者一樣、單一登入的人要先登入過一次。
- 介面導覽補：帳號選單（做了 M1：頂部導覽列主題鈕左邊的「帳號」；跳過 M1：左側導覽列主題鈕上面的「帳號」，照 README 當時的寫法）、分享按鈕、「分享給我的」。
- 「常見問題」補「請先登入」和「忘記管理員密碼」（由另一位管理員重設，或在 `.env` 換一個新的 `ADMIN_USERNAME` 重啟）。
- 「測試」一節補一句：測試的管理員帳號和密碼固定寫在 `application-test.yml`；有 migration 的 branch 要用自己的 schema。

- [ ] **Step 3: `backend/.env.example`**

補 `ADMIN_USERNAME=`、`ADMIN_PASSWORD=`、`OIDC_ISSUER_URI=`、`OIDC_CLIENT_ID=`、`OIDC_CLIENT_SECRET=`，每組上面一行註解。`OIDC_LABEL` 寫成註解 `# OIDC_LABEL=Microsoft`，不要寫成空的 `OIDC_LABEL=`：空字串也算有設，會蓋掉 `application.yml` 的預設「公司帳號」，按鈕就變成「用登入」。

- [ ] **Step 4: `docs/backlog.md`**

- 第 23 行（P1 唯讀分享）、第 73 行（P1 登入驗證）劃掉，寫上做了什麼和 spec 路徑。
- 「測試補強」第 136 行（`api.test.js` 假設 `VITE_USER_ID`，只標掉這一部分）和第 139 行（測試用 controller）標掉。
- 第 75 行（`DS_` 密鑰沒綁 host）補一句：有了帳號以後，任何帳號都能借用伺服器上的 `DS_` 密鑰（spec 第 10 節），要不要限制成只有管理員能註冊外部 API 來源，由使用者決定。
- 補上 spec 第 10 節留下來的項目：
  - 登入失敗次數限制或鎖帳號：「正式上線前」P2。
  - 綁定單一登入：「補齊」P3，順便寫出舊資料因此只能由密碼帳號接手。
  - 其餘放 P3：強制改密碼、刪除帳號和改帳號名稱、工作階段存資料庫和多台後端、在畫面上設定 OIDC、登出 IdP、兩步驟驗證、操作紀錄、限制 OIDC 網域、記住打開過的公開連結、免登入的公開連結、可以換新的分享 token（spec 2.4 的 ponytail）。
- 列層級權限（第 41 行）註明「登入和分享已完成，可以開始」。

- [ ] **Step 5: 用 `humanizer-zh` 潤過這次改的文字**（使用者的 CLAUDE.md 規定）

- [ ] **Step 6: Commit**

`docs: README 補登入、帳號與分享，backlog 標掉登入驗證和唯讀分享`

---

## 瀏覽器驗證（主 session 自己做，不交給子 agent）

D1 之後，由主 session 照 spec 8.3 的 13 步做：
- 用新的 E2E schema `jev_dashboard_e2e_auth`、`ADMIN_USERNAME=e2e-admin`，前端開在 5180。
- 每個帳號用 Chrome MCP `new_page` 開一個自己的 `isolatedContext`。
- 每一步先打 `/api/auth/me` 確認是誰，截圖自己看過才算過。
- 另外看一次 M1 的版面：帳號鈕緊貼主題鈕左邊，選單往下開；`resize_page` 到 600px 以下時，帳號鈕和主題鈕都只剩圖示。
- OIDC 用 scratchpad 裡的 `oauth2-mock-server`，不進 repo。

8080 上有別的後端時，請使用者自己停掉。正式的 `jev_dashboard` 第一次跑 V6 之前先 `pg_dump` 備份。
