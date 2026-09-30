# JEV 儀表板：JEV 建議疊加的欄位對應 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec 的「請 JEV 對欄位」。疊加、對照選了 2 個以上來源時，按一下就請 JEV 判斷哪兩個欄位是同一種資料，後端照規則挑出可以採用的答案，前端只填使用者還沒動過的格子，並標上「JEV 建議」。

**Architecture:** 後端在 `jev` 套件新增 `MappingService`。出題、`state`、採用規則都是 static 純函式，`suggest` 負責串起來呼叫 `analyzeJevClient`。`JevController` 多一支 `POST /api/jev/mapping`，輸入來源的檢查沿用 `MergeEngine` 的疊加規則（抽成 `requireUnionInputs`），欄位的 `state` 沿用分析第一輪的寫法（抽成 `AnalyzeService.field`）。前端 `api.js` 多一支 `suggestMapping`；`MergeForm.vue` 在對應表上方加按鈕，照 spec 5.2 套用結果。沒有 migration。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5 + AssertJ + MockMvc、本機假 JEV（`FakeJevServer`）；Vue 3.5、Vuetify 3.13、Vitest + @vue/test-utils + happy-dom。

**Spec:** `docs/superpowers/specs/2026-09-30-jev-column-mapping-design.md`

## Global Constraints

- 在主工作目錄 `C:\GitHub\jev-form-dashboard` 做，branch 是 `feat/jev-column-mapping`（已經 rebase 到 main `0e48ee9`，上面有 spec 的兩個 commit 和這份 plan）。不另開 worktree。
- 這次沒有 migration，後端測試用預設的 `jev_dashboard_test` schema，不必設 schema 環境變數。使用者開發用的 `jev_dashboard` 只有 Task 5 會讀，不寫入。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`，在 `backend/` 跑 `./mvnw test -Dtest=<類別>`。不要跑 `./mvnw clean`。不要印出 `backend/.env` 的內容。
- 前端在 `frontend/` 跑 `npx vitest run src/modules/dashboard/<檔案>.test.js`，全部跑是 `npm test`。
- 本機是 Windows 11 + Git Bash：不要用 `python3`；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`。不新增 npm 或 Maven 套件。
- 既有測試照舊通過，一行都不改，只加新的測試、fixture 和 import。例外只有兩個：`FakeJevServer.route` 最前面多一行認 `"map_1_` 的分支；`MergeForm.test.js` 最上面的 `vi.mock('./api.js', …)` 多一行 `suggestMapping: vi.fn(),`。
- 錯誤回應固定是 `{"error": "訊息"}`。這次沒有新的錯誤訊息，全部沿用：400「疊加要選 2 到 5 個來源」、400「疊加的來源不能重複」、404「找不到資料源」、503「未設定 TYPESAFE_API_KEY」、502「JEV 呼叫失敗（HTTP {狀態碼}）」。
- 送給 JEV 的字一字不差（spec 3.3）：
  - instructions：`來源「{B 的名稱}」的欄位「{c 的名稱}」，跟來源「{基準的名稱}」的哪個欄位是同一種資料？`
  - 候選：`"f<i>": "欄位「{基準第 i 個欄位的名稱}」"`；最後一個固定是 `"none": "都不是，這是另一種資料"`。
- 畫面上的字一字不差（spec 5）：
  - 按鈕「請 JEV 對欄位」，`prepend-icon="mdi-auto-fix"`。
  - 格子旁的標籤「JEV 建議」。
  - 提示：`JEV 對上了 {n} 個欄位，建立前請先預覽確認`；一筆都沒有時 `JEV 沒有找到可以對上的欄位`。
- 每個 commit 都用明確的 pathspec：`git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits 加繁體中文，例如 `feat(backend): …`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. 輸入來源的檢查：把 `MergeEngine.validateUnion` 開頭檢查數量和重複的兩行抽成 `public static void requireUnionInputs(List<UUID> inputs)`，`validateUnion` 改成呼叫它。`/mapping` 先呼叫它，再逐一 `datasources.get(userId, id)`（找不到或不是自己的會丟 404「找不到資料源」），這樣錯誤訊息一定跟建立合併來源時一樣。`inputs` 是 null 時算 0 個。
2. 欄位的 `state`：把 `AnalyzeService.state` 迴圈裡組一個欄位的那段抽成 package-private 的 `static Map<String, Object> field(FieldSchema f)`，分析和欄位對應共用。`datasources.get` 回的是有算過繼承敏感的 schema，所以繼承來的敏感欄位自然只剩名稱和型別。
3. 採用答案時，`f<i>` 要在那一題的候選裡才算數（用同一個 `candidates` 函式重算），不在候選裡的當作沒對上。JEV 照理只會回選項裡的 key，這一步順便把 `f<i>` 換回基準欄位的位置 i。
4. 回傳的 `matches` 先照來源的輸入順序，同一個來源裡再照欄位在那個來源 schema 裡的順序。
5. `state` 太大時沿用 `JevClient` 的 413，不另外處理。要五個來源、每個都有很多欄位才會碰到。
6. 前端用 `c.suggested`（`{ [來源 id]: 欄位名稱 }`）記 JEV 填了哪些格子。只有格子現在的值還是 JEV 填的那個時才顯示「JEV 建議」，所以使用者一改，標籤就消失，不必另外接事件。`definition` 只取 `name` 和 `from`，`suggested` 不會送進預覽或建立的請求。
7. 丟掉過期回應時比的是 `JSON.stringify([op, inputs])`。來源或方式一變，`watch([inputs, op])` 就會重排對應表，順便清掉提示文字。
8. 按下按鈕時清掉合併分頁的 `error` 和提示文字，跟「預覽」一樣。等待期間按鈕是 `loading`，函式開頭再擋一次 `if (suggesting.value) return`（`DatasourcePanel` 的刪除也這樣做），因為 Vuetify 的 `loading` 不會擋下點擊。

## Review Focus

- 手動設好的對應不能被蓋掉：`applyMatch` 兩列都找得到才套用（Task 3 測）。
- 對照模式絕不能把兩個來源的數字欄位併成一欄：後端對照時不問數字欄位（Task 1 測），前端丟掉「換了方式才回來」的回應（Task 3 測）。
- 敏感欄位（含繼承的）的樣本值和相異值數量不能送給 JEV（Task 1、Task 2 測）。
- 沒有要問的欄位時不呼叫 JEV；輸入不合法時，在呼叫 JEV 之前就擋下（Task 1、Task 2 測）。
- JEV 失敗要讓使用者看到，不能像異常檢查那樣吞掉（Task 1 測）。

## File Structure

```
backend/src/main/java/jev/dashboard/
  datasource/MergeEngine.java      requireUnionInputs（Task 1）
  jev/AnalyzeService.java          field（Task 1）
  jev/MappingService.java          新檔：出題、state、採用規則、suggest（Task 1）
  jev/JevController.java           POST /api/jev/mapping（Task 2）
