# JEV 儀表板：依需求分析與對話式調整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`：分析前可以寫一句想看什麼，預覽時可以用一句話或卡片上的「✕」調整圖表，需求紀錄跟著儀表板存檔；另外補上這些互動的動態效果，修掉卡片飛過畫面、設定一換就全部重新載入這兩個問題。

**Architecture:** 後端的 `/api/jev/analyze` 多收 `intents` 和 `current`。有需求時 `AnalyzeService` 多問 `rel_i`，調整時再多問 `keep_<chartId>`；焦點欄位、排序、保留和新增都寫在 `ConfigAssembler` 的純函式裡。沒有需求時走原本的路徑，題目和結果跟現在一模一樣。前端的狀態（需求紀錄、調整、移除和復原）放在 `AnalyzeButton`；`DashboardView` 比對新舊版面的 chartId，負責退場、補位、進場，也只清掉消失卡片的資料。轉場全部寫在 `motion.css`，JS 只在退場時等 120ms。

**Tech Stack:** Java 21、Spring Boot 3.5.16（Jackson 2.21）、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、grid-layout-plus 1.1、Vitest 5 + @vue/test-utils 2.5 + happy-dom 20。

前一版的計畫在 `docs/superpowers/plans/2026-09-24-jev-dashboard-backend.md`、`docs/superpowers/plans/2026-09-24-jev-dashboard-frontend.md`，這份只寫新增和改變的部分。

## Global Constraints

- 直接在 branch `feat/jev-intent-analysis` 上做（已經有 3 個 spec 的 commit）。
- 本機是 Windows 11 + Git Bash。不要用 `python3`（用 `python`）；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`。不要跑 `./mvnw clean`，使用者可能開著 dev 後端。後端測試會連本機 PostgreSQL（帳密在 `backend/.env`）。
- 不要印出 `backend/.env` 的內容，`cat`、`grep` 整行都不行。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`，不能 import 這個資料夾以外的檔案；`frontend/src/App.vue` 是宿主，可以 import 模組。
- 不新增 npm 或 Maven 套件。
- 沒有需求（`intents`）時，行為、送給 JEV 的題目和 `state` 都要跟現在完全一樣。既有測試（後端 121、前端 52）全部照舊通過。既有的測試檔只會加新的測試、helper 和 import；另外 `DashboardView.test.js`、`AnalyzeButton.test.js` 這兩個前端測試檔會改到 mock 宣告、import 和 `afterEach`（Task 4、5、6、7 各自寫明）。既有的 `it`、`@Test` 和它們的預期結果都不改。
- 錯誤回應固定是 `{"error": "訊息"}`。
- 畫面上的字照 spec 原文：「想看什麼？（選填）」「例如：各區每月營收、哪個產品退貨最多」「這段文字會送給 JEV 做分析」「需求紀錄」「想怎麼調整？」「例如：不要圓餅圖、加各產品的數量」「調整」「移除這張圖」「已移除「{title}」」「復原」「符合需求」「找不到跟需求直接相關的欄位，先顯示一般的分析結果。」「需求紀錄最多 10 句」「依據：」加上用「 → 」連起來的每一句。
- 後端的 400 訊息：「想看什麼最多 200 字」「需求紀錄最多 10 句」「請輸入想怎麼調整」，卡片不合法時沿用現有訊息（例如「第 1 張圖表是空的」）。存檔和預覽時有空白的句子回「需求紀錄不能有空白的句子」（spec 第 7 節）。
- JEV 題目照 spec 原文：
  - `rel_i`（`score`）：「使用者想看：「{最新需求}」。欄位「{欄位名}」跟這個需求有多相關？」，選項 `["無關", "間接相關", "直接相關"]`。
  - `keep_<chartId>`（`choice`）：「使用者最新的要求：「{最新需求}」。目前有一張圖「{title}」（{圖型中文名}，欄位：{欄位名，用、分隔}），這張圖要保留還是移除？」，選項 `{"keep": "保留這張圖", "drop": "移除這張圖"}`。
  - 第二輪每題說明的最後加上「使用者想看：「{最新需求}」。」。
- 門檻：焦點欄位是 `rel_i` 的 `round(score) == 2` 而且 confidence ≥ 0.5；`keep_<chartId>` 答 `drop` 而且 confidence ≥ 0.5 才移除，沒回答就保留。
- 上限：每句 200 字（用 UTF-16 長度，跟瀏覽器的 `maxlength` 一致）、需求紀錄 10 句、調整後最多 6 張圖（不含明細表）、候選欄位最多 8 個。
- 版面前後端同一套：圖表依順序每列兩張 `w: 6, h: 4`，明細表放最後 `w: 12, h: 5`。
- 動態效果只動 `transform` 和 `opacity`。token：`--tc-ease-out: cubic-bezier(0.23, 1, 0.32, 1)`、`--tc-dur-1: 120ms`、`--tc-dur-2: 160ms`、`--tc-dur-3: 200ms`。退場 120ms（`opacity` 1→0、`scale(1)`→`scale(0.98)`）；進場 200ms（`opacity` 0→1、`translateY(6px)`→0），每張錯開 30ms，最多延遲 150ms；提示進場 160ms、關閉 120ms（`translateY(6px)`）；需求紀錄新增一句 160ms（`translateY(4px)`）；等待時圖表區在 160ms 內降到 `opacity: 0.6`。開啟「減少動態效果」時全部瞬間完成。
- 前端測試要用假計時器時，只假 `setTimeout` 和 `clearTimeout`：`vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })`。Vitest 5 預設會連 `setImmediate`、`requestAnimationFrame` 一起換掉，而 `flushPromises()` 靠 `setImmediate` 排程，被換掉就會卡住。
- 每個 commit 都用明確的 pathspec，格式是 `git add <檔案…> && git commit -m "<訊息>" -m "<Co-Authored-By 那一行>" -- <檔案…>`。`<Co-Authored-By 那一行>` 換成你（implementer）的 harness 規定的署名；每次 Bash 呼叫不會保留環境變數，直接貼文字最保險。

## 實作上的決定

下面是 spec 原本沒寫死、由這份計畫決定的地方。第 2、5、8、9、10 點和存檔時空白句子的訊息，使用者已經核可，也寫回 spec 了。

1. 需求輸入框放在 `AnalyzeButton.vue`，就在「分析」按鈕正上方，不放 `DatasourcePanel.vue` 或 `App.vue`：第一次分析和調整都由 `AnalyzeButton` 發出，不用跨元件傳值，模組搬到別的專案也帶得走。
2. 調整模式的保證圖（spec 6.5 第 3 點）照 6.4 第 2 點挑組合和圖型，但加在保留的圖後面，不插到第一張：6.5 第 2 點和 10.6 都規定新增的圖排在保留的圖後面。
3. 新圖的 chartId 要比 `current` 裡所有 chartId 都大，明細表和這次被移除的圖都算進去。要是新圖用到剛被移除的編號，前端會把它當成保留的圖，沿用舊資料，也不做進場。
4. 預覽裡新的設定只少了卡片、沒有新卡片時，`DashboardView` 不重打 `/api/dashboards/preview/data`（剩下的卡片都已經有資料），「✕」才真的不呼叫 API。已存儀表板照舊每次重抓。
5. 提示的「暫停計時」：滑鼠在提示上或焦點在提示裡時停止計時，兩個都離開後重新計 6 秒（跟 Vuetify snackbar 一樣），不記剩下的秒數。
6. 進場錯開的序號用卡片在新版面裡的位置，不是新增卡片之間的序號：spec 的「第 6 張以後不再往後延」和 10.6「排在保留的卡片後面，大約在補位完成時到位」都是照整個版面算。
7. 「✕」的顏色寫成 `var(--tc-muted, …)`、`var(--tc-text, …)`，後備值用 Vuetify 的 medium／high emphasis。10.1 沒有定義這兩個變數，Tactile 的外觀這次也不導入（spec 第 2 節）。
8. 等待 JEV 時「想怎麼調整？」也停用，免得等待中打的字在「成功後清空」時不見。
9. 關掉預覽時清掉錯誤訊息，調整失敗的訊息才不會跑到左側面板（spec 第 8 節：左側只顯示第一次分析的錯誤）。`docs/backlog.md` 裡「存檔失敗的錯誤訊息，取消對話框後不會清掉」那一項因此一起解決，Task 8 會把它標成已修正。
10. spec §11 端對端第 1 步寫「第一張圖是地區 × 月份」，但 6.3 只比「有沒有焦點欄位」再比平均重要性，`(order_date, amount)` 可能排在 `(order_date, region, amount)` 前面。Task 9 驗證第一張有「符合需求」、而且有一張地區 × 月份的圖，實際順序照實回報，不改規則。
11. 預覽裡剛被「✕」拿掉的卡片，`DashboardView` 先把它的資料留著；按「復原」放回同一張圖（chartId、`type`、`mapping` 都一樣）時直接沿用，不重抓，也不閃「載入中」，跟「✕」一樣不呼叫 API。同一個 chartId 換成了別的圖（例如之後的調整剛好用回這個編號）就照常重抓。只留最近一次拿掉的卡片，跟「一次只有一個提示」一致。

## File Structure

```
backend/src/main/java/jev/dashboard/
  jev/ConfigAssembler.java           焦點欄位、候選與組合排序、第一次分析的保證、調整模式的保留／新增／編號、共用排版（Task 1）
  jev/AnalyzeService.java            rel_i、keep_<chartId>、state 的 intent／history／current、第二輪說明加上需求（Task 2）
  jev/JevController.java             AnalyzeRequest 加 intents、current，清理需求並回 400（Task 3）
  dashboard/DashboardConfig.java     加選填的 intents（Task 2）
  dashboard/DashboardService.java    從 requireComplete 抽出 requireValidCards，新增 requireValidIntents（Task 3）
  dashboard/DashboardRepository.java config_json 多存 intents（Task 3）
backend/src/test/
  java/jev/dashboard/jev/ConfigAssemblerTest.java、AnalyzeServiceTest.java、JevControllerTest.java
  java/jev/dashboard/dashboard/DashboardServiceTest.java、DashboardControllerTest.java
  resources/jev/round1-intent.json、round2-intent.json   有需求時錄好的 JEV 回應（新增，Task 2）
frontend/src/
  App.vue、App.test.js               已存儀表板的 DashboardView 加 :key（Task 4）
  modules/dashboard/
    motion.css                       動態 token、所有轉場、減少動態效果（新增，Task 4）
    motion.js                        prefersReducedMotion()（新增，Task 4）
    DashboardView.vue                is-settled、只清消失卡片的資料、退場與進場（Task 4）；busy、remove、依據（Task 5）
    ChartCard.vue                    「✕」和「符合需求」（Task 5）
    api.js                           analyze(datasourceId, intents, current)（Task 6）
    AnalyzeButton.vue                需求輸入框、需求紀錄、調整、名稱規則（Task 6）；「✕」移除與「復原」提示（Task 7）
    DashboardView.test.js、ChartCard.test.js、api.test.js、AnalyzeButton.test.js
README.md、docs/JEV智慧化產製儀表板的流程與方法.md、docs/backlog.md（Task 8）
```

測試數量：後端 121 → 134（Task 1）→ 137（Task 2）→ 143（Task 3）；前端 52 → 60（Task 4）→ 65（Task 5）→ 72（Task 6）→ 79（Task 7）。

---

### Task 1: ConfigAssembler 的組裝規則（純函式）

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`
- Test: `backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`

**Interfaces:**
- Consumes：現有的 `FieldSchema`、`FieldType`、`Answer`、`ChartSpec`、`ChartSpec.GridPos`、`Mapping`、`UniversalTransformer.referencedFields(Mapping) → List<String>`
- Produces（Task 2 會用到）：
  - `record ConfigAssembler.FieldEval(FieldSchema field, double importance, String role, boolean focus)`，另有 `FieldEval(FieldSchema, double, String)` 建構子（`focus = false`，舊的呼叫和測試照常編譯）
  - `Combo.hasFocus() → boolean`（package-private）
  - `ConfigAssembler.isFocus(Answer rel) → boolean`：`round(score) == 2` 而且 confidence ≥ 0.5
  - `ConfigAssembler.evaluate(List<FieldSchema>, Map<String, Answer>) → List<FieldEval>`：多讀 `rel_i`
  - `ConfigAssembler.candidates(List<FieldEval>) → List<FieldEval>`：焦點欄位排最前面
  - `ConfigAssembler.ranked(List<FieldEval> candidates) → List<Combo>`：全部合法組合，照 spec 6.3 排序
  - `ConfigAssembler.combos(List<FieldEval> candidates, int k) → List<Combo>`：`ranked` 的前 2K 組
  - `ConfigAssembler.assemble(List<Combo> combos, Map<String, Answer> round2, int k, List<String> tableColumns) → List<ChartSpec>`：簽名不變，多了保證焦點圖和 `options.focus`
  - `ConfigAssembler.kept(List<ChartSpec> current, Map<String, Answer> round1) → List<ChartSpec>`
  - `ConfigAssembler.adjustCombos(List<Combo> ranked, List<ChartSpec> kept) → List<Combo>`
  - `ConfigAssembler.adjust(List<ChartSpec> current, List<ChartSpec> kept, List<Combo> combos, Map<String, Answer> round2, Set<String> focusFields) → List<ChartSpec>`
  - `ConfigAssembler.maxChartNumber(List<ChartSpec>) → int`（package-private）
  - `ConfigAssembler.MAX_CHARTS = 6`
  - 用到焦點欄位的圖 `options` 是 `Map.of("focus", true)`，其他圖和明細表是 `Map.of()`（跟現在一樣）

`round1.json` 加上 `rel_2`（region）直接相關時的候選順序是 region、order_date、amount、product、qty（焦點欄位不看重要性，排最前面）。排序後的組合：

| 排名 | 欄位 | 平均 | 有焦點 |
|---|---|---|---|
| 0 | region, order_date, amount | 2.583 | ✓ |
| 1 | region, amount | 2.375 | ✓ |
| 2 | region, order_date, qty | 2.0 | ✓ |
| 3 | region, amount, product | 2.0 | ✓ |
| 4 | region, qty | 1.5 | ✓ |
| 5 | region, product, qty | 1.417 | ✓ |
| 6 | order_date, amount | 2.875 | |
| 7 | order_date, amount, product | 2.333 | |

排名 2、3 同分，照產生順序；排名 6 的平均最高，但沒有焦點欄位，排在所有焦點組合後面。

- [ ] **Step 1: 寫第一次分析規則的測試**

`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`：import 區加一行 `import java.util.ArrayList;`，然後在 class 裡現有的 `names(Combo c)` 後面加這些 helper：

```java
    static FieldEval focus(FieldEval e) {
        return new FieldEval(e.field(), e.importance(), e.role(), true);
    }

    static Answer choice(String choice, double confidence) {
        return new Answer("choice", choice, null, confidence, null);
    }

    static Answer rel(double score, double confidence) {
        return new Answer("score", null, score, confidence, null);
    }

    /** combo_0 含焦點欄位 region；combo_1、combo_2 沒有。 */
    static List<Combo> regionFocusCombos() {
        FieldEval time = new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, "2024-01-05", "2024-06-20"), 3, "time");
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        FieldEval amount = eval("amount", NUMBER, 12, 2.75, "measure");
        FieldEval qty = eval("qty", NUMBER, 8, 1, "measure");
        return List.of(
            new Combo(List.of(region, amount), List.of("bar", "pie"), 2.375),
            new Combo(List.of(time, amount), List.of("line", "bar"), 2.875),
            new Combo(List.of(time, qty), List.of("line", "bar"), 2.0));
    }
