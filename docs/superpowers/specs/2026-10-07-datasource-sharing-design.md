# JEV 儀表板：資料來源共享與免登入公開連結（設計）

日期：2026-10-07

前提：[登入與唯讀分享 spec](2026-10-01-auth-sharing-design.md)（下稱「分享 spec」）、[合併來源 spec](2026-09-27-merged-datasource-design.md)、[刪除來源 spec](2026-09-30-delete-datasource-design.md)。本文件只寫這次改變的部分。

## 1. 問題

- 資料來源只有建立的人能用，`DatasourceRepository` 的查詢都帶 `created_by`。要給別人用，只能分享儀表板，對方只能看，不能自己做圖。只有管理員能註冊外部 API 來源，一般帳號等於用不到外部 API（backlog「補齊」P2）。
- 公開連結要登入才能看（backlog P3「分享的後續」）。使用者 2026-10-07 決定一起做「免登入看儀表板」。

## 2. 規則

### 2.1 誰能用一個資料來源

- 擁有者可以把資料來源分享給指定帳號，也可以打開「所有帳號都能用」，兩個可以同時開。上傳、外部 API、合併來源都能分享。
- 以下把被分享的人叫「使用者」。使用者在「資料」頁的「分享給我的」看得到這個來源，可以：
  - 看名稱、種類、擁有者、欄位表（欄位名稱、顯示名稱、型別、敏感與否、樣本值、相異值數）、比率和筆數。
  - 按「用這個來源製圖」，把結果存成自己的儀表板，或加到自己既有的儀表板。
  - 拿它當自己合併來源的輸入。
- 使用者不能：
  - 看外部 API 的網址和 header（header 裡的 `${DS_…}` 名稱也不露出來）。
  - 看合併來源的輸入來源是哪些：合併定義（輸入來源的 id、欄位對應）不給，繼承來的敏感欄位照樣是敏感，但不說是在哪個來源標的（`sensitiveFrom` 是 null）。
  - 改顯示名稱、敏感設定、比率，或更新資料、刪除、再分享給別人。這些 API 對使用者回 404「找不到資料源」，跟現在對「別人」一樣。
- 分析時照擁有者的設定：敏感欄位一樣不送 JEV，比率照用。
- 擁有者帳號停用後，他的來源對使用者一律當作不存在，跟分享 spec 2.4 停用擁有者的儀表板一樣。
- 撤銷：擁有者把對方移出清單、關掉「所有帳號都能用」或刪掉來源，對方下一次載入就用不到了，見 2.3。

### 2.2 合併來源的輸入

- 合併來源載入輸入來源時，用這個合併來源擁有者的身分檢查權限，不是用目前請求的身分。所以：
  - A 把自己的合併來源 M 分享給 B，B 能用 M，但不必也能用 M 的輸入來源。B 在資料頁也看不到 M 的輸入來源，除非那些也分享給了 B。
  - 合併來源的敏感繼承（合併來源 spec 6.3）一樣用合併來源擁有者的身分去查輸入來源。不然 B 看 M 時查不到輸入來源，會因為 fail closed 把每一欄都當成敏感。
- B 自己建的合併來源可以用 A 分享給他的來源當輸入。A 之後若取消分享，B 的合併來源載入時會找不到輸入來源，回 400「合併來源的輸入來源已刪除或停止分享，這個合併來源不能用了」；敏感繼承照舊 fail closed，來源名稱顯示「（已刪除或停止分享的來源）」（原本是「（已刪除的來源）」，`DatasourceService.DELETED_SOURCE`）。
- 一次請求共用一個 loader（`DatasourceService.Loader`），每個來源只載入一次。loader 快取的是資料，不是權限：每次從 loader 拿某個來源的資料之前，都要先用這次的身分（或合併來源擁有者的身分）檢查一次權限，檢查過才給快取裡的資料。例子：B 的儀表板上一張卡用 M、一張卡直接用 A1，A1 是 M 的輸入但沒分享給 B；載入 M 時 A1 被載入並快取，第二張卡照樣要拿到「沒有權限」，不能拿到快取的 A1。
- 合併的層數（最多 3 層）只看結構：計算時用不帶擁有者條件的查詢讀定義，權限由 2.2 第一點負責。

