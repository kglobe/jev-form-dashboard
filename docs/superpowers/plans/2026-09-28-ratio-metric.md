# JEV 儀表板：比率 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-28-ratio-metric-design.md`：每個來源可以加「比率＝分子 ÷ 分母」，轉換器每組先把分子、分母各自加總再相除；折線圖、長條圖把比率顯示成百分比；JEV 分析把比率當成一個數值，只配時間或分類欄位；比率圖的異常檢查改比百分點；分子或分母敏感時比率也敏感。

**Architecture:** 比率是新的 record `Ratio(name, numerator, denominator, sensitive)`，存在 `datasource.ratios_json`（Flyway V4），不放進 `schema_json`。`Datasource` 多一個 `ratios`，`DatasourceService` 多一支整份取代的 `setRatios`，讀取時才算比率的 `sensitive`。`UniversalTransformer.transform` 多收比率定義，`value` 是比率時走 `grouped` 裡的比率分支，`ChartData` 多一個 `percent`。`ConfigAssembler.FieldEval` 多一個 `ratio`，比率固定是 `measure`，只出折線、長條，跟對照組合排同一層。前端新增 `charts/percent.js` 給長條、折線共用；比率區和「新增比率」對話框寫在 `DatasourcePanel.vue`；製圖頁的欄位標籤接上比率。

**Tech Stack:** Java 21、Spring Boot 3.5.16（Jackson 2.21）、Flyway、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、ECharts 6 + vue-echarts、Vitest 5 + @vue/test-utils 2.5 + happy-dom 20。

前一份計畫是 `docs/superpowers/plans/2026-09-28-compare-datasource.md`（B2），這份只寫新增和改變的部分。

## Global Constraints

- 在主工作目錄 `C:\GitHub\jev-form-dashboard` 做，branch 是 `feat/ratio-metric`（上面已經有 B3 spec 的 commit `e58b970`）。沒有平行開發，不另開 worktree。
- 這次有 migration（V4）。後端測試一律用獨立的 schema `jev_dashboard_test_b3`：每個後端指令都要帶 `export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3;`（環境變數蓋過 `application-test.yml`，Flyway 會自己建 schema）。預設的 `jev_dashboard_test` 不碰，開發中的 V4 才不會寫進 main 用的測試 schema。手動驗證（Task 10）的後端用全新的 `jev_dashboard_e2e_b3`。使用者開發用的 `jev_dashboard` 完全不碰。
- `V4__ratio_metric.sql` 寫好、跑過之後就不要再改（Flyway 會因為 checksum 不同而拒絕啟動）。真的非改不可，把上面兩個 schema 名稱換成新的（例如 `jev_dashboard_test_b3b`），並在報告裡寫出來。
- 本機是 Windows 11 + Git Bash。不要用 `python3`（用 `python`）；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`。不要跑 `./mvnw clean`。後端測試會連本機 PostgreSQL，帳密在 `backend/.env`。
- 不要印出 `backend/.env` 的內容，`cat`、`grep` 整行都不行。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`，不能 import 這個資料夾以外的檔案。
- 不新增 npm 或 Maven 套件。
- 既有測試全部照舊通過，而且既有測試一行都不改：只在既有測試檔裡加新的測試、helper 和 import。唯一的例外是 `DatasourcePanel.test.js` 最上面的 `vi.mock('./api.js', …)` 要多一行 `setRatios: vi.fn(),`。
- 錯誤回應固定是 `{"error": "訊息"}`。這次新增的 400 訊息一字不差照下面寫（名稱用「」包，理由見「實作上的決定」第 3 點）：
  - 「比率名稱不能空白」「比率名稱最多 50 個字」「比率名稱「{名稱}」跟欄位或其他比率重複」
  - 「分子「{欄位}」不是這個來源的數字欄位」「分母「{欄位}」不是這個來源的數字欄位」「分子和分母不能是同一個欄位」
- 送給 JEV 的內容一字不差：
  - `state.fields` 的比率：`{"name": 名稱, "type": "ratio", "formula": "{分子} ÷ {分母}"}`，沒有其他鍵。
  - 第一輪：「比率「{名稱}」對分析這份資料有多重要？」；有需求時再問「使用者想看：「{需求}」。比率「{名稱}」跟這個需求有多相關？」。
  - 第二輪題目（組合裡有比率時）多這一句：「「{名稱}」是比率＝{分子} ÷ {分母}，每組先各自加總再相除。」
  - 公式裡的 `÷` 是 U+00F7，對照來源欄位名稱裡的 `·` 是 U+00B7。
- 畫面上的字照 spec 第 6 節：區塊標題「比率 · {數量}」；沒有比率時「比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。」；按鈕「新增比率」；對話框標題「新增比率」，欄位「名稱」「分子」「分母」，按鈕「取消」「新增」；刪除鈕 `aria-label`「刪除比率 {名稱}」；敏感標籤「敏感」、滑過去「分子或分母是敏感欄位」；製圖頁比率標籤的圖示 `mdi-percent`、滑過去「{名稱}（比率：{分子} ÷ {分母}）」。
- 百分比一律用 `new Intl.NumberFormat('zh-TW', { style: 'percent', maximumFractionDigits: 1 })`：0.052 是「5.2%」，0.05 是「5%」。
- 每個 commit 都用明確的 pathspec，格式是 `git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits 加繁體中文，例如 `feat(backend): …`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. `Ratio` 的 `sensitive` 是 record 的 primitive boolean，另外有三個參數的建構子（`sensitive = false`）。`setRatios` 一律用三個參數的建構子重建，所以存進 `ratios_json` 的都是 `false`，前端送回來的 `sensitive` 會被丟掉。`GET` 回傳的是 `DatasourceService.effective` 讀取時算出來的值，一律帶 `sensitive`（`true` 或 `false`）。
2. `DatasourceRepository.insert` 不寫 `ratios_json`，靠欄位預設的 `[]`。建立上傳、外部 API、合併來源時本來就不能帶比率，只有 `PATCH /ratios` 會寫這欄。
3. 錯誤訊息和送給 JEV 的句子裡，名稱都用「」包，跟現有訊息一致。spec 第 8 節、5.3 寫『』，是因為外面已經有一層「」。
4. spec 4.2 只規定加比率時不能跟顯示名稱重複。反過來，改顯示名稱時也不能跟比率名稱重複，不然 dataset 一樣會出現兩個同名的維度。`setLabels` 把比率名稱先放進已用過的名稱裡，沿用現有訊息「顯示名稱「{名稱}」重複」。
5. 焦點比率（使用者在製圖頁勾了，或 JEV 判定「直接相關」）跟其他焦點欄位一起排在最前面，照重要性排；不是焦點的比率才排在焦點欄位之後、其他欄位之前。
6. 第二輪題目列欄位時，比率的角色照舊寫「（數值）」；比率怎麼算，交給多加的那句公式說明。
7. 轉換器裡 `value` 找到比率時，要檢查的欄位是分子、分母，不是比率名稱本身；比率名稱放在 `x`、`measures` 這些位置時不在資料欄位裡，自然回 `FIELD_MISSING`，不用另外寫判斷。
8. 比率圖的異常檢查：`changePoints` 用原始比率相減再換算、四捨五入，不是拿四捨五入後的兩個數字相減。系列超過 10 條時只留變化最大的 10 條，其餘不送，不併成「其他」（比率加起來沒有意義）；只有手動組的堆疊長條、熱力圖會遇到。`aggregate: none` 的卡片照現有規則不做異常檢查。
9. 前端的比率區直接寫在 `DatasourcePanel.vue`，跟欄位表共用 `busy`、`refresh()`。「新增比率」對話框放在「新增來源」對話框的後面：既有測試用 `findComponent({ name: 'VDialog' })` 找第一個對話框，找到的必須還是「新增來源」那個。
10. 百分比的 tooltip 遇到 `null` 顯示「-」。`ChartCard` 的 `compactValueAxis` 改成「軸已經有自己的 `formatter` 就不套」，不去看 `percent`。

## File Structure

```
backend/src/main/
  resources/db/migration/V4__ratio_metric.sql   新增：ratios_json（Task 1）
  java/jev/dashboard/datasource/Ratio.java      新增：比率定義（Task 1）
  java/jev/dashboard/datasource/Datasource.java 多 ratios、withRatios（Task 1）
  java/jev/dashboard/datasource/DatasourceRepository.java  讀 ratios_json、updateRatios（Task 1）
  java/jev/dashboard/datasource/DatasourceService.java     setRatios、比率的敏感、顯示名稱不能撞比率（Task 1）
  java/jev/dashboard/datasource/DatasourceController.java  PATCH /ratios（Task 1）
  java/jev/dashboard/transform/ChartData.java              多 percent（Task 2）
  java/jev/dashboard/transform/UniversalTransformer.java   比率的分組相除（Task 2）
  java/jev/dashboard/dashboard/DashboardDataService.java   把比率定義傳進轉換器（Task 3）
  java/jev/dashboard/jev/HighlightSummarizer.java          百分點（Task 3）
  java/jev/dashboard/jev/JevController.java                敏感比率（Task 3）、分析帶比率（Task 5）
  java/jev/dashboard/jev/ConfigAssembler.java              比率的候選、組合、排序（Task 4）
  java/jev/dashboard/jev/AnalyzeService.java               state、題目、明細表（Task 5）
backend/src/test/java/jev/dashboard/
  datasource/DatasourceRepositoryTest.java   加測試（Task 1）
  datasource/RatioServiceTest.java           新增（Task 1）
  datasource/DatasourceControllerTest.java   加測試（Task 1）
  transform/UniversalTransformerTest.java    加測試（Task 2）
  dashboard/DashboardDataServiceTest.java    加測試（Task 3）
  jev/HighlightSummarizerTest.java           加測試（Task 3）
  jev/JevControllerTest.java                 加測試（Task 3、Task 5）
  jev/ConfigAssemblerTest.java               加測試（Task 4）
  jev/AnalyzeServiceTest.java                加測試（Task 5）
frontend/src/modules/dashboard/
  charts/percent.js        新增：百分比格式（Task 6）
  charts/bar.js、line.js   percent 時 Y 軸和 tooltip 用百分比（Task 6）
  charts/charts.test.js    加測試（Task 6）
  ChartCard.vue            數字縮寫不蓋掉既有的 formatter（Task 6）
  ChartCard.test.js        加測試（Task 6）
  api.js                   setRatios（Task 7）
  DatasourcePanel.vue      比率區、「新增比率」對話框（Task 7）
  DatasourcePanel.test.js  加測試（Task 7）
  ChartBuilder.vue         欄位標籤接上比率（Task 8）
  ChartBuilder.test.js     加測試（Task 8）
README.md、docs/backlog.md（Task 9）
docs/superpowers/e2e/ratio-metric/margin.csv   手動驗證用（Task 10）
```

---

### Task 1: 比率定義：`Ratio`、V4、`PATCH /ratios`、驗證、敏感

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/Ratio.java`
- Create: `backend/src/main/resources/db/migration/V4__ratio_metric.sql`
- Modify: `backend/src/main/java/jev/dashboard/datasource/Datasource.java`（整個檔案換掉）
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java`
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceService.java`（`setLabels`、新增 `setRatios`、`effective`）
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceController.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/RatioServiceTest.java`（新增）
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`

**Interfaces:**
- Produces：
  - `record jev.dashboard.datasource.Ratio(String name, String numerator, String denominator, boolean sensitive)`；`new Ratio(name, numerator, denominator)`（`sensitive = false`）；`Ratio withSensitive(boolean)`
  - `Datasource` 的第 10 個元件 `List<Ratio> ratios`；原本 8 個、9 個參數的建構子保留（`ratios = List.of()`）；`Datasource withRatios(List<Ratio>)`
  - `void DatasourceRepository.updateRatios(UUID id, List<Ratio> ratios)`
  - `Datasource DatasourceService.setRatios(String userId, UUID id, List<Ratio> ratios)`：整份取代，回傳更新後的來源
  - `DatasourceService.get`、`list`、`load` 回傳的來源，每個比率都帶算好的 `sensitive`
  - `PATCH /api/datasources/{id}/ratios`，body `{"ratios": [...]}`

- [ ] **Step 1: 確認 branch，跑一次既有測試記下基準數量**

```bash
cd /c/GitHub/jev-form-dashboard && git branch --show-current && git status --short
```

Expected：印出 `feat/ratio-metric`，`git status` 沒有東西（或只有這份計畫檔）。branch 不對就停下來回報。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：`Failures: 0, Errors: 0` 和 `BUILD SUCCESS`。記下 `Tests run:` 的數字，之後每個後端 task 結束時的總數＝這個數字＋新加的測試數。這次會在新的 schema 上跑 V1～V3。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：全部通過，同樣記下數字。

- [ ] **Step 2: 寫會失敗的測試**

`DatasourceRepositoryTest.java` 在 class 最後加一個測試（沿用檔案裡現有的 import）：

```java
    @Test
    void ratiosAreStoredInOrderAndRowsWrittenBeforeV4ReadAsAnEmptyList() {
        Datasource upload = new Datasource(UUID.randomUUID(), "銷售", "upload", List.of(), null, Map.of(), "u1", null);
        repo.insert(upload);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().ratios()).isEmpty();

        List<Ratio> ratios = List.of(new Ratio("退貨率", "refund", "sales"), new Ratio("毛利率", "gross_profit", "revenue"));
        repo.updateRatios(upload.id(), ratios);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().ratios()).isEqualTo(ratios);

        // V4 之前寫進去的列沒有 ratios_json，靠欄位預設值
        UUID old = UUID.randomUUID();
        jdbc.sql("insert into datasource (id, name, kind, schema_json, created_by) values (:id, '舊的', 'upload', '[]', 'u1')")
            .param("id", old).update();
        assertThat(repo.find(old, "u1").orElseThrow().ratios()).isEmpty();
    }
```

