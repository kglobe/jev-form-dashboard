# JEV 儀表板：一份儀表板接多個資料來源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`：每張圖記錄自己的資料來源，製圖頁可以「存成新儀表板」或「加到既有儀表板」，已存的儀表板可以移除單張圖，多來源時卡片標出來源名稱、依據按每次分析分組。

**Architecture:** 後端把來源從 `dashboard.datasource_id` 搬到每張圖的 `ChartSpec.datasourceId`，Flyway V2 遷移舊資料，`config_json` 的 `intents` 改成 `basis` 分組。`DashboardDataService` 依來源分組、每個來源只載入一次，部分來源失敗時只有那些卡片回 `SOURCE_FAILED`。新 API `POST /api/dashboards/{id}/charts` 在同一個 transaction 裡鎖住那一列、重新編號、往下接。前端 `ChartBuilder` 多一個「加到既有儀表板 ▾」選單，`DashboardView` 負責已存儀表板的「✕」確認、移除後不重抓、依據分組和來源名稱。

**Tech Stack:** Java 21、Spring Boot 3.5.16（Jackson 2.21、Flyway 11）、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、grid-layout-plus 1.1、Vitest 5 + @vue/test-utils 2.5 + happy-dom 20。

前幾份計畫在 `docs/superpowers/plans/` 裡（2026-09-24、2026-09-25），這份只寫新增和改變的部分。

## Global Constraints

- 在獨立的 git worktree 上做，分支從 `docs/multi-source-specs` 開（上面已經有兩份 spec 的 commit）。下面的指令都用 `cd "$(git rev-parse --show-toplevel)/…"` 開頭，從 worktree 根目錄算路徑；Bash 會保留上一個指令的工作目錄，不要假設自己在哪裡。
- Migration 用 `V2__per_chart_datasource.sql`。子專案 B1（合併來源）會用 `V3`，而且 A 先合併進 main。
- 測試和端對端都不能碰共用的 schema。B1 會在另一個 worktree 同時開發，兩邊的 migration 版本不同，共用 `jev_dashboard_test` 會讓對方的 Flyway 驗證失敗；使用者平常開的 dev 後端用 `jev_dashboard`，合併前也不能先被遷移。所以：
  - 後端測試一律加環境變數 `SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a`（環境變數蓋過 `application-test.yml`，Flyway 的 `create-schemas: true` 會自己建 schema）。
  - 端對端（Task 9）的後端用 `jev_dashboard_e2e_a`。
  - 遷移測試（Task 3）用自己的 `jev_dashboard_v2_migration_test`，測完就刪。
- 本機是 Windows 11 + Git Bash。不要用 `python3`（用 `python`）；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`。不要跑 `./mvnw clean`。後端測試會連本機 PostgreSQL，帳密在 `backend/.env`。
- 不要印出 `backend/.env` 的內容，`cat`、`grep` 整行都不行。
- worktree 裡沒有 `backend/.env`（gitignore）和 `frontend/node_modules`：Task 1 Step 1 複製 `.env`，Task 5 Step 1 跑 `npm ci`。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`，不能 import 這個資料夾以外的檔案；`frontend/src/App.vue` 是宿主，可以 import 模組。
- 不新增 npm 或 Maven 套件。
- 錯誤回應固定是 `{"error": "訊息"}`。
- 後端訊息照 spec 6.6：「name、layout 都必填」「圖表 {chartId}：缺少 datasourceId」「一次存入的圖表要來自同一個資料來源」「沒有要存入的圖表」「依據缺少 datasourceId」「找不到資料源」「找不到儀表板」。
- 畫面上的字照 spec 原文：「存成新儀表板」「加到既有儀表板」「移除圖表」「確定從「{儀表板名稱}」移除「{卡片標題}」？移除後無法復原，要加回來得重新分析。」「取消」「移除」「這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。」「資料來源「{source}」載入失敗，請稍後重新整理」「資料來源載入失敗，請稍後重新整理」「依據：a → b」「依據（{來源名稱}）：a → b」。
- 測試總數：計畫裡寫的數字是用 grep 數出來的（後端 157 個 `@Test`、前端 129 個 `it(`）。Task 1 Step 2 先跑一次全部測試記下實際基準，之後每個 task 以「基準＋這個 task 新增的數量」為準；數字對不上時先查是不是基準不同，不要為了湊數改測試。
- 既有測試只在 spec 要求的地方改（建構子、JSON 格式、改名的按鈕、移到 DashboardView 的「✕」規則），每個 task 都寫明改了哪幾個；其他既有的 `it`、`@Test` 和預期結果不動。
- 前端測試要用假計時器時，只假 `setTimeout` 和 `clearTimeout`：`vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })`。
- 每個 commit 都用明確的 pathspec：`git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits，說明寫繁體中文，例如 `feat(backend): 每張圖記錄自己的資料來源`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. `DashboardDataService` 只把 `ApiException`（找不到來源、外部 API 失敗、筆數超過上限）當成「來源載入失敗」。其他例外是程式錯誤，照舊變成 500，不會被藏成 `SOURCE_FAILED`。
2. 來源載入失敗時才另外呼叫一次 `datasources.get` 查名稱給卡片顯示；成功時直接用 `load` 回傳的名稱，不多查一次。
3. 異常檢查改用一次 `datasources.list(userId)`，取得每個來源的敏感欄位。清單裡找不到某張卡片的來源時，那張卡片直接不送 JEV，整個異常檢查也不會因此回 404。敏感設定是資料外送的防線，查不到就當成不能送。
4. PUT 先確認儀表板存在，再檢查每個來源。別人的儀表板照舊回「找不到儀表板」，既有測試 `crudIsScopedToTheOwner` 不用改。
5. 加到既有儀表板的驗證順序：先確認儀表板存在，再驗證卡片，再檢查「至少一張、同一個來源」，然後是 intents，最後才查來源歸屬。卡片要先驗證過，`datasourceId` 才一定有值；各種錯誤的訊息跟 spec 6.6 一樣。
6. 「哪些卡片有 ✕」從 `ChartCard` 搬到 `DashboardView`：`removable = !preview || type !== 'table'`。`ChartCard` 只看 `removable`。
7. `DashboardView`「只少了卡片就不打 API」的規則延伸到已存儀表板，但只在 `config.id` 跟上一次一樣時才適用；換了一份儀表板一定重抓（App 本來就用 `:key` 重建，這裡是第二道防線）。
8. 多來源儀表板的依據，要等每張卡片都有資料（包含 `SOURCE_FAILED`）才顯示，用 computed 算，不另外記狀態。
9. `api.js` 的錯誤多帶 `status`。製圖頁加入時收到 404，就送出 `dashboards-changed`，請 App 重抓清單。
10. 「存成新儀表板」和「加到既有儀表板」各有自己的 loading 狀態（`saving`、`appending`），任何一個進行中兩顆都停用。
11. 空的 `basis` 一律存成 `null`，JSON 不輸出，跟「沒有需求紀錄」一樣。
12. `ChartSpec` 保留 6 個參數的建構子（`datasourceId = null`）；`DashboardConfig` 多一個 4 個參數的建構子 `(id, name, createdBy, layout)`。舊的 5 個參數建構子拿掉，編譯器會指出每個要改的呼叫端。
13. spec 第 9 節端對端第 4 步「V2 遷移前的儀表板照常打開」不在 worktree 裡做：worktree 的端對端用全新的 `jev_dashboard_e2e_a`，裡面沒有舊資料；遷移的正確性由 Task 3 的遷移測試保證。真正的舊資料要等合併進 main 之後，由 controller 照本文最後一節「合併後檢查」確認。

## File Structure

```
backend/src/main/java/jev/dashboard/
  transform/ChartSpec.java           加 datasourceId、6 參數建構子、withDatasourceId（Task 1）
  transform/ChartData.java           加 source、withSource、sourceFailed（Task 2）
  jev/ConfigAssembler.java           at()、withFocus() 帶上 datasourceId（Task 1）；maxChartNumber 改 public（Task 4）
  jev/AnalyzeService.java            每張圖寫入這次分析的來源（Task 1）；回傳的設定沒有儀表板層的來源（Task 3）
  jev/JevController.java             異常檢查的敏感欄位依每張圖的來源（Task 2）
  dashboard/DashboardService.java    cardProblem 檢查 datasourceId（Task 1）；create／update／basis（Task 3）；append（Task 4）
  dashboard/DashboardDataService.java 依來源分組載入、SOURCE_FAILED、source（Task 2）
  dashboard/DashboardConfig.java     拿掉 datasourceId，加 basis（Task 3）
  dashboard/DashboardRepository.java Stored(layout, basis)、DashboardSummary 拿掉 datasourceId（Task 3）；findForUpdate（Task 4）
  dashboard/DashboardController.java POST /{id}/charts（Task 4）
backend/src/main/resources/db/migration/V2__per_chart_datasource.sql   （新增，Task 3）
backend/src/test/java/jev/dashboard/
  TestData.java                      dashboardJson 每張圖帶 datasourceId（Task 1）、拿掉最上層的（Task 3）
  dashboard/DashboardMigrationTest.java （新增，Task 3）
  dashboard/DashboardServiceTest.java、DashboardControllerTest.java、DashboardDataServiceTest.java
  jev/ConfigAssemblerTest.java、AnalyzeServiceTest.java、JevControllerTest.java
frontend/src/
  App.vue、App.test.js               傳儀表板清單給 ChartBuilder、dashboards-changed、updated（Task 7）
  modules/dashboard/
    api.js、api.test.js              appendCharts、錯誤帶 status（Task 5）
    ChartCard.vue、ChartCard.test.js 來源名稱、SOURCE_FAILED、✕ 只看 removable（Task 5）
    DashboardView.vue、DashboardView.test.js  ✕ 確認與移除、不重抓、空狀態、依據分組、來源名稱（Task 6）
    ChartBuilder.vue、ChartBuilder.test.js    兩顆存檔按鈕、加到既有儀表板、調整用左側的來源（Task 7）
README.md、docs/backlog.md、docs/superpowers/specs/2026-09-23-jev-dashboard-design.md（Task 8）
```

測試數量（照上面的基準）：後端 157 → 159（Task 1）→ 163（Task 2）→ 168（Task 3）→ 170（Task 4）；前端 129 → 133（Task 5）→ 139（Task 6）→ 146（Task 7）。

---

### Task 1: 每張圖記錄自己的來源

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/ChartSpec.java`、`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`、`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`
- Test: `backend/src/test/java/jev/dashboard/TestData.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`、`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`、`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`、`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Produces：
  - `ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping, Map<String, Object> options, UUID datasourceId)`，另有 6 參數建構子（`datasourceId = null`）和 `ChartSpec withDatasourceId(UUID)`。JSON 屬性名 `datasourceId`，null 時不輸出。
  - `AnalyzeService.analyze(...)` 回傳的每張圖（包含調整模式留下來的）`datasourceId` 都是這次分析的來源。
  - 卡片驗證（`DashboardService.requireValidCards`，存檔、預覽、調整的 `current` 共用）多一條：`datasourceId` 是 null 時回「圖表 {chartId}：缺少 datasourceId」，排在 gridPos 檢查後面。

這個 task 還保留儀表板層的 `DashboardConfig.datasourceId`，Task 3 才拿掉。

- [ ] **Step 1: 準備 worktree 的後端設定、確認基準**

Run: `cd "$(git rev-parse --show-toplevel)" && [ -f backend/.env ] || cp /c/GitHub/jev-form-dashboard/backend/.env backend/.env; [ -f backend/.env ] && echo ".env ok"`
Expected: `.env ok`（只印這行，不印內容）

- [ ] **Step 2: 跑一次全部後端測試，記下基準**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 157, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`。數字不是 157 就把實際數字記在報告裡，之後的預期都用它加上新增的數量。

- [ ] **Step 3: 寫測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`：

把

```java
    static final GridPos POS = new GridPos(0, 0, 6, 4);

    static ChartSpec card(String type, Mapping m) {
        return new ChartSpec("c1", type, "t", POS, m, Map.of());
    }

    static ChartSpec at(GridPos pos) {
        return new ChartSpec("c1", "bar", "t", pos, x("region"), Map.of());
    }
```

換成

```java
    static final GridPos POS = new GridPos(0, 0, 6, 4);
    static final UUID DS = UUID.randomUUID();

    static ChartSpec card(String type, Mapping m) {
        return new ChartSpec("c1", type, "t", POS, m, Map.of(), DS);
    }

    static ChartSpec at(GridPos pos) {
        return new ChartSpec("c1", "bar", "t", pos, x("region"), Map.of(), DS);
    }
```

`validCardsPass` 裡的明細表也帶上來源，把

```java
            new Mapping(null, null, null, null, null, List.of("region"), "none", null), Map.of());
```

換成

```java
            new Mapping(null, null, null, null, null, List.of("region"), "none", null), Map.of(), DS);
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void everyCardNeedsADatasourceId() {
        rejects(config(card("bar", x("region")).withDatasourceId(null)), "圖表 c1：缺少 datasourceId");
    }
```

`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`：把

```java
        assertThatCode(() -> DashboardService.requireComplete(
            new DashboardConfig(null, "銷售 總覽", UUID.randomUUID(), null, layout))).doesNotThrowAnyException();
```

換成

```java
        // 存檔前 AnalyzeService 會把這次分析的來源寫進每張圖
        List<ChartSpec> saved = layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList();
        assertThatCode(() -> DashboardService.requireComplete(
            new DashboardConfig(null, "銷售 總覽", UUID.randomUUID(), null, saved))).doesNotThrowAnyException();
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void layOutAndFocusKeepTheChartsDatasourceId() {
        UUID ds = UUID.randomUUID();
        ChartSpec kept = new ChartSpec("c1", "bar", "amount 依 region", new GridPos(6, 4, 6, 4),
            new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of(), ds);

        List<ChartSpec> out = ConfigAssembler.adjust(List.of(kept), List.of(kept), List.of(), Map.of(), Set.of("region"));

        // withFocus() 加上 focus、layOut() 重新排位置，兩步都要把來源帶過去
        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.datasourceId()).isEqualTo(ds);
            assertThat(s.options()).isEqualTo(Map.of("focus", true));
            assertThat(s.gridPos()).isEqualTo(new GridPos(0, 0, 6, 4));
        });
    }
```

`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`：

在 `runsTwoRoundsAndSkipsSamplesOfSensitiveFields` 裡，把

```java
            assertThat(c.datasourceId()).isEqualTo(ds);
