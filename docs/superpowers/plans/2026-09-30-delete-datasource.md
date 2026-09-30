# JEV 儀表板：刪除資料來源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec 的刪除資料來源：所有來源都能刪；被合併來源直接用到時擋下並列出名稱；被儀表板卡片用到時確認後照刪，那些卡片顯示「資料來源已刪除」，儀表板照樣能打開、拖拉、移除卡片。

**Architecture:** 後端加 `GET /api/datasources/{id}/usage` 和 `DELETE /api/datasources/{id}`。「被哪些合併來源用到」和刪除寫在 `DatasourceService`，「被哪些儀表板用到」是 `DashboardRepository` 的一支 jsonb 查詢，由 `DatasourceController` 組起來。`/data` 查不到來源時回新的卡片錯誤 `SOURCE_DELETED`，不算進「全部失敗就整份報錯」；`PUT /api/dashboards/{id}` 只檢查新帶進來的來源。前端在資料頁的來源詳細加「刪除來源」按鈕和對話框，卡片多一種警告。沒有 migration。

**Tech Stack:** Java 21、Spring Boot 3.5、JdbcClient、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、Vitest + @vue/test-utils + happy-dom。

**Spec:** `docs/superpowers/specs/2026-09-30-delete-datasource-design.md`

## Global Constraints

- 在主工作目錄 `C:\GitHub\jev-form-dashboard` 做，branch 是 `feat/delete-datasource`（上面已經有 spec 的 commit `32834f8`）。不另開 worktree。
- 這次沒有 migration，後端測試用預設的 `jev_dashboard_test` schema，不必設 schema 環境變數。使用者開發用的 `jev_dashboard` 只有 Task 5 的瀏覽器驗證會動到，而且只刪 spec 6 第 4 步那一個來源。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`，在 `backend/` 跑 `./mvnw test -Dtest=<類別>`。不要跑 `./mvnw clean`。不要印出 `backend/.env` 的內容。
- 前端在 `frontend/` 跑 `npx vitest run src/modules/dashboard/<檔案>.test.js`，全部是 `npm test`。
- 本機是 Windows 11 + Git Bash：不要用 `python3`；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`。不新增 npm 或 Maven 套件。
- 既有測試照舊通過，一行都不改，只加新的測試、helper 和 import。例外只有兩個：`DashboardDataServiceTest.aFailingSourceOnlyBreaksItsOwnCards` 的 c3（來源本身找不到）改成期待 `ChartData.sourceDeleted("c3", "bar")`；`DatasourcePanel.test.js` 最上面的 `vi.mock('./api.js', …)` 多兩行 `datasourceUsage: vi.fn(),`、`deleteDatasource: vi.fn(),`。
- 錯誤回應固定是 `{"error": "訊息"}`。新的 409 訊息一字不差：`「{來源名稱}」是這些合併來源的輸入，要先刪掉：{名稱1}、{名稱2}`（名稱用全形頓號「、」接，順序跟來源清單一樣）。404 沿用「找不到資料源」。
- 畫面上的字一字不差：
  - 按鈕「刪除來源」，`prepend-icon="mdi-delete-outline"`。
  - 擋下：標題「無法刪除」；內文「「{名稱}」是下面這些合併來源的輸入，要先刪掉它們：」，下面每個合併來源一行；按鈕「知道了」。
  - 確認：標題「刪除資料來源」；內文「確定刪除「{名稱}」？刪除後無法復原。」；有儀表板時再一段「下面這些儀表板有用到它的圖，刪除後會顯示「資料來源已刪除」，可以再按 ✕ 移除：」，每份一行「{儀表板名稱}（{n} 張圖）」；按鈕「取消」「刪除」，「刪除」是 `color="error"`。
  - 卡片：「資料來源已刪除，可以按右上角 ✕ 移除這張圖」。