backend/src/test/
  java/jev/dashboard/jev/MappingServiceTest.java   新檔（Task 1）
  java/jev/dashboard/jev/JevControllerTest.java    加測試（Task 2）
  java/jev/dashboard/FakeJevServer.java            route 多一行（Task 2）
  resources/jev/mapping.json                       新檔（Task 2）
frontend/src/modules/dashboard/
  api.js、api.test.js              suggestMapping（Task 3）
  MergeForm.vue、MergeForm.test.js 按鈕、套用、標籤、提示（Task 3）
docs/JEV智慧化產製儀表板的流程與方法.md、README.md、docs/backlog.md（Task 4、Task 5）
```

---

### Task 1: 後端：`MappingService` 的出題、`state` 和採用規則

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/MergeEngine.java:55-61`
- Modify: `backend/src/main/java/jev/dashboard/jev/AnalyzeService.java:264-276`
- Create: `backend/src/main/java/jev/dashboard/jev/MappingService.java`
- Test: `backend/src/test/java/jev/dashboard/jev/MappingServiceTest.java`

**Interfaces:**
- Produces:
  - `MergeEngine.requireUnionInputs(List<UUID> inputs): void`（public static）
  - `AnalyzeService.field(FieldSchema f): Map<String, Object>`（package-private static）
  - `MappingService.Match(UUID input, String field, String to)`（public record）
  - `MappingService.suggest(List<Datasource> inputs, boolean compare): List<Match>`
  - package-private static：`questions(List<Datasource>, boolean)`、`state(List<Datasource>)`、`matches(List<Datasource>, boolean, Map<String, Answer>)`、`candidates(List<FieldSchema>, List<FieldSchema>, FieldSchema, boolean)`、`key(int, int)`

- [ ] **Step 1: 寫失敗的測試**