```

再把下面 6 個測試加在 class 最後一個 `}` 前面：

```java
    @Test
    void evaluateMarksDirectlyRelevantFieldsAsFocus() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("rel_2", rel(2.2, 0.9)); // 四捨五入是 2：直接相關
        a.put("rel_3", rel(2.0, 0.4)); // 信心不足
        a.put("rel_4", rel(1.4, 0.9)); // 四捨五入是 1：間接相關
        assertThat(ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a)).extracting(FieldEval::name, FieldEval::focus)
            .containsExactly(tuple("order_date", false), tuple("amount", false), tuple("region", true),
                tuple("product", false), tuple("qty", false), tuple("memo", false));
    }

    @Test
    void focusFieldsLeadTheCandidatesEvenWhenUnimportant() {
        List<FieldEval> e = List.of(
            eval("amount", NUMBER, 12, 2.75, "measure"),
            focus(eval("region", STRING, 4, 0.25, "dimension")), // 不重要，但直接相關
            focus(eval("memo", STRING, 12, 3, "ignore")),        // 直接相關，但角色是 ignore
            eval("qty", NUMBER, 8, 0.25, "measure"));             // 不重要，也不相關
        assertThat(ConfigAssembler.candidates(e)).extracting(FieldEval::name).containsExactly("region", "amount");
    }

    @Test
    void atMostEightFocusFieldsAreKeptByImportance() {
        List<FieldEval> e = new ArrayList<>();
        for (int i = 1; i <= 9; i++) e.add(focus(eval("f" + i, NUMBER, 5, i, "measure")));
        e.add(eval("other", NUMBER, 5, 3, "measure"));
        assertThat(ConfigAssembler.candidates(e)).extracting(FieldEval::name)
            .containsExactly("f9", "f8", "f7", "f6", "f5", "f4", "f3", "f2");
    }

    @Test
    void combosWithFocusFieldsRankFirst() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("rel_2", rel(2.0, 0.9)); // region 直接相關
        List<Combo> combos = ConfigAssembler.combos(
            ConfigAssembler.candidates(ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a)), 4);
        assertThat(combos).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("region", "order_date", "amount"),
            List.of("region", "amount"),
            List.of("region", "order_date", "qty"),
            List.of("region", "amount", "product"),
            List.of("region", "qty"),
            List.of("region", "product", "qty"),
            List.of("order_date", "amount"),
            List.of("order_date", "amount", "product"));
    }

    @Test
    void firstAnalysisPutsTheTopFocusComboFirstWhenNoPickedChartUsesAFocusField() {
        Map<String, Answer> round2 = Map.of(
            "combo_0", choice("none", 0.9), "combo_1", choice("line", 0.9), "combo_2", choice("bar", 0.9));
        List<ChartSpec> layout = ConfigAssembler.assemble(regionFocusCombos(), round2, 2, TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::options).containsExactly(
            tuple("c1", "bar", Map.of("focus", true)), // 排最前面的焦點組合，用它第一個合法圖型
            tuple("c2", "line", Map.of()),             // 原本的第一張
            tuple("c3", "table", Map.of()));           // 原本的第二張拿掉，維持 K = 2
        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("region", null, null, "amount", null, null, "sum", null));
        assertThat(layout).extracting(ChartSpec::gridPos).containsExactly(
            new GridPos(0, 0, 6, 4), new GridPos(6, 0, 6, 4), new GridPos(0, 4, 12, 5));
    }

    @Test
    void firstAnalysisMarksChartsThatUseFocusFields() {
        Map<String, Answer> round2 = Map.of("combo_0", choice("pie", 0.9), "combo_1", choice("line", 0.9));
        List<ChartSpec> layout = ConfigAssembler.assemble(regionFocusCombos(), round2, 3, TABLE_COLUMNS);
        assertThat(layout).extracting(ChartSpec::type, ChartSpec::options).containsExactly(
            tuple("pie", Map.of("focus", true)), tuple("line", Map.of()), tuple("table", Map.of()));
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: FAIL，編譯錯誤，例如 `constructor FieldEval in record FieldEval cannot be applied to given types`、`cannot find symbol ... focus()`

- [ ] **Step 3: 實作第一次分析的規則**

`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java` 改五個地方。

(a) 把 `FieldEval`、`Combo` 兩個 record：

```java
    public record FieldEval(FieldSchema field, double importance, String role) {
        public String name() {
            return field.name();
        }
    }

    public record Combo(List<FieldEval> fields, List<String> chartTypes, double score) {
        List<FieldEval> byRole(String role) {
            return fields.stream().filter(f -> f.role().equals(role)).toList();
        }
    }
```

換成：

```java
    /** focus：spec 6.1 的焦點欄位（rel_i 答「直接相關」）。沒有需求時一律是 false。 */
    public record FieldEval(FieldSchema field, double importance, String role, boolean focus) {
        public FieldEval(FieldSchema field, double importance, String role) {
            this(field, importance, role, false);
        }

        public String name() {
            return field.name();
        }
    }

    public record Combo(List<FieldEval> fields, List<String> chartTypes, double score) {
        List<FieldEval> byRole(String role) {
            return fields.stream().filter(f -> f.role().equals(role)).toList();
        }

        boolean hasFocus() {
            return fields.stream().anyMatch(FieldEval::focus);
        }
    }

    /** 選出來的一張圖：哪一組欄位、畫成哪種圖。 */
    private record Pick(Combo combo, String type) {}
```

(b) `evaluate` 迴圈裡的 `out.add(new FieldEval(f, importance, r));` 換成：

```java
            out.add(new FieldEval(f, importance, r, isFocus(answers.get("rel_" + i))));
```

並在 `evaluate` 方法後面加：

```java
    /** spec 6.1：rel_i 的 round(score) 等於 2（直接相關），而且 confidence ≥ 0.5；JEV 沒回答就不算。 */
    static boolean isFocus(Answer rel) {
        return rel != null && rel.score() != null && rel.confidence() != null
            && Math.round(rel.score()) == 2 && rel.confidence() >= MIN_CONFIDENCE;
    }
```

(c) 整個 `candidates` 方法換成：

```java
    /** spec 6.2：角色不是 ignore 的焦點欄位不看重要性，排在最前面；其他欄位照原本的規則補，總數最多 8 個。 */
    public static List<FieldEval> candidates(List<FieldEval> evals) {
        Comparator<FieldEval> byImportance = Comparator.comparingDouble(FieldEval::importance).reversed();
        Stream<FieldEval> focus = evals.stream()
            .filter(e -> e.focus() && !e.role().equals("ignore"))
            .sorted(byImportance);
        Stream<FieldEval> others = evals.stream()
            .filter(e -> !e.focus() && !e.role().equals("ignore") && Math.round(e.importance()) >= 1)
            .sorted(byImportance);
        return Stream.concat(focus, others).limit(MAX_CANDIDATES).toList();
    }
```

(d) `combos` 改名成 `ranked`，只換頭尾兩處。方法宣告那一行：

```java
    public static List<Combo> combos(List<FieldEval> c, int k) {
```

換成：

```java
    /** 全部合法組合，照 spec 6.3 排序：含焦點欄位的排前面，再比平均重要性，同分照產生順序（穩定排序）。 */
    public static List<Combo> ranked(List<FieldEval> c) {
```

方法最後兩行：

```java
        all.sort(Comparator.comparingDouble(Combo::score).reversed()); // 穩定排序，同分照產生順序
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }
```

換成：

```java
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
        return all;
    }

    /** 第一次分析要問的組合：排序後的前 2K 組。 */
    public static List<Combo> combos(List<FieldEval> c, int k) {
        List<Combo> all = ranked(c);
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }
```

中間產生組合的程式碼不動。

(e) 整個 `assemble` 方法換成下面這段（`assemble` 的簽名不變，後面四個 private／package-private 方法是新的）：

```java
    /**
     * 第一次分析的組裝（spec 6.4）：照排序取前 K 張能用的圖。有焦點組合、但選出來的圖都沒用到焦點欄位時，
     * 把排最前面的焦點組合用它第一個合法圖型放在第一張；原本已經 K 張就拿掉最後一張。
     */
    public static List<ChartSpec> assemble(List<Combo> combos, Map<String, Answer> round2, int k,
                                           List<String> tableColumns) {
        List<Pick> picks = picks(combos, round2, k);
        Combo top = combos.stream().filter(Combo::hasFocus).findFirst().orElse(null);
        if (top != null && picks.stream().noneMatch(p -> p.combo().hasFocus())) {
            if (picks.size() == k) picks.remove(picks.size() - 1);
            picks.add(0, new Pick(top, top.chartTypes().get(0)));
        }
        List<ChartSpec> charts = new ArrayList<>();
        for (Pick p : picks) charts.add(chart("c" + (charts.size() + 1), p));
        charts.add(table("c" + (charts.size() + 1), tableColumns));
        return layOut(charts);
    }

    /** 照組合順序取第二輪能用的答案（不是 none、信心夠、在合法圖型裡），最多 limit 張。 */
    private static List<Pick> picks(List<Combo> combos, Map<String, Answer> round2, int limit) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < combos.size() && out.size() < limit; i++) {
            Answer a = round2.get("combo_" + i);
            if (a == null || a.choice() == null || a.choice().equals("none")) continue;
            if (a.confidence() == null || a.confidence() < MIN_CONFIDENCE) continue;
            if (!combos.get(i).chartTypes().contains(a.choice())) continue;
            out.add(new Pick(combos.get(i), a.choice()));
        }
        return out;
    }

    /** gridPos 先留空，由 layOut 統一排；用到焦點欄位的圖標 options.focus。 */
    private static ChartSpec chart(String chartId, Pick p) {
        Mapping m = mapping(p.type(), p.combo());
        Map<String, Object> options = p.combo().hasFocus() ? Map.of("focus", true) : Map.of();
        return new ChartSpec(chartId, p.type(), title(p.type(), m), null, m, options);
    }

    private static ChartSpec table(String chartId, List<String> columns) {
        return new ChartSpec(chartId, "table", "明細", null,
            new Mapping(null, null, null, null, null, columns, "none", null), Map.of());
    }

    /** spec 6.6：圖表依順序每列兩張（6×4），明細表放最後（12×5）。其他設定原封不動。 */
    static List<ChartSpec> layOut(List<ChartSpec> specs) {
        List<ChartSpec> out = new ArrayList<>();
        for (ChartSpec s : specs) {
            if (s.type().equals("table")) continue;
            int n = out.size();
            out.add(at(s, new GridPos((n % 2) * 6, (n / 2) * 4, 6, 4)));
        }
        int y = (out.size() + 1) / 2 * 4;
        for (ChartSpec s : specs) {
            if (s.type().equals("table")) out.add(at(s, new GridPos(0, y, 12, 5)));
        }
        return out;
    }

    private static ChartSpec at(ChartSpec s, GridPos pos) {
        return new ChartSpec(s.chartId(), s.type(), s.title(), pos, s.mapping(), s.options());
    }
```

沒有焦點欄位時：`candidates` 裡焦點欄位那一段是空的，其餘照原本的篩選和排序；`ranked` 裡每組 `hasFocus()` 都是 false，順序跟原本一樣；`assemble` 不會觸發保證，`options` 一樣是 `Map.of()`。所以既有的測試不用改。

- [ ] **Step 4: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: PASS（ConfigAssemblerTest 17 個測試）

- [ ] **Step 5: 寫調整模式的測試**

同一個測試檔，import 區再加兩行：

```java
import java.util.Set;
import java.util.stream.IntStream;
```

helper 區（Step 1 加的 helper 後面）再加：

```java
    static final FieldEval PRODUCT = focus(eval("product", STRING, 3, 1.25, "dimension"));

    static Mapping xy(String x, String value) {
        return new Mapping(x, null, null, value, null, null, "sum", null);
    }

    static ChartSpec card(String chartId, String type, Mapping m) {
        return new ChartSpec(chartId, type, chartId + " 圖", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    static ChartSpec table(String chartId, List<String> columns) {
        return new ChartSpec(chartId, "table", "明細", new GridPos(0, 4, 12, 5),
            new Mapping(null, null, null, null, null, columns, "none", null), Map.of());
    }
```

再加 7 個測試：

```java
    @Test
    void keptDropsAChartOnlyWhenJevConfidentlySaysDrop() {
        List<ChartSpec> current = List.of(card("c1", "line", xy("order_date", "amount")),
            card("c2", "pie", xy("region", "amount")), card("c3", "bar", xy("product", "qty")), table("c4", List.of("region")));
        Map<String, Answer> round1 = Map.of(
            "keep_c1", choice("drop", 0.9),  // 移除
            "keep_c2", choice("drop", 0.4),  // 信心不足，保留
            "keep_c4", choice("drop", 0.9)); // 明細表不問，一律保留；c3 沒回答，保留
        assertThat(ConfigAssembler.kept(current, round1)).extracting(ChartSpec::chartId).containsExactly("c2", "c3", "c4");
    }

    @Test
    void adjustCombosAskOnlyFocusCombosAndSkipChartTypesTheKeptChartsAlreadyShow() {
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        FieldEval amount = eval("amount", NUMBER, 12, 2.75, "measure");
        FieldEval qty = eval("qty", NUMBER, 8, 1, "measure");
        FieldEval time = new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, "2024-01-05", "2024-06-20"), 3, "time");
        List<Combo> ranked = List.of(
            new Combo(List.of(region, amount), List.of("bar", "pie"), 2.375),
            new Combo(List.of(region, qty), List.of("bar", "pie"), 1.5),
            new Combo(List.of(time, amount), List.of("line", "bar"), 2.875)); // 沒有焦點欄位
        List<ChartSpec> kept = List.of(
            card("c1", "pie", xy("region", "amount")), // 同一組欄位已經有圓餅圖
            card("c2", "bar", xy("region", "qty")),
            card("c3", "pie", xy("region", "qty")),    // 第二組的 bar、pie 都有了，只剩 none，不問
            table("c4", List.of("region", "amount")));
        assertThat(ConfigAssembler.adjustCombos(ranked, kept)).extracting(ConfigAssemblerTest::names, Combo::chartTypes)
            .containsExactly(tuple(List.of("region", "amount"), List.of("bar")));
    }

    @Test
    void adjustCombosAskAtMostTwiceTheFreeSlots() {
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        List<Combo> ranked = List.of(
            new Combo(List.of(region, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar"), 2.375),
            new Combo(List.of(region, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar"), 1.5),
            new Combo(List.of(region, eval("cost", NUMBER, 8, 1, "measure")), List.of("bar"), 1.5));
        List<ChartSpec> kept = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> card("c" + i, "line", xy("order_date", "m" + i))).toList();
        assertThat(ConfigAssembler.adjustCombos(ranked, kept)).hasSize(2); // 2 ×（6 − 5）
    }

    @Test
    void adjustKeepsChartsAsTheyWereAppendsFocusChartsAndContinuesChartIds() {
        ChartSpec keptPie = new ChartSpec("c2", "pie", "amount 依 region", new GridPos(6, 0, 6, 4),
            xy("region", "amount"), Map.of("focus", true));
        ChartSpec keptTable = table("c5", List.of("region", "amount"));
        List<ChartSpec> current = List.of(card("c9", "line", xy("order_date", "amount")), keptPie, keptTable); // c9 這次被移除
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar", "pie"), 2.0),
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        Map<String, Answer> round2 = Map.of("combo_0", choice("bar", 0.9), "combo_1", choice("bar", 0.8));

        List<ChartSpec> layout = ConfigAssembler.adjust(current, List.of(keptPie, keptTable), combos, round2, Set.of("product"));

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos, ChartSpec::options).containsExactly(
            tuple("c2", "pie", new GridPos(0, 0, 6, 4), Map.of("focus", true)),  // 保留的圖設定不變，只重新排版
            tuple("c10", "bar", new GridPos(6, 0, 6, 4), Map.of("focus", true)), // 最大的是被移除的 c9，從 10 接下去
            tuple("c11", "bar", new GridPos(0, 4, 6, 4), Map.of("focus", true)),
            tuple("c5", "table", new GridPos(0, 8, 12, 5), Map.of()));           // 明細表放最後
        assertThat(layout.get(0).mapping()).isEqualTo(keptPie.mapping());
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("product", null, null, "amount", null, null, "sum", null));
        assertThat(layout.get(3).mapping()).isEqualTo(keptTable.mapping()); // 明細表沿用原本的欄位
        assertThat(ConfigAssembler.maxChartNumber(List.of(card("c3", "bar", xy("region", "amount")), table("c12", List.of("region")))))
            .isEqualTo(12); // 明細表的編號也算
    }

    @Test
    void adjustAddsAtMostSixCharts() {
        List<ChartSpec> kept = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> card("c" + i, "line", xy("order_date", "m" + i))).toList();
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar"), 2.0),
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar"), 1.125));
        Map<String, Answer> round2 = Map.of("combo_0", choice("bar", 0.9), "combo_1", choice("bar", 0.9));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, round2, Set.of("product")))
            .extracting(ChartSpec::chartId).containsExactly("c1", "c2", "c3", "c4", "c5", "c6");
    }

    @Test
    void adjustAddsNoGeneralChartsWhenAKeptChartAlreadyUsesAFocusField() {
        List<ChartSpec> kept = List.of(card("c1", "pie", xy("product", "amount")), table("c2", List.of("product")));
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, Map.of("combo_0", choice("none", 0.9)), Set.of("product")))
            .extracting(ChartSpec::chartId).containsExactly("c1", "c2");
    }

    @Test
    void adjustAddsTheTopFocusComboWhenNothingShowsTheFocus() {
        List<ChartSpec> kept = List.of(card("c1", "line", xy("order_date", "amount")), table("c2", List.of("order_date")));
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, Map.of(), Set.of("product")))
            .extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::options).containsExactly(
                tuple("c1", "line", Map.of()),
                tuple("c3", "bar", Map.of("focus", true)), // 加在保留的圖後面，用第一個合法圖型
                tuple("c2", "table", Map.of()));
    }
```

- [ ] **Step 6: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: FAIL，編譯錯誤 `cannot find symbol ... method kept`（還有 `adjustCombos`、`adjust`、`maxChartNumber`）

- [ ] **Step 7: 實作調整模式**

`ConfigAssembler.java` 的 import 區加三行：

```java
import java.util.HashSet;
import java.util.Set;
import jev.dashboard.transform.UniversalTransformer;
```

`static final int MAX_CANDIDATES = 8;` 下面加：

```java
    /** spec 6.5：調整後最多 6 張圖（不含明細表）。 */
    static final int MAX_CHARTS = 6;
```

然後把下面這段加在 `public static String timeGrain(FieldSchema f)` 前面：

```java
    /** spec 6.5 第 1 點：keep_<chartId> 答 drop 而且信心 ≥ 0.5 才移除，其他情況（包含沒回答）一律保留；明細表不問，一律保留。 */
    public static List<ChartSpec> kept(List<ChartSpec> current, Map<String, Answer> round1) {
        return current.stream()
            .filter(s -> s.type().equals("table") || !dropped(round1.get("keep_" + s.chartId())))
            .toList();
    }

    private static boolean dropped(Answer a) {
        return a != null && "drop".equals(a.choice()) && a.confidence() != null && a.confidence() >= MIN_CONFIDENCE;
    }

    /**
     * 調整模式要問的組合（spec 5.3、6.3）：只留含焦點欄位的組合；欄位集合跟某張保留的圖一樣時，把那張圖的圖型從選項拿掉，
     * 選項只剩 none 的組合不問；最多 2 ×（6 − 保留張數）組。
     */
    public static List<Combo> adjustCombos(List<Combo> ranked, List<ChartSpec> kept) {
        List<ChartSpec> keptCharts = withoutTables(kept);
        List<Combo> out = new ArrayList<>();
        for (Combo c : ranked) {
            if (!c.hasFocus()) continue;
            Set<String> fields = c.fields().stream().map(FieldEval::name).collect(Collectors.toSet());
            List<String> types = c.chartTypes().stream()
                .filter(t -> keptCharts.stream().noneMatch(s -> s.type().equals(t) && fieldSet(s).equals(fields)))
                .toList();
            if (!types.isEmpty()) out.add(new Combo(c.fields(), types, c.score()));
        }
        int limit = 2 * Math.max(0, MAX_CHARTS - keptCharts.size());
        return List.copyOf(out.subList(0, Math.min(out.size(), limit)));
    }

    /**
     * 調整模式的組裝（spec 6.5–6.7）：保留的圖原封不動排前面，第二輪接受的焦點組合依序加在後面，總數最多 6 張；
     * 一張都沒加、保留的圖也沒用到焦點欄位、還有空位時，補上排最前面的焦點組合。
     * 新圖的 chartId 接在 current 所有 chartId 最大的數字後面；明細表沿用 current 的，放最後。不補一般性的圖。
     */
    public static List<ChartSpec> adjust(List<ChartSpec> current, List<ChartSpec> kept, List<Combo> combos,
                                         Map<String, Answer> round2, Set<String> focusFields) {
        List<ChartSpec> charts = new ArrayList<>(withoutTables(kept));
        int room = Math.max(0, MAX_CHARTS - charts.size());
        List<Pick> picks = picks(combos, round2, room);
        boolean keptUsesFocus = charts.stream().anyMatch(s -> fieldSet(s).stream().anyMatch(focusFields::contains));
        if (picks.isEmpty() && !combos.isEmpty() && !keptUsesFocus && room > 0) {
            picks.add(new Pick(combos.get(0), combos.get(0).chartTypes().get(0)));
        }
        int n = maxChartNumber(current);
        for (Pick p : picks) charts.add(chart("c" + (++n), p));
        kept.stream().filter(s -> s.type().equals("table")).forEach(charts::add);
        return layOut(charts);
    }

    private static List<ChartSpec> withoutTables(List<ChartSpec> specs) {
        return specs.stream().filter(s -> !s.type().equals("table")).toList();
    }

    private static Set<String> fieldSet(ChartSpec s) {
        return new HashSet<>(UniversalTransformer.referencedFields(s.mapping()));
    }

    /** 形如 c<數字> 的 chartId 取最大的數字（明細表、這次移除的都算），新圖從下一號開始，保證不重複。 */
    static int maxChartNumber(List<ChartSpec> specs) {
        return specs.stream().map(ChartSpec::chartId)
            .filter(id -> id != null && id.matches("c\\d{1,9}"))
            .mapToInt(id -> Integer.parseInt(id.substring(1)))
            .max().orElse(0);
    }
```

`adjustCombos` 只收焦點組合，`adjust` 的新圖經過 `chart()` 一定標 `options.focus`；保留的圖只換 `gridPos`（`layOut` 用 `at()` 複製其他欄位）。

- [ ] **Step 8: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: PASS（ConfigAssemblerTest 24 個測試）

- [ ] **Step 9: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 134, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java && git commit -m "feat(backend): focus fields, focus-first ranking and adjust-mode assembly" -m "<Co-Authored-By 那一行>" -- backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java
```

---

### Task 2: AnalyzeService 的題目、state 和 DashboardConfig.intents

**Files:**
- Create: `backend/src/test/resources/jev/round1-intent.json`、`backend/src/test/resources/jev/round2-intent.json`
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java`（整個換掉）、`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`（整個換掉）
- Test: `backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`

**Interfaces:**
- Consumes：Task 1 的 `ConfigAssembler.evaluate`、`candidates`、`ranked`、`combos`、`assemble`、`kept`、`adjustCombos`、`adjust`、`FieldEval.focus()`
- Produces（Task 3 會用到）：
  - `record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout, List<String> intents)`，另有不帶 `intents` 的五參數建構子（`intents = null`）；JSON 省略 null
  - `AnalyzeService.analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount, List<String> intents, List<ChartSpec> current) → DashboardConfig`：`intents` 要傳清理過、不是 null 的 list（空的就是沒有需求）；`current` 是 null 代表第一次分析；回傳的 `intents` 在沒有需求時是 null
  - 原本的四參數 `analyze(name, datasourceId, schema, rowCount)` 保留，等於 `intents = List.of()`、`current = null`

測試用的 `round1-intent.json` 跟 `round1.json` 一樣，另外有 `rel_3`（product）直接相關、`keep_c1` 保留、`keep_c2` 移除。這時候選欄位是 product、order_date、amount、region、qty，排序後前 6 組都是焦點組合：

| combo | 欄位 | 平均 | 合法圖型 |
|---|---|---|---|
| 0 | product, order_date, amount | 2.333 | stacked_bar, heatmap |
| 1 | product, amount | 2.0 | bar, pie |
| 2 | product, amount, region | 2.0 | stacked_bar, heatmap |
| 3 | product, order_date, qty | 1.75 | stacked_bar, heatmap |
| 4 | product, region, qty | 1.417 | stacked_bar, heatmap |
| 5 | product, qty | 1.125 | bar, pie |

第一次分析 K = 3，剛好問這 6 組；調整時保留 1 張，最多問 2 ×（6 − 1）= 10 組，也是這 6 組。`round2-intent.json` 只接受 combo_1 和 combo_5，兩張都畫成長條圖。

- [ ] **Step 1: 放兩份錄好的 JEV 回應**

`backend/src/test/resources/jev/round1-intent.json`：

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "imp_0": {"type": "score", "score": 3.0, "confidence": 0.9},
    "role_0": {"type": "choice", "choice": "time", "confidence": 0.95},
    "imp_1": {"type": "score", "score": 2.75, "confidence": 0.9},
    "role_1": {"type": "choice", "choice": "measure", "confidence": 0.9},
    "imp_2": {"type": "score", "score": 2.0, "confidence": 0.8},
    "role_2": {"type": "choice", "choice": "dimension", "confidence": 0.9},
    "imp_3": {"type": "score", "score": 1.25, "confidence": 0.7},
    "role_3": {"type": "choice", "choice": "dimension", "confidence": 0.8},
    "imp_4": {"type": "score", "score": 1.0, "confidence": 0.7},
    "role_4": {"type": "choice", "choice": "measure", "confidence": 0.85},
    "imp_5": {"type": "score", "score": 0.25, "confidence": 0.9},
    "role_5": {"type": "choice", "choice": "ignore", "confidence": 0.9},
    "rel_3": {"type": "score", "score": 2.0, "confidence": 0.9},
    "chart_count": {"type": "score", "score": 2.2, "confidence": 0.6},
    "keep_c1": {"type": "choice", "choice": "keep", "confidence": 0.9},
    "keep_c2": {"type": "choice", "choice": "drop", "confidence": 0.9}
  },
  "usage": {"input_tokens": 1500, "output_tokens": 120}
}
```

`backend/src/test/resources/jev/round2-intent.json`：

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "combo_0": {"type": "choice", "choice": "none", "confidence": 0.8},
    "combo_1": {"type": "choice", "choice": "bar", "confidence": 0.9},
    "combo_2": {"type": "choice", "choice": "none", "confidence": 0.8},
    "combo_3": {"type": "choice", "choice": "none", "confidence": 0.8},
    "combo_4": {"type": "choice", "choice": "none", "confidence": 0.8},
    "combo_5": {"type": "choice", "choice": "bar", "confidence": 0.85}
  },
  "usage": {"input_tokens": 900, "output_tokens": 40}
}
```

