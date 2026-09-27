# JEV 儀表板：合併來源（疊加與查表）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-27-merged-datasource-design.md`：新增資料來源種類 `merged`，可以把現有來源疊加或查表合成一份新來源，載入時才即時合併；敏感設定從輸入來源繼承而且關不掉；「新增來源」對話框多一個「合併」分頁，建立前一定要先預覽。

**Architecture:** 合併定義是 Jackson 多型的 sealed interface `MergeDefinition`（`op` = `union` / `lookup`），存在 `datasource.merge_json`。合併規則全部寫成純函式 `MergeEngine`：檢查定義、合併資料列、算出輸出欄位。`DatasourceService.load` 遇到 `merged` 就遞迴載入輸入來源、交給 `MergeEngine`；`get`／`list`／`load` 讀取合併來源時，才即時算出繼承來的敏感設定（`FieldSchema.sensitiveFrom`），不存進資料庫。下游的 JEV 分析、轉換器、異常檢查都不改。前端新增 `MergeForm.vue`，放在 `DatasourcePanel.vue` 對話框的第三個分頁。

**Tech Stack:** Java 21、Spring Boot 3.5.16（Jackson 2.21、Flyway）、JUnit 5 + AssertJ + MockMvc；Vue 3.5、Vuetify 3.13、Vitest 5 + @vue/test-utils 2.5 + happy-dom 20。

前一版的計畫在 `docs/superpowers/plans/2026-09-24-jev-dashboard-backend.md`、`docs/superpowers/plans/2026-09-24-jev-dashboard-frontend.md`，這份只寫新增和改變的部分。

## Global Constraints

- 在獨立的 worktree 裡做，branch 是 `feat/merged-datasource`，從 `docs/multi-source-specs` 開（上面已經有 A 和 B1 兩份 spec 的 commit）。子專案 A（`docs/superpowers/specs/2026-09-27-multi-source-dashboard-design.md`）同時在另一個 worktree 開發。
- 這份的 migration 是 `V3__merged_datasource.sql`，A 用 `V2`，而且 A 先合併進 main。要是 B1 先合併，合併前把檔名改成 `V2__merged_datasource.sql`，因為 Flyway 不接受版本號比已套用的 migration 還小的新檔。
- A 也會改 `frontend/src/modules/dashboard/api.js`（兩邊各自加方法）、`README.md` 和 `docs/backlog.md`（兩邊各自加段落）。合併時保留兩邊的內容，不要整段蓋掉。
- 資料庫 schema 跟 A 錯開，做法跟 A 的計畫一樣（A 用 `jev_dashboard_test_a`、`jev_dashboard_e2e_a`）。兩個 worktree 如果共用 `jev_dashboard_test`，同時跑測試會互相 truncate 資料表；兩邊的 migration 版本也不一樣（A 有 V2，B1 有 V3），雙方的 Flyway 驗證都會失敗。所以這份計畫的每一個後端測試指令，前面都要加上 `export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1;`。環境變數的優先權比 `application-test.yml` 高，Flyway 的 `create-schemas: true` 會自己建 schema。手動驗證（Task 11）的後端用全新的 `jev_dashboard_e2e_b1`。使用者開發用的 `jev_dashboard` 完全不碰，合併進 main 之前也不能先遷移它。
- A 和 B1 的手動驗證不能同時跑，兩邊都要用 8080 和 5180（前端的 proxy 寫死 8080）。Task 11 開始前先確認這兩個埠沒人在用；有人在用就停下來回報，等 A 的手動驗證結束。
- 本機是 Windows 11 + Git Bash。不要用 `python3`（用 `python`）；丟掉輸出用 `> /dev/null 2>&1`，不要寫 `> NUL`。
- 後端指令前面一律加 `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH";`（再接上面的 schema 設定）。不要跑 `./mvnw clean`。後端測試會連本機 PostgreSQL，帳密在 `backend/.env`。
- 不要印出 `backend/.env` 的內容，`cat`、`grep` 整行都不行。這個檔在 gitignore 裡，worktree 不會有，Task 1 會從主工作目錄複製一份過來。
- 寫 `.sql` 檔要是被權限擋下來，就停下來回報，不要換別的方式寫。
- 儀表板的前端程式碼只放在 `frontend/src/modules/dashboard/`，不能 import 這個資料夾以外的檔案。
- 不新增 npm 或 Maven 套件。
- 既有測試全部照舊通過。既有的測試檔只會加新的測試、helper 和 import，唯一的例外是 `DatasourcePanel.test.js` 的「上傳檔案 and 外部 API are not pages in a sequence」：多了第三個分頁，預期值要從兩組改成三組（Task 9 寫明）。
- 錯誤回應固定是 `{"error": "訊息"}`。這次新增的 400 訊息一字不差照下面寫（第一組來自 spec 第 9 節，其餘是這份計畫補上的，見「實作上的決定」）：
  - 疊加：「疊加要選 2 到 5 個來源」「疊加的來源不能重複」「至少要有一個輸出欄位」「欄位名稱不能空白」「欄位名稱最多 50 個字」「欄位名稱「{名稱}」重複」「來源欄名稱不能空白」「來源欄名稱最多 50 個字」「欄位「{名稱}」至少要對應一個來源」「欄位「{名稱}」對應到不在疊加裡的來源」「{來源名}沒有 {欄位} 這個欄位」「「{名稱}」在{來源 A}是{型別}、在{來源 B}是{型別}，不能併成同一欄」（型別寫「數字」「日期」「文字」）。
  - 查表：「查表要選主來源和主檔」「主來源和主檔不能是同一個」「對接欄位要 1 到 3 組」「對接欄位「{主來源欄位}」是{型別}、「{主檔欄位}」是{型別}，型別要相同」「至少要補上一個欄位」「欄位名稱「{名稱}」跟主來源的欄位重複」。
  - 共用：「請選擇合併方式」（沒帶 `merge`）、「請輸入名稱」（建立時名稱空白）、「合併最多 3 層」、「沒有資料列」、「資料有 {N} 筆，超過上限 {上限} 筆」（後兩個沿用現有的 `checkRows`）。`op` 不認得時由現有的處理器回「請求內容不是正確的 JSON」。
  - 輸入來源找不到或不是自己的：404「找不到資料源」。
- 預覽的提示照 spec 第 7 節：「「{欄位}」只有{來源名，用、分隔}有，其他來源的列這欄是空的」「主檔有 {N} 個對接值重複，用排最前面的那一列」「{N} 筆對不到主檔，補上的欄位是空的」。
- 畫面上的字照 spec 第 8 節：分頁「合併」；方式「疊加（欄位一樣的資料接起來）」「查表（用主檔補欄位）」；「來源」「輸出欄位」「（無）」「新增一欄」「來源欄名稱」（預設「來源」）「主來源」「主檔」「對接欄位」「新增對接欄位」「補上的欄位」「名稱」「預覽」「建立」「預覽 · 共 {N} 筆」；型別對不上的列寫「型別不一致：{來源}是{型別}、{來源}是{型別}」；清單標籤「合併」；說明列「疊加：{來源名，用、分隔}」「查表：{主來源} ← {主檔}（{主來源欄位} = {主檔欄位}，多組用、分隔）」，查不到的來源寫「（已不存在）」；繼承來的敏感開關提示「在『{來源名}』標為敏感，要改請到那個來源」。
- 上限：疊加 2～5 個來源、對接欄位 1～3 組、巢狀最多 3 層、欄位名稱最多 50 字、預覽 20 列、合併後的筆數 1 到 `jev.upload.max-rows`（測試設定是 20）。
- 前端測試要用假計時器時，只假 `setTimeout` 和 `clearTimeout`（這份計畫用不到）。
- 每個 commit 都用明確的 pathspec，格式是 `git add <檔案…> && git commit -m "<訊息>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <檔案…>`。訊息用 Conventional Commits 加繁體中文，例如 `feat(backend): …`。

## 實作上的決定

spec 沒寫死、由這份計畫決定的地方：

1. 定義檢查放在 `MergeEngine`，是純函式 `validateUnion`、`validateLookup`、`validate`，測試寫在 `MergeEngineTest`。`DatasourceService` 只管輸入來源是不是自己的（404）、層數、筆數和名稱。spec §10 把驗證測試列在 `DatasourceServiceTest`，這裡改寫在新的 `MergedDatasourceServiceTest` 和 `MergeEngineTest`：這些測試要用記憶體版的 repository，放進原本的檔案會跟既有的匿名 repository 混在一起。
2. 輸出欄位的型別照輸入欄位，不重新推斷。spec 6.1 說拿合併後的資料列跑 `SchemaInference.infer`；相異值數量、樣本、日期的最大最小值照這樣算，型別則沿用輸入欄位，來源欄是文字。理由是查表只補上一部分主檔的列，剛好補上的值全是數字時，文字欄位會被推斷成數字，可是值還是字串，轉換器加總時會全部略過。
3. 繼承來的顯示名稱，要跟所有輸出欄位的名稱都不同、前面也沒有別的欄位用過，才會保留。這樣每一欄顯示出來的名稱（顯示名稱或原名）都不會重複，達到 spec 6.2「後面那一欄不繼承」的目的。
4. 載入時只擋上限。spec 5.4 只說載入時超過上限要報錯；合併後是 0 筆時照常回傳，跟外部 API 來源載入時一樣。
5. 查表至少要補一個欄位，什麼都不補的查表沒有意義。訊息是「至少要補上一個欄位」。
6. 預覽不看名稱。`POST .../merged/preview` 的 body 照 spec 帶 `name`，後端不檢查也不使用；建立時才檢查，空白回「請輸入名稱」，存檔前去掉前後空白。
7. 資料庫只存自己勾的敏感設定。`markSensitive` 和 `setLabels` 都改成拿資料庫裡的 schema 來改，不拿即時算出來的那份。`markSensitive` 收到繼承來的欄位會略過，這樣輸入來源取消敏感時，合併來源才會跟著取消。
8. 疊加時，有對應但值是空的欄位也不放 key。spec 5.1 只說沒對應的欄位不放 key，這裡一起比照，跟 `SchemaInference.normalize` 之後「上游省略空值」的語意一致。
9. 巢狀時 `sensitiveFrom` 一路往下找，提示寫的是可以關掉開關的那個來源，不是中間那層合併來源。
10. 合併表單：換了疊加的來源，對應表就重新排過，手動改過的對應會不見（有 `ponytail:` 註記）。「補上的欄位」預設全勾，被選成對接欄位的那一欄才取消勾選。表單自己帶「預覽」「建立」按鈕，對話框下方只留「取消」。繼承來的敏感開關停用，提示放在那一格原生的 `title` 上。
11. `op` 不認得時由 Jackson 丟出，照現有的處理器回「請求內容不是正確的 JSON」；沒帶 `merge` 就回「請選擇合併方式」。

## File Structure

```
backend/src/main/resources/db/migration/
  V3__merged_datasource.sql                 merge_json 欄位、kind 加 merged、兩個 check（Task 1）
backend/src/main/java/jev/dashboard/datasource/
  MergeDefinition.java        新增：sealed interface，Union／Lookup 兩種定義（Task 1）
  Datasource.java             加 merge（Task 1）
  FieldSchema.java            加 sensitiveFrom、withInheritedSensitive（Task 1）
  DatasourceRepository.java   讀寫 merge_json（Task 1）
  MergeEngine.java            新增：檢查定義、疊加、查表、輸出 schema、sources（Task 2、3、5）
  DatasourceService.java      previewMerged、createMerged、load 合併來源、層數；敏感設定繼承（Task 4、5）
  DatasourceController.java   POST /merged/preview、POST /merged（Task 6）
backend/src/test/java/jev/dashboard/
  TestData.java                               加 uploadCsv（Task 6）
  datasource/CsvSources.java                  新增：測試用，幾行 CSV 變成一份來源（Task 2）
  datasource/DatasourceRepositoryTest.java    新增（Task 1）
  datasource/MergeEngineTest.java             新增（Task 2、3、5）
  datasource/MergedDatasourceServiceTest.java 新增（Task 4、5）
  datasource/DatasourceControllerTest.java    加測試（Task 6）
  jev/JevControllerTest.java                  加測試（Task 6）
frontend/src/modules/dashboard/
  api.js                  previewMerged、createMerged（Task 7）
  MergeForm.vue           新增：合併分頁的表單（Task 7、8）
  MergeForm.test.js       新增（Task 7、8）
  DatasourcePanel.vue     合併分頁、清單圖示和標籤、說明列、繼承的敏感開關（Task 9）
  DatasourcePanel.test.js 加測試（Task 9）
  api.test.js             加測試（Task 7）
README.md、docs/backlog.md（Task 10）
docs/superpowers/e2e/merged-datasource/*.csv   手動驗證用的四份 CSV（Task 11）
```

---

### Task 1: 資料庫、合併定義型別、`Datasource.merge`、`FieldSchema.sensitiveFrom`

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__merged_datasource.sql`
- Create: `backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java`
- Modify: `backend/src/main/java/jev/dashboard/datasource/Datasource.java`（整個檔案換掉）
- Modify: `backend/src/main/java/jev/dashboard/datasource/FieldSchema.java`（整個檔案換掉）
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java`（`insert`、`map`，另外加 `writeMerge`）
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java`

**Interfaces:**
- Produces：
  - `MergeDefinition`（sealed interface，JSON 用 `op` 分辨）：
    - `MergeDefinition.Union(List<UUID> inputs, List<Union.Column> columns, String sourceColumn)`，`Union.Column(String name, Map<UUID, String> from)`
    - `MergeDefinition.Lookup(UUID main, UUID lookup, List<Lookup.Key> keys, List<Lookup.Bring> bring)`，`Lookup.Key(String main, String lookup)`，`Lookup.Bring(String name, String from)`
    - `static List<UUID> MergeDefinition.inputIds(MergeDefinition d)`：疊加回 `inputs`，查表回 `[main, lookup]`
  - `Datasource(..., OffsetDateTime createdAt, MergeDefinition merge)`（9 個參數）；保留原本 8 個參數的建構子（`merge = null`）；`withSchema` 會保留 `merge`
  - `FieldSchema(..., String label, String sensitiveFrom)`（9 個參數）；保留原本 7 個、8 個參數的建構子；`withSensitive`、`withLabel` 會保留 `sensitiveFrom`；新增 `withInheritedSensitive(String from)`（`sensitive = true`、`sensitiveFrom = from`）

- [ ] **Step 1: 準備 worktree**

```bash
ROOT=$(git rev-parse --show-toplevel)
git -C "$ROOT" branch --show-current
test -f "$ROOT/backend/.env" || cp /c/GitHub/jev-form-dashboard/backend/.env "$ROOT/backend/.env"
git -C "$ROOT" check-ignore -q backend/.env && echo ".env 在 gitignore 裡"
cd "$ROOT/frontend" && { [ -d node_modules ] || npm ci > /dev/null 2>&1; } && echo "node_modules ok"
```

Expected：第一行印出 `feat/merged-datasource`，接著是「.env 在 gitignore 裡」和「node_modules ok」。branch 不對就停下來回報。

- [ ] **Step 2: 跑一次既有測試，記下基準數量**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：`Failures: 0, Errors: 0` 和 `BUILD SUCCESS`。把 `Tests run:` 的數字記下來，之後每個 task 結束時的總數＝這個數字＋新加的測試數。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test 2>&1 | grep -E "Tests|Test Files" | tail -2`
Expected：全部通過，同樣記下數字。

