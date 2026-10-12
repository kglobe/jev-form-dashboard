# JEV 儀表板：匯出 Excel／CSV Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已存儀表板的每張卡多一個下載鈕，可以把這張卡的資料下載成 Excel 或 CSV。內容跟畫面上這張卡一樣，套用同一組篩選、表頭用顯示名稱，明細表下載全部的列。

**Architecture:** 後端新增 `GET /api/dashboards/{id}/charts/{chartId}/export`。`DashboardController.export` 照 `/data` 的方式查權限、算 `denied`，再呼叫 `DashboardDataService.exportData`。`exportData` 只拿這一張卡跑既有的 `build`，並傳一個新旗標，讓 `UniversalTransformer` 不截斷明細表。新的 `ChartExport` 只有靜態方法，用 POI `SXSSFWorkbook` 寫 xlsx、用 commons-csv 寫 CSV，也負責清理檔名。所有檢查都在寫檔之前做完，檢查過了才設定標頭、直接寫進回應。前端 `api.js` 多一個 `exportChart`，回傳 blob 和檔名。`ChartCard` 多一個 `download` prop，有這個 prop 才顯示下載鈕和格式選單。`DashboardView` 決定哪些卡片拿得到 `download`，下載時帶目前的篩選，用 `<a download>` 存檔。`App.vue` 的訪客檢視多傳 `guest`。

**Tech Stack:** Java 21、Spring Boot 3.5.16（spring-web 6.2.19）、Apache POI 5.5.1（`poi-ooxml`，已在 pom）、commons-csv 1.14.1（已在 pom）、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、Vitest 5 + @vue/test-utils + happy-dom。

**Spec:** `docs/superpowers/specs/2026-10-12-chart-export-design.md`

## Global Constraints

- 開工前，主 session 先在 `main` commit 這份 plan，再從 `main` 開 worktree：在 `C:/GitHub/jev-form-dashboard` 跑 `git worktree add .claude/worktrees/chart-export -b feat/chart-export main`。之後所有 task 都在 `C:/GitHub/jev-form-dashboard/.claude/worktrees/chart-export` 裡做。
- 不要跑 `git switch`、`git checkout`。共用的 `C:/GitHub/jev-form-dashboard` 可能有別的 agent 在用。
- task 照順序做：B1 → B2 → B3 → B4 → F1 → F2 → F3 → D1。F 系列只依賴 spec 第 3 節的 API，前端測試一律 mock `api.js`，不用起後端。每個 task 結束時，改到的那一邊（後端或前端）全部測試都要過，然後 commit 一次。
- 不要起伺服器。瀏覽器驗證由主 session 自己做，見最後一節。
- 後端指令在 `backend/` 跑，前面一律加：

  ```bash
  export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_export SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_export;
  ```

  單一類別跑 `./mvnw test -Dtest=<類別>`，全部跑 `./mvnw test`。不要跑 `./mvnw clean`。這次沒有 migration，但共用的 `jev_dashboard_test` 可能有別的 branch 正在跑測試（`TestData.clean` 會清空資料表），所以用自己的 schema，Flyway 會自己建。
- 前端指令在 `frontend/` 跑。worktree 裡第一次要先 `npm ci`。之後用 `npx vitest run <檔案>` 跑單一檔案，`npm test` 跑全部，`npm run build` 打包。
- 本機是 Windows 11 + Git Bash：不要用 `python3`；丟掉輸出寫 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 不新增 Maven 或 npm 套件。POI 和 commons-csv 本來就在 `backend/pom.xml`。
- 既有測試照舊要過。只能新增測試、helper 和 import，例外只有下面這些：
  - F3：`DashboardView.test.js` 的 `ChartCard` stub 的 props 多一個 `download`；`App.test.js` 的 `DashboardView` stub 的 props 多一個 `guest`。
  - F2：`ChartCard.test.js` 從 `@vue/test-utils` 多 import 一個 `flushPromises`。
- 字串一字不差（spec 2、3、5）：
  - 後端：400「format 只能是 xlsx 或 csv」、404「找不到這張圖」、400「這張圖目前沒有資料可以下載」、400「資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV」。檢視者的載入錯誤沿用「資料來源載入失敗，請聯絡儀表板擁有者」。工作表名稱「資料」；標題清掉後什麼都不剩時，檔名用「圖表」。
  - 前端：下載鈕的 aria-label 和 title 都是「下載這張圖的資料」；選單兩項是「Excel（.xlsx）」「CSV」；錯誤是「下載失敗：」加上訊息。
- 錯誤回應固定是 `{"error": "訊息"}`，由既有的 `ApiExceptionHandler` 產生。
- 程式碼註解用繁中，寫上 spec 節次，例如 `// 匯出 spec 2.2：…`。刻意簡化的地方加 `ponytail:` 註解。
- 每個 commit：`git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`。訊息用 Conventional Commits 加繁體中文，每個 task 的最後一步寫了確切的訊息。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. **`exportData` 收 `ChartSpec`，不收 `config` 和 `chartId`。** spec 4 寫的是 `exportData(userId, config, chartId, filters, denied)`。但 controller 要把載入錯誤換成檢視者看的通用訊息（spec 2.3），如果「找不到這張圖」也從 `exportData` 丟出來，就會一起被換掉。所以改成 controller 先在 `c.layout()` 裡找卡片（找不到直接 404），再呼叫 `exportData(userId, spec, filters, denied)`。`denied` 照舊由 controller 用整份 `config` 算，跟 `/data` 一樣。
2. **來源載入失敗沿用 `/data` 的錯誤。** 只有一張卡時，`sources()` 遇到唯一的來源載入失敗，會直接丟出那個載入錯誤（例如 500「環境變數 DS_… 未設定」、502「外部 API 無法連線」），不會回 `SOURCE_FAILED`。export 照樣讓它丟：擁有者看到原本的原因，檢視者看到「資料來源載入失敗，請聯絡儀表板擁有者」，狀態碼不變。這就是 spec 2.3 那句的意思。spec 2.4 的 400「這張圖目前沒有資料可以下載」用在其他卡片錯誤：`FIELD_MISSING`、`NOT_A_DATE`、`SOURCE_DELETED`、`SOURCE_UNSHARED`。這個檢查放在 controller、在換訊息的 try 外面，檢視者看到的也是同一句。
3. **檢查順序：** `format` → `filters` 的格式（`Filters.parse`）→ 看不看得到儀表板（404「找不到儀表板」）→ `chartId`（404）→ 載入 → 卡片錯誤（400）→ xlsx 的列數上限（400）。全部過了才設定 Content-Type、Content-Disposition，開始寫檔。
4. **`format` 用 `@RequestParam(required = false)`。** 沒帶 `format` 和帶錯一樣回 400「format 只能是 xlsx 或 csv」，不走 Spring 的「缺少參數：format」。
5. **不截斷的旗標：** `UniversalTransformer.transform` 加一個 7 個參數的版本，最後一個是 `boolean allRows`。原本 6 個參數的版本改成呼叫它，傳 `false`。`shaped` 多收一個 `int tableLimit`，明細表的 `project` 用它。散佈圖本來就傳 `Integer.MAX_VALUE`，不受影響。
6. **xlsx 的寫法：** 文字一律 `setCellValue(String)`，不另外設文字格式 `@`。字串格本來就不會被 Excel 當成公式，`007` 也不會變成 7。空值不建格子，讀回來是 `null`。比率欄是「`percent` 是 true 時的最後一欄」，只有數字格套 `0.0%`。用 `try (SXSSFWorkbook wb = new SXSSFWorkbook())`：POI 5.5.1 的 `dispose()` 已經 deprecated，`close()` 會刪掉暫存檔（查過 jar）。
7. **CSV 的寫法：** 數字用 `new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()`，處理得了 `Double`、`Long` 等型別。空值把 `null` 交給 `CSVPrinter`，寫成空字串，第一欄也不加引號（傳 `""` 的話第一欄會變成 `""`）。表頭也套公式字元的處理，因為顯示名稱是使用者自己打的。`CSVPrinter` 用 try-with-resources，關掉時順便關掉回應的 OutputStream，Servlet 允許這樣做。這幾點都用實際的 jar 跑過：輸出是 `007,1200`、`北區,0.043`、`南區,0.0000001`、`東區,100000000000000000000`、`,-5`、`西區,`，都沒有加引號。
8. **檔名：** 用 code point 算 100 字（`offsetByCodePoints`），emoji 不會被切一半。`title` 是 null 時當成空字串。Content-Disposition 用 Spring 的 `ContentDisposition.attachment().filename(name, UTF_8)`，實際輸出是 `attachment; filename="=?UTF-8?Q?…?="; filename*=UTF-8''%E6%9C%88…`（spring-web 6.2.19 跑過）。前端只讀 `filename*`。
9. **已知上限：** Excel 一格最多 32,767 個字。超過時，POI 在建格子的階段就會丟 `IllegalArgumentException`，這時還沒寫出任何位元組，`ApiExceptionHandler` 回 400 和 POI 的英文訊息。CSV 沒有這個限制。在 `ChartExport.xlsx` 上加 `ponytail:` 註解說明，真的遇到再截斷或換訊息。
10. **前端存檔：** `URL.createObjectURL` 加上沒有掛進 DOM 的 `<a download>`，呼叫 `click()`，再用 `setTimeout` 等瀏覽器開始下載之後才 `revokeObjectURL`。檔名先用 `Content-Disposition` 的 `filename*`，讀不到就用畫面上的標題（`data.title ?? spec.title`）加上 `.<format>`。
11. **錯誤顯示的分工：** `DashboardView` 的下載函式自己 catch，把訊息寫進 `error`，不往外丟。`ChartCard` 只管等待狀態（`try … finally`），做法跟 `KpiForm` 的 `save` 一樣。
12. **下載鈕出現的條件：** `download` 有值、`data` 沒有 `error`，而且 `data.rows` 至少一列。KPI 卡的 `rows` 只有已經結束的期，所以「一期都還沒結束」也算在這條裡，不用另外判斷。