- [ ] **Step 2: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`：import 區加：

```java
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
```

在 class 最後一個 `}` 前面（`memoField` 後面）加：

```java
    private static AnalyzeService service(FakeJevServer server) {
        return new AnalyzeService(new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
    }

    private static String intentRoute(String body) {
        return body.contains("\"combo_0\"") ? Fixtures.json("round2-intent.json") : Fixtures.json("round1-intent.json");
    }

    @Test
    void withoutIntentsNothingNewIsAsked() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12);

            assertThat(c.intents()).isNull();
            assertThat(c.layout()).allSatisfy(s -> assertThat(s.options()).isEmpty());
            assertThat(server.bodies.get(0)).doesNotContain("rel_").doesNotContain("keep_")
                .doesNotContain("\"intent\"").doesNotContain("\"history\"").doesNotContain("\"current\"");
            assertThat(server.bodies.get(1)).doesNotContain("使用者想看");
        }
    }

    @Test
    void firstAnalysisWithAnIntentAsksRelevanceAndPutsFocusChartsFirst() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(AnalyzeServiceTest::intentRoute)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                List.of("各產品的數量"), null);

            assertThat(c.intents()).containsExactly("各產品的數量");
            assertThat(c.layout()).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::title, ChartSpec::options)
                .containsExactly(
                    tuple("c1", "bar", "amount 依 product", Map.of("focus", true)),
                    tuple("c2", "bar", "qty 依 product", Map.of("focus", true)),
                    tuple("c3", "table", "明細", Map.of()));
            // 明細表的欄位是候選欄位，焦點欄位排最前面
            assertThat(c.layout().get(2).mapping().columns()).containsExactly("product", "order_date", "amount", "region", "qty");

            String round1 = server.bodies.get(0);
            assertThat(round1).contains("\"rel_0\"").contains("\"rel_5\"").contains("\"chart_count\"")
                .contains("\"intent\":\"各產品的數量\"").doesNotContain("\"history\"")
                .doesNotContain("keep_").doesNotContain("\"current\"")
                .contains("使用者想看：「各產品的數量」。欄位「product」跟這個需求有多相關？")
                .contains("[\"無關\",\"間接相關\",\"直接相關\"]")
                .doesNotContain("機密備註");
            assertThat(memoField(round1)).isEqualTo(Map.of("name", "memo", "type", "string"));
            assertThat(server.bodies.get(1)).contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("選 none。使用者想看：「各產品的數量」。");
        }
    }

    @Test
    void adjustAsksWhetherToKeepEachChartAndAddsFocusChartsAfterTheKeptOnes() throws Exception {
        ChartSpec line = new ChartSpec("c1", "line", "amount 依 order_date", new GridPos(0, 0, 6, 4),
            new Mapping("order_date", null, null, "amount", null, null, "sum", "month"), Map.of());
        ChartSpec pie = new ChartSpec("c2", "pie", "amount 依 region", new GridPos(6, 0, 6, 4),
            new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
        ChartSpec table = new ChartSpec("c3", "table", "明細", new GridPos(0, 4, 12, 5),
            new Mapping(null, null, null, null, null, List.of("order_date", "amount", "region"), "none", null), Map.of());
        List<String> intents = List.of("各區每月營收", "不要圓餅圖，加各產品的數量");

        try (FakeJevServer server = FakeJevServer.start(AnalyzeServiceTest::intentRoute)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                intents, List.of(line, pie, table));

            assertThat(c.intents()).isEqualTo(intents);
            assertThat(c.layout()).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos).containsExactly(
                tuple("c1", "line", new GridPos(0, 0, 6, 4)),     // keep_c1：保留
                tuple("c4", "bar", new GridPos(6, 0, 6, 4)),      // 新圖的編號接在 c3 後面
                tuple("c5", "bar", new GridPos(0, 4, 6, 4)),
                tuple("c3", "table", new GridPos(0, 8, 12, 5)));  // keep_c2：移除；明細表放最後
            assertThat(c.layout().get(0)).isEqualTo(line);        // 保留的圖設定原封不動
            assertThat(c.layout().get(1).options()).isEqualTo(Map.of("focus", true));
            assertThat(c.layout().get(3).mapping()).isEqualTo(table.mapping());

            String round1 = server.bodies.get(0);
            assertThat(round1).contains("\"keep_c1\"").contains("\"keep_c2\"").doesNotContain("\"keep_c3\"")
                .doesNotContain("\"chart_count\"").contains("\"rel_0\"")
                .contains("\"intent\":\"不要圓餅圖，加各產品的數量\"").contains("\"history\":[\"各區每月營收\"]")
                .contains("\"current\":[{\"chartId\":\"c1\",\"title\":\"amount 依 order_date\",\"type\":\"line\","
                    + "\"fields\":[\"order_date\",\"amount\"]},{\"chartId\":\"c2\",\"title\":\"amount 依 region\","
                    + "\"type\":\"pie\",\"fields\":[\"region\",\"amount\"]}]")
                .contains("使用者最新的要求：「不要圓餅圖，加各產品的數量」。目前有一張圖「amount 依 region」"
                    + "（圓餅圖，欄位：region、amount），這張圖要保留還是移除？")
                .contains("{\"keep\":\"保留這張圖\",\"drop\":\"移除這張圖\"}")
                .doesNotContain("機密備註");
            assertThat(server.bodies.get(1)).contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("選 none。使用者想看：「不要圓餅圖，加各產品的數量」。");
        }
    }
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=AnalyzeServiceTest`
Expected: FAIL，編譯錯誤 `cannot find symbol ... method intents()`，以及 `method analyze ... cannot be applied to given types`

- [ ] **Step 4: DashboardConfig 加上 intents**

`backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java` 整個換成：

```java
package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

/** intents：需求紀錄（選填），依序是使用者說過的每一句。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout,
                              List<String> intents) {
    /** 沒有需求紀錄的設定：舊儀表板，或沒填需求的分析結果。 */
    public DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout) {
        this(id, name, datasourceId, createdBy, layout, null);
    }
}
```

Jackson 2.21 反序列化 record 時用的是 canonical（六個參數）的建構子，多一個建構子不影響 `POST /api/dashboards`。

- [ ] **Step 5: AnalyzeService 加上需求**

`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java` 整個換成：

```java
package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeService {
    static final List<String> IMPORTANCE = List.of("不重要，略過", "次要", "重要", "核心指標");
    static final List<String> CHART_COUNTS = List.of("1 張", "2 張", "3 張", "4 張", "5 張", "6 張");
    static final List<String> RELEVANCE = List.of("無關", "間接相關", "直接相關");
    static final Map<String, String> ROLES = new LinkedHashMap<>();
    static final Map<String, String> KEEP = new LinkedHashMap<>();
    static final Map<String, String> ROLE_ZH = Map.of("dimension", "分類", "measure", "數值", "time", "時間");
    static {
        ROLES.put("dimension", "分類維度，用來分組，例如地區、產品");
        ROLES.put("measure", "數值指標，可以加總或平均，例如金額、數量");
        ROLES.put("time", "時間，例如訂單日期");
        ROLES.put("ignore", "忽略，例如流水號、備註");
        KEEP.put("keep", "保留這張圖");
        KEEP.put("drop", "移除這張圖");
    }

    private final JevClient jev;

    public AnalyzeService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    /** 沒有需求的第一次分析：題目、state 和結果都跟加入需求功能之前一樣。 */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount) {
        return analyze(name, datasourceId, schema, rowCount, List.of(), null);
    }

    /**
     * @param intents 清理過的需求紀錄，最後一句是這次的需求；空的代表沒有需求
     * @param current 調整模式時目前預覽裡的圖（含明細表）；null 是第一次分析
     */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current) {
        String intent = intents.isEmpty() ? null : intents.get(intents.size() - 1);
        Map<String, Object> state = state(schema, rowCount, intents, current);

        Map<String, Question> round1 = new LinkedHashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            String field = schema.get(i).name();
            round1.put("imp_" + i, Question.score("欄位「" + field + "」對分析這份資料有多重要？", IMPORTANCE));
            round1.put("role_" + i, Question.choice("欄位「" + field + "」在圖表裡適合扮演什麼角色？", ROLES));
            if (intent != null) {
                round1.put("rel_" + i, Question.score(
                    "使用者想看：「" + intent + "」。欄位「" + field + "」跟這個需求有多相關？", RELEVANCE));
            }
        }
        if (current == null) {
            round1.put("chart_count", Question.score("這份資料適合做成幾張圖表？", CHART_COUNTS));
        } else {
            // 調整不補一般性的圖，所以不問 chart_count；明細表不問要不要留
            for (ChartSpec s : current) {
                if (!s.type().equals("table")) {
                    round1.put("keep_" + s.chartId(), Question.choice(keepQuestion(intent, s), KEEP));
                }
            }
        }
        Map<String, Answer> a1 = jev.ask(state, round1);

        List<FieldEval> evals = ConfigAssembler.evaluate(schema, a1);
        List<FieldEval> candidates = ConfigAssembler.candidates(evals);
        int k = ConfigAssembler.chartCount(a1.get("chart_count"));
        List<ChartSpec> kept = current == null ? null : ConfigAssembler.kept(current, a1);
        List<Combo> combos = current == null
            ? ConfigAssembler.combos(candidates, k)
            : ConfigAssembler.adjustCombos(ConfigAssembler.ranked(candidates), kept);

        Map<String, Answer> a2 = Map.of();
        if (!combos.isEmpty()) {
            Map<String, Question> round2 = new LinkedHashMap<>();
            for (int j = 0; j < combos.size(); j++) {
                Combo c = combos.get(j);
                round2.put("combo_" + j, Question.choice(describe(c, intent), criteria(c)));
            }
            a2 = jev.ask(state, round2);
        }

        List<ChartSpec> layout;
        if (current == null) {
            List<String> tableColumns = (candidates.isEmpty()
                ? schema.stream().map(FieldSchema::name)
                : candidates.stream().map(FieldEval::name)).toList();
            layout = ConfigAssembler.assemble(combos, a2, k, tableColumns);
        } else {
            Set<String> focus = evals.stream().filter(FieldEval::focus).map(FieldEval::name).collect(Collectors.toSet());
            layout = ConfigAssembler.adjust(current, kept, combos, a2, focus);
        }
        return new DashboardConfig(null, name, datasourceId, null, layout, intents.isEmpty() ? null : intents);
    }

    static Map<String, Object> state(List<FieldSchema> schema, long rowCount, List<String> intents,
                                     List<ChartSpec> current) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            if (!f.sensitive()) {
                m.put("distinctCount", f.distinctCount());
                m.put("samples", f.samples());
            }
            fields.add(m);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("rowCount", rowCount);
        state.put("fields", fields);
        // spec 5.1：有需求才加；調整模式才列目前的圖，而且只有欄位名稱，沒有任何資料值
        if (!intents.isEmpty()) {
            state.put("intent", intents.get(intents.size() - 1));
            if (intents.size() > 1) state.put("history", intents.subList(0, intents.size() - 1));
        }
        if (current != null) {
            List<Map<String, Object>> charts = new ArrayList<>();
            for (ChartSpec s : current) {
                if (s.type().equals("table")) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("chartId", s.chartId());
                c.put("title", s.title());
                c.put("type", s.type());
                c.put("fields", UniversalTransformer.referencedFields(s.mapping()));
                charts.add(c);
            }
            state.put("current", charts);
        }
        return state;
    }

    static String describe(Combo c, String intent) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        String text = "用這組欄位畫一張圖：" + fields + "。選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
        return intent == null ? text : text + "使用者想看：「" + intent + "」。";
    }

    /** 圖型中文名取 CHART_TYPES_ZH 冒號前面那段，例如「圓餅圖」。 */
    static String keepQuestion(String intent, ChartSpec s) {
        String zh = ConfigAssembler.CHART_TYPES_ZH.get(s.type());
        String typeName = zh.substring(0, zh.indexOf('：'));
        return "使用者最新的要求：「" + intent + "」。目前有一張圖「" + s.title() + "」（" + typeName + "，欄位："
            + String.join("、", UniversalTransformer.referencedFields(s.mapping())) + "），這張圖要保留還是移除？";
    }

    static Map<String, String> criteria(Combo c) {
        Map<String, String> m = new LinkedHashMap<>();
        c.chartTypes().forEach(t -> m.put(t, ConfigAssembler.CHART_TYPES_ZH.get(t)));
        m.put("none", "這組欄位不值得畫圖");
        return m;
    }
}
```

`JevController` 還在呼叫四參數的 `analyze`，這個 Task 不用改它。

- [ ] **Step 6: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest=AnalyzeServiceTest`
Expected: PASS（AnalyzeServiceTest 4 個測試）

- [ ] **Step 7: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 137, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/resources/jev/round1-intent.json backend/src/test/resources/jev/round2-intent.json && git commit -m "feat(backend): ask rel_i/keep_<chartId> and send intent state when the user states a need" -m "<Co-Authored-By 那一行>" -- backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/resources/jev/round1-intent.json backend/src/test/resources/jev/round2-intent.json
```

---

### Task 3: `/api/jev/analyze` 的需求與驗證、儀表板存讀 intents

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`、`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`
- Test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`、`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Consumes：Task 2 的六參數 `AnalyzeService.analyze(...)`、`DashboardConfig.intents()`
- Produces（前端 Task 6 會用到）：
  - `POST /api/jev/analyze` body：`{datasourceId, intents?: string[], current?: ChartSpec[]}`；回傳 `DashboardConfig`，`intents` 是清理後的需求紀錄（沒有需求時沒有這個欄位）
  - `GET /api/dashboards/{id}`、`POST`、`PUT` 的 `DashboardConfig` 多了選填的 `intents`
  - `DashboardService.requireValidCards(List<ChartSpec>)`、`DashboardService.requireValidIntents(List<String>)`（public static）
  - `JevController.clean(List<String>) → List<String>`（package-private）

驗證順序照 spec 第 4 節的表：某一句超過 200 字 → 超過 10 句 → 有 `current` 卻沒有需求 → `current` 的卡片不合法。全部在讀資料源之前做，所以不合法的請求不會呼叫 JEV；擁有者檢查（別人的資料源回 404）不變。

- [ ] **Step 1: 寫 JevControllerTest 的測試**

`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`：在 `@Autowired JdbcClient jdbc;` 下面加：