Create `backend/src/test/java/jev/dashboard/jev/MappingServiceTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.FakeJevServer;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.MappingService.Match;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MappingServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final UUID ORDERS = UUID.randomUUID();
    static final UUID SALES = UUID.randomUUID();
    static final UUID RETURNS = UUID.randomUUID();

    static FieldSchema f(String name, FieldType type) {
        return new FieldSchema(name, type, 3, List.of("x"), false, null, null);
    }

    static Datasource ds(UUID id, String name, FieldSchema... fields) {
        return new Datasource(id, name, "upload", List.of(fields), null, Map.of(), "u1", null);
    }

    static Answer choice(String choice, double confidence) {
        return new Answer("choice", choice, null, confidence, null);
    }

    @Test
    void asksOnlyFieldsWithoutASameNameMatchAndOffersSameTypeBaseFieldsThatSourceLacks() throws Exception {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER), f("出貨日", FieldType.DATE), f("product", FieldType.STRING));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("product", FieldType.STRING),
            f("金額", FieldType.NUMBER), f("region", FieldType.STRING), f("amount", FieldType.NUMBER));

        Map<String, Question> q = MappingService.questions(List.of(orders, sales), false);

        // product、金額 同名，自動對上不問；amount 唯一同型別的「金額」，銷售自己也有，沒有候選不問
        assertThat(q.keySet()).containsExactly("map_1_0", "map_1_3");
        assertThat(JSON.writeValueAsString(q.get("map_1_0"))).isEqualTo("""
            {"type":"choice","instructions":"來源「銷售」的欄位「order_date」，跟來源「訂單」的哪個欄位是同一種資料？",\
            "criteria":{"f0":"欄位「訂單日期」","f3":"欄位「出貨日」","none":"都不是，這是另一種資料"}}""");
        // 基準的 product 銷售也有，不當 region 的候選
        assertThat(JSON.writeValueAsString(q.get("map_1_3").criteria()))
            .isEqualTo("{\"f1\":\"欄位「地區」\",\"none\":\"都不是，這是另一種資料\"}");
    }

    @Test
    void compareModeNeverAsksAboutNumberFields() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("amount", FieldType.NUMBER));

        assertThat(MappingService.questions(List.of(orders, sales), true).keySet()).containsExactly("map_1_0");
        assertThat(MappingService.questions(List.of(orders, sales), false).keySet()).containsExactly("map_1_0", "map_1_1");
    }

    @Test
    void everyLaterSourceIsComparedToTheFirstOnly() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("owner", FieldType.STRING));
        Datasource returns = ds(RETURNS, "退貨", f("return_date", FieldType.DATE), f("owner", FieldType.STRING));

        Map<String, Question> q = MappingService.questions(List.of(orders, sales, returns), false);

        // 銷售和退貨都有 owner，但基準沒有文字欄位，兩邊不會互相對
        assertThat(q.keySet()).containsExactly("map_1_0", "map_2_0");
        assertThat(q.get("map_2_0").instructions())
            .isEqualTo("來源「退貨」的欄位「return_date」，跟來源「訂單」的哪個欄位是同一種資料？");
    }

    @Test
    void stateListsEachSourceLikeRoundOneAndKeepsSensitiveValuesOut() {
        Datasource orders = ds(ORDERS, "訂單",
            new FieldSchema("訂單日期", FieldType.DATE, 6, List.of("2024-07-05", "備".repeat(70)), false, null, null),
            new FieldSchema("客戶", FieldType.STRING, 6, List.of("王小明"), true, null, null)).withRowCount(6);
        // 合併來源：沒記筆數；備註是從輸入來源繼承來的敏感欄位
        Datasource merged = new Datasource(SALES, "銷售（合併）", "merged",
            List.of(new FieldSchema("備註", FieldType.STRING, 12, List.of("首購"), false, null, null)
                .withInheritedSensitive("銷售")),
            null, Map.of(), "u1", null);

        JsonNode s = JSON.valueToTree(MappingService.state(List.of(orders, merged)));

        assertThat(s.at("/sources/0/name").asText()).isEqualTo("訂單");
        assertThat(s.at("/sources/0/rowCount").asInt()).isEqualTo(6);
        // 樣本值照分析的規則截到 60 個字
        assertThat(s.at("/sources/0/fields/0").toString()).isEqualTo(
            "{\"name\":\"訂單日期\",\"type\":\"date\",\"distinctCount\":6,\"samples\":[\"2024-07-05\",\""
                + "備".repeat(60) + "…\"]}");
        assertThat(s.at("/sources/0/fields/1").toString()).isEqualTo("{\"name\":\"客戶\",\"type\":\"string\"}");
        assertThat(s.at("/sources/1").has("rowCount")).isFalse();
        assertThat(s.at("/sources/1/fields/0").toString()).isEqualTo("{\"name\":\"備註\",\"type\":\"string\"}");
    }

    @Test
    void dropsNoneMissingLowConfidenceAndOffListAnswersButTakesExactlyTheThreshold() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("region", FieldType.STRING),
            f("amount", FieldType.NUMBER), f("ship_date", FieldType.DATE), f("memo", FieldType.STRING));

        List<Match> m = MappingService.matches(List.of(orders, sales), false, Map.of(
            "map_1_0", choice("none", 0.9),
            "map_1_1", choice("f1", 0.49),
            "map_1_2", choice("f2", 0.5),
            "map_1_3", choice("f1", 0.9))); // ship_date 是日期，f1（地區）不在它的選項裡；memo 沒回答

        assertThat(m).containsExactly(new Match(SALES, "amount", "金額"));
    }

    @Test
    void withinOneSourceTheMostConfidentClaimWinsAndTiesGoToTheEarlierField() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("amount", FieldType.NUMBER),
            f("qty", FieldType.NUMBER), f("region", FieldType.STRING), f("area", FieldType.STRING));
        Datasource returns = ds(RETURNS, "退貨", f("return_date", FieldType.DATE));

        List<Match> m = MappingService.matches(List.of(orders, sales, returns), false, Map.of(
            "map_1_0", choice("f0", 0.9),
            "map_1_1", choice("f2", 0.7),
            "map_1_2", choice("f2", 0.8),   // qty 比 amount 有把握，金額給 qty
            "map_1_3", choice("f1", 0.6),
            "map_1_4", choice("f1", 0.6),   // 跟 region 一樣高，留排前面的 region
            "map_2_0", choice("f0", 0.9))); // 不同來源對到同一個基準欄位，都留

        assertThat(m).containsExactly(
            new Match(SALES, "order_date", "訂單日期"),
            new Match(SALES, "qty", "金額"),
            new Match(SALES, "region", "地區"),
            new Match(RETURNS, "return_date", "訂單日期"));
    }

    @Test
    void nothingToAskSkipsJevAndAJevFailureIsNotSwallowed() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            MappingService service = new MappingService(
                new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
            Datasource orders = ds(ORDERS, "訂單", f("region", FieldType.STRING), f("訂單日期", FieldType.DATE));

            assertThat(service.suggest(List.of(orders, ds(SALES, "銷售", f("region", FieldType.STRING))), false)).isEmpty();
            assertThat(server.bodies).isEmpty();

            // 假伺服器回 500；欄位對應是使用者按了按鈕在等，失敗要讓他看到
            assertThatThrownBy(() -> service.suggest(List.of(orders, ds(SALES, "銷售", f("order_date", FieldType.DATE))), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessage("JEV 呼叫失敗（HTTP 500）");
            assertThat(server.bodies).hasSize(1);
        }
    }
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw test -Dtest=MappingServiceTest`
Expected: 編譯失敗，`cannot find symbol: class MappingService`。

- [ ] **Step 3: 抽出 `MergeEngine.requireUnionInputs`**

`MergeEngine.java` 的 `validateUnion` 開頭（第 56～59 行）：

```java
    public static void validateUnion(Union u, Function<UUID, Datasource> input) {
        List<UUID> ids = u.inputs() == null ? List.of() : u.inputs();
        if (ids.size() < 2 || ids.size() > MAX_UNION_INPUTS) throw bad("疊加要選 2 到 5 個來源");
        // List.of 的 contains(null) 會丟 NPE，用 anyMatch 檢查
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw bad("疊加的來源不能重複");
        Map<UUID, Datasource> byId = new LinkedHashMap<>();
        for (UUID id : ids) byId.put(id, input.apply(id));
```

改成：

```java
    public static void validateUnion(Union u, Function<UUID, Datasource> input) {
        requireUnionInputs(u.inputs());
        Map<UUID, Datasource> byId = new LinkedHashMap<>();
        for (UUID id : u.inputs()) byId.put(id, input.apply(id));
```

並在 `validateUnion` 前面加：