- fail closed 時的 `sensitiveFrom` 值一字不差是「（已刪除的來源）」（全形括號）。
- 每個 commit 都用明確的 pathspec：`git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits 加繁體中文，例如 `feat(backend): …`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. `DatasourceService` 的建構子不動（測試裡有 6 個地方直接 `new` 它）。「被哪些儀表板用到」放在 `DashboardRepository.usingDatasource`，`DashboardService` 開一支同名方法轉過去，`DatasourceController` 多注入 `DashboardService`。這樣不會有循環依賴：`DashboardService` 本來就依賴 `DatasourceService`，反過來沒有。
2. `mergedBy` 先用 `stored(userId, id)` 確認來源存在，找不到就 404，所以 usage 和 delete 對不存在的來源都是 404。
3. 已刪除的判斷：`DashboardDataService` 載入某個來源失敗後，原本會呼叫 `datasources.get` 查名稱；這一步丟出 `status() == NOT_FOUND` 的 `ApiException` 時，就當作這個來源已刪除。`Source` 多一個 `boolean deleted`，不要靠「名稱是 null」來判斷。合併來源的輸入被刪掉（只有同時操作才會）時，合併來源本身還查得到，照舊是 `SOURCE_FAILED`，並帶上名稱。
4. `DashboardService.update` 原本就先 `get(userId, id)`，拿它的 `layout()` 算出已有的來源 id，只對 `used` 裡不在其中的 id 呼叫 `datasources.get`。
5. usage 的 `dashboards` 照 `updated_at desc` 排，跟儀表板清單一樣；`charts` 為 0 的儀表板不列。jsonb 查詢時，`datasourceId` 用字串比對（`config_json -> 'layout'` 裡存的是 UUID 字串）。
6. 前端的刪除對話框只有一個 `v-dialog`，裡面依 usage 分成「無法刪除」和「刪除資料來源」兩種內容，放在「新增比率」對話框的後面。既有測試用 `findAllComponents({ name: 'VDialog' })` 的 `[0]`、`[1]` 找新增來源和新增比率，新的是 `[2]`。
7. 按「刪除來源」到 usage 回來之前，按鈕是 `loading`。usage 查詢失敗時，錯誤放進頁面既有的 `error`，不打開對話框。按「刪除」時，「刪除」鈕是 `loading`；失敗時錯誤顯示在對話框裡的 `v-alert type="error"`，對話框不關。成功後關掉對話框，呼叫既有的 `refresh()`。

## Review Focus

- 儀表板裡所有卡片的來源都被刪掉：`/data` 不能整份 404，每張卡片都要是 `SOURCE_DELETED`，否則連 ✕ 都按不到（Task 2 測）。
- 儀表板裡有已刪除來源的卡片時拖拉版面：`PUT` 必須成功，否則每次拖拉都跳錯（Task 2 測）。
- 刪除別人的來源、刪除已經刪掉的來源：一律 404，不能刪到別人的資料（Task 1 測）。
- 儀表板用到的是「用到它的合併來源」：usage 不列這份儀表板，而且刪除會被 `mergedBy` 擋下（Task 1 測）。
- 使用者連按兩次「刪除」：第二次請求不能送出，按鈕在請求期間是 `loading`（Task 4 測）。

## File Structure

```
backend/src/main/java/jev/dashboard/
  datasource/DatasourceRepository.java   delete（Task 1）
  datasource/DatasourceService.java      mergedBy、delete、sensitiveFrom fail closed（Task 1）
  datasource/DatasourceController.java   GET /{id}/usage、DELETE /{id}（Task 1）
  dashboard/DashboardRepository.java     usingDatasource、DatasourceUse（Task 1）
  dashboard/DashboardService.java        usingDatasource（Task 1）；update 只查新來源（Task 2）
  dashboard/DashboardDataService.java    SOURCE_DELETED、整份報錯的規則（Task 2）
  transform/ChartData.java               sourceDeleted（Task 2）
backend/src/test/java/jev/dashboard/
  datasource/DatasourceControllerTest.java     加測試（Task 1）
  datasource/MergedDatasourceServiceTest.java  加測試（Task 1）
  dashboard/DashboardDataServiceTest.java      改一行、加測試（Task 2）
  dashboard/DashboardControllerTest.java       加測試（Task 2）
frontend/src/modules/dashboard/
  api.js、api.test.js                    datasourceUsage、deleteDatasource（Task 3）
  ChartCard.vue、ChartCard.test.js       SOURCE_DELETED 的警告（Task 3）
  DatasourcePanel.vue、DatasourcePanel.test.js   按鈕和對話框（Task 4）