```java
    static final String TABLE_CARD = """
        {"chartId":"c2","type":"table","title":"明細","gridPos":{"x":0,"y":4,"w":12,"h":5},"mapping":{"columns":["region"]}}""";
    static final String BAR_CARD = """
        {"chartId":"c1","type":"bar","title":"amount 依 region","gridPos":{"x":0,"y":0,"w":6,"h":4},
         "mapping":{"x":"region","value":"amount","aggregate":"sum"}}""";

    void analyzeIsRejected(String body, String message) throws Exception {
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(message));
    }
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void analyzeValidatesIntentsAndCurrentBeforeCallingJev() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String head = "{\"datasourceId\":\"" + ds + "\",";
        analyzeIsRejected(head + "\"intents\":[\"" + "營".repeat(201) + "\"]}", "想看什麼最多 200 字");
        analyzeIsRejected(head + "\"intents\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]}",
            "需求紀錄最多 10 句");
        analyzeIsRejected(head + "\"intents\":[\"  \"],\"current\":[" + TABLE_CARD + "]}", "請輸入想怎麼調整");
        analyzeIsRejected(head + "\"current\":[" + TABLE_CARD + "]}", "請輸入想怎麼調整");
        analyzeIsRejected(head + "\"intents\":[\"不要圓餅圖\"],\"current\":[null]}", "第 1 張圖表是空的");
        analyzeIsRejected(head + "\"intents\":[\"不要圓餅圖\"],\"current\":[" + TABLE_CARD.replace("\"table\"", "\"donut\"") + "]}",
            "圖表 c2：type 必須是 line、bar、pie、scatter、table、radar、heatmap、stacked_bar 其中之一");
        assertThat(JEV.bodies).isEmpty();
    }

    @Test
    void analyzeReturnsTheCleanedIntents() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        // 頭尾的半形、全形空白都去掉；空字串、只有空白、null 的句子丟掉
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceId\":\"" + ds + "\",\"intents\":[\"  各區每月營收　\",\"\",\"   \",null]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").value(contains("各區每月營收")))
            .andExpect(jsonPath("$.layout[*].type").value(contains("line", "stacked_bar", "bar", "table")));
        assertThat(JEV.bodies.get(0)).contains("\"intent\":\"各區每月營收\"").contains("\"rel_0\"");
    }

    @Test
    void adjustSendsTheCurrentChartsAndKeepsThemWhenJevAddsNothing() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceId\":\"" + ds + "\",\"intents\":[\"各區每月營收\",\"不要圓餅圖\"],\"current\":["
                    + BAR_CARD + "," + TABLE_CARD + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").value(contains("各區每月營收", "不要圓餅圖")))
            .andExpect(jsonPath("$.layout[*].chartId").value(contains("c1", "c2")))
            .andExpect(jsonPath("$.layout[1].gridPos.y").value(4));
        // round1.json 沒有 rel_i 和 keep_c1 的答案：沒有焦點欄位就不問第二輪，目前的圖全部保留
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"keep_c1\"").doesNotContain("\"keep_c2\"").doesNotContain("\"chart_count\"");
    }

    @Test
    void adjustingWithSomeoneElsesDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceId\":\"" + ds + "\",\"intents\":[\"不要圓餅圖\"],\"current\":[" + TABLE_CARD + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }
```

- [ ] **Step 2: 寫 DashboardServiceTest 的測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java`：import 區加 `import java.util.Collections;`，在 `rejects(...)` 後面加 helper：

```java
    static DashboardConfig withIntents(List<String> intents) {
        return new DashboardConfig(null, "銷售總覽", UUID.randomUUID(), null, List.of(), intents);
    }
```

在 class 最後一個 `}` 前面加：

```java
    @Test
    void intentsMustBeAtMostTenNonBlankSentencesOf200Chars() {
        rejects(withIntents(Collections.nCopies(11, "看營收")), "需求紀錄最多 10 句");
        rejects(withIntents(List.of("看營收", "  ")), "需求紀錄不能有空白的句子");
        rejects(withIntents(Arrays.asList("看營收", null)), "需求紀錄不能有空白的句子");
        rejects(withIntents(List.of("營".repeat(201))), "想看什麼最多 200 字");
        assertThatCode(() -> DashboardService.requireComplete(withIntents(Collections.nCopies(10, "營".repeat(200)))))
            .doesNotThrowAnyException();
        assertThatCode(() -> DashboardService.requireComplete(withIntents(null))).doesNotThrowAnyException();
    }
```

- [ ] **Step 3: 寫 DashboardControllerTest 的測試**

`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`：import 區加 `import static org.hamcrest.Matchers.contains;`，在 class 最後一個 `}` 前面加：

```java
    @Test
    void intentsAreSavedReadBackAndValidated() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String withIntents = json.replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\",\"不要圓餅圖\"],");

        String id = create("u1", withIntents);
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").value(contains("各區每月營收", "不要圓餅圖")));

        // 沒有需求紀錄的儀表板（跟舊資料一樣）照常打開，回應裡沒有 intents
        String plain = create("u1", json);
        mvc.perform(get("/api/dashboards/" + plain).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").doesNotExist());

        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"intents\":[\" \"],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("需求紀錄不能有空白的句子"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",",
                    "\"name\":\"銷售總覽\",\"intents\":[\"" + "營".repeat(201) + "\"],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("想看什麼最多 200 字"));
    }
```

- [ ] **Step 4: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest='JevControllerTest,DashboardServiceTest,DashboardControllerTest'`
Expected: FAIL。`analyzeValidatesIntentsAndCurrentBeforeCallingJev` 是 `Status expected:<400> but was:<200>`；`analyzeReturnsTheCleanedIntents`、`intentsAreSavedReadBackAndValidated` 是 `No value at JSON path "$.intents"`；`intentsMustBeAtMostTenNonBlankSentencesOf200Chars` 是 `Expecting code to raise a throwable`。`adjustingWithSomeoneElsesDatasourceIs404` 現在就會過（擁有者檢查本來就在），留著是為了防止之後把驗證順序改壞。

- [ ] **Step 5: DashboardService 加上卡片和需求紀錄的驗證**

`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`：

(a) `public class DashboardService {` 下一行加：

```java
    static final int MAX_INTENTS = 10;
    static final int MAX_INTENT_LENGTH = 200;
```

(b) `requireComplete` 裡從 `Set<String> chartIds` 開始到方法結束的這段：

```java
        if (c.name().isBlank()) throw new ApiException(BAD_REQUEST, "儀表板名稱不能空白");
        Set<String> chartIds = new HashSet<>();
        for (int i = 0; i < c.layout().size(); i++) {
            ChartSpec s = c.layout().get(i);
            if (s == null) throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表是空的");
            if (s.chartId() == null || s.chartId().isBlank()) {
                throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表缺少 chartId");
            }
            if (!chartIds.add(s.chartId())) throw new ApiException(BAD_REQUEST, "chartId 重複：" + s.chartId());
            String problem = cardProblem(s);
            if (problem != null) throw new ApiException(BAD_REQUEST, "圖表 " + s.chartId() + "：" + problem);
        }
    }
```

換成：

```java
        if (c.name().isBlank()) throw new ApiException(BAD_REQUEST, "儀表板名稱不能空白");
        requireValidCards(c.layout());
        requireValidIntents(c.intents());
    }

    /** 每張卡片都要 transform() 處理得了；存檔、預覽和「調整」送來的 current 共用同一套訊息。 */
    public static void requireValidCards(List<ChartSpec> layout) {
        Set<String> chartIds = new HashSet<>();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec s = layout.get(i);
            if (s == null) throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表是空的");
            if (s.chartId() == null || s.chartId().isBlank()) {
                throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表缺少 chartId");
            }
            if (!chartIds.add(s.chartId())) throw new ApiException(BAD_REQUEST, "chartId 重複：" + s.chartId());
            String problem = cardProblem(s);
            if (problem != null) throw new ApiException(BAD_REQUEST, "圖表 " + s.chartId() + "：" + problem);
        }
    }

    /** spec 4、7：需求紀錄最多 10 句、每句最多 200 字、不能是空白。 */
    public static void requireValidIntents(List<String> intents) {
        if (intents == null) return;
        for (String s : intents) {
            if (s == null || s.isBlank()) throw new ApiException(BAD_REQUEST, "需求紀錄不能有空白的句子");
            if (s.length() > MAX_INTENT_LENGTH) throw new ApiException(BAD_REQUEST, "想看什麼最多 200 字");
        }
        if (intents.size() > MAX_INTENTS) throw new ApiException(BAD_REQUEST, "需求紀錄最多 10 句");
    }
```

- [ ] **Step 6: DashboardRepository 多存 intents**

`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`：

(a) import 區加 `import com.fasterxml.jackson.annotation.JsonInclude;`

(b) `record Stored(List<ChartSpec> layout) {}` 換成：

```java
    /** config_json 的內容。intents 是 null 時不寫出來，所以舊資料和沒有需求的儀表板長得一樣。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stored(List<ChartSpec> layout, List<String> intents) {}
```

(c) `insert` 和 `update` 裡各有一行 `.param("config", write(new Stored(c.layout())))`，兩行都換成：

```java
            .param("config", write(new Stored(c.layout(), c.intents())))
```

(d) `map` 最後一行的 `rs.getObject("datasource_id", UUID.class), rs.getString("created_by"), stored.layout());` 換成：

```java
            rs.getObject("datasource_id", UUID.class), rs.getString("created_by"), stored.layout(), stored.intents());
```

資料表不用改；舊的 `config_json` 沒有 `intents`，讀出來就是 null。

- [ ] **Step 7: JevController 接收 intents 和 current**

`backend/src/main/java/jev/dashboard/jev/JevController.java`：

(a) import 區加三行：

```java
import java.util.Objects;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;
```

(b) `public record AnalyzeRequest(UUID datasourceId) {}` 換成：

```java
    /** intents：需求紀錄（選填），最後一句是這次的需求。current：有值就是調整模式，放目前預覽裡的圖（含明細表）。 */
    public record AnalyzeRequest(UUID datasourceId, List<String> intents, List<ChartSpec> current) {}
```

(c) 整個 `analyze` 方法換成下面兩個方法：

```java
    @PostMapping("/analyze")
    public DashboardConfig analyze(@RequestHeader("X-User-Id") String userId, @RequestBody AnalyzeRequest body) {
        List<String> intents = clean(body.intents());
        DashboardService.requireValidIntents(intents);
        if (body.current() != null) {
            if (intents.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "請輸入想怎麼調整");
            DashboardService.requireValidCards(body.current());
        }
        DatasourceService.Loaded loaded = datasources.load(userId, body.datasourceId());
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size(), intents, body.current());
    }

    /** spec 4：每句去掉頭尾空白（含全形空白），空白的句子直接丟掉。 */
    static List<String> clean(List<String> intents) {
        if (intents == null) return List.of();
        return intents.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
```

`highlight` 方法不動。

- [ ] **Step 8: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw -q test -Dtest='JevControllerTest,DashboardServiceTest,DashboardControllerTest'`
Expected: PASS（JevControllerTest 8、DashboardServiceTest 8、DashboardControllerTest 6，共 22 個測試）

- [ ] **Step 9: 跑全部後端測試**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 143, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java && git commit -m "feat(backend): accept intents and current in analyze, validate and persist intents" -m "<Co-Authored-By 那一行>" -- backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/main/java/jev/dashboard/dashboard/DashboardService.java backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardServiceTest.java backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java
```

---

### Task 4: 動態效果的基礎、DashboardView 的退場與進場、卡片飛移修正

**Files:**
- Create: `frontend/src/modules/dashboard/motion.css`、`frontend/src/modules/dashboard/motion.js`
- Modify: `frontend/src/modules/dashboard/DashboardView.vue`（整個換掉）、`frontend/src/App.vue:86`
- Test: `frontend/src/modules/dashboard/DashboardView.test.js`、`frontend/src/App.test.js`

**Interfaces:**
- Consumes：現有的 `api.previewData`、`api.dashboardData`、`api.highlights`、`api.updateDashboard`；grid-layout-plus 的 `GridLayout` 在第一次量到寬度後會發一次 `layout-ready`（`node_modules/grid-layout-plus/src/components/grid-layout.vue:173`），根元素是 `.vgl-layout`，沒有 `inheritAttrs: false`，所以 `:class` 會掛在 `.vgl-layout` 上
- Produces：
  - `motion.js`：`export const prefersReducedMotion = () => boolean`
  - `motion.css` 的 class，後面的 Task 會用到：`.vgl-layout.is-settled`；卡片的 `.tc-card-enter`（搭配 CSS 變數 `--tc-i`）、`.tc-card-leave`；圖表區的 `.tc-grid`、`.tc-grid.is-busy`（Task 5）；`<Transition name="tc-toast">`（Task 7）；`<TransitionGroup name="tc-intent">`（Task 6）
  - `DashboardView` 的 props 不變（`config`、`preview`）。新行為：設定換新時，消失的卡片先掛 `tc-card-leave` 120ms 才從版面拿掉；新出現的卡片掛 `tc-card-enter`，`--tc-i` 是它在新版面裡的位置；第一次顯示不做進場；留下來的卡片元件不重新掛載，資料等新資料回來才換；預覽裡只少了卡片時不重抓資料；預覽裡按「復原」放回同一張圖時，沿用它被拿掉時的資料（「實作上的決定」第 11 點）；卸載時作廢還在等退場或資料的 `load()`

`motion.css` 一次把 spec 第 10 節的轉場全寫好（包含 Task 5、6、7 才會用到的 class），「減少動態效果」的規則才能集中寫在檔案最後。grid-layout-plus 的 CSS 是 import 時才由 JS 插進 `<head>`，會排在我們的 CSS 後面，所以 10.2 那條規則一定要 `!important`；卡片的 class 前面加 `.vgl-item`，是為了蓋過 Vuetify `.v-card` 自己的 `transition`。

- [ ] **Step 1: 寫 DashboardView 的測試**

`frontend/src/modules/dashboard/DashboardView.test.js`：

(a) 檔頭的 import 換成：

```js
// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import DashboardView from './DashboardView.vue'
import { api } from './api.js'
import { prefersReducedMotion } from './motion.js'
import { vuetify } from './test-vuetify.js'
```

(b) `vi.mock('./api.js', …)` 後面加：

```js
vi.mock('./motion.js', () => ({ prefersReducedMotion: vi.fn(() => false) }))
```

(c) `GridLayout` 的 mock 補宣告 `layout-ready` 事件，`emits: ['update:layout']` 改成：

```js
    emits: ['update:layout', 'layout-ready'],
```

(d) `mountView` 後面加 helper：

```js
const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, n) => ({ chartId, type: 'bar', title: chartId, gridPos: pos(n), mapping: { x: 'region', value: 'amount' } })
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const previewOf = (...layout) => ({ name: 'n', datasourceId: 'ds1', layout })
const chartCard = (wrapper, chartId) =>
  wrapper.findAllComponents({ name: 'ChartCard' }).find((c) => c.props('spec').chartId === chartId)
```

(e) `describe` 裡的 `beforeEach` 後面加：

```js
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })
```

(f) 在 `describe` 最後（`stale-response guard` 那個測試後面）加 7 個測試：

```js
  it('config change: a removed card keeps its exit class for 120 ms before it leaves the grid; kept cards stay mounted and keep their data until new data arrives', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.previewData.mockResolvedValueOnce([{ chartId: 'c1', v: 'old' }, { chartId: 'c2', v: 'old' }, { chartId: 'c3', v: 'old' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()
    const kept = chartCard(wrapper, 'c1').vm

    let resolveNext
    api.previewData.mockImplementationOnce(() => new Promise((resolve) => { resolveNext = resolve }))
    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c4', 1), table('c3', 4)) })

    expect(wrapper.get('[data-chart-id="c2"]').classes()).toContain('tc-card-leave')
    vi.advanceTimersByTime(119)
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(true)
    expect(wrapper.find('[data-chart-id="c4"]').exists()).toBe(false)
    expect(api.previewData).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(false)
    expect(chartCard(wrapper, 'c1').vm).toBe(kept)
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', v: 'old' })

    resolveNext([{ chartId: 'c1', v: 'new' }, { chartId: 'c4', v: 'new' }, { chartId: 'c3', v: 'new' }])
    await flushPromises()
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', v: 'new' })
    expect(chartCardPayload(wrapper, 'c4').data).toEqual({ chartId: 'c4', v: 'new' })
  })

  it('reduced motion: a removed card leaves at once', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(false)
  })

  it('cards that show up later get the enter class and their layout position for the stagger; nothing enters on the first display', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()
    expect(wrapper.findAll('.tc-card-enter')).toHaveLength(0)

    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c2', 1), bar('c5', 2), bar('c6', 3), table('c3', 8)) })
    await flushPromises()
    const c5 = wrapper.get('[data-chart-id="c5"]')
    const c6 = wrapper.get('[data-chart-id="c6"]')
    expect(c5.classes()).toContain('tc-card-enter')
    expect(c5.element.style.getPropertyValue('--tc-i')).toBe('2')
    expect(c6.element.style.getPropertyValue('--tc-i')).toBe('3')
    expect(wrapper.get('[data-chart-id="c1"]').classes()).not.toContain('tc-card-enter')
  })

  it('adds is-settled only on the frame after GridLayout reports layout-ready', async () => {
    let frame
    vi.stubGlobal('requestAnimationFrame', (cb) => {
      frame = cb
      return 1
    })
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), table('c3', 4)), preview: true })
    await flushPromises()
    const grid = wrapper.get('.grid-layout-stub')
    expect(grid.classes()).not.toContain('is-settled')

    wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('layout-ready')
    await nextTick()
    expect(grid.classes()).not.toContain('is-settled')

    frame()
    await nextTick()
    expect(grid.classes()).toContain('is-settled')
  })

  it('preview: a config that only drops cards does not fetch data again', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1' })
  })

  it('preview: a card that comes back unchanged (復原) reuses its data without fetching; the same chartId with a different chart fetches', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1', v: 1 }, { chartId: 'c2', v: 2 }, { chartId: 'c3', v: 3 }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c2').data).toEqual({ chartId: 'c2', v: 2 })

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    const other = { ...bar('c2', 1), mapping: { x: 'product', value: 'qty' } } // 同一個 chartId，但已經是另一張圖
    await wrapper.setProps({ config: previewOf(bar('c1', 0), other, table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(2)
  })

  it('unmounting during the exit wait cancels the pending load', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c4', 1), table('c3', 4)) })
    wrapper.unmount()
    vi.advanceTimersByTime(120)
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
  })
```

- [ ] **Step 2: 寫 App 的測試**

`frontend/src/App.test.js`：在 `describe` 最後加：