### 2.3 取消分享或刪除之後

- 擁有者隨時可以取消分享或刪除，有人在用也不擋（使用者選，2026-10-07）。刪除前的確認只列擁有者自己的合併來源和儀表板（跟現在一樣），來源有分享出去時多一行提醒，見 6.3。
- 使用者的儀表板上，直接用這個來源的卡片：
  - 來源還在、只是不能用了（取消分享、擁有者停用）：新的卡片錯誤 `SOURCE_UNSHARED`，顯示「資料來源已停止分享」；儀表板擁有者自己看時，後面接「，可以按右上角 ✕ 移除這張圖」。
  - 來源已經刪掉：照舊 `SOURCE_DELETED`。
  - 判斷方式：載入失敗而且用使用者的身分查不到時，再用不帶擁有者條件的查詢看來源還在不在。
- `DashboardDataService.sources` 的「全部來源都失敗就整份報錯」：`SOURCE_UNSHARED` 跟 `SOURCE_DELETED` 一樣，不算「失敗」，卡片各自顯示訊息。
- 用到它的合併來源：照 2.2，載入失敗，卡片是 `SOURCE_FAILED`。
- 擁有者更新資料時，所有直接或間接用到它的合併來源（不分擁有者）都清掉筆數（更新資料 spec 2.6 原本只清自己的）。

### 2.4 免登入看儀表板

- 儀表板的分享設定多一個「不用登入也能看」。要先打開公開連結才能開；關掉公開連結時一起關掉。
- 打開後，任何人拿到連結 `<網站網址>/#/d/<id>` 不用登入就能唯讀看這份儀表板：圖表、異常標註、「依據」那一行都看得到。有登入的人照舊用公開連結的規則看。
- 沒登入的人能用的 API 只有讀這份儀表板需要的幾支（4.4）；其他 API 照舊 401。
- 資料一樣用擁有者的身分載入，擁有者的外部 API 一樣會被抓，異常標註一樣用這個系統的 JEV key。
- 擁有者帳號停用後，一樣當作不存在（404）。

## 3. 資料庫（Flyway `V7__datasource_sharing.sql`）

```sql
alter table datasource add column shared_all boolean not null default false;

create table datasource_share (
    datasource_id uuid not null references datasource (id) on delete cascade,
    username      text not null references account (username),
    primary key (datasource_id, username)
);

create index datasource_share_username on datasource_share (username);

alter table dashboard add column anonymous_link boolean not null default false;
alter table dashboard add constraint dashboard_anonymous_needs_public check (not anonymous_link or public_link);
```

- 「能用」的查詢：

  ```sql
  d.created_by = :by
  or ((d.shared_all or exists (select 1 from datasource_share s where s.datasource_id = d.id and s.username = :by))
      and exists (select 1 from account o where o.username = d.created_by and o.enabled))
  ```

  擁有者自己那條不檢查 `account`，所以既有測試直接用沒有帳號的使用者名稱也不受影響。

## 4. API

### 4.1 資料來源分享（只有擁有者，別人 404「找不到資料源」）

- `GET /api/datasources/{id}/share`

  ```json
  { "allUsers": false, "users": ["alice", "bob"] }
  ```

- `PUT /api/datasources/{id}/share`：body 同一個形狀，整份取代，回傳存好的結果。規則照分享 spec 4.3：
  - `users` 沒給當作空的，丟掉 null 和空白，轉小寫、去重複、依名稱排序。
  - 400「不用分享給自己」。
  - 400「找不到帳號「x」。用單一登入的人要先登入過一次才會有帳號，也可以先打開「所有帳號都能用」」。停用的帳號可以加。
  - 更新 `shared_all`、刪掉舊的、寫入新的在同一個 transaction。

### 4.2 既有的資料來源 API