新增 `backend/src/test/java/jev/dashboard/datasource/RatioServiceTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;

/** B3 spec 4.2、4.3、第 8 節：比率清單的驗證、整份取代和敏感。用記憶體裡的 repository，不連資料庫。 */
class RatioServiceTest {
    final MergedDatasourceServiceTest.MemoryRepo repo = new MergedDatasourceServiceTest.MemoryRepo() {
        @Override
        public void updateRatios(UUID id, List<Ratio> ratios) {
            sources.put(id, sources.get(id).withRatios(ratios));
        }
    };
    final DatasourceService service = new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
        new JevProperties(null, null, null, new JevProperties.Upload(100), null), new ApiUrlGuard(List.of()));

    /** 幾行 CSV 變成 u1 的上傳來源，回傳 id。 */
    UUID upload(String name, String... lines) {
        DatasourceService.Loaded l = CsvSources.csv(name, lines);
        repo.insert(l.datasource());
        repo.insertRows(l.datasource().id(), l.rows());
        return l.datasource().id();
    }

    Datasource set(UUID id, Ratio... ratios) {
        return service.setRatios("u1", id, List.of(ratios));
    }

    @Test
    void everyRuleIsCheckedAndTheNameIsStripped() {
        UUID id = upload("銷售", "region,sales,refund", "北區,100,5");
        service.setLabels("u1", id, Map.of("sales", "銷售金額"));

        assertThat(set(id, new Ratio(" 退貨率 ", "refund", "sales")).ratios())
            .containsExactly(new Ratio("退貨率", "refund", "sales"));

        assertThatThrownBy(() -> set(id, new Ratio("  ", "refund", "sales"))).hasMessage("比率名稱不能空白");
        assertThatThrownBy(() -> set(id, new Ratio("率".repeat(51), "refund", "sales"))).hasMessage("比率名稱最多 50 個字");
        assertThatThrownBy(() -> set(id, new Ratio("region", "refund", "sales")))
            .hasMessage("比率名稱「region」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("銷售金額", "refund", "sales")))
            .hasMessage("比率名稱「銷售金額」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("退貨率", "refund", "sales"), new Ratio(" 退貨率", "sales", "refund")))
            .hasMessage("比率名稱「退貨率」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("率", "region", "sales"))).hasMessage("分子「region」不是這個來源的數字欄位");
        assertThatThrownBy(() -> set(id, new Ratio("率", "refund", "profit"))).hasMessage("分母「profit」不是這個來源的數字欄位");
        assertThatThrownBy(() -> set(id, new Ratio("率", "sales", "sales"))).hasMessage("分子和分母不能是同一個欄位");
        // 驗證失敗不會動到已經存的清單
        assertThat(service.get("u1", id).ratios()).containsExactly(new Ratio("退貨率", "refund", "sales"));
    }

    @Test
    void theWholeListIsReplacedInTheOrderGiven() {
        UUID id = upload("銷售", "sales,refund,qty", "100,5,2");

        set(id, new Ratio("退貨率", "refund", "sales"), new Ratio("件單價", "sales", "qty"));
        assertThat(service.get("u1", id).ratios()).extracting(Ratio::name).containsExactly("退貨率", "件單價");

        set(id, new Ratio("件單價", "sales", "qty"));
        assertThat(service.get("u1", id).ratios()).extracting(Ratio::name).containsExactly("件單價");
        assertThat(set(id).ratios()).isEmpty();
    }

    @Test
    void aRatioIsSensitiveWhenItsNumeratorOrDenominatorIsIncludingInheritedSensitivity() {
        UUID n = upload("北區", "sales,refund", "100,5");
        UUID s = upload("南區", "sales,refund", "80,2");
        set(n, new Ratio("退貨率", "refund", "sales"));
        assertThat(service.get("u1", n).ratios().get(0).sensitive()).isFalse();

        service.markSensitive("u1", n, List.of("sales"));
        assertThat(service.get("u1", n).ratios().get(0).sensitive()).isTrue();
        assertThat(service.list("u1")).filteredOn(d -> d.id().equals(n)).singleElement()
            .satisfies(d -> assertThat(d.ratios().get(0).sensitive()).isTrue());

        // 合併來源不會帶著輸入來源的比率；自己加的比率，分母從北區繼承了敏感
        UUID m = service.createMerged("u1", "全區", MergedDatasourceServiceTest.union(List.of(n, s), "sales", "refund")).id();
        assertThat(service.get("u1", m).ratios()).isEmpty();
        set(m, new Ratio("退貨率", "refund", "sales"));
        assertThat(service.get("u1", m).ratios().get(0).sensitive()).isTrue();

        // 輸入來源取消敏感，比率跟著不敏感
        service.markSensitive("u1", n, List.of());
        assertThat(service.get("u1", m).ratios().get(0).sensitive()).isFalse();
    }

    @Test
    void aDisplayNameCannotTakeARatiosName() {
        UUID id = upload("銷售", "sales,refund", "100,5");
        set(id, new Ratio("退貨率", "refund", "sales"));

        assertThatThrownBy(() -> service.setLabels("u1", id, Map.of("sales", "退貨率"))).hasMessage("顯示名稱「退貨率」重複");
    }
}
```

`DatasourceControllerTest.java` 在 class 最後加一個測試（沿用檔案裡現有的 import）：

```java
    @Test
    void ratiosArePatchedAsAWholeListValidatedAndListedWithTheirSensitivity() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].ratios.length()").value(0));

        mvc.perform(patch("/api/datasources/" + id + "/ratios").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\" 單價 \",\"numerator\":\"amount\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios[0].name").value("單價"))
            .andExpect(jsonPath("$.ratios[0].numerator").value("amount"))
            .andExpect(jsonPath("$.ratios[0].denominator").value("qty"))
            .andExpect(jsonPath("$.ratios[0].sensitive").value(false));

        // 分母標成敏感，比率跟著敏感
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"qty\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios[0].sensitive").value(true));
        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].ratios[0].sensitive").value(true));

        mvc.perform(patch("/api/datasources/" + id + "/ratios").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\"x\",\"numerator\":\"region\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("分子「region」不是這個來源的數字欄位"));
        mvc.perform(patch("/api/datasources/" + id + "/ratios").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"ratios\":[]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));

        // 沒帶 ratios 就是清空
        mvc.perform(patch("/api/datasources/" + id + "/ratios").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios.length()").value(0));
    }
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DatasourceRepositoryTest,RatioServiceTest,DatasourceControllerTest'`
Expected：編譯失敗，找不到 `Ratio`、`updateRatios`、`setRatios`、`withRatios`。

- [ ] **Step 4: `Ratio` 和 V4**

新增 `backend/src/main/java/jev/dashboard/datasource/Ratio.java`：

```java
package jev.dashboard.datasource;

/**
 * B3 spec 4.1：比率＝每組分子加總 ÷ 分母加總。numerator、denominator 是欄位的原始名稱。
 * sensitive 是讀取來源時從分子、分母算出來的（spec 4.3），存進 ratios_json 的一律是 false。
 */
public record Ratio(String name, String numerator, String denominator, boolean sensitive) {
    public Ratio(String name, String numerator, String denominator) {
        this(name, numerator, denominator, false);
    }

    public Ratio withSensitive(boolean value) {
        return new Ratio(name, numerator, denominator, value);
    }
}
```

新增 `backend/src/main/resources/db/migration/V4__ratio_metric.sql`：

```sql
-- B3 spec 4.1：每個來源的比率清單，順序就是使用者加入的順序；舊的來源是空清單，不用搬資料
alter table datasource add column ratios_json jsonb not null default '[]';
```

- [ ] **Step 5: `Datasource` 多 `ratios`**

`Datasource.java` 整個換成：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * merge 只有合併來源（kind = merged）才有，其他來源是 null，JSON 不輸出。
 * ratios 是使用者加的比率（B3 spec 4.1），沒有就是空清單。
 */
public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MergeDefinition merge, List<Ratio> ratios) {
    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, null, List.of());
    }

    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt, MergeDefinition merge) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, List.of());
    }

    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios);
    }

    public Datasource withRatios(List<Ratio> value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, value);
    }
}
```

- [ ] **Step 6: repository 讀寫 `ratios_json`**

`DatasourceRepository.java`：

`private static final TypeReference<MergeDefinition> MERGE = new TypeReference<>() {};` 下一行加：

```java
    private static final TypeReference<List<Ratio>> RATIOS = new TypeReference<>() {};
```

`public void insert(Datasource d) {` 上一行加：

```java
    /** ratios_json 不寫，用欄位預設的空清單：新建的來源一律沒有比率（B3 spec 第 7 節）。 */
```

`updateSchema` 方法後面加：

```java
    public void updateRatios(UUID id, List<Ratio> ratios) {
        jdbc.sql("update datasource set ratios_json = cast(:ratios as jsonb) where id = :id")
            .param("ratios", write(ratios)).param("id", id)
            .update();
    }
```

`map` 方法裡：

```java
            merge == null ? null : read(merge, MERGE));
```

換成：

```java
            merge == null ? null : read(merge, MERGE),
            read(rs.getString("ratios_json"), RATIOS));
```

- [ ] **Step 7: `setRatios`、比率的敏感、顯示名稱不能撞比率**

`DatasourceService.setLabels` 裡：

```java
        // 圖表的 dataset 用顯示名稱當維度名，重複了 ECharts 會分不出來
        Set<String> seen = new HashSet<>();
```

換成：

```java
        // 圖表的 dataset 用顯示名稱當維度名，重複了 ECharts 會分不出來；比率名稱也會當維度名（B3）
        Set<String> seen = ds.ratios().stream().map(Ratio::name).collect(Collectors.toCollection(HashSet::new));
```

`setLabels` 方法後面加：

```java
    /**
     * B3 spec 4.2：整份取代。名稱去掉頭尾空白，不能跟欄位名稱、顯示名稱或其他比率重複；
     * 分子、分母要是這個來源的兩個不同的數字欄位。存進去的 sensitive 一律是 false，讀取時才算。
     */
    public Datasource setRatios(String userId, UUID id, List<Ratio> ratios) {
        Datasource ds = stored(userId, id);
        Set<String> taken = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (FieldSchema f : ds.schema()) {
            taken.add(f.name());
            if (f.label() != null) taken.add(f.label());
            if (f.type() == FieldType.NUMBER) numbers.add(f.name());
        }
        List<Ratio> out = new ArrayList<>();
        for (Ratio r : ratios) {
            String name = r == null || r.name() == null ? "" : r.name().strip();
            if (name.isEmpty()) throw new ApiException(BAD_REQUEST, "比率名稱不能空白");
            if (name.length() > 50) throw new ApiException(BAD_REQUEST, "比率名稱最多 50 個字");
            if (!taken.add(name)) throw new ApiException(BAD_REQUEST, "比率名稱「" + name + "」跟欄位或其他比率重複");
            if (!numbers.contains(r.numerator())) {
                throw new ApiException(BAD_REQUEST, "分子「" + r.numerator() + "」不是這個來源的數字欄位");
            }
            if (!numbers.contains(r.denominator())) {
                throw new ApiException(BAD_REQUEST, "分母「" + r.denominator() + "」不是這個來源的數字欄位");
            }
            if (r.numerator().equals(r.denominator())) throw new ApiException(BAD_REQUEST, "分子和分母不能是同一個欄位");
            out.add(new Ratio(name, r.numerator(), r.denominator()));
        }
        repo.updateRatios(id, out);
        return get(userId, id);
    }
```

`effective` 連同它上面的註解：

```java
    /** spec 6.3：合併來源實際的敏感設定＝自己勾的，或任何一個提供這欄的輸入欄位是敏感的。讀取時才算，不存。 */
    private Datasource effective(Datasource ds, Function<UUID, Datasource> find) {
        return "merged".equals(ds.kind()) ? ds.withSchema(inherit(ds.merge(), ds.schema(), find)) : ds;
    }
```

換成：

```java
    /**
     * spec 6.3：合併來源實際的敏感設定＝自己勾的，或任何一個提供這欄的輸入欄位是敏感的。
     * B3 spec 4.3：分子或分母敏感（含繼承）時，比率也敏感。兩者都是讀取時才算，不存。
     */
    private Datasource effective(Datasource ds, Function<UUID, Datasource> find) {
        Datasource d = "merged".equals(ds.kind()) ? ds.withSchema(inherit(ds.merge(), ds.schema(), find)) : ds;
        Set<String> sensitive = d.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name)
            .collect(Collectors.toSet());
        return d.withRatios(d.ratios().stream()
            .map(r -> r.withSensitive(sensitive.contains(r.numerator()) || sensitive.contains(r.denominator()))).toList());
    }
```

- [ ] **Step 8: `PATCH /ratios`**

`DatasourceController.java` 的 `public record FieldLabels(Map<String, String> labels) {}` 下一行加：

```java
    public record RatioList(List<Ratio> ratios) {}
```

`labels` 方法後面加：

```java
    /** B3 spec 第 7 節：整份取代，新增、刪除都用這支。 */
    @PatchMapping("/{id}/ratios")
    public Datasource ratios(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                             @RequestBody RatioList body) {
        return service.setRatios(userId, id, body.ratios() == null ? List.of() : body.ratios());
    }
```

- [ ] **Step 9: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DatasourceRepositoryTest,RatioServiceTest,DatasourceControllerTest,MergedDatasourceServiceTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋6，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 10: Commit**

```bash
F="backend/src/main/java/jev/dashboard/datasource/Ratio.java backend/src/main/resources/db/migration/V4__ratio_metric.sql backend/src/main/java/jev/dashboard/datasource/Datasource.java backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java backend/src/main/java/jev/dashboard/datasource/DatasourceService.java backend/src/main/java/jev/dashboard/datasource/DatasourceController.java backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java backend/src/test/java/jev/dashboard/datasource/RatioServiceTest.java backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java"
git add $F && git commit -m "feat(backend): 資料來源可以加比率，PATCH 整份取代，讀取時算敏感" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 2: 轉換器：每組先加總再相除、`percent`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/transform/ChartData.java`（整個檔案換掉）
- Modify: `backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java`（`transform`、`grouped`，另外加 `divide`）
- Test: `backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `Ratio`
- Produces：
  - `ChartData` 的第 10 個元件 `Boolean percent`（只有比率是 `true`，其他是 `null`、JSON 不輸出）；原本 9、8、7、6 個參數的建構子保留；`withLabels`、`withSource` 會保留 `percent`
  - `static ChartData UniversalTransformer.transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields, List<Ratio> ratios)`；原本三個參數的版本保留（等於沒有比率）

- [ ] **Step 1: 寫會失敗的測試**

`UniversalTransformerTest.java` 的 import 加上：

```java
import jev.dashboard.datasource.Ratio;
```

在 class 最後加：