```java
    /** 疊加、對照的輸入來源：2～5 個、不能重複；JEV 建議欄位對應時也照這個規則（C2 spec 第 4 節）。 */
    public static void requireUnionInputs(List<UUID> inputs) {
        List<UUID> ids = inputs == null ? List.of() : inputs;
        if (ids.size() < 2 || ids.size() > MAX_UNION_INPUTS) throw bad("疊加要選 2 到 5 個來源");
        // List.of 的 contains(null) 會丟 NPE，用 anyMatch 檢查
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw bad("疊加的來源不能重複");
    }
```

- [ ] **Step 4: 抽出 `AnalyzeService.field`**

`AnalyzeService.state` 裡的迴圈（第 266～276 行）：

```java
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            if (!f.sensitive()) {
                m.put("distinctCount", f.distinctCount());
                m.put("samples", f.samples() == null ? null : f.samples().stream().map(AnalyzeService::clip).toList());
            }
            fields.add(m);
        }
```

改成：

```java
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) fields.add(field(f));
```

並在 `state` 前面加：

```java
    /** 第一輪 state 的一個欄位；敏感欄位只有名稱和型別。欄位對應（MappingService）也用這個。 */
    static Map<String, Object> field(FieldSchema f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.name());
        m.put("type", f.type());
        if (!f.sensitive()) {
            m.put("distinctCount", f.distinctCount());
            m.put("samples", f.samples() == null ? null : f.samples().stream().map(AnalyzeService::clip).toList());
        }
        return m;
    }
```

- [ ] **Step 5: 寫 `MappingService`**

Create `backend/src/main/java/jev/dashboard/jev/MappingService.java`：

```java
package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** C2：請 JEV 判斷疊加、對照時哪兩個欄位是同一種資料。JEV 只回答題目，採不採用由這裡的規則決定。 */
@Service
public class MappingService {
    /** 來源 input 的欄位 field，對到基準的欄位 to。 */
    public record Match(UUID input, String field, String to) {}

    static final String NONE = "none";

    private final JevClient jev;

    public MappingService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    /** inputs 照輸入順序，第一個是基準（spec 3.1）；沒有要問的欄位就不呼叫 JEV。 */
    public List<Match> suggest(List<Datasource> inputs, boolean compare) {
        Map<String, Question> questions = questions(inputs, compare);
        if (questions.isEmpty()) return List.of();
        return matches(inputs, compare, jev.ask(state(inputs), questions));
    }

    /** k 是來源在輸入裡的位置（從 1 起算），j 是欄位在那個來源 schema 裡的位置。 */
    static String key(int k, int j) {
        return "map_" + k + "_" + j;
    }

    /**
     * spec 3.2：c 的候選是基準裡跟 c 同型別、而且 c 的來源 b 沒有同名欄位的欄位位置。
     * 基準有同名欄位（已經自動對上）、對照模式的數字欄位不問，回空的。候選跟 c 同型別，所以對照時也不會有數字欄位。
     */
    static List<Integer> candidates(List<FieldSchema> base, List<FieldSchema> b, FieldSchema c, boolean compare) {
        if ((compare && c.type() == FieldType.NUMBER) || base.stream().anyMatch(f -> f.name().equals(c.name()))) {
            return List.of();
        }
        Set<String> own = b.stream().map(FieldSchema::name).collect(Collectors.toSet());
        return IntStream.range(0, base.size())
            .filter(i -> base.get(i).type() == c.type() && !own.contains(base.get(i).name()))
            .boxed().toList();
    }

    /** spec 3.3：每個要問的欄位一題 choice；key 和選項都用位置，不用欄位名稱。 */
    static Map<String, Question> questions(List<Datasource> inputs, boolean compare) {
        Datasource base = inputs.get(0);
        Map<String, Question> out = new LinkedHashMap<>();
        for (int k = 1; k < inputs.size(); k++) {
            Datasource b = inputs.get(k);
            for (int j = 0; j < b.schema().size(); j++) {
                FieldSchema c = b.schema().get(j);
                List<Integer> candidates = candidates(base.schema(), b.schema(), c, compare);
                if (candidates.isEmpty()) continue;
                Map<String, String> criteria = new LinkedHashMap<>();
                for (int i : candidates) criteria.put("f" + i, "欄位「" + base.schema().get(i).name() + "」");
                criteria.put(NONE, "都不是，這是另一種資料");
                out.put(key(k, j), Question.choice("來源「" + b.name() + "」的欄位「" + c.name() + "」，跟來源「"
                    + base.name() + "」的哪個欄位是同一種資料？", criteria));
            }
        }
        return out;
    }

    /** spec 3.4：每個欄位跟分析第一輪一樣；沒記筆數的來源不放 rowCount，不為了算筆數去載入資料。不送資料列。 */
    static Map<String, Object> state(List<Datasource> inputs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Datasource ds : inputs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", ds.name());
            if (ds.rowCount() != null) s.put("rowCount", ds.rowCount());
            s.put("fields", ds.schema().stream().map(AnalyzeService::field).toList());
            sources.add(s);
        }
        return Map.of("sources", sources);
    }

    /**
     * spec 3.5：none、沒回答、信心不到 MIN_CONFIDENCE 都不採用。同一個來源裡搶同一個基準欄位時留信心最高的，
     * 一樣高留排前面的；不同來源對到同一個基準欄位都留。結果先照來源、再照欄位在來源裡的順序。
     */
    static List<Match> matches(List<Datasource> inputs, boolean compare, Map<String, Answer> answers) {
        List<FieldSchema> base = inputs.get(0).schema();
        List<Match> out = new ArrayList<>();
        for (int k = 1; k < inputs.size(); k++) {
            List<FieldSchema> b = inputs.get(k).schema();
            Map<Integer, Integer> winner = new HashMap<>(); // 基準欄位位置 → 採用的 b 欄位位置
            for (int j = 0; j < b.size(); j++) {
                Answer a = answers.get(key(k, j));
                if (a == null || a.confidence() == null || a.confidence() < ConfigAssembler.MIN_CONFIDENCE) continue;
                // 選項以外的答案（含 none）當作沒對上
                Integer i = candidates(base, b, b.get(j), compare).stream()
                    .filter(x -> ("f" + x).equals(a.choice())).findFirst().orElse(null);
                if (i == null) continue;
                Integer prev = winner.get(i);
                if (prev == null || a.confidence() > answers.get(key(k, prev)).confidence()) winner.put(i, j);
            }
            UUID id = inputs.get(k).id();
            winner.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> out.add(new Match(id, b.get(e.getValue()).name(), base.get(e.getKey()).name())));
        }
        return out;
    }
}
```