- `GET /api/datasources`：先列自己的（照舊依建立時間由新到舊），再列別人分享給我的（同樣排序）。每一筆照舊有 `createdBy`，前端拿它跟 `me.username` 比，分出是不是自己的。
  - 別人的來源：`apiUrl` 是 null，`apiHeaders` 是 `{}`，沒有 `merge`，欄位的 `sensitiveFrom` 是 null（2.1）。
- `GET /api/datasources/{id}/usage`：照舊只有擁有者。回傳多一個 `shared`：有指定帳號或「所有帳號都能用」時是 true。
- 只認擁有者（使用者 404）：更新資料、敏感、顯示名稱、比率、刪除、usage、share。
- 用「能用」的規則：`/api/jev/analyze`、`/api/jev/mapping`、`/api/datasources/merged/preview`、`/api/datasources/merged`（輸入來源）、`POST /api/dashboards`、`PUT /api/dashboards/{id}`、`POST /api/dashboards/{id}/charts`、`POST /api/dashboards/preview/data`、`/data`、`/highlight`。
- 寫法：`DatasourceService.get(userId, id)` 和 `cachedFinder` 改用「能用」的查詢；`stored(userId, id)` 照舊只認擁有者，所有修改都已經走 `stored`。`list` 在 service 回傳前把別人來源的網址、header、合併定義和 `sensitiveFrom` 拿掉；`get` 給內部載入用，不拿掉。

### 4.3 儀表板分享

- `GET`／`PUT /api/dashboards/{id}/share` 的形狀多一個 `anonymous`：

  ```json
  { "publicLink": true, "anonymous": false, "users": ["alice"] }
  ```

  - `anonymous` 沒給當作 false。
  - `anonymous` 是 true、`publicLink` 是 false：400「要先打開公開連結」。

### 4.4 不用登入的 API

- 只有這幾支 GET，路徑裡的 id 要是 UUID：
  - `GET /api/dashboards/{id}`
  - `GET /api/dashboards/{id}/data`
  - `GET /api/dashboards/{id}/filter-options`（儀表板篩選器的功能另外做，先把規則放進來，到時候不用再改安全設定）
  - `GET /api/jev/highlight/{id}`
- 這幾支可以帶查詢字串（篩選器會用 `?filters=…`）。
- 安全設定用 `RegexRequestMatcher` 放行，例如 `^/api/dashboards/[0-9a-f-]{36}(/data|/filter-options)?(\?.*)?$`。`RegexRequestMatcher` 比對時會帶上查詢字串，所以結尾要允許 `?…`。不能用 `/api/dashboards/*`，不然 `/api/dashboards/shared` 也會被放行，沒登入時它拿到的 `Principal` 是 null。
- 沒登入時 controller 拿到的 `Principal` 是 null。只有 `anonymous_link` 打開、擁有者帳號啟用中的儀表板查得到，其他一律 404「找不到儀表板」。
- 有登入的人照舊走分享 spec 4.5 的規則。
- `/data` 的錯誤訊息比照檢視者，換成「資料來源載入失敗，請聯絡儀表板擁有者」。

## 5. 後端其他改動

- `DatasourceRepository`：`findUsable(id, userId)`、`listUsable(userId)`（自己的在前）、`findAny(id)`（不帶擁有者條件，給 2.2 的層數和 2.3 判斷來源還在不在用）、`share(id)`、`setShare(id, share)`、列出所有用到某個來源的合併來源（不分擁有者）。
- `DatasourceService`：
  - `effective(ds, …)` 遇到合併來源時，用 `ds.createdBy()` 的「能用」查詢找輸入來源。
  - `Loader` 照 2.2 檢查權限；合併來源的輸入用合併來源擁有者的身分檢查。
  - `depth` 改用 `findAny`。
  - `reload` 清筆數時，照 2.3 用不分擁有者的清單。
  - 新增 `exists(id)` 給 `DashboardDataService.failedSource` 用。