```

換成

```java
            assertThat(c.datasourceId()).isEqualTo(ds);
            assertThat(c.layout()).extracting(ChartSpec::datasourceId).containsOnly(ds);
```

在 `adjustAsksWhetherToKeepEachChartAndAddsFocusChartsAfterTheKeptOnes` 裡，把

```java
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                intents, List.of(line, pie, table));
```

換成

```java
            UUID ds = UUID.randomUUID();
            DashboardConfig c = service(server).analyze("銷售 總覽", ds, Fixtures.SALES_SCHEMA, 12,
                intents, List.of(line, pie, table));
```

再把

```java
            assertThat(c.layout().get(0)).isEqualTo(line);        // 保留的圖設定原封不動
```

換成

```java
            assertThat(c.layout().get(0)).isEqualTo(line.withDatasourceId(ds)); // 保留的圖只多了這次分析的來源
            assertThat(c.layout()).extracting(ChartSpec::datasourceId).containsOnly(ds);
```

`backend/src/test/java/jev/dashboard/TestData.java`：把 `dashboardJson` 整個換成下面這樣（每張圖都帶 `datasourceId`，最上層的先留著，Task 3 才拿掉）：

```java
    /** c1、c2 有時間欄位；c3 沒有；c4 用了不存在的欄位 profit。每張圖都讀 datasourceId 這個來源。 */
    public static String dashboardJson(String datasourceId) {
        return """
            {"name":"銷售總覽","datasourceId":"%1$s","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"line","title":"月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"bar","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}},
              {"chartId":"c3","datasourceId":"%1$s","type":"pie","title":"地區佔比","gridPos":{"x":0,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c4","datasourceId":"%1$s","type":"bar","title":"利潤","gridPos":{"x":6,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"profit","aggregate":"sum"}}
            ]}""".formatted(datasourceId);
    }
```

`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`：調整模式送來的 `current` 也要有來源（值是什麼都行，AnalyzeService 會蓋掉），把 `TABLE_CARD`、`BAR_CARD` 換成

```java
    static final String TABLE_CARD = """
        {"chartId":"c2","datasourceId":"00000000-0000-0000-0000-000000000001","type":"table","title":"明細",
         "gridPos":{"x":0,"y":4,"w":12,"h":5},"mapping":{"columns":["region"]}}""";
    static final String BAR_CARD = """
        {"chartId":"c1","datasourceId":"00000000-0000-0000-0000-000000000001","type":"bar","title":"amount 依 region",
         "gridPos":{"x":0,"y":0,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}""";
```

`highlightSkipsCardsThatReferenceSensitiveFields` 裡的儀表板 JSON 換成

```java
        String dashboardJson = """
            {"name":"分區銷售","datasourceId":"%1$s","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"stacked_bar","title":"分區月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"line","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}}
            ]}""".formatted(ds);
```

- [ ] **Step 4: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardServiceTest,ConfigAssemblerTest,AnalyzeServiceTest'`
Expected: FAIL，編譯錯誤，例如 `constructor ChartSpec in record ChartSpec cannot be applied to given types`、`cannot find symbol ... method withDatasourceId`

- [ ] **Step 5: 實作**

`backend/src/main/java/jev/dashboard/transform/ChartSpec.java` 整個換成：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/** datasourceId：這張圖讀哪個資料來源。ConfigAssembler 產生的圖還沒有，由 AnalyzeService 補上。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                        Map<String, Object> options, UUID datasourceId) {
    public ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                     Map<String, Object> options) {
        this(chartId, type, title, gridPos, mapping, options, null);
    }

    public ChartSpec withDatasourceId(UUID value) {
        return new ChartSpec(chartId, type, title, gridPos, mapping, options, value);
    }

    public record GridPos(int x, int y, int w, int h) {}
}
```

`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`：`at()` 和 `withFocus()` 重建 `ChartSpec` 時帶上來源。把

```java
        return new ChartSpec(s.chartId(), s.type(), s.title(), pos, s.mapping(), s.options());
```

換成

```java
        return new ChartSpec(s.chartId(), s.type(), s.title(), pos, s.mapping(), s.options(), s.datasourceId());
```

把

```java
        return new ChartSpec(s.chartId(), s.type(), s.title(), s.gridPos(), s.mapping(), options);
```

換成

```java
        return new ChartSpec(s.chartId(), s.type(), s.title(), s.gridPos(), s.mapping(), options, s.datasourceId());
```

`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`：把

```java
        return new DashboardConfig(null, name, datasourceId, null, layout, intents.isEmpty() ? null : intents);
```

換成

```java
        // 這次分析的圖都讀同一個來源，調整模式留下來的圖也一樣
        layout = layout.stream().map(s -> s.withDatasourceId(datasourceId)).toList();
        return new DashboardConfig(null, name, datasourceId, null, layout, intents.isEmpty() ? null : intents);
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`：`cardProblem` 最後的 `return null;` 前面加一條，整個方法變成：

```java
    private static String cardProblem(ChartSpec s) {
        String problem = UniversalTransformer.problem(s);
        if (problem != null) return problem;
        GridPos g = s.gridPos();
        if (g == null) return "缺少 gridPos";
        // 前端格線是 12 欄
        if (g.x() < 0 || g.y() < 0 || g.w() < 1 || g.h() < 1 || g.w() > 12 || g.x() > 12 - g.w()) {
            return "gridPos 要 x ≥ 0、y ≥ 0、w ≥ 1、h ≥ 1，而且 x + w ≤ 12";
        }
        // 多來源 spec 4.1：來源記在每張圖上
        if (s.datasourceId() == null) return "缺少 datasourceId";
        return null;
    }
```

- [ ] **Step 6: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardServiceTest,ConfigAssemblerTest,AnalyzeServiceTest'`
Expected: PASS

- [ ] **Step 7: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 159, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add backend/src/main/java/jev/dashboard/transform/ChartSpec.java backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java && git commit -m "feat(backend): 每張圖記錄自己的資料來源" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/transform/ChartSpec.java backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java
```

---

### Task 2: 依來源分組載入、部分失敗、異常檢查的敏感欄位

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/ChartData.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java`、`backend/src/main/java/jev/dashboard/jev/JevController.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`、`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `ChartSpec.datasourceId()`
- Produces（前端 Task 5、6 會用到）：
  - `ChartData` 多一個最後的欄位 `String source`（來源名稱），JSON 屬性名 `source`；`ChartData withSource(String)`；`static ChartData sourceFailed(String chartId, String type, String source)`，`error` 是 `"SOURCE_FAILED"`，`source` 可以是 null。
  - `GET /api/dashboards/{id}/data`、`POST /api/dashboards/preview/data` 回傳的每一筆都帶 `source`；只有部分來源失敗時，那些卡片是 `{"chartId", "type", "error": "SOURCE_FAILED", "source"?}`；全部失敗時照舊回第一個錯誤。
  - `DashboardDataService.load()`／`display()` 不再讀 `DashboardConfig.datasourceId()`。

- [ ] **Step 1: 寫 DashboardDataServiceTest 的測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`：import 加上

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Set;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;
```

在 class 開頭（第一個 `@Test` 前面）加：

```java
    static final GridPos POS = new GridPos(0, 0, 6, 4);
    static final UUID SALES = UUID.randomUUID();
    static final UUID RETURNS = UUID.randomUUID();
    /** 銷售只有 region 有顯示名稱，退貨只有 qty 有。 */
    static final Map<UUID, Datasource> TWO = Map.of(
        SALES, new Datasource(SALES, "銷售", "upload",
            List.of(new FieldSchema("region", FieldType.STRING, 2, List.of(), false, null, null, "地區")), null, Map.of(), "u1", null),
        RETURNS, new Datasource(RETURNS, "退貨", "upload",
            List.of(new FieldSchema("qty", FieldType.NUMBER, 2, List.of(), false, null, null, "數量")), null, Map.of(), "u1", null));
    static final Map<UUID, List<Map<String, Object>>> ROWS = Map.of(
        SALES, List.of(Map.of("region", "北區", "amount", 5.0)),
        RETURNS, List.of(Map.of("reason", "瑕疵", "qty", 2.0)));

    /** byId 裡的來源照常載入；fail 裡的來源載入時丟 502；不在 byId 的來源連名稱都查不到。loads 記每個來源被載入幾次。 */
    static DatasourceService sources(Map<UUID, Datasource> byId, Map<UUID, List<Map<String, Object>>> rows,
                                     Set<UUID> fail, Map<UUID, Integer> loads) {
        return new DatasourceService(null, List.of(),
                new JevProperties(null, null, null, new JevProperties.Upload(100), null), null) {
            @Override
            public Datasource get(String userId, UUID id) {
                if (!byId.containsKey(id)) throw new ApiException(HttpStatus.NOT_FOUND, "找不到資料源");
                return byId.get(id);
            }

            @Override
            public Loaded load(String userId, UUID id) {
                loads.merge(id, 1, Integer::sum);
                Datasource ds = get(userId, id);
                if (fail.contains(id)) throw new ApiException(HttpStatus.BAD_GATEWAY, "外部 API 無法連線");
                return new Loaded(ds, rows.get(id));
            }
        };
    }

    static ChartSpec bar(String chartId, UUID ds, String x, String value) {
        return new ChartSpec(chartId, "bar", value + " 依 " + x, POS,
            new Mapping(x, null, null, value, null, null, "sum", null), Map.of(), ds);
    }

    static DashboardConfig config(ChartSpec... layout) {
        return new DashboardConfig(null, "x", null, "u1", List.of(layout));
    }
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void eachSourceIsLoadedOnceAndEachCardUsesItsOwnSource() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(), loads));

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", SALES, "region", "amount")));

        assertThat(loads).isEqualTo(Map.of(SALES, 1, RETURNS, 1));
        assertThat(shown).extracting(ChartData::chartId).containsExactly("c1", "c2", "c3");
        assertThat(shown).extracting(ChartData::source).containsExactly("銷售", "退貨", "銷售");
        // 顯示名稱各用各的 schema
        assertThat(shown.get(0).dimensions()).isEqualTo(List.of("地區", "amount"));
        assertThat(shown.get(1).dimensions()).isEqualTo(List.of("reason", "數量"));
        assertThat(shown.get(1).rows()).isEqualTo(List.of(List.of("瑕疵", 2.0)));
    }

    @Test
    void aFailingSourceOnlyBreaksItsOwnCards() {
        UUID gone = UUID.randomUUID();
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(RETURNS), new HashMap<>()));

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", gone, "region", "amount")));

        assertThat(shown.get(0).error()).isNull();
        assertThat(shown.get(0).rows()).isEqualTo(List.of(List.of("北區", 5.0)));
        assertThat(shown.get(1)).isEqualTo(ChartData.sourceFailed("c2", "bar", "退貨"));
        assertThat(shown.get(2)).isEqualTo(ChartData.sourceFailed("c3", "bar", null)); // 來源本身找不到，沒有名稱
    }

    @Test
    void whenEverySourceFailsTheFirstErrorIsThrownLikeBefore() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES, RETURNS), new HashMap<>()));

        assertThatThrownBy(() -> service.load("u1", config(bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"))))
            .isInstanceOf(ApiException.class)
            .hasMessage("外部 API 無法連線");
    }
```

- [ ] **Step 2: 寫 JevControllerTest 的測試**

`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`：import 加上 `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;`，在 class 最後一個 `}` 前面加：

```java
    @Test
    void highlightChecksSensitiveFieldsAgainstEachCardsOwnSource() throws Exception {
        String plain = TestData.uploadSales(mvc, "u1");
        String secret = TestData.uploadSales(mvc, "u1");
        // 兩個來源都有 region，只有 secret 那邊標成敏感
        mvc.perform(patch("/api/datasources/" + secret + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"region\"]}"))
            .andExpect(status().isOk());
        String card = """
            {"chartId":"%s","datasourceId":"%s","type":"stacked_bar","title":"分區月銷售額",
             "gridPos":{"x":%d,"y":0,"w":6,"h":4},
             "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}}""";
        String first = card.formatted("c1", plain, 0);
        String body = mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"兩個來源\",\"datasourceId\":\"" + plain + "\",\"layout\":[" + first + "]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"兩個來源\",\"datasourceId\":\"" + plain + "\",\"layout\":["
                    + first + "," + card.formatted("c2", secret, 6) + "]}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u1")).andExpect(status().isOk());

        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c1\"").doesNotContain("anomaly_c2");
    }
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,JevControllerTest'`
Expected: FAIL，編譯錯誤 `cannot find symbol ... method source()`、`method sourceFailed`

- [ ] **Step 4: 實作 ChartData**