```java
    /** B3：對照來源的資料列，銷售、退貨各自一列。4 月那列兩邊都沒有值，最後一列沒有日期。 */
    static final List<Map<String, Object>> RATIO_ROWS = List.of(
        Map.of("date", "2026-01-05", "region", "北區", "sales", 100.0),
        Map.of("date", "2026-01-20", "region", "南區", "sales", 300.0),
        Map.of("date", "2026-02-03", "region", "北區", "sales", 400.0),
        Map.of("date", "2026-02-10", "region", "北區", "refund", 10.0),
        Map.of("date", "2026-02-11", "region", "東區", "refund", 5.0),
        Map.of("date", "2026-03-01", "region", "南區", "sales", 0.0),
        Map.of("date", "2026-03-02", "region", "南區", "refund", 3.0),
        Map.of("date", "2026-04-01", "region", "西區"),
        Map.of("region", "北區", "refund", 7.0));
    static final Set<String> RATIO_FIELDS = Set.of("date", "region", "sales", "refund");
    static final Ratio RETURN_RATE = new Ratio("退貨率", "refund", "sales");

    static ChartSpec spec(String type, Mapping m) {
        return new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    static ChartData ratioChart(String type, Mapping m) {
        return UniversalTransformer.transform(spec(type, m), RATIO_ROWS, RATIO_FIELDS, List.of(RETURN_RATE));
    }

    @Test
    void aRatioSumsTheNumeratorAndTheDenominatorOfEachGroupBeforeDividing() {
        List<Map<String, Object>> rows = List.of(
            Map.of("region", "北區", "sales", 100.0, "refund", 10.0),
            Map.of("region", "北區", "sales", 300.0, "refund", 10.0));

        ChartData d = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), rows, RATIO_FIELDS,
            List.of(RETURN_RATE));

        // 20 ÷ 400；逐列相除再加總會是 0.1 + 0.033…，再平均是 0.066…
        assertThat(d.dimensions()).containsExactly("region", "退貨率");
        assertThat(d.rows()).isEqualTo(List.of(List.of("北區", 0.05)));
    }

    @Test
    void aGroupWithoutANumeratorOrADenominatorOrWithAZeroDenominatorIsNullButKeepsItsPlace() {
        ChartData d = ratioChart("line", xv("date", "退貨率", "sum", "month"));

        // 1 月沒有退貨、3 月銷售加總是 0，這兩期是 null；4 月兩邊都沒有值，不輸出；沒有日期的那列跳過
        assertThat(d.rows()).isEqualTo(List.of(
            Arrays.asList("2026-01", null), List.of("2026-02", 0.0375), Arrays.asList("2026-03", null)));
    }

    @Test
    void byCategoryARatioSortsLargestFirstWithNullsLastWhateverTheAggregateSays() {
        // 北區 17 ÷ 500、南區 3 ÷ 300、東區沒有銷售；西區兩邊都沒有值，不輸出
        List<List<Object>> expected = List.of(List.of("北區", 0.034), List.of("南區", 0.01), Arrays.asList("東區", null));
        for (String agg : Arrays.asList("sum", "avg", "count", "none", null)) {
            assertThat(ratioChart("bar", xv("region", "退貨率", agg, null)).rows()).as(String.valueOf(agg)).isEqualTo(expected);
        }
        // 堆疊長條走同一段分組：先照期別、再照分類排
        ChartData stacked = ratioChart("stacked_bar", new Mapping("date", null, "region", "退貨率", null, null, "sum", "quarter"));
        assertThat(stacked.dimensions()).containsExactly("date", "region", "退貨率");
        assertThat(stacked.rows()).isEqualTo(List.of(
            List.of("2026-Q1", "北區", 0.02), List.of("2026-Q1", "南區", 0.01), Arrays.asList("2026-Q1", "東區", null)));
    }

    @Test
    void onlyRatioChartsArePercent() throws Exception {
        assertThat(ratioChart("line", xv("date", "退貨率", "sum", "month")).percent()).isTrue();
        ChartData plain = run("bar", xv("region", "amount", "sum", null));
        assertThat(plain.percent()).isNull();
        assertThat(JSON.writeValueAsString(plain)).doesNotContain("percent");
    }

    @Test
    void aRatioIsMissingWhenItsFieldsAreGoneOrWhenItSitsOutsideValue() {
        ChartData noRefund = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), RATIO_ROWS,
            Set.of("date", "region", "sales"), List.of(RETURN_RATE));
        assertThat(noRefund.error()).isEqualTo("FIELD_MISSING");
        assertThat(noRefund.missingField()).isEqualTo("refund");

        assertThat(ratioChart("bar", xv("退貨率", "sales", "sum", null)).missingField()).isEqualTo("退貨率");
        assertThat(ratioChart("line", new Mapping("date", null, null, null, List.of("sales", "退貨率"), null, "sum", "month"))
            .missingField()).isEqualTo("退貨率");
        // 比率刪掉之後，用到它的卡片是 FIELD_MISSING
        ChartData deleted = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), RATIO_ROWS,
            RATIO_FIELDS, List.of());
        assertThat(deleted.missingField()).isEqualTo("退貨率");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='UniversalTransformerTest'`
Expected：編譯失敗，沒有四個參數的 `transform`，`ChartData` 沒有 `percent()`。

- [ ] **Step 3: `ChartData` 多 `percent`**

`ChartData.java` 整個換成：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * truncated 只在明細表被截斷時是 true，其他時候是 null、JSON 不輸出。
 * title 是套上欄位顯示名稱後的卡片標題；null 時前端照用 spec 的標題。
 * source 是這張圖的來源名稱，多來源的儀表板用它標在卡片上。
 * percent 只在數值欄是比率時是 true（B3 spec 5.1），前端把 Y 軸和 tooltip 顯示成百分比；其他時候是 null、JSON 不輸出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField, Boolean truncated, String title, String source,
                        Boolean percent) {
    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title, String source) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, source, null);
    }

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
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, source, percent);
    }

    public ChartData withSource(String value) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, value, percent);
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

- [ ] **Step 4: `transform` 認得比率**

`UniversalTransformer.java` 的 import 加上：

```java
import jev.dashboard.datasource.Ratio;
```

`transform` 整個方法：

```java
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields) {
        Mapping m = spec.mapping();
        for (String field : referencedFields(m)) {
            if (!availableFields.contains(field)) return ChartData.missing(spec.chartId(), spec.type(), field);
        }
        String agg = m.aggregate() != null ? m.aggregate() : defaultAggregate(spec.type());
        return switch (spec.type()) {
            case "bar", "line" -> m.measures() != null
                ? measured(spec, rows, m.x(), m.measures(), agg, m.timeGrain())
                : grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
            case "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
            case "stacked_bar" -> grouped(spec, rows, List.of(m.x(), m.series()), m.value(), agg, m.timeGrain());
            case "heatmap" -> grouped(spec, rows, List.of(m.x(), m.y()), m.value(), agg, m.timeGrain());
```

（到 `case "heatmap"` 這一行為止，後面的 `radar`、`scatter`、`table`、`default` 不動）換成：

```java
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields) {
        return transform(spec, rows, availableFields, List.of());
    }

    /** ratios 是這個來源的比率定義（B3 spec 5.1）；value 是比率名稱時，每組分子、分母各自加總再相除。 */
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios) {
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
        return switch (spec.type()) {
            case "bar", "line" -> m.measures() != null
                ? measured(spec, rows, m.x(), m.measures(), agg, m.timeGrain())
                : grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain(), ratio);
            case "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain(), ratio);
            case "stacked_bar" -> grouped(spec, rows, List.of(m.x(), m.series()), m.value(), agg, m.timeGrain(), ratio);
            case "heatmap" -> grouped(spec, rows, List.of(m.x(), m.y()), m.value(), agg, m.timeGrain(), ratio);
```

- [ ] **Step 5: `grouped` 的比率分支**

`grouped` 整個方法換成（非比率的部分跟原本一樣；單一分組欄位的排序改用能放 `null` 的比較器，順序不變）：

```java
    private static ChartData grouped(ChartSpec spec, List<Map<String, Object>> rows, List<String> keys,
                                     String value, String agg, String grain, Ratio ratio) {
        List<String> dims = new ArrayList<>(keys);
        dims.add(value != null ? value : "count");
        // B3 spec 5.1：比率不管 aggregate 寫什麼（包括 none），都是每組先加總再相除
        if (agg.equals("none") && ratio == null) return project(spec, rows, dims, true, Integer.MAX_VALUE);

        // [加總, 筆數]；比率是 [分子加總, 分子筆數, 分母加總, 分母筆數]
        Map<List<Object>, double[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                Object v = row.get(keys.get(i));
                key.add(i == 0 && grain != null ? TimeBucket.of(v, grain) : v);
            }
            if (key.contains(null)) continue;
            if (ratio != null) {
                Object num = row.get(ratio.numerator());
                Object den = row.get(ratio.denominator());
                if (!(num instanceof Number) && !(den instanceof Number)) continue; // 兩邊都沒有值的列不算
                double[] a = acc.computeIfAbsent(key, k -> new double[4]);
                if (num instanceof Number nv) {
                    a[0] += nv.doubleValue();
                    a[1]++;
                }
                if (den instanceof Number dv) {
                    a[2] += dv.doubleValue();
                    a[3]++;
                }
                continue;
            }
            Object v = value == null ? null : row.get(value);
            // B2 spec 5.4：count 只算 value 不是空值的列；沒有 value 時算列數
            if (agg.equals("count") ? value != null && v == null : !(v instanceof Number)) continue;
            double[] a = acc.computeIfAbsent(key, k -> new double[2]);
            if (v instanceof Number n) a[0] += n.doubleValue();
            a[1]++;
        }

        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>(key);
            // 不能寫成三元運算：divide 回 Double、result 回 double，比率是 null 時會在拆箱丟 NPE
            if (ratio != null) r.add(divide(a));
            else r.add(result(agg, a));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> sortKey(r, keys.size())));
        } else if (keys.size() == 1) {
            // 比率可能是 null，排最後
            out.sort(Comparator.comparing((List<Object> r) -> (Double) r.get(1),
                Comparator.nullsLast(Comparator.<Double>reverseOrder())));
        }
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null, null, null, null,
            ratio != null ? Boolean.TRUE : null);
    }

    /** B3 spec 5.1：缺分子、缺分母，或分母加總是 0，比率是 null（空白，不是 0%）。 */
    private static Double divide(double[] a) {
        return a[1] == 0 || a[3] == 0 || a[2] == 0 ? null : a[0] / a[2];
    }
```

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='UniversalTransformerTest,DashboardDataServiceTest,HighlightSummarizerTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋11，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/transform/ChartData.java backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java"
git add $F && git commit -m "feat(backend): 轉換器算比率，每組先加總再相除" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 3: 儀表板資料和異常檢查：傳入比率、百分點、敏感比率

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java`（`Source`、`build`、`loaded`）
- Modify: `backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java`（`summarize`、`keepBiggestChanges`，另外加 `put`、`points`）
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`（`highlight`）
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java`
- Test: `backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java`
- Test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `Datasource.ratios()`、`Ratio.sensitive()`、`PATCH /ratios`；Task 2 的四個參數的 `transform`、`ChartData.percent()`
- Produces：
  - `DashboardDataService.load`、`display` 的比率卡片有 `percent: true`
  - 比率圖的異常檢查摘要：系列是 `{name, previous, current, changePoints}`（百分比數字、小數一位），外層多 `unit: "%"`
  - `JevController.highlight` 跳過用到敏感比率的卡片

- [ ] **Step 1: 寫會失敗的測試**

`DashboardDataServiceTest.java` 的 import 加上：

```java
import java.util.Arrays;
import jev.dashboard.datasource.Ratio;
```

在 class 最後加：

```java
    @Test
    void ratioChartsGetTheSourcesRatiosTheirTitleUsesTheXLabelAndBothViewsArePercent() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 3, List.of(), false, null, null, "日期"),
            new FieldSchema("sales", FieldType.NUMBER, 2, List.of(), false, null, null, "銷售額"),
            new FieldSchema("refund", FieldType.NUMBER, 1, List.of(), false, null, null, "退貨額"));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null)
            .withRatios(List.of(new Ratio("退貨率", "refund", "sales")));
        List<Map<String, Object>> rows = List.of(Map.of("date", "2026-06-10", "sales", 50.0),
            Map.of("date", "2026-07-03", "sales", 100.0), Map.of("date", "2026-07-09", "refund", 5.0));
        DashboardDataService service = new DashboardDataService(sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>()));
        ChartSpec line = new ChartSpec("c1", "line", "退貨率 依 date", POS,
            new Mapping("date", null, null, "退貨率", null, null, "sum", "month"), Map.of(), id);

        ChartData shown = service.display("u1", config(line)).get(0);

        // 比率名稱是使用者取的，沒有顯示名稱；x 軸照舊換
        assertThat(shown.title()).isEqualTo("退貨率 依 日期");
        assertThat(shown.dimensions()).isEqualTo(List.of("日期", "退貨率"));
        assertThat(shown.rows()).isEqualTo(List.of(Arrays.asList("2026-06", null), List.of("2026-07", 0.05)));
        assertThat(shown.percent()).isTrue();
        ChartData loaded = service.load("u1", config(line)).get(0);
        assertThat(loaded.dimensions()).isEqualTo(List.of("date", "退貨率"));
        assertThat(loaded.percent()).isTrue();
    }
```

`HighlightSummarizerTest.java` 在 class 最後加：

```java
    static final Mapping RETURN_RATE = new Mapping("date", null, null, "退貨率", null, null, "sum", "month");

    static ChartData percent(String type, List<String> dimensions, List<List<Object>> rows) {
        return new ChartData("c1", type, dimensions, rows, null, null, null, null, null, true);
    }

    @Test
    void ratioChartsComparePercentagesAndTheChangeInPoints() {
        ChartData d = percent("line", List.of("date", "退貨率"),
            List.of(r("2024-04", 0.03), r("2024-05", 0.0521), r("2024-06", 0.0649)));

        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", RETURN_RATE), d, TODAY);

        assertThat(s).containsEntry("metric", "退貨率").containsEntry("unit", "%")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 5.2, "current", 6.5, "changePoints", 1.3)));
    }

    @Test
    void aRatioSeriesWithAnEmptyPeriodIsNotComparedAndACardWithNothingComparableIsSkipped() {
        Mapping bySeries = new Mapping("date", null, "region", "退貨率", null, null, "sum", "month");
        ChartData d = percent("stacked_bar", List.of("date", "region", "退貨率"), List.of(
            r("2024-05", "北", 0.04), r("2024-05", "南", null),
            r("2024-06", "北", 0.05), r("2024-06", "南", 0.02), r("2024-06", "東", 0.1)));

        // 南區前一期是空白、東區前一期沒有資料，都不比；沒有值不代表比率是 0
        assertThat(HighlightSummarizer.summarize(spec("stacked_bar", bySeries), d, TODAY).get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 4.0, "current", 5.0, "changePoints", 1.0)));

        ChartData empty = percent("line", List.of("date", "退貨率"), List.of(r("2024-05", null), r("2024-06", 0.05)));
        assertThat(HighlightSummarizer.summarize(spec("line", RETURN_RATE), empty, TODAY)).isNull();
    }