- [ ] **Step 6: 跑測試確認通過**

Run: `./mvnw test -Dtest=MappingServiceTest,AnalyzeServiceTest,MergeEngineTest,MergedDatasourceServiceTest`
Expected: 全部 PASS（後三個是確認抽函式沒有改到行為）。

- [ ] **Step 7: Commit**

`feat(backend): JEV 建議欄位對應的出題、state 和採用規則`，檔案：`MergeEngine.java`、`AnalyzeService.java`、`MappingService.java`、`MappingServiceTest.java`。

---

### Task 2: 後端：`POST /api/jev/mapping`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`
- Modify: `backend/src/test/java/jev/dashboard/FakeJevServer.java:45-49`
- Create: `backend/src/test/resources/jev/mapping.json`
- Test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `MergeEngine.requireUnionInputs`、`MappingService.suggest`、`MappingService.Match`
- Produces:
  - `JevController.MappingRequest(List<UUID> inputs, boolean compare)`、`JevController.MappingResult(List<MappingService.Match> matches)`
  - `POST /api/jev/mapping` → 200 `{"matches":[{"input":"<uuid>","field":"order_date","to":"訂單日期"}]}`

- [ ] **Step 1: 加 fixture 和假 JEV 的路由**

Create `backend/src/test/resources/jev/mapping.json`（對 `訂單`＋`銷售`：`sales.csv` 的 6 個欄位各一題；基準是 `訂單日期`(f0)、`地區`(f1)、`金額`(f2)、`出貨日`(f3)）：

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "map_1_0": {"type": "choice", "choice": "f0", "confidence": 0.9},
    "map_1_1": {"type": "choice", "choice": "f2", "confidence": 0.85},
    "map_1_2": {"type": "choice", "choice": "f1", "confidence": 0.9},
    "map_1_3": {"type": "choice", "choice": "none", "confidence": 0.8},
    "map_1_4": {"type": "choice", "choice": "f2", "confidence": 0.6},
    "map_1_5": {"type": "choice", "choice": "f1", "confidence": 0.3}
  }
}
```

`FakeJevServer.route` 最前面加一行，javadoc 改成「依請求內容挑 fixture：欄位對應、異常檢查、第二輪、第一輪。」：

```java
        if (body.contains("\"map_1_")) return Fixtures.json("mapping.json");
```

- [ ] **Step 2: 寫失敗的測試（`JevControllerTest`）**

加 import：`java.util.List`、`java.util.stream.Collectors`、`java.util.stream.IntStream`、`org.springframework.test.web.servlet.ResultActions`。加在類別裡：

```java
    static final String ORDERS_CSV = "訂單日期,地區,金額,出貨日\n2024-07-05,北區,1200,2024-07-08\n2024-07-20,南區,800,2024-07-22\n";

    ResultActions mapping(String userId, String body) throws Exception {
        return mvc.perform(post("/api/jev/mapping").header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void mappingAsksJevOnceAndReturnsOnlyTheMatchesThatPassTheRules() throws Exception {
        String orders = TestData.uploadCsv(mvc, "u1", "訂單", ORDERS_CSV);
        String sales = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + sales + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());

        // mapping.json：product 答 none；qty 也選金額，但比 amount 沒把握；memo 信心只有 0.3
        mapping("u1", "{\"inputs\":[\"" + orders + "\",\"" + sales + "\"]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matches[*].input").value(contains(sales, sales, sales)))
            .andExpect(jsonPath("$.matches[*].field").value(contains("order_date", "amount", "region")))
            .andExpect(jsonPath("$.matches[*].to").value(contains("訂單日期", "金額", "地區")));

        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"map_1_0\"").contains("\"map_1_5\"")
            .contains("\"rowCount\":2").contains("\"rowCount\":12").doesNotContain("首購");
    }

    @Test
    void mappingWithNothingToAskReturnsNoMatchesWithoutCallingJev() throws Exception {
        String a = TestData.uploadSales(mvc, "u1");
        String b = TestData.uploadSales(mvc, "u1");

        mapping("u1", "{\"inputs\":[\"" + a + "\",\"" + b + "\"],\"compare\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matches.length()").value(0));
        assertThat(JEV.bodies).isEmpty();
    }

    @Test
    void mappingChecksTheInputsBeforeCallingJev() throws Exception {
        String a = TestData.uploadSales(mvc, "u1");
        String b = TestData.uploadCsv(mvc, "u1", "訂單", ORDERS_CSV);
        String six = IntStream.range(0, 6).mapToObj(i -> "\"" + UUID.randomUUID() + "\"").collect(Collectors.joining(","));

        for (String body : List.of("{}", "{\"inputs\":[\"" + a + "\"]}", "{\"inputs\":[" + six + "]}")) {
            mapping("u1", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("疊加要選 2 到 5 個來源"));
        }
        mapping("u1", "{\"inputs\":[\"" + a + "\",\"" + a + "\"]}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("疊加的來源不能重複"));
        mapping("u2", "{\"inputs\":[\"" + b + "\",\"" + a + "\"]}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `./mvnw test -Dtest=JevControllerTest`
Expected: 三個新測試 FAIL（404「找不到這個 API」），其他照舊 PASS。

- [ ] **Step 4: 實作 `JevController`**

在 `AnalyzeRequest` 後面加：

```java
    /** C2 spec 第 4 節：inputs 的第一個是基準；compare 是對照，JSON 沒寫就是 false。 */
    public record MappingRequest(List<UUID> inputs, boolean compare) {}
    public record MappingResult(List<MappingService.Match> matches) {}
```

建構子多注入 `MappingService mappings`（欄位 `private final MappingService mappings;`，參數加在 `highlights` 後面）。在 `analyze` 後面加：

```java
    /** 不快取：同樣的來源再按一次，就再問一次（C2 spec 第 4 節）。 */
    @PostMapping("/mapping")
    public MappingResult mapping(@RequestHeader("X-User-Id") String userId, @RequestBody MappingRequest body) {
        MergeEngine.requireUnionInputs(body.inputs());
        List<Datasource> inputs = body.inputs().stream().map(id -> datasources.get(userId, id)).toList();
        return new MappingResult(mappings.suggest(inputs, body.compare()));
    }
```

- [ ] **Step 5: 跑全部後端測試**

Run: `./mvnw test`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

`feat(backend): POST /api/jev/mapping，請 JEV 建議疊加的欄位對應`，檔案：`JevController.java`、`FakeJevServer.java`、`mapping.json`、`JevControllerTest.java`。

---

### Task 3: 前端：「請 JEV 對欄位」

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`、`MergeForm.vue`
- Test: `frontend/src/modules/dashboard/api.test.js`、`MergeForm.test.js`

**Interfaces:**
- Consumes: Task 2 的 `POST /api/jev/mapping`，回傳 `{ matches: [{ input, field, to }] }`
- Produces: `api.suggestMapping(inputs, compare)` → `POST /api/jev/mapping`，body `{ inputs, compare }`

- [ ] **Step 1: 寫失敗的測試**

`api.test.js` 的 `describe('api')` 裡加：

```js
  it('suggestMapping POSTs the inputs and the compare flag to /api/jev/mapping', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ matches: [] }))
    await api.suggestMapping(['a', 'b'], true)
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/jev/mapping')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ inputs: ['a', 'b'], compare: true }))
  })