`backend/src/main/java/jev/dashboard/transform/ChartData.java` 整個換成：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * truncated 只在明細表被截斷時是 true，其他時候是 null、JSON 不輸出。
 * title 是套上欄位顯示名稱後的卡片標題；null 時前端照用 spec 的標題。
 * source 是這張圖的來源名稱，多來源的儀表板用它標在卡片上。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField, Boolean truncated, String title, String source) {
    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, null, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField) {
        this(chartId, type, dimensions, rows, error, missingField, null, null, null);
    }

    public ChartData withLabels(List<String> dimensions, String title) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, source);
    }

    public ChartData withSource(String value) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, value);
    }

    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }

    /** 多來源 spec 第 7 節：這張圖的來源載入失敗；source 是查得到的來源名稱，查不到是 null。 */
    public static ChartData sourceFailed(String chartId, String type, String source) {
        return new ChartData(chartId, type, null, null, "SOURCE_FAILED", null, null, null, source);
    }
}
```

- [ ] **Step 5: 實作 DashboardDataService**

`backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java` 整個換成：

```java
package jev.dashboard.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.SchemaInference;
import jev.dashboard.jev.ConfigAssembler;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);

    private final DatasourceService datasources;

    /** 一個來源載入一次的結果。載入失敗時 rows 是 null；name 是查得到的來源名稱，查不到是 null。 */
    private record Source(String name, List<Map<String, Object>> rows, Set<String> available, Map<String, String> labels) {
        static Source failed(String name) {
            return new Source(name, null, null, null);
        }
    }

    public DashboardDataService(DatasourceService datasources) {
        this.datasources = datasources;
    }

    /** 原始欄位名稱的資料；異常檢查用這個，送給 JEV 的是原始名稱。 */
    public List<ChartData> load(String userId, DashboardConfig c) {
        return build(userId, c, false);
    }

    /** 給畫面用：維度名稱和自動產生的標題換成欄位的顯示名稱。 */
    public List<ChartData> display(String userId, DashboardConfig c) {
        return build(userId, c, true);
    }

    /** 回傳的順序跟 layout 一樣，每一筆都帶來源名稱。 */
    private List<ChartData> build(String userId, DashboardConfig c, boolean labelled) {
        Map<UUID, Source> sources = sources(userId, c.layout());
        List<ChartData> out = new ArrayList<>();
        for (ChartSpec s : c.layout()) {
            Source src = sources.get(s.datasourceId());
            if (src.rows() == null) {
                out.add(ChartData.sourceFailed(s.chartId(), s.type(), src.name()));
                continue;
            }
            ChartData d = UniversalTransformer.transform(s, src.rows(), src.available());
            out.add((labelled ? relabelled(s, d, src.labels()) : d).withSource(src.name()));
        }
        return out;
    }

    /**
     * 每個來源只載入一次。有來源載入失敗（外部 API 掛掉、超過筆數上限等）時，只有用到它的卡片是 SOURCE_FAILED；
     * 全部來源都失敗就丟出第一個錯誤，單一來源的儀表板和預覽跟以前一樣整份報錯。
     */
    private Map<UUID, Source> sources(String userId, List<ChartSpec> layout) {
        Map<UUID, Source> out = new LinkedHashMap<>();
        ApiException first = null;
        for (ChartSpec s : layout) {
            UUID id = s.datasourceId();
            if (out.containsKey(id)) continue;
            try {
                out.put(id, loaded(datasources.load(userId, id)));
            } catch (ApiException e) {
                if (first == null) first = e;
                log.warn("資料來源 {} 載入失敗：{}", id, e.getMessage());
                out.put(id, Source.failed(nameOf(userId, id)));
            }
        }
        if (first != null && out.values().stream().allMatch(src -> src.rows() == null)) throw first;
        return out;
    }

    private static Source loaded(DatasourceService.Loaded loaded) {
        List<FieldSchema> schema = loaded.datasource().schema();
        // 資料源改版時欄位會從資料列消失；只要有任何一列還有這個 key 就算欄位還在（上游常省略 null 欄位）
        Set<String> available = loaded.rows().isEmpty()
            ? schema.stream().map(FieldSchema::name).collect(Collectors.toSet())
            : SchemaInference.fieldNames(loaded.rows());
        Map<String, String> labels = schema.stream().filter(f -> f.label() != null)
            .collect(Collectors.toMap(FieldSchema::name, FieldSchema::label));
        return new Source(loaded.datasource().name(), loaded.rows(), available, labels);
    }

    /** 載入失敗時才查名稱給卡片顯示；來源本身找不到就沒有名稱。 */
    private String nameOf(String userId, UUID id) {
        try {
            return datasources.get(userId, id).name();
        } catch (ApiException e) {
            return null;
        }
    }

    private static ChartData relabelled(ChartSpec s, ChartData d, Map<String, String> labels) {
        if (labels.isEmpty()) return d;
        List<String> dims = d.dimensions() == null ? null : d.dimensions().stream().map(n -> labels.getOrDefault(n, n)).toList();
        // 只換自動產生的標題；明細表之類另外取名的標題照舊
        String title = s.mapping() != null && s.title() != null && s.title().equals(ConfigAssembler.title(s.type(), s.mapping()))
            ? ConfigAssembler.title(s.type(), relabel(s.mapping(), labels)) : null;
        return d.withLabels(dims, title);
    }

    private static Mapping relabel(Mapping m, Map<String, String> labels) {
        return new Mapping(labels.getOrDefault(m.x(), m.x()), labels.getOrDefault(m.y(), m.y()),
            labels.getOrDefault(m.series(), m.series()), labels.getOrDefault(m.value(), m.value()),
            m.measures(), m.columns(), m.aggregate(), m.timeGrain());
    }
}
```

- [ ] **Step 6: 實作 JevController.highlight**

`backend/src/main/java/jev/dashboard/jev/JevController.java`：import 加上 `import java.util.Map;` 和 `import jev.dashboard.datasource.Datasource;`。把

```java
        DashboardConfig c = dashboards.get(userId, dashboardId);
        List<ChartData> data = dashboardData.load(userId, c);
        Set<String> sensitive = datasources.get(userId, c.datasourceId()).schema().stream()
            .filter(FieldSchema::sensitive).map(FieldSchema::name).collect(Collectors.toSet());

        // 卡片只要用到任何被標記敏感的欄位就整張跳過，不送進異常檢查
        List<ChartSpec> eligibleSpecs = new ArrayList<>();
        List<ChartData> eligibleData = new ArrayList<>();
        List<ChartSpec> layout = c.layout();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec spec = layout.get(i);
            if (UniversalTransformer.referencedFields(spec.mapping()).stream().noneMatch(sensitive::contains)) {
```

換成

```java
        DashboardConfig c = dashboards.get(userId, dashboardId);
        List<ChartData> data = dashboardData.load(userId, c);
        // 每個來源各自的敏感欄位（多來源 spec 第 7 節）
        Map<UUID, Set<String>> sensitive = datasources.list(userId).stream().collect(Collectors.toMap(Datasource::id,
            ds -> ds.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name).collect(Collectors.toSet())));

        // 卡片只要用到它自己來源裡任何被標記敏感的欄位就整張跳過，不送進異常檢查；
        // 清單裡找不到的來源查不到敏感設定，也不送
        List<ChartSpec> eligibleSpecs = new ArrayList<>();
        List<ChartData> eligibleData = new ArrayList<>();
        List<ChartSpec> layout = c.layout();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec spec = layout.get(i);
            Set<String> fields = sensitive.get(spec.datasourceId());
            if (fields != null && UniversalTransformer.referencedFields(spec.mapping()).stream().noneMatch(fields::contains)) {
```

（迴圈後面的程式不動。）

- [ ] **Step 7: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,JevControllerTest'`
Expected: PASS

- [ ] **Step 8: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 163, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add backend/src/main/java/jev/dashboard/transform/ChartData.java backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java && git commit -m "feat(backend): 依來源分組載入，部分來源失敗只影響自己的卡片" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/transform/ChartData.java backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java
```

---

### Task 3: 拿掉儀表板層的來源、依據分組、V2 遷移

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__per_chart_datasource.sql`、`backend/src/test/java/jev/dashboard/dashboard/DashboardMigrationTest.java`
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`、`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`
- Test: `backend/src/test/java/jev/dashboard/TestData.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`、`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`、`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`、`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes：Task 1 的卡片驗證（每張圖都有 `datasourceId`）、Task 2 的 `DashboardDataService`（不讀儀表板層的來源）
- Produces：
  - `DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout, List<String> intents, List<Basis> basis)`，巢狀 `record Basis(UUID datasourceId, List<String> intents)`；另有 `DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout)`。JSON 省略 null。
  - `DashboardRepository.DashboardSummary(UUID id, String name, OffsetDateTime updatedAt)`；`config_json` 是 `{layout, basis?}`。
  - `DashboardService.requireComplete` 的缺欄位訊息改成「name、layout 都必填」；`static UUID requireBatch(List<ChartSpec>)`（package-private，Task 4 會用）：空的回「沒有要存入的圖表」，混著來源回「一次存入的圖表要來自同一個資料來源」，否則回那個來源；`static void requireValidBasis(List<Basis>)`（package-private）。
  - `POST /api/dashboards` body `{name, layout, intents?}`：至少一張、同一個來源、來源是自己的；`intents` 不是空的就變成第一組 `basis`。回傳 `GET` 的格式（有 `basis`、沒有 `intents`）。
  - `PUT /api/dashboards/{id}` body `{name, layout, basis}`：`layout` 可以是空的、可以混著來源；每個來源都要是自己的；刪掉已經沒有圖的 `basis` 分組；`intents` 不理。
  - `AnalyzeService` 回傳的 `DashboardConfig` 沒有儀表板層的來源。

- [ ] **Step 1: 寫遷移測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardMigrationTest.java`：

```java
package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** V2：舊儀表板的來源寫進每張圖、intents 變成一組 basis，最後刪掉 datasource_id。用自己的 schema，不動測試共用的資料。 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardMigrationTest {
    static final String SCHEMA = "jev_dashboard_v2_migration_test";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired DataSource dataSource;

    @Test
    void v2MovesTheDatasourceIntoEveryCardAndTurnsIntentsIntoBasis() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop schema if exists " + SCHEMA + " cascade").update();
        flyway("1").migrate();

        UUID ds = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".datasource (id, name, kind, schema_json, created_by)"
                + " values (:id, '銷售', 'upload', '[]', 'u1')")
            .param("id", ds).update();
        UUID withIntents = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"},{"chartId":"c2","type":"table"}],"intents":["各區每月營收","不要圓餅圖"]}""");
        UUID emptyIntents = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"}],"intents":[]}""");
        UUID plain = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"}]}""");
        UUID empty = insertDashboard(jdbc, ds, """
            {"layout":[]}""");

        flyway(null).migrate();

        assertThat(config(jdbc, withIntents)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%1$s"},{"chartId":"c2","type":"table","datasourceId":"%1$s"}],
             "basis":[{"datasourceId":"%1$s","intents":["各區每月營收","不要圓餅圖"]}]}""".formatted(ds)));
        assertThat(config(jdbc, emptyIntents)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%s"}]}""".formatted(ds)));
        assertThat(config(jdbc, plain)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%s"}]}""".formatted(ds)));
        assertThat(config(jdbc, empty)).isEqualTo(JSON.readTree("{\"layout\":[]}"));
        assertThat(jdbc.sql("select count(*) from information_schema.columns"
                + " where table_schema = :s and table_name = 'dashboard' and column_name = 'datasource_id'")
            .param("s", SCHEMA).query(Long.class).single()).isZero();

        jdbc.sql("drop schema " + SCHEMA + " cascade").update();
    }

    private Flyway flyway(String target) {
        FluentConfiguration conf = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).createSchemas(true)
            .table("jev_flyway_history");
        if (target != null) conf.target(target);
        return conf.load();
    }

    private static UUID insertDashboard(JdbcClient jdbc, UUID ds, String config) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".dashboard (id, name, datasource_id, created_by, config_json)"
                + " values (:id, 'd', :ds, 'u1', cast(:config as jsonb))")
            .param("id", id).param("ds", ds).param("config", config).update();
        return id;
    }

    private static com.fasterxml.jackson.databind.JsonNode config(JdbcClient jdbc, UUID id) throws Exception {
        return JSON.readTree(jdbc.sql("select config_json::text from " + SCHEMA + ".dashboard where id = :id")
            .param("id", id).query(String.class).single());
    }
}
```

- [ ] **Step 2: 寫 DashboardControllerTest 的測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`：在 `create(...)` helper 後面加：

```java
    static String card(String chartId, String ds, int y) {
        return """
            {"chartId":"%s","datasourceId":"%s","type":"bar","title":"amount 依 region",
             "gridPos":{"x":0,"y":%d,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}"""
            .formatted(chartId, ds, y);
    }
```

`invalidCardsAreRejectedOnCreateUpdateAndPreview` 裡的第一個請求 body 拿掉最上層的來源，把

```java
                .content("{\"name\":\"壞\",\"datasourceId\":\"" + ds + "\",\"layout\":[null]}"))
```

換成

```java
                .content("{\"name\":\"壞\",\"layout\":[null]}"))
```

`intentsAreSavedReadBackAndValidated` 整個換成下面的 `intentsBecomeTheFirstBasisGroupAndAreValidated`（spec 4.2：已存的儀表板不再回傳 `intents`，改成 `basis`；PUT 不理 `intents`，驗證的是 `basis` 裡的句子）：

```java
    @Test
    void intentsBecomeTheFirstBasisGroupAndAreValidated() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String withIntents = json.replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\",\"不要圓餅圖\"],");

        String id = create("u1", withIntents);
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").doesNotExist())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(ds))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區每月營收", "不要圓餅圖")));

        // 沒有需求紀錄就沒有依據
        String plain = create("u1", json);
        mvc.perform(get("/api/dashboards/" + plain).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis").doesNotExist());

        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"intents\":[\" \"],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("需求紀錄不能有空白的句子"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"basis\":[{\"datasourceId\":\""
                    + ds + "\",\"intents\":[\"" + "營".repeat(201) + "\"]}],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("想看什麼最多 200 字"));
    }
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void createNeedsAtLeastOneCardAllFromOneSource() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String other = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"空\",\"layout\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("沒有要存入的圖表"));
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"混\",\"layout\":[" + card("c1", ds, 0) + "," + card("c2", other, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("一次存入的圖表要來自同一個資料來源"));
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺\",\"layout\":[" + card("c1", ds, 0).replace("\"datasourceId\":\"" + ds + "\",", "") + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("圖表 c1：缺少 datasourceId"));
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void putChecksEverySourceAndDropsBasisGroupsThatLostTheirCharts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String strangers = TestData.uploadSales(mvc, "u2");
        String id = create("u1", "{\"name\":\"週報\",\"intents\":[\"各區每月營收\"],\"layout\":[" + card("c1", sales, 0) + "]}");
        String basis = "\"basis\":[{\"datasourceId\":\"" + sales + "\",\"intents\":[\"各區每月營收\"]},"
            + "{\"datasourceId\":\"" + returns + "\",\"intents\":[\"各區退貨\"]}]";

        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c1", sales, 0) + "," + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[*].datasourceId").value(contains(sales, returns)))
            .andExpect(jsonPath("$.basis[*].datasourceId").value(contains(sales, returns)));

        // 銷售的圖全部拿掉：銷售那組依據跟著刪
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(returns))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區退貨")));

        // backlog 第 42 行：圖指到別人的來源就擋下來
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", strangers, 0) + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"basis\":[{\"intents\":[\"各區退貨\"]}],\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("依據缺少 datasourceId"));

        // 最後一張也可以拿掉：layout 變成空的，依據也沒了
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.length()").value(0))
            .andExpect(jsonPath("$.basis").doesNotExist());
    }

    @Test
    void theListNoLongerCarriesADatasourceId() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        create("u1", TestData.dashboardJson(ds));
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].name").value("銷售總覽"))
            .andExpect(jsonPath("$[0].updatedAt").exists())
            .andExpect(jsonPath("$[0].datasourceId").doesNotExist());
    }
```