```

`JevControllerTest.java` 在 class 最後加：

```java
    @Test
    void highlightSendsRatioCardsInPercentPointsAndSkipsThemOnceTheRatioIsSensitive() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + ds + "/ratios").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\"單價\",\"numerator\":\"amount\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isOk());
        String dashboardJson = """
            {"name":"單價","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"line","title":"單價 依 order_date","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"單價","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"line","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}}
            ]}""".formatted(ds);
        String body = mvc.perform(post("/api/dashboards").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(dashboardJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u1")).andExpect(status().isOk());
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c1\"").contains("\"unit\":\"%\"").contains("\"changePoints\"");

        // 分子標成敏感，比率跟著敏感，那張卡整張不送
        mvc.perform(patch("/api/datasources/" + ds + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"amount\"]}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u1")).andExpect(status().isOk());
        assertThat(JEV.bodies).hasSize(2);
        assertThat(JEV.bodies.get(1)).contains("\"anomaly_c2\"").doesNotContain("anomaly_c1");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,HighlightSummarizerTest,JevControllerTest'`
Expected：三個新測試失敗。`DashboardDataServiceTest` 是 `FIELD_MISSING`（還沒把比率傳進轉換器）；`HighlightSummarizerTest` 找不到 `unit`、`changePoints`；`JevControllerTest` 的 body 沒有 `"unit":"%"`。

- [ ] **Step 3: `DashboardDataService` 傳比率**

import 加上：

```java
import jev.dashboard.datasource.Ratio;
```

`Source` record 連同上面的註解換成：

```java
    /**
     * 一個來源載入一次的結果。載入失敗時 rows 是 null；name 是查得到的來源名稱，查不到是 null。
     * ratios 是這個來源的比率定義（B3），交給轉換器。
     */
    private record Source(String name, List<Map<String, Object>> rows, Set<String> available, Map<String, String> labels,
                          List<Ratio> ratios) {
        static Source failed(String name) {
            return new Source(name, null, null, null, null);
        }
    }
```

`build` 裡：

```java
            ChartData d = UniversalTransformer.transform(s, src.rows(), src.available());
```

換成：

```java
            ChartData d = UniversalTransformer.transform(s, src.rows(), src.available(), src.ratios());
```

`loaded` 最後一行：

```java
        return new Source(loaded.datasource().name(), loaded.rows(), available, labels);
```

換成：

```java
        return new Source(loaded.datasource().name(), loaded.rows(), available, labels, loaded.datasource().ratios());
```

標題和維度不用改：`relabelled` 只換有顯示名稱的欄位，比率名稱不在 `labels` 裡，所以只有 x 軸會換；`withLabels` 會保留 `percent`（Task 2）。

- [ ] **Step 4: `HighlightSummarizer` 比百分點**

`summarize` 裡從 `List<String> measures = spec.mapping().measures();` 到 `out.put("metric", …);` 那兩行為止：

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
        if (bySeries.size() > MAX_SERIES) bySeries = keepBiggestChanges(bySeries);

        List<Map<String, Object>> series = new ArrayList<>();
        bySeries.forEach((name, v) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("previous", v[0]);
            s.put("current", v[1]);
            s.put("changePercent", v[0] == 0 ? null : Math.round((v[1] - v[0]) / Math.abs(v[0]) * 1000) / 10.0);
            series.add(s);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", spec.title());
        out.put("metric", measures != null ? String.join("、", measures)
            : spec.mapping().value() == null ? "count" : spec.mapping().value());
```

換成：

```java
        List<String> measures = spec.mapping().measures();
        boolean hasSeries = measures == null && data.dimensions().size() == 3;
        // B3 spec 5.4：比率的圖比百分點；沒有值不代表比率是 0，不能當 0
        boolean percent = Boolean.TRUE.equals(data.percent());

        Map<String, double[]> bySeries = new LinkedHashMap<>(); // [前一期, 最後一期]
        for (List<Object> row : data.rows()) {
            String period = String.valueOf(row.get(0));
            int slot = period.equals(current) ? 1 : period.equals(previous) ? 0 : -1;
            if (slot < 0) continue;
            if (measures != null) {
                // B2 spec 5.7：兩個數值的圖，每個數值當一條系列，名稱是原始欄位名稱
                for (int i = 1; i < row.size(); i++) add(bySeries, data.dimensions().get(i), slot, row.get(i));
            } else if (percent) {
                put(bySeries, hasSeries ? String.valueOf(row.get(1)) : "全部", slot, row.get(row.size() - 1));
            } else {
                add(bySeries, hasSeries ? String.valueOf(row.get(1)) : "全部", slot, row.get(row.size() - 1));
            }
        }
        if (percent) {
            // 前一期或最後一期是空白的系列不比；一條都不能比，這張圖就不做異常檢查
            bySeries.values().removeIf(v -> Double.isNaN(v[0]) || Double.isNaN(v[1]));
            if (bySeries.isEmpty()) return null;
        }
        if (bySeries.size() > MAX_SERIES) bySeries = keepBiggestChanges(bySeries, !percent);

        List<Map<String, Object>> series = new ArrayList<>();
        bySeries.forEach((name, v) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            if (percent) {
                s.put("previous", points(v[0]));
                s.put("current", points(v[1]));
                s.put("changePoints", points(v[1] - v[0]));
            } else {
                s.put("previous", v[0]);
                s.put("current", v[1]);
                s.put("changePercent", v[0] == 0 ? null : Math.round((v[1] - v[0]) / Math.abs(v[0]) * 1000) / 10.0);
            }
            series.add(s);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", spec.title());
        out.put("metric", measures != null ? String.join("、", measures)
            : spec.mapping().value() == null ? "count" : spec.mapping().value());
        if (percent) out.put("unit", "%");
```

`add` 方法後面加：

```java
    /** 比率：沒有值就是 NaN，不當 0。 */
    private static void put(Map<String, double[]> bySeries, String name, int slot, Object v) {
        bySeries.computeIfAbsent(name, k -> new double[] {Double.NaN, Double.NaN})[slot] =
            v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** 比率（0.052）換成百分比數字（5.2），四捨五入到小數一位。 */
    private static double points(double ratio) {
        return Math.round(ratio * 1000) / 10.0;
    }
```

`keepBiggestChanges` 的註解和簽名：

```java
    /** 留變化 |本期 − 前期| 最大的 MAX_SERIES 個（同分照名字排），其餘加總成「其他」；資料本來就有「其他」就併進去。 */
    private static Map<String, double[]> keepBiggestChanges(Map<String, double[]> bySeries) {
```

換成：

```java
    /**
     * 留變化 |本期 − 前期| 最大的 MAX_SERIES 個（同分照名字排），其餘加總成「其他」；資料本來就有「其他」就併進去。
     * sumRest 是 false 時（比率加起來沒有意義），其餘直接不送。
     */
    private static Map<String, double[]> keepBiggestChanges(Map<String, double[]> bySeries, boolean sumRest) {
```

同一個方法裡：

```java
        kept.merge("其他", rest, (a, b) -> new double[] {a[0] + b[0], a[1] + b[1]});
```

換成：

```java
        if (sumRest) kept.merge("其他", rest, (a, b) -> new double[] {a[0] + b[0], a[1] + b[1]});
```

- [ ] **Step 5: `JevController.highlight` 加上敏感的比率**

import 加上：

```java
import java.util.stream.Stream;
import jev.dashboard.datasource.Ratio;
```

`highlight` 裡：

```java
        // 每個來源各自的敏感欄位（多來源 spec 第 7 節）
        Map<UUID, Set<String>> sensitive = datasources.list(userId).stream().collect(Collectors.toMap(Datasource::id,
            ds -> ds.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name).collect(Collectors.toSet())));
```

換成：

```java
        // 每個來源各自的敏感欄位（多來源 spec 第 7 節），加上分子或分母敏感的比率（B3 spec 5.4）
        Map<UUID, Set<String>> sensitive = datasources.list(userId).stream().collect(Collectors.toMap(Datasource::id,
            ds -> Stream.concat(ds.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name),
                ds.ratios().stream().filter(Ratio::sensitive).map(Ratio::name)).collect(Collectors.toSet())));
```

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DashboardDataServiceTest,HighlightSummarizerTest,HighlightServiceTest,JevControllerTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋15，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/test/java/jev/dashboard/dashboard/DashboardDataServiceTest.java backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java"
git add $F && git commit -m "feat(backend): 比率圖的資料帶 percent，異常檢查改比百分點" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 4: `ConfigAssembler`：比率的候選、組合、排序

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`
- Test: `backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `Ratio`
- Produces：
  - `FieldEval(FieldSchema field, double importance, String role, boolean focus, Ratio ratio)`；三個、四個參數的建構子保留（`ratio = null`）
  - `static List<FieldEval> ConfigAssembler.evaluate(List<FieldSchema> schema, List<Ratio> ratios, Map<String, Answer> answers)`：比率接在欄位後面，第 j 個比率讀 `imp_<n+j>`、`rel_<n+j>`，角色固定是 `measure`，`field` 是用比率名稱做的 `FieldSchema`（型別 `number`）
  - `Combo.hasRatio()`（package-private）
  - `candidates`、`ranked`、`combos` 的簽名不變，行為照 spec 5.3

- [ ] **Step 1: 寫會失敗的測試**

`ConfigAssemblerTest.java` 的 import 加上：

```java
import jev.dashboard.datasource.Ratio;
```

在 class 最後加：

```java
    static final Ratio RETURN_RATE = new Ratio("退貨率", "refund", "sales");

    /** 比率的 FieldEval，跟 ConfigAssembler.evaluate 做出來的一樣。 */
    static FieldEval ratio(Ratio r, double importance) {
        return new FieldEval(new FieldSchema(r.name(), NUMBER, 0, List.of(), false, null, null), importance, "measure", false, r);
    }

    @Test
    void evaluateAddsEachRatioAfterTheFieldsAsAMeasureWithItsOwnImportanceAndRelevance() {
        List<FieldSchema> schema = List.of(
            new FieldSchema("sales", NUMBER, 9, List.of(), false, null, null),
            new FieldSchema("refund", NUMBER, 5, List.of(), false, null, null));
        Ratio margin = new Ratio("毛利率", "refund", "sales");
        Map<String, Answer> answers = Map.of("imp_2", score(2.0), "rel_2", rel(2, 0.9), "imp_3", score(1.0));

        List<FieldEval> evals = ConfigAssembler.evaluate(schema, List.of(RETURN_RATE, margin), answers);

        assertThat(evals).extracting(FieldEval::name, FieldEval::importance, FieldEval::role, FieldEval::focus).containsExactly(
            tuple("sales", 0.0, "ignore", false), tuple("refund", 0.0, "ignore", false),
            tuple("退貨率", 2.0, "measure", true), tuple("毛利率", 1.0, "measure", false));
        assertThat(evals).extracting(FieldEval::ratio).containsExactly(null, null, RETURN_RATE, margin);
    }

    @Test
    void ratiosAreAlwaysCandidatesRightAfterTheFocusFields() {
        List<FieldEval> evals = List.of(
            eval("amount", NUMBER, 12, 3, "measure"),
            ratio(new Ratio("毛利率", "gross_profit", "amount"), 0),
            focus(eval("region", STRING, 4, 0, "dimension")),
            ratio(RETURN_RATE, 2),
            eval("memo", STRING, 12, 3, "ignore"));

        // 重要性是 0 的比率也進候選；比率之間照重要性排
        assertThat(ConfigAssembler.candidates(evals)).extracting(FieldEval::name)
            .containsExactly("region", "退貨率", "毛利率", "amount");
    }

    @Test
    void aRatioIsDrawnByTimeAsLineOrBarAndByCategoryAsBarOnly() {
        FieldEval date = compareCandidates().get(0);
        List<FieldEval> cands = List.of(date, eval("region", STRING, 4, 2, "dimension"),
            eval("sales", NUMBER, 9, 2, "measure"), eval("qty", NUMBER, 5, 1, "measure"), ratio(RETURN_RATE, 1));

        List<Combo> ranked = ConfigAssembler.ranked(cands);

        // 不出圓餅（region 只有 4 個分類也不出）、散佈、堆疊、熱力
        assertThat(ranked).filteredOn(c -> names(c).contains("退貨率"))
            .extracting(ConfigAssemblerTest::names, Combo::chartTypes).containsExactly(
                tuple(List.of("date", "退貨率"), List.of("line", "bar")),
                tuple(List.of("region", "退貨率"), List.of("bar")));
        // 比率不算進雷達圖的數值：只剩 sales、qty 兩個，不到三個
        assertThat(ranked).noneMatch(c -> c.chartTypes().contains("radar"));
        // region 配一般數值照舊有圓餅
        assertThat(ranked).filteredOn(c -> names(c).equals(List.of("region", "sales"))).singleElement()
            .extracting(Combo::chartTypes).isEqualTo(List.of("bar", "pie"));
    }

    @Test
    void ratioCombosShareTheCompareTierBetweenFocusCombosAndTheRest() {
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.add(ratio(RETURN_RATE, 0));

        List<Combo> ranked = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);

        // 同一層裡照平均重要性：四個對照組合在前，兩個比率組合接著；重要性更高的一般組合（例如 date 配 sales）排在後面
        assertThat(ranked.subList(0, 6)).allMatch(c -> c.compare() || names(c).contains("退貨率"));
        assertThat(names(ranked.get(4))).containsExactly("date", "退貨率");
        assertThat(names(ranked.get(5))).containsExactly("region", "退貨率");
        // 比率不跟其他數值組成對照組
        assertThat(ranked).filteredOn(c -> names(c).contains("退貨率")).allMatch(c -> c.fields().size() == 2);

        cands.set(2, focus(cands.get(2))); // region 是焦點欄位
        List<Combo> focused = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);
        assertThat(focused.subList(0, 3)).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("region", "sales", "refund"), List.of("region", "sales", "qty"), List.of("region", "退貨率"));
        assertThat(focused.get(3).hasFocus()).isTrue();
    }

    @Test
    void onACompareSourceARatioOnlyGoesWithFieldsBothOfItsSourcesHave() {
        // region 只有銷售有；退貨率的分子來自退貨、分母來自銷售，兩邊都有的只有 date
        Map<String, Set<String>> regionOnlyInSales = Map.of(
            "sales", Set.of("date", "region", "sales"),
            "refund", Set.of("date", "refund", "qty"),
            "qty", Set.of("date", "refund", "qty"));
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.add(ratio(RETURN_RATE, 1));

        assertThat(ConfigAssembler.ranked(cands, regionOnlyInSales)).filteredOn(c -> names(c).contains("退貨率"))
            .extracting(ConfigAssemblerTest::names).containsExactly(List.of("date", "退貨率"));
    }

    @Test
    void ratioCombosBecomeSingleValueChartsThatPassValidation() {
        FieldEval date = compareCandidates().get(0);
        List<FieldEval> cands = List.of(date, eval("region", STRING, 4, 2, "dimension"), ratio(RETURN_RATE, 2));
        List<Combo> combos = ConfigAssembler.combos(cands, 2);
        Map<String, Answer> round2 = Map.of("combo_0", choice("line", 0.9), "combo_1", choice("bar", 0.9));

        List<ChartSpec> layout = ConfigAssembler.assemble(combos, round2, 2, List.of("date", "region"));

        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("date", null, null, "退貨率", null, null, "sum", "month"));
        assertThat(layout.get(0).title()).isEqualTo("退貨率 依 date");
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("region", null, null, "退貨率", null, null, "sum", null));
        assertThat(layout.get(1).title()).isEqualTo("退貨率 依 region");
        DashboardConfig c = new DashboardConfig(null, "比率", null,
            layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList());
        assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='ConfigAssemblerTest'`
Expected：編譯失敗，`FieldEval` 沒有五個參數的建構子、沒有三個參數的 `evaluate`。

- [ ] **Step 3: `FieldEval.ratio`、`Combo.hasRatio`、`evaluate`**

`ConfigAssembler.java` 的 import 加上：

```java
import java.util.HashMap;
import jev.dashboard.datasource.Ratio;
```

`FieldEval` 連同上面的註解：

```java
    /** focus：spec 6.1 的焦點欄位（rel_i 答「直接相關」），或使用者勾選想看的欄位。 */
    public record FieldEval(FieldSchema field, double importance, String role, boolean focus) {
        public FieldEval(FieldSchema field, double importance, String role) {
            this(field, importance, role, false);
        }
```

換成：

```java
    /**
     * focus：spec 6.1 的焦點欄位（rel_i 答「直接相關」），或使用者勾選想看的欄位。
     * ratio：B3 的比率，角色固定是 measure；一般欄位是 null。
     */
    public record FieldEval(FieldSchema field, double importance, String role, boolean focus, Ratio ratio) {
        public FieldEval(FieldSchema field, double importance, String role) {
            this(field, importance, role, false, null);
        }

        public FieldEval(FieldSchema field, double importance, String role, boolean focus) {
            this(field, importance, role, focus, null);
        }
```

`Combo` 的 `compare()` 方法後面加：

```java
        /** B3 spec 5.3：有比率的組合跟對照組合排在同一層。 */
        boolean hasRatio() {
            return fields.stream().anyMatch(f -> f.ratio() != null);
        }
```

`evaluate(List<FieldSchema> schema, Map<String, Answer> answers)` 方法後面加：

```java
    /** B3 spec 5.3：比率接在欄位後面編號（有 n 個欄位時第一個是 imp_<n>），只讀重要性和相關性，角色固定是 measure。 */
    public static List<FieldEval> evaluate(List<FieldSchema> schema, List<Ratio> ratios, Map<String, Answer> answers) {
        List<FieldEval> out = new ArrayList<>(evaluate(schema, answers));
        for (int j = 0; j < ratios.size(); j++) {
            Ratio r = ratios.get(j);
            int i = schema.size() + j;
            Answer imp = answers.get("imp_" + i);
            FieldSchema f = new FieldSchema(r.name(), FieldType.NUMBER, 0, List.of(), r.sensitive(), null, null);
            out.add(new FieldEval(f, imp == null || imp.score() == null ? 0 : imp.score(), "measure",
                isFocus(answers.get("rel_" + i)), r));
        }
        return out;
    }
```

- [ ] **Step 4: 候選欄位**

`candidates` 連同上面的註解換成：

```java
    /**
     * spec 6.2：角色不是 ignore 的焦點欄位不看重要性，排在最前面；其他欄位照原本的規則補，總數最多 8 個。
     * B3 spec 5.3：比率是使用者特地加的，一定進候選，排在焦點欄位後面、其他欄位前面，比率之間照重要性排。
     */
    public static List<FieldEval> candidates(List<FieldEval> evals) {
        Comparator<FieldEval> byImportance = Comparator.comparingDouble(FieldEval::importance).reversed();
        Stream<FieldEval> focus = evals.stream()
            .filter(e -> e.focus() && !e.role().equals("ignore"))
            .sorted(byImportance);
        Stream<FieldEval> ratios = evals.stream()
            .filter(e -> !e.focus() && e.ratio() != null)
            .sorted(byImportance);
        Stream<FieldEval> others = evals.stream()
            .filter(e -> !e.focus() && e.ratio() == null && !e.role().equals("ignore") && Math.round(e.importance()) >= 1)
            .sorted(byImportance);
        return Stream.of(focus, ratios, others).flatMap(s -> s).limit(MAX_CANDIDATES).toList();
    }
```

- [ ] **Step 5: 組合和排序**

`ranked(List<FieldEval> c, Map<String, Set<String>> provides)` 的註解，最後一行 ` * 配上分類欄位出長條，排在焦點之後、其他組合之前；畫不出東西的組合（drawable）一律不要。` 後面加一行：

```java
     * 有比率的組合（B3 spec 5.3）跟對照組合同一層；對照來源上，比率只配分子、分母兩邊來源都有的欄位（visible）。
```

同一個方法裡：

```java
        List<FieldEval> measures = c.stream().filter(e -> e.role().equals("measure")).toList();
```

換成：

```java
        // B3 spec 5.3：比率不畫雷達圖，也不算進雷達圖的數值
        List<FieldEval> measures = c.stream().filter(e -> e.role().equals("measure") && e.ratio() == null).toList();
```

同一個方法裡：

```java
                Set<String> seenByA = provides.get(a.name());
                // 對照組：兩個數值都是對照來源的數字欄位，而且來自不同來源
```

換成：

```java
                Set<String> seenByA = provides.get(a.name());
                // 對照組：兩個數值都是對照來源的數字欄位，而且來自不同來源；比率的名稱不在 provides 裡，不會組成對照組
```

同一個方法最後：

```java
        all.removeIf(combo -> !drawable(combo, provides));
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparing(Combo::compare).reversed())
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
        return all;
```

換成：

```java
        Map<String, Set<String>> seen = visible(c, provides);
        all.removeIf(combo -> !drawable(combo, seen));
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparing((Combo combo) -> combo.compare() || combo.hasRatio()).reversed())
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
        return all;