```

`MergeForm.test.js`：`vi.mock` 的 `api` 多一行 `suggestMapping: vi.fn(),`。檔案最後加：

```js
describe('MergeForm（JEV 對欄位）', () => {
  const orders = {
    id: 'o',
    name: '民國年訂單',
    kind: 'upload',
    schema: [
      { name: '訂單日期', type: 'date' },
      { name: '地區', type: 'string' },
      { name: '金額', type: 'number' },
      { name: '出貨日', type: 'date' },
    ],
  }
  const sales = {
    id: 'sa',
    name: 'sales',
    kind: 'upload',
    schema: [
      { name: 'order_date', type: 'date' },
      { name: 'region', type: 'string' },
      { name: 'amount', type: 'number' },
      { name: 'product', type: 'string' },
    ],
  }
  const THREE = {
    matches: [
      { input: 'sa', field: 'order_date', to: '訂單日期' },
      { input: 'sa', field: 'region', to: '地區' },
      { input: 'sa', field: 'amount', to: '金額' },
    ],
  }
  const mountJev = () => mount(MergeForm, { props: { sources: [orders, sales, north] }, global: { plugins: [vuetify] } })
  const tags = (w) => w.findAll('.v-chip').filter((c) => c.text() === 'JEV 建議')
  const way = async (w, value) => {
    await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', value)
    await flushPromises()
  }
  const ask = async (w) => {
    await btn(w, '請 JEV 對欄位').trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('the button shows for 疊加 and 對照 with 2+ sources only, and sends the compare flag', async () => {
    api.suggestMapping.mockResolvedValue({ matches: [] })
    const w = mountJev()
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()
    await pick(w, '來源', ['o'])
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()

    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    expect(api.suggestMapping).toHaveBeenLastCalledWith(['o', 'sa'], false)

    await way(w, 'compare')
    await ask(w)
    expect(api.suggestMapping).toHaveBeenLastCalledWith(['o', 'sa'], true)

    await way(w, 'lookup')
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()
  })

  it('fills the base row, removes the extra row, tags the cell, and drops the old preview', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 10, warnings: [] })
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('預覽 · 共 10 筆')

    await ask(w)

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['訂單日期', '地區', '金額', '出貨日', 'product'])
    expect(select(w, '第 1 欄在sales的欄位').props('modelValue')).toBe('order_date')
    expect(select(w, '第 2 欄在sales的欄位').props('modelValue')).toBe('region')
    expect(select(w, '第 3 欄在sales的欄位').props('modelValue')).toBe('amount')
    expect(tags(w)).toHaveLength(3)
    expect(w.text()).toContain('JEV 對上了 3 個欄位，建立前請先預覽確認')
    expect(w.text()).not.toContain('共 10 筆')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()
  })

  it('skips a match the user already changed by hand', async () => {
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    // 手動把 order_date 對到出貨日，刪掉 order_date 自己那一列：訂單日期那格還空著，但 order_date 那列已經不在了
    await pick(w, '第 4 欄在sales的欄位', 'order_date')
    await btn(w, '刪除第 5 欄').trigger('click')

    await ask(w)

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['訂單日期', '地區', '金額', '出貨日', 'product'])
    expect(select(w, '第 1 欄在sales的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 4 欄在sales的欄位').props('modelValue')).toBe('order_date')
    expect(tags(w)).toHaveLength(2)
    expect(w.text()).toContain('JEV 對上了 2 個欄位')
  })

  it('ignores a second click while waiting, and drops a response that comes back after the sources changed', async () => {
    let resolve
    api.suggestMapping.mockReturnValue(new Promise((r) => { resolve = r }))
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await btn(w, '請 JEV 對欄位').trigger('click')
    await btn(w, '請 JEV 對欄位').trigger('click')
    expect(api.suggestMapping).toHaveBeenCalledTimes(1)

    await pick(w, '來源', ['o', 'sa', 'n']) // 基準沒變，但來源換了，對應表已經重排
    resolve(THREE)
    await flushPromises()

    expect(columnNames(w)).toHaveLength(8)
    expect(tags(w)).toHaveLength(0)
    expect(w.text()).not.toContain('JEV 對上了')
  })

  it('the JEV 建議 tag goes away once the user changes that cell', async () => {
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    await pick(w, '第 1 欄在sales的欄位', null)
    expect(tags(w)).toHaveLength(2)
  })

  it('says so when JEV finds nothing, and shows a failure in the error area without touching the table', async () => {
    api.suggestMapping.mockResolvedValueOnce({ matches: [] })
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    expect(w.text()).toContain('JEV 沒有找到可以對上的欄位')

    api.suggestMapping.mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))
    await ask(w)
    expect(w.text()).toContain('JEV 呼叫失敗（HTTP 500）')
    expect(w.text()).not.toContain('JEV 沒有找到')
    expect(columnNames(w)).toHaveLength(8)
  })
})
```

（`north`、`btn`、`select`、`pick`、`columnNames` 是檔案上面既有的 helper。`orders`＋`sales` 照名稱排是 8 列：`訂單日期`、`地區`、`金額`、`出貨日`、`order_date`、`region`、`amount`、`product`；加上 `north`（`region`、`amount`）還是 8 列。）

- [ ] **Step 2: 跑測試確認失敗**

Run: `npx vitest run src/modules/dashboard/api.test.js src/modules/dashboard/MergeForm.test.js`
Expected: 新測試 FAIL，既有測試照舊 PASS。

- [ ] **Step 3: 實作 `api.js`**

在 `analyze` 那行後面加：

```js
  // C2 spec 第 4 節：inputs 的第一個是基準；compare 是對照
  suggestMapping: (inputs, compare) => request('POST', '/api/jev/mapping', { inputs, compare }),