README.md、docs/backlog.md（Task 4）
```

---

### Task 1: 後端：usage、刪除、fail closed

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java`、`DatasourceService.java`、`DatasourceController.java`
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`DashboardService.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`、`MergedDatasourceServiceTest.java`

**Interfaces:**
- Produces:
  - `DatasourceRepository.delete(UUID id, String userId): boolean`（刪到一列才是 true；`datasource_rows` 靠 `on delete cascade`）
  - `DatasourceService.mergedBy(String userId, UUID id): List<Datasource>`、`DatasourceService.delete(String userId, UUID id): void`
  - `DatasourceService.DELETED_SOURCE = "（已刪除的來源）"`（`static final String`）
  - `DashboardRepository.DatasourceUse(UUID id, String name, int charts)`（public record）、`DashboardRepository.usingDatasource(String userId, UUID datasourceId): List<DatasourceUse>`
  - `DashboardService.usingDatasource(String userId, UUID datasourceId): List<DatasourceUse>`
  - `DatasourceController.Usage(List<Ref> mergedBy, List<DashboardRepository.DatasourceUse> dashboards)`、`DatasourceController.Ref(UUID id, String name)`
  - `GET /api/datasources/{id}/usage` → 200 `Usage`；`DELETE /api/datasources/{id}` → 204

- [ ] **Step 1: 寫失敗的測試（`DatasourceControllerTest`）**

用 `TestData.uploadCsv`／`uploadSales` 建來源，`POST /api/datasources/merged` 建疊加來源，`POST /api/dashboards` 建儀表板（卡片可以照 `DashboardControllerTest.card`／`tableCard` 的 JSON 形狀自己寫一個 helper）。

```java
@Test void usageListsDirectMergedSourcesAndDashboardsWithCardCounts()
// sales 被疊加來源「全區」直接用到；「全區」再被疊加來源「全區二層」用到。
// 儀表板「週報」有 2 張 sales 的 bar、1 張 sales 的 table；「月報」只有「全區」的卡片。
// GET /api/datasources/{sales}/usage：
//   $.mergedBy[*].name == ["全區"]（沒有「全區二層」）
//   $.dashboards.length() == 1、$.dashboards[0].name == "週報"、$.dashboards[0].charts == 3
// GET /api/datasources/{某個沒人用的來源}/usage：$.mergedBy.length() == 0、$.dashboards.length() == 0

@Test void deleteIsBlockedWhileAMergedSourceUsesIt()
// DELETE /api/datasources/{sales}（sales 名稱是「銷售」，被「全區」「全區B」用到）→ 409，
//   $.error == "「銷售」是這些合併來源的輸入，要先刪掉：全區B、全區"（清單順序：後建的在前）
// 之後 GET /api/datasources 裡 sales 還在

@Test void deleteRemovesTheSourceAndItsRowsButLeavesDashboards()
// sales 被儀表板「週報」用到 → DELETE → 204
// GET /api/datasources 沒有 sales；datasource_rows 裡 datasource_id = sales 的列數是 0
// GET /api/dashboards/{週報} 還是 200，layout 張數不變
// 再 DELETE 一次 → 404 "找不到資料源"

@Test void deleteAndUsageAreScopedToTheOwner()
// u2 對 u1 的來源 GET usage、DELETE 都是 404 "找不到資料源"；u1 的來源還在
```

- [ ] **Step 2: 寫失敗的測試（`MergedDatasourceServiceTest`）**

```java
@Test void aMissingInputMakesItsFieldsSensitive()
// 建兩個上傳來源和一個疊加來源（欄位 region 兩邊都有），然後直接 repo.sources.remove(其中一個輸入)
// service.get(userId, merged) 的 region：sensitive() == true、sensitiveFrom() == "（已刪除的來源）"
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest='DatasourceControllerTest,MergedDatasourceServiceTest'`
Expected: 新測試 FAIL（usage/delete 回 404 或 405、sensitive 是 false），既有測試照舊 PASS。

- [ ] **Step 4: 實作**