- [ ] **Step 3: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.TestData;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DatasourceRepositoryTest {
    @Autowired DatasourceRepository repo;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    @Test
    void aMergedSourceKeepsItsDefinitionAndItsJsonCarriesOp() throws Exception {
        UUID north = UUID.randomUUID();
        UUID south = UUID.randomUUID();
        MergeDefinition def = new Union(List.of(north, south),
            List.of(new Union.Column("region", Map.of(north, "region", south, "area"))), "來源");
        Datasource merged = new Datasource(UUID.randomUUID(), "全區", "merged",
            List.of(new FieldSchema("region", FieldType.STRING, 2, List.of("北區"), false, null, null)),
            null, Map.of(), "u1", null, def);
        Datasource upload = new Datasource(UUID.randomUUID(), "北區", "upload", List.of(), null, Map.of(), "u1", null);
        repo.insert(merged);
        repo.insert(upload);

        Datasource found = repo.find(merged.id(), "u1").orElseThrow();
        assertThat(found.merge()).isEqualTo(def);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().merge()).isNull();
        assertThat(json.writeValueAsString(found)).contains("\"merge\":{\"op\":\"union\"").doesNotContain("sensitiveFrom");
        assertThat(json.writeValueAsString(repo.find(upload.id(), "u1").orElseThrow())).doesNotContain("\"merge\"");
    }

    @Test
    void theDatabaseRejectsAMergedSourceWithoutADefinition() {
        Datasource broken = new Datasource(UUID.randomUUID(), "壞的", "merged", List.of(), null, Map.of(), "u1", null);
        assertThatThrownBy(() -> repo.insert(broken)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 4: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=DatasourceRepositoryTest`
Expected：編譯失敗，`cannot find symbol` 指到 `MergeDefinition`。

- [ ] **Step 5: 寫 migration**

`backend/src/main/resources/db/migration/V3__merged_datasource.sql`：

```sql
-- spec 4.1：合併來源（子專案 B1）。datasource_kind_check 是 V1 行內 check 的預設名稱
alter table datasource add column merge_json jsonb;
alter table datasource drop constraint datasource_kind_check;
alter table datasource add constraint datasource_kind_check check (kind in ('upload', 'api', 'merged'));
alter table datasource add constraint datasource_merge_json_check check ((kind = 'merged') = (merge_json is not null));
```

- [ ] **Step 6: 新增 `MergeDefinition`**

`backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java`：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 合併來源的定義（spec 4.2、4.3），存在 datasource.merge_json；JSON 用 op 分辨疊加和查表。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MergeDefinition.Union.class, name = "union"),
    @JsonSubTypes.Type(value = MergeDefinition.Lookup.class, name = "lookup")})
public sealed interface MergeDefinition {
    /** inputs 的順序就是資料列的順序；sourceColumn 是標出每列來自哪個來源的欄位。 */
    record Union(List<UUID> inputs, List<Column> columns, String sourceColumn) implements MergeDefinition {
        /** from：輸入來源 id → 那個來源裡提供這個輸出欄位的欄位名稱。 */
        public record Column(String name, Map<UUID, String> from) {}
    }

    /** 主來源 left join 主檔：keys 全部相等才算對上，bring 是從主檔補上的欄位。 */
    record Lookup(UUID main, UUID lookup, List<Key> keys, List<Bring> bring) implements MergeDefinition {
        public record Key(String main, String lookup) {}

        public record Bring(String name, String from) {}
    }

    /** 直接用到的輸入來源，照定義裡的順序；定義要先驗證過。 */
    static List<UUID> inputIds(MergeDefinition d) {
        return switch (d) {
            case Union u -> u.inputs();
            case Lookup l -> List.of(l.main(), l.lookup());
        };
    }
}
```

- [ ] **Step 7: `Datasource` 加 `merge`**

把 `backend/src/main/java/jev/dashboard/datasource/Datasource.java` 整個換成：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** merge 只有合併來源（kind = merged）才有，其他來源是 null，JSON 不輸出。 */
public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MergeDefinition merge) {
    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, null);
    }

    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt, merge);
    }
}
```

- [ ] **Step 8: `FieldSchema` 加 `sensitiveFrom`**

把 `backend/src/main/java/jev/dashboard/datasource/FieldSchema.java` 整個換成：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * label 是使用者設定的顯示名稱，沒設定時是 null，畫面上直接顯示 name。
 * sensitiveFrom 只在讀取合併來源時填入，是讓這欄變敏感的來源名稱（spec 6.3）；不存進資料庫，null 時 JSON 不輸出。
 */
public record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                          boolean sensitive, String min, String max, String label,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String sensitiveFrom) {
    public FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                       boolean sensitive, String min, String max) {
        this(name, type, distinctCount, samples, sensitive, min, max, null, null);
    }

    public FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                       boolean sensitive, String min, String max, String label) {
        this(name, type, distinctCount, samples, sensitive, min, max, label, null);
    }

    public FieldSchema withSensitive(boolean value) {
        return new FieldSchema(name, type, distinctCount, samples, value, min, max, label, sensitiveFrom);
    }

    public FieldSchema withLabel(String value) {
        return new FieldSchema(name, type, distinctCount, samples, sensitive, min, max, value, sensitiveFrom);
    }

    /** 從輸入來源繼承來的敏感設定；from 是在哪個來源標成敏感的。 */
    public FieldSchema withInheritedSensitive(String from) {
        return new FieldSchema(name, type, distinctCount, samples, true, min, max, label, from);
    }
}
```

- [ ] **Step 9: repository 讀寫 `merge_json`**

`backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java`：

在 `ROW` 常數下面加一行：

```java
    private static final TypeReference<MergeDefinition> MERGE = new TypeReference<>() {};
```

`insert` 整個換成：

```java
    public void insert(Datasource d) {
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by, merge_json)
                values (:id, :name, :kind, cast(:schema as jsonb), :apiUrl, cast(:headers as jsonb), :createdBy,
                        cast(:merge as jsonb))""")
            .param("id", d.id())
            .param("name", d.name())
            .param("kind", d.kind())
            .param("schema", write(d.schema()))
            .param("apiUrl", d.apiUrl())
            .param("headers", write(d.apiHeaders() == null ? Map.of() : d.apiHeaders()))
            .param("createdBy", d.createdBy())
            // 一般來源要存 SQL 的 NULL，不能存 JSON 的 null，否則過不了 datasource_merge_json_check；
            // 指定 VARCHAR，null 才會跟其他 jsonb 參數一樣走 varchar → jsonb 的轉型
            .param("merge", d.merge() == null ? null : writeMerge(d.merge()), Types.VARCHAR)
            .update();
    }
```

檔案開頭的 import 加上 `import java.sql.Types;`。

`map` 裡最後一個參數後面加上 `merge`：

```java
    private Datasource map(ResultSet rs, int rowNum) throws SQLException {
        String merge = rs.getString("merge_json");
        return new Datasource(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("kind"),
            read(rs.getString("schema_json"), SCHEMA),
            rs.getString("api_url"),
            read(rs.getString("api_headers_json"), HEADERS),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            merge == null ? null : read(merge, MERGE));
    }
```

在 `write` 下面加：

```java
    /** 指定型別寫出，JSON 才一定帶 op。 */
    private String writeMerge(MergeDefinition merge) {
        try {
            return json.writerFor(MergeDefinition.class).writeValueAsString(merge);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
```

- [ ] **Step 10: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=DatasourceRepositoryTest`
Expected：PASS（2 個測試）。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋2，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__merged_datasource.sql backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java backend/src/main/java/jev/dashboard/datasource/Datasource.java backend/src/main/java/jev/dashboard/datasource/FieldSchema.java backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java && git commit -m "feat(backend): 資料來源多一種 merged，合併定義存在 merge_json" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/resources/db/migration/V3__merged_datasource.sql backend/src/main/java/jev/dashboard/datasource/MergeDefinition.java backend/src/main/java/jev/dashboard/datasource/Datasource.java backend/src/main/java/jev/dashboard/datasource/FieldSchema.java backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java backend/src/test/java/jev/dashboard/datasource/DatasourceRepositoryTest.java
```

---

### Task 2: `MergeEngine` 的疊加：檢查定義、合併資料列、輸出 schema

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/MergeEngine.java`
- Create: `backend/src/test/java/jev/dashboard/datasource/CsvSources.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java`

**Interfaces:**
- Consumes：Task 1 的 `MergeDefinition.Union`、`Datasource`、`FieldSchema`；既有的 `DatasourceService.Loaded(Datasource datasource, List<Map<String, Object>> rows)`、`SchemaInference.infer`、`ApiException`
- Produces：
  - `MergeEngine.Result(List<Output> columns, List<Map<String, Object>> rows, List<String> warnings)`
  - `MergeEngine.Output(String name, FieldType type, String label)`
  - `static void MergeEngine.validateUnion(Union u, Function<UUID, Datasource> input)`：不合法就丟 400 `ApiException`；`input` 取不到來源時，要由它自己丟 404
  - `static Result MergeEngine.union(Union u, List<Loaded> inputs)`：`inputs` 的順序要跟 `u.inputs()` 一樣
  - `static List<FieldSchema> MergeEngine.schema(Result r)`：建立合併來源時存進資料庫的 schema，敏感設定一律是 `false`
  - `static FieldSchema MergeEngine.field(Datasource ds, String name)`（package-private）：找不到就丟「{來源名}沒有 {欄位} 這個欄位」
  - 測試 helper `CsvSources.csv(String name, String... lines)` → `Loaded`：第一行是表頭，schema 用 `SchemaInference.infer` 推，資料列經過 `normalize`，擁有者是 `u1`

- [ ] **Step 1: 寫測試 helper**

`backend/src/test/java/jev/dashboard/datasource/CsvSources.java`：

```java
package jev.dashboard.datasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 測試用：幾行 CSV（第一行是表頭）變成一份 u1 的上傳來源，schema 推斷和正規化都跟真的上傳一樣。 */
final class CsvSources {
    private CsvSources() {}

    static DatasourceService.Loaded csv(String name, String... lines) {
        List<String> headers = List.of(lines[0].split(","));
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), cells[j]);
            raw.add(row);
        }
        List<FieldSchema> schema = SchemaInference.infer(headers, raw);
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), "u1", null);
        return new DatasourceService.Loaded(ds, SchemaInference.normalize(raw, schema));
    }
}
```

- [ ] **Step 2: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java`：

```java
package jev.dashboard.datasource;

import static jev.dashboard.datasource.CsvSources.csv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Union;
import jev.dashboard.datasource.MergeEngine.Output;
import jev.dashboard.datasource.MergeEngine.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MergeEngineTest {
    final Loaded north = csv("北區", "region,amount", "北區,100", "北區,200");
    final Loaded south = csv("南區", "area,amount,reason", "南區,50,瑕疵");

    /** 疊加的 from：依序是「來源、欄位」一對一對。 */
    static Map<UUID, String> from(Object... pairs) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(((Loaded) pairs[i]).datasource().id(), (String) pairs[i + 1]);
        return m;
    }

    static List<UUID> ids(Loaded... sources) {
        return Stream.of(sources).map(l -> l.datasource().id()).toList();
    }

    /** 驗證時取輸入來源：只認得這幾個，其他的回 404。 */
    static Function<UUID, Datasource> only(Loaded... sources) {
        return id -> Stream.of(sources).map(Loaded::datasource).filter(d -> d.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到資料源"));
    }

    static Loaded labeled(Loaded l, String field, String label) {
        return new Loaded(l.datasource().withSchema(l.datasource().schema().stream()
            .map(f -> f.name().equals(field) ? f.withLabel(label) : f).toList()), l.rows());
    }

    Union regionAmountReason() {
        return new Union(ids(north, south), List.of(
            new Union.Column("region", from(north, "region", south, "area")),
            new Union.Column("amount", from(north, "amount", south, "amount")),
            new Union.Column("reason", from(south, "reason"))), "來源");
    }

    void unionIsRejected(Union u, String message) {
        assertThatThrownBy(() -> MergeEngine.validateUnion(u, only(north, south)))
            .isInstanceOf(ApiException.class).hasMessage(message);
    }

    @Test
    void unionRenamesColumnsLeavesUnmappedOnesOutAndTagsEachRowWithItsSource() {
        Result r = MergeEngine.union(regionAmountReason(), List.of(labeled(north, "region", "地區"), south));

        assertThat(r.rows()).containsExactly(
            Map.of("region", "北區", "amount", 100.0, "來源", "北區"),
            Map.of("region", "北區", "amount", 200.0, "來源", "北區"),
            Map.of("region", "南區", "amount", 50.0, "reason", "瑕疵", "來源", "南區"));
        assertThat(r.rows().get(2).keySet()).containsExactly("region", "amount", "reason", "來源");
        assertThat(r.columns()).containsExactly(
            new Output("region", FieldType.STRING, "地區"),
            new Output("amount", FieldType.NUMBER, null),
            new Output("reason", FieldType.STRING, null),
            new Output("來源", FieldType.STRING, null));
        assertThat(r.warnings()).containsExactly("「reason」只有南區有，其他來源的列這欄是空的");
    }

    @Test
    void unionDefinitionsAreCheckedAgainstTheInputs() {
        List<Union.Column> ok = regionAmountReason().columns();
        UUID n = north.datasource().id();
        unionIsRejected(new Union(List.of(n), ok, "來源"), "疊加要選 2 到 5 個來源");
        unionIsRejected(new Union(Collections.nCopies(6, n), ok, "來源"), "疊加要選 2 到 5 個來源");
        unionIsRejected(new Union(List.of(n, n), ok, "來源"), "疊加的來源不能重複");
        unionIsRejected(new Union(ids(north, south), List.of(), "來源"), "至少要有一個輸出欄位");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column(" ", from(north, "region"))), "來源"),
            "欄位名稱不能空白");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("欄".repeat(51), from(north, "region"))), "來源"),
            "欄位名稱最多 50 個字");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", from(north, "region")),
            new Union.Column("region", from(south, "area"))), "來源"), "欄位名稱「region」重複");
        unionIsRejected(new Union(ids(north, south), ok, "region"), "欄位名稱「region」重複");
        unionIsRejected(new Union(ids(north, south), ok, ""), "來源欄名稱不能空白");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", Map.of())), "來源"),
            "欄位「region」至少要對應一個來源");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", Map.of(UUID.randomUUID(), "region"))), "來源"),
            "欄位「region」對應到不在疊加裡的來源");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", from(north, "region", south, "region"))), "來源"),
            "南區沒有 region 這個欄位");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("amount", from(north, "amount", south, "reason"))), "來源"),
            "「amount」在北區是數字、在南區是文字，不能併成同一欄");
        assertThatThrownBy(() -> MergeEngine.validateUnion(new Union(List.of(n, UUID.randomUUID()), ok, "來源"), only(north, south)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        MergeEngine.validateUnion(regionAmountReason(), only(north, south));
    }

    @Test
    void schemaUsesTheInputTypesCountsTheMergedRowsAndDropsInheritedLabelsThatWouldClash() {
        Result r = new Result(List.of(
                new Output("region", FieldType.STRING, "地區"),
                new Output("area", FieldType.STRING, "地區"),
                new Output("amount", FieldType.NUMBER, "region"),
                new Output("code", FieldType.STRING, null),
                new Output("day", FieldType.DATE, null)),
            List.of(Map.of("region", "北區", "amount", 1.0, "code", "1", "day", "2026-07-01"),
                Map.of("region", "南區", "code", "2", "day", "2026-08-01")),
            List.of());

        List<FieldSchema> schema = MergeEngine.schema(r);

        assertThat(schema).extracting(FieldSchema::name).containsExactly("region", "area", "amount", "code", "day");
        assertThat(schema).extracting(FieldSchema::label).containsExactly("地區", null, null, null, null);
        // code 的值看起來像數字，型別還是照輸入欄位的文字
        assertThat(schema).extracting(FieldSchema::type)
            .containsExactly(FieldType.STRING, FieldType.STRING, FieldType.NUMBER, FieldType.STRING, FieldType.DATE);
        assertThat(schema.get(0).distinctCount()).isEqualTo(2);
        assertThat(schema.get(4).min()).isEqualTo("2026-07-01");
        assertThat(schema.get(4).max()).isEqualTo("2026-08-01");
        assertThat(schema).noneMatch(FieldSchema::sensitive);
    }
}
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=MergeEngineTest`
Expected：編譯失敗，`cannot find symbol` 指到 `MergeEngine`。