## Review Focus

- 檢視者的錯誤訊息只換掉載入錯誤，「找不到這張圖」和「這張圖目前沒有資料可以下載」不能被換成「資料來源載入失敗」。
- 所有 `throw` 都在 `response.setContentType` 之前。
- 畫面用的 `/data` 照舊截斷在 500 筆（`truncated: true`）。
- `exportData` 不寫 `recent`，只載入那一張卡的來源。
- 訪客（`guest`）和預覽都不會拿到 `download`；檢視者（`readonly`）拿得到。

## File Structure

| 檔案 | 動作 | 內容 |
|---|---|---|
| `backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java` | 改 | `transform` 多一個 `allRows` 版本；`shaped` 收 `tableLimit` |
| `backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java` | 改 | 加 1 個測試 |
| `backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java` | 改 | `build` 多收 `allRows`；新增 `exportData` |
| `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java` | 改 | 加 helper `tableOf` 和 4 個測試 |
| `backend/src/main/java/jev/dashboard/dashboard/ChartExport.java` | 新 | `xlsx`、`csv`、`csvText`、`filename`、`requireXlsxRows` |
| `backend/src/test/java/jev/dashboard/dashboard/ChartExportTest.java` | 新 | 單元測試，不起 Spring |
| `backend/src/main/java/jev/dashboard/dashboard/DashboardController.java` | 改 | 新增 `export` |
| `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java` | 改 | 加 5 個測試 |
| `frontend/src/modules/dashboard/api.js` | 改 | `request` 可以回傳原始 Response；新增 `exportChart` |
| `frontend/src/modules/dashboard/api.test.js` | 改 | 加 2 個測試 |
| `frontend/src/modules/dashboard/ChartCard.vue` | 改 | `download` prop、下載鈕和選單 |
| `frontend/src/modules/dashboard/ChartCard.test.js` | 改 | 加 3 個測試 |
| `frontend/src/modules/dashboard/DashboardView.vue` | 改 | `guest` prop、`download()`、傳給 `ChartCard` |
| `frontend/src/modules/dashboard/DashboardView.test.js` | 改 | stub 加 `download`，加 3 個測試 |
| `frontend/src/App.vue` | 改 | 訪客檢視的 `DashboardView` 傳 `:guest="true"` |
| `frontend/src/App.test.js` | 改 | stub 加 `guest`，在既有的兩個測試各加一行斷言 |
| `README.md`、`docs/backlog.md`、`docs/testing/e2e-playbook.md` | 改 | D1 |

---

### Task B1: 後端：明細表可以不截斷

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java:97-141`
- Test: `backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java`

- [ ] **Step 1: 寫失敗的測試**

在 `UniversalTransformerTest` 的 `tableWithinTheRowLimitHasNoTruncatedField()` 後面加：

```java
    @Test
    void exportGetsEveryTableRowWhileTheScreenStillGetsTheFirst500() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= UniversalTransformer.TABLE_ROW_LIMIT; i++) rows.add(row("2024-01-05", (double) i, "北", "A", 1.0));
        ChartSpec spec = new ChartSpec("c1", "table", "明細", new GridPos(0, 0, 12, 5),
            new Mapping(null, null, null, null, null, List.of("amount"), null, null), Map.of());
        LocalDate today = LocalDate.of(2026, 10, 12);

        // 匯出 spec 4：匯出用的不截斷
        ChartData all = UniversalTransformer.transform(spec, rows, FIELDS, List.of(), List.of(), today, true);
        assertThat(all.rows()).hasSize(UniversalTransformer.TABLE_ROW_LIMIT + 1);
        assertThat(all.truncated()).isNull();
        // 畫面用的照舊
        ChartData shown = UniversalTransformer.transform(spec, rows, FIELDS, List.of(), List.of(), today, false);
        assertThat(shown.rows()).hasSize(UniversalTransformer.TABLE_ROW_LIMIT);
        assertThat(shown.truncated()).isTrue();
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `./mvnw test -Dtest=UniversalTransformerTest`
Expected: COMPILATION ERROR，`no suitable method found for transform(...)`（還沒有 7 個參數的版本）。

- [ ] **Step 3: 實作**

把原本 6 個參數的 `transform`（第 97～122 行，含上面的 javadoc）換成下面兩個方法：

```java
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios, List<String> dateFields, LocalDate today) {
        return transform(spec, rows, availableFields, ratios, dateFields, today, false);
    }

    /**
     * ratios 是這個來源的比率定義（B3 spec 5.1）；value 是比率名稱時，每組分子、分母各自加總再相除。
     * dateFields 是這個來源的日期欄位，依 schema 順序；比率只算分子、分母都有資料的月份（期間對齊）。
     * today 只有 KPI 卡用得到：只回已經結束的期（KPI spec 4）；DashboardDataService 傳它 Clock 的今天。
     * allRows 是匯出用的（匯出 spec 4）：明細表回傳全部的列；畫面用 false，只回前 TABLE_ROW_LIMIT 筆。
     */
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios, List<String> dateFields, LocalDate today, boolean allRows) {
        Mapping m = spec.mapping();
        Ratio ratio = ratios.stream().filter(r -> r.name().equals(m.value())).findFirst().orElse(null);
        List<String> fields = new ArrayList<>(referencedFields(m));
        if (ratio != null) {
            // B3 spec 5.2：比率本身不在資料裡，要檢查的是分子、分母；放在 value 以外的比率名稱照樣找不到
            fields.remove(ratio.name());
            fields.addAll(List.of(ratio.numerator(), ratio.denominator()));
        }
        for (String field : fields) {
            if (!availableFields.contains(field)) return ChartData.missing(spec.chartId(), spec.type(), field);
        }
        String agg = m.aggregate() != null ? m.aggregate() : defaultAggregate(spec.type());
        try {
            return shaped(spec, rows, m, agg, ratio, dateFields, today, allRows ? Integer.MAX_VALUE : TABLE_ROW_LIMIT);
        } catch (DateTimeParseException e) {
            // TimeBucket 分期時遇到不是日期的值：只有這張卡出錯，其他卡照常畫
            return ChartData.notADate(spec.chartId(), spec.type(), m.x());
        }
    }
```

`shaped` 的簽名和明細表那一行改成：

```java
    private static ChartData shaped(ChartSpec spec, List<Map<String, Object>> rows, Mapping m, String agg, Ratio ratio,
                                    List<String> dateFields, LocalDate today, int tableLimit) {
```