```

`drawable` 方法前面加：

```java
    /**
     * B3 spec 5.3：對照來源上，分子、分母都有 provides 時，比率只看得到兩者的交集；
     * 在 provides 裡補上「比率名稱 → 交集」，drawable 就照同一套規則擋掉交集外的時間、分類欄位。
     */
    private static Map<String, Set<String>> visible(List<FieldEval> c, Map<String, Set<String>> provides) {
        Map<String, Set<String>> out = new HashMap<>(provides);
        for (FieldEval e : c) {
            if (e.ratio() == null) continue;
            Set<String> num = provides.get(e.ratio().numerator());
            Set<String> den = provides.get(e.ratio().denominator());
            if (num == null || den == null) continue;
            Set<String> both = new HashSet<>(num);
            both.retainAll(den);
            out.put(e.name(), both);
        }
        return out;
    }
```

`pairTypes` 方法的第一行（`return switch (roles(a, b)) {` 前面）加：

```java
        // B3 spec 5.3：比率配時間出折線、長條，配分類只出長條；比率不能相加，不出圓餅，也不跟數值畫散佈圖
        if (a.ratio() != null || b.ratio() != null) {
            return switch (roles(a, b)) {
                case "measure,time" -> List.of("line", "bar");
                case "dimension,measure" -> List.of("bar");
                default -> List.of();
            };
        }
```

`tripleTypes` 方法的第一行（`int minDimDistinct = …` 前面）加：

```java
        if (Stream.of(a, b, c).anyMatch(f -> f.ratio() != null)) return List.of(); // B3 spec 5.3：比率不出堆疊、熱力
```

`mapping` 和 `title` 不用改：比率組合只有一個數值，`mapping` 走 `value` 那條路，標題是「退貨率 依 date」。

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='ConfigAssemblerTest,AnalyzeServiceTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋21，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java"
git add $F && git commit -m "feat(backend): 比率一定進候選，只配時間或分類畫折線、長條" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 5: 分析：state、題目、明細表、`JevController` 帶比率

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`（`analyze`）
- Test: `backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`
- Test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes：Task 1 的 `Datasource.ratios()`；Task 4 的 `ConfigAssembler.evaluate(schema, ratios, answers)`、`FieldEval.ratio()`
- Produces：
  - `DashboardConfig AnalyzeService.analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount, List<String> intents, List<ChartSpec> current, Set<String> picked, MergeEngine.Comparison compare, List<Ratio> ratios)`；原本八個參數的版本保留（等於沒有比率）
  - `static Map<String, Object> AnalyzeService.state(List<FieldSchema> schema, List<Ratio> ratios, long rowCount, List<String> intents, List<ChartSpec> current)`；原本四個參數的版本保留
  - `static String AnalyzeService.formula(Ratio r)`：`「{分子} ÷ {分母}」`

- [ ] **Step 1: 寫會失敗的測試**

`AnalyzeServiceTest.java` 的 import 加上：

```java
import jev.dashboard.datasource.Ratio;
```

在 class 最後加：

```java
    @Test
    void ratiosAreAskedAfterTheFieldsWithTheirFormulaAndDrawnAsSingleValueCharts() throws Exception {
        List<FieldSchema> schema = List.of(
            new FieldSchema("order_date", FieldType.DATE, 12, List.of("2024-01-05"), false, "2024-01-05", "2024-06-20"),
            new FieldSchema("region", FieldType.STRING, 4, List.of("北區"), false, null, null),
            new FieldSchema("revenue", FieldType.NUMBER, 12, List.of("1200"), false, null, null),
            new FieldSchema("gross_profit", FieldType.NUMBER, 12, List.of("300"), false, null, null));
        List<Ratio> ratios = List.of(new Ratio("毛利率", "gross_profit", "revenue"));
        // 毛利率的重要性是 0，照樣一定進候選；gross_profit 不重要，不進候選
        String round1 = """
            {"model":"jev-1.13.0","answers":{
              "imp_0":{"type":"score","score":3.0,"confidence":0.9},"role_0":{"type":"choice","choice":"time","confidence":0.9},
              "imp_1":{"type":"score","score":2.0,"confidence":0.9},"role_1":{"type":"choice","choice":"dimension","confidence":0.9},
              "imp_2":{"type":"score","score":2.0,"confidence":0.9},"role_2":{"type":"choice","choice":"measure","confidence":0.9},
              "imp_3":{"type":"score","score":0.0,"confidence":0.9},"role_3":{"type":"choice","choice":"measure","confidence":0.9},
              "imp_4":{"type":"score","score":0.0,"confidence":0.9},
              "chart_count":{"type":"score","score":1.0,"confidence":0.9}}}""";
        // 比率組合排在一般組合前面：combo_0 是毛利率配 order_date，combo_1 是毛利率配 region
        String round2 = """
            {"model":"jev-1.13.0","answers":{
              "combo_0":{"type":"choice","choice":"line","confidence":0.9},
              "combo_1":{"type":"choice","choice":"bar","confidence":0.9}}}""";

        try (FakeJevServer server = FakeJevServer.start(body -> body.contains("\"combo_0\"") ? round2 : round1)) {
            DashboardConfig c = service(server).analyze("毛利 總覽", UUID.randomUUID(), schema, 12,
                List.of(), null, Set.of(), null, ratios);

            assertThat(c.layout()).extracting(ChartSpec::type, ChartSpec::title).containsExactly(
                tuple("line", "毛利率 依 order_date"), tuple("bar", "毛利率 依 region"), tuple("table", "明細"));
            assertThat(c.layout().get(0).mapping())
                .isEqualTo(new Mapping("order_date", null, null, "毛利率", null, null, "sum", "month"));
            // 明細表不放比率
            assertThat(c.layout().get(2).mapping().columns()).containsExactly("order_date", "region", "revenue");
            assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();

            JsonNode fields = JSON.readTree(server.bodies.get(0)).at("/state/fields");
            assertThat(fields).hasSize(5);
            assertThat(JSON.convertValue(fields.get(4), new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("name", "毛利率", "type", "ratio", "formula", "gross_profit ÷ revenue"));
            assertThat(server.bodies.get(0)).contains("\"imp_4\"").contains("比率「毛利率」對分析這份資料有多重要？")
                .doesNotContain("\"role_4\"").doesNotContain("rel_");
            assertThat(server.bodies.get(1)).contains("「毛利率」是比率＝gross_profit ÷ revenue，每組先各自加總再相除。");

            // 有需求時也問比率的相關性
            service(server).analyze("毛利 總覽", UUID.randomUUID(), schema, 12, List.of("毛利"), null, Set.of(), null, ratios);
            assertThat(server.bodies.get(2)).contains("\"rel_4\"").contains("比率「毛利率」跟這個需求有多相關？");
        }
    }
```

`JevControllerTest.java` 在 class 最後加：

```java
    @Test
    void analyzingASourceWithARatioAsksAboutItAfterTheFields() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + ds + "/ratios").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\"單價\",\"numerator\":\"amount\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isOk());

        // sales.csv 有 6 個欄位，比率是第 7 個（imp_6），不問角色
        assertThat(JEV.bodies.get(0)).contains("{\"name\":\"單價\",\"type\":\"ratio\",\"formula\":\"amount ÷ qty\"}")
            .contains("\"imp_6\"").doesNotContain("\"role_6\"");
    }
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest'`
Expected：編譯失敗，`AnalyzeService` 沒有九個參數的 `analyze`。

- [ ] **Step 3: `analyze` 多收比率、第一輪問比率**

`AnalyzeService.java` 的 import 加上：

```java
import java.util.Objects;
import jev.dashboard.datasource.Ratio;
```

八個參數的 `analyze`（`@param compare` 那個）的開頭：

```java
    /** @param compare 對照來源的對照組和來源欄（B2 spec 5.5）；其他來源是 null */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked,
                                   MergeEngine.Comparison compare) {
        String intent = intents.isEmpty() ? null : intents.get(intents.size() - 1);
        Map<String, Object> state = state(schema, rowCount, intents, current);
```

換成：

```java
    /** @param compare 對照來源的對照組和來源欄（B2 spec 5.5）；其他來源是 null */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked,
                                   MergeEngine.Comparison compare) {
        return analyze(name, datasourceId, schema, rowCount, intents, current, picked, compare, List.of());
    }

    /** @param ratios 這個來源的比率（B3 spec 5.3）：接在欄位後面問，一定進候選，不進明細表 */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked,
                                   MergeEngine.Comparison compare, List<Ratio> ratios) {
        String intent = intents.isEmpty() ? null : intents.get(intents.size() - 1);
        Map<String, Object> state = state(schema, ratios, rowCount, intents, current);
```

同一個方法裡，欄位題目的迴圈結束後、`if (current == null) {` 前面加：

```java
        // B3 spec 5.3：比率接在欄位後面編號，只問重要性和相關性，角色固定是 measure
        for (int j = 0; j < ratios.size(); j++) {
            int i = schema.size() + j;
            String ratio = ratios.get(j).name();
            round1.put("imp_" + i, Question.score("比率「" + ratio + "」對分析這份資料有多重要？", IMPORTANCE));
            if (intent != null) {
                round1.put("rel_" + i, Question.score(
                    "使用者想看：「" + intent + "」。比率「" + ratio + "」跟這個需求有多相關？", RELEVANCE));
            }
        }
```

同一個方法裡：

```java
        List<FieldEval> evals = ConfigAssembler.evaluate(schema, a1).stream()
            .map(e -> picked.contains(e.name()) ? new FieldEval(e.field(), e.importance(), e.role(), true) : e)
```

換成（勾選的比率也要留著 `ratio`）：

```java
        List<FieldEval> evals = ConfigAssembler.evaluate(schema, ratios, a1).stream()
            .map(e -> picked.contains(e.name()) ? new FieldEval(e.field(), e.importance(), e.role(), true, e.ratio()) : e)
```

同一個方法裡：

```java
            List<String> tableColumns = (candidates.isEmpty()
                // B2 spec 5.5：對照來源的來源欄不進明細表
                ? schema.stream().map(FieldSchema::name).filter(n -> compare == null || !n.equals(compare.sourceColumn()))
                : candidates.stream().map(FieldEval::name)).toList();
```

換成：

```java
            // B3 spec 5.3：明細表不放比率；候選裡只剩比率時，跟沒有候選一樣放全部欄位
            List<String> columns = candidates.stream().filter(e -> e.ratio() == null).map(FieldEval::name).toList();
            List<String> tableColumns = !columns.isEmpty() ? columns
                // B2 spec 5.5：對照來源的來源欄不進明細表
                : schema.stream().map(FieldSchema::name).filter(n -> compare == null || !n.equals(compare.sourceColumn())).toList();
```

- [ ] **Step 4: state 接上比率、題目多一句**

`state` 方法的開頭：

```java
    static Map<String, Object> state(List<FieldSchema> schema, long rowCount, List<String> intents,
                                     List<ChartSpec> current) {
        List<Map<String, Object>> fields = new ArrayList<>();
```

換成：

```java
    static Map<String, Object> state(List<FieldSchema> schema, long rowCount, List<String> intents,
                                     List<ChartSpec> current) {
        return state(schema, List.of(), rowCount, intents, current);
    }

    static Map<String, Object> state(List<FieldSchema> schema, List<Ratio> ratios, long rowCount, List<String> intents,
                                     List<ChartSpec> current) {
        List<Map<String, Object>> fields = new ArrayList<>();
```

同一個方法裡，欄位迴圈（`for (FieldSchema f : schema) { … fields.add(m); }`）結束後加：

```java
        // B3 spec 5.3：比率接在欄位後面，只有名稱和公式，沒有樣本值和相異值數量
        for (Ratio r : ratios) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("type", "ratio");
            m.put("formula", formula(r));
            fields.add(m);
        }
```

`describe` 整個方法換成：

```java
    static String describe(Combo c, String intent) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        Ratio ratio = c.fields().stream().map(FieldEval::ratio).filter(Objects::nonNull).findFirst().orElse(null);
        String text = "用這組欄位畫一張圖：" + fields + "。"
            + (c.compare() ? "兩個數值來自不同來源，要放在同一張圖上對照。" : "")
            // B3 spec 5.3：比率組合只有一個比率
            + (ratio == null ? "" : "「" + ratio.name() + "」是比率＝" + formula(ratio) + "，每組先各自加總再相除。")
            + "選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
        return intent == null ? text : text + "使用者想看：「" + intent + "」。";
    }

    /** 例如「退貨·amount ÷ 銷售·amount」；用原始欄位名稱，跟 state.fields 對得上。 */
    static String formula(Ratio r) {
        return r.numerator() + " ÷ " + r.denominator();
    }
```

- [ ] **Step 5: `JevController.analyze` 帶比率**

`JevController.analyze` 裡：

```java
            MergeEngine.comparison(loaded.datasource()));
```

換成：

```java
            MergeEngine.comparison(loaded.datasource()), loaded.datasource().ratios());
```

- [ ] **Step 6: 跑測試確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest,ConfigAssemblerTest'`
Expected：全部通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋23，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
F="backend/src/main/java/jev/dashboard/jev/AnalyzeService.java backend/src/main/java/jev/dashboard/jev/JevController.java backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java"
git add $F && git commit -m "feat(backend): 分析時把比率和公式送給 JEV，明細表不放比率" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 6: 前端圖表：比率顯示成百分比

**Files:**
- Create: `frontend/src/modules/dashboard/charts/percent.js`
- Modify: `frontend/src/modules/dashboard/charts/bar.js`、`charts/line.js`（整個檔案換掉）
- Modify: `frontend/src/modules/dashboard/ChartCard.vue`（`compactValueAxis`）
- Test: `frontend/src/modules/dashboard/charts/charts.test.js`
- Test: `frontend/src/modules/dashboard/ChartCard.test.js`

**Interfaces:**
- Consumes：Task 2 的 `/data` 回應裡的 `percent: true`（只有兩欄的比率資料會有）
- Produces：`formatPercent(v)`（`charts/percent.js`）：數字照 Global Constraints 的格式，其他值回「-」

- [ ] **Step 1: 寫會失敗的測試**

`charts.test.js` 最後加：

```js
describe('toOption with a ratio (B3 spec 6.3)', () => {
  const ratio = (type) => ({
    chartId: 'c9', type, dimensions: ['date', '退貨率'], rows: [['2026-06', null], ['2026-07', 0.052]], percent: true,
  })

  it('line and bar show the Y axis and the tooltip in percent with at most one decimal, and keep null groups empty', () => {
    for (const type of ['line', 'bar']) {
      const o = toOption[type](ratio(type))
      expect(o.yAxis.axisLabel.formatter(0.052)).toBe('5.2%')
      expect(o.yAxis.axisLabel.formatter(0.05)).toBe('5%')
      expect(o.tooltip.valueFormatter(0.052)).toBe('5.2%')
      expect(o.tooltip.valueFormatter(null)).toBe('-')
      expect(o.dataset.source).toEqual([['2026-06', null], ['2026-07', 0.052]])
    }
  })

  it('without percent the Y axis and the tooltip stay as they were', () => {
    for (const [type, data] of [['line', byMonth], ['bar', byRegion]]) {
      const o = toOption[type](data)
      expect(o.yAxis).toEqual({ type: 'value' })
      expect(o.tooltip).toEqual({ trigger: 'axis' })
    }
  })
})
```

`ChartCard.test.js` 在 `describe('ChartCard', …)` 裡、最後一個 `it(...)` 後面加：

```js
  it('a percent Y axis keeps its percent labels instead of the compact number form', () => {
    const data = { chartId: 'c1', type: 'line', dimensions: ['date', '退貨率'], rows: [['2026-07', 0.052]], percent: true }
    const option = chartOf(mountCard({ spec: { ...barSpec, type: 'line' }, data, highlight: null })).props('option')
    expect(option.yAxis.axisLabel.formatter(0.052)).toBe('5.2%')
    expect(option.tooltip.valueFormatter(0.052)).toBe('5.2%')
  })
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/charts/charts.test.js src/modules/dashboard/ChartCard.test.js`
Expected：新加的百分比測試失敗（`o.yAxis.axisLabel` 是 undefined），「without percent」那個測試通過。

- [ ] **Step 3: `percent.js`、長條、折線**

新增 `frontend/src/modules/dashboard/charts/percent.js`：

```js
// B3 spec 6.3：比率一律顯示成百分比，最多一位小數：0.052 是「5.2%」，0.05 是「5%」
const percent = new Intl.NumberFormat('zh-TW', { style: 'percent', maximumFractionDigits: 1 })

// 缺分子或分母的組是 null，tooltip 顯示「-」
export const formatPercent = (v) => (typeof v === 'number' ? percent.format(v) : '-')
```

`bar.js` 整個換成：

```js
import { twoMeasures } from './measures.js'
import { formatPercent } from './percent.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'bar')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    // B3 spec 6.3：數值是比率時，Y 軸和 tooltip 用百分比；null 照原樣留著，那一根長條留空
    tooltip: { trigger: 'axis', ...(data.percent && { valueFormatter: formatPercent }) },
    xAxis: { type: 'category' },
    yAxis: { type: 'value', ...(data.percent && { axisLabel: { formatter: formatPercent } }) },
    series: [{ type: 'bar', encode: { x: 0, y: 1 } }],
  }
}
```

`line.js` 整個換成：

```js
import { twoMeasures } from './measures.js'
import { formatPercent } from './percent.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'line')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    // B3 spec 6.3：數值是比率時，Y 軸和 tooltip 用百分比；null 照原樣留著，折線在那裡斷開
    tooltip: { trigger: 'axis', ...(data.percent && { valueFormatter: formatPercent }) },
    xAxis: { type: 'category' },
    yAxis: { type: 'value', ...(data.percent && { axisLabel: { formatter: formatPercent } }) },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}
```

沒有 `percent` 時，`...(undefined && {…})` 什麼都不加，既有快照不變。

- [ ] **Step 4: `ChartCard` 的數字縮寫不蓋掉百分比**

`ChartCard.vue` 裡：

```js
const compactValueAxis = (axis) =>
  axis?.type === 'value' ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } } : axis
```

換成：

```js
// B3 spec 6.3：軸已經有自己的格式（比率的百分比）就不套數字縮寫
const compactValueAxis = (axis) =>
  axis?.type === 'value' && !axis.axisLabel?.formatter
    ? { ...axis, axisLabel: { ...axis.axisLabel, formatter: (v) => compact.format(v) } }
    : axis
```

- [ ] **Step 5: 跑測試確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/charts/charts.test.js src/modules/dashboard/ChartCard.test.js`
Expected：全部通過，快照沒有更新（輸出裡沒有 `Snapshots … updated` 或 `written`）。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：前端基準數量＋3，全部通過。

- [ ] **Step 6: Commit**

```bash
F="frontend/src/modules/dashboard/charts/percent.js frontend/src/modules/dashboard/charts/bar.js frontend/src/modules/dashboard/charts/line.js frontend/src/modules/dashboard/ChartCard.vue frontend/src/modules/dashboard/charts/charts.test.js frontend/src/modules/dashboard/ChartCard.test.js"
git add $F && git commit -m "feat(frontend): 比率的長條、折線圖用百分比顯示" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 7: 資料頁的比率區和「新增比率」對話框

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`
- Modify: `frontend/src/modules/dashboard/DatasourcePanel.vue`
- Test: `frontend/src/modules/dashboard/DatasourcePanel.test.js`

**Interfaces:**
- Consumes：Task 1 的 `PATCH /api/datasources/{id}/ratios`、`GET /api/datasources` 每個來源的 `ratios`（`[{ name, numerator, denominator, sensitive }]`）
- Produces：`api.setRatios(id, ratios)`

- [ ] **Step 1: 寫會失敗的測試**

`DatasourcePanel.test.js` 最上面的 `vi.mock('./api.js', …)` 裡，`setLabels: vi.fn(),` 下一行加：

```js
    setRatios: vi.fn(),
```

`function mountPanel(` 前面加：

```js
// B3：sales、refund 有顯示名稱，refund 是敏感的，已經有一個比率
const withRatio = {
  id: 'dr',
  name: '銷售明細',
  kind: 'upload',
  schema: [
    { name: 'region', type: 'string', sensitive: false },
    { name: 'sales', type: 'number', sensitive: false, label: '銷售金額' },
    { name: 'refund', type: 'number', sensitive: true, label: '退貨金額' },
    { name: 'qty', type: 'number', sensitive: false },
  ],
  ratios: [{ name: '退貨率', numerator: 'refund', denominator: 'sales', sensitive: true }],
}
```

`async function click(text) {…}` 後面加：

```js
// 比率區每一列的名稱、公式、敏感；「新增比率」是第二個對話框（第一個是「新增來源」）
const ratioRows = (wrapper) => wrapper.findAll('.ds-ratios tbody tr').map((r) => r.findAll('td').slice(0, 3).map((td) => td.text()))
const ratioDialog = (wrapper) => wrapper.findAllComponents({ name: 'VDialog' })[1]
const ratioSelect = (wrapper, label) => wrapper.findAllComponents({ name: 'VSelect' }).find((s) => s.props('label') === label)

async function openRatio(wrapper) {
  await wrapper.findAll('button').find((b) => b.text() === '新增比率').trigger('click')
  await flushPromises()
}

async function fillRatio(wrapper, name, numerator, denominator) {
  await field(wrapper, '名稱').find('input').setValue(name)
  await ratioSelect(wrapper, '分子').vm.$emit('update:modelValue', numerator)
  await ratioSelect(wrapper, '分母').vm.$emit('update:modelValue', denominator)
  await flushPromises()
}
```

在 `describe('DatasourcePanel', …)` 裡、最後一個 `it(...)` 後面加：

```js
  it('比率區 lists each ratio with its formula in display names and a 敏感 mark; with none it explains what a ratio is', async () => {
    api.listDatasources.mockResolvedValue([withRatio, ds1])
    const wrapper = mountPanel('dr')
    await flushPromises()

    expect(wrapper.find('.ds-ratios .cc-label').text()).toBe('比率 · 1')
    expect(ratioRows(wrapper)).toEqual([['退貨率', '退貨金額 ÷ 銷售金額', '敏感']])
    expect(wrapper.find('.ds-ratios .tc-tag').attributes('title')).toBe('分子或分母是敏感欄位')
    expect(wrapper.find('button[aria-label="刪除比率 退貨率"]').exists()).toBe(true)

    await wrapper.setProps({ selectedId: 'ds1' })
    expect(wrapper.find('.ds-ratios .cc-label').text()).toBe('比率 · 0')
    expect(wrapper.findAll('.ds-ratios tbody tr')).toHaveLength(0)
    expect(wrapper.find('.ds-ratios').text()).toContain('比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。')
  })

  it('新增比率 offers only number fields by display name, enables 新增 once all three are filled with two different fields, and sends the whole list', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockResolvedValue({})
    const wrapper = mountPanel('dr')
    await flushPromises()
    await openRatio(wrapper)

    expect(ratioDialog(wrapper).props('modelValue')).toBe(true)
    expect(ratioSelect(wrapper, '分子').props('items')).toEqual([
      { title: '銷售金額', value: 'sales' }, { title: '退貨金額', value: 'refund' }, { title: 'qty', value: 'qty' },
    ])
    expect(button('新增').disabled).toBe(true)
    await fillRatio(wrapper, ' 件均 ', 'sales', 'sales')
    expect(button('新增').disabled).toBe(true) // 分子和分母一樣
    await ratioSelect(wrapper, '分母').vm.$emit('update:modelValue', 'qty')
    await flushPromises()
    expect(button('新增').disabled).toBe(false)

    await click('新增')
    // 整份清單送出，只有名稱、分子、分母；sensitive 是後端算的
    expect(api.setRatios).toHaveBeenCalledWith('dr', [
      { name: '退貨率', numerator: 'refund', denominator: 'sales' },
      { name: '件均', numerator: 'sales', denominator: 'qty' },
    ])
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(ratioDialog(wrapper).props('modelValue')).toBe(false)
  })

  it('a rejected 新增 keeps the dialog open with the backend message', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockRejectedValue(new Error('比率名稱「退貨率」跟欄位或其他比率重複'))
    const wrapper = mountPanel('dr')
    await flushPromises()
    await openRatio(wrapper)
    await fillRatio(wrapper, '退貨率', 'sales', 'qty')

    await click('新增')

    expect(ratioDialog(wrapper).props('modelValue')).toBe(true)
    expect(ratioDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('比率名稱「退貨率」跟欄位或其他比率重複')
    expect(api.listDatasources).toHaveBeenCalledTimes(1)
  })

  it('刪除比率 sends the list without it and refreshes; a rejected delete shows the message in the 比率 section', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('找不到資料源'))
    const wrapper = mountPanel('dr')
    await flushPromises()

    await wrapper.find('button[aria-label="刪除比率 退貨率"]').trigger('click')
    await flushPromises()
    expect(api.setRatios).toHaveBeenCalledWith('dr', [])
    expect(api.listDatasources).toHaveBeenCalledTimes(2)

    await wrapper.find('button[aria-label="刪除比率 退貨率"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.ds-ratios .v-alert').text()).toBe('找不到資料源')
  })
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/DatasourcePanel.test.js`
Expected：四個新測試失敗（找不到 `.ds-ratios`、「新增比率」按鈕），既有測試全部通過。

- [ ] **Step 3: `api.setRatios`**

`api.js` 的 `setLabels: …` 那一行後面加：

```js
  // B3 spec 第 7 節：比率清單整份取代，新增、刪除都用這支
  setRatios: (id, ratios) => request('PATCH', `/api/datasources/${id}/ratios`, { ratios }),