```js
  it('re-creates DashboardView when another saved dashboard is opened (keyed by id), so cards never slide over from the previous one', async () => {
    api.listDashboards.mockResolvedValue([{ id: 'd1', name: 'Dash One' }, { id: 'd2', name: 'Dash Two' }])
    api.getDashboard.mockImplementation(async (id) => ({ id, name: id, datasourceId: 'ds1', layout: [] }))
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()
    const item = (name) => wrapper.findAll('.v-list-item').find((w) => w.text().includes(name))

    await item('Dash One').trigger('click')
    await flushPromises()
    const first = wrapper.findComponent({ name: 'DashboardView' }).vm

    await item('Dash Two').trigger('click')
    await flushPromises()
    const second = wrapper.findComponent({ name: 'DashboardView' })
    expect(second.props('config').id).toBe('d2')
    expect(second.vm).not.toBe(first)
  })
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd frontend && npm test -- src/modules/dashboard/DashboardView.test.js src/App.test.js`
Expected: FAIL。`DashboardView.test.js` 整個載入失敗（`./motion.js` 還不存在）；`App.test.js` 新的測試失敗（同一個 `DashboardView` 被重用，`expected … not to be …`），原本 5 個照樣通過。

- [ ] **Step 4: 建 motion.js**

`frontend/src/modules/dashboard/motion.js`：

```js
// spec 10.1、10.9：JS 要判斷使用者有沒有開「減少動態效果」都用這個
export const prefersReducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches
```

- [ ] **Step 5: 建 motion.css**

`frontend/src/modules/dashboard/motion.css`：

```css
/* spec 10.1：儀表板模組共用的動態設定。模組不能依賴模組以外的檔案，所以放在這裡。 */
:root {
  --tc-ease-out: cubic-bezier(0.23, 1, 0.32, 1);
  --tc-dur-1: 120ms; /* 退場、淡出 */
  --tc-dur-2: 160ms; /* 提示、需求紀錄、等待狀態 */
  --tc-dur-3: 200ms; /* 卡片進場 */
}

/* 10.2：GridLayout 量好寬度的下一幀才加 is-settled，在那之前不做位移轉場，打開、切換儀表板時卡片不會飛過畫面 */
.vgl-layout:not(.is-settled),
.vgl-layout:not(.is-settled) .vgl-item { transition: none !important; }

/* 10.4：調整後新增、按「復原」放回的卡片淡入加位移，依版面位置每張晚 30ms，最多晚 150ms */
.vgl-item .tc-card-enter {
  animation: tc-card-enter var(--tc-dur-3) var(--tc-ease-out) calc(min(var(--tc-i, 0), 5) * 30ms) both;
}

@keyframes tc-card-enter {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}

/* 10.5：被移除的卡片退場 */
.vgl-item .tc-card-leave {
  opacity: 0;
  transform: scale(0.98);
  transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out);
}

/* 10.6：等 JEV 回應時圖表區變淡 */
.tc-grid { transition: opacity var(--tc-dur-2) var(--tc-ease-out); }
.tc-grid.is-busy { opacity: 0.6; }

/* 10.7：「復原」提示，出現 160ms、關閉 120ms */
.tc-toast-enter-active {
  transition: opacity var(--tc-dur-2) var(--tc-ease-out), transform var(--tc-dur-2) var(--tc-ease-out);
}
.tc-toast-leave-active {
  transition: opacity var(--tc-dur-1) var(--tc-ease-out), transform var(--tc-dur-1) var(--tc-ease-out);
}
.tc-toast-enter-from,
.tc-toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* 10.8：需求紀錄新增一句 */
.tc-intent-enter-active {
  transition: opacity var(--tc-dur-2) var(--tc-ease-out), transform var(--tc-dur-2) var(--tc-ease-out);
}
.tc-intent-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

/* 10.9：減少動態效果時全部瞬間完成，補位也不滑。JS 那邊用 motion.js 的 prefersReducedMotion()。 */
@media (prefers-reduced-motion: reduce) {
  .vgl-layout,
  .vgl-layout .vgl-item,
  .vgl-item .tc-card-leave,
  .tc-grid,
  .tc-toast-enter-active,
  .tc-toast-leave-active,
  .tc-intent-enter-active {
    transition: none !important;
  }

  .vgl-item .tc-card-enter { animation: none !important; }
}
```

- [ ] **Step 6: 改 DashboardView.vue**

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
})

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

const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))

let loadSeq = 0
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
  // spec 10.4：第一次顯示不做進場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : [])
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
  highlightById.value = {}
  error.value = ''
  loadError.value = ''
  // 預覽裡只是少了卡片時，剩下的卡片都有資料了，不必再打 API（spec 3.2：「✕」不呼叫 API）
  if (props.preview && next.every((s) => dataById.value[s.chartId])) return
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

// spec 10.2：套件量好寬度、卡片就定位的下一幀，才打開位移轉場
function onLayoutReady() {
  requestAnimationFrame(() => (settled.value = true))
}

async function saveLayout() {
  if (props.preview) return
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  const next = { ...props.config, layout: props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] })) }
  error.value = ''
  await api.updateDashboard(props.config.id, next).catch((e) => (error.value = e.message))
}
</script>

<template>
  <v-alert v-if="loadError || error" type="error" class="mb-4">{{ loadError || error }}</v-alert>
  <v-alert v-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <GridLayout
    v-if="!loadError"
    v-model:layout="layout"
    :class="{ 'is-settled': settled }"
    :col-num="12"
    :row-height="80"
    :is-draggable="!preview"
    :is-resizable="!preview"
    @layout-ready="onLayoutReady"
  >
    <GridItem
      v-for="(item, index) in layout"
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
        :data="dataById[item.i]"
        :highlight="highlightById[item.i]"
        :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
        :style="entering.has(item.i) ? { '--tc-i': index } : undefined"
      />
    </GridItem>
  </GridLayout>
</template>
```

- [ ] **Step 7: App.vue 加 key**

`frontend/src/App.vue` 第 86 行：

```vue
        <DashboardView v-if="current" :config="current" />
```

換成：

```vue
        <!-- spec 10.2：換一份儀表板就整個重建，卡片不會從上一份的位置滑過來；預覽裡的 DashboardView 不加 key -->
        <DashboardView v-if="current" :key="current.id" :config="current" />
```

- [ ] **Step 8: 跑測試，確認通過**

Run: `cd frontend && npm test -- src/modules/dashboard/DashboardView.test.js src/App.test.js`
Expected: PASS（`Tests  22 passed (22)`：DashboardView 16、App 6）

- [ ] **Step 9: 跑全部前端測試和 build**

Run: `cd frontend && npm test && npm run build`
Expected: `Tests  60 passed (60)`，build 成功（`✓ built in …`）

- [ ] **Step 10: Commit**

```bash
git add frontend/src/modules/dashboard/motion.css frontend/src/modules/dashboard/motion.js frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js frontend/src/App.vue frontend/src/App.test.js && git commit -m "feat(frontend): motion tokens, card exit/enter by chartId, keep data of kept cards, no fly-in on open/switch" -m "<Co-Authored-By 那一行>" -- frontend/src/modules/dashboard/motion.css frontend/src/modules/dashboard/motion.js frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js frontend/src/App.vue frontend/src/App.test.js
```

---

### Task 5: 卡片的「✕」和「符合需求」、等待狀態、「依據」

**Files:**
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`（整個換掉）、`frontend/src/modules/dashboard/DashboardView.vue`（整個換掉）
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`、`frontend/src/modules/dashboard/DashboardView.test.js`

**Interfaces:**
- Consumes：Task 4 的 `DashboardView.vue`、`motion.css` 的 `.tc-grid`、`.is-busy`
- Produces（Task 6、7 會用到）：
  - `<ChartCard :spec :data :highlight :removable :remove-disabled @remove />`：`removable` 而且不是明細表才有「✕」；`spec.options?.focus` 是 true 時顯示「符合需求」
  - `<DashboardView :config :preview :busy @remove="(chartId) => …" />`：預覽裡每張卡片 `removable`；`busy` 時圖表區加 `.is-busy` 和 `aria-busy="true"`，「✕」停用；已存的儀表板（`preview` 是 false）有 `config.intents` 時，圖表上方顯示「依據：…」

- [ ] **Step 1: 寫 ChartCard 的測試**

`frontend/src/modules/dashboard/ChartCard.test.js`：在 `describe` 最後加：

```js
  it('✕: shown on removable chart cards only (not on the table, not when not removable), named 移除這張圖, emits remove', async () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true })
    await card.get('button[aria-label="移除這張圖"]').trigger('click')
    expect(card.emitted('remove')).toHaveLength(1)

    expect(mountCard({ spec, data: null, highlight: null }).find('button[aria-label="移除這張圖"]').exists()).toBe(false)
    const tableCard = mountCard({ spec: { ...spec, type: 'table' }, data: null, highlight: null, removable: true })
    expect(tableCard.find('button[aria-label="移除這張圖"]').exists()).toBe(false)
  })

  it('✕ is disabled while the preview waits for JEV', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true, removeDisabled: true })
    expect(card.get('button[aria-label="移除這張圖"]').attributes('disabled')).toBeDefined()
  })

  it('shows 符合需求 when options.focus is true, and not otherwise', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    expect(mountCard({ spec: { ...spec, options: { focus: true } }, data: null, highlight: null }).text()).toContain('符合需求')
    expect(mountCard({ spec, data: null, highlight: null }).text()).not.toContain('符合需求')
    expect(mountCard({ spec: { ...spec, options: {} }, data: null, highlight: null }).text()).not.toContain('符合需求')
  })
```

- [ ] **Step 2: 寫 DashboardView 的測試**

`frontend/src/modules/dashboard/DashboardView.test.js`：`ChartCard` 的 mock 補宣告新的 props 和事件，`props: ['spec', 'data', 'highlight'],` 換成：

```js
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled'],
    emits: ['remove'],
```

在 `describe` 最後加：

```js
  it('preview: cards get ✕ (disabled while busy), the grid area says aria-busy, and a card’s remove is passed up with its chartId', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: makeConfig(), preview: true, busy: true })
    await flushPromises()

    const cards = wrapper.findAllComponents({ name: 'ChartCard' })
    expect(cards.map((c) => [c.props('removable'), c.props('removeDisabled')])).toEqual([[true, true], [true, true]])
    expect(wrapper.get('.tc-grid').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.tc-grid').classes()).toContain('is-busy')

    await wrapper.setProps({ busy: false })
    expect(wrapper.get('.tc-grid').attributes('aria-busy')).toBeUndefined()
    expect(cards[0].props('removeDisabled')).toBe(false)

    cards[0].vm.$emit('remove')
    expect(wrapper.emitted('remove')).toEqual([['c1']])
  })

  it('saved dashboards: no ✕, and the intents show as 依據 joined by →; previews and dashboards without intents show no 依據', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue([])
    const saved = mountView({ config: { ...makeConfig(), intents: ['各區每月營收', '不要圓餅圖'] }, preview: false })
    await flushPromises()
    expect(saved.text()).toContain('依據：各區每月營收 → 不要圓餅圖')
    expect(saved.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([false, false])

    const plain = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    expect(plain.text()).not.toContain('依據')

    api.previewData.mockResolvedValue([])
    const inPreview = mountView({ config: { ...makeConfig(), intents: ['各區每月營收'] }, preview: true })
    await flushPromises()
    expect(inPreview.text()).not.toContain('依據')
  })
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd frontend && npm test -- src/modules/dashboard/ChartCard.test.js src/modules/dashboard/DashboardView.test.js`
Expected: FAIL，新加的 5 個測試失敗（例如 `Unable to get button[aria-label="移除這張圖"]`、`Unable to get .tc-grid`），原本的測試照樣通過

- [ ] **Step 4: 改 ChartCard.vue**

`frontend/src/modules/dashboard/ChartCard.vue` 整個換成：

```vue
<script setup>
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
  // 預覽裡才有「✕」；等 JEV 回應時停用
  removable: { type: Boolean, default: false },
  removeDisabled: { type: Boolean, default: false },
})
const emit = defineEmits(['remove'])

const SEVERITY_COLOR = { 正常波動: 'success', 值得注意: 'info', 需要關注: 'warning', 嚴重警示: 'error' }

const ready = computed(() => props.data && !props.data.error)
const unsupportedType = computed(() => ready.value && !Object.hasOwn(toOption, props.spec.type))
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' && Object.hasOwn(toOption, props.spec.type)
    ? toOption[props.spec.type](props.data, props.spec.mapping)
    : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag d-flex align-center text-subtitle-1">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <v-chip v-if="spec.options?.focus" size="small" color="primary" class="mr-1">符合需求</v-chip>
      <v-chip v-if="highlight" size="small" :color="SEVERITY_COLOR[highlight.severity]">
        {{ highlight.severity }}
      </v-chip>
      <!-- grid-layout-plus 的 dragIgnoreFrom 預設是 'a, button'，按「✕」不會被當成拖拉 -->
      <v-btn
        v-if="removable && spec.type !== 'table'"
        icon="mdi-close"
        size="x-small"
        variant="text"
        class="tc-remove ml-1"
        aria-label="移除這張圖"
        :disabled="removeDisabled"
        @click="emit('remove')"
      />
    </v-card-title>
    <v-card-text class="flex-grow-1 card-body">
      <div v-if="!data" class="text-medium-emphasis">載入中…</div>
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-alert v-else-if="unsupportedType" type="warning" density="compact">
        不支援的圖表類型：{{ spec.type }}
      </v-alert>
      <template v-else-if="table">
        <div v-if="data.truncated" class="text-caption text-medium-emphasis">僅顯示前 500 筆</div>
        <v-table density="compact" fixed-header height="100%">
          <thead>
            <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in table.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
            </tr>
          </tbody>
        </v-table>
      </template>
      <VChart v-else :option="option" autoresize />
    </v-card-text>
  </v-card>
</template>

<style scoped>
.card-drag { cursor: move; }
.card-body { min-height: 0; }
/* spec 10.5：靜止時就看得到，hover 變主文字色，按下時往下 1px；x-small 的圖示按鈕是 32×32，超過 24×24 */
.tc-remove {
  color: var(--tc-muted, rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity)));
  transition: none;
}
.tc-remove:hover { color: var(--tc-text, rgba(var(--v-theme-on-surface), var(--v-high-emphasis-opacity))); }
.tc-remove:active { transform: translateY(1px); }
</style>
```

- [ ] **Step 5: 改 DashboardView.vue**

`frontend/src/modules/dashboard/DashboardView.vue` 整個換成（跟 Task 4 比，只多了 `busy` prop、`remove` 事件、「依據」和包住 `GridLayout` 的 `.tc-grid`）：

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
const emit = defineEmits(['remove'])

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

const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))

let loadSeq = 0
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
  // spec 10.4：第一次顯示不做進場；之後才出現的卡片（調整新增的、按「復原」放回的）才進場
  entering.value = new Set(shown.length ? [...ids].filter((id) => !shown.includes(id)) : [])
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
  highlightById.value = {}
  error.value = ''
  loadError.value = ''
  // 預覽裡只是少了卡片時，剩下的卡片都有資料了，不必再打 API（spec 3.2：「✕」不呼叫 API）
  if (props.preview && next.every((s) => dataById.value[s.chartId])) return
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

// spec 10.2：套件量好寬度、卡片就定位的下一幀，才打開位移轉場
function onLayoutReady() {
  requestAnimationFrame(() => (settled.value = true))
}

async function saveLayout() {
  if (props.preview) return
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  const next = { ...props.config, layout: props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] })) }
  error.value = ''
  await api.updateDashboard(props.config.id, next).catch((e) => (error.value = e.message))
}
</script>

<template>
  <v-alert v-if="loadError || error" type="error" class="mb-4">{{ loadError || error }}</v-alert>
  <v-alert v-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <!-- spec 3.3：已存的儀表板顯示這份設定是依據哪些需求做的 -->
  <div v-if="!preview && config.intents?.length" class="text-caption text-medium-emphasis mb-2">
    依據：{{ config.intents.join(' → ') }}
  </div>
  <div class="tc-grid" :class="{ 'is-busy': busy }" :aria-busy="busy || undefined">
    <GridLayout
      v-if="!loadError"
      v-model:layout="layout"
      :class="{ 'is-settled': settled }"
      :col-num="12"
      :row-height="80"
      :is-draggable="!preview"
      :is-resizable="!preview"
      @layout-ready="onLayoutReady"
    >
      <GridItem
        v-for="(item, index) in layout"
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
          :data="dataById[item.i]"
          :highlight="highlightById[item.i]"
          :removable="preview"
          :remove-disabled="busy"
          :class="{ 'tc-card-leave': leaving.has(item.i), 'tc-card-enter': entering.has(item.i) }"
          :style="entering.has(item.i) ? { '--tc-i': index } : undefined"
          @remove="emit('remove', item.i)"
        />
      </GridItem>
    </GridLayout>
  </div>
</template>
```

- [ ] **Step 6: 跑測試，確認通過**

Run: `cd frontend && npm test -- src/modules/dashboard/ChartCard.test.js src/modules/dashboard/DashboardView.test.js`
Expected: PASS（`Tests  28 passed (28)`：ChartCard 10、DashboardView 18）

- [ ] **Step 7: 跑全部前端測試和 build**

Run: `cd frontend && npm test && npm run build`
Expected: `Tests  65 passed (65)`，build 成功

- [ ] **Step 8: Commit**

```bash
git add frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js && git commit -m "feat(frontend): card remove button, focus chip, busy state and intents caption" -m "<Co-Authored-By 那一行>" -- frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/ChartCard.test.js frontend/src/modules/dashboard/DashboardView.vue frontend/src/modules/dashboard/DashboardView.test.js
```

---

### Task 6: 需求輸入框、預覽的需求紀錄與「調整」

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js:29`、`frontend/src/modules/dashboard/AnalyzeButton.vue`（整個換掉）
- Test: `frontend/src/modules/dashboard/api.test.js`、`frontend/src/modules/dashboard/AnalyzeButton.test.js`

**Interfaces:**
- Consumes：Task 3 的 `POST /api/jev/analyze`（`{datasourceId, intents?, current?}`，回傳的 `DashboardConfig.intents` 是清理後的需求紀錄）；Task 5 的 `<DashboardView :config :preview :busy />`；`motion.css` 的 `tc-intent`
- Produces（Task 7 會接著改同一個檔）：
  - `api.analyze(datasourceId, intents?, current?)`：`intents`、`current` 沒給時 `JSON.stringify` 會略過，body 跟現在一樣只有 `{"datasourceId": …}`
  - `AnalyzeButton` 的 state：`preview`（預覽的 `DashboardConfig`）、`history`（`preview.intents ?? []`）、`adjustText`、`adjusting`、`nameEdited`、`adjustSeq`；函式 `analyze()`、`adjust()`、`save()`，以及 `watch(open)`（關掉預覽時作廢還在等的調整、清掉錯誤）

存檔照舊送整個 `preview`，伺服器回傳的 `intents` 就在裡面，`save()` 不用改；已存儀表板拖拉後的 `PUT` 也是把整份 `config` 展開再送，`intents` 會跟著保留。

`AnalyzeButton.test.js` 的 `DashboardView` mock 要補宣告 `busy` prop，其餘既有的 mock 和 5 個 `it` 不動。既有測試會用 `wrapper.get('button')` 找「分析」按鈕、用 `findComponent({ name: 'VTextField' })` 找名稱欄位：新的輸入框是 `v-textarea`（元件名 `VTextarea`，裡面沒有按鈕），排在「分析」前面也不影響；「想怎麼調整？」是 `v-text-field`，但在元件樹裡排在工具列的名稱欄位後面。Vue Test Utils 會把 `<TransitionGroup>` 換成 `<transition-group-stub>`，所以測試用 `.tc-intents li` 找需求紀錄，不用 `ol li`。

- [ ] **Step 1: 寫 api 的測試**

`frontend/src/modules/dashboard/api.test.js`：在 `describe` 最後加：

```js
  it('analyze sends intents and current only when they are given', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1', ['各區每月營收'], [{ chartId: 'c1' }])
    expect(fetch.mock.calls[0][1].body).toBe(
      JSON.stringify({ datasourceId: 'ds1', intents: ['各區每月營收'], current: [{ chartId: 'c1' }] }),
    )

    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1', ['各區每月營收'])
    expect(fetch.mock.calls[1][1].body).toBe('{"datasourceId":"ds1","intents":["各區每月營收"]}')
  })