- `ChartData.sourceUnshared(chartId, type)`，錯誤代碼 `SOURCE_UNSHARED`。
- `DashboardRepository`：`Share` 多 `anonymous`；`findAnonymous(id)`。`DashboardService.view(userId, id)` 的 `userId` 是 null 時走 `findAnonymous`。
- `DashboardController.get`、`data`，`JevController.highlight`：`principal` 可能是 null，用 `principal == null ? null : principal.getName()`。

## 6. 畫面

### 6.1 資料頁的清單

- 左邊清單先列自己的；有別人分享的來源時，下面多一段「分享給我的 · N」，副標是「來自 帳號 · N 個欄位」。
- 選到別人分享的來源：
  - 標題下面的說明換成「「帳號」分享給你的來源，可以拿來製圖，不能修改。」。
  - 種類那一行，外部 API 不顯示網址，只寫「外部 API · N 個欄位 · N 個敏感」。
  - 只有「用這個來源製圖」按鈕；沒有「分享」「更新資料」「刪除來源」「新增比率」。
  - 欄位表的敏感勾選停用，顯示名稱不能改；比率只列出來，沒有刪除鈕。
- 自己的來源：標題列多一顆「分享」按鈕（`mdi-share-variant-outline`），在「更新資料」左邊。

### 6.2 分享對話框

- 沿用 `ShareDialog.vue`，用 prop 分出「儀表板」和「資料來源」兩種，不另寫一個元件。
- 資料來源：
  - 標題「分享「名稱」」。
  - 第一段是開關「所有帳號都能用」，說明「所有登入的人都能在資料頁看到它、拿來製圖」。
  - 第二段「分享給」跟儀表板一樣（輸入框、加入、chip）。
  - 沒有連結那一段。
  - 提醒文字：「對方可以拿它製圖，看得到明細資料和敏感欄位的值；看不到 API 網址和 header，也不能修改。」
- 儀表板：公開連結開關下面多一個開關「不用登入也能看」，說明「任何拿到連結的人都能看，包括沒有帳號的人」。公開連結關著時停用；關掉公開連結時一起關掉。打開時，提醒文字換成「沒有帳號的人也看得到這份儀表板的所有資料，包括明細表和敏感欄位。」

### 6.3 刪除確認

- `usage.shared` 是 true 時，確認文字多一行：「這個來源有分享給別人，刪除後，別人用它做的圖和合併來源都會失效。」

### 6.4 製圖頁、合併表單

- 來源選單列出自己的和別人分享的。別人的名稱後面加「（來自 帳號）」。

### 6.5 卡片

- `SOURCE_UNSHARED`：照 2.3 的文字。

### 6.6 沒登入時打開儀表板連結

- 開機時 `/api/auth/me` 回 401，而且網址是 `#/d/<uuid>`：先用 `GET /api/dashboards/{id}` 試試看。
  - 成功：進「訪客檢視」。頂部導覽列只有品牌、深淺主題切換和「登入」按鈕。下面就是唯讀檢視（分享 spec 7.8），說明文字是「「擁有者」分享的儀表板，只能檢視。」。
  - 401 或 404：照舊顯示登入頁。
- 訪客按「登入」，顯示登入頁；登入成功後照舊開機，`openFromHash` 會打開同一份。
- 訪客把網址改成另一份 `#/d/<uuid>`：再試一次 `GET /api/dashboards/{id}`，打不開就顯示登入頁。同一份、格式不對的 hash 都不理。
- 訪客模式沒有 `me`，`onUnauthorized` 不會跳重新登入對話框（`App.vue` 原本就只在有 `me` 時才跳）。

## 7. 測試

### 7.1 後端