```java
            case "table" -> project(spec, rows, m.columns(), false, tableLimit);
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `./mvnw test -Dtest=UniversalTransformerTest`
Expected: PASS（含既有的 `tableOverTheRowLimitIsCutAndFlaggedTruncated`）。

- [ ] **Step 5: 全部後端測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java
git commit -m "feat: 轉換器可以不截斷明細表，給匯出用" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B2: 後端：`DashboardDataService.exportData`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java:95-116`、`:189-220`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`

- [ ] **Step 1: 寫失敗的測試**

在 `DashboardDataServiceTest` 最後（`aCardNoFilterAppliesToGetsTheSharedRowsWithoutACopy` 後面、class 結尾的 `}` 前面）加：

```java

    // 匯出 spec 4：只拿這一張卡跑 build，明細表不截斷，套上顯示名稱，不留給異常檢查

    static ChartSpec tableOf(String chartId, UUID ds, String... columns) {
        return new ChartSpec(chartId, "table", "明細", POS,
            new Mapping(null, null, null, null, null, List.of(columns), null, null), Map.of(), ds);
    }

    @Test
    void exportGetsTheWholeTableLoadsOnlyThatCardsSourceAndAppliesTheFilters() {
        Map<UUID, Integer> loads = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) rows.add(Map.of("region", i % 2 == 0 ? "北區" : "南區", "amount", (double) i));
        DashboardDataService service = new DashboardDataService(sources(TWO, Map.of(SALES, rows), Set.of(), loads), CLOCK);
        ChartSpec table = tableOf("c1", SALES, "region", "amount");

        ChartData all = service.exportData("u1", table, null, Set.of());

        assertThat(all.rows()).hasSize(501);
        assertThat(all.truncated()).isNull();
        assertThat(all.dimensions()).containsExactly("地區", "amount");
        assertThat(all.source()).isEqualTo("銷售");
        assertThat(loads).isEqualTo(Map.of(SALES, 1));
        // 畫面照舊只有前 500 筆（UniversalTransformer.TABLE_ROW_LIMIT）
        assertThat(service.display("u1", config(table), null, Set.of()).get(0).rows()).hasSize(500);

        ChartData north = service.exportData("u1", table, by("地區", "北區"), Set.of());
        assertThat(north.rows()).hasSize(251);
        assertThat(north.rows().get(1)).isEqualTo(List.of("北區", 2.0));
    }

    @Test
    void exportRelabelsTheAutoTitleAndADeniedFilterSlicesNothing() {
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK);
        Filters both = new Filters(null, List.of(new Field("地區", List.of("北區")), new Field("product", List.of("A"))));

        ChartData d = service.exportData("u1", bar("c1", ORDERS, "region", "amount"), both, Set.of("地區"));

        // 地區不生效，只剩 product = A：第 1、3、5 列
        assertThat(d.title()).isEqualTo("amount 依 地區");
        assertThat(d.dimensions()).containsExactly("地區", "amount");
        assertThat(d.filterSkipped()).containsExactly("地區");
        assertThat(d.rows()).isEqualTo(List.of(List.of("中區", 16.0), List.of(UniversalTransformer.BLANK, 4.0), List.of("北區", 1.0)));
    }

    @Test
    void exportKeepsNothingForTheHighlight() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = monthly(loads);

        service.exportData("u1", LINE, null, Set.of());
        service.load("u1", saved(UUID.randomUUID(), LINE), null, Set.of());

        assertThat(loads).isEqualTo(Map.of(MONTHLY, 2));
    }

    @Test
    void exportThrowsTheLoadErrorLikeDataAndADeletedSourceComesBackAsACardError() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES), new HashMap<>()), CLOCK);

        assertThatThrownBy(() -> service.exportData("u1", bar("c1", SALES, "region", "amount"), null, Set.of()))
            .isInstanceOf(ApiException.class)
            .hasMessage("外部 API 無法連線");
        // 不在 byId 的來源查不到，也不存在：已刪除
        assertThat(service.exportData("u1", bar("c1", UUID.randomUUID(), "region", "amount"), null, Set.of()).error())
            .isEqualTo("SOURCE_DELETED");
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `./mvnw test -Dtest=DashboardDataServiceTest`
Expected: COMPILATION ERROR，`cannot find symbol … method exportData`。

- [ ] **Step 3: 實作**

`load()` 和 `display()` 裡的兩個 `build(userId, c.layout(), filters, denied)` 都改成 `build(userId, c.layout(), filters, denied, false)`。

在 `display()` 後面加：

```java
    /**
     * 匯出 spec 4：只拿這一張卡跑 build，所以只載入它的來源；明細表不截斷，套上顯示名稱，不留給異常檢查（recent）。
     * filters、denied 同 load()。唯一的來源載入失敗時，跟 /data 一樣丟出那個錯誤。
     */
    public ChartData exportData(String userId, ChartSpec spec, Filters filters, Set<String> denied) {
        Built b = build(userId, List.of(spec), filters, denied, true).getFirst();
        return relabelled(b.spec(), b.data(), b.labels());
    }
```

`build` 的 javadoc 和簽名改成：

```java
    /** 回傳的順序跟 layout 一樣，每一筆都帶來源名稱。allRows 是匯出用的：明細表不截斷（匯出 spec 4）。 */
    private List<Built> build(String userId, List<ChartSpec> layout, Filters filters, Set<String> denied, boolean allRows) {
```

`build` 裡呼叫轉換器的那一行改成：