```

- [ ] **Step 2: 寫 AnalyzeButton 的測試**

`frontend/src/modules/dashboard/AnalyzeButton.test.js`：

(a) `DashboardView` 的 mock 裡 `props: { config: Object, preview: Boolean },` 換成：

```js
    props: { config: Object, preview: Boolean, busy: Boolean },
```

(b) `mountButton` 後面加 helper：

```js
const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, title, n, options) => ({
  chartId, type: 'bar', title, gridPos: pos(n), mapping: { x: 'region', value: 'amount' }, ...(options && { options }),
})
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const view = (wrapper) => wrapper.findComponent({ name: 'DashboardView' })
const field = (wrapper, label) => wrapper.findAllComponents({ name: 'VTextField' }).find((f) => f.props('label') === label)
const button = (text) => byText(document.body, 'button', text)
const historyItems = () => [...document.body.querySelectorAll('.tc-intents li')].map((li) => li.textContent.trim())

async function openPreview() {
  const wrapper = mountButton('ds1')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  return wrapper
}

async function adjustWith(wrapper, sentence) {
  await field(wrapper, '想怎麼調整？').find('input').setValue(sentence)
  button('調整').click()
  await flushPromises()
}
```

(c) 在 `describe` 最後加 6 個測試：

```js
  it('left panel: an optional box above 分析 with label, hint and a 200-character counter; 分析 sends the trimmed sentence as the first intent', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [] })

    const blank = mountButton('ds1')
    await blank.findComponent({ name: 'VTextarea' }).find('textarea').setValue('   ')
    await blank.get('button').trigger('click')
    await flushPromises()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1')
    expect(document.body.querySelector('.tc-history').style.display).toBe('none') // 沒有需求紀錄就不顯示
    expect(document.body.textContent).not.toContain('找不到跟需求直接相關的欄位')
    blank.unmount()
    document.body.innerHTML = ''

    const wrapper = mountButton('ds1')
    const box = wrapper.findComponent({ name: 'VTextarea' })
    expect(box.props('label')).toBe('想看什麼？（選填）')
    expect(box.props('placeholder')).toBe('例如：各區每月營收、哪個產品退貨最多')
    expect(wrapper.text()).toContain('這段文字會送給 JEV 做分析')
    expect(wrapper.text()).toContain('0 / 200')
    expect(box.find('textarea').attributes('maxlength')).toBe('200')

    await box.find('textarea').setValue('  各區每月營收  ')
    expect(wrapper.text()).toContain('10 / 200')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收'])
  })

  it('調整: disabled while empty or busy; sends the history plus the new sentence with the current charts; on success shows the new result, appends the sentence and clears the box', async () => {
    const first = { name: 'A 總覽', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    const second = {
      name: 'A 總覽',
      datasourceId: 'ds1',
      intents: ['各區每月營收', '不要圓餅圖'],
      layout: [bar('c1', 'A', 0, { focus: true }), bar('c3', 'C', 1, { focus: true }), table('c2', 4)],
    }
    let resolveAdjust
    api.analyze
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveAdjust = resolve }))

    const wrapper = await openPreview()
    expect(document.body.querySelector('.tc-history').style.display).toBe('')
    expect(historyItems()).toEqual(['各區每月營收'])
    expect(field(wrapper, '想怎麼調整？').props('placeholder')).toBe('例如：不要圓餅圖、加各產品的數量')
    expect(field(wrapper, '想怎麼調整？').find('input').attributes('maxlength')).toBe('200')
    expect(button('調整').disabled).toBe(true)

    await field(wrapper, '想怎麼調整？').find('input').setValue('  不要圓餅圖 ')
    expect(button('調整').disabled).toBe(false)
    button('調整').click()
    await flushPromises()

    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收', '不要圓餅圖'], first.layout)
    expect(button('調整').disabled).toBe(true)
    expect(button('調整').classList.contains('v-btn--loading')).toBe(true)
    expect(field(wrapper, '想怎麼調整？').props('disabled')).toBe(true)
    expect(view(wrapper).props('busy')).toBe(true)

    resolveAdjust(second)
    await flushPromises()
    expect(view(wrapper).props('config')).toEqual(second)
    expect(view(wrapper).props('busy')).toBe(false)
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('')
    expect(historyItems()).toEqual(['各區每月營收', '不要圓餅圖'])
  })

  it('調整 failure keeps the preview as it was and shows the error inside the preview', async () => {
    const first = { name: 'A 總覽', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))

    const wrapper = await openPreview()
    await adjustWith(wrapper, '不要圓餅圖')

    expect(view(wrapper).props('config')).toEqual(first)
    expect(document.body.textContent).toContain('JEV 呼叫失敗（HTTP 500）')
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('不要圓餅圖')
    expect(button('調整').disabled).toBe(false)
  })

  it('keeps a name the user typed; otherwise takes the new default name', async () => {
    const layout = [bar('c1', 'A', 0, { focus: true }), table('c2', 4)]
    api.analyze
      .mockResolvedValueOnce({ name: 'A 總覽', datasourceId: 'ds1', intents: ['一'], layout })
      .mockResolvedValueOnce({ name: 'B 總覽', datasourceId: 'ds1', intents: ['一', '二'], layout })
      .mockResolvedValueOnce({ name: 'C 總覽', datasourceId: 'ds1', intents: ['一', '二', '三'], layout })

    const wrapper = await openPreview()
    await adjustWith(wrapper, '二')
    expect(view(wrapper).props('config').name).toBe('B 總覽')

    await field(wrapper, '儀表板名稱').find('input').setValue('我的版本')
    await adjustWith(wrapper, '三')
    expect(view(wrapper).props('config').name).toBe('我的版本')
  })

  it('warns when there is a history but no chart matches it, and drops the warning once one does', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0), table('c2', 4)] })
      .mockResolvedValueOnce({
        name: 'p',
        datasourceId: 'ds1',
        intents: ['各區每月營收', '看各產品'],
        layout: [bar('c1', 'A', 0), bar('c3', 'C', 1, { focus: true }), table('c2', 4)],
      })

    const wrapper = await openPreview()
    expect(document.body.textContent).toContain('找不到跟需求直接相關的欄位，先顯示一般的分析結果。')

    await adjustWith(wrapper, '看各產品')
    expect(document.body.textContent).not.toContain('找不到跟需求直接相關的欄位')
  })

  it('at 10 sentences the adjust box is disabled and says 需求紀錄最多 10 句', async () => {
    const intents = Array.from({ length: 10 }, (_, i) => `第 ${i + 1} 句`)
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents, layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })

    const wrapper = await openPreview()
    expect(field(wrapper, '想怎麼調整？').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('需求紀錄最多 10 句')
    expect(button('調整').disabled).toBe(true)
  })
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd frontend && npm test -- src/modules/dashboard/api.test.js src/modules/dashboard/AnalyzeButton.test.js`
Expected: FAIL，新加的 7 個測試失敗（例如 api 的 body 只有 `{"datasourceId":"ds1"}`、`Cannot call props on an empty VueWrapper`），原本 11 個照樣通過

- [ ] **Step 4: api.analyze 帶上需求和目前的圖**

`frontend/src/modules/dashboard/api.js` 第 29 行：

```js
  analyze: (datasourceId) => request('POST', '/api/jev/analyze', { datasourceId }),
```

換成：

```js
  // intents、current 沒給時 JSON.stringify 會略過，body 跟原本一樣只有 datasourceId
  analyze: (datasourceId, intents, current) => request('POST', '/api/jev/analyze', { datasourceId, intents, current }),
```

- [ ] **Step 5: 改 AnalyzeButton.vue**

`frontend/src/modules/dashboard/AnalyzeButton.vue` 整個換成：

```vue
<script setup>
import { computed, ref, watch } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'
import './motion.css'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const MAX_INTENTS = 10 // spec 3.2：需求紀錄最多 10 句

const intent = ref('')
const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const adjustText = ref('')
const adjusting = ref(false)
const nameEdited = ref(false)

const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())
const history = computed(() => preview.value?.intents ?? [])
const historyFull = computed(() => history.value.length >= MAX_INTENTS)
const noFocus = computed(() => history.value.length > 0 && !preview.value.layout.some((s) => s.options?.focus))
const canAdjust = computed(() => !!adjustText.value.trim() && !adjusting.value && !historyFull.value)

// 關掉預覽之後才回來的調整結果直接丟掉
let adjustSeq = 0