```

- [ ] **Step 4: 實作 `MergeForm.vue` 的 script**

在 `const busy = ref(false)` 後面加：

```js
const suggesting = ref(false)
const suggestNote = ref('')
```

既有的 `watch([inputs, op], …)` 第一行加 `suggestNote.value = ''`：

```js
watch([inputs, op], ([ids, mode]) => {
  suggestNote.value = ''
  if (mode === 'union') columns.value = matchByName(ids)
  if (mode === 'compare') columns.value = [...matchByName(ids, (f) => f.type !== 'number'), ...splitNumbers(ids)]
})
```

在 `create()` 後面加：

```js
// C2 spec 5.3：格子的值還是 JEV 填的那個，才標「JEV 建議」
const jevPicked = (c, id) => !!c.suggested?.[id] && c.suggested[id] === c.from[id]

// C2 spec 5.2：基準欄位 to 那一列的 input 格子是空的，field 自己那一列只有 input 格子有值，兩列都在才套用；
// 任何一列找不到，就是使用者已經改過，跳過
function applyMatch({ input, field, to }) {
  const target = columns.value.find((c) => c.from[inputs.value[0]] === to && !c.from[input])
  const own = columns.value.find((c) => c.from[input] === field && inputs.value.every((id) => id === input || !c.from[id]))
  if (!target || !own) return false
  target.from[input] = field
  target.suggested = { ...target.suggested, [input]: field }
  columns.value.splice(columns.value.indexOf(own), 1)
  return true
}

const sourcesKey = computed(() => JSON.stringify([op.value, inputs.value]))

async function suggest() {
  if (suggesting.value) return // v-btn 的 loading 不會擋點擊
  const key = sourcesKey.value
  suggesting.value = true
  suggestNote.value = ''
  error.value = ''
  try {
    const { matches } = await api.suggestMapping(inputs.value, op.value === 'compare')
    // 等回應的時候來源或方式換了，對應表已經重排過，這份結果丟掉
    if (key !== sourcesKey.value) return
    let n = 0
    for (const m of matches) if (applyMatch(m)) n++
    suggestNote.value = n ? `JEV 對上了 ${n} 個欄位，建立前請先預覽確認` : 'JEV 沒有找到可以對上的欄位'
  } catch (e) {
    if (key === sourcesKey.value) error.value = e.message
  } finally {
    suggesting.value = false
  }
}
```

- [ ] **Step 5: 實作 `MergeForm.vue` 的 template**

`<template v-if="inputs.length">` 裡、`<v-table … class="mf-map mt-3">` 前面加：

```html
        <div v-if="inputs.length >= 2" class="mt-3">
          <v-btn variant="tonal" size="small" prepend-icon="mdi-auto-fix" :loading="suggesting" @click="suggest">
            請 JEV 對欄位
          </v-btn>
          <div v-if="suggestNote" class="text-caption mt-1">{{ suggestNote }}</div>
        </div>
```

對應表每一格的 `v-select` 外面包一層，後面接標籤：

```html
              <td v-for="id in inputs" :key="id">
                <div class="d-flex align-center ga-1">
                  <v-select
                    v-model="c.from[id]"
                    :items="fieldItems(id)"
                    item-props
                    :aria-label="`第 ${i + 1} 欄在${nameOf(id)}的欄位`"
                    placeholder="（無）"
                    persistent-placeholder
                    clearable
                    density="compact"
                    hide-details
                  />
                  <v-chip v-if="jevPicked(c, id)" size="x-small" label>JEV 建議</v-chip>
                </div>
              </td>