- Repository：「能用」的查詢涵蓋自己的、指定帳號、所有帳號、沒分享、擁有者停用；`listUsable` 的順序。
- 使用者對修改類 API（更新資料、敏感、顯示名稱、比率、刪除、usage、share）一律 404。
- `GET /api/datasources` 對別人的來源不回網址和 header；擁有者自己看照舊有。
- 2.2：B 用 A 分享的合併來源 M（輸入沒分享給 B）能載入；B 的欄位表沒有因為 fail closed 變成全部敏感；同一次請求裡 B 直接用 A1 的卡片拿不到資料。
- 2.3：取消分享後卡片是 `SOURCE_UNSHARED`；刪掉後是 `SOURCE_DELETED`；全部卡片都是這兩種時 `/data` 不整份報錯；B 的合併來源載入失敗的訊息。
- 更新資料清掉別人合併來源的筆數。
- 資料來源 share API 的驗證（自己、找不到的帳號、轉小寫、去重複、整份取代）。
- 儀表板 share 的 `anonymous`：沒開公開連結時 400。
- 安全設定：沒登入時，打開 `anonymous_link` 的儀表板，三支 GET 回 200；只開公開連結的回 404；`/api/dashboards/shared`、`GET /api/dashboards`、`PUT /api/dashboards/{id}`、`/api/datasources` 回 401；帶查詢字串的 `/data?filters=…` 一樣放行。

### 7.2 前端

- `ShareDialog`：資料來源模式（沒有連結、開關文字、送出的 body）；儀表板模式的「不用登入也能看」連動。
- `DatasourcePanel`：別人分享的來源是唯讀的，沒有網址，按鈕只有製圖；自己的來源有「分享」；刪除確認的提醒。
- `App`：沒登入、hash 是儀表板時進訪客檢視；`getDashboard` 回 404 時顯示登入頁。

### 7.3 瀏覽器（主 session 自己做）

- 三個帳號（admin、owner、user）各一個 `isolatedContext`，加上一個沒登入的 context。
- owner 上傳一份來源，分享給 user；user 在資料頁看得到、不能改，拿來製圖、存檔；owner 取消分享，user 的卡片顯示「資料來源已停止分享」。
- admin 註冊的外部 API 來源無法在本機測（`ApiUrlGuard` 擋 loopback），靠單元測試。
- owner 打開儀表板的「不用登入也能看」，沒登入的 context 打開連結看得到圖和標註；關掉後重新整理，回到登入頁。

## 8. 文件

- `README.md`：資料來源分享、免登入連結各補一段。
- `docs/backlog.md`：「資料來源共享」劃掉，寫上完成說明；「分享的後續」拿掉「免登入的公開連結」那一句；「不做」的項目列進 backlog（第 9 節）。
- `docs/testing/e2e-playbook.md`：加一節涵蓋 7.3 的步驟。

## 9. 不做

- 使用者自己的比率、顯示名稱（現在比率、顯示名稱綁在來源上，只有擁有者能改）。backlog 已經有「共用指標定義」。
- 列層級權限（backlog P3）。
- 沒登入的 API 限流。沒登入的人每打開一次都會載入資料，外部 API 和 JEV 都會被呼叫（異常檢查有快取）。列進 backlog「正式上線前」。
- 換新的分享 token、讓舊連結永久失效（backlog P3 已有）。
- 資料來源的公開連結、沒登入的人拿來製圖（使用者 2026-10-07 選了只做免登入看儀表板）。

## 10. 決定（2026-10-07）

| 題目 | 選擇 | 沒選的 |
|---|---|---|
| 分享範圍 | 指定帳號，加上「所有帳號都能用」開關（使用者選） | 只能指定帳號 |
| 沒登入的人 | 免登入看儀表板（使用者選） | 訪客拿公開來源製圖、兩種都要 |
| 使用者看 API 網址 | 網址和 header 都看不到（使用者選） | 看得到網址 |
| 取消分享或刪除之後 | 照常允許，卡片顯示提示（使用者選） | 有人在用就擋下 |
| 誰能改來源 | 只有擁有者（backlog 原本就這樣寫） | 使用者也能改顯示名稱 |
| 合併來源的輸入權限 | 用合併來源擁有者的身分 | 用目前請求的身分（分享合併來源就得連輸入一起分享） |
| 擁有者停用 | 他的來源對別人當作不存在，跟儀表板一致 | 照常能用 |
| 「停止分享」和「刪除」 | 分成兩個錯誤代碼，訊息不同 | 合成一個「已刪除或停止分享」 |