- [ ] **Step 4: 寫 `MergeEngine`（疊加部分）**

`backend/src/main/java/jev/dashboard/datasource/MergeEngine.java`：

```java
package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Union;

/** 合併來源的規則（spec 第 5、6、9 節）：檢查定義、合併資料列、算出輸出欄位。全是純函式，不碰資料庫。 */
public final class MergeEngine {
    static final int MAX_UNION_INPUTS = 5;
    static final int MAX_NAME_LENGTH = 50;
    static final Map<FieldType, String> TYPE_ZH =
        Map.of(FieldType.NUMBER, "數字", FieldType.DATE, "日期", FieldType.STRING, "文字");

    /** columns 照輸出順序排；warnings 是預覽要顯示的提示。 */
    public record Result(List<Output> columns, List<Map<String, Object>> rows, List<String> warnings) {}

    /** 輸出欄位的名稱、型別（照輸入欄位）和繼承來的顯示名稱。 */
    public record Output(String name, FieldType type, String label) {}

    private MergeEngine() {}

    /** spec 第 9 節。input 依 id 取輸入來源，找不到或不是這位使用者的，由 input 自己丟 404。 */
    public static void validateUnion(Union u, Function<UUID, Datasource> input) {
        List<UUID> ids = u.inputs() == null ? List.of() : u.inputs();
        if (ids.size() < 2 || ids.size() > MAX_UNION_INPUTS) throw bad("疊加要選 2 到 5 個來源");
        // List.of 的 contains(null) 會丟 NPE，用 anyMatch 檢查
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw bad("疊加的來源不能重複");
        Map<UUID, Datasource> byId = new LinkedHashMap<>();
        for (UUID id : ids) byId.put(id, input.apply(id));
        if (u.columns() == null || u.columns().isEmpty()) throw bad("至少要有一個輸出欄位");
        Set<String> taken = new HashSet<>();
        for (Union.Column c : u.columns()) {
            requireName(c == null ? null : c.name(), taken, "欄位名稱");
            if (c.from() == null || c.from().isEmpty()) throw bad("欄位「" + c.name() + "」至少要對應一個來源");
            FieldSchema first = null;
            Datasource firstSource = null;
            for (Map.Entry<UUID, String> e : c.from().entrySet()) {
                Datasource ds = byId.get(e.getKey());
                if (ds == null) throw bad("欄位「" + c.name() + "」對應到不在疊加裡的來源");
                FieldSchema f = field(ds, e.getValue());
                if (first == null) {
                    first = f;
                    firstSource = ds;
                } else if (f.type() != first.type()) {
                    throw bad("「" + c.name() + "」在" + firstSource.name() + "是" + TYPE_ZH.get(first.type())
                        + "、在" + ds.name() + "是" + TYPE_ZH.get(f.type()) + "，不能併成同一欄");
                }
            }
        }
        requireName(u.sourceColumn(), taken, "來源欄名稱");
    }

    /** spec 5.1：照 inputs 的順序接起來；沒對應或值是空的就不放 key，最後一欄標出來源名稱。 */
    public static Result union(Union u, List<Loaded> inputs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Loaded in : inputs) {
            UUID id = in.datasource().id();
            for (Map<String, Object> row : in.rows()) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Union.Column c : u.columns()) {
                    String from = c.from().get(id);
                    Object v = from == null ? null : row.get(from);
                    if (v != null) out.put(c.name(), v);
                }
                out.put(u.sourceColumn(), in.datasource().name());
                rows.add(out);
            }
        }
        List<Output> columns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Union.Column c : u.columns()) {
            FieldType type = null;
            String label = null;
            List<String> has = new ArrayList<>();
            for (Loaded in : inputs) {
                String from = c.from().get(in.datasource().id());
                if (from == null) continue;
                has.add(in.datasource().name());
                FieldSchema f = find(in.datasource(), from);
                if (f == null) continue; // 外部 API 輸入後來少了這欄（spec 第 9 節）：值是空的
                if (type == null) type = f.type();
                if (label == null) label = f.label();
            }
            columns.add(new Output(c.name(), type == null ? FieldType.STRING : type, label));
            if (has.size() < inputs.size()) {
                warnings.add("「" + c.name() + "」只有" + String.join("、", has) + "有，其他來源的列這欄是空的");
            }
        }
        columns.add(new Output(u.sourceColumn(), FieldType.STRING, null));
        return new Result(columns, rows, warnings);
    }

    /**
     * spec 6.1、6.2：型別照輸入欄位，相異值、樣本、日期範圍從合併後的資料列算。
     * 繼承的顯示名稱跟任何一個欄位名稱、或前面欄位的顯示名稱重複，就不繼承。
     */
    public static List<FieldSchema> schema(Result r) {
        List<String> names = r.columns().stream().map(Output::name).toList();
        List<FieldSchema> inferred = SchemaInference.infer(names, r.rows());
        Set<String> used = new HashSet<>(names);
        List<FieldSchema> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Output o = r.columns().get(i);
            FieldSchema f = inferred.get(i);
            String label = o.label() != null && used.add(o.label()) ? o.label() : null;
            boolean date = o.type() == FieldType.DATE;
            out.add(new FieldSchema(o.name(), o.type(), f.distinctCount(), f.samples(), false,
                date ? f.min() : null, date ? f.max() : null, label));
        }
        return out;
    }

    static FieldSchema field(Datasource ds, String name) {
        FieldSchema f = find(ds, name);
        if (f == null) throw bad(ds.name() + "沒有 " + name + " 這個欄位");
        return f;
    }

    private static FieldSchema find(Datasource ds, String name) {
        return ds.schema().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    private static void requireName(String name, Set<String> taken, String what) {
        if (name == null || name.isBlank()) throw bad(what + "不能空白");
        if (name.length() > MAX_NAME_LENGTH) throw bad(what + "最多 50 個字");
        if (!taken.add(name)) throw bad("欄位名稱「" + name + "」重複");
    }

    private static ApiException bad(String message) {
        return new ApiException(BAD_REQUEST, message);
    }
}
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=MergeEngineTest`
Expected：PASS（3 個測試）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/test/java/jev/dashboard/datasource/CsvSources.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java && git commit -m "feat(backend): MergeEngine 的疊加：檢查定義、合併資料列、輸出 schema" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/test/java/jev/dashboard/datasource/CsvSources.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java
```

---

### Task 3: `MergeEngine` 的查表，以及依 `op` 分派的 `validate`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/MergeEngine.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java`（加測試、import）

**Interfaces:**
- Consumes：Task 2 的 `Result`、`Output`、`validateUnion`、`field`、`requireName`、`bad`、`TYPE_ZH`；Task 1 的 `MergeDefinition.Lookup`
- Produces：
  - `static void MergeEngine.validate(MergeDefinition def, Function<UUID, Datasource> input)`：`def` 是 null 時丟「請選擇合併方式」，其餘依型別分派到 `validateUnion`／`validateLookup`
  - `static void MergeEngine.validateLookup(Lookup l, Function<UUID, Datasource> input)`
  - `static Result MergeEngine.lookup(Lookup l, Loaded main, Loaded lookup)`
  - `static String MergeEngine.key(Map<String, Object> row, List<Lookup.Key> keys, Function<Lookup.Key, String> side)`（package-private）：任何一個對接欄位是空值就回 null

- [ ] **Step 1: 寫會失敗的測試**

`MergeEngineTest.java` 加 import：

```java
import java.util.HashMap;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Lookup.Bring;
import jev.dashboard.datasource.MergeDefinition.Lookup.Key;
```

在 class 裡 `south` 下面加欄位：

```java
    final Loaded returns = csv("退貨", "product,qty,owner", "A,1,甲", "B,2,乙", "Z,3,丙", ",4,丁");
    final Loaded products = csv("產品主檔", "code,category,owner", "A,家電,王小明", "B,文具,李小華", "A,重複,張三");
```

在 class 最後加：

```java
    Lookup productLookup() {
        return new Lookup(returns.datasource().id(), products.datasource().id(), List.of(new Key("product", "code")),
            List.of(new Bring("category", "category"), new Bring("產品主檔·owner", "owner")));
    }

    void lookupIsRejected(Lookup l, String message) {
        assertThatThrownBy(() -> MergeEngine.validate(l, only(returns, products)))
            .isInstanceOf(ApiException.class).hasMessage(message);
    }

    @Test
    void lookupKeepsEveryMainRowBringsColumnsFromTheFirstMatchAndCountsProblems() {
        Result r = MergeEngine.lookup(productLookup(), returns, labeled(products, "category", "類別"));

        assertThat(r.rows()).hasSize(4);
        assertThat(r.rows().get(0))
            .isEqualTo(Map.of("product", "A", "qty", 1.0, "owner", "甲", "category", "家電", "產品主檔·owner", "王小明"));
        assertThat(r.rows().get(1)).containsEntry("category", "文具");
        assertThat(r.rows().get(2)).containsEntry("product", "Z").doesNotContainKeys("category", "產品主檔·owner");
        assertThat(r.rows().get(3)).containsEntry("qty", 4.0).doesNotContainKey("category");
        assertThat(r.columns()).extracting(Output::name)
            .containsExactly("product", "qty", "owner", "category", "產品主檔·owner");
        assertThat(r.columns().get(3)).isEqualTo(new Output("category", FieldType.STRING, "類別"));
        assertThat(r.warnings()).containsExactly("主檔有 1 個對接值重複，用排最前面的那一列", "2 筆對不到主檔，補上的欄位是空的");
    }

    @Test
    void keysIgnoreTrailingZerosNeedEveryPartAndDoNotRunTogether() {
        List<Key> one = List.of(new Key("a", "b"));
        assertThat(MergeEngine.key(Map.of("a", 1.0), one, Key::main)).isEqualTo(MergeEngine.key(Map.of("b", 1), one, Key::lookup));
        List<Key> two = List.of(new Key("a", "a"), new Key("c", "c"));
        Map<String, Object> half = new HashMap<>();
        half.put("a", "x");
        half.put("c", null);
        assertThat(MergeEngine.key(half, two, Key::main)).isNull();
        assertThat(MergeEngine.key(Map.of("a", "x", "c", "y"), two, Key::main))
            .isNotEqualTo(MergeEngine.key(Map.of("a", "xy", "c", ""), two, Key::main));
    }

    @Test
    void lookupDefinitionsAreCheckedAgainstTheInputs() {
        UUID r = returns.datasource().id();
        UUID p = products.datasource().id();
        List<Key> keys = productLookup().keys();
        List<Bring> bring = productLookup().bring();
        lookupIsRejected(new Lookup(null, p, keys, bring), "查表要選主來源和主檔");
        lookupIsRejected(new Lookup(r, r, keys, bring), "主來源和主檔不能是同一個");
        lookupIsRejected(new Lookup(r, p, List.of(), bring), "對接欄位要 1 到 3 組");
        lookupIsRejected(new Lookup(r, p, Collections.nCopies(4, keys.get(0)), bring), "對接欄位要 1 到 3 組");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("sku", "code")), bring), "退貨沒有 sku 這個欄位");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("product", "sku")), bring), "產品主檔沒有 sku 這個欄位");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("qty", "code")), bring), "對接欄位「qty」是數字、「code」是文字，型別要相同");
        lookupIsRejected(new Lookup(r, p, keys, List.of()), "至少要補上一個欄位");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("owner", "owner"))), "欄位名稱「owner」跟主來源的欄位重複");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("category", "category"), new Bring("category", "owner"))),
            "欄位名稱「category」重複");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring(" ", "category"))), "欄位名稱不能空白");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("price", "price"))), "產品主檔沒有 price 這個欄位");

        MergeEngine.validate(productLookup(), only(returns, products));
    }

    @Test
    void validateNeedsADefinitionAndDispatchesOnOp() {
        assertThatThrownBy(() -> MergeEngine.validate(null, only())).isInstanceOf(ApiException.class).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> MergeEngine.validate(new Union(List.of(), List.of(), "來源"), only()))
            .hasMessage("疊加要選 2 到 5 個來源");
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=MergeEngineTest`
Expected：編譯失敗，`cannot find symbol` 指到 `MergeEngine.lookup`、`MergeEngine.key`、`MergeEngine.validate`。

- [ ] **Step 3: 寫查表和分派**

`MergeEngine.java` 加 import：

```java
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.stream.Collectors;
import jev.dashboard.datasource.MergeDefinition.Lookup;
```

`MAX_NAME_LENGTH` 下面加：

```java
    static final int MAX_KEYS = 3;
```

在 `validateUnion` 前面加：

```java
    /** 依 op 分派；def 是 null 代表 request 沒帶 merge。 */
    public static void validate(MergeDefinition def, Function<UUID, Datasource> input) {
        switch (def) {
            case null -> throw bad("請選擇合併方式");
            case Union u -> validateUnion(u, input);
            case Lookup l -> validateLookup(l, input);
        }
    }
```

在 `validateUnion` 後面加：

```java
    public static void validateLookup(Lookup l, Function<UUID, Datasource> input) {
        if (l.main() == null || l.lookup() == null) throw bad("查表要選主來源和主檔");
        if (l.main().equals(l.lookup())) throw bad("主來源和主檔不能是同一個");
        Datasource main = input.apply(l.main());
        Datasource lookup = input.apply(l.lookup());
        if (l.keys() == null || l.keys().isEmpty() || l.keys().size() > MAX_KEYS || l.keys().stream().anyMatch(Objects::isNull)) {
            throw bad("對接欄位要 1 到 3 組");
        }
        for (Lookup.Key k : l.keys()) {
            FieldSchema a = field(main, k.main());
            FieldSchema b = field(lookup, k.lookup());
            if (a.type() != b.type()) {
                throw bad("對接欄位「" + a.name() + "」是" + TYPE_ZH.get(a.type()) + "、「" + b.name() + "」是"
                    + TYPE_ZH.get(b.type()) + "，型別要相同");
            }
        }
        if (l.bring() == null || l.bring().isEmpty()) throw bad("至少要補上一個欄位");
        Set<String> mainNames = main.schema().stream().map(FieldSchema::name).collect(Collectors.toSet());
        Set<String> taken = new HashSet<>();
        for (Lookup.Bring b : l.bring()) {
            String name = b == null ? null : b.name();
            if (name != null && mainNames.contains(name)) throw bad("欄位名稱「" + name + "」跟主來源的欄位重複");
            requireName(name, taken, "欄位名稱");
            field(lookup, b.from());
        }
    }
```