```

- [ ] **Step 6: 跑前端全部測試**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 7: Commit**

`feat(frontend): 合併分頁可以請 JEV 對欄位，只填還沒動過的格子`，檔案：`api.js`、`api.test.js`、`MergeForm.vue`、`MergeForm.test.js`。

---

### Task 4: 文件

**Files:**
- Modify: `docs/JEV智慧化產製儀表板的流程與方法.md`、`README.md`、`docs/backlog.md`

- [ ] **Step 1: `docs/JEV智慧化產製儀表板的流程與方法.md`**

- 第 11 節第一句「送給 JEV 的內容只有四種」改成「五種」，清單最後加第 5 點：合併時請 JEV 對欄位，送參加合併的來源名稱和筆數，以及每個欄位的名稱、型別、相異值數量、樣本值（跟第一輪一樣，敏感欄位含繼承的只留名稱和型別）；不送資料列，也不送使用者在對應表上做過的調整（第 14 節）。
- 原本的「## 14. 限制與後續方向」改成「## 15.」，前面插入「## 14. 合併時的欄位對應」，內容照 spec 第 2、3 節和 5.2 節寫：JEV 只判斷、規則在後端；第一個來源是基準；`map_<k>_<j>` 題目和 `f<i>`／`none` 選項；哪些欄位不問；`state` 的 `sources`；採用規則（`none`、沒回答、confidence 小於 0.5 不採用，同一來源搶同一個基準欄位留最高、同分留前面，不同來源都留）；前端只在兩列都找得到時套用，過期回應丟掉。最後附 spec 路徑。
- 第 12 節的表格加一列：「請 JEV 對欄位時 JEV 失敗」→「503／502 照分析的規則，錯誤顯示在合併分頁，對應表不動」。

- [ ] **Step 2: `README.md`**

「合併來源」一節在三個項目符號後面、「建立前一定要先按「預覽」」那段前面加一段：欄位名稱對不上時（例如一份中文、一份英文），可以按對應表上方的「請 JEV 對欄位」。JEV 只會把型別相同的欄位對起來，名稱已經一樣的欄位不問，三個以上來源時都跟第一個來源比。JEV 填的格子旁邊標著「JEV 建議」，手動設好的對應不會被蓋掉。JEV 也會對錯，它填的對應要自己看過，一樣要預覽過才能建立。最後連到 spec。

- [ ] **Step 3: `docs/backlog.md`**

「功能構想」裡「編輯合併來源的定義」那一項前面加四項，出處都寫「（C2 spec 第 8 節）」：
- P3 · 查表的對接欄位建議，例如 `product` ↔ `code`；後端可以先算兩邊的值對上幾成。
- P3 · 合併方式建議：選好來源之後，建議用疊加、對照還是查表。
- P3 · 值的對齊：「北區」和「North」這類寫法不同的值當成同一個。
- P3 · 從一句需求直接組出合併定義。

- [ ] **Step 4: 用 `humanizer-zh` 潤過這三份文件新增和改動的文字**

使用者的 CLAUDE.md 規定文件文字一律要用 `humanizer-zh` 處理。

- [ ] **Step 5: Commit**

`docs: 流程文件、README 補 JEV 對欄位，backlog 記下 C2 不做的四項`

---

### Task 5: 瀏覽器驗證（主 session 自己做，不交給子 agent）

照 spec 第 7 節「瀏覽器」：真的呼叫 JEV，用 2026-09-30 示範的那一組。使用者自己的 `8080`、`5173` 不動。

- [ ] **Step 1: 起一個 8081 的後端**

子 agent 在 `backend/` 跑 `./mvnw -q package -DskipTests`（測試 Task 2 已經跑過）。主 session 用 `run_in_background` 在 `backend/` 跑 `java -jar target/jev-dashboard-0.1.0-SNAPSHOT.jar --server.port=8081`（`.env` 是照工作目錄找的，所以一定要在 `backend/` 跑）。先用 `netstat -ano | grep -E ":(8081|5181) .*LISTENING"` 確認兩個 port 沒被占用。

- [ ] **Step 2: 起一個 5181 的前端，使用者是 `demo-user`**

在 scratchpad 寫 `vite.c2.config.js`：

```js
import base from 'C:/GitHub/jev-form-dashboard/frontend/vite.config.js'

export default {
  ...base,
  root: 'C:/GitHub/jev-form-dashboard/frontend',
  server: { port: 5181, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8081' } },
}
```

在 `frontend/` 用 `run_in_background` 跑 `VITE_USER_ID=demo-user npx vite --config <scratchpad>/vite.c2.config.js`。Chrome 開新分頁到 `http://localhost:5181`，只操作自己開的分頁。

- [ ] **Step 3: 操作並截圖**

1. 資料頁確認 `demo-user` 底下有「民國年訂單.csv」和「sales.csv」。找不到就停下來問使用者檔案在哪，不要自己編一份。
2. 「新增來源」→「合併」→「疊加」，來源依序選「民國年訂單.csv」「sales.csv」，按「請 JEV 對欄位」。截圖。
3. 對照 spec 7 的期望：`order_date`→訂單日期、`region`→地區、`amount`→金額三個對上；`product`、`qty`、`memo`、出貨日各自留成一列。每一格照實記下對或錯，含 JEV 沒對上的和對錯的。
4. 按「預覽」確認能預覽（不按「建立」，不寫進 `jev_dashboard`）。截圖。
5. 換成「對照」再按一次，確認數字欄位沒有被對起來。截圖。

截圖都要自己 Read 過才算數。

- [ ] **Step 4: 記錄結果**

`docs/backlog.md` 的「P2 · 送給 JEV 的題目和選項改成英文」那一項後面補一句 C2 實測結果（2026-09-30，哪幾個對、哪幾個錯），用 `humanizer-zh` 潤過。這項要不要提前做是使用者的決定，在回報裡問，不要自己改優先度。

Commit：`docs: backlog 記下 C2 真的呼叫 JEV 的結果`

- [ ] **Step 5: 收尾**

停掉 8081 的 java 和 5181 的 vite（`TaskStop`；還留著的話給使用者 `taskkill` 指令）。回報每一步的結果和截圖路徑；有問題就修，修完重跑受影響的測試和那一步。之後照使用者平常的流程，等使用者說可以再 merge、push。