- `DatasourceRepository.delete`：`delete from datasource where id = :id and created_by = :by`。
- `DatasourceService.mergedBy`：先 `stored(userId, id)`，再從 `repo.list(userId)` 挑 `kind` 是 `merged`、而且 `MergeDefinition.inputIds(d.merge())` 含這個 id 的。
- `DatasourceService.delete`：`mergedBy` 不是空的就丟 `ApiException(CONFLICT, …)`（訊息見 Global Constraints，來源名稱用 `stored` 拿到的 `name()`）；否則 `repo.delete`。方法上加一行註解：`// ponytail: 檢查和刪除之間沒上鎖，同時建立合併來源可能留下斷掉的定義，由 sensitiveFrom 的 fail closed 兜底；多人同時操作時再包交易、鎖住使用者的來源`。
- `sensitiveFrom`：`if (input == null) continue;` 改成 `if (input == null) return DELETED_SOURCE;`，註解改成寫 fail closed 的理由。
- `DashboardRepository.usingDatasource`：一支 SQL，對每份自己的儀表板算 `jsonb_array_elements(config_json -> 'layout')` 裡 `->> 'datasourceId'` 等於 `datasourceId.toString()` 的張數，只回張數 > 0 的，照 `updated_at desc` 排。
- `DatasourceController`：`GET /{id}/usage` 回 `new Usage(service.mergedBy(…) 轉成 Ref, dashboards.usingDatasource(…))`；`DELETE /{id}` 加 `@ResponseStatus(HttpStatus.NO_CONTENT)`。

- [ ] **Step 5: 跑測試確認通過**

Run: `./mvnw test -Dtest='DatasourceControllerTest,MergedDatasourceServiceTest,DatasourceServiceTest,RatioServiceTest,DatasourceRepositoryTest'`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

`feat(backend): 資料來源可以刪除，被合併來源用到時擋下；輸入來源不見時欄位當作敏感`

---

### Task 2: 後端：儀表板遇到已刪除的來源

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/ChartData.java`、`dashboard/DashboardDataService.java`、`dashboard/DashboardService.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`、`DashboardControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `DELETE /api/datasources/{id}`（controller 測試用）
- Produces: `ChartData.sourceDeleted(String chartId, String type): ChartData`，`error` 是 `"SOURCE_DELETED"`，`source` 是 null，其他欄位跟 `sourceFailed` 一樣是 null

- [ ] **Step 1: 改既有測試、寫新的失敗測試（`DashboardDataServiceTest`）**

- `aFailingSourceOnlyBreaksItsOwnCards`：c3 那行改成 `assertThat(shown.get(2)).isEqualTo(ChartData.sourceDeleted("c3", "bar"));`，註解改成「來源本身找不到，當作已刪除」。

```java
@Test void whenEverySourceIsDeletedEachCardSaysSoInsteadOfThrowing()
// 兩張卡片各指到一個不在 byId 裡的 id：display() 不丟例外，兩張都是 sourceDeleted
@Test void aDeletedSourceDoesNotCountTowardsEverySourceFailing()
// SALES 載入失敗（fail 裡）、另一張卡片的來源不在 byId：不丟例外，
// 第一張是 sourceFailed(…, "銷售")，第二張是 sourceDeleted
```

`whenEverySourceFailsTheFirstErrorIsThrownLikeBefore` 不改，要照舊通過。

- [ ] **Step 2: 寫失敗的測試（`DashboardControllerTest`）**

```java
@Test void aDashboardKeepsWorkingAfterOneOfItsSourcesIsDeleted()
// u1：sales、returns 兩個上傳來源；儀表板 c1 用 sales、c2 用 returns
// DELETE /api/datasources/{returns} → 204
// GET /api/dashboards/{id}/data：$[0].error 不存在（或是 null）、$[1].error == "SOURCE_DELETED"
// PUT 同樣兩張卡片、只改 c1 的 gridPos.y → 200（拖拉照樣存得進去）
// PUT 只留 c1 → 200（移除已刪除的卡片）
// PUT 帶進一張指到隨機 UUID 的新卡片 → 404 "找不到資料源"（新來源照舊要檢查）

@Test void aDashboardWhoseSourcesAreAllDeletedStillOpens()
// 儀表板只有 sales 的卡片；DELETE sales；GET /data → 200，每張的 error 都是 "SOURCE_DELETED"
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest='DashboardDataServiceTest,DashboardControllerTest'`
Expected: 新測試和改過的那一個 FAIL（還是 SOURCE_FAILED、整份 404、PUT 404）。