```

- [ ] **Step 4: 比率區和對話框的程式**

`DatasourcePanel.vue` 的 `saveLabel` 函式後面（`</script>` 前面）加：

```js
// ---------- 比率（B3 spec 6.1） ----------
const ratioOpen = ref(false)
const ratioName = ref('')
const ratioNumerator = ref(null)
const ratioDenominator = ref(null)
const ratioError = ref('') // 刪除失敗：顯示在比率區
const ratioDialogError = ref('') // 新增失敗：顯示在對話框裡

const ratios = computed(() => selected.value?.ratios ?? [])
// 分子、分母的選單只列數字欄位，選項用顯示名稱
const numberFields = computed(() =>
  (selected.value?.schema ?? []).filter((f) => f.type === 'number').map((f) => ({ title: f.label || f.name, value: f.name })),
)
const canAddRatio = computed(
  () => !!ratioName.value.trim() && !!ratioNumerator.value && !!ratioDenominator.value && ratioNumerator.value !== ratioDenominator.value,
)
const shownName = (ds, field) => ds.schema.find((f) => f.name === field)?.label || field

function openRatio() {
  ratioName.value = ''
  ratioNumerator.value = ratioDenominator.value = null
  ratioDialogError.value = ''
  ratioOpen.value = true
}

// 整份清單送給後端，只送名稱、分子、分母；sensitive 是後端讀取時算的
async function saveRatios(ds, next) {
  busy.value = true
  try {
    await api.setRatios(ds.id, next.map((r) => ({ name: r.name, numerator: r.numerator, denominator: r.denominator })))
    await refresh()
  } finally {
    busy.value = false
  }
}