在 `union` 後面加：

```java
    /** spec 5.2：left join，主來源一列不增也不減；主檔的對接值重複時用排最前面的那一列。 */
    public static Result lookup(Lookup l, Loaded main, Loaded lookup) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (Map<String, Object> row : lookup.rows()) {
            String k = key(row, l.keys(), Lookup.Key::lookup);
            if (k != null && index.putIfAbsent(k, row) != null) duplicated.add(k);
        }
        List<Map<String, Object>> rows = new ArrayList<>(main.rows().size());
        int unmatched = 0;
        for (Map<String, Object> row : main.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            String k = key(row, l.keys(), Lookup.Key::main);
            Map<String, Object> hit = k == null ? null : index.get(k);
            if (hit == null) {
                unmatched++;
            } else {
                for (Lookup.Bring b : l.bring()) {
                    Object v = hit.get(b.from());
                    if (v != null) out.put(b.name(), v);
                }
            }
            rows.add(out);
        }
        List<Output> columns = new ArrayList<>();
        for (FieldSchema f : main.datasource().schema()) columns.add(new Output(f.name(), f.type(), f.label()));
        for (Lookup.Bring b : l.bring()) {
            FieldSchema f = find(lookup.datasource(), b.from());
            columns.add(new Output(b.name(), f == null ? FieldType.STRING : f.type(), f == null ? null : f.label()));
        }
        List<String> warnings = new ArrayList<>();
        if (!duplicated.isEmpty()) warnings.add("主檔有 " + duplicated.size() + " 個對接值重複，用排最前面的那一列");
        if (unmatched > 0) warnings.add(unmatched + " 筆對不到主檔，補上的欄位是空的");
        return new Result(columns, rows, warnings);
    }

    /** 所有對接欄位都有值才算得出 key；數字去掉多餘的小數零，1 和 1.0 算同一個值。 */
    static String key(Map<String, Object> row, List<Lookup.Key> keys, Function<Lookup.Key, String> side) {
        StringBuilder sb = new StringBuilder();
        for (Lookup.Key k : keys) {
            Object v = row.get(side.apply(k));
            if (v == null) return null;
            sb.append(v instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros().toPlainString() : v)
                .append('\u0000');
        }
        return sb.toString();
    }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=MergeEngineTest`
Expected：PASS（7 個測試）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java && git commit -m "feat(backend): MergeEngine 的查表與依 op 分派的定義檢查" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java
```

---

### Task 4: `DatasourceService` 建立、預覽、載入合併來源

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceService.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java`

**Interfaces:**
- Consumes：Task 3 的 `MergeEngine.validate`、`union`、`lookup`、`schema`、`Result`；Task 1 的 `MergeDefinition.inputIds`、9 個參數的 `Datasource`
- Produces：
  - `DatasourceService.MergePreview(List<FieldSchema> schema, List<Map<String, Object>> rows, int rowCount, List<String> warnings)`
  - `MergePreview DatasourceService.previewMerged(String userId, MergeDefinition def)`
  - `Datasource DatasourceService.createMerged(String userId, String name, MergeDefinition def)`
  - `DatasourceService.load` 支援 `kind = merged`
  - `private Datasource stored(String userId, UUID id)`：資料庫裡存的樣子，找不到丟 404。Task 5 會讓 `get` 改成在它外面再算一層繼承
  - 常數 `MAX_DEPTH = 3`、`PREVIEW_ROWS = 20`

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.DatasourceService.MergePreview;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.junit.jupiter.api.Test;

class MergedDatasourceServiceTest {
    /** 記憶體裡的 repository：只做 DatasourceService 會用到的方法，不連資料庫。 */
    static class MemoryRepo extends DatasourceRepository {
        final Map<UUID, Datasource> sources = new LinkedHashMap<>();
        final Map<UUID, List<Map<String, Object>>> rows = new HashMap<>();

        MemoryRepo() {
            super(null, null, null);
        }

        @Override
        public void insert(Datasource d) {
            sources.put(d.id(), d);
        }

        @Override
        public Optional<Datasource> find(UUID id, String userId) {
            return Optional.ofNullable(sources.get(id)).filter(d -> d.createdBy().equals(userId));
        }

        @Override
        public List<Datasource> list(String userId) {
            return sources.values().stream().filter(d -> d.createdBy().equals(userId)).toList();
        }

        @Override
        public void updateSchema(UUID id, List<FieldSchema> schema) {
            sources.put(id, sources.get(id).withSchema(schema));
        }

        @Override
        public void insertRows(UUID id, List<Map<String, Object>> value) {
            rows.put(id, value);
        }

        @Override
        public List<Map<String, Object>> rows(UUID id) {
            return rows.getOrDefault(id, List.of());
        }
    }

    final MemoryRepo repo = new MemoryRepo();