- [ ] **Step 3: 改其他既有測試配合新的建構子和 JSON**

`backend/src/test/java/jev/dashboard/TestData.java`：`dashboardJson` 拿掉最上層的來源，把

```java
            {"name":"銷售總覽","datasourceId":"%1$s","layout":[
```

換成

```java
            {"name":"銷售總覽","layout":[
```

`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`：

- `config(...)` 裡的 `new DashboardConfig(null, "銷售總覽", UUID.randomUUID(), null, Arrays.asList(cards))` 換成 `new DashboardConfig(null, "銷售總覽", null, Arrays.asList(cards))`
- `withIntents(...)` 裡的 `new DashboardConfig(null, "銷售總覽", UUID.randomUUID(), null, List.of(), intents)` 換成 `new DashboardConfig(null, "銷售總覽", null, List.of(), intents, null)`
- `blankNameIsRejected` 裡的 `new DashboardConfig(null, "  ", UUID.randomUUID(), null, List.of())` 換成 `new DashboardConfig(null, "  ", null, List.of())`
- 在 class 最後一個 `}` 前面加：

```java
    @Test
    void basisGroupsNeedADatasourceIdAndValidIntents() {
        assertThatThrownBy(() -> DashboardService.requireValidBasis(Arrays.asList(new DashboardConfig.Basis(DS, List.of("看營收")), null)))
            .isInstanceOf(ApiException.class).hasMessage("依據缺少 datasourceId");
        assertThatThrownBy(() -> DashboardService.requireValidBasis(List.of(new DashboardConfig.Basis(null, List.of("看營收")))))
            .hasMessage("依據缺少 datasourceId");
        assertThatThrownBy(() -> DashboardService.requireValidBasis(List.of(new DashboardConfig.Basis(DS, List.of(" ")))))
            .hasMessage("需求紀錄不能有空白的句子");
        assertThatCode(() -> DashboardService.requireValidBasis(null)).doesNotThrowAnyException();
    }
```

`backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`：三處建構子拿掉儀表板層的來源：

- `new DashboardConfig(null, "x", id, "u1", List.of(bar, gone))` 換成 `new DashboardConfig(null, "x", "u1", List.of(bar, gone))`
- `new DashboardConfig(null, "x", id, "u1", List.of(bar, named))` 換成 `new DashboardConfig(null, "x", "u1", List.of(bar, named))`
- `config(...)` helper 裡的 `new DashboardConfig(null, "x", null, "u1", List.of(layout))` 換成 `new DashboardConfig(null, "x", "u1", List.of(layout))`

`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`：`new DashboardConfig(null, "銷售 總覽", UUID.randomUUID(), null, saved)` 換成 `new DashboardConfig(null, "銷售 總覽", null, saved)`。

`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`：刪掉 `runsTwoRoundsAndSkipsSamplesOfSensitiveFields` 裡的 `assertThat(c.datasourceId()).isEqualTo(ds);` 這一行（下一行 Task 1 加的 `containsOnly(ds)` 留著）。

`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`：

- `highlightSkipsCardsThatReferenceSensitiveFields` 的 JSON 裡，`{"name":"分區銷售","datasourceId":"%1$s","layout":[` 換成 `{"name":"分區銷售","layout":[`
- `highlightChecksSensitiveFieldsAgainstEachCardsOwnSource` 的兩個 body 裡，`"{\"name\":\"兩個來源\",\"datasourceId\":\"" + plain + "\",\"layout\":["` 都換成 `"{\"name\":\"兩個來源\",\"layout\":["`

- [ ] **Step 4: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardMigrationTest,DashboardControllerTest,DashboardServiceTest'`
Expected: FAIL，編譯錯誤，例如 `constructor DashboardConfig in record DashboardConfig cannot be applied to given types`、`cannot find symbol ... class Basis`

- [ ] **Step 5: 寫 migration**

`backend/src/main/resources/db/migration/V2__per_chart_datasource.sql`：

```sql
-- 多來源 spec 第 5 節：來源從 dashboard.datasource_id 搬到 config_json 的每張圖上，
-- 舊的 intents 變成只有一組的 basis。卡片順序不變；layout 是空陣列時 jsonb_agg 回 NULL，用 coalesce 補回 []。
update dashboard d
set config_json = jsonb_set(d.config_json - 'intents', '{layout}',
        coalesce((select jsonb_agg(e || jsonb_build_object('datasourceId', d.datasource_id) order by n)
                  from jsonb_array_elements(d.config_json -> 'layout') with ordinality as t(e, n)),
                 '[]'::jsonb))
    || case when jsonb_typeof(d.config_json -> 'intents') = 'array' and d.config_json -> 'intents' <> '[]'::jsonb
            then jsonb_build_object('basis', jsonb_build_array(
                     jsonb_build_object('datasourceId', d.datasource_id, 'intents', d.config_json -> 'intents')))
            else '{}'::jsonb
       end;

-- 外鍵跟著欄位一起刪；目前沒有刪除資料來源的 API，不會出現指到不存在來源的圖
alter table dashboard drop column datasource_id;
```

- [ ] **Step 6: 實作 DashboardConfig**

`backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java` 整個換成：

```java
package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

/**
 * 來源記在每張圖上（ChartSpec.datasourceId），儀表板本身不綁來源。
 * intents：一次分析的需求紀錄（選填），分析結果、建立和「加到既有儀表板」時帶；已存的儀表板不回傳。
 * basis：已存儀表板的依據，每組是一次分析的來源和需求紀錄，照加入順序排；沒有時是 null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout,
                              List<String> intents, List<Basis> basis) {
    public record Basis(UUID datasourceId, List<String> intents) {}

    /** 沒有需求紀錄、也沒有依據的設定。 */
    public DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout) {
        this(id, name, createdBy, layout, null, null);
    }
}
```

- [ ] **Step 7: 實作 DashboardRepository**

`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`：

import 加上 `import jev.dashboard.dashboard.DashboardConfig.Basis;`。

把

```java
    public record DashboardSummary(UUID id, String name, UUID datasourceId, OffsetDateTime updatedAt) {}

    /** config_json 的內容。intents 是 null 時不寫出來，所以舊資料和沒有需求的儀表板長得一樣。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stored(List<ChartSpec> layout, List<String> intents) {}
```

換成

```java
    public record DashboardSummary(UUID id, String name, OffsetDateTime updatedAt) {}

    /** config_json 的內容。basis 是 null 時不寫出來，沒有依據的儀表板就只有 layout。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stored(List<ChartSpec> layout, List<Basis> basis) {}
```

`insert` 整個換成

```java
    public UUID insert(DashboardConfig c, String userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into dashboard (id, name, created_by, config_json)
                values (:id, :name, :by, cast(:config as jsonb))""")
            .param("id", id)
            .param("name", c.name())
            .param("by", userId)
            .param("config", write(new Stored(c.layout(), c.basis())))
            .update();
        return id;
    }
```

`list` 整個換成

```java
    public List<DashboardSummary> list(String userId) {
        return jdbc.sql("select id, name, updated_at from dashboard where created_by = :by order by updated_at desc")
            .param("by", userId)
            .query((rs, n) -> new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }
```

`update` 裡的 `.param("config", write(new Stored(c.layout(), c.intents())))` 換成 `.param("config", write(new Stored(c.layout(), c.basis())))`。

`map` 最後的 return 換成

```java
        return new DashboardConfig(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("created_by"),
            stored.layout(), null, stored.basis());
```

- [ ] **Step 8: 實作 DashboardService**

`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`：

import 加上

```java
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig.Basis;
```

`create` 和 `update` 換成

```java
    /** body 的 intents 變成第一組依據；body 帶了 basis 也不理。 */
    public DashboardConfig create(String userId, DashboardConfig c) {
        requireComplete(c);
        UUID ds = requireBatch(c.layout());
        datasources.get(userId, ds);
        List<Basis> basis = c.intents() == null || c.intents().isEmpty() ? null : List.of(new Basis(ds, c.intents()));
        return get(userId, repo.insert(new DashboardConfig(null, c.name(), null, c.layout(), null, basis), userId));
    }
```

```java
    /** 拖拉版面、移除單張圖都走這裡：layout 可以是空的、可以混著不同來源；body 帶了 intents 也不理。 */
    public DashboardConfig update(String userId, UUID id, DashboardConfig c) {
        requireComplete(c);
        requireValidBasis(c.basis());
        get(userId, id); // 別人的儀表板先回「找不到儀表板」，才輪到查來源
        Set<UUID> used = c.layout().stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        for (UUID ds : used) datasources.get(userId, ds);
        // 已經沒有任何圖的來源，它的依據分組跟著刪掉
        List<Basis> basis = c.basis() == null ? List.of()
            : c.basis().stream().filter(b -> used.contains(b.datasourceId())).toList();
        if (!repo.update(id, userId, new DashboardConfig(id, c.name(), null, c.layout(), null, basis.isEmpty() ? null : basis))) {
            throw notFound();
        }
        return get(userId, id);
    }
```

`requireComplete` 換成

```java
    /** 存檔和預覽前檢查每張卡片，免得存進去的配置之後每次載入 /data 都 500。 */
    public static void requireComplete(DashboardConfig c) {
        if (c == null || c.name() == null || c.layout() == null) {
            throw new ApiException(BAD_REQUEST, "name、layout 都必填");
        }
        if (c.name().isBlank()) throw new ApiException(BAD_REQUEST, "儀表板名稱不能空白");
        requireValidCards(c.layout());
        requireValidIntents(c.intents());
    }

    /** 建立和加入時，一批圖就是一次分析的結果：至少一張，而且來自同一個來源。回傳那個來源。卡片要先驗證過。 */
    static UUID requireBatch(List<ChartSpec> layout) {
        if (layout.isEmpty()) throw new ApiException(BAD_REQUEST, "沒有要存入的圖表");
        Set<UUID> sources = layout.stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        if (sources.size() > 1) throw new ApiException(BAD_REQUEST, "一次存入的圖表要來自同一個資料來源");
        return sources.iterator().next();
    }

    /** 每一組依據都要有來源，需求紀錄照 requireValidIntents 的規則。 */
    static void requireValidBasis(List<Basis> basis) {
        if (basis == null) return;
        for (Basis b : basis) {
            if (b == null || b.datasourceId() == null) throw new ApiException(BAD_REQUEST, "依據缺少 datasourceId");
            requireValidIntents(b.intents());
        }
    }
```

- [ ] **Step 9: AnalyzeService 回傳的設定拿掉儀表板層的來源**

`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`：把

```java
        return new DashboardConfig(null, name, datasourceId, null, layout, intents.isEmpty() ? null : intents);
```

換成

```java
        return new DashboardConfig(null, name, null, layout, intents.isEmpty() ? null : intents, null);
```

- [ ] **Step 10: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardMigrationTest,DashboardControllerTest,DashboardServiceTest'`
Expected: PASS

- [ ] **Step 11: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 168, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 12: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add backend/src/main/resources/db/migration/V2__per_chart_datasource.sql backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/dashboard/DashboardMigrationTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java && git commit -m "feat(backend): 儀表板不再綁單一來源，依據改成按每次分析分組，V2 遷移舊資料" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/resources/db/migration/V2__per_chart_datasource.sql backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/dashboard/DashboardMigrationTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java
```

---

### Task 4: 加到既有儀表板 `POST /api/dashboards/{id}/charts`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardController.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Consumes：Task 3 的 `DashboardService.requireBatch`、`DashboardConfig.Basis`、`DashboardControllerTest.card(...)`
- Produces（前端 Task 5、7 會用到）：
  - `POST /api/dashboards/{id}/charts`，body `{layout: ChartSpec[], intents?: string[]}`（帶了 `name` 也不理），回傳跟 `GET /api/dashboards/{id}` 一樣格式的完整設定。
  - `ConfigAssembler.maxChartNumber(List<ChartSpec>)` 改成 public。
  - `DashboardRepository.findForUpdate(UUID id, String userId) → Optional<DashboardConfig>`。

- [ ] **Step 1: 寫測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`：import 加上 `import java.util.UUID;`。在 `card(...)` helper 後面加：