async function addRatio(ds) {
  ratioDialogError.value = ''
  try {
    await saveRatios(ds, [...ratios.value, { name: ratioName.value.trim(), numerator: ratioNumerator.value, denominator: ratioDenominator.value }])
    ratioOpen.value = false
    ratioError.value = ''
  } catch (e) {
    ratioDialogError.value = e.message
  }
}

// spec 6.1：按下去直接刪，不另外確認
async function removeRatio(ds, ratio) {
  try {
    await saveRatios(ds, ratios.value.filter((r) => r.name !== ratio.name))
    ratioError.value = ''
  } catch (e) {
    ratioError.value = e.message
  }
}
```

- [ ] **Step 5: 比率區和對話框的畫面**

`DatasourcePanel.vue` 的欄位表說明：

```vue
        <p class="ds-foot">
          顯示名稱會用在圖表標題、座標軸和明細表頭，空白就顯示原始欄位名稱。
          標成敏感的欄位不會把樣本和相異值送給 JEV，用到它的卡片也不做異常檢查。
        </p>
```

後面（同一個 `<section v-if="selected" …>` 裡）加：

```vue
        <!-- B3 spec 6.1：比率＝分子 ÷ 分母，存在來源上 -->
        <section class="ds-ratios" aria-label="比率">
          <div class="ds-ratios__head">
            <span class="cc-label">比率 · {{ ratios.length }}</span>
            <v-spacer />
            <v-btn size="small" variant="text" prepend-icon="mdi-plus" :disabled="busy" @click="openRatio">新增比率</v-btn>
          </div>
          <v-alert v-if="ratioError" type="error" density="compact" class="mx-5 mb-2">{{ ratioError }}</v-alert>
          <v-table v-if="ratios.length">
            <thead>
              <tr>
                <th>名稱</th>
                <th>公式</th>
                <th class="text-center">敏感</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in ratios" :key="r.name">
                <td>{{ r.name }}</td>
                <td>{{ shownName(selected, r.numerator) }} ÷ {{ shownName(selected, r.denominator) }}</td>
                <td class="text-center">
                  <span v-if="r.sensitive" class="tc-tag" title="分子或分母是敏感欄位">敏感</span>
                </td>
                <td class="text-right">
                  <v-btn
                    icon="mdi-delete-outline"
                    size="small"
                    variant="text"
                    :aria-label="`刪除比率 ${r.name}`"
                    :disabled="busy"
                    @click="removeRatio(selected, r)"
                  />
                </td>
              </tr>
            </tbody>
          </v-table>
          <p v-else class="ds-ratios__empty">比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。</p>
        </section>
```

「新增來源」的 `<v-dialog v-model="addOpen" …> … </v-dialog>` 後面（`</section>` 和 `</template>` 前面）加。一定要放在「新增來源」對話框的後面，理由見「實作上的決定」第 9 點：

```vue
    <v-dialog v-model="ratioOpen" max-width="480" transition="tc-sheet">
      <v-card>
        <v-card-title class="pt-5 px-6">新增比率</v-card-title>
        <v-card-text class="px-6">
          <v-text-field v-model="ratioName" label="名稱" placeholder="例如退貨率" />
          <v-select v-model="ratioNumerator" label="分子" :items="numberFields" />
          <v-select v-model="ratioDenominator" label="分母" :items="numberFields" />
          <v-alert v-if="ratioDialogError" type="error" density="compact">{{ ratioDialogError }}</v-alert>
        </v-card-text>
        <v-card-actions class="px-6 pb-5">
          <v-spacer />
          <v-btn variant="flat" @click="ratioOpen = false">取消</v-btn>
          <v-btn variant="flat" color="primary" :disabled="!canAddRatio" :loading="busy" @click="addRatio(selected)">新增</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
```

`<style scoped>` 最後（`</style>` 前面）加：

```css
/* B3 spec 6.1：欄位表下面的比率區 */
.ds-ratios { border-top: 1px solid var(--tc-border-subtle); }
.ds-ratios__head { display: flex; align-items: center; padding: 12px 20px 4px; }
.ds-ratios__empty { margin: 0; padding: 4px 20px 18px; font-size: 12.5px; color: var(--tc-muted); }
```

- [ ] **Step 6: 跑測試確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/DatasourcePanel.test.js src/modules/dashboard/MergeForm.test.js`
Expected：全部通過。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：前端基準數量＋7，全部通過。

- [ ] **Step 7: Commit**

```bash
F="frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/DatasourcePanel.vue frontend/src/modules/dashboard/DatasourcePanel.test.js"
git add $F && git commit -m "feat(frontend): 資料頁可以新增、刪除比率" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 8: 製圖頁的欄位標籤接上比率

**Files:**
- Modify: `frontend/src/modules/dashboard/ChartBuilder.vue`（「02 欄位」的 `v-chip-group`）
- Test: `frontend/src/modules/dashboard/ChartBuilder.test.js`

**Interfaces:**
- Consumes：Task 1 的 `GET /api/datasources` 每個來源的 `ratios`；後端的 `fields` 會把勾選的比率名稱當焦點欄位（Task 5，`picked.contains(e.name())`）
- Produces：無

- [ ] **Step 1: 寫會失敗的測試**

`ChartBuilder.test.js` 在 `describe('ChartBuilder', …)` 裡、最後一個 `it(...)` 後面加：

```js
  it('比率 follow the fields as % chips with the formula on hover, and can be picked as focus fields', async () => {
    const [sales, ...rest] = sources()
    api.listDatasources.mockResolvedValue([
      { ...sales, ratios: [{ name: '退貨率', numerator: '退貨·amount', denominator: '銷售·amount', sensitive: false }] },
      ...rest,
    ])
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [table('c1', 0)] })
    await mountBuilder()

    expect(chips().map((c) => c.textContent.trim())).toEqual(['地區', 'amount', '退貨率'])
    expect(chips()[2].getAttribute('title')).toBe('退貨率（比率：退貨·amount ÷ 銷售·amount）')
    expect(chips()[2].querySelector('.mdi-percent')).not.toBeNull()

    chips()[2].click()
    await flushPromises()
    expect(chips()[2].getAttribute('aria-checked')).toBe('true')
    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', undefined, undefined, ['退貨率'])
  })
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/ChartBuilder.test.js`
Expected：新測試失敗，標籤只有 `['地區', 'amount']`。

- [ ] **Step 3: 比率標籤**

`ChartBuilder.vue` 的「02 欄位」裡，欄位的 `<v-chip v-for="f in source.schema" …> … </v-chip>` 後面、`</v-chip-group>` 前面加：

```vue
            <!-- B3 spec 6.2：比率接在欄位後面，勾了一樣當焦點欄位 -->
            <v-chip
              v-for="r in source.ratios ?? []"
              :key="`ratio:${r.name}`"
              :value="r.name"
              :title="`${r.name}（比率：${r.numerator} ÷ ${r.denominator}）`"
              role="checkbox"
              :aria-checked="String(picked.includes(r.name))"
              variant="outlined"
              filter
            >
              <v-icon icon="mdi-percent" size="13" start />{{ r.name }}
            </v-chip>
```

- [ ] **Step 4: 跑測試確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/ChartBuilder.test.js`
Expected：全部通過。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2 && npm run build 2>&1 | tail -3`
Expected：前端基準數量＋8，全部通過，build 成功。

- [ ] **Step 5: Commit**

```bash
F="frontend/src/modules/dashboard/ChartBuilder.vue frontend/src/modules/dashboard/ChartBuilder.test.js"
git add $F && git commit -m "feat(frontend): 製圖頁可以勾比率當焦點欄位" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 9: 文件：README、backlog

**Files:**
- Modify: `README.md`（「前端設定」的「資料」那一條、新增「比率」一節）
- Modify: `docs/backlog.md`

- [ ] **Step 1: README**

`README.md`「前端設定」裡「資料」那一條的：

```markdown
右邊的欄位表可以改顯示名稱、設定敏感欄位。
```

換成：

```markdown
右邊的欄位表可以改顯示名稱、設定敏感欄位，下面的「比率」區可以加比率。
```

`## 一份儀表板放多個來源` 那一行的前面加一節：

```markdown
## 比率

退貨率、達成率、毛利率這種指標不能逐列算好再加總，要每一組先把分子、分母各自加總再相除，例如 9 月的退貨率＝9 月退貨金額合計 ÷ 9 月銷售金額合計。在「資料」頁欄位表下面的「比率」區按「新增比率」，取個名稱，再選分子和分母（都要是數字欄位）就好，任何來源都能加。後端是 `PATCH /api/datasources/{id}/ratios`，每次送整份清單。

分析時，JEV 把比率當成一個數值，配時間欄位畫折線圖或長條圖，配分類欄位畫長條圖，Y 軸和 tooltip 都顯示成百分比（最多一位小數）。某一組沒有分子或分母的資料，或分母加總是 0，那一組會留空，不是 0%。例如對照來源上退貨只有 7～9 月，1～6 月的退貨率就是空白。在對照來源上，比率只會配分子、分母兩邊來源都有的欄位。比率圖的異常檢查比的是百分點。

分子或分母是敏感欄位（包括從輸入來源繼承來的）時，比率也算敏感，用到它的卡片不做異常檢查。比率不能編輯，要改就刪掉重加；刪掉之後，用到它的卡片會顯示欄位遺失，同名重加就恢復。合併來源不會帶著輸入來源的比率，要用就在合併來源上自己加。比率存在 `datasource.ratios_json`（Flyway `V4__ratio_metric.sql`），舊的來源是空清單，不用搬資料。完整規則見 [`docs/superpowers/specs/2026-09-28-ratio-metric-design.md`](docs/superpowers/specs/2026-09-28-ratio-metric-design.md)。
```