async function analyze() {
  busy.value = true
  error.value = ''
  const first = intent.value.trim()
  try {
    // 沒填需求就跟原本一樣只送 datasourceId
    preview.value = await (first ? api.analyze(props.datasourceId, [first]) : api.analyze(props.datasourceId))
    nameEdited.value = false
    adjustText.value = ''
    open.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function adjust() {
  if (!canAdjust.value) return
  const seq = ++adjustSeq
  adjusting.value = true
  error.value = ''
  try {
    const next = await api.analyze(preview.value.datasourceId, [...history.value, adjustText.value.trim()],
      preview.value.layout)
    if (seq !== adjustSeq) return
    // spec 3.2：使用者改過名稱就保留他改的，沒改過就用新結果的預設名稱
    preview.value = { ...next, name: nameEdited.value ? preview.value.name : next.name }
    adjustText.value = ''
  } catch (e) {
    if (seq === adjustSeq) error.value = e.message
  } finally {
    if (seq === adjustSeq) adjusting.value = false
  }
}

// 關掉預覽（取消、存檔、Esc）時，還在等的調整作廢；錯誤訊息也清掉，才不會跑到左側面板
watch(open, (isOpen) => {
  if (isOpen) return
  adjustSeq++
  adjusting.value = false
  error.value = ''
})

async function save() {
  if (saving.value || nameInvalid.value) return
  saving.value = true
  try {
    const saved = await api.createDashboard(preview.value)
    open.value = false
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-textarea
    v-model="intent"
    label="想看什麼？（選填）"
    placeholder="例如：各區每月營收、哪個產品退貨最多"
    hint="這段文字會送給 JEV 做分析"
    persistent-hint
    counter="200"
    persistent-counter
    maxlength="200"
    rows="2"
    density="compact"
    class="mb-2"
  />
  <v-btn color="primary" block :loading="busy" :disabled="!datasourceId" @click="analyze">分析</v-btn>
  <v-alert v-if="error && !open" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
  <v-dialog v-model="open" fullscreen>
    <v-card>
      <v-toolbar title="儀表板預覽">
        <v-text-field
          v-if="preview"
          v-model="preview.name"
          label="儀表板名稱"
          density="compact"
          hide-details="auto"
          :error-messages="nameInvalid ? ['請輸入儀表板名稱'] : []"
          class="mx-4"
          style="max-width: 320px"
          @update:model-value="nameEdited = true"
        />
        <v-btn @click="open = false">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          class="mr-2"
          :loading="saving"
          :disabled="nameInvalid || saving"
          @click="save"
        >存為我的儀表板</v-btn>
      </v-toolbar>
      <v-card-text>
        <v-alert v-if="error" type="error" density="compact" class="mb-2">{{ error }}</v-alert>
        <template v-if="preview">
          <v-alert v-if="noFocus" type="info" density="compact" class="mb-2">找不到跟需求直接相關的欄位，先顯示一般的分析結果。</v-alert>
          <!-- 用 v-show 不用 v-if：TransitionGroup 一直掛著，第一句出現時才有淡入 -->
          <div v-show="history.length" class="tc-history mb-2">
            <div class="text-caption text-medium-emphasis">需求紀錄</div>
            <!-- spec 10.8：新的一句淡入；key 用序號，舊的句子不會重播 -->
            <TransitionGroup name="tc-intent" tag="ol" class="tc-intents pl-5">
              <li v-for="(sentence, i) in history" :key="i">{{ sentence }}</li>
            </TransitionGroup>
          </div>
          <div class="d-flex align-start ga-2 mb-2">
            <v-text-field
              v-model="adjustText"
              label="想怎麼調整？"
              placeholder="例如：不要圓餅圖、加各產品的數量"
              maxlength="200"
              density="compact"
              :disabled="historyFull || adjusting"
              :messages="historyFull ? ['需求紀錄最多 10 句'] : []"
            />
            <v-btn color="primary" :loading="adjusting" :disabled="!canAdjust" @click="adjust">調整</v-btn>
          </div>
          <DashboardView :config="preview" preview :busy="adjusting" />
        </template>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
```

- [ ] **Step 6: 跑測試，確認通過**

Run: `cd frontend && npm test -- src/modules/dashboard/api.test.js src/modules/dashboard/AnalyzeButton.test.js`
Expected: PASS（`Tests  18 passed (18)`：api 7、AnalyzeButton 11）

- [ ] **Step 7: 跑全部前端測試和 build**

Run: `cd frontend && npm test && npm run build`
Expected: `Tests  72 passed (72)`，build 成功

- [ ] **Step 8: Commit**

```bash
git add frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js && git commit -m "feat(frontend): intent box before analyze, intent history and adjust in the preview" -m "<Co-Authored-By 那一行>" -- frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js
```

---

### Task 7: 「✕」移除後重新排版與「復原」提示

**Files:**
- Modify: `frontend/src/modules/dashboard/AnalyzeButton.vue`（整個換掉）
- Test: `frontend/src/modules/dashboard/AnalyzeButton.test.js`

**Interfaces:**
- Consumes：Task 5 的 `<DashboardView @remove="(chartId) => …" />`；Task 6 的 `AnalyzeButton.vue`；`motion.css` 的 `tc-toast`
- Produces：`remove(chartId)`、`undo()`（沒有提示時什麼都不做）、`relayout(layout)`（spec 6.6，跟後端 `layOut` 同一套）、提示 state `toast = { spec, index } | null`；提示的 DOM 是 `.tc-toast-region[role="status"]` 裡的 `.tc-toast`，外框是 `<v-card>` 的子元素、排在 `<v-card-text>` 後面；卸載時清掉提示的計時器

流程：按「✕」→ 前端拿掉那張、照 6.6 重排，把新的 `preview` 交給 `DashboardView`，由它播 120ms 退場再補位（Task 4）→ 下方出現提示。按「復原」→ 放回移除前的位置、重排，`DashboardView` 把它當成新卡片播進場，資料沿用拿掉前的（Task 4），不重打 API。提示只有一個：再移除一張就取代舊的；按「調整」或關掉預覽就關掉。`role="status"` 放在一直存在的外框上，提示內容進出時螢幕報讀器才念得到。

提示要固定在預覽畫面的下方中央，預覽捲動時也不動。全螢幕對話框的 `.v-overlay__content` 有 `contain: layout`，本身也是捲動的容器，放在 `<v-card-text>` 裡的 `position: fixed` 會跟著內容捲走。所以對話框加 `scrollable`，讓捲動的是 `<v-card-text>`；提示的外框放在 `<v-card>` 底下、`<v-card-text>` 後面，用 `position: absolute; bottom: 24px`（`.v-card` 本來就是 `position: relative`）。外框還在對話框裡，「復原」也還在對話框的焦點範圍內。提示退場那 120ms 裡「復原」按鈕還在畫面上，所以 `undo()` 在提示已經關掉時直接跳過。

- [ ] **Step 1: 寫會失敗的測試**

`frontend/src/modules/dashboard/AnalyzeButton.test.js`：

(a) `DashboardView` 的 mock 補宣告 `remove` 事件，`props: { config: Object, preview: Boolean, busy: Boolean },` 下面加一行：

```js
    emits: ['remove'],
```

(b) 原本的 `afterEach` 換成（多還原計時器）：

```js
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })
```

(c) Task 6 加的 helper 後面再加：

```js
const layout4 = () => [bar('c1', 'A', 0), bar('c2', 'B', 1), bar('c3', 'C', 2), table('c4', 8)]
const region = () => document.body.querySelector('.tc-toast-region')
const toastEl = () => document.body.querySelector('.tc-toast')
```

(d) 在 `describe` 最後加 7 個測試：

```js
  it('✕ removes the card locally and re-flows the rest (two per row, table last); the next 調整 no longer sends it', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['不要圓餅圖'], layout: [] })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()

    expect(view(wrapper).props('config').layout.map((s) => [s.chartId, s.gridPos])).toEqual([
      ['c2', { x: 0, y: 0, w: 6, h: 4 }],
      ['c3', { x: 6, y: 0, w: 6, h: 4 }],
      ['c4', { x: 0, y: 4, w: 12, h: 5 }],
    ])
    expect(api.analyze).toHaveBeenCalledTimes(1) // 「✕」不呼叫 API
    expect(region().getAttribute('role')).toBe('status')
    expect(region().textContent).toContain('已移除「A」')
    expect(byText(region(), 'button', '復原')).toBeTruthy()

    await adjustWith(wrapper, '不要圓餅圖')
    expect(api.analyze.mock.calls[1][2].map((s) => s.chartId)).toEqual(['c2', 'c3', 'c4'])
  })

  it('復原 puts the card back in its old slot and closes the notice', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    byText(region(), 'button', '復原').click()
    await flushPromises()

    expect(view(wrapper).props('config').layout).toEqual(layout4())
    expect(toastEl()).toBeNull()
  })

  it('the notice closes by itself after 6 seconds', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    vi.advanceTimersByTime(5999)
    await flushPromises()
    expect(toastEl()).not.toBeNull()

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('hovering or focusing the notice stops the countdown; it starts over once both have left', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    vi.advanceTimersByTime(3000)
    toastEl().dispatchEvent(new Event('mouseenter'))
    toastEl().dispatchEvent(new Event('focusin'))
    vi.advanceTimersByTime(10000)
    toastEl().dispatchEvent(new Event('mouseleave'))
    vi.advanceTimersByTime(10000)
    await flushPromises()
    expect(toastEl()).not.toBeNull() // 焦點還在提示裡

    toastEl().dispatchEvent(new Event('focusout'))
    vi.advanceTimersByTime(5999)
    await flushPromises()
    expect(toastEl()).not.toBeNull() // 兩個都離開後重新計 6 秒

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('removing another card replaces the notice; only the latest removal can be undone', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(region().textContent).toContain('已移除「B」')
    expect(region().textContent).not.toContain('已移除「A」')

    byText(region(), 'button', '復原').click()
    await flushPromises()
    expect(view(wrapper).props('config').layout.map((s) => s.chartId)).toEqual(['c2', 'c3', 'c4'])
  })

  it('closing the preview or pressing 調整 closes the notice for good', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    button('取消').click()
    await flushPromises()
    expect(toastEl()).toBeNull()

    await wrapper.get('button').trigger('click') // 再分析一次，重新打開預覽
    await flushPromises()
    expect(toastEl()).toBeNull()

    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(toastEl()).not.toBeNull()
    await field(wrapper, '想怎麼調整？').find('input').setValue('不要圓餅圖')
    button('調整').click()
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('unmounting clears the notice timer', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    const pending = vi.getTimerCount()
    expect(pending).toBeGreaterThan(0)

    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(pending - 1)
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd frontend && npm test -- src/modules/dashboard/AnalyzeButton.test.js`
Expected: FAIL，新加的 7 個測試失敗（例如版面還是 4 張、`Cannot read properties of null (reading 'getAttribute')`），原本 11 個照樣通過

- [ ] **Step 3: 改 AnalyzeButton.vue**

`frontend/src/modules/dashboard/AnalyzeButton.vue` 整個換成（跟 Task 6 比，多了 `remove`、`undo`、提示和它的樣式；`adjust()` 開頭和 `watch(open)` 裡各多一行 `closeToast()`；對話框加 `scrollable`；卸載時清掉提示的計時器）：

```vue
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'
import './motion.css'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const MAX_INTENTS = 10 // spec 3.2：需求紀錄最多 10 句
const TOAST_MS = 6000 // spec 3.2：「復原」提示停留 6 秒

const intent = ref('')
const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const adjustText = ref('')
const adjusting = ref(false)
const nameEdited = ref(false)
const toast = ref(null) // { spec, index }：剛移除的卡片和它在移除前的位置

const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())
const history = computed(() => preview.value?.intents ?? [])
const historyFull = computed(() => history.value.length >= MAX_INTENTS)
const noFocus = computed(() => history.value.length > 0 && !preview.value.layout.some((s) => s.options?.focus))
const canAdjust = computed(() => !!adjustText.value.trim() && !adjusting.value && !historyFull.value)

// 關掉預覽之後才回來的調整結果直接丟掉
let adjustSeq = 0
let toastTimer = 0
const toastHold = { hover: false, focus: false }

async function analyze() {
  busy.value = true
  error.value = ''
  const first = intent.value.trim()
  try {
    // 沒填需求就跟原本一樣只送 datasourceId
    preview.value = await (first ? api.analyze(props.datasourceId, [first]) : api.analyze(props.datasourceId))
    nameEdited.value = false
    adjustText.value = ''
    open.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function adjust() {
  if (!canAdjust.value) return
  closeToast() // spec 3.2：按「調整」時提示立即關閉，已移除的圖不能再復原
  const seq = ++adjustSeq
  adjusting.value = true
  error.value = ''
  try {
    const next = await api.analyze(preview.value.datasourceId, [...history.value, adjustText.value.trim()],
      preview.value.layout)
    if (seq !== adjustSeq) return
    // spec 3.2：使用者改過名稱就保留他改的，沒改過就用新結果的預設名稱
    preview.value = { ...next, name: nameEdited.value ? preview.value.name : next.name }
    adjustText.value = ''
  } catch (e) {
    if (seq === adjustSeq) error.value = e.message
  } finally {
    if (seq === adjustSeq) adjusting.value = false
  }
}

// 關掉預覽（取消、存檔、Esc）時，還在等的調整作廢；錯誤訊息也清掉，才不會跑到左側面板；提示一併關掉
watch(open, (isOpen) => {
  if (isOpen) return
  adjustSeq++
  adjusting.value = false
  error.value = ''
  closeToast()
})

// spec 6.6：依順序每列兩張（6×4），明細表放最後（12×5），跟後端的 ConfigAssembler.layOut 同一套
function relayout(layout) {
  const charts = layout.filter((s) => s.type !== 'table')
  const y = Math.ceil(charts.length / 2) * 4
  return [
    ...charts.map((s, n) => ({ ...s, gridPos: { x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 } })),
    ...layout.filter((s) => s.type === 'table').map((s) => ({ ...s, gridPos: { x: 0, y, w: 12, h: 5 } })),
  ]
}

// spec 3.2：只在前端拿掉，不呼叫 API；下次「調整」送出的 current 也不會有這張
function remove(chartId) {
  const layout = preview.value.layout
  const index = layout.findIndex((s) => s.chartId === chartId)
  if (index < 0) return // 退場那 120ms 裡又用鍵盤按了一次「✕」
  preview.value = { ...preview.value, layout: relayout(layout.filter((s) => s.chartId !== chartId)) }
  showToast({ spec: layout[index], index })
}

function undo() {
  if (!toast.value) return // 提示退場那 120ms 裡「復原」還在，又按了一次
  const { spec, index } = toast.value
  const layout = [...preview.value.layout]
  layout.splice(index, 0, spec)
  preview.value = { ...preview.value, layout: relayout(layout) }
  closeToast()
}

function showToast(t) {
  closeToast() // 一次只有一個提示，前一張就不能再復原
  toast.value = t
  toastTimer = setTimeout(closeToast, TOAST_MS)
}

function closeToast() {
  clearTimeout(toastTimer)
  toast.value = null
  toastHold.hover = false
  toastHold.focus = false
}

// ponytail: 滑鼠和焦點都離開後重新計滿 6 秒，不記剩下的時間；要精準暫停再記 Date.now()
function holdToast(kind, on) {
  if (!toast.value) return
  toastHold[kind] = on
  clearTimeout(toastTimer)
  if (!toastHold.hover && !toastHold.focus) toastTimer = setTimeout(closeToast, TOAST_MS)
}

onBeforeUnmount(() => clearTimeout(toastTimer))

async function save() {
  if (saving.value || nameInvalid.value) return
  saving.value = true
  try {
    const saved = await api.createDashboard(preview.value)
    open.value = false
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-textarea
    v-model="intent"
    label="想看什麼？（選填）"
    placeholder="例如：各區每月營收、哪個產品退貨最多"
    hint="這段文字會送給 JEV 做分析"
    persistent-hint
    counter="200"
    persistent-counter
    maxlength="200"
    rows="2"
    density="compact"
    class="mb-2"
  />
  <v-btn color="primary" block :loading="busy" :disabled="!datasourceId" @click="analyze">分析</v-btn>
  <v-alert v-if="error && !open" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
  <!-- scrollable：捲動的是 v-card-text，工具列和下面的提示都固定不動 -->
  <v-dialog v-model="open" fullscreen scrollable>
    <v-card>
      <v-toolbar title="儀表板預覽">
        <v-text-field
          v-if="preview"
          v-model="preview.name"
          label="儀表板名稱"
          density="compact"
          hide-details="auto"
          :error-messages="nameInvalid ? ['請輸入儀表板名稱'] : []"
          class="mx-4"
          style="max-width: 320px"
          @update:model-value="nameEdited = true"
        />
        <v-btn @click="open = false">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          class="mr-2"
          :loading="saving"
          :disabled="nameInvalid || saving"
          @click="save"
        >存為我的儀表板</v-btn>
      </v-toolbar>
      <v-card-text>
        <v-alert v-if="error" type="error" density="compact" class="mb-2">{{ error }}</v-alert>
        <template v-if="preview">
          <v-alert v-if="noFocus" type="info" density="compact" class="mb-2">找不到跟需求直接相關的欄位，先顯示一般的分析結果。</v-alert>
          <!-- 用 v-show 不用 v-if：TransitionGroup 一直掛著，第一句出現時才有淡入 -->
          <div v-show="history.length" class="tc-history mb-2">
            <div class="text-caption text-medium-emphasis">需求紀錄</div>
            <!-- spec 10.8：新的一句淡入；key 用序號，舊的句子不會重播 -->
            <TransitionGroup name="tc-intent" tag="ol" class="tc-intents pl-5">
              <li v-for="(sentence, i) in history" :key="i">{{ sentence }}</li>
            </TransitionGroup>
          </div>
          <div class="d-flex align-start ga-2 mb-2">
            <v-text-field
              v-model="adjustText"
              label="想怎麼調整？"
              placeholder="例如：不要圓餅圖、加各產品的數量"
              maxlength="200"
              density="compact"
              :disabled="historyFull || adjusting"
              :messages="historyFull ? ['需求紀錄最多 10 句'] : []"
            />
            <v-btn color="primary" :loading="adjusting" :disabled="!canAdjust" @click="adjust">調整</v-btn>
          </div>
          <DashboardView :config="preview" preview :busy="adjusting" @remove="remove" />
        </template>
      </v-card-text>
      <!-- 放在 v-card 底下、v-card-text 後面：內容捲動時提示還在預覽下方中央，也還在對話框的焦點範圍裡。
           role="status" 放在一直存在的外框上，提示出現時螢幕報讀器才念得到 -->
      <div class="tc-toast-region" role="status">
        <Transition name="tc-toast">
          <div
            v-if="toast"
            class="tc-toast"
            @mouseenter="holdToast('hover', true)"
            @mouseleave="holdToast('hover', false)"
            @focusin="holdToast('focus', true)"
            @focusout="holdToast('focus', false)"
          >
            <span>已移除「{{ toast.spec.title }}」</span>
            <v-btn variant="text" size="small" @click="undo">復原</v-btn>
          </div>
        </Transition>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
/* spec 3.2：預覽畫面下方中央，內容捲動時也不動；外框不擋點擊，只有提示本身可以按。
   .v-card 是 position: relative；不用 position: fixed，因為全螢幕對話框的 .v-overlay__content 有 contain: layout */
.tc-toast-region {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 24px;
  z-index: 10;
  display: flex;
  justify-content: center;
  pointer-events: none;
}

.tc-toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 16px;
  border-radius: 4px;
  background: rgb(var(--v-theme-surface-variant));
  color: rgb(var(--v-theme-on-surface-variant));
  box-shadow: 0 3px 5px -1px rgba(0, 0, 0, 0.2), 0 6px 10px 0 rgba(0, 0, 0, 0.14);
  pointer-events: auto;
}
</style>
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd frontend && npm test -- src/modules/dashboard/AnalyzeButton.test.js`
Expected: PASS（`Tests  18 passed (18)`）

- [ ] **Step 5: 跑全部前端測試和 build**

Run: `cd frontend && npm test && npm run build`
Expected: `Tests  79 passed (79)`，build 成功

- [ ] **Step 6: Commit**

```bash
git add frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js && git commit -m "feat(frontend): remove a preview card with re-flow and an undo notice" -m "<Co-Authored-By 那一行>" -- frontend/src/modules/dashboard/AnalyzeButton.vue frontend/src/modules/dashboard/AnalyzeButton.test.js
```

---

### Task 8: 文件：README、方法說明文件、backlog

**Files:**
- Modify: `README.md`（在「## 測試」前面加一節）
- Modify: `docs/JEV智慧化產製儀表板的流程與方法.md`（第 11 節、新增第 13 節、原本的第 13 節改成第 14 節）
- Modify: `docs/backlog.md`（第 9、10、35 行）

**Interfaces:**
- Consumes：Task 1–7 做好的行為，文件只描述已經做出來的東西
- Produces：無

下面要貼的文字已經照使用者的 CLAUDE.md 用 humanizer-zh 潤過，照貼就好；要是改了內容，改完再跑一次 humanizer-zh。

- [ ] **Step 1: README 加上功能說明**

在 `README.md` 的 `## 測試` 那一行前面插入下面這一節（前後各空一行）：

```markdown
## 依需求分析與調整

按「分析」之前，可以在左側「想看什麼？（選填）」寫一句需求，例如「各區每月營收」，最多 200 字。JEV 判斷跟這句話直接相關的欄位，一定會列入挑圖的候選；用到這些欄位的圖排在最前面，卡片標題旁有「符合需求」標籤，後面才補一般性的圖。沒填的話，分析結果跟以前一樣。

預覽畫面上方會列出「需求紀錄」。在「想怎麼調整？」寫下一句，例如「不要圓餅圖，加各產品的數量」，再按「調整」。後端會把目前的圖和整串需求紀錄一起送給 JEV，由 JEV 判斷每張圖要保留還是移除（拿不準就保留），再補上跟這句話有關的新圖，總數最多 6 張。需求紀錄最多 10 句。

不想要某張圖，也可以直接按卡片右上角的「✕」，不會呼叫 JEV。畫面下方接著出現「已移除「…」」的提示，6 秒內按「復原」就能放回原位；滑鼠停在提示上，或鍵盤焦點在提示裡時，不會倒數。

需求紀錄會跟著儀表板一起存檔。之後打開這份儀表板，圖表上方會多一行「依據：各區每月營收 → 不要圓餅圖，加各產品的數量」。

送給 JEV 的內容只多了使用者寫的句子；調整時再加上目前每張圖的標題、圖型和欄位名稱，一樣不含任何資料值。完整規則見 [`docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`](docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md)，JEV 多問了哪些題目見方法說明文件第 13 節。
```

- [ ] **Step 2: 方法說明文件加一節「依需求分析與調整」**

`docs/JEV智慧化產製儀表板的流程與方法.md`：

(a) 第 11 節的 `送給 JEV 的內容只有三種，原始資料列永遠不送：` 換成 `送給 JEV 的內容只有四種，原始資料列永遠不送：`，並在清單第 3 點（「異常檢查摘要：…」那一行）後面加一行：

```markdown
4. 依需求分析時多送的內容：使用者寫的句子；調整時再加上目前每張圖的標題、圖型和欄位名稱（第 13 節），一樣不含資料值。
```

(b) 在 `## 13. 限制與後續方向` 那一行前面插入：

```markdown
## 13. 依需求分析與調整

分析前寫的一句需求，和預覽裡的每一次「調整」，都只會讓兩輪多出幾題、`state` 多出幾個欄位，JEV 一樣只回答結構化題目。沒有需求時，題目和 `state` 跟前面幾節寫的完全一樣。完整規格見 `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`。

`/api/jev/analyze` 多收兩個選填欄位：`intents` 是需求紀錄，最後一句是這次的需求；`current` 是目前預覽裡的圖，有值就是調整模式。每句先去掉頭尾空白，空白句直接丟掉；某一句超過 200 字、超過 10 句，或有 `current` 卻沒有任何需求，都回 400。

**`state` 多的欄位**：`intent`（最新一句）、`history`（之前的句子，只有一句時不送）；調整時再加 `current`，每張圖只列 `chartId`、`title`、`type` 和 mapping 用到的欄位名稱，明細表不列。

**第一輪多的題目**：

| 題目 | 什麼時候問 | 類型與選項 | 怎麼用 |
|---|---|---|---|
| `rel_i` | 有需求時，每個欄位一題 | `score`：無關、間接相關、直接相關 | `round(score)` 是 2、confidence ≥ 0.5 的叫焦點欄位；JEV 沒回答就不算 |
| `keep_<chartId>` | 調整時，每張目前的圖一題（明細表不問） | `choice`：保留這張圖、移除這張圖 | 答「移除」而且 confidence ≥ 0.5 才移除，其他情況都保留 |

調整時不問 `chart_count`，因為調整不補一般性的圖。

**候選與排序**：角色不是 `ignore` 的焦點欄位不看重要性，直接排在候選最前面，其他欄位照第 5 節的規則補，總數一樣最多 8 個。組合先比有沒有焦點欄位，再比平均重要性，同分照產生順序。第二輪每題說明的最後加一句「使用者想看：「…」。」。

**第一次分析**：照第 7 節取 K 張。有焦點組合、但選出來的圖都沒用到焦點欄位時，把排最前面的焦點組合用它第一個合法圖型放到第一張，原本已經 K 張就拿掉最後一張。用到焦點欄位的圖在 `options.focus` 標 `true`，畫面上顯示「符合需求」。

**調整**：

1. 保留的圖設定不動，`chartId`、`options` 都照舊，只重新排版。
2. 第二輪只問含焦點欄位的組合，最多 2 ×（6 − 保留張數）組。組合用的欄位跟某張保留的圖相同時，選項裡就拿掉那張圖的圖型；只剩 `none` 可選的組合不問。
3. 第二輪接受的組合依序加在保留的圖後面，總數最多 6 張，新圖都標 `options.focus`。一張都沒加、保留的圖也沒用到焦點欄位、還有空位時，補上排最前面的焦點組合。
4. 新圖的編號從 `current` 裡 chartId 最大的數字往後接，明細表和這次移除的圖都算在內，因為前端靠 chartId 分辨哪些卡片是保留下來的。明細表沿用原本的欄位，放在最後。
```

(c) 原本的 `## 13. 限制與後續方向` 改成 `## 14. 限制與後續方向`，這一節裡的 `（例如分析前先讓使用者輸入需求、事後用對話方式加圖表）` 換成 `（例如已存儀表板的對話式修改）`。

- [ ] **Step 3: backlog**

`docs/backlog.md`：

(a) 第 9 行（開頭是 `- P3 · 分析前先讓使用者用一段文字講想看什麼`）整行換成：

```markdown
- ~~P3 · 分析前先讓使用者用一段文字講想看什麼（一個輸入框／對話框），JEV 再照這個提示挑欄位、選圖表。（human 提出的構想）~~ 已完成：分析前可以輸入需求，預覽裡也能「調整」，見 spec `docs/superpowers/specs/2026-09-25-jev-dashboard-intent-analysis-design.md`。
```

(b) 第 10 行（開頭是 `- P3 · 存檔後用對話的方式說「幫我加一張圖」`）整行換成：

```markdown
- P3 · 已存儀表板的對話式修改：存檔後用一句話說「幫我加一張圖」。預覽裡的「調整」已經把需求轉成 JEV 的結構化題目（`rel_i`、`keep_<chartId>`，加上 `current`），這裡可以沿用同一套機制，入口和存檔流程另立 spec 設計。（human 提出的構想）
```

(c) 第 35 行（開頭是 `- P3 · 分析預覽存檔失敗的錯誤訊息`）整行換成（Task 6 的 `watch(open)` 在關掉預覽時清掉錯誤，這一項跟著解決）：

```markdown
- ~~P3 · 分析預覽存檔失敗的錯誤訊息，取消對話框後不會清掉，下次打開還會看到舊的錯誤。（frontend ledger，Task 4）~~ 已修正：關掉預覽時會清掉錯誤訊息（依需求分析與調整）。
```

- [ ] **Step 4: 檢查標題和標記**

Run: `grep -n "^## " README.md`
Expected: `## 依需求分析與調整` 在 `## 啟動方式` 和 `## 測試` 之間

Run: `grep -n "^## 1[1-4]\." "docs/JEV智慧化產製儀表板的流程與方法.md"`
Expected: 依序是 `## 11. 隱私與安全設計`、`## 12. 失敗時的行為`、`## 13. 依需求分析與調整`、`## 14. 限制與後續方向`

Run: `grep -c "^- ~~P3" docs/backlog.md`
Expected: `2`

- [ ] **Step 5: Commit**

```bash
git add README.md "docs/JEV智慧化產製儀表板的流程與方法.md" docs/backlog.md && git commit -m "docs: intent-guided analysis and adjust in README, method doc and backlog" -m "<Co-Authored-By 那一行>" -- README.md "docs/JEV智慧化產製儀表板的流程與方法.md" docs/backlog.md
```

---

### Task 9: 端對端驗證（接真的 JEV）

**Files:**
- 不新增檔案。發現問題時修正對應的檔案，commit 訊息寫清楚改了什麼。
- 可能修改：`docs/backlog.md`（Step 13 的觀察）

**Interfaces:**
- Consumes：Task 1–8 的全部成果、`backend/src/test/resources/sales.csv`、Chrome DevTools MCP（`new_page`、`take_snapshot`、`upload_file`、`fill`、`click`、`wait_for`、`evaluate_script`、`list_network_requests`、`get_network_request`、`emulate`、`take_screenshot`、`close_page`）

**誰來跑：** 整個 Task 9 交給同一個 subagent 從頭跑到尾，這個 subagent 要能用 Chrome DevTools MCP。Step 3、4 開的背景程序屬於它；controller 的 main session 不跑 `mvnw`，也不跑 `npm run dev`。

**瀏覽器規則，一定要遵守：** 用 `new_page` 開一個自己的分頁，記下它的 pageId，之後只操作這個分頁。別的分頁是使用者自己在用的：不要 `select_page`、`navigate_page`、`close_page` 它們，也不要讀取、截圖或點它們。不確定目前選的是哪一頁時，先 `select_page` 自己的 pageId。結束時只關自己的分頁。下面的取樣腳本靠 `requestAnimationFrame`，分頁要在前景。

**等待和程序：** 每個等待迴圈最多等 180 秒，逾時就印出伺服器 log 的最後幾十行，停下來回報，不要一直重試。agent 不能 `taskkill`：埠被別的程序佔著，或自己開的程序停不掉時，用 `netstat -ano | grep …` 找出 PID，回報給使用者處理。

- [ ] **Step 1: 全部測試再跑一次**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd backend && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected: `Tests run: 143, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`

Run: `cd frontend && npm test && npm run build`
Expected: `Tests  79 passed (79)`，build 成功

- [ ] **Step 2: 確認 JEV key，以及 8080、5180 沒有人在用**

Run: `grep -q '^TYPESAFE_API_KEY=.' backend/.env && echo "TYPESAFE_API_KEY 已設定" || echo "TYPESAFE_API_KEY 沒設定"`
Expected: `TYPESAFE_API_KEY 已設定`。這行只印結果，不印內容。沒設定就停下來向 controller 要 key，不要自己打開 `.env` 看。

Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
Expected: `8080 000` 和 `5180 000`（兩個埠都沒有程式在聽）。

只要有一個不是 `000`，就代表有程式佔著這個埠。8080 多半是使用者之前開的舊後端：它跑的不是這個 branch 的程式，Task 1–3 重新編譯 `backend/target/classes` 之後還會壞掉，不能拿來驗證。不要用它、不要關它，也不要 `mvnw clean`。跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把最後一欄的 PID 回報給 controller，請使用者自己關掉，然後停在這一步。

- [ ] **Step 3: 啟動後端（背景），確認跑的是這個 branch 的程式**

用 Bash 的 `run_in_background: true` 執行，log 寫進已經在 gitignore 裡的 `backend/target/`：

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; cd /c/GitHub/jev-form-dashboard/backend && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1 > target/e2e-backend.log 2>&1
```

再用 Bash 的 `run_in_background: true` 跑下面的探測迴圈，等它結束的通知。這個請求帶了 `current` 卻沒有需求：Task 3 的程式在讀資料源之前就回 400「請輸入想怎麼調整」，舊的程式會先去讀資料源，回「找不到資料源」。看到「請輸入想怎麼調整」，才代表 8080 上跑的是新程式。

```bash
for i in $(seq 1 90); do
  r=$(curl -s -X POST -H 'Content-Type: application/json' -H 'X-User-Id: e2e-intent' \
    -d '{"datasourceId":"00000000-0000-0000-0000-000000000000","current":[]}' \
    http://127.0.0.1:8080/api/jev/analyze || true)
  case "$r" in *請輸入想怎麼調整*) echo "backend-ready $r"; exit 0 ;; esac
  sleep 2
done
echo "backend not ready after 180 s, last response: $r"
tail -n 60 /c/GitHub/jev-form-dashboard/backend/target/e2e-backend.log
exit 1
```

Expected: `backend-ready {"error":"請輸入想怎麼調整"}`。逾時就把印出來的 log 附在報告裡，停在這一步。

- [ ] **Step 4: 啟動自己的前端（背景，用獨立的使用者）**

用 Bash 的 `run_in_background: true` 執行：

```bash
cd /c/GitHub/jev-form-dashboard/frontend && VITE_USER_ID=e2e-intent npm run dev -- --port 5180 --strictPort > /c/GitHub/jev-form-dashboard/backend/target/e2e-vite.log 2>&1
```

再用 Bash 的 `run_in_background: true` 等它起來：

```bash
for i in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5180/)" = 200 ] && { echo frontend-ready; exit 0; }
  sleep 2
done
echo "frontend not ready after 180 s"
tail -n 40 /c/GitHub/jev-form-dashboard/backend/target/e2e-vite.log
exit 1
```

Expected: `frontend-ready`。`VITE_USER_ID=e2e-intent` 讓這次建的資料源和儀表板都掛在 `e2e-intent` 名下，不會混進使用者的 `demo-user`。

- [ ] **Step 5: 開自己的分頁、上傳測試資料**

1. `new_page`，url 填 `http://localhost:5180/`，記下 pageId。
2. `take_snapshot` 找到「CSV / Excel」檔案欄位，用 `upload_file` 傳 `C:\GitHub\jev-form-dashboard\backend\src\test\resources\sales.csv`，再按「上傳」。

Expected: 資料源清單出現「sales.csv」，副標題「6 個欄位」，而且已經選取。

下面幾步會用到的 `evaluate_script` 腳本：

S1，列出預覽裡的卡片。位置用 grid-layout-plus 寫在元素上的 `transform`，不受捲動影響：

```js
() => [...document.querySelectorAll('.v-dialog .vgl-item:not(.vgl-item--placeholder)')].map((el) => ({
  title: el.querySelector('.v-card-title .text-truncate')?.textContent.trim(),
  focus: el.querySelector('.v-card-title')?.textContent.includes('符合需求') ?? false,
  loading: el.textContent.includes('載入中…'),
  position: el.style.transform,
}))
```

S2，開始逐幀取樣：從「調整」開始等 JEV（圖表區 `aria-busy` 變成 true），一直取到等待結束（變回 false）再多 1 秒，最多 60 秒：

```js
() => {
  const samples = []
  const t0 = performance.now()
  let wasBusy = false
  let idleSince = null
  window.__e2eSamples = samples
  window.__e2eDone = false
  const grab = () => {
    const now = performance.now()
    const busy = document.querySelector('.v-dialog .tc-grid')?.getAttribute('aria-busy') === 'true'
    samples.push({
      busy,
      cards: [...document.querySelectorAll('.v-dialog .vgl-item .v-card')].map((c) => ({
        title: c.querySelector('.v-card-title .text-truncate')?.textContent.trim(),
        loading: c.textContent.includes('載入中…'),
        leave: c.classList.contains('tc-card-leave'),
        enter: c.classList.contains('tc-card-enter'),
      })),
    })
    if (busy) wasBusy = true
    if (wasBusy && !busy && idleSince === null) idleSince = now
    if ((idleSince !== null && now - idleSince >= 1000) || now - t0 >= 60000) window.__e2eDone = true
    else requestAnimationFrame(grab)
  }
  requestAnimationFrame(grab)
  return 'sampling'
}
```

S3，等 S2 取樣結束（每次最多等 20 秒，還沒結束就回 `done: false`），再整理結果：

```js
async () => {
  const t0 = performance.now()
  while (!window.__e2eDone && performance.now() - t0 < 20000) await new Promise((resolve) => setTimeout(resolve, 250))
  if (!window.__e2eDone) return { done: false }
  const s = window.__e2eSamples
  const firstBusy = s.findIndex((x) => x.busy)
  const first = new Set(s[0].cards.map((c) => c.title))
  const last = new Set(s.at(-1).cards.map((c) => c.title))
  const kept = [...first].filter((t) => last.has(t))
  const removed = [...first].filter((t) => !last.has(t))
  const added = [...last].filter((t) => !first.has(t))
  const titles = (pick) => [...new Set(s.flatMap((x) => x.cards.filter(pick).map((c) => c.title)))]
  return {
    done: true,
    samples: s.length,
    busyToIdle: firstBusy >= 0 && s.slice(firstBusy).some((x) => !x.busy),
    changed: removed.length + added.length > 0,
    keptFlashedLoading: s.some((x) => x.cards.some((c) => kept.includes(c.title) && c.loading)),
    removed,
    left: titles((c) => c.leave),
    added,
    entered: titles((c) => c.enter),
  }
}
```

S4，點左側清單裡的一份儀表板，逐幀記下每個卡片元素出現過幾個不同的位置（`NAME` 換成要打開的儀表板名稱）：

```js
async () => {
  const NAME = 'E2E 依需求 A'
  const item = [...document.querySelectorAll('.v-navigation-drawer .v-list-item')].find((el) => el.textContent.includes(NAME))
  item.click()
  const seen = new Map()
  const t0 = performance.now()
  while (performance.now() - t0 < 800) {
    await new Promise((resolve) => requestAnimationFrame(resolve))
    document.querySelectorAll('.v-main .vgl-item:not(.vgl-item--placeholder)').forEach((el) => {
      if (!seen.has(el)) seen.set(el, new Set())
      seen.get(el).add(getComputedStyle(el).transform)
    })
  }
  return [...seen].map(([el, values]) => ({
    title: el.querySelector('.v-card-title .text-truncate')?.textContent.trim(),
    positions: values.size,
  }))
}
```

S5，等待 JEV 時的狀態：

```js
() => {
  const grid = document.querySelector('.v-dialog .tc-grid')
  return {
    busy: grid?.getAttribute('aria-busy'),
    opacity: grid ? getComputedStyle(grid).opacity : null,
    removeDisabled: [...document.querySelectorAll('.v-dialog [aria-label="移除這張圖"]')].every((b) => b.disabled),
  }
}
```

S6，把預覽捲到最下面（`scrollable` 對話框裡捲動的是 `.v-card-text`）：

```js
() => {
  const scroller = document.querySelector('.v-dialog .v-card-text')
  scroller.scrollTop = scroller.scrollHeight
  return { scrollTop: Math.round(scroller.scrollTop), scrollable: scroller.scrollHeight > scroller.clientHeight }
}
```

S7，量「復原」提示的位置：

```js
() => {
  const toast = document.querySelector('.tc-toast')
  if (!toast) return { toast: false }
  return {
    toast: true,
    text: toast.textContent.trim(),
    role: document.querySelector('.tc-toast-region').getAttribute('role'),
    bottom: Math.round(toast.getBoundingClientRect().bottom),
    expected: innerHeight - 24,
    scrollTop: Math.round(document.querySelector('.v-dialog .v-card-text').scrollTop),
  }
}
```

- [ ] **Step 6: spec §11 第 1 步，依需求分析**

1. 在「想看什麼？（選填）」輸入「各區每月營收」。
   Expected: 下方看得到「這段文字會送給 JEV 做分析」和字數「6 / 200」。
2. 按「分析」，等「儀表板預覽」出現，跑 S1。
3. `list_network_requests` 找 `POST /api/jev/analyze`，`get_network_request` 看回應的 `intents` 和 `layout`（每張的 `type`、`mapping`、`options`）。

Expected: 「需求紀錄」列出「各區每月營收」；第一張卡片有「符合需求」；有一張 `mapping` 同時用到 `order_date` 和 `region` 的圖（地區 × 月份，通常是 `stacked_bar` 或 `heatmap`），`options.focus` 是 true。第一張不是地區 × 月份時，照實記下前兩張的標題和圖型（見「實作上的決定」第 10 點），不要改規則。

- [ ] **Step 7: spec §11 第 2 步，調整**

1. 記下上一步的回應裡有沒有 `pie`。
2. 在「想怎麼調整？」輸入「不要圓餅圖，加各產品的數量」，按「調整」，馬上跑 S5。
   Expected: `busy` 是 `"true"`，`removeDisabled` 是 true，`opacity` 是 `"0.6"`；「調整」按鈕在轉圈。
3. 回應回來後，看第二筆 `POST /api/jev/analyze` 的 request body 和回應。

Expected: request body 的 `intents` 是兩句，`current` 是上一步的全部卡片（含明細表）；回應裡沒有 `pie`（上一步本來就沒有圓餅圖的話，在報告裡註明）；多了一張 `mapping` 用到 `product` 和 `qty` 的圖，`options.focus` 是 true；「需求紀錄」變成兩句，輸入框清空。

- [ ] **Step 8: spec §11 第 3 步，按「✕」之後再調整**

1. 從目前的卡片挑一張 `mapping` 沒用到 `order_date`、也沒用到 `qty` 的（例如「amount 依 region」），記下它的 chartId 和標題，按它的「✕」。沒有這種卡片就任挑一張，把下一句改成跟它的欄位都無關的需求，並在報告裡寫明。
2. 在「想怎麼調整？」輸入「加每月的數量趨勢」，按「調整」。

Expected: 第三筆 `POST /api/jev/analyze` 的 `current` 裡沒有剛剛那個 chartId；回應裡也沒有同標題的卡片。

- [ ] **Step 9: spec §11 第 4 步，存檔後看「依據」**

把名稱改成「E2E 依需求 A」，按「存為我的儀表板」。

Expected: 預覽關閉，主畫面打開「E2E 依需求 A」，圖表上方有一行「依據：各區每月營收 → 不要圓餅圖，加各產品的數量 → 加每月的數量趨勢」（第 3 步換過句子的話，照實際送出的句子）；用到焦點欄位的卡片有「符合需求」。

- [ ] **Step 10: spec §11 第 5、7 步，網路變慢時調整，再「✕」和「復原」**

1. 按「分析」（輸入框還是「各區每月營收」），等預覽出現。
2. 跑 S2，回傳 `sampling`。
3. `emulate` 把網路設成 `Slow 3G`。
4. 在「想怎麼調整？」輸入「不要圓餅圖，加各產品的數量」，按「調整」。
5. 跑 S3；回傳 `done: false` 就再跑一次，直到 `done: true`。然後用 `emulate` 把網路改回不限速。

Expected: `busyToIdle` 是 true（真的等過一次 JEV）；`changed` 是 true（版面真的變了）；`keptFlashedLoading` 是 false（保留的卡片沒有閃「載入中」）；`removed` 裡每個標題都出現在 `left`（先退場才消失）；`added` 裡每個標題都出現在 `entered`（新卡片有進場）。`changed` 是 false 時，換一句更明確的需求（例如「只留折線圖，加各產品的數量」）從第 2 點重做，並在報告裡寫明；`busyToIdle` 是 false 代表 S2 沒有在按「調整」之前開始，從第 2 點重做。

6. 跑 S1，記下每張卡片的 `position`。跑 S6 把預覽捲到最下面；回傳的 `scrollable` 是 false（內容不夠長，捲不動）就在報告裡註明這項沒測到，不要去改視窗大小。用 `take_snapshot` 找最後一個「移除這張圖」按鈕（明細表沒有這個按鈕，所以它屬於最後一張圖），記下那張的標題後按下去。用 `wait_for` 等「已移除」出現，再跑 S7。

Expected: `text` 包含「已移除「{那張的標題}」」和「復原」，`role` 是 `status`；`scrollTop` 大於 0；`bottom` 跟 `expected` 相差不到 2px，也就是提示固定在預覽下方中央，沒有跟著內容捲走。

7. 記下 `list_network_requests` 目前有幾筆 `POST /api/dashboards/preview/data`。按「復原」，先跑 `async () => { await new Promise((resolve) => setTimeout(resolve, 500)); return 'ok' }` 等 0.5 秒，再跑 S1。

Expected: 那張卡片的 `position` 跟第 6 步記下的一樣，`loading` 是 false；提示消失；`POST /api/dashboards/preview/data` 的筆數沒有增加（「✕」和「復原」都不呼叫 API）。

- [ ] **Step 11: spec §11 第 6 步，在兩份已存儀表板之間切換**

1. 名稱改成「E2E 依需求 B」，按「存為我的儀表板」。主畫面現在是 B。
2. 跑 S4（`NAME` 是「E2E 依需求 A」），再把 `NAME` 換成「E2E 依需求 B」跑一次。

Expected: 兩次的每個元素 `positions` 都 ≤ 2（打開時可能先有一個還沒量到寬度的位置，接著直接跳到定位）。出現 3 個以上代表卡片在滑動，回頭查 Task 4 的 `is-settled` 和 `App.vue` 的 key。

- [ ] **Step 12: 收尾**

1. `emulate` 確認網路不限速，`close_page` 關掉自己的分頁。
2. 用 TaskStop 停掉 Step 3、4 自己開的背景程序（後端、前端，還有還在跑的探測迴圈）；不要停不是你開的程序。
3. 確認兩個埠都沒有程式在聽了：

   Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
   Expected: `8080 000` 和 `5180 000`。還有回應，就代表 TaskStop 沒停乾淨（在 Windows 上，`mvnw spring-boot:run` 另外開的 java、`npm run dev` 底下的 node 常會留下來）。跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 寫進報告，請使用者自己關。
4. 測試資料留在 `e2e-intent` 名下，不用刪；`backend/target/e2e-backend.log`、`e2e-vite.log` 在 gitignore 裡，也不用刪。

- [ ] **Step 13: 回報並 commit 修正**

把 Step 6 到 Step 11 每一步的結果（通過，或實際看到什麼）整理回報。Step 6 的第一張不是地區 × 月份的話，在 `docs/backlog.md` 的「功能構想」最後加一行：

```markdown
- P3 · 組合排序先比「有沒有焦點欄位」再比平均重要性，沒有比含幾個焦點欄位：需求是「各區每月營收」時，第一張是「{實際的標題}」，不是地區 × 月份。可以考慮把焦點欄位的數量加進排序。（依需求分析 E2E）
```

有修正程式或改了 backlog 才 commit，把實際改過的檔案逐一列在兩個地方：

```bash
git add docs/backlog.md && git commit -m "docs: note focus-count ranking from intent-analysis E2E" -m "<Co-Authored-By 那一行>" -- docs/backlog.md
```

（上面是只改 backlog 的例子；改了程式就把那些檔案也加進 `git add` 和 `--` 後面，訊息改成 `fix: …` 並寫清楚改了什麼。）