    DatasourceService service(int maxRows) {
        return new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
            new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null), new ApiUrlGuard(List.of()));
    }

    UUID upload(String name, String... lines) {
        return uploadFor("u1", name, lines);
    }

    UUID uploadFor(String owner, String name, String... lines) {
        Loaded l = CsvSources.csv(name, lines);
        Datasource ds = new Datasource(l.datasource().id(), name, "upload", l.datasource().schema(), null, Map.of(), owner, null);
        repo.insert(ds);
        repo.insertRows(ds.id(), l.rows());
        return ds.id();
    }

    /** 同名欄位直接對起來的疊加，來源欄叫「來源」。 */
    static Union union(List<UUID> inputs, String... fields) {
        return new Union(inputs, Stream.of(fields).map(f -> new Union.Column(f,
            inputs.stream().collect(Collectors.toMap(id -> id, id -> f, (a, b) -> a, LinkedHashMap::new)))).toList(), "來源");
    }

    /** 表頭加上 count 列資料，第 i 列（從 1 開始）是 row(i)。 */
    static String[] lines(String header, int count, IntFunction<String> row) {
        return Stream.concat(Stream.of(header), IntStream.rangeClosed(1, count).mapToObj(row)).toArray(String[]::new);
    }

    static FieldSchema field(Datasource ds, String name) {
        return ds.schema().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void createStoresOnlyTheDefinitionAndEveryLoadMergesAgain() {
        DatasourceService service = service(20);
        UUID n = upload("北區", "region,amount", "北區,100", "北區,200");
        UUID s = upload("南區", "region,amount", "南區,50");

        Datasource m = service.createMerged("u1", " 全區 ", union(List.of(n, s), "region", "amount"));

        assertThat(m.kind()).isEqualTo("merged");
        assertThat(m.name()).isEqualTo("全區");
        assertThat(m.merge()).isEqualTo(union(List.of(n, s), "region", "amount"));
        assertThat(m.schema()).extracting(FieldSchema::name).containsExactly("region", "amount", "來源");
        assertThat(repo.rows).doesNotContainKey(m.id());
        assertThat(service.load("u1", m.id()).rows()).extracting(r -> r.get("amount")).containsExactly(100.0, 200.0, 50.0);

        repo.insertRows(s, List.of(Map.of("region", "南區", "amount", 60.0), Map.of("region", "南區", "amount", 70.0)));
        assertThat(service.load("u1", m.id()).rows()).extracting(r -> r.get("amount"))
            .containsExactly(100.0, 200.0, 60.0, 70.0);
    }

    @Test
    void previewReturnsTheFirst20RowsTheCountAndTheWarningsWithoutSaving() {
        DatasourceService service = service(100);
        UUID n = upload("北區", lines("amount", 15, i -> String.valueOf(i)));
        UUID s = upload("南區", lines("amount,reason", 15, i -> i + ",瑕疵"));
        Union u = new Union(List.of(n, s), List.of(
            new Union.Column("amount", Map.of(n, "amount", s, "amount")),
            new Union.Column("reason", Map.of(s, "reason"))), "來源");

        MergePreview p = service.previewMerged("u1", u);

        assertThat(p.rowCount()).isEqualTo(30);
        assertThat(p.rows()).hasSize(20);
        assertThat(p.warnings()).containsExactly("「reason」只有南區有，其他來源的列這欄是空的");
        assertThat(p.schema()).extracting(FieldSchema::name).containsExactly("amount", "reason", "來源");
        assertThat(repo.sources).hasSize(2);
    }

    @Test
    void theNameTheDefinitionTheRowCountAndTheOwnerAreChecked() {
        DatasourceService service = service(3);
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID c = upload("c", "v", "3");
        UUID d = upload("d", "v", "4");
        UUID empty1 = upload("空1", "v");
        UUID empty2 = upload("空2", "v");
        UUID others = uploadFor("u2", "別人的", "v", "9");

        assertThatThrownBy(() -> service.createMerged("u1", " ", union(List.of(a, b), "v"))).hasMessage("請輸入名稱");
        assertThatThrownBy(() -> service.createMerged("u1", "x", null)).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> service.previewMerged("u1", null)).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(a, b, c, d), "v")))
            .hasMessage("資料有 4 筆，超過上限 3 筆");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(empty1, empty2), "v"))).hasMessage("沒有資料列");
        assertThatThrownBy(() -> service.createMerged("u1", "偷看", union(List.of(a, others), "v")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(NOT_FOUND))
            .hasMessage("找不到資料源");
        assertThat(repo.sources).hasSize(7);
    }

    @Test
    void mergesNestUpToThreeLevels() {
        DatasourceService service = service(100);
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID c = upload("c", "v", "3");
        UUID d = upload("d", "v", "4");
        UUID e = upload("e", "v", "5");
        UUID m1 = service.createMerged("u1", "m1", union(List.of(a, b), "v")).id();
        UUID m2 = service.createMerged("u1", "m2", union(List.of(m1, c), "v")).id();
        UUID m3 = service.createMerged("u1", "m3", union(List.of(m2, d), "v")).id();

        List<Map<String, Object>> rows = service.load("u1", m3).rows();
        assertThat(rows).extracting(r -> r.get("v")).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(rows).extracting(r -> r.get("來源")).containsExactly("m2", "m2", "m2", "d");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(m3, e), "v"))).hasMessage("合併最多 3 層");
    }

    @Test
    void loadingOnlyStopsAtTheUpperLimit() {
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID m = service(100).createMerged("u1", "m", union(List.of(a, b), "v")).id();

        assertThatThrownBy(() -> service(1).load("u1", m)).hasMessage("資料有 2 筆，超過上限 1 筆");
        repo.insertRows(a, List.of());
        repo.insertRows(b, List.of());
        assertThat(service(1).load("u1", m).rows()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest=MergedDatasourceServiceTest`
Expected：編譯失敗，`cannot find symbol` 指到 `MergePreview`、`createMerged`、`previewMerged`。

- [ ] **Step 3: 改 `DatasourceService`**

`DatasourceService.java` 加 import：

```java
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Union;
```

`Loaded` 那行下面加：

```java
    /** spec 第 7 節：預覽的回傳，rows 只放前 20 列。 */
    public record MergePreview(List<FieldSchema> schema, List<Map<String, Object>> rows, int rowCount,
                               List<String> warnings) {}

    static final int MAX_DEPTH = 3;
    static final int PREVIEW_ROWS = 20;
```

`get` 換成下面兩個方法：

```java
    public Datasource get(String userId, UUID id) {
        return stored(userId, id);
    }

    /** 資料庫裡存的樣子；改 schema 一律從這裡拿。 */
    private Datasource stored(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(() -> new ApiException(NOT_FOUND, "找不到資料源"));
    }
```

`load` 整個換成：

```java
    public Loaded load(String userId, UUID id) {
        Datasource ds = get(userId, id);
        if ("merged".equals(ds.kind())) {
            List<Map<String, Object>> rows = merge(userId, ds.merge()).rows();
            // spec 5.4：載入時只擋超過上限；0 筆跟其他來源一樣照常回傳
            if (rows.size() > maxRows) checkRows(rows.size());
            return new Loaded(ds, rows);
        }
        List<Map<String, Object>> raw = provider(ds.kind()).fetchRows(ds);
        return new Loaded(ds, "upload".equals(ds.kind()) ? raw : SchemaInference.normalize(raw, ds.schema()));
    }

    /** 不存檔。預覽只看定義，body 裡的名稱用不到。 */
    public MergePreview previewMerged(String userId, MergeDefinition def) {
        MergeEngine.Result r = build(userId, def);
        return new MergePreview(MergeEngine.schema(r), r.rows().subList(0, Math.min(PREVIEW_ROWS, r.rows().size())),
            r.rows().size(), r.warnings());
    }

    /** 建立時照同一套規則再驗證、再合併一次，因為輸入來源可能在預覽之後變了（spec 第 7 節）。 */
    public Datasource createMerged(String userId, String name, MergeDefinition def) {
        if (name == null || name.isBlank()) throw new ApiException(BAD_REQUEST, "請輸入名稱");
        MergeEngine.Result r = build(userId, def);
        Datasource ds = new Datasource(UUID.randomUUID(), name.strip(), "merged", MergeEngine.schema(r), null, Map.of(),
            userId, null, def);
        repo.insert(ds);
        return get(userId, ds.id());
    }

    /** 檢查定義和層數、合併、檢查筆數；預覽和建立共用。 */
    private MergeEngine.Result build(String userId, MergeDefinition def) {
        MergeEngine.validate(def, id -> get(userId, id));
        int depth = 1 + MergeDefinition.inputIds(def).stream().mapToInt(id -> depth(userId, id)).max().orElse(0);
        if (depth > MAX_DEPTH) throw new ApiException(BAD_REQUEST, "合併最多 3 層");
        MergeEngine.Result r = merge(userId, def);
        checkRows(r.rows().size());
        return r;
    }

    /** spec 5.3：上傳和外部 API 是第 0 層，合併來源是輸入來源最大層數加 1。 */
    private int depth(String userId, UUID id) {
        Datasource ds = stored(userId, id);
        if (!"merged".equals(ds.kind())) return 0;
        return 1 + MergeDefinition.inputIds(ds.merge()).stream().mapToInt(i -> depth(userId, i)).max().orElse(0);
    }

    // ponytail: 同一個輸入來源在巢狀結構裡出現兩次，就會載入兩次（spec 5.3）；巢狀合併常見、載入變慢時，再在同一次 load 裡加快取
    private MergeEngine.Result merge(String userId, MergeDefinition def) {
        return switch (def) {
            case Union u -> MergeEngine.union(u, u.inputs().stream().map(id -> load(userId, id)).toList());
            case Lookup l -> MergeEngine.lookup(l, load(userId, l.main()), load(userId, l.lookup()));
        };
    }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='MergedDatasourceServiceTest,DatasourceServiceTest'`
Expected：PASS（5＋1 個測試）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/jev/dashboard/datasource/DatasourceService.java backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java && git commit -m "feat(backend): 建立、預覽、載入合併來源，限制層數與筆數" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/datasource/DatasourceService.java backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java
```

---

### Task 5: 敏感設定從輸入來源繼承；欄位設定改用存下來的 schema

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/MergeEngine.java`（加 `sources`）
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceService.java`（`list`、`get`、`markSensitive`、`setLabels`、`previewMerged`，另外加 `effective`、`inherit`、`sensitiveFrom`）
- Test: `backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java`、`MergedDatasourceServiceTest.java`（加測試、import）

**Interfaces:**
- Consumes：Task 4 的 `stored`、`previewMerged`、`build`；Task 1 的 `FieldSchema.withInheritedSensitive`、`sensitiveFrom()`
- Produces：
  - `static Map<UUID, String> MergeEngine.sources(MergeDefinition def, String field)`：提供這個輸出欄位的輸入欄位（輸入來源 id → 欄位名稱）。疊加回 `from`，來源欄回空的 map；查表的 `bring` 欄位回 `{主檔: from}`，其他欄位回 `{主來源: 同名欄位}`
  - `get`、`list`、`load` 回傳的合併來源，schema 帶有實際的 `sensitive` 和 `sensitiveFrom`；資料庫裡存的永遠只有自己勾的，`sensitiveFrom` 永遠是 null
  - `markSensitive`、`setLabels` 回傳 `get` 的結果

- [ ] **Step 1: 寫會失敗的測試**

`MergeEngineTest.java` 最後加：

```java
    @Test
    void sourcesNameTheInputFieldsBehindEachOutputColumn() {
        assertThat(MergeEngine.sources(regionAmountReason(), "region")).isEqualTo(from(north, "region", south, "area"));
        assertThat(MergeEngine.sources(regionAmountReason(), "來源")).isEmpty();
        assertThat(MergeEngine.sources(productLookup(), "產品主檔·owner")).isEqualTo(Map.of(products.datasource().id(), "owner"));
        assertThat(MergeEngine.sources(productLookup(), "qty")).isEqualTo(Map.of(returns.datasource().id(), "qty"));
    }
```

`MergedDatasourceServiceTest.java` 加 import：

```java
import jev.dashboard.datasource.MergeDefinition.Lookup;
```

在 class 最後加：

```java
    @Test
    void sensitivityFollowsTheInputsAndCannotBeTurnedOffOnTheMergedSource() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,memo", "北區,首購");
        UUID s = upload("南區", "region,memo", "南區,備註");
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "memo")).id();
        assertThat(field(service.get("u1", m), "memo").sensitive()).isFalse();

        service.markSensitive("u1", s, List.of("memo"));
        FieldSchema memo = field(service.get("u1", m), "memo");
        assertThat(memo.sensitive()).isTrue();
        assertThat(memo.sensitiveFrom()).isEqualTo("南區");
        Datasource listed = service.list("u1").stream().filter(d -> d.id().equals(m)).findFirst().orElseThrow();
        assertThat(field(listed, "memo").sensitiveFrom()).isEqualTo("南區");
        assertThat(field(service.load("u1", m).datasource(), "memo").sensitive()).isTrue();

        // 畫面會把繼承來的也一起送回來；在合併來源上關不掉，存下來的只有自己勾的
        Datasource toggled = service.markSensitive("u1", m, List.of("region", "memo"));
        assertThat(field(toggled, "memo").sensitiveFrom()).isEqualTo("南區");
        assertThat(field(repo.sources.get(m), "memo").sensitive()).isFalse();
        assertThat(field(repo.sources.get(m), "region").sensitive()).isTrue();
        assertThat(repo.sources.get(m).schema()).allMatch(f -> f.sensitiveFrom() == null);

        service.markSensitive("u1", s, List.of());
        assertThat(field(service.get("u1", m), "memo").sensitive()).isFalse();
    }

    @Test
    void nestedAndLookupSourcesPointAtTheSourceWhoseSwitchIsOn() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "product,memo", "A,首購");
        UUID s = upload("南區", "product,memo", "B,備註");
        UUID p = upload("產品主檔", "code,owner", "A,王小明", "B,李小華");
        service.markSensitive("u1", n, List.of("memo"));
        service.markSensitive("u1", p, List.of("owner"));
        UUID all = service.createMerged("u1", "全區", union(List.of(n, s), "product", "memo")).id();
        Lookup enrich = new Lookup(all, p, List.of(new Lookup.Key("product", "code")), List.of(new Lookup.Bring("負責人", "owner")));
        UUID enriched = service.createMerged("u1", "全區（補產品主檔）", enrich).id();

        Datasource e = service.get("u1", enriched);
        assertThat(field(e, "memo").sensitiveFrom()).isEqualTo("北區");
        assertThat(field(e, "負責人").sensitiveFrom()).isEqualTo("產品主檔");
        assertThat(field(e, "product").sensitive()).isFalse();
        assertThat(field(e, "來源").sensitive()).isFalse();
        assertThat(service.previewMerged("u1", enrich).schema()).filteredOn(FieldSchema::sensitive)
            .extracting(FieldSchema::name).containsExactly("memo", "負責人");
    }

    @Test
    void displayNamesComeFromTheInputsAndCanBeChangedOnTheMergedSource() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,amount", "北區,100");
        UUID s = upload("南區", "region,amount", "南區,50");
        service.setLabels("u1", n, Map.of("region", "地區"));
        service.markSensitive("u1", n, List.of("amount"));
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "amount")).id();
        assertThat(field(service.get("u1", m), "region").label()).isEqualTo("地區");

        Datasource renamed = service.setLabels("u1", m, Map.of("region", "區域", "來源", "資料來源"));

        assertThat(field(renamed, "region").label()).isEqualTo("區域");
        assertThat(field(renamed, "來源").label()).isEqualTo("資料來源");
        assertThat(field(renamed, "amount").sensitiveFrom()).isEqualTo("北區");
        // 改顯示名稱時不能把即時算出來的敏感設定一起存進去
        assertThat(field(repo.sources.get(m), "amount").sensitive()).isFalse();
        assertThat(repo.sources.get(m).schema()).allMatch(f -> f.sensitiveFrom() == null);
        assertThat(field(service.get("u1", n), "region").label()).isEqualTo("地區");
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='MergeEngineTest,MergedDatasourceServiceTest'`
Expected：編譯失敗，`cannot find symbol` 指到 `MergeEngine.sources`。

- [ ] **Step 3: `MergeEngine.sources`**

`MergeEngine.java` 在 `schema` 後面加：

```java
    /** spec 6.3：提供這個輸出欄位的輸入欄位，輸入來源 id → 欄位名稱；來源欄沒有輸入欄位。 */
    public static Map<UUID, String> sources(MergeDefinition def, String field) {
        return switch (def) {
            case Union u -> u.columns().stream().filter(c -> c.name().equals(field)).findFirst()
                .map(Union.Column::from).orElse(Map.of());
            case Lookup l -> l.bring().stream().filter(b -> b.name().equals(field)).findFirst()
                .map(b -> Map.of(l.lookup(), b.from())).orElse(Map.of(l.main(), field));
        };
    }
```

- [ ] **Step 4: `DatasourceService` 的繼承**

加 import：

```java
import java.util.function.Function;
import java.util.stream.Collectors;
```

`list` 換成：

```java
    public List<Datasource> list(String userId) {
        List<Datasource> all = repo.list(userId);
        Map<UUID, Datasource> byId = all.stream().collect(Collectors.toMap(Datasource::id, d -> d));
        return all.stream().map(d -> effective(d, byId::get)).toList();
    }
```

`get` 換成：

```java
    public Datasource get(String userId, UUID id) {
        return effective(stored(userId, id), i -> repo.find(i, userId).orElse(null));
    }
```

`markSensitive` 換成：

```java
    public Datasource markSensitive(String userId, UUID id, Collection<String> fields) {
        // spec 6.3：繼承來的不算自己勾的，輸入來源取消時，合併來源才會跟著取消
        Set<String> inherited = get(userId, id).schema().stream().filter(f -> f.sensitiveFrom() != null)
            .map(FieldSchema::name).collect(Collectors.toSet());
        List<FieldSchema> schema = stored(userId, id).schema().stream()
            .map(f -> f.withSensitive(fields.contains(f.name()) && !inherited.contains(f.name()))).toList();
        repo.updateSchema(id, schema);
        return get(userId, id);
    }
```

`setLabels` 的第一行 `Datasource ds = get(userId, id);` 改成 `Datasource ds = stored(userId, id);`，最後一行 `return ds.withSchema(schema);` 改成 `return get(userId, id);`，其餘不動。

`previewMerged` 換成：

```java
    /** 不存檔。預覽只看定義，body 裡的名稱用不到。 */
    public MergePreview previewMerged(String userId, MergeDefinition def) {
        MergeEngine.Result r = build(userId, def);
        List<FieldSchema> schema = inherit(def, MergeEngine.schema(r), i -> repo.find(i, userId).orElse(null));
        return new MergePreview(schema, r.rows().subList(0, Math.min(PREVIEW_ROWS, r.rows().size())),
            r.rows().size(), r.warnings());
    }
```

在 `checkRows` 前面加：

```java
    /** spec 6.3：合併來源實際的敏感設定＝自己勾的，或任何一個提供這欄的輸入欄位是敏感的。讀取時才算，不存。 */
    private Datasource effective(Datasource ds, Function<UUID, Datasource> find) {
        return "merged".equals(ds.kind()) ? ds.withSchema(inherit(ds.merge(), ds.schema(), find)) : ds;
    }

    private List<FieldSchema> inherit(MergeDefinition def, List<FieldSchema> schema, Function<UUID, Datasource> find) {
        return schema.stream().map(f -> {
            String from = sensitiveFrom(def, f.name(), find);
            return from == null ? f : f.withInheritedSensitive(from);
        }).toList();
    }

    /** 讓這欄變敏感的來源名稱：巢狀時一路往下，找到真正打開開關的那個來源；都不敏感就是 null。 */
    private String sensitiveFrom(MergeDefinition def, String field, Function<UUID, Datasource> find) {
        for (Map.Entry<UUID, String> e : MergeEngine.sources(def, field).entrySet()) {
            Datasource input = find.apply(e.getKey());
            if (input == null) continue;
            for (FieldSchema f : effective(input, find).schema()) {
                if (f.name().equals(e.getValue()) && f.sensitive()) {
                    return f.sensitiveFrom() != null ? f.sensitiveFrom() : input.name();
                }
            }
        }
        return null;
    }
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='MergeEngineTest,MergedDatasourceServiceTest,DatasourceServiceTest,DatasourceControllerTest'`
Expected：PASS。`DatasourceControllerTest` 既有的 `markSensitiveFields`、`setFieldLabels` 照舊通過。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋18，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/main/java/jev/dashboard/datasource/DatasourceService.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java && git commit -m "feat(backend): 合併來源繼承輸入來源的敏感設定，而且關不掉" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/datasource/MergeEngine.java backend/src/main/java/jev/dashboard/datasource/DatasourceService.java backend/src/test/java/jev/dashboard/datasource/MergeEngineTest.java backend/src/test/java/jev/dashboard/datasource/MergedDatasourceServiceTest.java
```

---

### Task 6: API `POST /api/datasources/merged/preview`、`POST /api/datasources/merged`

**Files:**
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceController.java`
- Modify: `backend/src/test/java/jev/dashboard/TestData.java`（加 `uploadCsv`）
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`、`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`（加測試、import）

**Interfaces:**
- Consumes：Task 4、5 的 `previewMerged(userId, def)`、`createMerged(userId, name, def)`、`MergePreview`
- Produces：
  - `DatasourceController.MergeRequest(String name, MergeDefinition merge)`
  - `POST /api/datasources/merged/preview` → `{ schema, rows, rowCount, warnings }`；`POST /api/datasources/merged` → `Datasource`（`kind: "merged"`，帶 `merge`）
  - `TestData.uploadCsv(MockMvc mvc, String userId, String name, String csv)` → 新來源的 id

- [ ] **Step 1: 寫 `TestData.uploadCsv`**

`TestData.java` 在 `uploadSales` 後面加：

```java
    /** 上傳一段 CSV 文字，檔名是「name.csv」，回傳新來源的 id。 */
    public static String uploadCsv(MockMvc mvc, String userId, String name, String csv) throws Exception {
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", name + ".csv", "text/csv", csv.getBytes(UTF_8)))
                .param("name", name)
                .header("X-User-Id", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }
```

- [ ] **Step 2: 寫會失敗的測試**

`DatasourceControllerTest.java` 加 import：

```java
import com.jayway.jsonpath.JsonPath;
```

在 class 最後加：

```java
    static final String NORTH_SOUTH = """
        {"op":"union","inputs":["%1$s","%2$s"],"columns":[
          {"name":"region","from":{"%1$s":"region","%2$s":"area"}},
          {"name":"amount","from":{"%1$s":"amount","%2$s":"amount"}},
          {"name":"memo","from":{"%1$s":"memo"}}],"sourceColumn":"來源"}""";

    void mergeIsRejected(String body, int status, String message) throws Exception {
        mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void mergedSourcesArePreviewedThenCreatedAndListedWithTheirDefinition() throws Exception {
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount,memo\n北區,100,首購\n北區,200,\n");
        String s = TestData.uploadCsv(mvc, "u1", "南區", "area,amount\n南區,50\n");
        String body = "{\"name\":\"全區\",\"merge\":" + NORTH_SOUTH.formatted(n, s) + "}";

        mvc.perform(post("/api/datasources/merged/preview").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(3))
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[2].region").value("南區"))
            .andExpect(jsonPath("$.rows[2]['來源']").value("南區"))
            .andExpect(jsonPath("$.warnings[0]").value("「memo」只有北區有，其他來源的列這欄是空的"))
            .andExpect(jsonPath("$.schema[3].name").value("來源"));
        assertThat(jdbc.sql("select count(*) from datasource").query(Long.class).single()).isEqualTo(2L);

        mvc.perform(patch("/api/datasources/" + n + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("merged"))
            .andExpect(jsonPath("$.merge.op").value("union"))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].merge.inputs[1]").value(s))
            .andExpect(jsonPath("$[0].schema[2].sensitive").value(true))
            .andExpect(jsonPath("$[0].schema[2].sensitiveFrom").value("北區"))
            .andExpect(jsonPath("$[0].schema[0].sensitiveFrom").doesNotExist())
            .andExpect(jsonPath("$[1].merge").doesNotExist());
    }

    @Test
    void badMergeRequestsAreRejected() throws Exception {
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount,memo\n北區,100,首購\n");
        String others = TestData.uploadCsv(mvc, "u2", "別人的", "area,amount\n南區,50\n");
        mergeIsRejected("{\"name\":\"x\",\"merge\":{\"op\":\"join\"}}", 400, "請求內容不是正確的 JSON");
        mergeIsRejected("{\"name\":\"x\"}", 400, "請選擇合併方式");
        mergeIsRejected("{\"name\":\" \",\"merge\":" + NORTH_SOUTH.formatted(n, others) + "}", 400, "請輸入名稱");
        mergeIsRejected("{\"name\":\"x\",\"merge\":{\"op\":\"union\",\"inputs\":[\"" + n + "\"],\"columns\":[],\"sourceColumn\":\"來源\"}}",
            400, "疊加要選 2 到 5 個來源");
        mergeIsRejected("{\"name\":\"x\",\"merge\":" + NORTH_SOUTH.formatted(n, others) + "}", 404, "找不到資料源");
    }
```

`JevControllerTest.java` 在 class 最後加：

```java
    @Test
    void analyzingAMergedSourceKeepsInheritedSensitiveSamplesAwayFromJev() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String products = TestData.uploadCsv(mvc, "u1", "產品主檔", "code,category,owner\nA,家電,王小明\nB,文具,李小華\nC,食品,陳大文\n");
        mvc.perform(patch("/api/datasources/" + sales + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/datasources/" + products + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"owner\"]}"))
            .andExpect(status().isOk());
        String merge = """
            {"op":"lookup","main":"%s","lookup":"%s","keys":[{"main":"product","lookup":"code"}],
             "bring":[{"name":"category","from":"category"},{"name":"owner","from":"owner"}]}""".formatted(sales, products);
        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售（補產品主檔）\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String merged = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + merged + "\"}"))
            .andExpect(status().isOk());

        // 第一輪的 state：category 有樣本；從 sales、產品主檔繼承來的敏感欄位沒有
        assertThat(JEV.bodies.get(0)).contains("家電").doesNotContain("首購").doesNotContain("王小明");
    }
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DatasourceControllerTest,JevControllerTest'`
Expected：FAIL。新的三個測試打 `/api/datasources/merged…` 時拿到 404「找不到這個 API」。

- [ ] **Step 4: 加 endpoint**

`DatasourceController.java`，在 `ApiRegistration` 那行下面加：

```java
    /** merge 是合併定義，JSON 用 op 分辨疊加（union）和查表（lookup），見 spec 4.2、4.3。 */
    public record MergeRequest(String name, MergeDefinition merge) {}
```

在 `registerApi` 後面加：

```java
    @PostMapping("/merged/preview")
    public DatasourceService.MergePreview previewMerged(@RequestHeader("X-User-Id") String userId,
                                                        @RequestBody MergeRequest body) {
        return service.previewMerged(userId, body.merge());
    }

    @PostMapping("/merged")
    public Datasource createMerged(@RequestHeader("X-User-Id") String userId, @RequestBody MergeRequest body) {
        return service.createMerged(userId, body.name(), body.merge());
    }
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw -q test -Dtest='DatasourceControllerTest,JevControllerTest'`
Expected：PASS。

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋21，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/jev/dashboard/datasource/DatasourceController.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java && git commit -m "feat(backend): 合併來源的預覽與建立 API" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- backend/src/main/java/jev/dashboard/datasource/DatasourceController.java backend/src/test/java/jev/dashboard/TestData.java backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java backend/src/test/java/jev/dashboard/jev/JevControllerTest.java
```

---

### Task 7: 前端 API 與 `MergeForm` 的疊加

**Files:**
- Modify: `frontend/src/modules/dashboard/api.js`
- Create: `frontend/src/modules/dashboard/MergeForm.vue`
- Test: `frontend/src/modules/dashboard/MergeForm.test.js`、`frontend/src/modules/dashboard/api.test.js`（加測試）

**Interfaces:**
- Consumes：Task 6 的兩個 API；`FIELD_TYPES`（`fields.js`）
- Produces：
  - `api.previewMerged({ name, merge })`、`api.createMerged({ name, merge })`
  - `MergeForm.vue`：prop `sources`（`listDatasources` 回傳的陣列），事件 `created`（參數是 `createMerged` 回傳的來源）。表單內部有 `op`（`'union'`／`'lookup'`）、`definition`（送給後端的 `merge`）、`preview`、`name`；Task 8 會在同一個檔案補上查表

- [ ] **Step 1: 寫會失敗的測試**

`api.test.js` 在 `describe('api', …)` 裡最後加：

```js
  it('previewMerged / createMerged POST {name, merge} to the merged endpoints', async () => {
    const body = { name: '全區', merge: { op: 'union', inputs: ['a', 'b'], columns: [], sourceColumn: '來源' } }
    fetch.mockResolvedValueOnce(jsonResponse({ rowCount: 0 }))
    await api.previewMerged(body)
    fetch.mockResolvedValueOnce(jsonResponse({ id: 'm1' }))
    await api.createMerged(body)
    expect(fetch.mock.calls.map(([url, init]) => [url, init.method, init.body])).toEqual([
      ['/api/datasources/merged/preview', 'POST', JSON.stringify(body)],
      ['/api/datasources/merged', 'POST', JSON.stringify(body)],
    ])
  })
```

`frontend/src/modules/dashboard/MergeForm.test.js`：

```js
// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MergeForm from './MergeForm.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    previewMerged: vi.fn(),
    createMerged: vi.fn(),
  },
}))