```java
    static String tableCard(String chartId, String ds, int y) {
        return """
            {"chartId":"%s","datasourceId":"%s","type":"table","title":"明細",
             "gridPos":{"x":0,"y":%d,"w":12,"h":5},"mapping":{"columns":["region"]}}""".formatted(chartId, ds, y);
    }

    void appendFails(String id, String body, int code, String message) throws Exception {
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(code))
            .andExpect(jsonPath("$.error").value(message));
    }
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void appendRenumbersTheBatchAndStacksItBelowTheExistingCharts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String id = create("u1", TestData.dashboardJson(sales).replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\"],"));
        // 預覽裡的一批圖：chartId 跟既有的重複也沒關係，會重新編號；name 不理
        String batch = "{\"name\":\"亂改\",\"intents\":[\"各區退貨\"],\"layout\":["
            + card("c1", returns, 0) + "," + tableCard("c2", returns, 4) + "]}";

        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("銷售總覽"))
            .andExpect(jsonPath("$.layout[*].chartId").value(contains("c1", "c2", "c3", "c4", "c5", "c6")))
            // 既有的圖最下面到 y 4 + h 4 = 8：整批往下移 8，彼此的相對位置不變
            .andExpect(jsonPath("$.layout[4].gridPos.y").value(8))
            .andExpect(jsonPath("$.layout[5].gridPos.y").value(12))
            .andExpect(jsonPath("$.layout[5].gridPos.w").value(12))
            .andExpect(jsonPath("$.layout[4].datasourceId").value(returns))
            .andExpect(jsonPath("$.basis[*].datasourceId").value(contains(sales, returns)))
            .andExpect(jsonPath("$.basis[1].intents").value(contains("各區退貨")));

        // 沒有需求紀錄：圖照加，依據不多一組
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"layout\":[" + card("c9", returns, 3) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[6].chartId").value("c7"))
            .andExpect(jsonPath("$.layout[6].gridPos.y").value(17))
            .andExpect(jsonPath("$.basis.length()").value(2));
    }

    @Test
    void appendIsValidatedAndScopedToTheOwner() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String strangers = TestData.uploadSales(mvc, "u2");
        String id = create("u1", TestData.dashboardJson(sales));
        String ok = "{\"layout\":[" + card("c1", returns, 0) + "]}";

        appendFails(UUID.randomUUID().toString(), ok, 404, "找不到儀表板");
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(ok))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        appendFails(id, "{\"layout\":[]}", 400, "沒有要存入的圖表");
        appendFails(id, "{\"layout\":[" + card("c1", returns, 0) + "," + card("c2", sales, 4) + "]}", 400,
            "一次存入的圖表要來自同一個資料來源");
        appendFails(id, "{\"layout\":[" + card("c1", strangers, 0) + "]}", 404, "找不到資料源");
        appendFails(id, "{\"layout\":[" + card("c1", returns, 0).replace("\"type\":\"bar\"", "\"type\":\"donut\"") + "]}", 400,
            "圖表 c1：type 必須是 line、bar、pie、scatter、table、radar、heatmap、stacked_bar 其中之一");
        appendFails(id, "{\"intents\":[\" \"],\"layout\":[" + card("c1", returns, 0) + "]}", 400, "需求紀錄不能有空白的句子");

        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(jsonPath("$.layout.length()").value(4));
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=DashboardControllerTest`
Expected: FAIL，兩個新測試失敗（沒有這個路由，狀態碼不是預期的 200／400／404），其他照樣通過

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`：`static int maxChartNumber(List<ChartSpec> specs)` 前面加 `public`，變成 `public static int maxChartNumber(List<ChartSpec> specs)`。

`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`：在 `find` 後面加：

```java
    /** 加到既有儀表板用：讀的時候鎖住那一列，同一個 transaction 寫回之前，另一次加入要等。 */
    public Optional<DashboardConfig> findForUpdate(UUID id, String userId) {
        return jdbc.sql("select * from dashboard where id = :id and created_by = :by for update")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`：import 加上

```java
import java.util.ArrayList;
import jev.dashboard.jev.ConfigAssembler;
import org.springframework.transaction.annotation.Transactional;
```

在 `update` 後面加：

```java
    /**
     * 加到既有儀表板（多來源 spec 6.3）：這批圖的 chartId 接著既有的編號，整批往下移到既有圖的最下面，
     * 彼此的相對位置不變；有需求紀錄就追加一組依據，名稱不變。
     * 讀到寫在同一個 transaction，讀的時候鎖住那一列，兩次同時加入不會吃掉其中一批。
     */
    @Transactional
    public DashboardConfig append(String userId, UUID id, DashboardConfig batch) {
        DashboardConfig current = repo.findForUpdate(id, userId).orElseThrow(DashboardService::notFound);
        List<ChartSpec> charts = batch == null || batch.layout() == null ? List.of() : batch.layout();
        requireValidCards(charts);
        UUID ds = requireBatch(charts);
        List<String> intents = batch == null ? null : batch.intents();
        requireValidIntents(intents);
        datasources.get(userId, ds);

        int n = ConfigAssembler.maxChartNumber(current.layout());
        int offset = current.layout().stream().mapToInt(s -> s.gridPos().y() + s.gridPos().h()).max().orElse(0);
        int top = charts.stream().mapToInt(s -> s.gridPos().y()).min().orElse(0);
        List<ChartSpec> layout = new ArrayList<>(current.layout());
        for (ChartSpec s : charts) {
            GridPos g = s.gridPos();
            layout.add(new ChartSpec("c" + (++n), s.type(), s.title(), new GridPos(g.x(), g.y() - top + offset, g.w(), g.h()),
                s.mapping(), s.options(), s.datasourceId()));
        }
        List<Basis> basis = new ArrayList<>(current.basis() == null ? List.of() : current.basis());
        if (intents != null && !intents.isEmpty()) basis.add(new Basis(ds, intents));
        repo.update(id, userId, new DashboardConfig(id, current.name(), null, layout, null, basis.isEmpty() ? null : basis));
        return get(userId, id);
    }
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardController.java`：在 `update` 後面加：

```java
    @PostMapping("/{id}/charts")
    public DashboardConfig append(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                  @RequestBody DashboardConfig body) {
        return service.append(userId, id, body);
    }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=DashboardControllerTest`
Expected: PASS

- [ ] **Step 5: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 170, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add backend/src/main/java/jev/dashboard/dashboard/DashboardController.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java && git commit -m "feat(backend): 加到既有儀表板 API，新圖重新編號並接在最下面" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/dashboard/DashboardController.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java
```

---

### Task 5: 前端 API 與卡片：appendCharts、錯誤狀態碼、來源名稱、SOURCE_FAILED

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`、`frontend/src/modules/dashboard/ChartCard.vue`
- Test: `frontend/src/modules/dashboard/api.test.js`、`frontend/src/modules/dashboard/ChartCard.test.js`

**Interfaces:**
- Consumes：Task 2 的 `ChartData.source`、`SOURCE_FAILED`；Task 4 的 `POST /api/dashboards/{id}/charts`
- Produces（Task 6、7 會用到）：
  - `api.appendCharts(id, { layout, intents })` → `POST /api/dashboards/{id}/charts`，回傳更新後的設定。
  - `api` 的每個 reject 都是 `Error`，`message` 跟以前一樣，另外多一個 `status`（HTTP 狀態碼；連不上伺服器時沒有）。
  - `ChartCard` 多一個 prop `source`（String，預設 null）：有值就在標題右邊顯示 `.tc-source`。`removable` 為 true 就顯示「✕」，不再另外排除明細表（改由 DashboardView 決定）。`data.error === 'SOURCE_FAILED'` 時顯示警告。

- [ ] **Step 1: 裝前端套件（worktree 沒有 node_modules）**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm ci > /dev/null 2>&1 && npm test 2>&1 | grep -E "Tests +[0-9]+" | tail -1`
Expected: `Tests  129 passed (129)`。數字不同就記下實際基準，之後照「基準＋新增數」算。

- [ ] **Step 2: 寫 api 的測試**

`frontend/src/modules/dashboard/api.test.js`：在 `describe('api', ...)` 最後一個 `it` 後面加：

```js
  it('appendCharts POSTs the batch to /api/dashboards/{id}/charts', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ id: 'd1' }))
    await api.appendCharts('d1', { layout: [{ chartId: 'c1' }], intents: ['各區退貨'] })
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/dashboards/d1/charts')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ layout: [{ chartId: 'c1' }], intents: ['各區退貨'] }))
  })

  it('a rejected request also carries the HTTP status, so callers can tell a 404 apart', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ error: '找不到儀表板' }, { status: 404 }))
    await expect(api.getDashboard('d1')).rejects.toMatchObject({ message: '找不到儀表板', status: 404 })
  })
```

- [ ] **Step 3: 寫 ChartCard 的測試**

`frontend/src/modules/dashboard/ChartCard.test.js`：

把既有的 `'✕: shown on removable chart cards only (not on the table, not when not removable), named 移除這張圖, emits remove'` 整個換成下面這個（明細表要不要有「✕」改由 DashboardView 決定，見 Task 6）：

```js
  it('✕: shown only when removable (DashboardView decides which cards), named 移除這張圖, emits remove', async () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true })
    await card.get('button[aria-label="移除這張圖"]').trigger('click')
    expect(card.emitted('remove')).toHaveLength(1)

    expect(mountCard({ spec, data: null, highlight: null }).find('button[aria-label="移除這張圖"]').exists()).toBe(false)
    const tableCard = mountCard({ spec: { ...spec, type: 'table' }, data: null, highlight: null, removable: true })
    expect(tableCard.find('button[aria-label="移除這張圖"]').exists()).toBe(true)
  })
```

在 `describe('ChartCard', ...)` 最後一個 `it` 後面加：

```js
  it('source: the source name sits after the title in small muted text, truncated with the full name on hover; none without it', () => {
    const card = mountCard({ spec: barSpec, data: null, highlight: null, source: 'R7 Excel 退貨' })
    const tag = card.get('.tc-card-title .tc-source')
    expect(tag.text()).toBe('R7 Excel 退貨')
    expect(tag.attributes('title')).toBe('R7 Excel 退貨')
    expect(tag.classes()).toContain('text-truncate')
    expect(mountCard({ spec: barSpec, data: null, highlight: null }).find('.tc-source').exists()).toBe(false)
  })

  it('SOURCE_FAILED: a warning names the source when it is known, and no chart is drawn', () => {
    const failed = (source) => mountCard({
      spec: barSpec,
      data: { chartId: 'c1', type: 'bar', error: 'SOURCE_FAILED', ...(source && { source }) },
      highlight: null,
    })
    const named = failed('銷售 API')
    expect(named.text()).toContain('資料來源「銷售 API」載入失敗，請稍後重新整理')
    expect(named.find('.v-chart-stub').exists()).toBe(false)
    expect(failed().text()).toContain('資料來源載入失敗，請稍後重新整理')
  })
```

- [ ] **Step 4: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/api.test.js src/modules/dashboard/ChartCard.test.js`
Expected: FAIL，新加和改寫的 5 個測試失敗（例如 `api.appendCharts is not a function`、`Unable to get .tc-card-title .tc-source`、明細表找不到「✕」），其他照樣通過

- [ ] **Step 5: 實作 api.js**

`frontend/src/modules/dashboard/api.js`：把

```js
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || `HTTP ${res.status}`)
  }
```

換成

```js
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    // 帶上狀態碼，呼叫端才分得出「找不到」（例如儀表板在別的分頁被刪了）
    throw Object.assign(new Error(err.error || `HTTP ${res.status}`), { status: res.status })
  }
```

在 `updateDashboard: ...` 那一行後面加：

```js
  // 多來源 spec 6.3：把預覽裡的一批圖接在既有儀表板最下面，回傳更新後的設定
  appendCharts: (id, batch) => request('POST', `/api/dashboards/${id}/charts`, batch),
```

- [ ] **Step 6: 實作 ChartCard.vue**

`frontend/src/modules/dashboard/ChartCard.vue`：

props 裡把

```js
  // 預覽裡才有「✕」；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
```

換成

```js
  // 「✕」：哪些卡片有由 DashboardView 決定（預覽的明細表沒有）；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
  // 多來源的儀表板才傳：這張圖的來源名稱
  source: { type: String, default: null },
```

標題列把

```html
      <span class="text-truncate">{{ data?.title ?? spec.title }}</span>
      <v-spacer />
```

換成

```html
      <span class="text-truncate">{{ data?.title ?? spec.title }}</span>
      <!-- 多來源 spec 3.2：名稱太長就截斷，滑鼠移上去看得到全名 -->
      <span v-if="source" class="tc-source text-truncate" :title="source">{{ source }}</span>
      <v-spacer />
```

「✕」的條件把

```html
        v-if="removable && spec.type !== 'table'"
```

換成

```html
        v-if="removable"
```

在 FIELD_MISSING 的 `v-alert` 後面加：

```html
      <v-alert v-else-if="data.error === 'SOURCE_FAILED'" type="warning" density="compact" class="tc-fade-in">
        {{ data.source ? `資料來源「${data.source}」載入失敗，請稍後重新整理` : '資料來源載入失敗，請稍後重新整理' }}
      </v-alert>
```

`<style scoped>` 裡 `.card-body` 那一行後面加：

```css
/* 多來源 spec 3.2：標題右邊的來源名稱，次要文字色的小字 */
.tc-source {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 40%;
  font-size: 12px;
  font-weight: 400;
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
}
```

- [ ] **Step 7: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/api.test.js src/modules/dashboard/ChartCard.test.js`
Expected: PASS

- [ ] **Step 8: 跑全部前端測試和 build**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected: `Tests  133 passed (133)`，build 成功（`✓ built in …`）

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js && git commit -m "feat(frontend): appendCharts、錯誤帶狀態碼，卡片標出來源與來源載入失敗" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js
```

---

### Task 6: 已存儀表板：移除單張圖、空狀態、依據分組、來源名稱

**Files:**
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`
- Test: `frontend/src/modules/dashboard/DashboardView.test.js`

**Interfaces:**
- Consumes：Task 5 的 `ChartCard` prop `source`、`removable`；Task 3 的 `config.basis`、每張圖的 `datasourceId`；Task 2 的 `ChartData.source`
- Produces（Task 7 的 App 會用到）：
  - `DashboardView` 多一個事件 `updated(config)`：已存儀表板移除一張圖、PUT 成功後，帶著後端回傳的設定送出。
  - 預覽模式照舊送出 `remove(chartId)`，不跳確認。

- [ ] **Step 1: 寫測試**

`frontend/src/modules/dashboard/DashboardView.test.js`：

ChartCard 的 mock 多收一個 `source`，把

```js
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled'],
```

換成

```js
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled', 'source'],
```

`chartCard` helper 後面加：

```js
const dialogButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
const withSource = (spec, datasourceId) => ({ ...spec, datasourceId })
```

`afterEach` 換成（確認對話框會掛在 body 上，測完要清掉）：

```js
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })
```

既有的 `'preview: cards get ✕ (disabled while busy), ...'` 只改一行預期：預覽裡的明細表現在沒有「✕」（實作上的決定第 6 點），把

```js
    expect(cards.map((c) => [c.props('removable'), c.props('removeDisabled')])).toEqual([[true, true], [true, true]])
```

換成

```js
    expect(cards.map((c) => [c.props('removable'), c.props('removeDisabled')])).toEqual([[true, true], [false, true]])
```

既有的 `'saved dashboards: no ✕, and the intents show as 依據 joined by →; previews and dashboards without intents show no 依據'` 整個換成下面這個（已存的儀表板每張卡片都有「✕」，依據改從 `basis` 來，見 spec 3.3、3.4）：

```js
  it('✕: every card of a saved dashboard has one, the table too; in a preview the table has none and no 依據 shows', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue([])
    const saved = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    expect(saved.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([true, true])

    api.previewData.mockResolvedValue([])
    const inPreview = mountView({ config: { ...makeConfig(), basis: [{ datasourceId: 'ds1', intents: ['各區每月營收'] }] }, preview: true })
    await flushPromises()
    expect(inPreview.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([true, false])
    expect(inPreview.text()).not.toContain('依據')
  })
```

在 `describe('DashboardView', ...)` 最後一個 `it` 後面加：