- [ ] **Step 4: 實作**

- `ChartData.sourceDeleted`：javadoc 寫「刪除來源 spec 4.1：這張圖的來源已經刪掉」。
- `DashboardDataService`：`Source` 多 `boolean deleted` 和 `static Source ofDeleted()`（不能叫 `deleted()`，會跟 record 的 accessor 撞名）；`sources()` 載入失敗後查名稱，查的時候遇到 `NOT_FOUND` 就放 `Source.ofDeleted()`（見「實作上的決定」第 3 點，取代現在的 `nameOf`）；`build()` 對 `deleted` 的來源放 `sourceDeleted`；整份報錯的條件改成「每個來源都失敗，而且沒有一個是 `deleted`」。
- `DashboardService.update`：見「實作上的決定」第 4 點，註解寫「刪除來源 spec 4.2：原本版面裡的來源可能已經刪掉，只檢查這次新帶進來的」。

- [ ] **Step 5: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

`feat(backend): 來源刪掉之後，儀表板的卡片顯示已刪除，版面照樣能存`

---

### Task 3: 前端：API 和卡片的警告

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`、`ChartCard.vue`
- Test: `frontend/src/modules/dashboard/api.test.js`、`ChartCard.test.js`

**Interfaces:**
- Produces: `api.datasourceUsage(id)` → `GET /api/datasources/${id}/usage`；`api.deleteDatasource(id)` → `DELETE /api/datasources/${id}`（204 回 `null`，`request` 本來就這樣處理）

- [ ] **Step 1: 寫失敗的測試**

- `api.test.js`：照既有的寫法，驗證兩支的 method 和 URL。
- `ChartCard.test.js`：`data = { chartId: 'c1', type: 'bar', error: 'SOURCE_DELETED' }`、`removable: true` 時，畫面有「資料來源已刪除，可以按右上角 ✕ 移除這張圖」，也有移除鈕。

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/dashboard/api.test.js src/modules/dashboard/ChartCard.test.js`
Expected: 新測試 FAIL。

- [ ] **Step 3: 實作**

`api.js` 加兩行，放在 `setRatios` 後面。`ChartCard.vue` 在 `SOURCE_FAILED` 那個 `v-alert` 後面加一個 `v-else-if="data.error === 'SOURCE_DELETED'"`，屬性跟它一樣。

- [ ] **Step 4: 跑測試確認通過**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: Commit**

`feat(frontend): 卡片的來源刪掉時顯示「資料來源已刪除」`

---

### Task 4: 前端：「刪除來源」按鈕和對話框，文件

**Files:**
- Modify: `frontend/src/modules/dashboard/DatasourcePanel.vue`、`README.md`、`docs/backlog.md`
- Test: `frontend/src/modules/dashboard/DatasourcePanel.test.js`

**Interfaces:**
- Consumes: Task 3 的 `api.datasourceUsage`、`api.deleteDatasource`；Task 1 的 usage 形狀 `{ mergedBy: [{id, name}], dashboards: [{id, name, charts}] }`

- [ ] **Step 1: 寫失敗的測試（`DatasourcePanel.test.js`）**

先在 `vi.mock` 加兩行（見 Global Constraints）。刪除對話框用 `wrapper.findAllComponents({ name: 'VDialog' })[2]` 找。