const north = { id: 'n', name: '北區', kind: 'upload', schema: [{ name: 'region', type: 'string' }, { name: 'amount', type: 'number' }] }
const south = { id: 's', name: '南區', kind: 'upload', schema: [{ name: 'area', type: 'string' }, { name: 'amount', type: 'string' }] }
const returns = {
  id: 'r',
  name: '退貨',
  kind: 'upload',
  schema: [{ name: 'product', type: 'string' }, { name: 'qty', type: 'number' }, { name: 'owner', type: 'string' }],
}
const master = {
  id: 'm',
  name: '產品主檔',
  kind: 'upload',
  schema: [{ name: 'code', type: 'string' }, { name: 'category', type: 'string' }, { name: 'owner', type: 'string' }],
}

function mountForm() {
  return mount(MergeForm, { props: { sources: [north, south, returns, master] }, global: { plugins: [vuetify] } })
}

// 有 label 的找 label，表格裡的下拉和輸入框找 aria-label
const named = (c, label) => c.props('label') === label || c.vm.$attrs['aria-label'] === label
const select = (w, label) => w.findAllComponents({ name: 'VSelect' }).find((c) => named(c, label))
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => named(c, label))
const btn = (w, label) => w.findAll('button').find((b) => b.text() === label || b.attributes('aria-label') === label)
const columnNames = (w) =>
  w.findAllComponents({ name: 'VTextField' }).filter((c) => /^第 \d+ 欄的輸出名稱$/.test(c.vm.$attrs['aria-label'] ?? ''))

async function pick(w, label, value) {
  await select(w, label).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('MergeForm（疊加）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('picking 北區 and 南區 lists every field name in source order, matches same names, marks type clashes, and names it 北區＋南區', async () => {
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['region', 'amount', 'area'])
    expect(select(w, '第 1 欄在南區的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 2 欄在南區的欄位').props('modelValue')).toBe('amount')
    expect(select(w, '第 3 欄在北區的欄位').props('modelValue')).toBeNull()
    expect(columnNames(w)[1].props('errorMessages')).toBe('型別不一致：北區是數字、南區是文字')
    expect(columnNames(w)[0].props('errorMessages')).toEqual([])
    expect(field(w, '名稱').props('modelValue')).toBe('北區＋南區')
    expect(field(w, '來源欄名稱').props('modelValue')).toBe('來源')
  })

  it('預覽 sends the definition; 建立 stays off until a preview of the current definition succeeds; editing the definition drops the preview', async () => {
    api.previewMerged.mockResolvedValue({
      schema: [{ name: 'region' }, { name: '來源' }],
      rows: [{ region: '北區', 來源: '北區' }],
      rowCount: 30,
      warnings: ['「region」只有北區有，其他來源的列這欄是空的'],
    })
    api.createMerged.mockResolvedValue({ id: 'x1' })
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])
    // region 對到南區的 area；刪掉多出來的 area 列，型別不同的 amount 也刪掉
    await pick(w, '第 1 欄在南區的欄位', 'area')
    await btn(w, '刪除第 3 欄').trigger('click')
    await btn(w, '刪除第 2 欄').trigger('click')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged).toHaveBeenCalledWith({
      name: '北區＋南區',
      merge: { op: 'union', inputs: ['n', 's'], columns: [{ name: 'region', from: { n: 'region', s: 'area' } }], sourceColumn: '來源' },
    })
    expect(w.text()).toContain('預覽 · 共 30 筆')
    expect(w.text()).toContain('「region」只有北區有')
    expect(w.findAll('.mf-preview tbody tr')).toHaveLength(1)
    expect(btn(w, '建立').attributes('disabled')).toBeUndefined()

    await field(w, '來源欄名稱').vm.$emit('update:modelValue', '區')
    await flushPromises()
    expect(w.text()).not.toContain('共 30 筆')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    await btn(w, '建立').trigger('click')
    await flushPromises()
    expect(api.createMerged).toHaveBeenCalledWith({ name: '北區＋南區', merge: expect.objectContaining({ sourceColumn: '區' }) })
    expect(w.emitted('created')).toEqual([[{ id: 'x1' }]])
  })

  it('a failed preview shows the error and keeps 建立 off', async () => {
    api.previewMerged.mockRejectedValue(new Error('「region」在北區是文字、在南區是數字，不能併成同一欄'))
    const w = mountForm()
    expect(btn(w, '預覽').attributes('disabled')).toBeDefined()
    await pick(w, '來源', ['n', 's'])
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('不能併成同一欄')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()
  })

  it('the default name follows the picked sources until the user types one', async () => {
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])
    expect(field(w, '名稱').props('modelValue')).toBe('北區＋南區')
    await field(w, '名稱').vm.$emit('update:modelValue', '全公司')
    await pick(w, '來源', ['s', 'n'])
    expect(field(w, '名稱').props('modelValue')).toBe('全公司')
  })
})
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js src/modules/dashboard/api.test.js`
Expected：FAIL。`MergeForm.vue` 找不到，`api.previewMerged is not a function`。

- [ ] **Step 3: `api.js` 加兩個方法**

在 `setLabels` 那行下面加（A 也會在這個物件裡加方法，合併時兩邊都留）：

```js
  previewMerged: (body) => request('POST', '/api/datasources/merged/preview', body),
  createMerged: (body) => request('POST', '/api/datasources/merged', body),
```

- [ ] **Step 4: 寫 `MergeForm.vue`（疊加部分）**

`frontend/src/modules/dashboard/MergeForm.vue`：

```vue
<script setup>
import { computed, ref, watch } from 'vue'
import { api } from './api.js'
import { FIELD_TYPES } from './fields.js'

// 「新增來源」對話框的「合併」分頁（spec 8.1）：疊加或查表，預覽成功而且顯示的是目前的定義，才能建立
const props = defineProps({
  sources: { type: Array, required: true },
})
const emit = defineEmits(['created'])

const op = ref('union')
const name = ref('')
const nameEdited = ref(false)
// 疊加
const inputs = ref([])
const columns = ref([]) // [{ key, name, from: { [來源 id]: 欄位名稱 | null } }]
const sourceColumn = ref('來源')

const preview = ref(null)
const error = ref('')
const busy = ref(false)

let nextKey = 0
const byId = computed(() => Object.fromEntries(props.sources.map((ds) => [ds.id, ds])))
const nameOf = (id) => byId.value[id]?.name ?? '（已不存在）'
const fieldsOf = (id) => byId.value[id]?.schema ?? []
const typeOf = (id, field) => fieldsOf(id).find((f) => f.name === field)?.type
const fieldItems = (id) => fieldsOf(id).map((f) => ({ title: f.name, value: f.name, prependIcon: FIELD_TYPES[f.type]?.icon }))

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

const definition = computed(() => ({
  op: 'union',
  inputs: inputs.value,
  columns: columns.value.map((c) => ({
    name: c.name.trim(),
    from: Object.fromEntries(Object.entries(c.from).filter(([, f]) => f)),
  })),
  sourceColumn: sourceColumn.value.trim(),
}))
const definitionKey = computed(() => JSON.stringify(definition.value))

const defaultName = computed(() => inputs.value.map(nameOf).join('＋'))
watch(defaultName, (n) => {
  if (!nameEdited.value) name.value = n
}, { immediate: true })

// spec 8.1：定義一改，預覽就清掉，「建立」要重新預覽才能按
watch(definitionKey, () => {
  preview.value = null
  error.value = ''
})

const canPreview = computed(() => !busy.value && inputs.value.length >= 2)
const canCreate = computed(() => !busy.value && !!preview.value && !!name.value.trim())