```js
  it('saved: ✕ asks first; 取消 does nothing; 移除 PUTs the current grid without that card and emits updated with the reply', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1', title: '金額 依 地區' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue([])
    const reply = { ...makeConfig(), layout: [makeConfig().layout[1]] }
    api.updateDashboard.mockResolvedValue(reply)
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    // 使用者拖過明細表、還沒重新整理：移除 c1 時要用格線上現在的位置
    await wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('update:layout', [
      { i: 'c1', x: 0, y: 0, w: 6, h: 4 },
      { i: 'c2', x: 0, y: 9, w: 12, h: 5 },
    ])

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    expect(document.body.querySelector('.v-card-title').textContent.trim()).toBe('移除圖表')
    expect(document.body.textContent).toContain('確定從「n」移除「金額 依 地區」？移除後無法復原，要加回來得重新分析。')
    dialogButton('取消').click()
    await flushPromises()
    expect(api.updateDashboard).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('確定從')
    expect(wrapper.emitted('remove')).toBeUndefined()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    expect(dialogButton('移除').classList.contains('bg-error')).toBe(true)
    dialogButton('移除').click()
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(1)
    const [id, next] = api.updateDashboard.mock.calls[0]
    expect(id).toBe('d1')
    expect(next.layout).toEqual([{ ...makeConfig().layout[1], gridPos: { x: 0, y: 9, w: 12, h: 5 } }])
    expect(wrapper.emitted('updated')).toEqual([[reply]])
  })

  it('saved: a failed remove shows the error, keeps the card and emits nothing', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue([])
    api.updateDashboard.mockRejectedValue(new Error('找不到儀表板'))
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()
    expect(wrapper.text()).toContain('找不到儀表板')
    expect(wrapper.find('[data-chart-id="c1"]').exists()).toBe(true)
    expect(wrapper.emitted('updated')).toBeUndefined()
  })

  it('saved: when the new config only drops a card, the rest keep their data and highlight without fetching again', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    api.highlights.mockResolvedValue([{ chartId: 'c2', severity: '需要關注', noul: 0.8 }])
    const config = { ...makeConfig(), layout: [bar('c1', 0), bar('c2', 1), table('c3', 4)] }
    const wrapper = mountView({ config, preview: false })
    await flushPromises()

    await wrapper.setProps({ config: { ...config, layout: [bar('c2', 1), table('c3', 4)] } })
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c1"]').exists()).toBe(false)
    expect(api.dashboardData).toHaveBeenCalledTimes(1)
    expect(api.highlights).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c2')).toEqual({ data: { chartId: 'c2' }, highlight: { chartId: 'c2', severity: '需要關注', noul: 0.8 } })
  })

  it('a saved dashboard with no cards says how to add charts back, instead of 找不到適合的圖表', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue([])
    const wrapper = mountView({ config: { ...makeConfig(), layout: [] }, preview: false })
    await flushPromises()
    expect(wrapper.text()).toContain('這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。')
    expect(wrapper.text()).not.toContain('找不到適合的圖表')
  })

  it('依據: one line per group; one source reads 依據：a → b, several sources name each source once /data is back (no name, no brackets)', async () => {
    api.highlights.mockResolvedValue([])
    api.dashboardData.mockResolvedValue([])
    const single = mountView({ config: { ...makeConfig(), basis: [{ datasourceId: 'ds1', intents: ['各區每月營收', '不要圓餅圖'] }] }, preview: false })
    await flushPromises()
    expect(single.findAll('.dash-basis').map((l) => l.text())).toEqual(['依據：各區每月營收 → 不要圓餅圖'])

    let resolveData
    api.dashboardData.mockImplementation(() => new Promise((resolve) => { resolveData = resolve }))
    const multi = mountView({
      config: {
        id: 'd2',
        name: 'n',
        layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds2'), withSource(bar('c3', 2), 'ds3')],
        basis: [
          { datasourceId: 'ds1', intents: ['各區每月營收'] },
          { datasourceId: 'ds2', intents: ['各區退貨', '不要圓餅圖'] },
          { datasourceId: 'ds3', intents: ['庫存'] },
        ],
      },
      preview: false,
    })
    await flushPromises()
    expect(multi.findAll('.dash-basis')).toHaveLength(0)

    resolveData([
      { chartId: 'c1', source: '銷售 API' },
      { chartId: 'c2', source: 'R7 Excel 退貨' },
      { chartId: 'c3', error: 'SOURCE_FAILED' },
    ])
    await flushPromises()
    expect(multi.findAll('.dash-basis').map((l) => l.text())).toEqual([
      '依據（銷售 API）：各區每月營收',
      '依據（R7 Excel 退貨）：各區退貨 → 不要圓餅圖',
      '依據：庫存',
    ])
  })

  it('names the source on each card only when the dashboard mixes sources', async () => {
    api.highlights.mockResolvedValue([])
    api.dashboardData.mockResolvedValue([{ chartId: 'c1', source: '銷售' }, { chartId: 'c2', source: '退貨' }])
    const mixed = mountView({ config: { id: 'd1', name: 'n', layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds2')] }, preview: false })
    await flushPromises()
    expect(['c1', 'c2'].map((id) => chartCard(mixed, id).props('source'))).toEqual(['銷售', '退貨'])

    api.dashboardData.mockResolvedValue([{ chartId: 'c1', source: '銷售' }, { chartId: 'c2', source: '銷售' }])
    const single = mountView({ config: { id: 'd2', name: 'n', layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds1')] }, preview: false })
    await flushPromises()
    expect(['c1', 'c2'].map((id) => chartCard(single, id).props('source'))).toEqual([null, null])
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/DashboardView.test.js`
Expected: FAIL，新加和改過的測試失敗（例如 `expected [ true, true ] to deeply equal [ true, false ]`、`Cannot read properties of null (reading 'textContent')`），其他照樣通過

- [ ] **Step 3: 實作**

`frontend/src/modules/dashboard/DashboardView.vue` 整個換成：

```vue
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import { api } from './api.js'
import { prefersReducedMotion } from './motion.js'
import './motion.css'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
  // 預覽等 JEV 回應時：圖表區變淡、標 aria-busy、「✕」停用
  busy: { type: Boolean, default: false },
})
// remove：預覽的「✕」交給製圖頁處理；updated：已存儀表板移除一張圖後，後端回傳的新設定
const emit = defineEmits(['remove', 'updated'])

// ponytail: 跟 motion.css 的 --tc-dur-1 同值，改一邊要一起改
const EXIT_MS = 120

const layout = ref([])
// 退場那 120ms 裡，消失的卡片還要用舊的 spec 畫，所以不能直接從 props.config 算
const specById = ref({})
const dataById = ref({})
const highlightById = ref({})
const error = ref('')
const loadError = ref('')
const settled = ref(false)
const leaving = ref(new Set())
const entering = ref(new Set())
// 已存儀表板按「✕」之後等確認的卡片：{ chartId, title }
const confirmRemove = ref(null)

const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))
// 多來源 spec 3.2：圖來自兩個以上的來源時，卡片才標來源名稱
const multiSource = computed(() => new Set(props.config.layout.map((s) => s.datasourceId)).size > 1)

// 多來源 spec 3.3：依據一組一行。多來源時寫出來源名稱，名稱從 /data 回傳的 source 來，
// 每張卡片都有資料之前先不顯示；找不到名稱（例如來源載入失敗）就不加括號
const basisLines = computed(() => {
  const groups = props.preview ? [] : (props.config.basis ?? []).filter((b) => b.intents?.length)
  if (!multiSource.value) return groups.map((b) => `依據：${b.intents.join(' → ')}`)
  if (!props.config.layout.every((s) => dataById.value[s.chartId])) return []
  const names = {}
  for (const s of props.config.layout) names[s.datasourceId] ??= dataById.value[s.chartId].source
  return groups.map((b) => `依據${names[b.datasourceId] ? `（${names[b.datasourceId]}）` : ''}：${b.intents.join(' → ')}`)
})

// spec 4.4：錯開的順序照畫面位置（先 y 再 x）。使用者拖過之後，layout 陣列的順序不一定等於畫面順序
const screenOrder = computed(() =>
  Object.fromEntries([...layout.value].sort((a, b) => a.y - b.y || a.x - b.x).map((l, n) => [l.i, n])),
)

let loadSeq = 0
// 上一次 load 顯示的是哪一份儀表板；換了一份就一定重抓資料
let shownId
// 預覽裡最近一次被拿掉的卡片：chartId → { spec, data }；按「復原」放回同一張圖時直接沿用資料
let removed = {}
const sameChart = (a, b) => a.type === b.type && JSON.stringify(a.mapping) === JSON.stringify(b.mapping)

async function load() {
  const seq = ++loadSeq
  const next = props.config.layout
  const ids = new Set(next.map((s) => s.chartId))
  const shown = layout.value.map((l) => l.i)
  const gone = shown.filter((id) => !ids.has(id))
  // spec 10.5：消失的卡片先退場，退場結束才套用新版面；減少動態效果時不等
  if (gone.length && !prefersReducedMotion()) {
    leaving.value = new Set(gone)
    await new Promise((resolve) => setTimeout(resolve, EXIT_MS))
    if (seq !== loadSeq) return
  }
  leaving.value = new Set()
  // spec 4.4：已存的儀表板第一次顯示時每張都進場（App 用 :key 換儀表板，每次打開都是第一次）；
  // 預覽第一次顯示不進場，對話框自己有轉場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : props.preview ? [] : ids)
  // spec 10.7：按「復原」放回同一張圖時，沿用它被拿掉時的資料，不重抓、不閃「載入中」
  const restored = Object.fromEntries(next
    .filter((s) => removed[s.chartId] && sameChart(removed[s.chartId].spec, s))
    .map((s) => [s.chartId, removed[s.chartId].data]))
  removed = props.preview
    ? Object.fromEntries(gone.filter((id) => dataById.value[id])
      .map((id) => [id, { spec: specById.value[id], data: dataById.value[id] }]))
    : {}
  specById.value = Object.fromEntries(next.map((s) => [s.chartId, s]))
  layout.value = next.map((s) => ({ i: s.chartId, ...s.gridPos }))
  // spec 10.3：只清掉不在新版面裡的資料；留下來的卡片先用舊資料，等新資料回來再換
  dataById.value = {
    ...Object.fromEntries(Object.entries(dataById.value).filter(([id]) => ids.has(id))),
    ...restored,
  }
  error.value = ''
  loadError.value = ''
  // 只是少了卡片時，剩下的卡片都有資料了，不必再打 API：預覽的「✕」（spec 3.2：「✕」不呼叫 API），
  // 以及已存儀表板移除單張圖（多來源 spec 3.4：異常標註也留著）
  const sameDashboard = props.preview || props.config.id === shownId
  shownId = props.config.id
  if (sameDashboard && next.every((s) => dataById.value[s.chartId])) {
    highlightById.value = Object.fromEntries(Object.entries(highlightById.value).filter(([id]) => ids.has(id)))
    return
  }
  highlightById.value = {}
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id)
    if (seq !== loadSeq) return
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
  } catch (e) {
    if (seq !== loadSeq) return
    loadError.value = e.message
    settled.value = false // GridLayout 會被 v-if 拿掉，重新掛載時要再等一次 layout-ready
    return
  }
  if (props.preview) return
  // 異常檢查失敗就不標註，不顯示錯誤
  const found = await api.highlights(props.config.id).catch(() => [])
  if (seq !== loadSeq) return
  highlightById.value = Object.fromEntries(found.map((h) => [h.chartId, h]))
}

watch(() => props.config, load, { immediate: true })

// 卸載後，還在等退場或資料的 load() 直接作廢，不再打 API
onBeforeUnmount(() => {
  loadSeq++
})

// spec 10.2：layout-ready 那個 task 裡，grid-layout-plus 量完寬度但這一幀還沒 recalc 版面；
// 用兩層 rAF 等到下一幀才打開位移轉場，才不會跟卡片定位同一次生效，飛過畫面
function onLayoutReady() {
  requestAnimationFrame(() => requestAnimationFrame(() => (settled.value = true)))
}

// 格線上目前的位置套回每張圖；拖拉、縮放、移除都照這份存
function currentLayout() {
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  return props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] }))
}

async function saveLayout() {
  if (props.preview) return
  error.value = ''
  await api.updateDashboard(props.config.id, { ...props.config, layout: currentLayout() }).catch((e) => (error.value = e.message))
}

function askRemove(chartId) {
  confirmRemove.value = { chartId, title: dataById.value[chartId]?.title ?? specById.value[chartId].title }
}

// 多來源 spec 3.4：用格線上目前的版面（使用者拖過的位置照樣保留）拿掉這張再存；成功後交給 App 換成回傳的設定
async function removeCard() {
  if (!confirmRemove.value) return // 對話框關閉前又按了一次「移除」
  const { chartId } = confirmRemove.value
  confirmRemove.value = null
  error.value = ''
  try {
    const next = { ...props.config, layout: currentLayout().filter((s) => s.chartId !== chartId) }
    emit('updated', await api.updateDashboard(props.config.id, next))
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <v-alert v-if="loadError || error" type="error" class="mb-4">{{ loadError || error }}</v-alert>
  <!-- 多來源 spec 3.5：已存的儀表板連最後一張圖也拿掉了 -->
  <v-alert v-if="!preview && !config.layout.length" type="info" class="mb-4">
    這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。
  </v-alert>
  <v-alert v-else-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <!-- spec 3.3：已存的儀表板顯示這份設定是依據哪些需求做的，每次分析一行 -->
  <div v-for="(line, n) in basisLines" :key="n" class="dash-basis text-caption text-medium-emphasis mb-2">{{ line }}</div>
  <div class="dash-grid" :class="{ 'is-busy': busy }" :aria-busy="busy || undefined">
    <GridLayout
      v-if="!loadError"
      v-model:layout="layout"
      :class="{ 'is-settled': settled }"
      :col-num="12"
      :row-height="80"
      :margin="[14, 14]"
      :is-draggable="!preview"
      :is-resizable="!preview"
      @layout-ready="onLayoutReady"
    >
      <GridItem
        v-for="item in layout"
        :key="item.i"
        :i="item.i"
        :x="item.x"
        :y="item.y"
        :w="item.w"
        :h="item.h"
        drag-allow-from=".card-drag"
        @moved="saveLayout"
        @resized="saveLayout"
      >
        <!-- spec 10.4：動的是 GridItem 裡面的卡片；GridItem 自己的 transform 是格子位置 -->
        <ChartCard
          :spec="specById[item.i]"
          :data-chart-id="item.i"
          :data="dataById[item.i]"
          :highlight="highlightById[item.i]"
          :source="multiSource ? (dataById[item.i]?.source ?? null) : null"
          :removable="!preview || specById[item.i].type !== 'table'"
          :remove-disabled="busy"
          :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
          :style="entering.has(item.i) ? { '--tc-i': screenOrder[item.i] } : undefined"
          @remove="preview ? emit('remove', item.i) : askRemove(item.i)"
        />
      </GridItem>
    </GridLayout>
  </div>
  <!-- 多來源 spec 3.4：已存儀表板移除單張圖前先確認，樣式跟 App 的「刪除儀表板」一樣 -->
  <v-dialog :model-value="!!confirmRemove" max-width="420" @update:model-value="(v) => !v && (confirmRemove = null)">
    <v-card v-if="confirmRemove">
      <v-card-title class="pt-5 px-6">移除圖表</v-card-title>
      <v-card-text class="px-6">確定從「{{ config.name }}」移除「{{ confirmRemove.title }}」？移除後無法復原，要加回來得重新分析。</v-card-text>
      <v-card-actions class="px-6 pb-5">
        <v-spacer />
        <!-- v-card-actions 預設把按鈕變成 text，這裡明確用 flat，移除才看得出是按鈕 -->
        <v-btn variant="flat" @click="confirmRemove = null">取消</v-btn>
        <v-btn variant="flat" color="error" @click="removeCard">移除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/DashboardView.test.js`