```js
it('被合併來源用到時只說明、不能刪')
// usage 回 { mergedBy: [{ id: 'mg', name: '全區' }], dashboards: [] }
// 按「刪除來源」→ api.datasourceUsage 被呼叫一次、參數是選取的 id
// 對話框標題「無法刪除」，內文有「「sales.csv」是下面這些合併來源的輸入，要先刪掉它們：」和「全區」
// 沒有「刪除」鈕；按「知道了」關閉；api.deleteDatasource 沒被呼叫

it('被儀表板用到時列出儀表板，確認後刪除並重讀清單')
// usage 回 { mergedBy: [], dashboards: [{ id: 'd1', name: '週報', charts: 3 }] }
// 對話框有「確定刪除「sales.csv」？刪除後無法復原。」「週報（3 張圖）」
// 按「刪除」→ api.deleteDatasource('ds1')，之後 api.listDatasources 又被呼叫一次，對話框關閉

it('沒有人用到時不列儀表板那一段')
// usage 兩個都是空陣列：沒有「下面這些儀表板」那句

it('刪除失敗時錯誤留在對話框裡')
// deleteDatasource reject new Error('「sales.csv」是這些合併來源的輸入，要先刪掉：全區')
// 對話框還開著，裡面有那段訊息

it('刪除請求還沒回來時不能再按一次')
// deleteDatasource 回一個還沒 resolve 的 promise；按「刪除」後「刪除」鈕的 loading 是 true，
// 再按一次，api.deleteDatasource 還是只被呼叫一次
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/dashboard/DatasourcePanel.test.js`
Expected: 新測試 FAIL，既有測試照舊 PASS。

- [ ] **Step 3: 實作 `DatasourcePanel.vue`**

按鈕放在標題列「用這個來源製圖」的前面。狀態和行為見「實作上的決定」第 6、7 點，文案見 Global Constraints。

- [ ] **Step 4: 跑前端全部測試**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 5: 文件**

- `README.md`「合併來源」一節最後一段：把「合併定義建立之後不能編輯，也不能刪除（其他來源目前也不能刪），定義錯了就另建一個」改成「合併定義建立之後不能編輯，定義錯了就刪掉重建」。在「一份儀表板放多個來源」之後加一節「刪除來源」，寫：按鈕在哪、被合併來源用到會擋下、被儀表板用到確認後照刪而卡片顯示已刪除、可以按 ✕ 移除、刪除無法復原，最後連到 spec。
- `docs/backlog.md`：第 27 行「編輯、刪除合併來源的定義」改成只剩「編輯」（刪除已完成，寫上 spec 路徑）；第 28 行 fail closed 劃掉並寫「已修正」。
- 文件文字用 `humanizer-zh` 潤過（使用者的 CLAUDE.md 規定）。

- [ ] **Step 6: Commit**

`feat(frontend): 資料頁可以刪除來源，被用到時先說明影響`，README 和 backlog 另一個 commit：`docs: README 補刪除來源，backlog 更新刪除和 fail closed 兩項`

---

### Task 5: 瀏覽器驗證（主 session 自己做，不交給子 agent）

照 spec 第 6 節「瀏覽器」四步。

- [ ] **Step 1: 起後端**

`8080` 上現在跑的是 main 的後端（舊程式碼）。先查 `netstat -ano | grep -E ":8080 .*LISTENING"` 拿到 PID，請使用者自己執行 `! taskkill //PID <pid> //F`，再由子 agent 用 `run_in_background` 在 `backend/` 起 `./mvnw spring-boot:run`（連 `jev_dashboard`，沒有 migration）。

- [ ] **Step 2: 起一個用測試使用者的前端**

在 `frontend/` 用 `run_in_background` 跑 `VITE_USER_ID=e2e-delete npx vite --port 5180 --strictPort`，Chrome 開新分頁到 `http://localhost:5180`。使用者自己的 `5173` 不動。

- [ ] **Step 3: 在 `e2e-delete` 下準備資料並操作**

從畫面上傳兩份 CSV（放 scratchpad，`upload_file` 只收 workspace 內的路徑）、建一個疊加來源、各分析一次存成同一份儀表板。照 spec 6 第 1～3 步操作，每一步截圖，截圖自己 Read 過再算數。

- [ ] **Step 4: 在 `demo-user` 下刪掉「示範疊加：民國年訂單＋sales」**

用 `5173` 的分頁操作，確認它沒有被其他合併來源或儀表板用到，對話框沒有儀表板那一段。

- [ ] **Step 5: 回報**

每一步的結果和截圖路徑；有問題就修，修完重跑受影響的測試和那一步。