async function runPreview() {
  const key = definitionKey.value
  busy.value = true
  error.value = ''
  try {
    const result = await api.previewMerged({ name: name.value.trim(), merge: definition.value })
    // 等回應的時候定義又改了：這份預覽已經不是目前的定義，丟掉
    if (key === definitionKey.value) preview.value = result
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function create() {
  busy.value = true
  error.value = ''
  try {
    emit('created', await api.createMerged({ name: name.value.trim(), merge: definition.value }))
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <v-radio-group v-model="op" inline hide-details aria-label="合併方式" class="mb-2">
      <v-radio value="union" label="疊加（欄位一樣的資料接起來）" />
      <v-radio value="lookup" label="查表（用主檔補欄位）" />
    </v-radio-group>

    <template v-if="op === 'union'">
      <v-select
        v-model="inputs"
        :items="sources"
        item-title="name"
        item-value="id"
        label="來源"
        multiple
        chips
        closable-chips
        hint="2～5 個，順序就是資料列的順序"
        persistent-hint
      />
      <template v-if="inputs.length">
        <v-table density="compact" class="mf-map mt-3">
          <thead>
            <tr>
              <th>輸出欄位</th>
              <th v-for="id in inputs" :key="id">{{ nameOf(id) }}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in columns" :key="c.key">
              <td>
                <v-text-field
                  v-model="c.name"
                  :aria-label="`第 ${i + 1} 欄的輸出名稱`"
                  density="compact"
                  hide-details="auto"
                  :error-messages="typeProblem(c) || []"
                />
              </td>
              <td v-for="id in inputs" :key="id">
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
              </td>
              <td>
                <v-btn icon="mdi-close" variant="text" size="small" :aria-label="`刪除第 ${i + 1} 欄`" @click="columns.splice(i, 1)" />
              </td>
            </tr>
          </tbody>
        </v-table>
        <v-btn variant="text" size="small" prepend-icon="mdi-plus" class="mt-1" @click="columns.push({ key: nextKey++, name: '', from: {} })">
          新增一欄
        </v-btn>
      </template>
      <v-text-field v-model="sourceColumn" label="來源欄名稱" class="mt-3" />
    </template>

    <v-text-field v-model="name" label="名稱" class="mt-3" @update:model-value="nameEdited = true" />

    <v-alert v-if="error" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
    <div v-if="preview" class="mt-3">
      <div class="cc-label mb-1">預覽 · 共 {{ preview.rowCount }} 筆</div>
      <v-alert v-for="w in preview.warnings" :key="w" type="warning" density="compact" class="mb-2">{{ w }}</v-alert>
      <div class="mf-preview">
        <v-table density="compact">
          <thead>
            <tr><th v-for="f in preview.schema" :key="f.name">{{ f.label || f.name }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in preview.rows" :key="i">
              <td v-for="f in preview.schema" :key="f.name">{{ row[f.name] ?? '' }}</td>
            </tr>
          </tbody>
        </v-table>
      </div>
    </div>

    <div class="d-flex justify-end ga-2 mt-3">
      <v-btn variant="flat" :loading="busy" :disabled="!canPreview" @click="runPreview">預覽</v-btn>
      <v-btn variant="flat" color="primary" :disabled="!canCreate" @click="create">建立</v-btn>
    </div>
  </div>
</template>

<style scoped>
.mf-preview { max-height: 320px; overflow: auto; }
.mf-preview :deep(th),
.mf-preview :deep(td) { white-space: nowrap; }
</style>
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js src/modules/dashboard/api.test.js`
Expected：PASS。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/MergeForm.vue frontend/src/modules/dashboard/MergeForm.test.js && git commit -m "feat(frontend): 合併表單的疊加：對應表、型別檢查、預覽後才能建立" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/api.js frontend/src/modules/dashboard/api.test.js frontend/src/modules/dashboard/MergeForm.vue frontend/src/modules/dashboard/MergeForm.test.js
```

---

### Task 8: `MergeForm` 的查表

**Files:**
- Modify: `frontend/src/modules/dashboard/MergeForm.vue`
- Test: `frontend/src/modules/dashboard/MergeForm.test.js`（加一個 `describe`）

**Interfaces:**
- Consumes：Task 7 的 `MergeForm.vue`（`op`、`definition`、`defaultName`、`canPreview`、`fieldItems`、`nameOf`、`fieldsOf`）
- Produces：`op === 'lookup'` 時 `definition` 是 `{ op: 'lookup', main, lookup, keys: [{ main, lookup }], bring: [{ name, from }] }`，`bring` 只放有勾的欄位

- [ ] **Step 1: 寫會失敗的測試**

`MergeForm.test.js` 最後加：

```js
const checkbox = (w, label) => w.findAllComponents({ name: 'VCheckboxBtn' }).find((c) => c.vm.$attrs['aria-label'] === label)
const keySelects = (w) =>
  w.findAllComponents({ name: 'VSelect' }).filter((c) => /^第 \d 組主來源欄位$/.test(c.vm.$attrs['aria-label'] ?? ''))

async function lookupMode(w) {
  await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', 'lookup')
  await flushPromises()
}

describe('MergeForm（查表）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('主檔 skips the 主來源; 補上的欄位 keep their names (「主檔名·欄位」 on a clash); a field picked as a key gets unchecked; 預覽 sends the lookup', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 4, warnings: [] })
    const w = mountForm()
    await lookupMode(w)
    await pick(w, '主來源', 'r')
    expect(select(w, '主檔').props('items').map((d) => d.id)).toEqual(['n', 's', 'm'])
    await pick(w, '主檔', 'm')
    expect(field(w, '名稱').props('modelValue')).toBe('退貨（補產品主檔）')
    expect(['code', 'category', 'owner'].map((f) => field(w, `${f} 的輸出名稱`).props('modelValue')))
      .toEqual(['code', 'category', '產品主檔·owner'])
    expect(checkbox(w, '補上 code').props('modelValue')).toBe(true)
    expect(btn(w, '預覽').attributes('disabled')).toBeDefined()

    await pick(w, '第 1 組主來源欄位', 'product')
    await pick(w, '第 1 組主檔欄位', 'code')
    expect(checkbox(w, '補上 code').props('modelValue')).toBe(false)

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged).toHaveBeenCalledWith({
      name: '退貨（補產品主檔）',
      merge: {
        op: 'lookup',
        main: 'r',
        lookup: 'm',
        keys: [{ main: 'product', lookup: 'code' }],
        bring: [{ name: 'category', from: 'category' }, { name: '產品主檔·owner', from: 'owner' }],
      },
    })
  })

  it('查表 takes 1 to 3 key pairs, and picking the 主檔 as 主來源 clears the 主檔', async () => {
    const w = mountForm()
    await lookupMode(w)
    expect(keySelects(w)).toHaveLength(1)
    expect(btn(w, '刪除第 1 組對接欄位')).toBeUndefined()
    await btn(w, '新增對接欄位').trigger('click')
    await btn(w, '新增對接欄位').trigger('click')
    expect(keySelects(w)).toHaveLength(3)
    expect(btn(w, '新增對接欄位')).toBeUndefined()
    await btn(w, '刪除第 2 組對接欄位').trigger('click')
    expect(keySelects(w)).toHaveLength(2)

    await pick(w, '主來源', 'r')
    await pick(w, '主檔', 'm')
    await pick(w, '主來源', 'm')
    expect(select(w, '主檔').props('modelValue')).toBeNull()
  })
})
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js`
Expected：「查表」的兩個測試 FAIL，找不到「主來源」下拉選單。

- [ ] **Step 3: 補上查表**

`MergeForm.vue` 的 `<script setup>`：

在 `const sourceColumn = ref('來源')` 下面加：

```js
// 查表
const main = ref(null)
const lookup = ref(null)
const keys = ref([{ main: null, lookup: null }])
const bring = ref([]) // 主檔每個欄位一列：{ from, name, on }
```

在 `typeProblem` 後面加：

```js
const lookupItems = computed(() => props.sources.filter((ds) => ds.id !== main.value))

watch(main, (id) => {
  if (lookup.value === id) lookup.value = null
})

// 換主來源或主檔：對接欄位重選；補上的欄位預設用原本的名稱，跟主來源欄位同名時改成「主檔名·欄位」
watch([main, lookup], () => {
  keys.value = [{ main: null, lookup: null }]
  const taken = new Set(fieldsOf(main.value).map((f) => f.name))
  bring.value = fieldsOf(lookup.value).map((f) => ({
    from: f.name,
    name: taken.has(f.name) ? `${nameOf(lookup.value)}·${f.name}` : f.name,
    on: true,
  }))
})

// spec 8.1：對接欄位預設不勾。剛被選成對接欄位的主檔欄位取消勾選，之後使用者要勾回來也可以
watch(() => keys.value.map((k) => k.lookup), (now, before) => {
  for (const b of bring.value) if (now.includes(b.from) && !before.includes(b.from)) b.on = false
})
```

`definition` 整個換成：

```js
const definition = computed(() => op.value === 'union'
  ? {
      op: 'union',
      inputs: inputs.value,
      columns: columns.value.map((c) => ({
        name: c.name.trim(),
        from: Object.fromEntries(Object.entries(c.from).filter(([, f]) => f)),
      })),
      sourceColumn: sourceColumn.value.trim(),
    }
  : {
      op: 'lookup',
      main: main.value,
      lookup: lookup.value,
      keys: keys.value.map((k) => ({ main: k.main, lookup: k.lookup })),
      bring: bring.value.filter((b) => b.on).map((b) => ({ name: b.name.trim(), from: b.from })),
    })
```

`defaultName` 換成：

```js
const defaultName = computed(() => op.value === 'union'
  ? inputs.value.map(nameOf).join('＋')
  : main.value && lookup.value ? `${nameOf(main.value)}（補${nameOf(lookup.value)}）` : '')
```

`canPreview` 換成：

```js
const canPreview = computed(() => !busy.value && (op.value === 'union'
  ? inputs.value.length >= 2
  : !!main.value && !!lookup.value && keys.value.every((k) => k.main && k.lookup)))
```

`<template>` 裡，在疊加那個 `<template v-if="op === 'union'">…</template>` 後面加：

```vue
    <template v-else>
      <div class="d-flex ga-3">
        <v-select v-model="main" :items="sources" item-title="name" item-value="id" label="主來源" />
        <v-select v-model="lookup" :items="lookupItems" item-title="name" item-value="id" label="主檔" />
      </div>
      <div class="cc-label mb-1">對接欄位</div>
      <div v-for="(k, i) in keys" :key="i" class="d-flex align-center ga-2 mb-2">
        <v-select
          v-model="k.main"
          :items="fieldItems(main)"
          item-props
          :aria-label="`第 ${i + 1} 組主來源欄位`"
          density="compact"
          hide-details
        />
        <span aria-hidden="true">=</span>
        <v-select
          v-model="k.lookup"
          :items="fieldItems(lookup)"
          item-props
          :aria-label="`第 ${i + 1} 組主檔欄位`"
          density="compact"
          hide-details
        />
        <v-btn
          v-if="keys.length > 1"
          icon="mdi-close"
          variant="text"
          size="small"
          :aria-label="`刪除第 ${i + 1} 組對接欄位`"
          @click="keys.splice(i, 1)"
        />
      </div>
      <v-btn v-if="keys.length < 3" variant="text" size="small" prepend-icon="mdi-plus" @click="keys.push({ main: null, lookup: null })">
        新增對接欄位
      </v-btn>
      <template v-if="bring.length">
        <div class="cc-label mt-3 mb-1">補上的欄位</div>
        <div v-for="b in bring" :key="b.from" class="d-flex align-center ga-2">
          <v-checkbox-btn v-model="b.on" :aria-label="`補上 ${b.from}`" />
          <v-text-field v-model="b.name" :aria-label="`${b.from} 的輸出名稱`" :disabled="!b.on" density="compact" hide-details />
        </div>
      </template>
    </template>
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/MergeForm.test.js`
Expected：PASS（疊加 4 個＋查表 2 個）。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/MergeForm.vue frontend/src/modules/dashboard/MergeForm.test.js && git commit -m "feat(frontend): 合併表單的查表：對接欄位、補上的欄位與預設名稱" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/MergeForm.vue frontend/src/modules/dashboard/MergeForm.test.js
```

---

### Task 9: 資料頁：合併分頁、合併來源的標籤與說明、繼承來的敏感開關

**Files:**
- Modify: `frontend/src/modules/dashboard/DatasourcePanel.vue`
- Test: `frontend/src/modules/dashboard/DatasourcePanel.test.js`（加測試；改一個既有測試的預期值）

**Interfaces:**
- Consumes：Task 7、8 的 `MergeForm`（prop `sources`、事件 `created`）；後端清單裡的 `kind: 'merged'`、`merge`、`schema[].sensitiveFrom`
- Produces：畫面行為，沒有新的程式介面

- [ ] **Step 1: 寫會失敗的測試**

`DatasourcePanel.test.js` 加 import：

```js
import MergeForm from './MergeForm.vue'
```

`vi.mock('./api.js', …)` 的 `api` 物件裡加兩行：

```js
    previewMerged: vi.fn(),
    createMerged: vi.fn(),
```

在 `ds2` 下面加：

```js
const merged = {
  id: 'mg',
  name: '全區',
  kind: 'merged',
  merge: { op: 'union', inputs: ['ds1', 'gone'], columns: [], sourceColumn: '來源' },
  schema: [
    { name: 'region', type: 'string', sensitive: false },
    { name: 'memo', type: 'string', sensitive: true, sensitiveFrom: 'sales.csv' },
    { name: 'qty', type: 'number', sensitive: false },
  ],
}
const enriched = {
  id: 'lk',
  name: '退貨（補產品主檔）',
  kind: 'merged',
  merge: { op: 'lookup', main: 'ds1', lookup: 'ds2', keys: [{ main: 'product', lookup: 'code' }], bring: [] },
  schema: [{ name: 'product', type: 'string', sensitive: false }],
}
```

在 `describe` 裡最後加：

```js
  it('a merged source shows the 合併 badge and icon, what it merges (missing inputs as （已不存在）), and locks inherited sensitive switches', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2, merged, enriched])
    const wrapper = mountPanel('mg')
    await flushPromises()

    expect(wrapper.find('.tc-badge').text()).toBe('合併')
    expect(wrapper.findAllComponents({ name: 'VListItem' }).find((i) => i.props('title') === '全區').props('prependIcon')).toBe('mdi-set-merge')
    expect(wrapper.find('.ds-meta').text()).toBe('疊加：sales.csv、（已不存在） · 3 個欄位 · 1 個敏感')
    const [region, memo] = switches(wrapper)
    expect(memo.element.disabled).toBe(true)
    expect(region.element.disabled).toBe(false)
    expect(memo.element.closest('td').getAttribute('title')).toBe('在『sales.csv』標為敏感，要改請到那個來源')
    expect(region.element.closest('td').hasAttribute('title')).toBe(false)

    await wrapper.setProps({ selectedId: 'lk' })
    expect(wrapper.find('.ds-meta').text()).toBe('查表：sales.csv ← ERP 訂單（product = code） · 1 個欄位 · 0 個敏感')
  })

  it('合併 tab widens the dialog and shows the merge form; a created source is selected and the dialog closes', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    const wrapper = mountPanel()
    await flushPromises()
    await openAdd(wrapper)
    await wrapper.findAllComponents({ name: 'VTab' }).find((t) => t.text() === '合併').trigger('click')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'VDialog' }).props('maxWidth')).toBe(760)
    const form = wrapper.findComponent(MergeForm)
    expect(form.props('sources')).toEqual([ds1, ds2])
    expect(button('上傳')).toBeUndefined()
    expect(button('註冊')).toBeUndefined()

    api.listDatasources.mockResolvedValue([ds1, ds2, merged])
    form.vm.$emit('created', { id: 'mg' })
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('select').at(-1)).toEqual(['mg'])
    expect(dialogOpen(wrapper)).toBe(false)
  })
```

既有測試「上傳檔案 and 外部 API are not pages in a sequence…」最後一行的預期值改成三組（多了「合併」分頁）：

```js
    expect(items.map((w) => [w.props('transition'), w.props('reverseTransition')])).toEqual([[false, false], [false, false], [false, false]])
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test -- src/modules/dashboard/DatasourcePanel.test.js`
Expected：新的兩個測試和改過預期值的那個 FAIL（標籤還是「上傳檔案」、沒有「合併」分頁、只有兩個 `VWindowItem`）。

- [ ] **Step 3: 改 `DatasourcePanel.vue`**

`<script setup>`：

`import { FIELD_TYPES } from './fields.js'` 下面加：

```js
import MergeForm from './MergeForm.vue'
```

`const filter = ref('')` 下面加：

```js
const KINDS = {
  upload: { icon: 'mdi-file-table-outline', text: '上傳檔案' },
  api: { icon: 'mdi-api', text: '外部 API' },
  merged: { icon: 'mdi-set-merge', text: '合併' },
}
```

`const apiHeaders = ref('{}')` 下面加：

```js
// 建立成功後換 key，下次打開是乾淨的表單
const mergeKey = ref(0)
```

`register()` 後面加：

```js
async function onMerged(ds) {
  try {
    await refresh()
    emit('select', ds.id)
    addOpen.value = false
    mergeKey.value++
  } catch (e) {
    addError.value = e.message
  }
}

// spec 8.2：合併來源的說明列；輸入來源查不到就寫「（已不存在）」
function mergeSummary(ds) {
  const nameOf = (id) => list.value.find((d) => d.id === id)?.name ?? '（已不存在）'
  const m = ds.merge
  return m.op === 'union'
    ? `疊加：${m.inputs.map(nameOf).join('、')}`
    : `查表：${nameOf(m.main)} ← ${nameOf(m.lookup)}（${m.keys.map((k) => `${k.main} = ${k.lookup}`).join('、')}）`
}
```

`<template>`：

清單項目的 `:prepend-icon="ds.kind === 'api' ? 'mdi-api' : 'mdi-file-table-outline'"` 改成：

```vue
            :prepend-icon="KINDS[ds.kind]?.icon"
```

標籤 `<span class="tc-badge">{{ selected.kind === 'api' ? '外部 API' : '上傳檔案' }}</span>` 改成：

```vue
              <span class="tc-badge">{{ KINDS[selected.kind]?.text ?? selected.kind }}</span>
```

說明列 `<template v-if="selected.kind === 'api'">{{ selected.apiUrl }} · </template>{{ selected.schema.length }} 個欄位 · {{ sensitiveCount }} 個敏感` 改成：

```vue
              <template v-if="selected.kind === 'api'">{{ selected.apiUrl }} · </template><template v-else-if="selected.merge">{{ mergeSummary(selected) }} · </template>{{ selected.schema.length }} 個欄位 · {{ sensitiveCount }} 個敏感