Expected: PASS

- [ ] **Step 5: 跑全部前端測試和 build**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected: `Tests  139 passed (139)`，build 成功

- [ ] **Step 6: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js && git commit -m "feat(frontend): 已存儀表板可移除單張圖，依據分組、多來源標出來源名稱" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js
```

---

### Task 7: 製圖頁「加到既有儀表板」、App 串接

**Files:**
- Modify: `frontend/src/modules/dashboard/ChartBuilder.vue`、`frontend/src/App.vue`
- Test: `frontend/src/modules/dashboard/ChartBuilder.test.js`、`frontend/src/App.test.js`

**Interfaces:**
- Consumes：Task 5 的 `api.appendCharts`、錯誤的 `status`；Task 6 的 `DashboardView` 事件 `updated(config)`
- Produces：
  - `ChartBuilder` 多一個 prop `dashboards`（Array，預設 `[]`，元素至少有 `id`、`name`），多一個事件 `dashboards-changed`（沒有參數）；`saved(id)` 照舊，加到既有儀表板成功時也送出，帶那份儀表板的 id。
  - 「調整」送出時的來源改用左側選單目前選的來源（`datasourceId`），不再讀 `preview.datasourceId`。

- [ ] **Step 1: 寫 ChartBuilder 的測試**

`frontend/src/modules/dashboard/ChartBuilder.test.js`：

api mock 加上 `appendCharts`：把

```js
    createDashboard: vi.fn(),
```

換成

```js
    createDashboard: vi.fn(),
    appendCharts: vi.fn(),
```

按鈕改名（spec 3.1）：整個檔案的「存為我的儀表板」都換成「存成新儀表板」，包含測試名稱。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && sed -i 's/存為我的儀表板/存成新儀表板/g' src/modules/dashboard/ChartBuilder.test.js && grep -c "存成新儀表板" src/modules/dashboard/ChartBuilder.test.js`
Expected: `11`（4 個測試名稱加 7 行 `button(...)`；數字不同就用 `grep -n` 看一下，確定都換到了）

在 `toastEl` 那一行後面加 helper：

```js
const menuItems = () => [...document.body.querySelectorAll('.v-overlay-container .v-list-item')].map((el) => el.textContent.trim())
async function pickDashboard(name) {
  button('加到既有儀表板').click()
  await flushPromises()
  byText(document.body, '.v-list-item', name).click()
  await flushPromises()
}
```

在 `describe('ChartBuilder', ...)` 最後一個 `it` 後面加：

```js
  it('加到既有儀表板 shows only when there are dashboards, and lists them in the order given', async () => {
    api.analyze.mockResolvedValue({ name: 'p', layout: layout4() })
    const wrapper = await openPreview()
    expect(button('存成新儀表板')).toBeDefined()
    expect(button('加到既有儀表板')).toBeUndefined()

    await wrapper.setProps({ dashboards: [{ id: 'd2', name: '營運月報' }, { id: 'd1', name: '高階主管週報' }] })
    button('加到既有儀表板').click()
    await flushPromises()
    expect(menuItems()).toEqual(['營運月報', '高階主管週報'])
  })

  it('picking a dashboard sends the previewed charts and intents to appendCharts (a blank name is fine), emits saved with its id and empties the canvas', async () => {
    const preview = { name: 'p', intents: ['各區退貨'], layout: layout4() }
    api.analyze.mockResolvedValue(preview)
    api.appendCharts.mockResolvedValue({ id: 'd1' })
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })
    await field(wrapper, '儀表板名稱').find('input').setValue('  ')
    await flushPromises()
    expect(button('存成新儀表板').disabled).toBe(true)
    expect(button('加到既有儀表板').disabled).toBe(false)

    await pickDashboard('高階主管週報')
    expect(api.appendCharts).toHaveBeenCalledWith('d1', { layout: layout4(), intents: ['各區退貨'] })
    expect(api.createDashboard).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toEqual([['d1']])
    expect(isEmpty()).toBe(true)
  })

  it('a failed append keeps the preview and shows the error; a 404 (deleted in another tab) also asks for a fresh list', async () => {
    api.analyze.mockResolvedValue({ name: 'p', layout: layout4() })
    api.appendCharts
      .mockRejectedValueOnce(new Error('無法連線到伺服器'))
      .mockRejectedValueOnce(Object.assign(new Error('找不到儀表板'), { status: 404 }))
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })

    await pickDashboard('高階主管週報')
    expect(errorAlert(wrapper).text()).toContain('無法連線到伺服器')
    expect(view(wrapper).exists()).toBe(true)
    expect(wrapper.emitted('dashboards-changed')).toBeUndefined()

    await pickDashboard('高階主管週報')
    expect(errorAlert(wrapper).text()).toContain('找不到儀表板')
    expect(wrapper.emitted('dashboards-changed')).toHaveLength(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('加到既有儀表板 is disabled while an adjust is pending', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })
    expect(button('加到既有儀表板').disabled).toBe(false)

    await adjustWith(wrapper, '二')
    expect(button('加到既有儀表板').disabled).toBe(true)
  })

  it('調整 sends the datasource chosen on the left (the preview itself has no datasourceId)', async () => {
    const first = { name: 'p', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockResolvedValueOnce({ ...first, intents: ['一', '二'] })
    const wrapper = await mountBuilder()
    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await flushPromises()

    await runAnalyze()
    await adjustWith(wrapper, '二')
    expect(api.analyze).toHaveBeenLastCalledWith('ds2', ['一', '二'], first.layout)
  })
```

如果 happy-dom 裡點選單按鈕打不開選單（`menuItems()` 是空的），把 `pickDashboard` 和第一個測試裡的 `button('加到既有儀表板').click()` 改成 `await wrapper.findComponent({ name: 'VMenu' }).vm.$emit('update:modelValue', true)`，並在報告裡註明改了這裡；測試的預期都不改。

- [ ] **Step 2: 寫 App 的測試**

`frontend/src/App.test.js`：mock 裡的 `ChartBuilder`、`DashboardView` 換成

```js
  ChartBuilder: {
    name: 'ChartBuilder',
    props: ['active', 'request', 'dashboards'],
    emits: ['saved', 'dashboards-changed'],
    template: '<div class="chart-builder-stub" />',
  },
  DashboardView: { name: 'DashboardView', props: ['config'], emits: ['updated'], template: '<div class="dashboard-view-stub" />' },
```

在 `describe('App', ...)` 最後一個 `it` 後面加：

```js
  it('passes the dashboard list to ChartBuilder and reloads it when ChartBuilder says it changed', async () => {
    const wrapper = mountApp()
    await flushPromises()
    const builder = wrapper.findComponent({ name: 'ChartBuilder' })
    expect(builder.props('dashboards')).toEqual(dashboards)

    api.listDashboards.mockResolvedValue([])
    builder.vm.$emit('dashboards-changed')
    await flushPromises()
    expect(api.listDashboards).toHaveBeenCalledTimes(2)
    expect(builder.props('dashboards')).toEqual([])
  })

  it('DashboardView updated replaces the open config in place (same view, not re-created)', async () => {
    api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', layout: [{ chartId: 'c1' }, { chartId: 'c2' }] })
    const wrapper = mountApp()
    await flushPromises()
    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()
    const before = wrapper.findComponent({ name: 'DashboardView' })
    const first = before.vm

    before.vm.$emit('updated', { id: 'd1', name: 'Dash One', layout: [{ chartId: 'c2' }] })
    await flushPromises()
    const after = wrapper.findComponent({ name: 'DashboardView' })
    expect(after.props('config').layout).toEqual([{ chartId: 'c2' }])
    expect(after.vm).toBe(first)
  })
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/ChartBuilder.test.js src/App.test.js`
Expected: FAIL，新加的 7 個測試失敗（例如 `Unable to find button 加到既有儀表板`、`expected undefined to deeply equal [...]`），改名後的「存成新儀表板」測試也會失敗（按鈕還叫舊名字），其他照樣通過

- [ ] **Step 4: 實作 ChartBuilder.vue**

`frontend/src/modules/dashboard/ChartBuilder.vue`：

把

```js
  // 製圖頁一直掛著（切頁不會丟掉預覽），切回來時重新抓資料來源，資料頁改過的顯示名稱才會跟上
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['saved'])
```

換成

```js
  // 製圖頁一直掛著（切頁不會丟掉預覽），切回來時重新抓資料來源，資料頁改過的顯示名稱才會跟上
  active: { type: Boolean, default: true },
  // 自己的儀表板清單（App 左側那份，最近更新的在最上面），給「加到既有儀表板」選
  dashboards: { type: Array, default: () => [] },
})
// saved：存好或加好了，帶儀表板 id；dashboards-changed：加圖時發現儀表板已經不在，請 App 重抓清單
const emit = defineEmits(['saved', 'dashboards-changed'])
```

在 `const saving = ref(false)` 後面加：

```js
const appending = ref(false)
```

`adjust()` 裡把

```js
    const next = await api.analyze(preview.value.datasourceId, [...history.value, adjustText.value.trim()],
```

換成

```js
    // 換來源時預覽會被捨棄，左邊選的來源一定就是這份預覽的來源
    const next = await api.analyze(datasourceId.value, [...history.value, adjustText.value.trim()],
```

`save()` 的第一行把

```js
  if (saving.value || nameInvalid.value) return
```

換成

```js
  if (saving.value || appending.value || nameInvalid.value) return
```

在 `save()` 後面加：

```js
// 多來源 spec 3.1：加到既有儀表板不用預覽的名稱，名稱空白也能加
async function append(id) {
  if (saving.value || appending.value || busy.value || adjusting.value) return
  appending.value = true
  error.value = ''
  try {
    await api.appendCharts(id, { layout: preview.value.layout, intents: preview.value.intents })
    discard()
    emit('saved', id)
  } catch (e) {
    error.value = e.message
    // 儀表板在別的分頁被刪了：請 App 重抓清單，選單裡就不會再有它
    if (e.status === 404) emit('dashboards-changed')
  } finally {
    appending.value = false
  }
}
```

template 裡把

```html
          <v-btn
            color="primary"
            prepend-icon="mdi-content-save-outline"
            :loading="saving"
            :disabled="nameInvalid || saving || adjusting || busy"
            @click="save"
          >存為我的儀表板</v-btn>
```

換成

```html
          <v-btn
            color="primary"
            prepend-icon="mdi-content-save-outline"
            :loading="saving"
            :disabled="nameInvalid || saving || appending || adjusting || busy"
            @click="save"
          >存成新儀表板</v-btn>
          <!-- 多來源 spec 3.1：一份儀表板都沒有時不顯示；分析或調整進行中不能按 -->
          <v-menu v-if="dashboards.length" location="bottom end">
            <template #activator="{ props: activator }">
              <v-btn
                v-bind="activator"
                variant="tonal"
                append-icon="mdi-menu-down"
                :loading="appending"
                :disabled="saving || appending || adjusting || busy"
              >加到既有儀表板</v-btn>
            </template>
            <v-list aria-label="加到既有儀表板">
              <v-list-item v-for="d in dashboards" :key="d.id" :title="d.name" @click="append(d.id)" />
            </v-list>
          </v-menu>
```

- [ ] **Step 5: 實作 App.vue**

`frontend/src/App.vue`：

在 `refresh()` 後面加：

```js
// 製圖頁加圖時發現儀表板已經不在（在別的分頁刪了）：重抓清單，選單裡就不會再有它
function reloadDashboards() {
  refresh().catch((e) => (error.value = e.message))
}
```

把

```html
      <ChartBuilder v-show="view === 'build'" :active="view === 'build'" :request="buildRequest" @saved="onSaved" />
```

換成

```html
      <ChartBuilder
        v-show="view === 'build'"
        :active="view === 'build'"
        :request="buildRequest"
        :dashboards="dashboards"
        @saved="onSaved"
        @dashboards-changed="reloadDashboards"
      />
```

把

```html
            <DashboardView v-if="current" :key="current.id" :config="current" />
```

換成

```html
            <!-- 移除單張圖存檔後，換成後端回傳的設定；同一份儀表板，key 不變、不重建 -->
            <DashboardView v-if="current" :key="current.id" :config="current" @updated="(c) => (current = c)" />
```

- [ ] **Step 6: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/ChartBuilder.test.js src/App.test.js`
Expected: PASS

- [ ] **Step 7: 跑全部前端測試和 build**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected: `Tests  146 passed (146)`，build 成功

- [ ] **Step 8: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add frontend/src/modules/dashboard/ChartBuilder.vue frontend/src/modules/dashboard/ChartBuilder.test.js frontend/src/App.vue frontend/src/App.test.js && git commit -m "feat(frontend): 製圖頁可以加到既有儀表板，App 串接清單與移除後的設定" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/ChartBuilder.vue frontend/src/modules/dashboard/ChartBuilder.test.js frontend/src/App.vue frontend/src/App.test.js
```

---

### Task 8: 文件：README、backlog、主設計 spec

**Files:**
- Modify: `README.md`、`docs/backlog.md`、`docs/superpowers/specs/2026-09-23-jev-dashboard-design.md`

**Interfaces:**
- Consumes：Task 1–7 的行為（畫面上的字照 Global Constraints）

- [ ] **Step 1: README 的工作區說明**

`README.md`：把

