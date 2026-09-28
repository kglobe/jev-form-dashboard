# JEV 儀表板：對照來源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-28-compare-datasource-design.md`：疊加定義多一個 `compare: true`（對照），數值欄位強制依來源分開；長條圖、折線圖可以一次畫兩個數值，差距大時用左右兩條 Y 軸；JEV 分析對照來源時會優先問「一個時間或分類欄位配兩個數值」的組合；計數聚合改成只算非空值。

**Architecture:** 對照不是新的合併方式，而是 `MergeDefinition.Union` 多一個 boolean `compare`，合併、敏感繼承、顯示名稱、巢狀層數全部沿用疊加。`MergeEngine` 多兩條驗證、一條提示規則，以及算出「對照組」的純函式 `comparison`。轉換器的 `Mapping.measures` 原本只給雷達圖用，現在長條圖和折線圖也能用（剛好 2 個）。`ConfigAssembler` 拿到對照組時多產生三欄組合，排序改成焦點 → 對照 → 重要性；`AnalyzeService` 把對照來源的來源欄當成 `ignore`。前端新增 `charts/measures.js` 給長條、折線共用，`MergeForm` 的「方式」多一個「對照」。

**Tech Stack:** Java 21、Spring Boot 3.5.16（Jackson 2.21）、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、ECharts 6 + vue-echarts、Vitest 5 + @vue/test-utils 2.5 + happy-dom 20。

前一份計畫是 `docs/superpowers/plans/2026-09-27-merged-datasource.md`（B1），這份只寫新增和改變的部分。

## Global Constraints

- 在主工作目錄 `C:\GitHub\jev-form-dashboard` 做，branch 是 `feat/compare-datasource`，從 `docs/b2-compare-spec` 開（上面有 B2 spec 和這份計畫的 commit）。這次沒有 migration，也沒有平行開發，所以不另開 worktree。
- 後端測試用預設的測試 schema（`application-test.yml` 的 `jev_dashboard_test`），不用加 schema 環境變數。手動驗證（Task 9）的後端用全新的 `jev_dashboard_e2e_b2`。使用者開發用的 `jev_dashboard` 完全不碰。
- 本機是 Windows 11 + Git Bash。不要用 `python3`（用 `python`）；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`。不要跑 `./mvnw clean`。後端測試會連本機 PostgreSQL，帳密在 `backend/.env`。
- 不要印出 `backend/.env` 的內容，`cat`、`grep` 整行都不行。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`，不能 import 這個資料夾以外的檔案。
- 不新增 npm 或 Maven 套件。
- 既有測試全部照舊通過，而且既有測試一行都不改：這份計畫只在既有測試檔裡加新的測試、helper 和 import。唯一會改變既有行為的是計數聚合（spec 5.4），既有測試裡沒有「有 value 又用 count」的案例。
- 錯誤回應固定是 `{"error": "訊息"}`。這次新增的 400 訊息一字不差照下面寫：
  - 對照定義：「對照時，數字欄位「{欄位名稱}」只能對應一個來源」「對照至少要有兩個來源各提供一個數字欄位」。
  - 卡片（存檔、預覽、「調整」的 current 都會驗證，前面由現有程式加上「圖表 {chartId}：」）：「value 和 measures 只能擇一」「兩個數值的圖需要剛好兩個不同的 measures」「兩個數值的圖不能用 aggregate none」。
- 送給 JEV 的對照組合題目多這一句，一字不差：「兩個數值來自不同來源，要放在同一張圖上對照。」
- 畫面上的字照 spec 第 6 節：方式「對照（數值依來源分開比較）」；拆開的數字欄位名稱「{來源名}·{欄位}」（中間是 U+00B7 `·`）；標紅訊息「對照時數字欄位只能對應一個來源」；預設名稱「{來源名，用／分隔} 對照」；說明列「對照：{來源名，用、分隔}」。
- 雙 Y 軸門檻：兩個數值各自的最大絕對值都大於 0，而且大的除以小的「超過」10（剛好 10 倍不算）。
- 每個 commit 都用明確的 pathspec，格式是 `git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits 加繁體中文，例如 `feat(backend): …`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. `compare` 是 `Union` record 的 primitive boolean，另外保留原本三個參數的建構子（`compare = false`），既有程式和測試都不用改。B1 存下來的 `merge_json` 沒有這個欄位，Jackson 會給 `false`。API 回傳一般疊加時也會帶 `"compare": false`，前端只看 truthy，沒有影響。
2. 錯誤訊息裡的欄位名稱用「」包，跟現有訊息一致。spec 5.1 寫『』是因為外面已經有一層「」。
3. `Combo.compare()` 從欄位推導：三個欄位裡剛好兩個是 `measure`。一般的三欄組合遇到兩個數值時，`tripleTypes` 不會給任何圖型，所以只有對照組合符合；`adjustCombos` 重建 `Combo` 時也不會弄丟這個判斷。
4. 某一組沒有任何值時，那個數值是 `null`，`count` 也一樣（不是 0）。規則只有一條：沒有值就是空白。
5. 對照組只看最上層的對照定義；對照來源再拿去疊加，外層不算對照。
6. 分類軸的對照長條圖不限分類數，跟單一數值的長條圖一樣。
7. 長條圖和折線圖的兩個數值寫在新檔 `charts/measures.js`，兩邊共用；拿到兩欄資料時，`bar.js`、`line.js` 的輸出跟現在完全一樣，快照不變。
8. 「來源」選單選滿 5 個時，用 Vuetify `item-props` 函式把其他選項停用；疊加和對照共用同一個選單。
9. 合併表單切到查表時，疊加／對照的對應表不動；從查表切回來、或在疊加和對照之間切換，照新的方式重排（跟換來源一樣，手動改過的對應會不見，沿用 B1 的 `ponytail:` 註記）。
10. 異常檢查裡的空值照舊當 0（spec 5.7）。

## File Structure

```
backend/src/main/java/jev/dashboard/
  datasource/MergeDefinition.java     Union 加 compare（Task 1）
  datasource/MergeEngine.java         對照的驗證、提示；Comparison、comparison（Task 1）
  transform/UniversalTransformer.java 兩個數值的長條、折線；計數只算非空值；problem（Task 2）
  jev/ConfigAssembler.java            對照組合、排序、mapping、標題（Task 3）
  jev/AnalyzeService.java             接對照組、來源欄當 ignore、題目多一句（Task 4）
  jev/JevController.java              把 MergeEngine.comparison 傳給 AnalyzeService（Task 4）
  dashboard/DashboardDataService.java 顯示名稱套到 measures（Task 5）
  jev/HighlightSummarizer.java        兩個數值各當一條系列（Task 5）
backend/src/test/java/jev/dashboard/
  datasource/MergeEngineTest.java           加測試（Task 1）
  datasource/DatasourceControllerTest.java  加測試（Task 1）
  transform/UniversalTransformerTest.java   加測試（Task 2）
  dashboard/DashboardServiceTest.java       加測試（Task 2）
  jev/ConfigAssemblerTest.java              加測試（Task 3）
  jev/AnalyzeServiceTest.java               加測試（Task 4）
  jev/JevControllerTest.java                加測試（Task 4）
  dashboard/DashboardDataServiceTest.java   加測試（Task 5）
  jev/HighlightSummarizerTest.java          加測試（Task 5）
frontend/src/modules/dashboard/
  charts/measures.js       新增：兩個數值、雙 Y 軸（Task 6）
  charts/bar.js、line.js   三欄資料交給 measures.js（Task 6）
  charts/charts.test.js    加測試（Task 6）
  ChartCard.vue            數字縮寫處理兩條 Y 軸（Task 6）
  ChartCard.test.js        加測試（Task 6）
  MergeForm.vue            對照、第 6 個來源停用（Task 7）
  MergeForm.test.js        加測試（Task 7）
  DatasourcePanel.vue      說明列「對照：」（Task 7）
  DatasourcePanel.test.js  加測試（Task 7）
README.md、docs/backlog.md、B2 spec 第 10 節（Task 8）
docs/superpowers/e2e/compare-datasource/*.csv   手動驗證用的兩份 CSV（Task 9）
```

---

### Task 1: 對照定義：`Union.compare`、驗證、提示、對照組

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java`（`Union` record）
- Modify: `backend/src/main/java/jev/dashboard/datasource/MergeEngine.java`（`validateUnion`、`union`，另外加 `Comparison`、`comparison`）
- Test: `backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`

**Interfaces:**
- Produces：
  - `MergeDefinition.Union(List<UUID> inputs, List<Column> columns, String sourceColumn, boolean compare)`；保留 `Union(inputs, columns, sourceColumn)`（`compare = false`）
  - `MergeEngine.Comparison(Set<Set<String>> pairs, String sourceColumn)`（public record）
  - `static MergeEngine.Comparison MergeEngine.comparison(Datasource ds)`：不是對照來源回 `null`；`pairs` 的每個元素是兩個欄位名稱組成的 `Set.of(a, b)`

- [ ] **Step 1: 確認 branch，跑一次既有測試記下基準數量**

```bash
cd /c/GitHub/jev-form-dashboard && git branch --show-current
```

Expected：印出 `feat/compare-datasource`。不對就停下來回報。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：`Failures: 0, Errors: 0` 和 `BUILD SUCCESS`。記下 `Tests run:` 的數字，之後每個後端 task 結束時的總數＝這個數字＋新加的測試數。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：全部通過，同樣記下數字。

- [ ] **Step 2: 寫會失敗的測試**

`MergeEngineTest.java` 的 import 加上：

```java
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Set;
```

在 class 裡、`regionAmountReason()` 前面加欄位和 helper：

```java
    final Loaded sales = csv("銷售", "order_date,region,amount", "2026-07-03,北區,1000", "2026-08-02,南區,1200");
    final Loaded refunds = csv("退貨", "return_date,region,amount,qty", "2026-08-05,北區,50,1");

    /** 對照：銷售、退貨，來源欄叫「來源」。 */
    Union compare(Union.Column... columns) {
        return new Union(ids(sales, refunds), List.of(columns), "來源", true);
    }

    Union.Column date() {
        return new Union.Column("date", from(sales, "order_date", refunds, "return_date"));
    }

    static Datasource mergedSource(Union u, Loaded... inputs) {
        List<FieldSchema> schema = MergeEngine.schema(MergeEngine.union(u, List.of(inputs)));
        return new Datasource(UUID.randomUUID(), "對照", "merged", schema, null, Map.of(), "u1", null, u);
    }