```

敏感那一格整個換成：

```vue
              <!-- spec 6.3：繼承來的敏感設定在這裡關不掉，提示要回到哪個來源改 -->
              <td class="text-center" :title="f.sensitiveFrom ? `在『${f.sensitiveFrom}』標為敏感，要改請到那個來源` : undefined">
                <v-switch
                  :model-value="f.sensitive"
                  :aria-label="`${f.name} 敏感`"
                  hide-details
                  class="d-inline-flex"
                  :disabled="busy || !!f.sensitiveFrom"
                  @update:model-value="(on) => toggleSensitive(selected, f.name, on)"
                />
              </td>
```

對話框 `<v-dialog v-model="addOpen" max-width="520" transition="tc-sheet">` 改成：

```vue
    <v-dialog v-model="addOpen" :max-width="addTab === 'merge' ? 760 : 520" transition="tc-sheet">
```

`<v-tab value="api" prepend-icon="mdi-api">外部 API</v-tab>` 下面加：

```vue
          <v-tab value="merge" prepend-icon="mdi-set-merge">合併</v-tab>
```

外部 API 的 `</v-window-item>` 後面加：

```vue
            <v-window-item value="merge" :transition="false" :reverse-transition="false">
              <MergeForm :key="mergeKey" :sources="list" @created="onMerged" />
            </v-window-item>
```

「註冊」按鈕的 `v-else` 改成 `v-else-if="addTab === 'api'"`（合併分頁自己有「預覽」「建立」，下面只留「取消」）：

```vue
          <v-btn v-else-if="addTab === 'api'" variant="flat" color="primary" :loading="adding" @click="register">註冊</v-btn>
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected：全部通過（基準數量＋9），build 成功。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/dashboard/DatasourcePanel.vue frontend/src/modules/dashboard/DatasourcePanel.test.js && git commit -m "feat(frontend): 資料頁加合併分頁，合併來源顯示合併方式、鎖住繼承的敏感開關" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- frontend/src/modules/dashboard/DatasourcePanel.vue frontend/src/modules/dashboard/DatasourcePanel.test.js
```

---

### Task 10: 文件：README、backlog

**Files:**
- Modify: `README.md`
- Modify: `docs/backlog.md`

**Interfaces:**
- Consumes：Task 1–9 的行為
- Produces：無

- [ ] **Step 1: README**

「介面分成三個工作區」清單的第一項改成：

```markdown
- 「資料」：新增來源（上傳 CSV／Excel、註冊外部 API，或把現有的來源合併），右邊的欄位表可以改顯示名稱、設定敏感欄位。按「用這個來源製圖」會帶著這個來源切到製圖頁。
```

在「## 測試」前面加一節：

```markdown
## 合併來源

「新增來源」對話框的「合併」分頁，可以把現有的來源合成一份新的來源（`kind: merged`）：

- 疊加：欄位一樣的幾份資料接成一份，例如北區接南區，或每月一個檔。欄位名稱不一樣時，在對應表裡對起來就好；另外會多一欄「來源」，標出每一列來自哪一份。最多 5 個來源，同一欄的型別要一樣。
- 查表：用主檔替主來源補欄位，例如退貨單拿產品代碼去對產品主檔，補上產品類別。主來源一列不增也不減；主檔的對接值重複時，用排最前面的那一列。

建立前一定要先按「預覽」，看筆數、提示（例如「12 筆對不到主檔，補上的欄位是空的」）和前 20 列。合併來源不存資料列，每次載入才照定義重新合併，所以外部 API 的輸入更新了，合併來源也會跟著更新。合併來源可以再拿去合併，最多 3 層；合併後的筆數一樣受 `jev.upload.max-rows` 限制。

敏感設定從輸入來源繼承：輸入來源標成敏感的欄位，在合併來源上也是敏感的，而且關不掉，要改得回到那個來源。顯示名稱在建立時沿用輸入來源的設定，之後可以自己改。

合併定義建立之後不能編輯，也不能刪除（其他來源目前也不能刪），定義錯了就另建一個。完整規則見 [`docs/superpowers/specs/2026-09-27-merged-datasource-design.md`](docs/superpowers/specs/2026-09-27-merged-datasource-design.md)。
```

- [ ] **Step 2: backlog**

「## 功能構想」最後加：

```markdown
- P3 · 編輯、刪除合併來源的定義：合併定義建立之後不能改也不能刪，定義錯了只能另建一個，清單會越積越多。刪除要跟「刪除資料來源」一起設計（其他來源目前也不能刪），包括用到它的合併來源和儀表板要怎麼處理。（B1 spec 第 2、11 節）
```

「## 穩定度與效能」最後加：

```markdown
- P3 · 巢狀合併時，同一個輸入來源在結構裡出現兩次，就會被載入兩次（`DatasourceService.merge` 沒有在同一次載入裡去重），外部 API 輸入也會被打兩次。等巢狀合併常見、載入變慢時，再在同一次 `load` 裡加快取。（B1 spec 5.3）
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/backlog.md && git commit -m "docs: README 說明合併來源，backlog 記下合併定義不能編輯刪除" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- README.md docs/backlog.md
```

---

### Task 11: 手動驗證（自己起前後端，用 Chrome DevTools MCP 操作）

**Files:**
- Create: `docs/superpowers/e2e/merged-datasource/north.csv`、`south.csv`、`returns.csv`、`products.csv`
- 發現問題時修正對應的檔案，commit 訊息要寫清楚改了什麼

**Interfaces:**
- Consumes：Task 1–10 的全部成果、Chrome DevTools MCP（`new_page`、`take_snapshot`、`upload_file`、`fill`、`click`、`wait_for`、`evaluate_script`、`take_screenshot`、`close_page`）

**誰來跑：** 整個 Task 11 交給同一個 subagent 從頭跑到尾，這個 subagent 要能用 Chrome DevTools MCP。Step 3、4 開的背景程序屬於它；controller 的 main session 不跑 `mvnw`，也不跑 `npm run dev`。

**瀏覽器規則，一定要遵守：** 用 `new_page` 開一個自己的分頁，記下它的 pageId，之後只操作這個分頁。別的分頁是使用者自己在用的：不要 `select_page`、`navigate_page`、`close_page` 它們，也不要讀取、截圖或點它們。不確定目前選的是哪一頁時，先 `select_page` 自己的 pageId。結束時只關自己的分頁。MCP 說連不到 `127.0.0.1:9222`，就代表自動化用的 Chrome 沒開：用 Bash 的 `run_in_background: true` 執行 `"/c/Program Files/Google/Chrome/Application/chrome.exe" --remote-debugging-port=9222 --user-data-dir="C:\\selenum\\AutomationProfile" about:blank`，再試一次。快照太大時改用 `evaluate_script` 讀畫面。

**等待和程序：** 每個等待迴圈最多等 180 秒，逾時就印出伺服器 log 的最後幾十行，停下來回報，不要一直重試。agent 不能 `taskkill`：埠被別的程序佔著，或自己開的程序停不掉時，用 `netstat -ano | grep …` 找出 PID，回報給使用者處理。

- [ ] **Step 1: 寫測試用的 CSV**

`docs/superpowers/e2e/merged-datasource/north.csv`：

```csv
order_date,region,product,amount,memo
2026-07-03,北區,A,1200,首購
2026-07-15,北區,B,800,
2026-08-02,北區,A,1500,老客戶
2026-08-20,北區,C,600,
2026-09-05,北區,B,900,
```

`docs/superpowers/e2e/merged-datasource/south.csv`：

```csv
order_date,area,product,amount,channel
2026-07-08,南區,A,700,門市
2026-07-22,南區,C,400,網路
2026-08-11,南區,B,1100,門市
2026-09-01,南區,A,950,網路
```

`docs/superpowers/e2e/merged-datasource/returns.csv`：

```csv
return_date,product,qty,reason
2026-07-10,A,1,瑕疵
2026-08-05,B,2,尺寸不合
2026-08-28,Z,1,其他
2026-09-12,A,1,瑕疵
```

`docs/superpowers/e2e/merged-datasource/products.csv`：

```csv
code,category,owner
A,家電,王小明
B,文具,李小華
C,食品,陳大文
A,家電,張三
```

- [ ] **Step 2: 全部測試再跑一次，確認 JEV key 和埠**

Run: `export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_test_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_test_b1; cd "$(git rev-parse --show-toplevel)/backend" && ./mvnw test 2>&1 | grep -E "Tests run:|BUILD" | tail -2`
Expected：基準數量＋21，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。

Run: `cd "$(git rev-parse --show-toplevel)/frontend" && npm test && npm run build`
Expected：全部通過，build 成功。

Run: `grep -q '^TYPESAFE_API_KEY=.' backend/.env && echo "TYPESAFE_API_KEY 已設定" || echo "TYPESAFE_API_KEY 沒設定"`
Expected：`TYPESAFE_API_KEY 已設定`。這行只印結果，不印內容。沒設定就停下來向 controller 要 key，不要自己打開 `.env` 看。

Run: `curl -s -o /dev/null -w "8080 %{http_code}\n" http://127.0.0.1:8080/; curl -s -o /dev/null -w "5180 %{http_code}\n" http://localhost:5180/`
Expected：`8080 000` 和 `5180 000`。只要有一個不是 `000`，就代表有程式佔著這個埠（可能是使用者的舊後端，或 A 的手動驗證正在跑）。不要用它、不要關它：跑 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'`，把 PID 回報給 controller，停在這一步。前端的 proxy 寫死 8080，A 和 B1 的手動驗證不能同時跑。

- [ ] **Step 3: 啟動後端（背景，用獨立的 schema）**

用 Bash 的 `run_in_background: true` 執行：

```bash
ROOT=$(git rev-parse --show-toplevel); export JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; export SPRING_DATASOURCE_HIKARI_SCHEMA=jev_dashboard_e2e_b1 SPRING_FLYWAY_SCHEMAS=jev_dashboard_e2e_b1; cd "$ROOT/backend" && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1 > target/e2e-backend.log 2>&1
```

再用 Bash 的 `run_in_background: true` 跑探測迴圈。沒帶 `merge` 的預覽在新程式會回「請選擇合併方式」，舊程式會回「找不到這個 API」；看到前者，才代表 8080 上跑的是這個 branch 的程式：

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

先決定這次的使用者 id，例如 `e2e-merge-$(date +%H%M%S)`，記在報告裡。用 Bash 的 `run_in_background: true` 執行（把 `<USER>` 換成它）：

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

- [ ] **Step 5: 上傳四份 CSV，設定敏感欄位**

`new_page` 開 `http://localhost:5180/`，切到「資料」。用「新增來源 → 上傳檔案」依序上傳，`upload_file` 的路徑用 `cygpath -w "$(git rev-parse --show-toplevel)/docs/superpowers/e2e/merged-datasource/north.csv"` 印出來的 Windows 路徑，名稱分別填「北區營收」「南區營收」「退貨」「產品主檔」。

把「北區營收」的 `memo`、「產品主檔」的 `owner` 打開「敏感」。

- [ ] **Step 6: 疊加**

「新增來源 → 合併 → 疊加」，「來源」依序選「北區營收」「南區營收」。逐項確認並記錄：

1. 對話框變寬。對應表的列依序是 `order_date`、`region`、`product`、`amount`、`memo`、`area`、`channel`，同名欄位已經自動對上。
2. 把 `amount` 那列在南區營收的欄位改成 `channel`：那一列標紅，寫「型別不一致：北區營收是數字、南區營收是文字」。改回 `amount`，紅字消失。
3. `region` 那列在南區營收的欄位選 `area`，再刪掉 `area` 那一列。
4. 按「預覽」：顯示「預覽 · 共 9 筆」；提示有兩則：「「memo」只有北區營收有，其他來源的列這欄是空的」和「「channel」只有南區營收有，其他來源的列這欄是空的」；表格有 9 列，南區的列 `region` 是「南區」，最後一欄「來源」是「南區營收」。
5. 把「來源欄名稱」改成「區域來源」：預覽消失，「建立」停用。改回「來源」，再按一次「預覽」。
6. 名稱預設是「北區營收＋南區營收」，改成「全公司營收」，按「建立」。對話框關閉，清單選中「全公司營收」，標籤是「合併」；說明列是「疊加：北區營收、南區營收 · 7 個欄位 · 1 個敏感」；`memo` 的敏感開關打開而且停用，滑鼠移上去（或用 `evaluate_script` 讀那一格的 `title`）是「在『北區營收』標為敏感，要改請到那個來源」。

- [ ] **Step 7: 查表**

「新增來源 → 合併 → 查表」：「主來源」選「退貨」，「主檔」的選項裡沒有「退貨」，選「產品主檔」。「補上的欄位」列出 `code`、`category`、`owner`，名稱都沒改（「退貨」沒有同名欄位）。對接欄位選 `product` = `code`：`code` 被取消勾選。按「預覽」：共 4 筆，提示「主檔有 1 個對接值重複，用排最前面的那一列」和「1 筆對不到主檔，補上的欄位是空的」；`Z` 那一列的 `category` 是空的，`A` 的 `owner` 是「王小明」。名稱預設是「退貨（補產品主檔）」，按「建立」。說明列是「查表：退貨 ← 產品主檔（product = code） · 6 個欄位 · 1 個敏感」，`owner` 的開關停用，提示指到「產品主檔」。

- [ ] **Step 8: 巢狀**

「新增來源 → 合併 → 查表」：主來源「全公司營收」、主檔「產品主檔」、對接 `product` = `code`，只勾 `category`。預覽共 9 筆，只有「主檔有 1 個對接值重複…」一則提示。建立，名稱用預設的「全公司營收（補產品主檔）」。確認 `memo` 的開關停用，提示寫的是「北區營收」，不是「全公司營收」。

- [ ] **Step 9: 拿巢狀的合併來源去製圖**

在「全公司營收（補產品主檔）」按「用這個來源製圖」，到製圖頁按「分析」（接真的 JEV），最多等 180 秒，直到預覽出現圖表。確認沒有錯誤訊息，至少一張圖用到 `category` 或 `來源`。存成「B1 驗證」，到儀表板頁打開它，圖表都畫得出來，沒有「欄位不見了」之類的錯誤。

分別在 Step 6、7、9 的關鍵畫面 `take_screenshot` 一張，附在報告裡。

- [ ] **Step 10: 收尾**

只關自己的分頁。背景的後端和前端程序停不掉時，用 `netstat -ano | grep -E ':(8080|5180) .*LISTENING'` 找出 PID，寫在報告裡請使用者關（`! taskkill //PID <pid> //F`）。

驗證中有修正程式的話，重跑 Step 2 的兩組測試，照 Global Constraints 的格式 commit 修正。最後 commit 測試用的 CSV：

```bash
git add docs/superpowers/e2e/merged-datasource/north.csv docs/superpowers/e2e/merged-datasource/south.csv docs/superpowers/e2e/merged-datasource/returns.csv docs/superpowers/e2e/merged-datasource/products.csv && git commit -m "test(e2e): 合併來源手動驗證用的四份 CSV" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- docs/superpowers/e2e/merged-datasource/north.csv docs/superpowers/e2e/merged-datasource/south.csv docs/superpowers/e2e/merged-datasource/returns.csv docs/superpowers/e2e/merged-datasource/products.csv
```

報告照 Step 5–9 逐項寫「通過／不通過（實際看到什麼）」，附上這次的使用者 id 和截圖，並註明 schema `jev_dashboard_e2e_b1` 裡的測試資料還留著。