```
- 「製圖」：選來源、點選想看的欄位、寫一句需求，按「分析」（或在需求框按 Ctrl+Enter），圖表會出現在右邊，滿意再存成儀表板。切到別頁再回來，預覽還在。
- 「儀表板」：列出存好的儀表板，卡片可以拖拉、縮放，版面會自動存。
```

換成

```
- 「製圖」：選來源、點選想看的欄位、寫一句需求，按「分析」（或在需求框按 Ctrl+Enter），圖表會出現在右邊。滿意就「存成新儀表板」，或用「加到既有儀表板」接在某份儀表板的最下面。切到別頁再回來，預覽還在。
- 「儀表板」：列出存好的儀表板，卡片可以拖拉、縮放，版面會自動存。按卡片右上角的「✕」可以移除單張圖，會先跳確認。
```

- [ ] **Step 2: README 新增一節**

在 `## 依需求分析與調整` 這一節最後一段（「送給 JEV 的內容只多了使用者寫的句子…」那段）後面、`## 測試` 前面加：

```markdown
## 一份儀表板放多個來源

每張圖各自記得自己讀哪個資料來源，所以同一份儀表板可以放不同來源的圖，例如「銷售趨勢」來自銷售 API、「退貨原因」來自 R7 Excel。

製圖頁一次還是只分析一個來源。預覽滿意後可以「存成新儀表板」，也可以從「加到既有儀表板」選一份儀表板，這批圖會接在那份儀表板的最下面，彼此的相對位置不變。加到既有儀表板不會用到預覽上的名稱，儀表板名稱維持原樣。

圖來自兩個以上的來源時，每張卡片標題旁會標出來源名稱；「依據」也改成每次分析一行，前面註明來源，例如「依據（R7 Excel 退貨）：各區退貨 → 不要圓餅圖」。某個來源載入失敗（例如外部 API 連不上）時，只有那個來源的卡片顯示「資料來源「…」載入失敗」，其他卡片照常顯示。

已存的儀表板每張卡片（包括明細表）右上角都有「✕」。按下去會先確認，確認後馬上存檔，不能復原，要加回來得重新分析。某個來源的圖全部移除後，那個來源的依據也會跟著拿掉。最後一張圖也可以移除，之後再用「加到既有儀表板」把圖加回來。

舊的儀表板會在後端啟動時由 Flyway 的 `V2__per_chart_datasource.sql` 自動轉成新格式，不用重建。完整規則見 [`docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`](docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md)。
```

- [ ] **Step 3: backlog 第 42 行標為已修正**

`docs/backlog.md`：把

```
- P3 · PUT 更新儀表板時，body 裡的 `datasourceId` 會被完全忽略，換成別的資料源也不會報錯。（backend ledger，Task 10）
```

換成

```
- ~~P3 · PUT 更新儀表板時，body 裡的 `datasourceId` 會被完全忽略，換成別的資料源也不會報錯。（backend ledger，Task 10）~~ 已修正：來源改成記在每張圖上，PUT 會檢查每個來源都是自己的（多來源儀表板，`docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`）。
```

- [ ] **Step 4: 主設計 spec 第 11 節**

`docs/superpowers/specs/2026-09-23-jev-dashboard-design.md`：把

```
- 一份儀表板接多個資料源。
```

換成

```
- 一份儀表板接多個資料源。已由 `docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md` 處理：每張圖各自一個來源。
```

- [ ] **Step 5: 確認**

Run: `cd "$(git rev-parse --show-toplevel)" && grep -n "^## " README.md | grep -A1 "依需求分析與調整"`
Expected: `## 依需求分析與調整` 下一行是 `## 一份儀表板放多個來源`

Run: `cd "$(git rev-parse --show-toplevel)" && grep -c "PUT 更新儀表板時.*~~ 已修正" docs/backlog.md`
Expected: `1`

- [ ] **Step 6: Commit**

```bash
cd "$(git rev-parse --show-toplevel)" && git add README.md docs/backlog.md docs/superpowers/specs/2026-09-23-jev-dashboard-design.md && git commit -m "docs: README、backlog 與主設計 spec 補上多來源儀表板" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- README.md docs/backlog.md docs/superpowers/specs/2026-09-23-jev-dashboard-design.md
```

---

### Task 9: 端對端驗證（接真的 JEV）

**Files:**
- 不新增檔案。發現問題時修正對應的檔案，commit 訊息寫清楚改了什麼。

**Interfaces:**
- Consumes：Task 1–8 的全部成果、`backend/src/test/resources/sales.csv`、Chrome DevTools MCP（`new_page`、`take_snapshot`、`click`、`fill`、`wait_for`、`evaluate_script`、`drag`、`navigate_page`、`list_network_requests`、`list_console_messages`、`take_screenshot`、`close_page`）

**誰來跑：** 整個 Task 9 交給同一個 subagent 從頭跑到尾，這個 subagent 要能用 Chrome DevTools MCP。Step 3、4 開的背景程序屬於它；controller 不跑 `mvnw`，也不跑 `npm run dev`。B1 的 worktree 如果也要做端對端，要等這個 task 結束：兩邊都要用 8080（前端 proxy 寫死）。

**瀏覽器規則，一定要遵守：** 用 `new_page` 開一個自己的分頁，記下 pageId，之後只操作這個分頁。別的分頁是使用者自己在用的：不要 `select_page`、`navigate_page`、`close_page` 它們，也不要讀取、截圖或點它們。不確定目前選的是哪一頁時，先 `select_page` 自己的 pageId。結束時只關自己的分頁。

**等待和程序：** 每個等待迴圈最多等 180 秒，逾時就印出伺服器 log 的最後幾十行，停下來回報，不要一直重試。不能 `taskkill`：埠被別的程序佔著、或自己開的程序停不掉時，用 `netstat -ano | grep …` 找出 PID，回報給使用者處理。

**資料：** 後端用全新的 schema `jev_dashboard_e2e_a`，使用者的 `jev_dashboard` 完全不碰；前端用 `VITE_USER_ID=e2e-multi`。測完 schema 留著，在報告裡註明。

- [ ] **Step 1: 全部測試再跑一次**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 170, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected: `Tests  146 passed (146)`，build 成功

- [ ] **Step 2: 確認 JEV key，以及 8080、5180 沒有人在用**

Run: `cd "$(git rev-parse --show-toplevel)" && grep -q '^TYPESAFE_API_KEY=.' backend/.env && echo "TYPESAFE_API_KEY 已設定" || echo "TYPESAFE_API_KEY 沒設定"`
Expected: `TYPESAFE_API_KEY 已設定`。只印結果，不印內容。沒設定就停下來回報。

Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
Expected: `8080 000` 和 `5180 000`。有一個不是 `000` 就代表有程式佔著：不要用它、不要關它，跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 回報給 controller，停在這一步。

- [ ] **Step 3: 啟動後端（背景），確認跑的是這個分支的程式**

用 Bash 的 `run_in_background: true` 執行：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_e2e_a SPRING_FLYWAY_SCHEMAS=jev_dashboard_e2e_a; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q spring-boot:run > target/e2e-backend.log 2>&1
```

再用 Bash 的 `run_in_background: true` 跑探測迴圈。空的 `layout` 在新程式回「沒有要存入的圖表」，舊程式回「name、datasourceId、layout 都必填」，看到前者才代表 8080 上是這個分支的程式：

```bash
cd "$(git rev-parse --show-toplevel)"
for i in $(seq 1 90); do
  r=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-User-Id: e2e-multi' \
    -d '{"name":"probe","layout":[]}' http://127.0.0.1:8080/api/dashboards || true)
  case "$r" in *沒有要存入的圖表*) echo "backend-ready $r"; exit 0 ;; esac
  sleep 2
done
echo "backend not ready after 180 s, last response: $r"
tail -n 60 backend/target/e2e-backend.log
exit 1
```

Expected: `backend-ready {"error":"沒有要存入的圖表"}`

- [ ] **Step 4: 啟動前端（背景）**

用 Bash 的 `run_in_background: true` 執行：

```bash
cd "$(git rev-parse --show-toplevel)/frontend" && VITE_USER_ID=e2e-multi npm run dev -- --port 5180 --strictPort > ../backend/target/e2e-vite.log 2>&1
```

再用 Bash 的 `run_in_background: true` 等它起來：

```bash
cd "$(git rev-parse --show-toplevel)"
for i in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5180/)" = 200 ] && { echo frontend-ready; exit 0; }
  sleep 2
done
echo "frontend not ready after 180 s"
tail -n 40 backend/target/e2e-vite.log
exit 1
```

Expected: `frontend-ready`

- [ ] **Step 5: 準備兩個來源**

同一份 `sales.csv` 上傳兩次，取不同名字，當成兩個來源：

Run: `cd "$(git rev-parse --show-toplevel)" && for n in 銷售 退貨; do curl -s -H 'X-User-Id: e2e-multi' -F "file=@backend/src/test/resources/sales.csv" -F "name=$n" http://127.0.0.1:8080/api/datasources/upload | head -c 80; echo; done`
Expected: 兩行 JSON，開頭是 `{"id":"…","name":"銷售"`、`{"id":"…","name":"退貨"`

下面幾步會用到的 `evaluate_script` 腳本 S1，列出儀表板頁的依據和每張卡片（`top` 是卡片在頁面上的位置，不受捲動影響）：

```js
() => ({
  basis: [...document.querySelectorAll('.dash-basis')].map((el) => el.textContent.trim()),
  cards: [...document.querySelectorAll('.v-main .vgl-item:not(.vgl-item--placeholder)')].map((el) => ({
    id: el.querySelector('[data-chart-id]')?.dataset.chartId,
    title: el.querySelector('.tc-card-title .text-truncate')?.textContent.trim(),
    source: el.querySelector('.tc-source')?.textContent.trim() ?? null,
    top: Math.round(el.getBoundingClientRect().top + window.scrollY),
    bottom: Math.round(el.getBoundingClientRect().bottom + window.scrollY),
  })),
})
```

- [ ] **Step 6: 分析 A，存成新儀表板**

1. `new_page`，url 填 `http://localhost:5180/`，記下 pageId。
2. 點左側導覽列的「製圖」。`take_snapshot`，在「資料來源」選單選「銷售」。
3. 在「想看什麼？（選填）」填「各區每月營收」，按「分析」。用 `wait_for` 等「儀表板名稱」出現（真的 JEV，最多 180 秒）。
4. 「儀表板名稱」改成「高階主管週報」，按「存成新儀表板」。

Expected: 畫面切到「儀表板」頁，標題是「高階主管週報」。跑 S1：`basis` 是 `["依據：各區每月營收"]`，每張卡片的 `source` 都是 `null`（只有一個來源）。

- [ ] **Step 7: 分析 B，加到既有儀表板（spec 第 9 節端對端第 1 步）**

1. 點「製圖」，「資料來源」選「退貨」，「想看什麼？」填「各區退貨」，按「分析」，等「儀表板名稱」出現。
2. 按「加到既有儀表板」，在選單裡點「高階主管週報」。

Expected:
- 畫面切到「儀表板」頁，打開的是「高階主管週報」。
- S1 的 `cards`：`source` 只有「銷售」和「退貨」兩種，每張都有值；每張「退貨」卡片的 `top` 都 ≥ 每張「銷售」卡片的 `bottom`（B 的圖整批排在 A 下面）。
- S1 的 `basis` 是 `["依據（銷售）：各區每月營收", "依據（退貨）：各區退貨"]`。
- `take_screenshot` 存一張，附在報告裡。

- [ ] **Step 8: 拖拉後重新整理，位置還在（第 2 步）**

1. `take_snapshot` 找到第一張卡片的標題列，用 `drag` 拖到最後一張卡片的位置。
2. `list_network_requests` 確認有一筆 `PUT /api/dashboards/…` 回 200。
3. 跑 S1 記下每張卡片的 `id` 和 `top`。`navigate_page` 重新整理自己的分頁，點左側清單的「高階主管週報」，再跑一次 S1。

Expected: 重新整理前後，每個 `id` 的 `top` 都一樣。`drag` 沒有讓卡片移動（沒有 PUT）時，記在報告裡，繼續下一步；拖拉存檔的邏輯有既有的單元測試保護。

- [ ] **Step 9: 移除單張圖（第 3 步）**

1. 在一張「銷售」卡片上按「移除這張圖」（✕），確認對話框的標題是「移除圖表」，內容是「確定從「高階主管週報」移除「{那張卡片的標題}」？移除後無法復原，要加回來得重新分析。」，按「移除」。
2. 跑 S1：那張卡片不見了。重新整理、再打開「高階主管週報」，跑 S1：還是不見。
3. 把所有「退貨」卡片（包括明細表）一張一張移除。

Expected: 最後 S1 的 `cards` 只剩「銷售」的卡片，而且 `source` 都變回 `null`（又只剩一個來源）；`basis` 是 `["依據：各區每月營收"]`，退貨那一行跟著消失。

- [ ] **Step 10: 異常標註與主控台（第 5 步）**

1. `list_network_requests` 找打開「高階主管週報」之後的 `GET /api/jev/highlight/…`。
2. `list_console_messages` 看有沒有 error。

Expected: highlight 請求回 200。有標註的話卡片標題列會出現嚴重程度的標籤，沒有標註也算正常（看 JEV 怎麼判斷），實際結果寫進報告。主控台沒有 error。

- [ ] **Step 11: 收尾**

1. `close_page` 關掉自己的分頁。
2. Run: `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 寫進報告，請使用者自己關掉這兩個程序。
3. 報告註明：schema `jev_dashboard_e2e_a` 和使用者 `e2e-multi` 的測試資料還留在資料庫裡。

---

## 合併後檢查（controller 做，不在 worktree 裡）

spec 第 9 節端對端第 4 步：V2 遷移前就存在的儀表板要照常打開。worktree 裡沒有舊資料，所以等 A 合併進 main 之後，在 main 上照使用者平常的方式啟動後端（schema `jev_dashboard`，不加 Global Constraints 的環境變數），Flyway 會把使用者真正的儀表板遷移到 V2。接著用 Chrome MCP 開自己的分頁，打開一份原本就有、而且存過需求的儀表板。

Expected:
- 圖表照常顯示，沒有「欄位遺失」或「資料來源載入失敗」。
- 依據照舊顯示成一行「依據：…」。
- 後端 log 有 `Migrating schema "jev_dashboard" to version "2 - per chart datasource"`。

B1 合併前要先 rebase 到包含 V2 的 main，確認自己的 migration 是 `V3`。