```java
                d = UniversalTransformer.transform(s, f.rows(), src.available(), src.ratios(), src.dates(), today, allRows)
                    .withFilterSkipped(f.skipped());
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `./mvnw test -Dtest=DashboardDataServiceTest`
Expected: PASS。

- [ ] **Step 5: 全部後端測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java
git commit -m "feat: exportData 只載入一張卡、明細表不截斷" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: 後端：`ChartExport`（xlsx、CSV、檔名）

**Files:**
- Create: `backend/src/main/java/jev/dashboard/dashboard/ChartExport.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/ChartExportTest.java`

- [ ] **Step 1: 寫失敗的測試**

```java
package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChartExportTest {
    /** 可以放 null 的一列。 */
    static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    static ChartData data(List<String> dims, List<List<Object>> rows, Boolean percent) {
        return new ChartData("c1", "table", dims, rows, null, null, null, null, null, percent);
    }

    static Sheet xlsx(ChartData d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartExport.xlsx(d, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheet("資料");
    }

    static String csv(ChartData d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartExport.csv(d, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    static boolean bold(Cell cell) {
        return cell.getSheet().getWorkbook().getFontAt(cell.getCellStyle().getFontIndex()).getBold();
    }

    @Test
    void xlsxHasOneSheetABoldHeaderNumbersAsNumbersAndTextAsText() throws IOException {
        Sheet s = xlsx(data(List.of("代碼", "名稱", "金額"),
            List.of(row("007", "北區", 1200.0), row("=1+1", null, -5.0)), null));

        assertThat(s.getWorkbook().getNumberOfSheets()).isEqualTo(1);
        assertThat(s.getLastRowNum()).isEqualTo(2);
        Row header = s.getRow(0);
        assertThat(header.getCell(0).getStringCellValue()).isEqualTo("代碼");
        assertThat(bold(header.getCell(0))).isTrue();

        Row first = s.getRow(1);
        assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(first.getCell(0).getStringCellValue()).isEqualTo("007");
        assertThat(bold(first.getCell(0))).isFalse();
        assertThat(first.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(1200.0);
        assertThat(first.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("General");

        // 公式字元開頭的值照樣是文字格，不是 FORMULA；空值不建格子
        Row second = s.getRow(2);
        assertThat(second.getCell(0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(second.getCell(0).getStringCellValue()).isEqualTo("=1+1");
        assertThat(second.getCell(1)).isNull();
        assertThat(second.getCell(2).getNumericCellValue()).isEqualTo(-5.0);
    }

    @Test
    void xlsxShowsTheRatioColumnAsAPercentButStoresTheFraction() throws IOException {
        Sheet s = xlsx(data(List.of("月份", "退貨率"), List.of(row("2026-09", 0.043), row("2026-10", null)), true));

        Cell rate = s.getRow(1).getCell(1);
        assertThat(rate.getNumericCellValue()).isEqualTo(0.043);
        assertThat(rate.getCellStyle().getDataFormatString()).isEqualTo("0.0%");
        assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2026-09");
        assertThat(s.getRow(2).getCell(1)).isNull();
    }

    @Test
    void xlsxStopsAtExcelsRowLimit() {
        ChartExport.requireXlsxRows(1_048_575);
        assertThatThrownBy(() -> ChartExport.requireXlsxRows(1_048_576))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessage("資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV");
    }

    @Test
    void csvStartsWithABomAndWritesPlainNumbersAndEmptyNulls() throws IOException {
        String text = csv(data(List.of("代碼", "金額"), List.of(
            row("007", 1200.0), row("北區", 0.043), row("南區", 1.0E-7), row("東區", 1.0E20),
            row(null, -5.0), row("西區", null), row("中區", 3L)), true));

        assertThat(text).isEqualTo("\uFEFF代碼,金額\r\n007,1200\r\n北區,0.043\r\n南區,0.0000001\r\n東區,100000000000000000000\r\n"
            + ",-5\r\n西區,\r\n中區,3\r\n");
    }

    @Test
    void textStartingWithAFormulaCharacterGetsAQuote() {
        assertThat(Stream.of("=SUM(A1)", "+1", "-x", "@a", "\tb", "\rc", "a=b", "", "北區").map(ChartExport::csvText))
            .containsExactly("'=SUM(A1)", "'+1", "'-x", "'@a", "'\tb", "'\rc", "a=b", "", "北區");
    }

    @Test
    void csvQuotesTextAndHeadersButNotNegativeNumbers() throws IOException {
        assertThat(csv(data(List.of("=標題", "值"), List.of(row("=SUM(A1)", -5.0)), null)))
            .isEqualTo("\uFEFF'=標題,值\r\n'=SUM(A1),-5\r\n");
    }

    @Test
    void filenameDropsWhatWindowsRejectsTrimsAndCutsAt100() {
        assertThat(ChartExport.filename(" 營收/成本: \"北區\" <2026>?*| ", "xlsx")).isEqualTo("營收成本 北區 2026.xlsx");
        assertThat(ChartExport.filename("a\tb\u0007c", "csv")).isEqualTo("abc.csv");
        assertThat(ChartExport.filename("  /?:  ", "csv")).isEqualTo("圖表.csv");
        assertThat(ChartExport.filename(null, "csv")).isEqualTo("圖表.csv");
        assertThat(ChartExport.filename("長".repeat(120), "xlsx")).isEqualTo("長".repeat(100) + ".xlsx");
        // 照 code point 算，emoji 不會被切一半
        assertThat(ChartExport.filename("😀".repeat(101), "csv")).isEqualTo("😀".repeat(100) + ".csv");
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `./mvnw test -Dtest=ChartExportTest`
Expected: COMPILATION ERROR，`cannot find symbol … ChartExport`。

- [ ] **Step 3: 實作**

```java
package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 匯出 spec 2.2：一張卡的 dimensions 和 rows 寫成 xlsx 或 CSV，第一列是表頭，資料照原本的順序。 */
final class ChartExport {
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String CSV_TYPE = "text/csv; charset=UTF-8";
    /** Excel 一個工作表最多 1,048,576 列，扣掉表頭。 */
    static final int XLSX_MAX_ROWS = 1_048_575;
    static final int MAX_NAME = 100;

    private ChartExport() {}

    static void requireXlsxRows(int rows) {
        if (rows > XLSX_MAX_ROWS) throw new ApiException(BAD_REQUEST, "資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV");
    }

    /**
     * 一個工作表「資料」，表頭粗體。數字存成數字格，其他值存成文字格（Excel 不會當成公式執行，補零的代碼也不會掉 0），空值不建格子。
     * 比率卡（percent）的最後一欄用 0.0% 顯示，存的還是小數。SXSSF 只把最近 100 列留在記憶體，其他寫進暫存檔，close() 時刪掉。
     * ponytail: 一格超過 32,767 字時 POI 丟 IllegalArgumentException（400、英文訊息）；真的遇到再截斷或換訊息，CSV 沒有這個限制
     */
    static void xlsx(ChartData d, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook()) {
            Sheet sheet = wb.createSheet("資料");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            CellStyle percent = wb.createCellStyle();
            percent.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            int ratioColumn = Boolean.TRUE.equals(d.percent()) ? d.dimensions().size() - 1 : -1;

            Row top = sheet.createRow(0);
            for (int i = 0; i < d.dimensions().size(); i++) {
                Cell cell = top.createCell(i);
                cell.setCellValue(d.dimensions().get(i));
                cell.setCellStyle(header);
            }
            int r = 1;
            for (List<Object> values : d.rows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.size(); i++) {
                    Object v = values.get(i);
                    if (v == null) continue;
                    Cell cell = row.createCell(i);
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        if (i == ratioColumn) cell.setCellStyle(percent);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
        }
    }

    /** UTF-8 加 BOM（Excel 直接打開才不會亂碼），逗號分隔、CRLF 換行；數字不用科學記號，文字擋掉公式字元，空值寫空字串。 */
    static void csv(ChartData d, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write('\uFEFF');
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.EXCEL)) {
            printer.printRecord(d.dimensions().stream().map(ChartExport::csvText).toList());
            for (List<Object> row : d.rows()) printer.printRecord(row.stream().map(ChartExport::csvValue).toList());
        }
    }

    // null 原樣交給 CSVPrinter：寫成空字串，在第一欄也不加引號
    private static String csvValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return csvText(String.valueOf(v));
    }

    /** 匯出 spec 2.2：以 = + - @ Tab CR 開頭的文字前面加 '，Excel 才不會當成公式執行（CSV injection）。 */
    static String csvText(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    /** 匯出 spec 2.2：拿掉 Windows 檔名不能用的字元和控制字元，去掉頭尾空白，最多 100 字（照 code point 算），什麼都不剩就用「圖表」。 */
    static String filename(String title, String format) {
        String name = (title == null ? "" : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").strip();
        if (name.codePointCount(0, name.length()) > MAX_NAME) name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME)).strip();
        return (name.isEmpty() ? "圖表" : name) + "." + format;
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `./mvnw test -Dtest=ChartExportTest`
Expected: PASS，7 個測試。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/jev/dashboard/dashboard/ChartExport.java backend/src/test/java/jev/dashboard/dashboard/ChartExportTest.java
git commit -m "feat: ChartExport 把一張卡寫成 xlsx 或 CSV" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: 後端：`GET /api/dashboards/{id}/charts/{chartId}/export`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardController.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`

`TestData.dashboardJson` 有四張卡（`sales.csv`，沒有顯示名稱），測試用到下面三張，c2（月數量）沒用到：

- c1：折線「月銷售額」，`order_date` 按月加總 `amount`。2024-01～06 是 2000、2100、3100、2800、3200、4300。自動標題是「amount 依 order_date」，跟「月銷售額」不同，所以檔名用「月銷售額」。
- c3：圓餅「地區佔比」，`region` 加總 `amount`，由大到小是北區 7400、東區 4700、中區 3000、南區 2400。
- c4：長條「利潤」，用了不存在的 `profit`，是 `FIELD_MISSING`。

檔名編碼（Python `urllib.parse.quote` 算過，跟 Spring 的輸出一致）：「月銷售額.xlsx」是 `%E6%9C%88%E9%8A%B7%E5%94%AE%E9%A1%8D.xlsx`，「地區佔比.csv」是 `%E5%9C%B0%E5%8D%80%E4%BD%94%E6%AF%94.csv`。

- [ ] **Step 1: 寫失敗的測試**

`DashboardControllerTest` 的 import 補上：

```java
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
```

在 class 最後加：

```java

    // 匯出 spec 3

    static MockHttpServletRequestBuilder export(String id, String chartId, String format) {
        MockHttpServletRequestBuilder req = get("/api/dashboards/" + id + "/charts/" + chartId + "/export");
        return format == null ? req : req.param("format", format);
    }

    @Test
    void theOwnerAndAViewerDownloadACardAsXlsxOrCsv() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        byte[] xlsx = mvc.perform(export(id, "c1", "xlsx").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
            .andExpect(header().string("Content-Disposition",
                containsString("filename*=UTF-8''%E6%9C%88%E9%8A%B7%E5%94%AE%E9%A1%8D.xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheet("資料");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("order_date");
            assertThat(s.getRow(0).getCell(1).getStringCellValue()).isEqualTo("amount");
            assertThat(s.getLastRowNum()).isEqualTo(6);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2024-01");
            assertThat(s.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(2000.0);
        }

        String csv = mvc.perform(export(id, "c3", "csv").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(header().string("Content-Disposition",
                containsString("filename*=UTF-8''%E5%9C%B0%E5%8D%80%E4%BD%94%E6%AF%94.csv")))
            .andReturn().getResponse().getContentAsString(UTF_8);
        assertThat(csv).isEqualTo("\uFEFFregion,amount\r\n北區,7400\r\n東區,4700\r\n中區,3000\r\n南區,2400\r\n");
    }

    @Test
    void exportAppliesTheFiltersLikeData() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        String north = "{\"fields\":[{\"field\":\"region\",\"values\":[\"北區\"]}]}";
        String productA = "{\"fields\":[{\"field\":\"product\",\"values\":[\"A\"]}]}";

        assertThat(mvc.perform(export(id, "c3", "csv").param("filters", north).with(TestData.as("u1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8))
            .isEqualTo("\uFEFFregion,amount\r\n北區,7400\r\n");
        // 篩選器 spec 2.3：檢視者帶了卡片沒用到的 product，不生效
        assertThat(mvc.perform(export(id, "c3", "csv").param("filters", productA).with(TestData.as("u2")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8))
            .isEqualTo("\uFEFFregion,amount\r\n北區,7400\r\n東區,4700\r\n中區,3000\r\n南區,2400\r\n");
        mvc.perform(export(id, "c3", "csv").param("filters", "{").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("篩選的格式不對"));
    }

    @Test
    void exportNeedsALoginAndTheRightToSeeTheDashboard() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        // 沒登入也能看的儀表板，匯出還是要登入（SecurityConfig 只放行 /{id}、/data、/filter-options）
        putShare("u1", id, "{\"publicLink\":true,\"anonymous\":true,\"users\":[]}", 200);
        mvc.perform(get("/api/dashboards/" + id + "/data")).andExpect(status().isOk());
        mvc.perform(export(id, "c1", "csv"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("請先登入"));
        // 開著公開連結：任何登入的帳號都能下載
        mvc.perform(export(id, "c1", "csv").with(TestData.as("u3"))).andExpect(status().isOk());

        putShare("u1", id, "{\"publicLink\":false,\"users\":[]}", 200);
        notFound(export(id, "c1", "csv").with(TestData.as("u3")));
    }

    @Test
    void exportRejectsAnUnknownCardABadFormatAndACardWithAnError() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(export(id, "nope", "csv").with(TestData.as("u1")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到這張圖"));
        for (String format : new String[] {"pdf", null}) {
            mvc.perform(export(id, "c1", format).with(TestData.as("u1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("format 只能是 xlsx 或 csv"));
        }
        // c4 用了不存在的 profit；檢視者看到的也是同一句，不會換成「資料來源載入失敗」
        for (String user : new String[] {"u1", "u2"}) {
            mvc.perform(export(id, "c4", "xlsx").with(TestData.as(user)))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.error").value("這張圖目前沒有資料可以下載"));
        }
        mvc.perform(export(id, "nope", "csv").with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到這張圖"));
    }

    @Test
    void aViewerSeesAGenericMessageWhenTheExportsSourceFails() throws Exception { // 匯出 spec 2.3
        // 跟 aViewerSeesAGenericMessageWhenTheOwnersSourcesFail 一樣：抓之前就因為環境變數不存在而失敗，不會連網路
        UUID ds = UUID.randomUUID();
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by)
                values (:id, '外部', 'api', '[]', 'http://10.0.0.5/sales', '{"Authorization":"${DS_JEV_TEST_UNSET}"}', 'u1')""")
            .param("id", ds).update();
        String id = create("u1", TestData.dashboardJson(ds.toString()));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(export(id, "c1", "csv").with(TestData.as("u1")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("環境變數 DS_JEV_TEST_UNSET 未設定"));
        mvc.perform(export(id, "c1", "csv").with(TestData.as("u2")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("資料來源載入失敗，請聯絡儀表板擁有者"));
    }
```

`notFound(...)`、`putShare(...)`、`create(...)` 是這個檔案既有的 helper，`startsWith`、`jsonPath` 也已經 import 了。

- [ ] **Step 2: 跑測試，確認失敗**

Run: `./mvnw test -Dtest=DashboardControllerTest`
Expected: 新加的 5 個測試 FAIL（還沒有這個路徑，狀態碼或 `$.error` 對不上）。未登入那一條本來就是 401，要到後面 u3 下載的斷言才會失敗。

- [ ] **Step 3: 實作**

`DashboardController` 的 import 補上（照字母順序插進既有的區塊）：

```java
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jev.dashboard.transform.ChartSpec;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
```

在 `filterOptions` 後面、`preview` 前面加：

```java
    /**
     * 匯出 spec 3：一張卡的資料下載成 xlsx 或 CSV，內容跟 /data 裡這張卡一樣（同一組篩選、顯示名稱），明細表不截斷。
     * 要登入，SecurityConfig 只放行 /{id}、/data、/filter-options；看得到這份儀表板的帳號都能下載，用擁有者的身分載入。
     * 檢查都在寫檔之前做完。只有載入錯誤會替檢視者換成通用訊息（spec 2.3），「找不到這張圖」和卡片錯誤照原文。
     */
    @GetMapping("/{id}/charts/{chartId}/export")
    public void export(Principal principal, @PathVariable UUID id, @PathVariable String chartId,
                       @RequestParam(required = false) String format, @RequestParam(required = false) String filters,
                       HttpServletResponse response) throws IOException {
        if (!"xlsx".equals(format) && !"csv".equals(format)) throw new ApiException(HttpStatus.BAD_REQUEST, "format 只能是 xlsx 或 csv");
        Filters f = Filters.parse(filters);
        String viewer = principal.getName();
        DashboardConfig c = service.view(viewer, id);
        ChartSpec spec = c.layout().stream().filter(s -> s.chartId().equals(chartId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到這張圖"));
        boolean owner = c.createdBy().equals(viewer);
        ChartData d;
        try {
            d = data.exportData(c.createdBy(), spec, f, owner ? Set.of() : data.denied(c.createdBy(), c, f));
        } catch (ApiException e) {
            if (owner) throw e;
            throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
        }
        // 匯出 spec 2.4：欄位遺失、不是日期、來源已刪除或停止分享
        if (d.error() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "這張圖目前沒有資料可以下載");
        boolean xlsx = format.equals("xlsx");
        if (xlsx) ChartExport.requireXlsxRows(d.rows().size());

        response.setContentType(xlsx ? ChartExport.XLSX_TYPE : ChartExport.CSV_TYPE);
        String name = ChartExport.filename(d.title() != null ? d.title() : spec.title(), format);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        if (xlsx) ChartExport.xlsx(d, response.getOutputStream());
        else ChartExport.csv(d, response.getOutputStream());
    }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `./mvnw test -Dtest=DashboardControllerTest`
Expected: PASS。

- [ ] **Step 5: 全部後端測試**

Run: `./mvnw test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/dashboard/DashboardController.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java
git commit -m "feat: 卡片資料下載 API（xlsx、CSV）" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task F1: 前端：`api.exportChart`

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js:14-42`、`:91-96`
- Test: `frontend/src/modules/dashboard/api.test.js`

- [ ] **Step 1: 寫失敗的測試**

在 `api.test.js` 的 `describe('api', …)` 最後加：

```js
  it('匯出 spec 5.2：exportChart 帶 format 和篩選，回傳 blob 和 filename* 的檔名；沒有篩選就不帶，讀不到檔名是 null', async () => {
    const blob = { size: 3 }
    const disposition = `attachment; filename="=?UTF-8?Q?x?="; filename*=UTF-8''%E7%87%9F%E6%94%B6.xlsx`
    fetch.mockResolvedValueOnce({ ok: true, status: 200, blob: async () => blob, headers: { get: (n) => (n === 'Content-Disposition' ? disposition : null) } })
    const filters = { period: 'last_month', fields: [{ field: '地區', values: ['北區'] }] }
    await expect(api.exportChart('d1', 'c1', 'xlsx', filters)).resolves.toEqual({ blob, filename: '營收.xlsx' })
    const url = new URL(fetch.mock.calls[0][0], 'http://x')
    expect(url.pathname).toBe('/api/dashboards/d1/charts/c1/export')
    expect(url.searchParams.get('format')).toBe('xlsx')
    expect(JSON.parse(url.searchParams.get('filters'))).toEqual(filters)
    expect(fetch.mock.calls[0][1].method).toBe('GET')

    fetch.mockResolvedValueOnce({ ok: true, status: 200, blob: async () => blob, headers: { get: () => null } })
    await expect(api.exportChart('d1', 'c1', 'csv', null)).resolves.toEqual({ blob, filename: null })
    expect(fetch.mock.calls[1][0]).toBe('/api/dashboards/d1/charts/c1/export?format=csv')
  })

  it('exportChart 失敗時丟後端的訊息和狀態碼；401 照舊呼叫 onUnauthorized', async () => {
    const message = '資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV'
    fetch.mockResolvedValueOnce(jsonResponse({ error: message }, { status: 400 }))
    await expect(api.exportChart('d1', 'c1', 'xlsx', null)).rejects.toMatchObject({ message, status: 400 })

    const handler = vi.fn()
    onUnauthorized(handler)
    fetch.mockResolvedValueOnce(jsonResponse({ error: '請先登入' }, { status: 401 }))
    await expect(api.exportChart('d1', 'c1', 'csv', null)).rejects.toMatchObject({
      message: '登入逾時，重新登入後請再操作一次',
      status: 401,
    })
    expect(handler).toHaveBeenCalledTimes(1)
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/api.test.js`
Expected: 2 個新測試 FAIL，`api.exportChart is not a function`。

- [ ] **Step 3: 實作**

`request` 多一個參數，最後的 return 改成：

```js
// asResponse：回傳原始的 Response（下載檔案用），錯誤處理跟其他請求一樣
async function request(method, url, body, asResponse = false) {
```

```js
  if (asResponse) return res
  return res.status === 204 ? null : res.json()
```

`api` 物件裡、`highlights` 後面加：

```js
  // 匯出 spec 5.2：回傳檔案和後端給的檔名（Content-Disposition 的 filename*），讀不到檔名是 null
  exportChart: async (id, chartId, format, filters) => {
    const query = new URLSearchParams({ format })
    if (filters) query.set('filters', JSON.stringify(filters))
    const res = await request('GET', `/api/dashboards/${id}/charts/${chartId}/export?${query}`, null, true)
    const m = /filename\*=UTF-8''([^;]+)/i.exec(res.headers.get('Content-Disposition') ?? '')
    return { blob: await res.blob(), filename: m ? decodeURIComponent(m[1]) : null }
  },
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/api.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js
git commit -m "feat: api.exportChart 下載卡片資料" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task F2: 前端：卡片的下載鈕

**Files:**
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`

- [ ] **Step 1: 寫失敗的測試**

`ChartCard.test.js` 第 3 行改成 `import { flushPromises, mount } from '@vue/test-utils'`。

在最外層 `describe('ChartCard', …)` 的最後（KPI「設定」那個測試後面）加：

```js
  describe('匯出 spec 5.1：下載鈕', () => {
    afterEach(() => {
      document.body.innerHTML = ''
    })
    const downloadButton = (card) => card.find('button[aria-label="下載這張圖的資料"]')
    // 選單內容 teleport 到 document.body
    const menuItems = () => [...document.body.querySelectorAll('.v-overlay-container .v-list-item')]

    it('有 download 而且卡片有資料才有，在「設定」「✕」左邊，圖示 mdi-download，title 跟名稱一樣', () => {
      const download = vi.fn()
      const card = kpiCard({ data: kpiData([['2026-09', 1]]), download, configurable: true, removable: true })
      expect(card.findAll('.tc-card-title button').map((b) => b.attributes('aria-label')))
        .toEqual(['下載這張圖的資料', 'KPI 卡設定', '移除這張圖'])
      expect(downloadButton(card).attributes('title')).toBe('下載這張圖的資料')
      expect(downloadButton(card).find('.mdi-download').exists()).toBe(true)
      expect(downloadButton(mountCard({ spec: barSpec, data: barData, highlight: null, download })).exists()).toBe(true)

      // 沒有 download（預覽、訪客）、還在載入、有錯誤、沒有資料、KPI 卡一期都還沒結束：都沒有
      for (const props of [
        { spec: barSpec, data: barData },
        { spec: barSpec, data: null, download },
        { spec: barSpec, data: { chartId: 'c1', type: 'bar', error: 'FIELD_MISSING', missingField: 'profit' }, download },
        { spec: barSpec, data: { ...barData, rows: [] }, download },
        { spec: kpiSpec, data: kpiData([]), download },
      ]) {
        expect(downloadButton(mountCard({ highlight: null, ...props })).exists()).toBe(false)
      }
    })

    it('選單有「Excel（.xlsx）」「CSV」；選 Excel 呼叫 download("xlsx")，等待時按鈕停用，完成後恢復', async () => {
      let finish
      const download = vi.fn(() => new Promise((resolve) => (finish = resolve)))
      const card = mountCard({ spec: barSpec, data: barData, highlight: null, download })
      await downloadButton(card).trigger('click')
      await flushPromises()
      expect(menuItems().map((el) => el.textContent.trim())).toEqual(['Excel（.xlsx）', 'CSV'])

      menuItems()[0].click()
      await flushPromises()
      expect(download).toHaveBeenCalledWith('xlsx')
      expect(downloadButton(card).attributes('disabled')).toBeDefined()
      finish()
      await flushPromises()
      expect(downloadButton(card).attributes('disabled')).toBeUndefined()
      card.unmount()
    })

    it('選 CSV 呼叫 download("csv")', async () => {
      const download = vi.fn().mockResolvedValue()
      const card = mountCard({ spec: barSpec, data: barData, highlight: null, download })
      await downloadButton(card).trigger('click')
      await flushPromises()
      menuItems().find((el) => el.textContent.trim() === 'CSV').click()
      await flushPromises()
      expect(download).toHaveBeenCalledWith('csv')
      card.unmount()
    })
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js`
Expected: 3 個新測試 FAIL（找不到下載鈕）；其他照舊 PASS。

- [ ] **Step 3: 實作**

`<script setup>` 第 2 行改成 `import { computed, ref } from 'vue'`。

`defineProps` 裡 `configurable` 後面加：

```js
  // 匯出 spec 5.2：有這個函式才有下載鈕，由 DashboardView 決定（已存的儀表板、不是訪客）；參數是 'xlsx' 或 'csv'，回傳 Promise
  download: { type: Function, default: null },
```

`filterNote` 後面加：

```js
// 匯出 spec 5.1：有資料才能下載。KPI 卡的 rows 只有已經結束的期，一期都還沒結束時也沒有下載鈕
const downloadable = computed(() => !!props.download && ready.value && props.data.rows?.length > 0)
// 等待中的狀態由卡片自己管；錯誤由 DashboardView 顯示在最上面
const downloading = ref(false)
async function pickFormat(format) {
  downloading.value = true
  try {
    await props.download(format)
  } finally {
    downloading.value = false
  }
}
```

template 裡，「設定」那顆 `v-btn` 前面（`<!-- grid-layout-plus 的 dragIgnoreFrom … -->` 註解下面）插入：

```vue
      <!-- 匯出 spec 5.1：在「設定」「✕」左邊，樣式跟它們一樣；選單 teleport 到 body，點選項不會拖到卡片 -->
      <v-menu v-if="downloadable" location="bottom end">
        <template #activator="{ props: activator }">
          <v-btn
            v-bind="activator"
            icon="mdi-download"
            size="small"
            density="default"
            variant="text"
            class="tc-remove ml-1"
            aria-label="下載這張圖的資料"
            title="下載這張圖的資料"
            :loading="downloading"
            :disabled="downloading"
          />
        </template>
        <v-list density="compact" aria-label="下載格式">
          <v-list-item title="Excel（.xlsx）" @click="pickFormat('xlsx')" />
          <v-list-item title="CSV" @click="pickFormat('csv')" />
        </v-list>
      </v-menu>
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/ChartCard.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js
git commit -m "feat: 卡片標題列加下載鈕和格式選單" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task F3: 前端：儀表板下載、訪客沒有下載

**Files:**
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`、`frontend/src/App.vue:265`
- Test: `frontend/src/modules/dashboard/DashboardView.test.js`、`frontend/src/App.test.js`

- [ ] **Step 1: 寫失敗的測試**

`DashboardView.test.js`：
- 最上面 `vi.mock('./api.js', …)` 的 `api` 加 `exportChart: vi.fn(),`。
- `ChartCard` stub 的 props 陣列最後加 `'download'`。
- 在最外層 `describe('DashboardView', …)` 的最後加：

```js
  describe('匯出 spec 5.2：下載', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })
    let clicked
    beforeEach(() => {
      clicked = []
      vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x')
      vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
      vi.spyOn(HTMLElement.prototype, 'click').mockImplementation(function () {
        clicked.push({ href: this.getAttribute('href'), download: this.download })
      })
    })
    afterEach(() => {
      vi.restoreAllMocks()
    })

    it('預覽和沒登入的訪客不傳 download；已存的儀表板，擁有者和檢視者都有', async () => {
      api.previewData.mockResolvedValue([])
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue([])
      const preview = mountView({ config: makeConfig(), preview: true })
      const guest = mountView({ config: makeConfig(), readonly: true, guest: true })
      const viewer = mountView({ config: makeConfig(), readonly: true })
      const owner = mountView({ config: makeConfig() })
      await flushPromises()

      expect(chartCard(preview, 'c1').props('download')).toBeNull()
      expect(chartCard(guest, 'c1').props('download')).toBeNull()
      expect(chartCard(viewer, 'c1').props('download')).toBeTypeOf('function')
      expect(chartCard(owner, 'c2').props('download')).toBeTypeOf('function')
    })

    it('帶著目前的篩選（包括臨時改的）呼叫 exportChart，用後端給的檔名存檔；沒有檔名就用卡片標題', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1', title: '金額 依 地區', rows: [['北區', 1]] }, { chartId: 'c2', rows: [] }])
      api.highlights.mockResolvedValue([])
      const blob = new Blob(['x'])
      api.exportChart.mockResolvedValueOnce({ blob, filename: '營收.xlsx' }).mockResolvedValueOnce({ blob, filename: null })
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      await chartCard(wrapper, 'c1').props('download')('xlsx')
      expect(api.exportChart).toHaveBeenLastCalledWith('d1', 'c1', 'xlsx', NORTH)
      expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
      await chartCard(wrapper, 'c1').props('download')('csv')
      expect(clicked).toEqual([{ href: 'blob:x', download: '營收.xlsx' }, { href: 'blob:x', download: '金額 依 地區.csv' }])
      expect(wrapper.find('.v-alert').exists()).toBe(false)
    })

    it('下載失敗時，最上面的錯誤寫「下載失敗：」加上後端的訊息，不存檔', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue([])
      api.exportChart.mockRejectedValue(new Error('資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV'))
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()

      await chartCard(wrapper, 'c1').props('download')('xlsx')
      await flushPromises()
      expect(wrapper.get('.v-alert').text()).toBe('下載失敗：資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV')
      expect(clicked).toEqual([])
    })
  })
```

`App.test.js`：
- `DashboardView` stub 的 props 改成 `['config', 'readonly', 'guest']`。
- 「打得開就進訪客檢視…」那個測試，`expect(view.props('readonly')).toBe(true)` 下面加 `expect(view.props('guest')).toBe(true)`。
- 「檢視者：說明文字換掉，DashboardView 是 readonly」那個測試最後加 `expect(wrapper.findComponent({ name: 'DashboardView' }).props('guest')).toBeUndefined()`。

- [ ] **Step 2: 跑測試，確認失敗**

Run: `npx vitest run src/modules/dashboard/DashboardView.test.js src/App.test.js`
Expected: DashboardView 的 3 個新測試 FAIL（`download` 是 undefined）；App 的訪客測試 FAIL（`guest` 是 undefined）。

- [ ] **Step 3: 實作**

`DashboardView.vue` 的 `defineProps` 裡 `readonly` 後面加：

```js
  // 匯出 spec 5.2：App 的訪客檢視（沒登入）傳 true，卡片沒有下載鈕
  guest: { type: Boolean, default: false },
```

`saveKpi` 後面加：

```js
// 匯出 spec 5.2：帶著目前的篩選（包括臨時改的）下載這張卡；檔名用後端給的，讀不到就用畫面上的標題。
// 失敗時訊息放在最上面那條錯誤提示；401 由 api.js 跳出重新登入
async function download(chartId, format) {
  error.value = ''
  try {
    const { blob, filename } = await api.exportChart(props.config.id, chartId, format, filters.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename ?? `${dataById.value[chartId]?.title ?? specById.value[chartId].title}.${format}`
    a.click()
    // 等瀏覽器開始下載再放掉
    setTimeout(() => URL.revokeObjectURL(url))
  } catch (e) {
    error.value = `下載失敗：${e.message}`
  }
}
```

template 的 `<ChartCard` 裡，`:configurable=…` 那一行下面加：

```vue
          :download="preview || guest ? null : (format) => download(item.i, format)"
```

`App.vue` 第 265 行改成：

```vue
        <DashboardView :key="guest.id" :config="guest" :readonly="true" :guest="true" />
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `npx vitest run src/modules/dashboard/DashboardView.test.js src/App.test.js`
Expected: PASS。

- [ ] **Step 5: 全部前端測試和打包**

Run: `npm test`，然後 `npm run build`
Expected: 全部 PASS；build 成功，沒有新的警告。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js frontend/src/App.vue frontend/src/App.test.js
git commit -m "feat: 儀表板帶著目前的篩選下載卡片資料，訪客和預覽沒有下載" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task D1: 文件

**Files:**
- Modify: `README.md`、`docs/backlog.md`、`docs/testing/e2e-playbook.md`

- [ ] **Step 1: 先確認程式**

在 `backend/` 跑 `./mvnw test`（用 Global Constraints 的前綴），在 `frontend/` 跑 `npm test` 和 `npm run build`。有失敗就停下來回報，不在這個 task 修。

- [ ] **Step 2: README 加「下載資料」一節**

放在「## KPI 大數字卡」後面、「## 刪除來源」前面。內容照 spec 2、3、5：

- 已存的儀表板，每張卡標題列右邊有下載鈕，選「Excel（.xlsx）」或「CSV」。下載的是這張卡的資料，跟畫面一樣套用目前的篩選（臨時改的也算），表頭用顯示名稱。
- 各種卡下載到什麼：圖表是圖上的彙總數字，有系列的圖是長表（例如「月份、地區、營收」三欄）；散佈圖每個點一列；明細表是全部的列，不受「僅顯示前 500 筆」限制；KPI 卡是已經結束的期，最多 13 期。
- Excel：一個工作表「資料」；數字是數字格，代碼這類文字是文字格（`007` 不會變成 7）；比率顯示成百分比、存的是小數；超過 1,048,575 列時改下載 CSV。
- CSV：UTF-8 加 BOM，用 Excel 直接打開中文不會亂碼；以 `=`、`+`、`-`、`@` 開頭的文字前面加 `'`，免得被當成公式。
- 檔名是卡片標題。
- 誰能下載：擁有者、被分享的帳號、用公開連結打開的登入帳號。沒登入的訪客沒有下載鈕。製圖頁的預覽不能下載，存成儀表板之後才有。卡片有錯誤或沒有資料時沒有下載鈕。
- 後端是 `GET /api/dashboards/{id}/charts/{chartId}/export?format=xlsx|csv&filters=…`。最後連到 spec。

- [ ] **Step 3: `docs/backlog.md`**

- 第 35 行「P2 · 匯出 Excel／CSV」劃掉，照 KPI、寬表轉長表那幾行的寫法補上：「已完成（<D1 當天的日期，`date +%F`>，branch `feat/chart-export`）：」加兩三句做了什麼（每張卡的下載鈕、Excel 和 CSV、套用目前的篩選和顯示名稱、明細表全部的列、登入的人才能下載），最後是 spec 路徑。
- 下一行加一項 P3「匯出 Excel／CSV 的後續」，列 spec 第 9 節除了「匯出圖片或 PDF」以外的項目：整份儀表板匯出成一個檔案（每張卡一個工作表）、圖表卡附上原始資料列（跟「下鑽」一起做）、從製圖頁的預覽匯出、讓沒登入的訪客匯出（等「沒登入的 API 呼叫次數限制」做完）、日期存成 Excel 的日期格、有系列的圖轉成交叉表、在檔案裡寫篩選條件、資料來源和匯出時間。結尾寫「（匯出 spec 第 9 節）」。

- [ ] **Step 4: `docs/testing/e2e-playbook.md` 加 S24**

1. 第 5 節的 helper：
   - `jev` 物件裡、`dashboard` 後面加：

     ```js
         // 匯出 spec 3：直接打下載 API；CSV（和錯誤）把內容解成文字，ignoreBOM 留著 BOM 才看得出開頭有沒有
         exportCard: async (id, chartId, format, filters) => {
           const q = new URLSearchParams({ format })
           if (filters) q.set('filters', JSON.stringify(filters))
           const res = await (window.__jevFetch ?? fetch)(`/api/dashboards/${id}/charts/${chartId}/export?${q}`)
           const bytes = new Uint8Array(await res.arrayBuffer())
           return {
             status: res.status, type: res.headers.get('Content-Type'), disposition: res.headers.get('Content-Disposition'),
             size: bytes.length, zip: bytes[0] === 0x50 && bytes[1] === 0x4b,
             text: format === 'csv' || !res.ok ? new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes) : undefined,
           }
         },
     ```

   - `cards()` 裡 `settings` 後面加 `download: !!el.querySelector('[aria-label="下載這張圖的資料"]'),`。
   - 「常用的呼叫」表格加一列：「下載一張卡的資料（xlsx 只看狀態、型別和是不是 zip）」，function 是 `async () => window.jev.exportCard(await window.jev.dashId('E2E 銷售總覽'), 'c1', 'csv', { period: null, fields: [{ field: '區域', values: ['北區'] }] })`。
2. S23 後面、S99 前面加「### S24 下載資料」，格式跟 S23 一樣：目的、帳號／context（`alice`、`bob`、`carol`、`anon`）、前置條件（S23 完成）、步驟表、留下的狀態。chartId、儀表板 id 都在執行時用 `jev.dashboard()`、`jev.dashId()` 查，不要寫死。步驟：

   | 編號 | 操作 | 預期結果 | 檢查方式 |
   |---|---|---|---|
   | S24-1 | alice 打開「E2E 銷售總覽」，跑 helper；`jev.cards()` | 預設篩選是「區域：北區」。有資料的卡片 `download` 都是 true；下載鈕在「✕」左邊，KPI 卡的下載鈕在「設定」左邊 | 畫面、截圖 |
   | S24-2 | 按營收 KPI 卡的下載鈕 | 選單有「Excel（.xlsx）」「CSV」 | 畫面 |
   | S24-3 | 選「CSV」；`list_network_requests` | 有一筆 `GET /api/dashboards/<id>/charts/<這張卡>/export?format=csv&filters=…`，200，filters 是北區；最上面沒有錯誤 | API |
   | S24-4 | `jev.exportCard(<id>, <營收 KPI>, 'csv', <北區>)`，跟 `jev.dashboard(undefined, <北區>)` 同一張卡比 | 200；`text` 以 `\uFEFF` 開頭；第一列是那張卡的 `dims`（顯示名稱）；之後每一列跟 `data` 一樣，整數不帶 `.0` | API |
   | S24-5 | 同上，換成毛利率（月）KPI 卡、`'xlsx'` | 200；`type` 是 xlsx 的 Content-Type；`zip` 是 true；`disposition` 有 `filename*=UTF-8''`，`decodeURIComponent` 之後是卡片標題加 `.xlsx` | API |
   | S24-6 | 篩選改成「區域：南區」（不要存成預設），挑一張長條圖，從下載鈕選 CSV；再跑 `jev.exportCard(…, 'csv', <南區>)` 跟 `jev.dashboard(undefined, <南區>)` 比 | network 那一筆的 filters 是南區；CSV 的列跟南區的 `data` 一樣 | 畫面、API |
   | S24-7 | 建 600 筆的來源：`async () => { const lines = ['代碼,名稱,金額']; for (let i = 1; i <= 600; i++) lines.push(`${String(i).padStart(3, '0')},品項${i},${i * 10}`); const form = new FormData(); form.append('file', new File([lines.join('\n')], 'codes.csv')); form.append('name', '代碼明細'); return window.jev.api('POST', '/api/datasources/upload', form) }`；`jev.source('代碼明細')` | 200；`rowCount` 600；代碼是文字（string） | API |
   | S24-8 | 用 API 建儀表板：`jev.api('POST', '/api/dashboards', { name: 'E2E 代碼明細', layout: [{ chartId: 'c1', datasourceId: <代碼明細的 id>, type: 'table', title: '代碼明細', gridPos: { x: 0, y: 0, w: 12, h: 5 }, mapping: { columns: ['代碼', '名稱', '金額'] } }] })`；重新整理後打開它 | 明細表上面寫「僅顯示前 500 筆」，第一列代碼是 `001`；有下載鈕 | 畫面、截圖 |
   | S24-9 | `jev.exportCard(<id>, 'c1', 'csv')` | `text` 去掉最後的 `\r\n` 再用 `\r\n` 切開是 601 列；第 2 列 `001,品項1,10`，最後一列 `600,品項600,6000` | API |
   | S24-10 | `jev.exportCard(<id>, 'c1', 'xlsx')` | 200，`zip` 是 true | API |
   | S24-11 | 打開「E2E 分店」（卡片都是已刪除）；`jev.cards()`；`jev.exportCard(<id>, <第一張卡>, 'csv')` | 每張卡 `download` 都是 false；API 回 400，`text` 是 `{"error":"這張圖目前沒有資料可以下載"}` | 畫面、API |
   | S24-12 | bob 打開「E2E 銷售總覽」（被分享的）；`jev.cards()`；`jev.exportCard(…, 'csv')` 下載任一張有資料的卡 | `download` 是 true；200 | 畫面、API |
   | S24-13 | carol 登入（`isolatedContext: "carol"`，密碼 `Carol-reset-02`），打開「E2E 對照」的公開連結 `#/d/<id>` | 有下載鈕；`jev.exportCard` 200 | 畫面、API |
   | S24-14 | alice：先 `GET /api/dashboards/<E2E 對照>/share`，再 `PUT` 同一份設定，只把 `anonymous` 改成 true | 200 | API |
   | S24-15 | `anon` 開 `http://localhost:5181/#/d/<E2E 對照>`，跑 helper；`jev.cards()`；`jev.exportCard(…, 'csv')` | 訪客檢視，每張卡 `download` 都是 false；API 回 401，`text` 是 `{"error":"請先登入"}` | 畫面、API |
   | S24-16 | alice 把 `anonymous` 改回 false | 200；跟 S20 結束時一樣 | API |
   | S24-17 | alice 到「製圖」選「代碼明細」，需求填 `各品項金額`，按「分析」，等預覽 | 預覽的卡片都沒有下載鈕（`jev.cards()` 的 `download` 都是 false）。不用存檔〔JEV〕 | 畫面 |

   留下的狀態：多了「代碼明細」來源和「E2E 代碼明細」儀表板；「E2E 對照」的「不用登入也能看」還是關著。
3. 第 8 節「在哪一節驗到什麼」的表格加一列：「下載資料（Excel／CSV）| S24」。

- [ ] **Step 5: 用 `humanizer-zh` 潤過這次改的文字**（使用者的 CLAUDE.md 規定）

只潤 README 新的一節、backlog 改的兩行、S24 的說明文字。程式碼和步驟表裡的指令不動。

- [ ] **Step 6: Commit**

```bash
git add README.md docs/backlog.md docs/testing/e2e-playbook.md
git commit -m "docs: README、backlog、E2E 劇本補上下載資料（S24）" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 瀏覽器驗證（主 session 自己做，不交給子 agent）

D1 之後，主 session 照 spec 第 6 節「瀏覽器」的 6 步自己做：

- 用新的 schema（例如 `jev_e2e_export_<時間>`）在 8081 起後端、5181 起前端（`API_TARGET=http://127.0.0.1:8081`），做法照 E2E 劇本 S00。8080、5180 是使用者自己的，不要碰。
- 資料要有長條圖、明細表、KPI 卡和比率圖。明細表要超過 500 列，而且有補零的代碼欄（可以照 S24-7 用 FormData 上傳）。
- 每張卡各下載一次 Excel 和 CSV。用 `evaluate_script` 在頁面裡 `fetch` 下載 API，把 xlsx 的位元組轉成 base64 傳回來，存進 scratchpad，用 Python `openpyxl` 讀（先 `python -c "import openpyxl"`；沒有的話在 scratchpad 建 venv 再裝，不要裝進系統的 Python）。要核對：表頭是顯示名稱、數字跟畫面一樣、比率的格式是 `0.0%`、`007` 還是 `007`、明細表的列數是全部。
- 也要按一次真的下載鈕，確認瀏覽器存下來的檔名是中文的卡片標題，沒有亂碼。
- 篩選、被分享的帳號、訪客、預覽，照 S24 的步驟各看一次。截圖自己看過才算過。
- 做完照 S99 收掉自己開的程序。