- [ ] **Step 2: backlog**

`docs/backlog.md` 的「下一步」：

```markdown
- P1 · B3 比率：計算指標＝分子÷分母，例如退貨率、達成率。每一組先各自加總再相除，任何來源都能加。B2 已完成，可以開始寫 spec，從 B2 spec 第 11 節開始。（同上）
```

換成：

```markdown
- ~~P1 · B3 比率：計算指標＝分子÷分母，例如退貨率、達成率。每一組先各自加總再相除，任何來源都能加。~~ 已完成：資料頁可以加比率，折線圖、長條圖顯示成百分比，見 spec `docs/superpowers/specs/2026-09-28-ratio-metric-design.md`。之後可以接的項目在 spec 第 11 節。
```

- [ ] **Step 3: Commit**

```bash
F="README.md docs/backlog.md"
git add $F && git commit -m "docs: README 補上比率，backlog 劃掉 B3" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

---

### Task 10: 手動驗證（自己起前後端，用 Chrome DevTools MCP 操作）

**Files:**
- Create: `docs/superpowers/e2e/ratio-metric/margin.csv`
- 沿用：`docs/superpowers/e2e/compare-datasource/sales.csv`、`returns.csv`（B2 留下的，不要改）
- 發現問題時修正對應的檔案，commit 訊息要寫清楚改了什麼

**Interfaces:**
- Consumes：Task 1–9 的全部成果、Chrome DevTools MCP（`new_page`、`take_snapshot`、`upload_file`、`fill`、`click`、`hover`、`wait_for`、`evaluate_script`、`list_network_requests`、`get_network_request`、`take_screenshot`、`close_page`）

**誰來跑：** 整個 Task 10 交給同一個 subagent 從頭跑到尾，這個 subagent 要能用 Chrome DevTools MCP。Step 3、4 開的背景程序屬於它；controller 的 main session 不跑 `mvnw`，也不跑 `npm run dev`。controller 派工時要給它 session 的 scratchpad 路徑（Step 1 要把 CSV 複製過去）。

**controller 收報告時：** 自己打開每一張截圖看，不要只看 subagent 寫的「通過」。B2 的驗證就是這樣才抓到「圖畫出來了，但只有一邊有柱子」。比率圖要看到 Y 軸真的是百分比、1～6 月真的是空的。

**瀏覽器規則，一定要遵守：** 用 `new_page` 開一個自己的分頁，記下它的 pageId，之後只操作這個分頁。別的分頁是使用者自己在用的：不要 `select_page`、`navigate_page`、`close_page` 它們，也不要讀取、截圖或點它們。不確定目前選的是哪一頁時，先 `select_page` 自己的 pageId。結束時只關自己的分頁。MCP 說連不到 `127.0.0.1:9222`，就代表自動化用的 Chrome 沒開：用 Bash 的 `run_in_background: true` 執行 `"/c/Program Files/Google/Chrome/Application/chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\\selenum\\AutomationProfile" about:blank`，再試一次。快照太大時改用 `evaluate_script` 讀畫面。圖表畫在 canvas 上，快照看不到座標軸和 tooltip：圖表內容用 `get_network_request` 讀 `/data` 的回應，外觀用 `take_screenshot` 看。

**等待和程序：** 每個等待迴圈最多等 180 秒，逾時就印出伺服器 log 的最後幾十行，停下來回報，不要一直重試。agent 不能 `taskkill`：埠被別的程序佔著，或自己開的程序停不掉時，用 `netstat -ano | grep …` 找出 PID，回報給使用者處理。

- [ ] **Step 1: 寫測試用的 CSV，複製到 scratchpad**

`docs/superpowers/e2e/ratio-metric/margin.csv`：

```csv
order_date,region,revenue,gross_profit
2026-01-10,北區,120000,30000
2026-01-22,南區,90000,18000
2026-02-08,北區,125000,31000
2026-02-19,南區,88000,17600
2026-03-05,北區,130000,33800
2026-03-26,南區,95000,19000
2026-04-09,北區,128000,32000
2026-04-23,南區,97000,20400
2026-05-07,北區,135000,35100
2026-05-20,南區,99000,20800
2026-06-04,北區,140000,36400
2026-06-25,南區,102000,21400
2026-07-08,北區,138000,35900
2026-07-24,南區,101000,21200
2026-08-06,北區,142000,29800
2026-08-21,南區,104000,17700
2026-09-03,北區,150000,39000
2026-09-17,南區,108000,22700
```

毛利率每月在 22.8%～23.9% 之間，8 月掉到 19.3%（47,500 ÷ 246,000）。異常檢查比的是 7 月和 8 月（9 月還沒結束）。

B2 的兩份 CSV 算出來的退貨率：7 月 950 ÷ 27,200＝3.5%、8 月 1,130 ÷ 26,300＝4.3%、9 月 1,180 ÷ 28,300＝4.2%，1～6 月沒有退貨，是空白。

把 `docs/superpowers/e2e/compare-datasource/sales.csv`、`returns.csv` 和 `margin.csv` 複製到 controller 給的 scratchpad 資料夾，`upload_file` 用那裡的 Windows 路徑（Chrome MCP 只接受 workspace root 底下的檔案）。

- [ ] **Step 2: 全部測試再跑一次，確認 JEV key 和埠**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b3; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋23，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2 && npm run build 2>&1 | tail -3`
Expected：前端基準數量＋8，全部通過，build 成功。

Run: `grep -q '^TYPESAFE_API_KEY=.' backend/.env && echo "TYPESAFE_API_KEY 已設定" || echo "TYPESAFE_API_KEY 沒設定"`
Expected：`TYPESAFE_API_KEY 已設定`。這行只印結果，不印內容。沒設定就停下來向 controller 要 key，不要自己打開 `.env` 看。

Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
Expected：`8080 000` 和 `5180 000`。只要有一個不是 `000`，就代表有程式佔著這個埠（可能是使用者自己開的後端）。不要用它、不要關它：跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 回報給 controller，停在這一步。

- [ ] **Step 3: 啟動後端（背景，用獨立的 schema）**

用 Bash 的 `run_in_background: true` 執行：

```bash
ROOT=$(git rev-parse --show-toplevel); export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_e2e_b3 SPRING_FLYWAY_SCHEMAS=jev_dashboard_e2e_b3; cd "$ROOT/backend" && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1 > target/e2e-backend.log 2>&1
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

Expected：`backend-ready {"error":"請選擇合併方式"}`。log 裡看得到 Flyway 套用了 `4 - ratio metric`。

- [ ] **Step 4: 啟動前端（背景，每次用新的使用者）**

先決定這次的使用者 id，例如 `e2e-ratio-$(date +%H%M%S)`，記在報告裡。用 Bash 的 `run_in_background: true` 執行（把 `<USER>` 換成它）：

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

- [ ] **Step 5: 建對照來源**

`new_page` 開 `http://localhost:5180/`，切到「資料」。用「新增來源 → 上傳檔案」依序上傳 `sales.csv`、`returns.csv`，名稱分別填「銷售」「退貨」。

「新增來源 → 合併」，「方式」選「對照（數值依來源分開比較）」，「來源」依序選「銷售」「退貨」。把 `order_date` 那一列的退貨那格選成 `return_date`，用「✕」刪掉 `return_date` 那一列，再把 `order_date` 改名成 `date`。名稱用預設的「銷售／退貨 對照」，按「預覽」、再按「建立」。

- [ ] **Step 6: 加退貨率（spec 9 節 E2E 第 1 點）**

選「銷售／退貨 對照」，逐項確認並記錄：

1. 欄位表下面有「比率 · 0」，說明文字是「比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。」
2. 按「新增比率」：「分子」「分母」的選單只有 `銷售·amount`、`退貨·amount` 兩個選項。三個都還沒填時「新增」不能按；分子、分母選同一個時也不能按。
3. 名稱「退貨率」，分子 `退貨·amount`，分母 `銷售·amount`，按「新增」：對話框關掉，比率區變成「比率 · 1」，那一列是「退貨率｜退貨·amount ÷ 銷售·amount」，沒有「敏感」。
4. 再按「新增比率」，名稱還是「退貨率」，分子、分母隨便選兩個不同的，按「新增」：對話框不關，顯示「比率名稱「退貨率」跟欄位或其他比率重複」。按「取消」。
5. 用 `evaluate_script` 執行 `fetch('/api/datasources', { headers: { 'X-User-Id': '<USER>' } }).then((r) => r.json())`，這個來源的 `ratios` 是 `[{"name":"退貨率","numerator":"退貨·amount","denominator":"銷售·amount","sensitive":false}]`，另外兩個來源是 `[]`。

- [ ] **Step 7: 分析，確認退貨率的圖（spec 9 節 E2E 第 2 點）**

按「用這個來源製圖」：

1. 「02 欄位」最後一個標籤是「退貨率」，前面是百分比圖示；`hover` 上去（或用 `evaluate_script` 讀 `title`）是「退貨率（比率：退貨·amount ÷ 銷售·amount）」。
2. 不填需求、不勾欄位，按「分析」。用 `list_network_requests` 找預覽的 `POST /api/dashboards/preview/data`，`get_network_request` 讀回應：
   - 找 `percent` 是 `true` 的卡片，記下它的 `type`、`dimensions`、`rows`。
   - 時間軸的比率卡片：`2026-01`～`2026-06` 是 `null`，`2026-07`、`2026-08`、`2026-09` 約是 0.0349、0.0430、0.0417。
   - 其他每一張不是明細表的卡片，`rows` 裡至少有一個不是 `null` 的數值（沒有畫不出東西的圖）。
   - 明細表的欄位裡沒有「退貨率」，也沒有「來源」。
3. `take_screenshot`：退貨率那張圖的 Y 軸刻度是百分比（例如「4%」），1～6 月是空的（折線從 7 月開始，或長條只有 7～9 月）。標題是「退貨率 依 date」（或「退貨率 依 region」）。
4. `hover` 到 8 月那一點（或那根長條），截圖確認 tooltip 是「4.3%」。

JEV 沒挑退貨率的圖時，勾「退貨率」標籤再分析一次；還是沒有，在需求框輸入「每月退貨率」再分析。三次都沒有，就把三次預覽的卡片清單記在報告裡（這是 JEV 的判斷，不算失敗），跳過第 2 點的比率檢查和第 3、4 點，繼續。

- [ ] **Step 8: 存檔、重新打開、異常檢查（spec 9 節 E2E 第 4 點）**

按「存成新儀表板」，從左側清單重新打開它：

1. 退貨率那張圖照樣畫出來，Y 軸是百分比，1～6 月是空的。
2. `/highlight` 請求回 200（有沒有標註都可以，有的話記下是哪張卡片、哪個等級）；`grep -c Exception backend/target/e2e-backend.log` 是 0（JEV 逾時之類的 WARN 可以，要記下來）。
3. 切到淺色主題（導覽列最下面的切換鈕）再截一張圖，百分比的刻度看得清楚。

- [ ] **Step 9: 一般來源的毛利率（spec 9 節 E2E 第 3 點）**

1. 「資料」上傳 `margin.csv`，名稱「毛利明細」。顯示名稱改成：`order_date`「日期」、`revenue`「營收」、`gross_profit`「毛利」。
2. 新增比率「毛利率」，分子選「毛利」、分母選「營收」（選單用顯示名稱）。比率那一列的公式是「毛利 ÷ 營收」。
3. 把 `gross_profit` 標成敏感：毛利率那一列出現「敏感」，滑過去是「分子或分母是敏感欄位」。再取消敏感，「敏感」消失。
4. 按「用這個來源製圖」、「分析」。讀 `/preview/data` 的回應：有 `percent: true` 的毛利率卡片；時間軸的話 `2026-08` 約是 0.1931，其他月份約 0.228～0.239，沒有 `null`。標題用顯示名稱，例如「毛利率 依 日期」。截圖確認 Y 軸是百分比。JEV 沒挑的話照 Step 7 的方式再試。
5. 存成新儀表板、重新打開，`/highlight` 回 200；毛利率的卡片有標註的話記下等級（8 月比 7 月掉了約 4.6 個百分點，JEV 標不標都不算失敗）。

- [ ] **Step 10: 刪掉再加回來（spec 9 節 E2E 第 5 點）**

1. 回到「資料」，選「銷售／退貨 對照」，按「刪除比率 退貨率」：不用確認，那一列直接不見，變成「比率 · 0」。
2. 打開 Step 8 存的儀表板：退貨率那張卡片顯示「欄位遺失：退貨率」，其他卡片照常。
3. 回到「資料」，照 Step 6 第 3 點再加一次同名同公式的退貨率。
4. 再打開那份儀表板：退貨率那張卡片恢復成百分比的圖。截圖。

- [ ] **Step 11: 收尾**

1. `close_page` 關掉自己的分頁。
2. 停掉 Step 3、4 的背景程序。停不掉的（Maven fork 出來的 `java.exe` 常會留著），用 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'` 找出 PID，寫進報告給使用者處理，不要 `taskkill`。
3. commit 測試用的 CSV：

```bash
F="docs/superpowers/e2e/ratio-metric/margin.csv"
git add $F && git commit -m "test(e2e): 比率的手動驗證資料" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- $F
```

4. 報告：Step 5–10 每一項的結果（通過／不通過，附觀察到的值）、截圖的檔案路徑、使用者 id、留下來的 PID、中途修掉的問題和對應的 commit。

---

## 合併之後

- 使用者自己的 `jev_dashboard` 第一次跑 V4 之前先備份：`"/c/Program Files/PostgreSQL/17/bin/pg_dump.exe" -n jev_dashboard -F p -f <檔案>`，密碼用 `PGPASSWORD` 從 `backend/.env` 讀（不要印出來），備份放在 `C:\Users\kglob\jev-dashboard-backups\`。
- 合併進 main 之後，預設的 `jev_dashboard_test` 會在下一次跑測試時套用 V4；`jev_dashboard_test_b3`、`jev_dashboard_e2e_b3` 可以留著，也可以請使用者刪掉。