```

在 class 最後加三個測試：

```java
    @Test
    void compareKeepsEveryNumberColumnToOneSourceAndNeedsTwoSourcesWithNumbers() {
        Function<UUID, Datasource> input = only(sales, refunds);
        Union merged = compare(date(), new Union.Column("amount", from(sales, "amount", refunds, "amount")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(merged, input))
            .isInstanceOf(ApiException.class).hasMessage("對照時，數字欄位「amount」只能對應一個來源");

        Union oneSide = compare(date(), new Union.Column("銷售·amount", from(sales, "amount")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(oneSide, input))
            .isInstanceOf(ApiException.class).hasMessage("對照至少要有兩個來源各提供一個數字欄位");
        Union sameSource = compare(date(),
            new Union.Column("退貨·amount", from(refunds, "amount")), new Union.Column("退貨·qty", from(refunds, "qty")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(sameSource, input))
            .isInstanceOf(ApiException.class).hasMessage("對照至少要有兩個來源各提供一個數字欄位");

        Union ok = compare(date(), new Union.Column("region", from(sales, "region", refunds, "region")),
            new Union.Column("銷售·amount", from(sales, "amount")), new Union.Column("退貨·amount", from(refunds, "amount")));
        assertThatCode(() -> MergeEngine.validateUnion(ok, input)).doesNotThrowAnyException();
        // 一般疊加照舊可以把兩邊的數字併成一欄
        Union plain = new Union(ids(sales, refunds), List.of(date(),
            new Union.Column("amount", from(sales, "amount", refunds, "amount"))), "來源");
        assertThatCode(() -> MergeEngine.validateUnion(plain, input)).doesNotThrowAnyException();
    }

    @Test
    void compareOnlyWarnsAboutTextAndDateColumnsThatSomeSourcesLack() {
        Union u = compare(date(), new Union.Column("region", from(sales, "region")),
            new Union.Column("銷售·amount", from(sales, "amount")), new Union.Column("退貨·amount", from(refunds, "amount")));
        Result r = MergeEngine.union(u, List.of(sales, refunds));

        assertThat(r.warnings()).containsExactly("「region」只有銷售有，其他來源的列這欄是空的");
        assertThat(r.rows().get(2).keySet()).containsExactly("date", "退貨·amount", "來源");
        assertThat(r.rows().get(2).get("退貨·amount")).isEqualTo(50.0);
    }

    @Test
    void comparisonPairsNumberColumnsFromDifferentSourcesOnly() {
        Union u = compare(date(), new Union.Column("銷售·amount", from(sales, "amount")),
            new Union.Column("退貨·amount", from(refunds, "amount")), new Union.Column("退貨·qty", from(refunds, "qty")));

        MergeEngine.Comparison c = MergeEngine.comparison(mergedSource(u, sales, refunds));

        assertThat(c.sourceColumn()).isEqualTo("來源");
        // 退貨·amount 和 退貨·qty 來自同一個來源，不配對
        assertThat(c.pairs()).containsExactlyInAnyOrder(Set.of("銷售·amount", "退貨·amount"), Set.of("銷售·amount", "退貨·qty"));
        // 一般疊加、上傳來源都不是對照
        Union plain = new Union(u.inputs(), u.columns(), "來源");
        assertThat(MergeEngine.comparison(mergedSource(plain, sales, refunds))).isNull();
        assertThat(MergeEngine.comparison(sales.datasource())).isNull();
    }
```

`DatasourceControllerTest.java` 最後加一個測試（沿用檔案裡現有的 import）：

```java
    @Test
    void aCompareSourceKeepsCompareInItsDefinition() throws Exception {
        String sales = TestData.uploadCsv(mvc, "u1", "銷售", "order_date,amount\n2026-07-03,1000\n");
        String refunds = TestData.uploadCsv(mvc, "u1", "退貨", "return_date,amount\n2026-08-05,50\n");
        String merge = """
            {"op":"union","compare":true,"inputs":["%1$s","%2$s"],"columns":[
              {"name":"date","from":{"%1$s":"order_date","%2$s":"return_date"}},
              {"name":"銷售·amount","from":{"%1$s":"amount"}},
              {"name":"退貨·amount","from":{"%2$s":"amount"}}],"sourceColumn":"來源"}""".formatted(sales, refunds);

        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售／退貨 對照\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merge.compare").value(true))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        String list = mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        List<Boolean> compare = JsonPath.read(list, "$[?(@.id == '" + id + "')].merge.compare");
        assertThat(compare).containsExactly(true);
    }
```

`DatasourceControllerTest.java` 的 import 加上 `import java.util.List;`（已經有就不用加）。

- [ ] **Step 3: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='MergeEngineTest,DatasourceControllerTest'`
Expected：編譯失敗，`Union` 沒有四個參數的建構子、找不到 `MergeEngine.Comparison`。

- [ ] **Step 4: `Union` 加 `compare`**

`MergeDefinition.java` 的 `Union` 換成：

```java
    /**
     * inputs 的順序就是資料列的順序；sourceColumn 是標出每列來自哪個來源的欄位。
     * compare 是對照（B2 spec 4.1）：數字欄位依來源分開，JSON 沒寫就是 false。
     */
    record Union(List<UUID> inputs, List<Column> columns, String sourceColumn, boolean compare) implements MergeDefinition {
        public Union(List<UUID> inputs, List<Column> columns, String sourceColumn) {
            this(inputs, columns, sourceColumn, false);
        }

        /** from：輸入來源 id → 那個來源裡提供這個輸出欄位的欄位名稱。 */
        public record Column(String name, Map<UUID, String> from) {}
    }
```

- [ ] **Step 5: 對照的驗證**

`MergeEngine.validateUnion` 裡，`Set<String> taken = new HashSet<>();` 下一行加：

```java
        Set<UUID> numberSources = new HashSet<>(); // 對照：提供數字欄位的來源
```

同一個方法裡，`for (Union.Column c : u.columns())` 迴圈的最後（內層 `for (Map.Entry<UUID, String> e : c.from().entrySet())` 結束之後、外層迴圈的 `}` 之前）加：

```java
            // B2 spec 5.1：對照時數字欄位依來源分開
            if (u.compare() && first.type() == FieldType.NUMBER) {
                if (c.from().size() > 1) throw bad("對照時，數字欄位「" + c.name() + "」只能對應一個來源");
                numberSources.add(c.from().keySet().iterator().next());
            }
```

外層迴圈結束後、`requireName(u.sourceColumn(), taken, "來源欄名稱");` 前面加：

```java
        if (u.compare() && numberSources.size() < 2) throw bad("對照至少要有兩個來源各提供一個數字欄位");
```

- [ ] **Step 6: 對照的提示**

`MergeEngine.union` 裡：

```java
            if (has.size() < inputs.size()) {
```

換成：

```java
            // B2 spec 5.2：對照的數字欄位本來就只來自一個來源，不出提示
            if (has.size() < inputs.size() && !(u.compare() && type == FieldType.NUMBER)) {
```

- [ ] **Step 7: 對照組**

`MergeEngine` 的 `Output` record 下面加：

```java
    /** B2 spec 4.2、5.5：對照組（來自不同來源的兩個數字欄位，名稱組成的 Set）和來源欄名稱。 */
    public record Comparison(Set<Set<String>> pairs, String sourceColumn) {}
```

`sources` 方法前面加：

```java
    /** 不是對照來源回 null。型別看合併來源自己的 schema；只看最上層的定義。 */
    public static Comparison comparison(Datasource ds) {
        if (!(ds.merge() instanceof Union u) || !u.compare()) return null;
        Set<String> numbers = ds.schema().stream().filter(f -> f.type() == FieldType.NUMBER)
            .map(FieldSchema::name).collect(Collectors.toSet());
        List<Union.Column> measured = u.columns().stream()
            .filter(c -> numbers.contains(c.name()) && c.from().size() == 1).toList();
        Set<Set<String>> pairs = new HashSet<>();
        for (int i = 0; i < measured.size(); i++) {
            for (int j = i + 1; j < measured.size(); j++) {
                if (!measured.get(i).from().keySet().equals(measured.get(j).from().keySet())) {
                    pairs.add(Set.of(measured.get(i).name(), measured.get(j).name()));
                }
            }
        }
        return new Comparison(pairs, u.sourceColumn());
    }
```

- [ ] **Step 8: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='MergeEngineTest,DatasourceControllerTest,MergedDatasourceServiceTest,DatasourceRepositoryTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋4，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 9: Commit**

```bash
F="backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java"
git add $F && git commit -m "feat(backend): 疊加加上對照模式，數字欄位強制依來源分開" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 2: 轉換器：兩個數值、計數只算非空值、卡片驗證

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java`
- Test: `backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java`
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Produces：長條圖、折線圖的 `Mapping` 可以用 `measures`（剛好 2 個不同的欄位）取代 `value`；`transform` 回傳 dimensions `[x, a, b]`，某一組沒有值的數值是 `null`。`UniversalTransformer.problem` 認得 8 節的三種錯誤。

- [ ] **Step 1: 寫會失敗的測試**

`UniversalTransformerTest.java` 的 import 加上 `import java.util.Arrays;`。在 class 最後加：

```java
    /** 對照來源的資料列：每一列只有一邊的數值（B2 spec 5.3）；最後一列沒有日期。 */
    static final List<Map<String, Object>> COMPARE = List.of(
        Map.of("date", "2026-01-05", "region", "北區", "sales", 100.0),
        Map.of("date", "2026-01-20", "region", "南區", "sales", 250.0),
        Map.of("date", "2026-02-03", "region", "北區", "sales", 200.0),
        Map.of("date", "2026-02-10", "region", "北區", "refund", 20.0),
        Map.of("date", "2026-02-11", "region", "東區", "refund", 5.0),
        Map.of("region", "北區", "refund", 7.0));
    static final Set<String> COMPARE_FIELDS = Set.of("date", "region", "sales", "refund");

    static ChartData compare(String type, String x, String aggregate, String grain) {
        return UniversalTransformer.transform(new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4),
            new Mapping(x, null, null, null, List.of("sales", "refund"), null, aggregate, grain), Map.of()),
            COMPARE, COMPARE_FIELDS);
    }

    @Test
    void twoMeasuresAreSummedSeparatelyAndAPeriodWithoutOneSideIsNull() {
        ChartData d = compare("line", "date", "sum", "month");
        assertThat(d.dimensions()).containsExactly("date", "sales", "refund");
        assertThat(d.rows()).isEqualTo(List.of(Arrays.asList("2026-01", 350.0, null), List.of("2026-02", 200.0, 25.0)));
    }

    @Test
    void twoMeasuresByCategorySortByTheFirstMeasureWithNullsLast() {
        ChartData d = compare("bar", "region", "sum", null);
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("北區", 300.0, 27.0), Arrays.asList("南區", 250.0, null), Arrays.asList("東區", null, 5.0)));
    }

    @Test
    void twoMeasuresAverageAndCountLookOnlyAtEachMeasuresOwnValues() {
        assertThat(compare("bar", "region", "avg", null).rows()).isEqualTo(List.of(
            Arrays.asList("南區", 250.0, null), List.of("北區", 150.0, 13.5), Arrays.asList("東區", null, 5.0)));
        assertThat(compare("bar", "region", "count", null).rows()).isEqualTo(List.of(
            List.of("北區", 2.0, 2.0), Arrays.asList("南區", 1.0, null), Arrays.asList("東區", null, 1.0)));
    }

    @Test
    void countOnlyCountsRowsWhereTheValueIsPresent() {
        // 南區 2024-04-10 那列的 amount 是空的，不算
        assertThat(run("bar", xv("region", "amount", "count", null)).rows())
            .isEqualTo(List.of(List.of("北", 2.0), List.of("南", 1.0)));
        // 沒有 value 時照舊算列數
        assertThat(run("bar", xv("region", null, "count", null)).rows())
            .isEqualTo(List.of(List.of("北", 2.0), List.of("南", 2.0)));
        ChartData radar = run("radar", new Mapping(null, null, "region", null, List.of("amount", "qty"), null, "count", null));
        assertThat(radar.rows()).isEqualTo(List.of(List.of("北", 2.0, 2.0), List.of("南", 1.0, 2.0)));
    }
```

`DashboardServiceTest.java` 在 `aggregateAndTimeGrainMustBeSupported` 後面加：

```java
    @Test
    void twoMeasureChartsNeedExactlyTwoDifferentMeasuresAndAnAggregate() {
        Mapping ok = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        assertThatCode(() -> DashboardService.requireComplete(config(card("line", ok)))).doesNotThrowAnyException();
        assertThatCode(() -> DashboardService.requireComplete(config(card("bar", ok)))).doesNotThrowAnyException();

        rejects(config(card("bar", new Mapping("region", null, null, "sales", List.of("sales", "refund"), null, "sum", null))),
            "圖表 c1：value 和 measures 只能擇一");
        String two = "圖表 c1：兩個數值的圖需要剛好兩個不同的 measures";
        rejects(config(card("line", new Mapping("date", null, null, null, List.of("sales"), null, "sum", null))), two);
        rejects(config(card("line", new Mapping("date", null, null, null, List.of("sales", "refund", "qty"), null, "sum", null))), two);
        rejects(config(card("line", new Mapping("date", null, null, null, List.of("sales", "sales"), null, "sum", null))), two);
        rejects(config(card("line", new Mapping("date", null, null, null, Arrays.asList("sales", null), null, "sum", null))), two);
        rejects(config(card("bar", new Mapping("region", null, null, null, List.of("sales", "refund"), null, "none", null))),
            "圖表 c1：兩個數值的圖不能用 aggregate none");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='UniversalTransformerTest,DashboardServiceTest'`
Expected：新加的 5 個測試失敗（兩個數值的圖目前被當成 `value` 是 null 的計數；count 把空值也算進去；驗證沒擋）。

- [ ] **Step 3: 卡片驗證**

`UniversalTransformer.problem` 最後的 `return null;` 前面加：

```java
        // B2 spec 5.3、第 8 節：長條圖、折線圖可以用剛好兩個不同的 measures 取代 value
        if (m.measures() != null && (spec.type().equals("bar") || spec.type().equals("line"))) {
            if (m.value() != null) return "value 和 measures 只能擇一";
            if (m.measures().size() != 2 || blank(m.measures()) || m.measures().get(0).equals(m.measures().get(1))) {
                return "兩個數值的圖需要剛好兩個不同的 measures";
            }
            if ("none".equals(m.aggregate())) return "兩個數值的圖不能用 aggregate none";
        }
```

- [ ] **Step 4: 兩個數值的分組**

`transform` 的 switch 裡：

```java
            case "bar", "line", "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
```

換成：

```java
            case "bar", "line" -> m.measures() != null
                ? measured(spec, rows, m.x(), m.measures(), agg, m.timeGrain())
                : grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
            case "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
```

`radar` 方法前面加：

```java
    /**
     * B2 spec 5.3：兩個數值各自聚合；某一組沒有這個數值的任何值就是 null，兩個都沒有的組不輸出。
     * 有 timeGrain 照期別排，沒有就照第一個數值由大到小，null 排最後。
     */
    private static ChartData measured(ChartSpec spec, List<Map<String, Object>> rows, String x,
                                      List<String> measures, String agg, String grain) {
        Map<Object, double[][]> acc = new LinkedHashMap<>(); // 每個數值一組 [加總, 筆數]
        for (Map<String, Object> row : rows) {
            Object key = grain != null ? TimeBucket.of(row.get(x), grain) : row.get(x);
            if (key == null) continue;
            for (int i = 0; i < measures.size(); i++) {
                Object v = row.get(measures.get(i));
                if (agg.equals("count") ? v == null : !(v instanceof Number)) continue;
                double[] a = acc.computeIfAbsent(key, k -> new double[measures.size()][2])[i];
                if (v instanceof Number n) a[0] += n.doubleValue();
                a[1]++;
            }
        }
        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>();
            r.add(key);
            for (double[] one : a) r.add(one[1] == 0 ? null : result(agg, one));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> String.valueOf(r.get(0))));
        } else {
            out.sort(Comparator.comparing((List<Object> r) -> (Double) r.get(1),
                Comparator.nullsLast(Comparator.<Double>reverseOrder())));
        }
        List<String> dims = new ArrayList<>();
        dims.add(x);
        dims.addAll(measures);
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }
```

- [ ] **Step 5: 計數只算非空值**

`grouped` 裡：

```java
            if (!agg.equals("count") && !(v instanceof Number)) continue;
```

換成：

```java
            // B2 spec 5.4：count 只算 value 不是空值的列；沒有 value 時算列數
            if (agg.equals("count") ? value != null && v == null : !(v instanceof Number)) continue;
```

`radar` 裡：

```java
                if (v instanceof Number || agg.equals("count")) a[i][1]++;
```

換成：

```java
                if (v instanceof Number || agg.equals("count") && v != null) a[i][1]++;
```

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='UniversalTransformerTest,DashboardServiceTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋9，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java"
git add $F && git commit -m "feat(backend): 長條圖和折線圖可以放兩個數值，計數只算非空值" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 3: `ConfigAssembler`：對照組合、排序、mapping、標題

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`
- Test: `backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`

**Interfaces:**
- Consumes：Task 2 的兩個數值 `Mapping`（`problem` 通過）。
- Produces：
  - `boolean ConfigAssembler.Combo.compare()`（package-private）：三個欄位裡剛好兩個 `measure`
  - `static List<Combo> ConfigAssembler.ranked(List<FieldEval> c, Set<Set<String>> pairs)`；原本的 `ranked(c)` 等於 `ranked(c, Set.of())`
  - `static List<Combo> ConfigAssembler.combos(List<FieldEval> c, int k, Set<Set<String>> pairs)`；原本的 `combos(c, k)` 等於 `combos(c, k, Set.of())`
  - 對照組合的 mapping：`new Mapping(x, null, null, null, List.of(a, b), null, "sum", grain)`；標題「{a} 與 {b} 依 {x}」

- [ ] **Step 1: 寫會失敗的測試**

`ConfigAssemblerTest.java` 在 class 最後加：

```java
    static final Set<Set<String>> SALES_VS_REFUNDS = Set.of(Set.of("sales", "refund"));

    /** 對照來源的候選欄位：sales、refund 是對照組；qty 跟 refund 同一個來源，不是。 */
    static List<FieldEval> compareCandidates() {
        FieldEval time = new FieldEval(new FieldSchema("date", DATE, 9, List.of(), false, "2026-01-05", "2026-09-20"), 3, "time");
        return List.of(time, eval("sales", NUMBER, 9, 2.75, "measure"), eval("region", STRING, 3, 2, "dimension"),
            eval("refund", NUMBER, 5, 1, "measure"), eval("qty", NUMBER, 5, 1, "measure"));
    }

    @Test
    void comparePairsGetTimeAndCategoryCombosRankedFirst() {
        List<Combo> ranked = ConfigAssembler.ranked(compareCandidates(), SALES_VS_REFUNDS);

        assertThat(ranked.subList(0, 2)).extracting(ConfigAssemblerTest::names, Combo::chartTypes).containsExactly(
            tuple(List.of("date", "sales", "refund"), List.of("line", "bar")),
            tuple(List.of("region", "sales", "refund"), List.of("bar")));
        // 不是對照組的兩個數值（sales 和 qty、refund 和 qty）不配
        assertThat(ranked).filteredOn(Combo::compare).hasSize(2);
        // 沒有對照組就跟以前一樣
        assertThat(ConfigAssembler.ranked(compareCandidates(), Set.of())).noneMatch(Combo::compare);
    }

    @Test
    void focusCombosStillComeBeforeCompareCombos() {
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.set(2, focus(cands.get(2))); // region 是焦點欄位
        List<Combo> ranked = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);

        // 焦點層級裡對照組合排第一；焦點組合全部排完，才輪到沒有焦點的對照組合
        assertThat(names(ranked.get(0))).containsExactly("region", "sales", "refund");
        int focused = (int) ranked.stream().filter(Combo::hasFocus).count();
        assertThat(ranked.subList(0, focused)).allMatch(Combo::hasFocus);
        assertThat(names(ranked.get(focused))).containsExactly("date", "sales", "refund");
    }

    @Test
    void compareCombosBecomeTwoMeasureChartsThatPassValidation() {
        List<Combo> combos = ConfigAssembler.combos(compareCandidates(), 2, SALES_VS_REFUNDS);
        Map<String, Answer> round2 = Map.of("combo_0", choice("line", 0.9), "combo_1", choice("bar", 0.9));

        List<ChartSpec> layout = ConfigAssembler.assemble(combos, round2, 2, List.of("date", "sales", "region", "refund"));

        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month"));
        assertThat(layout.get(0).title()).isEqualTo("sales 與 refund 依 date");
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("region", null, null, null, List.of("sales", "refund"), null, "sum", null));
        assertThat(layout.get(1).title()).isEqualTo("sales 與 refund 依 region");
        DashboardConfig c = new DashboardConfig(null, "對照", null,
            layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList());
        assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected：編譯失敗，找不到 `ranked(List, Set)`、`Combo.compare()`。

- [ ] **Step 3: `Combo.compare()`**

`Combo` record 裡 `hasFocus()` 後面加：

```java
        /**
         * B2 spec 5.5：對照組合是一個時間或分類欄位配兩個數值。一般的三欄組合遇到兩個數值時
         * tripleTypes 不給圖型，所以只有對照組合會有三個欄位、兩個數值。
         */
        boolean compare() {
            return fields.size() == 3 && byRole("measure").size() == 2;
        }
```

- [ ] **Step 4: 產生對照組合、改排序**

`ranked` 的 javadoc 和開頭：

```java
    /** 全部合法組合，照 spec 6.3 排序：含焦點欄位的排前面，再比平均重要性，同分照產生順序（穩定排序）。 */
    public static List<Combo> ranked(List<FieldEval> c) {
```

換成：

```java
    public static List<Combo> ranked(List<FieldEval> c) {
        return ranked(c, Set.of());
    }

    /**
     * 全部合法組合，照 spec 6.3 排序：含焦點欄位的排前面，再比平均重要性，同分照產生順序（穩定排序）。
     * pairs 是對照組（B2 spec 4.2），只有對照來源才有：每一組配上時間欄位出折線、長條，配上分類欄位出長條，
     * 排在焦點之後、其他組合之前。
     */
    public static List<Combo> ranked(List<FieldEval> c, Set<Set<String>> pairs) {
```

同一個方法裡，三欄組合的迴圈結束後、`all.sort(...)` 前面加：

```java
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                FieldEval a = c.get(i);
                FieldEval b = c.get(j);
                if (!a.role().equals("measure") || !b.role().equals("measure")) continue;
                if (!pairs.contains(Set.of(a.name(), b.name()))) continue;
                for (FieldEval d : c) {
                    if (d.role().equals("time")) add(all, List.of(d, a, b), List.of("line", "bar"));
                    if (d.role().equals("dimension")) add(all, List.of(d, a, b), List.of("bar"));
                }
            }
        }
```

排序：

```java
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
```

換成：

```java
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparing(Combo::compare).reversed())
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
```

`combos` 換成：

```java
    /** 第一次分析要問的組合：排序後的前 2K 組。 */
    public static List<Combo> combos(List<FieldEval> c, int k) {
        return combos(c, k, Set.of());
    }

    public static List<Combo> combos(List<FieldEval> c, int k, Set<Set<String>> pairs) {
        List<Combo> all = ranked(c, pairs);
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }
```

- [ ] **Step 5: mapping 和標題**

`mapping` 的 switch 裡：

```java
            case "line", "bar" -> time != null
                ? new Mapping(time, null, null, m.get(0).name(), null, null, "sum", grain)
                : new Mapping(d.get(0).name(), null, null, m.get(0).name(), null, null, "sum", null);
```

換成：

```java
            case "line", "bar" -> {
                String x = time != null ? time : d.get(0).name();
                String g = time != null ? grain : null;
                // B2 spec 5.5：對照組合的兩個數值放 measures
                yield m.size() == 2
                    ? new Mapping(x, null, null, null, List.of(m.get(0).name(), m.get(1).name()), null, "sum", g)
                    : new Mapping(x, null, null, m.get(0).name(), null, null, "sum", g);
            }
```

`title` 裡 `if (type.equals("radar")) ...` 下一行加：

```java
        if (m.measures() != null) return String.join(" 與 ", m.measures()) + " 依 " + m.x();
```

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='ConfigAssemblerTest,AnalyzeServiceTest'`
Expected：全部通過（既有的排序測試沒有對照組，結果不變）。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋12，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java"
git add $F && git commit -m "feat(backend): 對照來源多出兩個數值的組合，排在焦點之後" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 4: 分析：接上對照組、來源欄不畫、題目多一句

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`（`analyze`）
- Test: `backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`
- Test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `MergeEngine.Comparison`、`MergeEngine.comparison(Datasource)`；Task 3 的 `ranked(c, pairs)`、`combos(c, k, pairs)`、`Combo.compare()`。
- Produces：`AnalyzeService.analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount, List<String> intents, List<ChartSpec> current, Set<String> picked, MergeEngine.Comparison compare)`；原本七個參數的版本等於 `compare = null`。

- [ ] **Step 1: 寫會失敗的測試**

`AnalyzeServiceTest.java` 的 import 加上 `import jev.dashboard.datasource.MergeEngine;`。在 class 最後加：

```java
    @Test
    void aCompareSourceAsksTwoMeasureCombosFirstAndNeverDrawsTheSourceColumn() throws Exception {
        // 欄位順序對齊 round1.json 的角色：0 時間、1 數值、2 分類、3 分類（來源欄）、4 數值、5 忽略
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 9, List.of("2026-01-05"), false, "2026-01-05", "2026-09-20"),
            new FieldSchema("銷售·amount", FieldType.NUMBER, 9, List.of("1000"), false, null, null),
            new FieldSchema("region", FieldType.STRING, 2, List.of("北區"), false, null, null),
            new FieldSchema("來源", FieldType.STRING, 2, List.of("銷售", "退貨"), false, null, null),
            new FieldSchema("退貨·amount", FieldType.NUMBER, 3, List.of("50"), false, null, null),
            new FieldSchema("memo", FieldType.STRING, 9, List.of(), false, null, null));
        MergeEngine.Comparison compare = new MergeEngine.Comparison(Set.of(Set.of("銷售·amount", "退貨·amount")), "來源");

        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            DashboardConfig c = service(server).analyze("對照 總覽", UUID.randomUUID(), schema, 12,
                List.of(), null, Set.of(), compare);

            // round2.json：combo_0（對照組合配時間）答 line；combo_4（銷售·amount 依 region）答 bar
            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("line", "bar", "table");
            assertThat(c.layout().get(0).mapping()).isEqualTo(
                new Mapping("date", null, null, null, List.of("銷售·amount", "退貨·amount"), null, "sum", "month"));
            assertThat(c.layout().get(0).title()).isEqualTo("銷售·amount 與 退貨·amount 依 date");
            assertThat(c.layout().get(2).mapping().columns()).containsExactly("date", "銷售·amount", "region", "退貨·amount");
            assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
            assertThat(server.bodies.get(1)).contains("兩個數值來自不同來源，要放在同一張圖上對照。")
                .doesNotContain("來源（分類）");
        }
    }
```

`JevControllerTest.java` 在 `analyzingAMergedSourceKeepsInheritedSensitiveSamplesAwayFromJev` 後面加（沿用檔案裡現有的 import）：

```java
    @Test
    void analyzingACompareSourceAsksTwoMeasureCombosFirst() throws Exception {
        String sales = TestData.uploadCsv(mvc, "u1", "銷售",
            "order_date,region,product,amount\n2026-01-05,北區,A,1000\n2026-02-10,南區,B,2000\n");
        String refunds = TestData.uploadCsv(mvc, "u1", "退貨", "return_date,region,product,amount\n2026-02-12,北區,A,50\n");
        // 欄位順序對齊 round1.json 的角色：0 時間、1 數值、2 分類、3 分類、4 數值；來源欄排最後
        String merge = """
            {"op":"union","compare":true,"inputs":["%1$s","%2$s"],"columns":[
              {"name":"date","from":{"%1$s":"order_date","%2$s":"return_date"}},
              {"name":"銷售·amount","from":{"%1$s":"amount"}},
              {"name":"region","from":{"%1$s":"region","%2$s":"region"}},
              {"name":"product","from":{"%1$s":"product","%2$s":"product"}},
              {"name":"退貨·amount","from":{"%2$s":"amount"}}],"sourceColumn":"來源"}""".formatted(sales, refunds);
        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售／退貨 對照\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String compared = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + compared + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[0].type").value("line"))
            .andExpect(jsonPath("$.layout[0].mapping.measures[0]").value("銷售·amount"))
            .andExpect(jsonPath("$.layout[0].mapping.measures[1]").value("退貨·amount"));

        assertThat(JEV.bodies.get(1)).contains("兩個數值來自不同來源，要放在同一張圖上對照。");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest'`
Expected：`AnalyzeServiceTest` 編譯失敗（沒有八個參數的 `analyze`）。

- [ ] **Step 3: `AnalyzeService`**

import 加上 `import jev.dashboard.datasource.MergeEngine;`。

原本七個參數的 `analyze`（`@param picked` 那一個）的方法本體整個換成一行委派，另外加八個參數的版本，本體是原本的程式再加兩處修改：

```java
    /** @param picked 使用者勾選想看的欄位，跟 JEV 判定「直接相關」的欄位一樣當焦點欄位 */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked) {
        return analyze(name, datasourceId, schema, rowCount, intents, current, picked, null);
    }

    /** @param compare 對照來源的對照組和來源欄（B2 spec 5.5）；其他來源是 null */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked,
                                   MergeEngine.Comparison compare) {
        // ……原本七個參數版本的方法本體，照原樣搬過來，只改下面兩處……
    }
```

搬過來的本體裡，第一處：

```java
        List<FieldEval> evals = ConfigAssembler.evaluate(schema, a1).stream()
            .map(e -> picked.contains(e.name()) ? new FieldEval(e.field(), e.importance(), e.role(), true) : e).toList();
```

換成：

```java
        List<FieldEval> evals = ConfigAssembler.evaluate(schema, a1).stream()
            .map(e -> picked.contains(e.name()) ? new FieldEval(e.field(), e.importance(), e.role(), true) : e)
            // B2 spec 5.5：對照來源的來源欄不拿來畫圖，也不進明細表
            .map(e -> compare != null && e.name().equals(compare.sourceColumn())
                ? new FieldEval(e.field(), e.importance(), "ignore", e.focus()) : e)
            .toList();
        Set<Set<String>> pairs = compare == null ? Set.of() : compare.pairs();
```

第二處：

```java
        List<Combo> combos = current == null
            ? ConfigAssembler.combos(candidates, k)
            : ConfigAssembler.adjustCombos(ConfigAssembler.ranked(candidates), kept);
```

換成：

```java
        List<Combo> combos = current == null
            ? ConfigAssembler.combos(candidates, k, pairs)
            : ConfigAssembler.adjustCombos(ConfigAssembler.ranked(candidates, pairs), kept);
```

`describe` 換成：

```java
    static String describe(Combo c, String intent) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        String text = "用這組欄位畫一張圖：" + fields + "。"
            + (c.compare() ? "兩個數值來自不同來源，要放在同一張圖上對照。" : "")
            + "選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
        return intent == null ? text : text + "使用者想看：「" + intent + "」。";
    }
```

- [ ] **Step 4: `JevController.analyze` 傳對照組**

import 加上 `import jev.dashboard.datasource.MergeEngine;`（`DatasourceService` 已經 import 了同一個 package，照現有寫法加一行）。`analyze` 最後的呼叫：

```java
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size(), intents, body.current(),
            body.fields() == null ? Set.of() : body.fields().stream().filter(Objects::nonNull).collect(Collectors.toSet()));
```

換成：

```java
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size(), intents, body.current(),
            body.fields() == null ? Set.of() : body.fields().stream().filter(Objects::nonNull).collect(Collectors.toSet()),
            MergeEngine.comparison(loaded.datasource()));
```

- [ ] **Step 5: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest'`
Expected：全部通過，既有測試的題目沒變。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋14，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
F="backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java"
git add $F && git commit -m "feat(backend): 分析對照來源時問兩個數值的組合，來源欄不拿來畫圖" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 5: 兩個數值的圖：顯示名稱和異常檢查

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java`（`relabel`）
- Modify: `backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java`（`summarize`，另外加 `add`）
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`
- Test: `backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java`

**Interfaces:**
- Consumes：Task 2 的 `[x, a, b]` 資料；Task 3 的 `ConfigAssembler.title`（認得 `measures`）。

- [ ] **Step 1: 寫會失敗的測試**

`DashboardDataServiceTest.java` 在 `displayUsesFieldLabelsForDimensionsAndAutoTitles` 後面加：

```java
    @Test
    void displayRelabelsBothMeasuresOfATwoMeasureChart() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 2, List.of(), false, null, null, "日期"),
            new FieldSchema("sales", FieldType.NUMBER, 1, List.of(), false, null, null, "銷售額"),
            new FieldSchema("refund", FieldType.NUMBER, 1, List.of(), false, null, null, "退貨額"));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null);
        List<Map<String, Object>> rows = List.of(
            Map.of("date", "2026-07-03", "sales", 100.0), Map.of("date", "2026-07-09", "refund", 5.0));
        DatasourceService datasources = sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>());
        Mapping m = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        ChartSpec line = new ChartSpec("c1", "line", "sales 與 refund 依 date", POS, m, Map.of(), id);

        ChartData shown = new DashboardDataService(datasources).display("u1", config(line)).get(0);

        assertThat(shown.dimensions()).isEqualTo(List.of("日期", "銷售額", "退貨額"));
        assertThat(shown.title()).isEqualTo("銷售額 與 退貨額 依 日期");
        assertThat(shown.rows()).isEqualTo(List.of(List.of("2026-07", 100.0, 5.0)));
    }
```

`HighlightSummarizerTest.java` 在 `comparesEachSeriesAndTreatsMissingAsZero` 後面加：

```java
    @Test
    void twoMeasureChartsCompareEachMeasureAsItsOwnSeries() {
        Mapping m = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        ChartData d = new ChartData("c1", "line", List.of("date", "sales", "refund"),
            List.of(r("2024-04", 90.0, null), r("2024-05", 100.0, 10.0), r("2024-06", 150.0, 5.0)), null, null);

        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", m), d, TODAY);

        assertThat(s).containsEntry("metric", "sales、refund")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "sales", "previous", 100.0, "current", 150.0, "changePercent", 50.0),
            Map.of("name", "refund", "previous", 10.0, "current", 5.0, "changePercent", -50.0)));
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,HighlightSummarizerTest'`
Expected：兩個新測試失敗（標題還是「sales 與 refund 依 日期」；異常檢查把 sales 的值當成系列名稱）。

- [ ] **Step 3: 顯示名稱套到 measures**

`DashboardDataService.relabel` 換成：

```java
    private static Mapping relabel(Mapping m, Map<String, String> labels) {
        return new Mapping(labels.getOrDefault(m.x(), m.x()), labels.getOrDefault(m.y(), m.y()),
            labels.getOrDefault(m.series(), m.series()), labels.getOrDefault(m.value(), m.value()),
            m.measures() == null ? null : m.measures().stream().map(n -> labels.getOrDefault(n, n)).toList(),
            m.columns(), m.aggregate(), m.timeGrain());
    }
```

- [ ] **Step 4: 異常檢查的兩個數值**

`HighlightSummarizer.summarize` 裡：

```java
        boolean hasSeries = data.dimensions().size() == 3;

        Map<String, double[]> bySeries = new LinkedHashMap<>(); // [前一期, 最後一期]
        for (List<Object> row : data.rows()) {
            String period = String.valueOf(row.get(0));
            int slot = period.equals(current) ? 1 : period.equals(previous) ? 0 : -1;
            if (slot < 0) continue;
            String name = hasSeries ? String.valueOf(row.get(1)) : "全部";
            Object v = row.get(row.size() - 1);
            bySeries.computeIfAbsent(name, k -> new double[2])[slot] += v instanceof Number n ? n.doubleValue() : 0;
        }
```

換成：

```java
        List<String> measures = spec.mapping().measures();
        boolean hasSeries = measures == null && data.dimensions().size() == 3;

        Map<String, double[]> bySeries = new LinkedHashMap<>(); // [前一期, 最後一期]
        for (List<Object> row : data.rows()) {
            String period = String.valueOf(row.get(0));
            int slot = period.equals(current) ? 1 : period.equals(previous) ? 0 : -1;
            if (slot < 0) continue;
            if (measures != null) {
                // B2 spec 5.7：兩個數值的圖，每個數值當一條系列，名稱是原始欄位名稱
                for (int i = 1; i < row.size(); i++) add(bySeries, data.dimensions().get(i), slot, row.get(i));
            } else {
                add(bySeries, hasSeries ? String.valueOf(row.get(1)) : "全部", slot, row.get(row.size() - 1));
            }
        }
```

`metric` 那一行：

```java
        out.put("metric", spec.mapping().value() == null ? "count" : spec.mapping().value());
```

換成：

```java
        out.put("metric", measures != null ? String.join("、", measures)
            : spec.mapping().value() == null ? "count" : spec.mapping().value());
```

`keepBiggestChanges` 前面加：

```java
    /** 空值當 0。 */
    private static void add(Map<String, double[]> bySeries, String name, int slot, Object v) {
        bySeries.computeIfAbsent(name, k -> new double[2])[slot] += v instanceof Number n ? n.doubleValue() : 0;
    }
```

- [ ] **Step 5: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,HighlightSummarizerTest,HighlightServiceTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋16，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
F="backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java"
git add $F && git commit -m "feat(backend): 兩個數值的圖套上顯示名稱，異常檢查分開比兩個數值" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 6: 前端圖表：兩個數值、雙 Y 軸

**Files:**
- Create: `frontend/src/modules/dashboard/charts/measures.js`
- Modify: `frontend/src/modules/dashboard/charts/bar.js`、`frontend/src/modules/dashboard/charts/line.js`
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`（`tunedOption`）
- Test: `frontend/src/modules/dashboard/charts/charts.test.js`
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`

**Interfaces:**
- Consumes：後端 `ChartData` 的 `dimensions` 是 `[x, a, b]`（已經換成顯示名稱），`rows` 的數值可能是 `null`。
- Produces：`twoMeasures(data, type)`（`charts/measures.js`），`type` 是 `'bar'` 或 `'line'`。

- [ ] **Step 1: 寫會失敗的測試**

`charts/charts.test.js` 最後（最外層 `describe` 的 `})` 後面）加：

```js
describe('toOption with two measures (B2 spec 6.2)', () => {
  const compare = (rows, type = 'line') => ({ chartId: 'c8', type, dimensions: ['date', '銷售·amount', '退貨·amount'], rows })

  it('line and bar draw one series per measure with a legend; bars sit side by side on one shared axis', () => {
    for (const type of ['line', 'bar']) {
      const o = toOption[type](compare([['2026-07', 1000, 200], ['2026-08', 1200, 300]], type))
      expect(o.series.map((s) => [s.type, s.name, s.encode, s.stack, s.yAxisIndex])).toEqual([
        [type, '銷售·amount', { x: 0, y: 1 }, undefined, undefined],
        [type, '退貨·amount', { x: 0, y: 2 }, undefined, undefined],
      ])
      expect(o.legend).toEqual({ type: 'scroll', bottom: 0 })
      expect(o.yAxis).toEqual({ type: 'value' })
    }
  })

  it('more than 10× apart: the second measure moves to a right-hand Y axis and both axes are named', () => {
    const o = toOption.line(compare([['2026-07', 1100, 100]]))
    expect(o.yAxis).toEqual([{ type: 'value', name: '銷售·amount' }, { type: 'value', name: '退貨·amount' }])
    expect(o.series.map((s) => s.yAxisIndex)).toEqual([undefined, 1])
  })

  it('exactly 10× apart, one side all zero, or one side empty keeps one shared axis', () => {
    expect(toOption.bar(compare([['北區', 1000, 100]], 'bar')).yAxis).toEqual({ type: 'value' })
    expect(toOption.bar(compare([['北區', 1000, 0]], 'bar')).yAxis).toEqual({ type: 'value' })
    expect(toOption.line(compare([['2026-07', 1000, null]])).yAxis).toEqual({ type: 'value' })
  })

  it('negative values count by their size, and a missing side stays null so the line breaks there', () => {
    const rows = [['2026-01', -1100, null], ['2026-07', 900, 50]]
    const o = toOption.line(compare(rows))
    expect(o.yAxis).toHaveLength(2)
    expect(o.dataset.source).toEqual(rows)
  })
})
```

`ChartCard.test.js` 在 `value axes label numbers in compact zh-TW form; category axes are left as they were` 後面加：

```js
  it('two Y axes (two measures far apart) both label numbers in compact zh-TW form', () => {
    const data = { chartId: 'c1', type: 'line', dimensions: ['date', '銷售', '退貨'], rows: [['2026-07', 1200000, 30000]] }
    const option = chartOf(mountCard({ spec: { ...barSpec, type: 'line' }, data, highlight: null })).props('option')
    expect(option.yAxis).toHaveLength(2)
    expect(option.yAxis.map((a) => a.axisLabel.formatter(1200000))).toEqual(['120萬', '120萬'])
  })
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/charts/charts.test.js src/modules/dashboard/ChartCard.test.js`
Expected：新加的 5 個測試失敗（目前只畫一條系列，`yAxis` 不是陣列）。

- [ ] **Step 3: `charts/measures.js`**

```js
// B2 spec 6.2：兩個數值的長條圖、折線圖。兩邊最大絕對值都大於 0、而且差超過 10 倍時，
// 第二個數值改用右邊的 Y 軸，兩條軸都標數值名稱。null 照原樣留著：折線斷開、長條不畫
const DUAL_AXIS_RATIO = 10

export function twoMeasures(data, type) {
  const [, a, b] = data.dimensions
  const peak = (i) => Math.max(0, ...data.rows.map((r) => Math.abs(r[i] ?? 0)))
  const pa = peak(1)
  const pb = peak(2)
  const dual = pa > 0 && pb > 0 && Math.max(pa, pb) / Math.min(pa, pb) > DUAL_AXIS_RATIO
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0 },
    xAxis: { type: 'category' },
    yAxis: dual ? [{ type: 'value', name: a }, { type: 'value', name: b }] : { type: 'value' },
    series: [
      { type, name: a, encode: { x: 0, y: 1 } },
      { type, name: b, encode: { x: 0, y: 2 }, ...(dual && { yAxisIndex: 1 }) },
    ],
  }
}
```

- [ ] **Step 4: `bar.js`、`line.js` 接上**

`charts/bar.js` 換成：

```js
import { twoMeasures } from './measures.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'bar')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', encode: { x: 0, y: 1 } }],
  }
}
```

`charts/line.js` 換成：

```js
import { twoMeasures } from './measures.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'line')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}
```

- [ ] **Step 5: `ChartCard` 的數字縮寫處理兩條 Y 軸**

`ChartCard.vue` 的 `tunedOption` 裡：

```js
    ...(base.yAxis && { yAxis: compactValueAxis(base.yAxis) }),
```

換成：

```js
    // B2 spec 6.2：兩個數值差距大時 yAxis 是兩條軸的陣列
    ...(base.yAxis && { yAxis: Array.isArray(base.yAxis) ? base.yAxis.map(compactValueAxis) : compactValueAxis(base.yAxis) }),
```

- [ ] **Step 6: 跑測試確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/charts/charts.test.js src/modules/dashboard/ChartCard.test.js`
Expected：全部通過，既有快照沒有變動（不能出現 `Snapshots … updated` 或 `obsolete`）。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：前端基準數量＋5，全部通過。

- [ ] **Step 7: Commit**

```bash
F="frontend/src/modules/dashboard/charts/measures.js frontend/src/modules/dashboard/charts/bar.js frontend/src/modules/dashboard/charts/line.js frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/charts/charts.test.js frontend/src/modules/dashboard/ChartCard.test.js"
git add $F && git commit -m "feat(frontend): 長條圖和折線圖畫兩個數值，差距大時用兩條 Y 軸" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 7: 合併表單的「對照」、第 6 個來源停用、清單說明列

**Files:**
- Modify: `frontend/src/modules/dashboard/MergeForm.vue`
- Modify: `frontend/src/modules/dashboard/DatasourcePanel.vue`（`mergeSummary`）
- Test: `frontend/src/modules/dashboard/MergeForm.test.js`
- Test: `frontend/src/modules/dashboard/DatasourcePanel.test.js`

**Interfaces:**
- Consumes：Task 1 的 `compare: true`（`POST /api/datasources/merged/preview`、`POST /api/datasources/merged`、`GET /api/datasources` 的 `merge`）。

- [ ] **Step 1: 寫會失敗的測試**

`MergeForm.test.js` 最後加：

```js
describe('MergeForm（對照）', () => {
  const sales = {
    id: 'sa',
    name: '銷售',
    kind: 'upload',
    schema: [{ name: 'order_date', type: 'date' }, { name: 'region', type: 'string' }, { name: 'amount', type: 'number' }],
  }
  const refunds = {
    id: 'rf',
    name: '退貨',
    kind: 'upload',
    schema: [
      { name: 'order_date', type: 'date' },
      { name: 'region', type: 'string' },
      { name: 'amount', type: 'number' },
      { name: 'qty', type: 'number' },
    ],
  }
  const mountCompare = (sources = [sales, refunds]) => mount(MergeForm, { props: { sources }, global: { plugins: [vuetify] } })
  const way = async (w, value) => {
    await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', value)
    await flushPromises()
  }

  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('對照 matches dates and text by name, splits every number field per source as 「來源名·欄位」, and is named 銷售／退貨 對照', async () => {
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['order_date', 'region', '銷售·amount', '退貨·amount', '退貨·qty'])
    expect(select(w, '第 1 欄在退貨的欄位').props('modelValue')).toBe('order_date')
    expect(select(w, '第 3 欄在銷售的欄位').props('modelValue')).toBe('amount')
    expect(select(w, '第 3 欄在退貨的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 4 欄在銷售的欄位').props('modelValue')).toBeNull()
    expect(field(w, '名稱').props('modelValue')).toBe('銷售／退貨 對照')
  })

  it('a number column mapped to two sources is marked red; 預覽 sends op union with compare: true', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 1, warnings: [] })
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])

    await pick(w, '第 3 欄在退貨的欄位', 'amount')
    expect(columnNames(w)[2].props('errorMessages')).toBe('對照時數字欄位只能對應一個來源')
    await pick(w, '第 3 欄在退貨的欄位', null)
    expect(columnNames(w)[2].props('errorMessages')).toEqual([])

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    const { name, merge } = api.previewMerged.mock.calls[0][0]
    expect(name).toBe('銷售／退貨 對照')
    expect(merge).toMatchObject({ op: 'union', compare: true, inputs: ['sa', 'rf'], sourceColumn: '來源' })
    expect(merge.columns[2]).toEqual({ name: '銷售·amount', from: { sa: 'amount' } })
  })

  it('switching from 對照 to 疊加 rebuilds the columns by name, and 疊加 sends no compare flag', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 1, warnings: [] })
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])
    await way(w, 'union')

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['order_date', 'region', 'amount', 'qty'])
    expect(field(w, '名稱').props('modelValue')).toBe('銷售＋退貨')
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged.mock.calls[0][0].merge).not.toHaveProperty('compare')
  })

  it('the 來源 menu stops at 5: once 5 are picked the others are disabled', async () => {
    const six = Array.from({ length: 6 }, (_, i) => ({ id: `d${i}`, name: `來源${i}`, kind: 'upload', schema: [] }))
    const w = mountCompare(six)
    await pick(w, '來源', ['d0', 'd1', 'd2', 'd3'])
    expect(select(w, '來源').props('itemProps')(six[5])).toEqual({ disabled: false })
    await pick(w, '來源', ['d0', 'd1', 'd2', 'd3', 'd4'])
    const itemProps = select(w, '來源').props('itemProps')
    expect(itemProps(six[5])).toEqual({ disabled: true })
    expect(itemProps(six[0])).toEqual({ disabled: false })
  })
})
```

`DatasourcePanel.test.js` 在 `a merged source shows the 合併 badge and icon, …` 那個測試後面加：

```js
  it('a 對照 source says 對照 instead of 疊加 in its summary', async () => {
    const compared = { ...merged, id: 'cp', name: '銷售／退貨 對照', merge: { ...merged.merge, compare: true } }
    api.listDatasources.mockResolvedValue([ds1, ds2, compared])
    const wrapper = mountPanel('cp')
    await flushPromises()

    expect(wrapper.find('.ds-meta').text()).toBe('對照：sales.csv、（已不存在） · 3 個欄位 · 1 個敏感')
  })
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js src/modules/dashboard/DatasourcePanel.test.js`
Expected：新加的 5 個測試失敗。

- [ ] **Step 3: `MergeForm` 的對應表、驗證和第 6 個來源**

`MergeForm.vue` 的 `<script setup>` 裡，原本的：

```js
// ponytail: 選的來源一變就重排對應表，手動改過的對應會不見；要保留再比對新舊來源
watch(inputs, (ids) => {
  const names = [...new Set(ids.flatMap((id) => fieldsOf(id).map((f) => f.name)))]
  columns.value = names.map((n) => ({
    key: nextKey++,
    name: n,
    from: Object.fromEntries(ids.map((id) => [id, typeOf(id, n) ? n : null])),
  }))
})

function typeProblem(c) {
  const used = inputs.value.filter((id) => c.from[id]).map((id) => ({ id, type: typeOf(id, c.from[id]) }))
  if (new Set(used.map((u) => u.type)).size < 2) return ''
  return '型別不一致：' + used.map((u) => `${nameOf(u.id)}是${FIELD_TYPES[u.type]?.text ?? u.type}`).join('、')
}
```

換成：

```js
// 同名的欄位自動對上；only 決定哪些欄位參加（對照時數字欄位另外依來源拆開）
function matchByName(ids, only = () => true) {
  const has = (id, n) => fieldsOf(id).some((f) => f.name === n && only(f))
  const names = [...new Set(ids.flatMap((id) => fieldsOf(id).filter(only).map((f) => f.name)))]
  return names.map((n) => ({ key: nextKey++, name: n, from: Object.fromEntries(ids.map((id) => [id, has(id, n) ? n : null])) }))
}

// B2 spec 6.1：對照時，每個來源的每個數字欄位各一列，名稱是「來源名·欄位」，只對應那一個來源
function splitNumbers(ids) {
  return ids.flatMap((id) => fieldsOf(id).filter((f) => f.type === 'number').map((f) => ({
    key: nextKey++,
    name: `${nameOf(id)}·${f.name}`,
    from: Object.fromEntries(ids.map((other) => [other, other === id ? f.name : null])),
  })))
}

// ponytail: 選的來源或方式一變就重排對應表，手動改過的對應會不見；要保留再比對新舊來源。切到查表時不動
watch([inputs, op], ([ids, mode]) => {
  if (mode === 'union') columns.value = matchByName(ids)
  if (mode === 'compare') columns.value = [...matchByName(ids, (f) => f.type !== 'number'), ...splitNumbers(ids)]
})

function columnProblem(c) {
  const used = inputs.value.filter((id) => c.from[id]).map((id) => ({ id, type: typeOf(id, c.from[id]) }))
  if (new Set(used.map((u) => u.type)).size >= 2) {
    return '型別不一致：' + used.map((u) => `${nameOf(u.id)}是${FIELD_TYPES[u.type]?.text ?? u.type}`).join('、')
  }
  if (op.value === 'compare' && used.length > 1 && used[0].type === 'number') return '對照時數字欄位只能對應一個來源'
  return ''
}

const MAX_INPUTS = 5 // B1 spec 4.2：疊加和對照最多 5 個來源
// 選滿 5 個之後，其他來源停用，不用等按預覽才由後端擋
const inputProps = (ds) => ({ disabled: inputs.value.length >= MAX_INPUTS && !inputs.value.includes(ds.id) })
```

- [ ] **Step 4: 定義、預設名稱、能不能預覽**

`definition` 的開頭：

```js
const definition = computed(() => op.value === 'union'
  ? {
      op: 'union',
      inputs: inputs.value,
```

換成：

```js
const definition = computed(() => op.value !== 'lookup'
  ? {
      op: 'union',
      ...(op.value === 'compare' && { compare: true }),
      inputs: inputs.value,
```

`defaultName` 換成：

```js
const defaultName = computed(() => {
  if (op.value === 'union') return inputs.value.map(nameOf).join('＋')
  if (op.value === 'compare') return inputs.value.length ? `${inputs.value.map(nameOf).join('／')} 對照` : ''
  return main.value && lookup.value ? `${nameOf(main.value)}（補${nameOf(lookup.value)}）` : ''
})
```

`canPreview` 的：

```js
const canPreview = computed(() => !busy.value && (op.value === 'union'
```

換成：

```js
const canPreview = computed(() => !busy.value && (op.value !== 'lookup'
```

- [ ] **Step 5: 範本**

`<v-radio value="union" label="疊加（欄位一樣的資料接起來）" />` 下一行加：

```vue
      <v-radio value="compare" label="對照（數值依來源分開比較）" />
```

`<template v-if="op === 'union'">` 換成 `<template v-if="op !== 'lookup'">`。

「來源」那個 `v-select`（`label="來源"`、`multiple`）加一個屬性：

```vue
        :item-props="inputProps"
```

輸出欄位的 `v-text-field` 上 `:error-messages="typeProblem(c) || []"` 換成 `:error-messages="columnProblem(c) || []"`。

- [ ] **Step 6: 清單說明列**

`DatasourcePanel.vue` 的 `mergeSummary` 裡：

```js
    ? `疊加：${m.inputs.map(nameOf).join('、')}`
```

換成：

```js
    ? `${m.compare ? '對照' : '疊加'}：${m.inputs.map(nameOf).join('、')}`
```

- [ ] **Step 7: 跑測試確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js src/modules/dashboard/DatasourcePanel.test.js`
Expected：全部通過。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2 && npm run build 2>&1 | tail -3`
Expected：前端基準數量＋10，全部通過；build 成功。

- [ ] **Step 8: Commit**

```bash
F="frontend/src/modules/dashboard/MergeForm.vue frontend/src/modules/dashboard/DatasourcePanel.vue frontend/src/modules/dashboard/MergeForm.test.js frontend/src/modules/dashboard/DatasourcePanel.test.js"
git add $F && git commit -m "feat(frontend): 合併表單加上對照，來源選滿 5 個就停用其他選項" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 8: 文件：README、backlog、spec 第 10 節

**Files:**
- Modify: `README.md`（「合併來源」一節）
- Modify: `docs/backlog.md`
- Modify: `docs/superpowers/specs/2026-09-28-compare-datasource-design.md`（第 10 節標題）

- [ ] **Step 1: README**

`README.md` 的「合併來源」一節，`- 查表：…` 那一條後面加一條：

```markdown
- 對照：把兩個以上的來源放在同一張圖上比，例如每月銷售額對退貨額。日期和文字欄位照疊加的方式合併，數字欄位一律依來源分開，預設名稱是「來源名·欄位」（`銷售·amount`、`退貨·amount`），所以任何圖都不會把兩個來源的數字加在一起。分析對照來源時，JEV 會先考慮「一個時間或分類欄位配兩個數值」的折線圖和長條圖；兩個數值差超過 10 倍時改用左右兩條 Y 軸，某一期只有一邊有資料，另一邊就留白。
```

同一節最後一段（「合併定義建立之後不能編輯……完整規則見……」）的後面加一段：

```markdown
對照的規則見 [`docs/superpowers/specs/2026-09-28-compare-datasource-design.md`](docs/superpowers/specs/2026-09-28-compare-datasource-design.md)。計數（`count`）只算有值的列，對照來源裡一邊沒有數值的列不會被算進去。
```

- [ ] **Step 2: backlog**

`docs/backlog.md` 的「下一步」：

```markdown
- P1 · B2 對照：同一張圖上比兩個來源的數字，……（高階主管儀表板需求，2026-09-27）
```

（整條，從 `- P1 · B2 對照` 開始到行尾）換成：

```markdown
- ~~P1 · B2 對照：同一張圖上比兩個來源的數字，例如每月銷售額對退貨額。~~ 已完成：疊加多一個「對照」，長條圖和折線圖可以放兩個數值，見 spec `docs/superpowers/specs/2026-09-28-compare-datasource-design.md`。
```

B3 那一條結尾的「B2 做完再寫 spec，兩者都會改到轉換器。」換成「B2 已完成，可以開始寫 spec，從 B2 spec 第 11 節開始。」

「穩定度與效能」裡這一條：

```markdown
- P3 · 合併表單的「來源」選單寫著 2～5 個，但畫面沒有擋第 6 個，要等按預覽時才由後端回 400。（B1 Task 7 review）
```

換成：

```markdown
- ~~P3 · 合併表單的「來源」選單寫著 2～5 個，但畫面沒有擋第 6 個，要等按預覽時才由後端回 400。（B1 Task 7 review）~~ 已修正：選滿 5 個之後，其他來源停用（B2）。
```

- [ ] **Step 3: spec 第 10 節**

`docs/superpowers/specs/2026-09-28-compare-datasource-design.md` 的 `## 10. 預設決定（請確認）` 換成 `## 10. 預設決定（2026-09-28 已確認）`，下一行「以下是先訂的預設，審閱時可以改：」換成「下表是審閱時確認的預設：」。

- [ ] **Step 4: Commit**

```bash
F="README.md docs/backlog.md docs/superpowers/specs/2026-09-28-compare-datasource-design.md"
git add $F && git commit -m "docs: README 補上對照來源，backlog 劃掉 B2" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 9: 手動驗證（自己起前後端，用 Chrome DevTools MCP 操作）

**Files:**
- Create: `docs/superpowers/e2e/compare-datasource/sales.csv`、`returns.csv`
- 發現問題時修正對應的檔案，commit 訊息要寫清楚改了什麼

**Interfaces:**
- Consumes：Task 1–8 的全部成果、Chrome DevTools MCP（`new_page`、`take_snapshot`、`upload_file`、`fill`、`click`、`wait_for`、`evaluate_script`、`list_network_requests`、`get_network_request`、`take_screenshot`、`close_page`）

**誰來跑：** 整個 Task 9 交給同一個 subagent 從頭跑到尾，這個 subagent 要能用 Chrome DevTools MCP。Step 3、4 開的背景程序屬於它；controller 的 main session 不跑 `mvnw`，也不跑 `npm run dev`。controller 派工時要給它 session 的 scratchpad 路徑（Step 1 要把 CSV 複製過去）。

**瀏覽器規則，一定要遵守：** 用 `new_page` 開一個自己的分頁，記下它的 pageId，之後只操作這個分頁。別的分頁是使用者自己在用的：不要 `select_page`、`navigate_page`、`close_page` 它們，也不要讀取、截圖或點它們。不確定目前選的是哪一頁時，先 `select_page` 自己的 pageId。結束時只關自己的分頁。MCP 說連不到 `127.0.0.1:9222`，就代表自動化用的 Chrome 沒開：用 Bash 的 `run_in_background: true` 執行 `"/c/Program Files/Google/Chrome/Application/chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\\selenum\\AutomationProfile" about:blank`，再試一次。快照太大時改用 `evaluate_script` 讀畫面。圖表畫在 canvas 上，快照看不到圖例和座標軸：圖表內容用 `get_network_request` 讀 `/data` 的回應，外觀用 `take_screenshot` 看。

**等待和程序：** 每個等待迴圈最多等 180 秒，逾時就印出伺服器 log 的最後幾十行，停下來回報，不要一直重試。agent 不能 `taskkill`：埠被別的程序佔著，或自己開的程序停不掉時，用 `netstat -ano | grep …` 找出 PID，回報給使用者處理。

- [ ] **Step 1: 寫測試用的 CSV，複製到 scratchpad**

`docs/superpowers/e2e/compare-datasource/sales.csv`：

```csv
order_date,region,product,amount
2026-01-08,北區,A,12000
2026-01-21,南區,B,9000
2026-02-05,北區,B,13500
2026-02-18,南區,A,8800
2026-03-03,北區,A,14200
2026-03-25,南區,C,9600
2026-04-09,北區,C,12800
2026-04-22,南區,A,10100
2026-05-06,北區,B,15100
2026-05-19,南區,B,9900
2026-06-02,北區,A,14700
2026-06-27,南區,C,10400
2026-07-07,北區,B,16000
2026-07-24,南區,A,11200
2026-08-04,北區,C,15500
2026-08-21,南區,B,10800
2026-09-03,北區,A,16800
2026-09-16,南區,C,11500
```

`docs/superpowers/e2e/compare-datasource/returns.csv`：

```csv
return_date,region,product,amount,reason
2026-07-10,北區,B,600,瑕疵
2026-07-28,南區,A,350,尺寸不合
2026-08-06,北區,C,720,瑕疵
2026-08-25,南區,B,410,其他
2026-09-08,北區,A,800,瑕疵
2026-09-19,南區,C,380,尺寸不合
```

退貨每月約 1,000，銷售每月約 21,000～28,000，差距超過 10 倍，會出現雙 Y 軸；退貨只有 7～9 月，1～6 月那段應該是空白。

把兩個檔案複製到 controller 給的 scratchpad 資料夾，`upload_file` 用那裡的 Windows 路徑（Chrome MCP 只接受 workspace root 底下的檔案）。

- [ ] **Step 2: 全部測試再跑一次，確認 JEV key 和埠**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋16，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2 && npm run build 2>&1 | tail -3`
Expected：前端基準數量＋10，全部通過，build 成功。

Run: `grep -q '^TYPESAFE_API_KEY=.' backend/.env && echo "TYPESAFE_API_KEY 已設定" || echo "TYPESAFE_API_KEY 沒設定"`
Expected：`TYPESAFE_API_KEY 已設定`。這行只印結果，不印內容。沒設定就停下來向 controller 要 key，不要自己打開 `.env` 看。

Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
Expected：`8080 000` 和 `5180 000`。只要有一個不是 `000`，就代表有程式佔著這個埠（可能是使用者自己開的後端）。不要用它、不要關它：跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 回報給 controller，停在這一步。

- [ ] **Step 3: 啟動後端（背景，用獨立的 schema）**

用 Bash 的 `run_in_background: true` 執行：

```bash
ROOT=$(git rev-parse --show-toplevel); export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_e2e_b2 SPRING_FLYWAY_SCHEMAS=jev_dashboard_e2e_b2; cd "$ROOT/backend" && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1 > target/e2e-backend.log 2>&1
```

再用 Bash 的 `run_in_background: true` 跑探測迴圈（Step 2 已經確認 8080 原本沒人用，所以回應的就是這次啟動的程式）：

```bash
ROOT=$(git rev-parse --show-toplevel)
for i in $(seq 1 90); do
  r=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-User-Id: e2e-probe' -d '{"name":"x"}' \
    http://127.0.0.1:8080/api/datasources/merged/preview || true)
  case "$r" in *請選擇合併方式*) echo "backend-ready $r"; exit 0 ;; esac
  sleep 2
done
echo "backend not ready after 180 s, last response: $r"
tail -n 60 "$ROOT/backend/target/e2e-backend.log"
exit 1
```

Expected：`backend-ready {"error":"請選擇合併方式"}`。

- [ ] **Step 4: 啟動前端（背景，每次用新的使用者）**

先決定這次的使用者 id，例如 `e2e-compare-$(date +%H%M%S)`，記在報告裡。用 Bash 的 `run_in_background: true` 執行（把 `<USER>` 換成它）：

```bash
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT/frontend" && VITE_USER_ID=<USER> npm run dev -- --port 5180 --strictPort > "$ROOT/backend/target/e2e-vite.log" 2>&1
```

再等它起來：

```bash
for i in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5180/)" = 200 ] && { echo frontend-ready; exit 0; }
  sleep 2
done
echo "frontend not ready after 180 s"
exit 1
```

- [ ] **Step 5: 上傳兩份 CSV**

`new_page` 開 `http://localhost:5180/`，切到「資料」。用「新增來源 → 上傳檔案」依序上傳 `sales.csv`、`returns.csv`，名稱分別填「銷售」「退貨」。確認兩份的欄位表型別：`order_date`／`return_date` 是日期、`amount` 是數字。

- [ ] **Step 6: 建對照來源**

「新增來源 → 合併」，「方式」選「對照（數值依來源分開比較）」，「來源」依序選「銷售」「退貨」。逐項確認並記錄：

1. 對應表的輸出欄位依序是 `order_date`、`region`、`product`、`return_date`、`reason`、`銷售·amount`、`退貨·amount`（日期和文字依同名對上，`order_date` 在退貨那格是「（無）」）。
2. 把 `銷售·amount` 那一列的退貨那格選成 `amount`：這列標紅「對照時數字欄位只能對應一個來源」。再清掉，紅字消失。
3. 把 `order_date` 那一列的退貨那格選成 `return_date`，用「✕」刪掉 `return_date` 那一列，再把 `order_date` 改名成 `date`。
4. 名稱預設是「銷售／退貨 對照」。
5. 按「預覽」：共 24 筆；提示只有「「reason」只有退貨有，其他來源的列這欄是空的」，沒有 `銷售·amount`、`退貨·amount` 的提示。
6. 按「建立」。清單上出現這個來源，標籤「合併」，說明列是「對照：銷售、退貨 · 7 個欄位 · 0 個敏感」。

- [ ] **Step 7: 分析，確認兩個數值的圖**

在對照來源按「用這個來源製圖」，不填需求，按「分析」。等預覽出來後：

1. 用 `list_network_requests` 找預覽的 `POST /api/dashboards/preview/data`，`get_network_request` 讀回應，找出 `dimensions` 有三個元素、後兩個是 `銷售·amount` 和 `退貨·amount` 的卡片，記下它的 `type`、`rows`。
2. 確認那張卡片 1～6 月的 `退貨·amount` 是 `null`，7～9 月有值。
3. `take_screenshot`：那張圖有兩條系列和圖例；左右各有一條 Y 軸，軸名是兩個數值的名稱；退貨那條線（或柱子）1～6 月是空的。卡片標題是「銷售·amount 與 退貨·amount 依 date」。
4. 看有沒有「銷售·amount 依 來源」這類拿來源欄畫的圖，應該沒有；明細表的欄位裡也沒有「來源」。

JEV 沒挑兩個數值的圖時，在需求框輸入「每月銷售額和退貨額的對照」再分析一次。還是沒有，就把兩次預覽的卡片清單記在報告裡（這是 JEV 的判斷，不算失敗），跳過第 2、3 項繼續。

- [ ] **Step 8: 存檔、重新打開、異常檢查**

按「存為我的儀表板」，從左側清單重新打開它：

1. 兩個數值的圖照樣畫出來，標題、雙 Y 軸跟預覽一樣。
2. `/highlight` 請求回 200（有沒有標註都可以）；`backend/target/e2e-backend.log` 裡沒有 `Exception`（`grep -c Exception backend/target/e2e-backend.log`，JEV 逾時之類的 WARN 可以，要記下來）。
3. 切到淺色主題（導覽列最下面的切換鈕）再截一張圖，兩條系列和兩條軸都看得清楚。

- [ ] **Step 9: 一般疊加沒被影響**

「新增來源 → 合併 → 疊加」，選「銷售」「退貨」：`amount` 同名自動對上成一欄（不拆開），名稱預設「銷售＋退貨」。按「預覽」：`order_date`、`return_date`、`reason` 各出一條「只有…有」的提示，`amount` 沒有。不用建立。

- [ ] **Step 10: 收尾**

1. `close_page` 關掉自己的分頁。
2. 停掉 Step 3、4 的背景程序。停不掉的（Maven fork 出來的 `java.exe` 常會留著），用 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'` 找出 PID，寫進報告給使用者處理，不要 `taskkill`。
3. commit 測試用的 CSV：

```bash
F="docs/superpowers/e2e/compare-datasource/sales.csv docs/superpowers/e2e/compare-datasource/returns.csv"
git add $F && git commit -m "test(e2e): 對照來源的手動驗證資料" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

4. 報告：Step 5–9 每一項的結果（通過／不通過，附觀察到的值）、截圖的檔案路徑、使用者 id、留下來的 PID、中途修掉的問題和對應的 commit。
