# JEV 儀表板後端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-23-jev-dashboard-design.md` 的 Spring Boot 後端：接資料（上傳檔案、外部 API）、兩輪 JEV 分析組出儀表板配置、萬用轉換器產生 ChartData、個人儀表板 CRUD、載入時的異常檢查。

**Architecture:** 單一 Maven module，依職責分四個 package：`datasource`、`transform`、`jev`、`dashboard`。會影響圖表結果的邏輯（檔案解析、型別推斷、轉換器、`ConfigAssembler`、異常摘要）都寫成純函式，不碰資料庫也能測。資料庫用 `JdbcClient` 直接寫 SQL，配置和資料列存 JSONB。

**Tech Stack:** Java 21、Spring Boot 3.5.16（web、jdbc、cache）、Flyway、PostgreSQL 17、Apache POI 5.5.1、commons-csv 1.14.1、JUnit 5 + AssertJ + MockMvc。

前端另有一份計畫：`docs/superpowers/plans/2026-09-24-jev-dashboard-frontend.md`，它依賴這份計畫做出來的 API。

## Global Constraints

- Java 21，Spring Boot 3.x（固定 3.5.16），單一 Maven module，放在 `backend/`。
- PostgreSQL schema `jev_dashboard`；測試用 `jev_dashboard_test`。Flyway 設定 `schemas`、`create-schemas: true`、`table: jev_flyway_history`。
- JEV 端點 `POST https://api.typesafe.ai/v1/systemone`，Bearer token 讀 `TYPESAFE_API_KEY`，模型固定 `jev-1.13.0`（設定鍵 `jev.model`）。
- JEV 逾時：連線 5 秒；讀取時分析用 30 秒、異常檢查用 10 秒。外部 API 讀取逾時 10 秒。
- 門檻：選圖和角色題 confidence 低於 0.5 就捨棄；`noul` 大於 0.7 才標註。
- question key 用索引：`imp_i`、`role_i`、`chart_count`、`combo_j`、`anomaly_<chartId>`、`severity_<chartId>`。
- 上傳上限 100,000 筆（`jev.upload.max-rows`）。外部 API 只允許 `jev.datasource.allowed-hosts` 裡的 host，解析到 loopback 一律拒絕。
- 使用者識別用 `X-User-Id` header；讀寫別人的資源回 404。
- 錯誤回應格式固定為 `{"error": "訊息"}`。
- `.env` 放在 `backend/`，靠 `spring.config.import: optional:file:.env[.properties]` 讀取，不進 git。
- 本機是 Windows + Git Bash；指令都在 Git Bash 裡跑。不要用 `python3`，不要寫 `> NUL`。

## File Structure

```
.gitignore
docker/postgres/Dockerfile
backend/
  pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
  .env.example
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__init.sql
  src/main/java/jev/dashboard/
    JevDashboardApplication.java   啟動、@EnableCaching
    JevProperties.java             jev.* 設定
    ApiException.java              帶 HTTP 狀態的例外
    ApiExceptionHandler.java       轉成 {"error": ...}
    datasource/
      FieldType.java, FieldSchema.java, ParsedTable.java, UploadParseException.java
      CsvParser.java, ExcelParser.java, SchemaInference.java
      Datasource.java, DatasourceRepository.java
      DataSourceProvider.java, UploadDataSourceProvider.java, HttpApiDataSourceProvider.java
      ApiUrlGuard.java, DatasourceService.java, DatasourceController.java
    transform/
      Mapping.java, ChartSpec.java, ChartData.java, TimeBucket.java, UniversalTransformer.java
    jev/
      Question.java, Answer.java, JevClient.java, JevConfig.java
      ConfigAssembler.java, AnalyzeService.java
      HighlightSummarizer.java, Highlight.java, HighlightService.java
      JevController.java
    dashboard/
      DashboardConfig.java, DashboardRepository.java, DashboardService.java
      DashboardDataService.java, DashboardController.java
  src/test/resources/application-test.yml
  src/test/resources/sales.csv
  src/test/resources/jev/round1.json, round2.json, highlight.json
  src/test/java/jev/dashboard/
    ApplicationTest.java, TestData.java, Fixtures.java, FakeJevServer.java
    datasource/CsvParserTest.java, ExcelParserTest.java, SchemaInferenceTest.java
    datasource/ApiUrlGuardTest.java, HttpApiDataSourceProviderTest.java, DatasourceControllerTest.java
    transform/UniversalTransformerTest.java
    jev/JevClientTest.java, ConfigAssemblerTest.java, AnalyzeServiceTest.java
    jev/HighlightSummarizerTest.java, HighlightServiceTest.java, JevControllerTest.java
    dashboard/DashboardControllerTest.java
```

---

### Task 1: 工具鏈、專案骨架、資料庫 migration

**Files:**
- Create: `.gitignore`、`docker/postgres/Dockerfile`
- Create: `backend/pom.xml`、`backend/.env.example`、`backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/jev/dashboard/JevDashboardApplication.java`、`JevProperties.java`、`ApiException.java`、`ApiExceptionHandler.java`
- Test: `backend/src/test/resources/application-test.yml`、`backend/src/test/java/jev/dashboard/ApplicationTest.java`

**Interfaces:**
- Produces: `JevProperties(String apiUrl, String apiKey, String model, Upload upload, Datasource datasource)`，內含 `Upload(int maxRows)`、`Datasource(List<String> allowedHosts)`；`ApiException(HttpStatus status, String message)` 和 `status()`；資料表 `datasource`、`datasource_rows`、`dashboard`。

- [ ] **Step 1: 安裝 JDK 21 並設定 JAVA_HOME**

這台機器沒有 Java。在 PowerShell 執行：

```powershell
winget install --id Microsoft.OpenJDK.21 -e --accept-source-agreements --accept-package-agreements
$jdk = (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
```

之後開新的 Git Bash，確認：

Run: `"$JAVA_HOME/bin/java" -version`
Expected: 輸出含 `openjdk version "21`

- [ ] **Step 2: 下載 Maven（只用來產生 wrapper）**

```bash
mkdir -p ~/tools
curl -L -o ~/tools/maven.zip https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
unzip -q -o ~/tools/maven.zip -d ~/tools
~/tools/apache-maven-3.9.16/bin/mvn -v
```

Expected: `Apache Maven 3.9.16`，`Java version: 21`

- [ ] **Step 3: 寫 `.gitignore`、`pom.xml`、Dockerfile**

`.gitignore`（repo 根目錄）：

```gitignore
backend/.env
backend/target/
frontend/node_modules/
frontend/dist/
```

`docker/postgres/Dockerfile`：

```dockerfile
# schema 和表由應用程式啟動時的 Flyway 建立，這裡不放 init.sql
FROM postgres:17
```

`backend/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>
  <groupId>jev</groupId>
  <artifactId>jev-dashboard</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <java.version>21</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-cache</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
      <version>5.5.1</version>
    </dependency>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.14.1</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: 產生 Maven wrapper**

```bash
cd backend && ~/tools/apache-maven-3.9.16/bin/mvn -q -N wrapper:wrapper -Dmaven=3.9.16
ls mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties
```

Expected: 三個檔案都在。之後一律用 `./mvnw`。

- [ ] **Step 5: 寫設定檔和 `.env`**

`backend/.env.example`：

```properties
TYPESAFE_API_KEY=
DB_URL=jdbc:postgresql://localhost:5432/postgres
DB_USER=postgres
DB_PASSWORD=
```

複製成 `backend/.env`：`cp backend/.env.example backend/.env`。**向使用者要本機 Postgres 的密碼填進 `DB_PASSWORD`**（JEV key 先留空，Task 9 之前的測試都不需要）。不要自己猜密碼。

`backend/src/main/resources/application.yml`：

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/postgres}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:}
    hikari:
      schema: jev_dashboard
  flyway:
    schemas: jev_dashboard
    create-schemas: true
    table: jev_flyway_history
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

jev:
  api-url: https://api.typesafe.ai/v1/systemone
  api-key: ${TYPESAFE_API_KEY:}
  model: jev-1.13.0
  upload:
    max-rows: 100000
  datasource:
    allowed-hosts: []
```

`backend/src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    hikari:
      schema: jev_dashboard_test
  flyway:
    schemas: jev_dashboard_test

jev:
  api-key: test-key
  upload:
    max-rows: 20
  datasource:
    allowed-hosts: [10.0.0.5]
```

- [ ] **Step 6: 寫共用類別**

`backend/src/main/java/jev/dashboard/JevDashboardApplication.java`：

```java
package jev.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
public class JevDashboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(JevDashboardApplication.class, args);
    }
}
```

`backend/src/main/java/jev/dashboard/JevProperties.java`：

```java
package jev.dashboard;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jev")
public record JevProperties(String apiUrl, String apiKey, String model, Upload upload, Datasource datasource) {
    public record Upload(int maxRows) {}

    public record Datasource(List<String> allowedHosts) {}
}
```

`backend/src/main/java/jev/dashboard/ApiException.java`：

```java
package jev.dashboard;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
```

`backend/src/main/java/jev/dashboard/ApiExceptionHandler.java`：

```java
package jev.dashboard;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 7: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/ApplicationTest.java`：

```java
package jev.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void flywayCreatesTablesInTestSchema() {
        List<String> tables = jdbc.sql("""
                select table_name from information_schema.tables
                where table_schema = 'jev_dashboard_test' order by table_name""")
            .query(String.class).list();
        assertThat(tables).contains("dashboard", "datasource", "datasource_rows", "jev_flyway_history");
    }
}
```

- [ ] **Step 8: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=ApplicationTest`
Expected: FAIL。還沒有 migration，`tables` 裡沒有 `dashboard`、`datasource`。

如果錯誤是連不上資料庫或密碼錯誤，先修好 `.env` 再繼續，不要往下做。

- [ ] **Step 9: 寫 migration**

`backend/src/main/resources/db/migration/V1__init.sql`：

```sql
create table datasource (
    id               uuid primary key,
    name             text        not null,
    kind             text        not null check (kind in ('upload', 'api')),
    schema_json      jsonb       not null,
    api_url          text,
    api_headers_json jsonb       not null default '{}',
    created_by       text        not null,
    created_at       timestamptz not null default now()
);

create table datasource_rows (
    datasource_id uuid  not null references datasource (id) on delete cascade,
    row_no        int   not null,
    row_json      jsonb not null,
    primary key (datasource_id, row_no)
);

create table dashboard (
    id            uuid primary key,
    name          text        not null,
    datasource_id uuid        not null references datasource (id),
    created_by    text        not null,
    config_json   jsonb       not null,
    updated_at    timestamptz not null default now()
);

create index dashboard_created_by on dashboard (created_by);
create index datasource_created_by on datasource (created_by);
```

- [ ] **Step 10: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=ApplicationTest`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add .gitignore docker/postgres/Dockerfile backend/pom.xml backend/mvnw backend/mvnw.cmd backend/.mvn backend/.env.example backend/src
git commit -m "feat(backend): project skeleton, config, Flyway schema"
```

---

### Task 2: CSV 與 Excel 解析

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/ParsedTable.java`、`UploadParseException.java`、`CsvParser.java`、`ExcelParser.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/CsvParserTest.java`、`ExcelParserTest.java`

**Interfaces:**
- Consumes: `ApiException`（Task 1）
- Produces: `record ParsedTable(List<String> headers, List<Map<String, Object>> rows)`；`CsvParser.parse(byte[]) → ParsedTable`（值都是 `String`）；`ExcelParser.parse(InputStream, String sheetName) → ParsedTable`（數字是 `Double`、日期是 ISO 字串、其他是 `String`）；`UploadParseException(String message)`，HTTP 400。

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/CsvParserTest.java`：

```java
package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class CsvParserTest {
    @Test
    void parsesUtf8WithBom() {
        byte[] bytes = ("\uFEFF" + "name,amount\n北區,100\n南區,200\n").getBytes(UTF_8);
        ParsedTable t = CsvParser.parse(bytes);
        assertThat(t.headers()).containsExactly("name", "amount");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(0)).containsEntry("name", "北區").containsEntry("amount", "100");
    }

    @Test
    void fallsBackToMs950WhenNotUtf8() {
        byte[] bytes = "地區,金額\n北區,100\n".getBytes(Charset.forName("MS950"));
        ParsedTable t = CsvParser.parse(bytes);
        assertThat(t.headers()).containsExactly("地區", "金額");
        assertThat(t.rows().get(0)).containsEntry("地區", "北區");
    }

    @Test
    void reportsLineNumberOfRowWithWrongColumnCount() {
        byte[] bytes = "a,b\n1,2\n3\n".getBytes(UTF_8);
        assertThatThrownBy(() -> CsvParser.parse(bytes))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("第 3 行");
    }
}
```

`backend/src/test/java/jev/dashboard/datasource/ExcelParserTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelParserTest {
    static byte[] workbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("銷售");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("order_date");
            h.createCell(1).setCellValue("amount");
            h.createCell(2).setCellValue("region");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Row r = s.createRow(1);
            Cell d = r.createCell(0);
            d.setCellValue(LocalDate.of(2024, 1, 5));
            d.setCellStyle(dateStyle);
            r.createCell(1).setCellValue(1200);
            r.createCell(2).setCellValue("北區");
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsFirstSheetWithDatesAndNumbers() throws Exception {
        ParsedTable t = ExcelParser.parse(new ByteArrayInputStream(workbook()), null);
        assertThat(t.headers()).containsExactly("order_date", "amount", "region");
        assertThat(t.rows()).hasSize(1);
        assertThat(t.rows().get(0))
            .containsEntry("order_date", "2024-01-05")
            .containsEntry("amount", 1200.0)
            .containsEntry("region", "北區");
    }

    @Test
    void readsNamedSheet() throws Exception {
        ParsedTable t = ExcelParser.parse(new ByteArrayInputStream(workbook()), "銷售");
        assertThat(t.rows()).hasSize(1);
    }

    @Test
    void missingSheetIsAnError() {
        assertThatThrownBy(() -> ExcelParser.parse(new ByteArrayInputStream(workbook()), "不存在"))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("找不到工作表");
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest='CsvParserTest,ExcelParserTest'`
Expected: FAIL，編譯錯誤 `cannot find symbol: class CsvParser`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/datasource/ParsedTable.java`：

```java
package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;

public record ParsedTable(List<String> headers, List<Map<String, Object>> rows) {}
```

`backend/src/main/java/jev/dashboard/datasource/UploadParseException.java`：

```java
package jev.dashboard.datasource;

import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;

public class UploadParseException extends ApiException {
    public UploadParseException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/CsvParser.java`：

```java
package jev.dashboard.datasource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class CsvParser {
    private static final Charset MS950 = Charset.forName("MS950");

    private CsvParser() {}

    public static ParsedTable parse(byte[] bytes) {
        String text = decode(bytes);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).get();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            List<String> headers = parser.getHeaderNames();
            List<Map<String, Object>> rows = new ArrayList<>();
            // ponytail: 行號 = 資料列序號 + 2，儲存格內有換行時會算錯，遇到再改用 parser.getCurrentLineNumber()
            int line = 1;
            for (CSVRecord record : parser) {
                line++;
                if (record.size() != headers.size()) {
                    throw new UploadParseException("第 " + line + " 行有 " + record.size() + " 欄，標題列是 " + headers.size() + " 欄");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), record.get(i));
                rows.add(row);
            }
            return new ParsedTable(headers, rows);
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new UploadParseException("CSV 解析失敗：" + e.getMessage());
        }
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, MS950);
        }
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/ExcelParser.java`：

```java
package jev.dashboard.datasource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class ExcelParser {
    private ExcelParser() {}

    public static ParsedTable parse(InputStream in, String sheetName) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = sheetName == null || sheetName.isBlank() ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) throw new UploadParseException("找不到工作表：" + sheetName);
            Row head = sheet.getRow(sheet.getFirstRowNum());
            if (head == null) throw new UploadParseException("第 1 行沒有標題列");
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < head.getLastCellNum(); c++) {
                String name = String.valueOf(value(head.getCell(c))).trim();
                if (name.isEmpty()) throw new UploadParseException("第 1 行第 " + (c + 1) + " 欄的標題是空的");
                headers.add(name);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = head.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                boolean hasValue = false;
                for (int c = 0; c < headers.size(); c++) {
                    Object v = value(row.getCell(c));
                    values.put(headers.get(c), v);
                    if (!"".equals(v)) hasValue = true;
                }
                if (hasValue) rows.add(values);
            }
            return new ParsedTable(headers, rows);
        } catch (UploadParseException e) {
            throw e;
        } catch (Exception e) {
            throw new UploadParseException("Excel 解析失敗：" + e.getMessage());
        }
    }

    static Object value(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : (Object) cell.getNumericCellValue();
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest='CsvParserTest,ExcelParserTest'`
Expected: PASS（6 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): CSV (UTF-8/MS950) and Excel upload parsing"
```

---

### Task 3: 欄位型別推斷與正規化

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/FieldType.java`、`FieldSchema.java`、`SchemaInference.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/SchemaInferenceTest.java`

**Interfaces:**
- Consumes: `ParsedTable`（Task 2）
- Produces:
  - `enum FieldType { NUMBER, DATE, STRING }`，JSON 值是 `"number"`、`"date"`、`"string"`
  - `record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples, boolean sensitive, String min, String max)`，加上 `withSensitive(boolean)`。`min`、`max` 只有 DATE 欄位有值，是 ISO 日期字串。
  - `SchemaInference.infer(List<String> headers, List<Map<String, Object>> rows) → List<FieldSchema>`
  - `SchemaInference.normalize(List<Map<String, Object>> rows, List<FieldSchema> schema) → List<Map<String, Object>>`：NUMBER 轉 `Double`、DATE 轉 ISO 日期字串、STRING 轉 `String`、空白轉 `null`。原始列沒有的欄位就不放 key（用來判斷「欄位遺失」）。

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/SchemaInferenceTest.java`：

```java
package jev.dashboard.datasource;

import static jev.dashboard.datasource.FieldType.DATE;
import static jev.dashboard.datasource.FieldType.NUMBER;
import static jev.dashboard.datasource.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaInferenceTest {
    static Map<String, Object> row(Object d, Object a, Object r, Object m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("d", d);
        row.put("a", a);
        row.put("r", r);
        row.put("m", m);
        return row;
    }

    static final List<Map<String, Object>> ROWS = List.of(
        row("2024/01/05", "1,200", "北區", ""),
        row("2024-06-20", "800", "南區", "x"),
        row("2024-03-01 13:45:00", "800", "北區", ""));

    @Test
    void infersTypesDistinctCountSamplesAndDateRange() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        assertThat(s.get(0)).isEqualTo(new FieldSchema("d", DATE, 3,
            List.of("2024/01/05", "2024-06-20", "2024-03-01 13:45:00"), false, "2024-01-05", "2024-06-20"));
        assertThat(s.get(1).type()).isEqualTo(NUMBER);
        assertThat(s.get(1).distinctCount()).isEqualTo(2);
        assertThat(s.get(2).type()).isEqualTo(STRING);
        assertThat(s.get(2).distinctCount()).isEqualTo(2);
        assertThat(s.get(3).type()).isEqualTo(STRING);
        assertThat(s.get(3).samples()).containsExactly("x");
    }

    @Test
    void excelNumbersStayNumbers() {
        List<FieldSchema> s = SchemaInference.infer(List.of("a"), List.of(Map.of("a", 1200.0)));
        assertThat(s.get(0).type()).isEqualTo(NUMBER);
    }

    @Test
    void normalizeConvertsValuesByType() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        Map<String, Object> first = SchemaInference.normalize(ROWS, s).get(0);
        assertThat(first).containsEntry("d", "2024-01-05").containsEntry("a", 1200.0).containsEntry("r", "北區");
        assertThat(first).containsKey("m");
        assertThat(first.get("m")).isNull();
    }

    @Test
    void normalizeLeavesOutFieldsMissingFromTheRow() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        Map<String, Object> normalized = SchemaInference.normalize(List.of(Map.of("a", "5")), s).get(0);
        assertThat(normalized).containsOnlyKeys("a");
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaInferenceTest`
Expected: FAIL，編譯錯誤 `cannot find symbol: class FieldSchema`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/datasource/FieldType.java`：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FieldType {
    NUMBER("number"), DATE("date"), STRING("string");

    private final String json;

    FieldType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/FieldSchema.java`：

```java
package jev.dashboard.datasource;

import java.util.List;

public record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                          boolean sensitive, String min, String max) {
    public FieldSchema withSensitive(boolean value) {
        return new FieldSchema(name, type, distinctCount, samples, value, min, max);
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/SchemaInference.java`：

```java
package jev.dashboard.datasource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SchemaInference {
    // ponytail: 只取日期部分，時間捨去；分桶最細到 day，夠用
    private static final Pattern DATE = Pattern.compile(
        "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");

    private SchemaInference() {}

    public static List<FieldSchema> infer(List<String> headers, List<Map<String, Object>> rows) {
        List<FieldSchema> out = new ArrayList<>();
        for (String h : headers) {
            List<Object> values = rows.stream().map(r -> r.get(h)).filter(v -> !isBlank(v)).toList();
            FieldType type = values.isEmpty() ? FieldType.STRING
                : values.stream().allMatch(v -> parseNumber(v) != null) ? FieldType.NUMBER
                : values.stream().allMatch(v -> parseDate(v) != null) ? FieldType.DATE
                : FieldType.STRING;
            Set<String> distinct = new LinkedHashSet<>();
            values.forEach(v -> distinct.add(String.valueOf(v)));
            String min = null;
            String max = null;
            if (type == FieldType.DATE) {
                List<LocalDate> dates = values.stream().map(SchemaInference::parseDate).sorted().toList();
                min = dates.getFirst().toString();
                max = dates.getLast().toString();
            }
            out.add(new FieldSchema(h, type, distinct.size(), distinct.stream().limit(5).toList(), false, min, max));
        }
        return out;
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> rows, List<FieldSchema> schema) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (FieldSchema f : schema) {
                if (!row.containsKey(f.name())) continue;
                Object v = row.get(f.name());
                m.put(f.name(), isBlank(v) ? null : switch (f.type()) {
                    case NUMBER -> parseNumber(v);
                    case DATE -> {
                        LocalDate d = parseDate(v);
                        yield d == null ? null : d.toString();
                    }
                    case STRING -> String.valueOf(v);
                });
            }
            out.add(m);
        }
        return out;
    }

    static boolean isBlank(Object v) {
        return v == null || (v instanceof String s && s.isBlank());
    }

    static Double parseNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim().replace(",", "");
        return NUMBER.matcher(s).matches() ? Double.valueOf(s) : null;
    }

    static LocalDate parseDate(Object v) {
        Matcher m = DATE.matcher(String.valueOf(v).trim());
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaInferenceTest`
Expected: PASS（4 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): field type inference and row normalization"
```

---

### Task 4: 萬用轉換器

**Files:**
- Create: `backend/src/main/java/jev/dashboard/transform/Mapping.java`、`ChartSpec.java`、`ChartData.java`、`TimeBucket.java`、`UniversalTransformer.java`
- Test: `backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java`

**Interfaces:**
- Consumes: 正規化後的資料列（Task 3 的 `normalize` 輸出）
- Produces:
  - `record Mapping(String x, String y, String series, String value, List<String> measures, List<String> columns, String aggregate, String timeGrain)`，JSON 省略 null
  - `record ChartSpec(String chartId, String type, String title, ChartSpec.GridPos gridPos, Mapping mapping, Map<String, Object> options)`，內含 `record GridPos(int x, int y, int w, int h)`
  - `record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows, String error, String missingField)`，JSON 省略 null；`ChartData.missing(chartId, type, field)` 產生 `error = "FIELD_MISSING"`
  - `UniversalTransformer.transform(ChartSpec, List<Map<String, Object>> rows, Set<String> availableFields) → ChartData`
  - `UniversalTransformer.defaultAggregate(String type) → "none" | "sum"`
  - 各圖表的 `rows` 格式：bar/line/pie 是 `[x, value]`；stacked_bar 是 `[x, series, value]`；heatmap 是 `[x, y, value]`；radar 是 `[series, m1, m2, ...]`；scatter 是 `[x, y]`；table 是 `columns` 的值。`aggregate = count` 且沒有 value 時，dimensions 最後一個是 `"count"`。

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/transform/UniversalTransformerTest.java`：

```java
package jev.dashboard.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jev.dashboard.transform.ChartSpec.GridPos;
import org.junit.jupiter.api.Test;

class UniversalTransformerTest {
    static Map<String, Object> row(String d, Double amount, String region, String product, Double qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_date", d);
        m.put("amount", amount);
        m.put("region", region);
        m.put("product", product);
        m.put("qty", qty);
        return m;
    }

    static final List<Map<String, Object>> ROWS = List.of(
        row("2024-01-05", 100.0, "北", "A", 1.0),
        row("2024-01-20", 50.0, "南", "A", 2.0),
        row("2024-02-03", 200.0, "北", "B", 3.0),
        row("2024-04-10", null, "南", "B", 4.0));
    static final Set<String> FIELDS = Set.of("order_date", "amount", "region", "product", "qty");

    static ChartData run(String type, Mapping m) {
        return UniversalTransformer.transform(
            new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4), m, Map.of()), ROWS, FIELDS);
    }

    static Mapping xv(String x, String value, String aggregate, String grain) {
        return new Mapping(x, null, null, value, null, null, aggregate, grain);
    }

    @Test
    void lineBucketsByMonthAndSkipsNullValues() {
        ChartData d = run("line", xv("order_date", "amount", "sum", "month"));
        assertThat(d.dimensions()).containsExactly("order_date", "amount");
        assertThat(d.rows()).isEqualTo(List.of(List.of("2024-01", 150.0), List.of("2024-02", 200.0)));
    }

    @Test
    void lineByDay() {
        ChartData d = run("line", xv("order_date", "amount", "sum", "day"));
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("2024-01-05", 100.0), List.of("2024-01-20", 50.0), List.of("2024-02-03", 200.0)));
    }

    @Test
    void barDefaultsToSumAndSortsByValueDescending() {
        ChartData d = run("bar", xv("region", "amount", null, null));
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 300.0), List.of("南", 50.0)));
    }

    @Test
    void barAverage() {
        ChartData d = run("bar", xv("region", "amount", "avg", null));
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 150.0), List.of("南", 50.0)));
    }

    @Test
    void pieCountsRowsWithoutValueField() {
        ChartData d = run("pie", xv("product", null, "count", null));
        assertThat(d.dimensions()).containsExactly("product", "count");
        assertThat(d.rows()).isEqualTo(List.of(List.of("A", 2.0), List.of("B", 2.0)));
    }

    @Test
    void scatterDefaultsToNoAggregationAndSkipsNulls() {
        ChartData d = run("scatter", new Mapping("amount", "qty", null, null, null, null, null, null));
        assertThat(d.rows()).isEqualTo(List.of(List.of(100.0, 1.0), List.of(50.0, 2.0), List.of(200.0, 3.0)));
    }

    @Test
    void tableProjectsColumns() {
        ChartData d = run("table", new Mapping(null, null, null, null, null, List.of("order_date", "region"), null, null));
        assertThat(d.dimensions()).containsExactly("order_date", "region");
        assertThat(d.rows()).hasSize(4);
        assertThat(d.rows().get(0)).isEqualTo(List.of("2024-01-05", "北"));
    }

    @Test
    void radarAggregatesEachMeasurePerSeries() {
        ChartData d = run("radar", new Mapping(null, null, "region", null, List.of("amount", "qty"), null, null, null));
        assertThat(d.dimensions()).containsExactly("region", "amount", "qty");
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 300.0, 4.0), List.of("南", 50.0, 6.0)));
    }

    @Test
    void heatmapGroupsByTwoKeysInFirstSeenOrder() {
        ChartData d = run("heatmap", new Mapping("region", "product", null, "amount", null, null, null, null));
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("北", "A", 100.0), List.of("南", "A", 50.0), List.of("北", "B", 200.0)));
    }

    @Test
    void stackedBarByQuarterSortsByTimeThenSeries() {
        ChartData d = run("stacked_bar", new Mapping("order_date", null, "region", "amount", null, null, null, "quarter"));
        assertThat(d.dimensions()).containsExactly("order_date", "region", "amount");
        assertThat(d.rows()).isEqualTo(List.of(List.of("2024-Q1", "北", 300.0), List.of("2024-Q1", "南", 50.0)));
    }

    @Test
    void missingFieldIsReportedInsteadOfThrowing() {
        ChartData d = run("bar", xv("region", "profit", null, null));
        assertThat(d.error()).isEqualTo("FIELD_MISSING");
        assertThat(d.missingField()).isEqualTo("profit");
        assertThat(d.rows()).isNull();
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=UniversalTransformerTest`
Expected: FAIL，編譯錯誤 `cannot find symbol: class Mapping`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/transform/Mapping.java`：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mapping(String x, String y, String series, String value, List<String> measures,
                      List<String> columns, String aggregate, String timeGrain) {}
```

`backend/src/main/java/jev/dashboard/transform/ChartSpec.java`：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                        Map<String, Object> options) {
    public record GridPos(int x, int y, int w, int h) {}
}
```

`backend/src/main/java/jev/dashboard/transform/ChartData.java`：

```java
package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField) {
    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }
}
```

`backend/src/main/java/jev/dashboard/transform/TimeBucket.java`：

```java
package jev.dashboard.transform;

import java.time.LocalDate;
import java.time.YearMonth;

final class TimeBucket {
    private TimeBucket() {}

    static String of(Object value, String grain) {
        if (value == null) return null;
        LocalDate d = LocalDate.parse(String.valueOf(value));
        return switch (grain) {
            case "day" -> d.toString();
            case "month" -> YearMonth.from(d).toString();
            case "quarter" -> d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
            default -> throw new IllegalArgumentException("未知的 timeGrain：" + grain);
        };
    }
}
```

`backend/src/main/java/jev/dashboard/transform/UniversalTransformer.java`：

```java
package jev.dashboard.transform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ponytail: 在 Java 記憶體裡聚合，上限是 jev.upload.max-rows（10 萬筆）；資料再大就改成 SQL 的 jsonb 運算
public final class UniversalTransformer {
    // ponytail: 明細表只回前 500 筆，要看全部時再加分頁
    static final int TABLE_ROW_LIMIT = 500;

    private UniversalTransformer() {}

    public static String defaultAggregate(String type) {
        return type.equals("scatter") || type.equals("table") ? "none" : "sum";
    }

    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields) {
        Mapping m = spec.mapping();
        for (String field : referencedFields(m)) {
            if (!availableFields.contains(field)) return ChartData.missing(spec.chartId(), spec.type(), field);
        }
        String agg = m.aggregate() != null ? m.aggregate() : defaultAggregate(spec.type());
        return switch (spec.type()) {
            case "bar", "line", "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
            case "stacked_bar" -> grouped(spec, rows, List.of(m.x(), m.series()), m.value(), agg, m.timeGrain());
            case "heatmap" -> grouped(spec, rows, List.of(m.x(), m.y()), m.value(), agg, m.timeGrain());
            case "radar" -> radar(spec, rows, m.series(), m.measures(), agg.equals("none") ? "sum" : agg);
            case "scatter" -> project(spec, rows, List.of(m.x(), m.y()), true, Integer.MAX_VALUE);
            case "table" -> project(spec, rows, m.columns(), false, TABLE_ROW_LIMIT);
            default -> throw new IllegalArgumentException("未知的圖表類型：" + spec.type());
        };
    }

    static List<String> referencedFields(Mapping m) {
        List<String> out = new ArrayList<>();
        Stream.of(m.x(), m.y(), m.series(), m.value()).filter(Objects::nonNull).forEach(out::add);
        if (m.measures() != null) out.addAll(m.measures());
        if (m.columns() != null) out.addAll(m.columns());
        return out;
    }

    private static ChartData grouped(ChartSpec spec, List<Map<String, Object>> rows, List<String> keys,
                                     String value, String agg, String grain) {
        List<String> dims = new ArrayList<>(keys);
        dims.add(value != null ? value : "count");
        if (agg.equals("none")) return project(spec, rows, dims, true, Integer.MAX_VALUE);

        Map<List<Object>, double[]> acc = new LinkedHashMap<>(); // [加總, 筆數]
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                Object v = row.get(keys.get(i));
                key.add(i == 0 && grain != null ? TimeBucket.of(v, grain) : v);
            }
            if (key.contains(null)) continue;
            Object v = value == null ? null : row.get(value);
            if (!agg.equals("count") && !(v instanceof Number)) continue;
            double[] a = acc.computeIfAbsent(key, k -> new double[2]);
            if (v instanceof Number n) a[0] += n.doubleValue();
            a[1]++;
        }

        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>(key);
            r.add(result(agg, a));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> sortKey(r, keys.size())));
        } else if (keys.size() == 1) {
            out.sort(Comparator.comparingDouble((List<Object> r) -> (Double) r.get(1)).reversed());
        }
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }

    private static ChartData radar(ChartSpec spec, List<Map<String, Object>> rows, String series,
                                   List<String> measures, String agg) {
        Map<Object, double[][]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object s = row.get(series);
            if (s == null) continue;
            double[][] a = acc.computeIfAbsent(s, k -> new double[measures.size()][2]);
            for (int i = 0; i < measures.size(); i++) {
                Object v = row.get(measures.get(i));
                if (v instanceof Number n) a[i][0] += n.doubleValue();
                if (v instanceof Number || agg.equals("count")) a[i][1]++;
            }
        }
        List<List<Object>> out = new ArrayList<>();
        acc.forEach((s, a) -> {
            List<Object> r = new ArrayList<>();
            r.add(s);
            for (double[] x : a) r.add(result(agg, x));
            out.add(r);
        });
        List<String> dims = new ArrayList<>();
        dims.add(series);
        dims.addAll(measures);
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }

    private static ChartData project(ChartSpec spec, List<Map<String, Object>> rows, List<String> fields,
                                     boolean skipNulls, int limit) {
        List<List<Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (out.size() >= limit) break;
            List<Object> r = new ArrayList<>(fields.size());
            for (String f : fields) r.add(row.get(f));
            if (skipNulls && r.contains(null)) continue;
            out.add(r);
        }
        return new ChartData(spec.chartId(), spec.type(), List.copyOf(fields), out, null, null);
    }

    private static double result(String agg, double[] a) {
        return switch (agg) {
            case "sum" -> a[0];
            case "count" -> a[1];
            case "avg" -> a[1] == 0 ? 0 : a[0] / a[1];
            default -> throw new IllegalArgumentException("未知的 aggregate：" + agg);
        };
    }

    private static String sortKey(List<Object> row, int keyCount) {
        return row.subList(0, keyCount).stream().map(String::valueOf).collect(Collectors.joining("\u0000"));
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=UniversalTransformerTest`
Expected: PASS（11 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): UniversalTransformer for eight chart types"
```

---

### Task 5: 資料源儲存與上傳 API

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/Datasource.java`、`DatasourceRepository.java`、`DataSourceProvider.java`、`UploadDataSourceProvider.java`、`DatasourceService.java`、`DatasourceController.java`
- Create: `backend/src/test/resources/sales.csv`、`backend/src/test/java/jev/dashboard/TestData.java`
- Test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`

**Interfaces:**
- Consumes: `CsvParser`、`ExcelParser`（Task 2）；`SchemaInference`、`FieldSchema`（Task 3）；`JevProperties`、`ApiException`（Task 1）
- Produces:
  - `record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl, Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt)`，加上 `withSchema(List<FieldSchema>)`
  - `interface DataSourceProvider { boolean supports(String kind); List<Map<String, Object>> fetchRows(Datasource ds); }`，其他系統實作這個介面就能接自家資料
  - `DatasourceService`：`upload(userId, name, MultipartFile, sheet)`、`list(userId)`、`get(userId, id)`（找不到回 404）、`markSensitive(userId, id, Collection<String>)`、`load(userId, id) → Loaded(Datasource datasource, List<Map<String, Object>> rows)`（資料列已正規化）、`checkRows(int)`、`provider(kind)`
  - REST：`POST /api/datasources/upload`（multipart：`file`、`name`、`sheet` 選填）、`GET /api/datasources`、`PATCH /api/datasources/{id}/sensitive`（body `{"fields": [...]}`）
  - 測試工具 `TestData.clean(JdbcClient)`、`TestData.uploadSales(MockMvc, userId) → String datasourceId`

- [ ] **Step 1: 建立測試資料和工具**

`backend/src/test/resources/sales.csv`（UTF-8）：

```csv
order_date,amount,region,product,qty,memo
2024-01-05,1200,北區,A,3,首購
2024-01-18,800,南區,B,2,備註一
2024-02-03,1500,北區,A,5,備註二
2024-02-20,600,中區,C,1,備註三
2024-03-11,2200,東區,B,7,備註四
2024-03-25,900,南區,A,2,備註五
2024-04-02,1700,北區,C,4,備註六
2024-04-28,1100,中區,B,3,備註七
2024-05-09,2500,東區,A,8,備註八
2024-05-30,700,南區,C,2,備註九
2024-06-14,3000,北區,B,9,備註十
2024-06-20,1300,中區,A,4,備註十一
```

`backend/src/test/java/jev/dashboard/TestData.java`：

```java
package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

public final class TestData {
    private TestData() {}

    public static void clean(JdbcClient jdbc) {
        jdbc.sql("truncate dashboard, datasource_rows, datasource").update();
    }

    public static String uploadSales(MockMvc mvc, String userId) throws Exception {
        byte[] csv;
        try (InputStream in = TestData.class.getResourceAsStream("/sales.csv")) {
            csv = in.readAllBytes();
        }
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", "sales.csv", "text/csv", csv))
                .param("name", "銷售")
                .header("X-User-Id", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }
}
```

- [ ] **Step 2: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`：

```java
package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasourceControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(UTF_8));
    }

    @Test
    void uploadInfersSchemaStoresRowsAndIsPrivateToTheUser() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].kind").value("upload"))
            .andExpect(jsonPath("$[0].schema[0].name").value("order_date"))
            .andExpect(jsonPath("$[0].schema[0].type").value("date"))
            .andExpect(jsonPath("$[0].schema[0].min").value("2024-01-05"))
            .andExpect(jsonPath("$[0].schema[1].type").value("number"))
            .andExpect(jsonPath("$[0].schema[2].distinctCount").value(4));
        assertThat(jdbc.sql("select count(*) from datasource_rows").query(Long.class).single()).isEqualTo(12L);
        mvc.perform(get("/api/datasources").header("X-User-Id", "u2"))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void markSensitiveFields() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema[5].sensitive").value(true))
            .andExpect(jsonPath("$.schema[0].sensitive").value(false));
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void badCsvReportsLineNumber() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("bad.csv", "a,b\n1,2\n3\n"))
                .param("name", "壞檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("第 3 行")));
    }

    @Test
    void tooManyRowsIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("a\n");
        for (int i = 0; i < 21; i++) sb.append(i).append('\n');
        mvc.perform(multipart("/api/datasources/upload").file(csv("big.csv", sb.toString()))
                .param("name", "大檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("超過上限 20 筆")));
    }

    @Test
    void unsupportedExtensionIsRejected() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("data.txt", "a\n1\n"))
                .param("name", "文字檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=DatasourceControllerTest`
Expected: FAIL。上傳端點還不存在，`uploadSales` 拿到 404。

- [ ] **Step 4: 實作**

`backend/src/main/java/jev/dashboard/datasource/Datasource.java`：

```java
package jev.dashboard.datasource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt);
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/DatasourceRepository.java`：

```java
package jev.dashboard.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DatasourceRepository {
    private static final TypeReference<List<FieldSchema>> SCHEMA = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json;

    public DatasourceRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    public void insert(Datasource d) {
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by)
                values (:id, :name, :kind, cast(:schema as jsonb), :apiUrl, cast(:headers as jsonb), :createdBy)""")
            .param("id", d.id())
            .param("name", d.name())
            .param("kind", d.kind())
            .param("schema", write(d.schema()))
            .param("apiUrl", d.apiUrl())
            .param("headers", write(d.apiHeaders() == null ? Map.of() : d.apiHeaders()))
            .param("createdBy", d.createdBy())
            .update();
    }

    public Optional<Datasource> find(UUID id, String userId) {
        return jdbc.sql("select * from datasource where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    public List<Datasource> list(String userId) {
        return jdbc.sql("select * from datasource where created_by = :by order by created_at desc")
            .param("by", userId)
            .query(this::map).list();
    }

    public void updateSchema(UUID id, List<FieldSchema> schema) {
        jdbc.sql("update datasource set schema_json = cast(:schema as jsonb) where id = :id")
            .param("schema", write(schema)).param("id", id)
            .update();
    }

    public void insertRows(UUID id, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) args.add(new Object[] {id, i, write(rows.get(i))});
        jdbcTemplate.batchUpdate(
            "insert into datasource_rows (datasource_id, row_no, row_json) values (?, ?, cast(? as jsonb))", args);
    }

    public List<Map<String, Object>> rows(UUID id) {
        return jdbc.sql("select row_json from datasource_rows where datasource_id = :id order by row_no")
            .param("id", id)
            .query(String.class).list()
            .stream().map(s -> read(s, ROW)).toList();
    }

    private Datasource map(ResultSet rs, int rowNum) throws SQLException {
        return new Datasource(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("kind"),
            read(rs.getString("schema_json"), SCHEMA),
            rs.getString("api_url"),
            read(rs.getString("api_headers_json"), HEADERS),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/DataSourceProvider.java`：

```java
package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;

/** 宿主系統實作這個介面、註冊成 Spring bean，就能接自家的資料來源。 */
public interface DataSourceProvider {
    boolean supports(String kind);

    /** 回傳原始資料列；上傳檔案的資料列在存檔時已經正規化，其他來源由 DatasourceService 依 schema 正規化。 */
    List<Map<String, Object>> fetchRows(Datasource ds);
}
```

`backend/src/main/java/jev/dashboard/datasource/UploadDataSourceProvider.java`：

```java
package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UploadDataSourceProvider implements DataSourceProvider {
    private final DatasourceRepository repo;

    public UploadDataSourceProvider(DatasourceRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean supports(String kind) {
        return "upload".equals(kind);
    }

    @Override
    public List<Map<String, Object>> fetchRows(Datasource ds) {
        return repo.rows(ds.id());
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/DatasourceService.java`：

```java
package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasourceService {
    public record Loaded(Datasource datasource, List<Map<String, Object>> rows) {}

    private final DatasourceRepository repo;
    private final List<DataSourceProvider> providers;
    private final int maxRows;

    public DatasourceService(DatasourceRepository repo, List<DataSourceProvider> providers, JevProperties props) {
        this.repo = repo;
        this.providers = providers;
        this.maxRows = props.upload().maxRows();
    }

    @Transactional
    public Datasource upload(String userId, String name, MultipartFile file, String sheet) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        ParsedTable table;
        try {
            if (filename.endsWith(".csv")) table = CsvParser.parse(file.getBytes());
            else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) table = ExcelParser.parse(file.getInputStream(), sheet);
            else throw new ApiException(BAD_REQUEST, "只支援 .csv、.xlsx、.xls");
        } catch (IOException e) {
            throw new UploadParseException("讀取檔案失敗：" + e.getMessage());
        }
        checkRows(table.rows().size());
        List<FieldSchema> schema = SchemaInference.infer(table.headers(), table.rows());
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), userId, null);
        repo.insert(ds);
        repo.insertRows(ds.id(), SchemaInference.normalize(table.rows(), schema));
        return get(userId, ds.id());
    }

    public List<Datasource> list(String userId) {
        return repo.list(userId);
    }

    public Datasource get(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(() -> new ApiException(NOT_FOUND, "找不到資料源"));
    }

    public Datasource markSensitive(String userId, UUID id, Collection<String> fields) {
        Datasource ds = get(userId, id);
        List<FieldSchema> schema = ds.schema().stream().map(f -> f.withSensitive(fields.contains(f.name()))).toList();
        repo.updateSchema(id, schema);
        return ds.withSchema(schema);
    }

    public Loaded load(String userId, UUID id) {
        Datasource ds = get(userId, id);
        List<Map<String, Object>> raw = provider(ds.kind()).fetchRows(ds);
        return new Loaded(ds, "upload".equals(ds.kind()) ? raw : SchemaInference.normalize(raw, ds.schema()));
    }

    void checkRows(int count) {
        if (count == 0) throw new ApiException(BAD_REQUEST, "沒有資料列");
        if (count > maxRows) throw new ApiException(BAD_REQUEST, "資料有 " + count + " 筆，超過上限 " + maxRows + " 筆");
    }

    DataSourceProvider provider(String kind) {
        return providers.stream().filter(p -> p.supports(kind)).findFirst()
            .orElseThrow(() -> new IllegalStateException("沒有處理 " + kind + " 的 DataSourceProvider"));
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/DatasourceController.java`：

```java
package jev.dashboard.datasource;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {
    public record SensitiveFields(List<String> fields) {}

    private final DatasourceService service;

    public DatasourceController(DatasourceService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Datasource upload(@RequestHeader("X-User-Id") String userId,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam("name") String name,
                             @RequestParam(value = "sheet", required = false) String sheet) {
        return service.upload(userId, name, file, sheet);
    }

    @GetMapping
    public List<Datasource> list(@RequestHeader("X-User-Id") String userId) {
        return service.list(userId);
    }

    @PatchMapping("/{id}/sensitive")
    public Datasource sensitive(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                @RequestBody SensitiveFields body) {
        return service.markSensitive(userId, id, body.fields() == null ? List.of() : body.fields());
    }
}
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=DatasourceControllerTest`
Expected: PASS（5 個測試）

- [ ] **Step 6: 跑全部測試**

Run: `cd backend && ./mvnw -q test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(backend): datasource storage, upload endpoint, sensitive fields"
```

---

### Task 6: 外部 API 資料源

**Files:**
- Create: `backend/src/main/java/jev/dashboard/datasource/ApiUrlGuard.java`、`HttpApiDataSourceProvider.java`
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceService.java`（建構子、加 `registerApi`）
- Modify: `backend/src/main/java/jev/dashboard/datasource/DatasourceController.java`（加 `POST /api`）
- Test: `backend/src/test/java/jev/dashboard/datasource/ApiUrlGuardTest.java`、`HttpApiDataSourceProviderTest.java`
- Modify test: `backend/src/test/java/jev/dashboard/datasource/DatasourceControllerTest.java`

**Interfaces:**
- Consumes: `Datasource`、`DataSourceProvider`、`DatasourceService`（Task 5）
- Produces: `ApiUrlGuard.check(String url) → URI`（違規時丟 400 的 `ApiException`）；`HttpApiDataSourceProvider.validateHeaders(Map<String, String>)`；`DatasourceService.registerApi(userId, name, url, headers) → Datasource`；REST `POST /api/datasources/api`（body `{"name","url","headers"}`）

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/datasource/ApiUrlGuardTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import jev.dashboard.ApiException;
import org.junit.jupiter.api.Test;

class ApiUrlGuardTest {
    @Test
    void emptyAllowListRejectsEverything() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of()).check("http://10.0.0.5/api"))
            .isInstanceOf(ApiException.class).hasMessageContaining("未開放");
    }

    @Test
    void hostNotInListIsRejected() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("10.0.0.5")).check("https://evil.example/x"))
            .hasMessageContaining("不在允許清單");
    }

    @Test
    void listedPrivateIpIsAllowed() {
        assertThat(new ApiUrlGuard(List.of("10.0.0.5")).check("http://10.0.0.5:8080/api/sales").getHost())
            .isEqualTo("10.0.0.5");
    }

    @Test
    void loopbackIsRejectedEvenWhenListed() {
        ApiUrlGuard guard = new ApiUrlGuard(List.of("127.0.0.1", "localhost", "0.0.0.0"));
        assertThatThrownBy(() -> guard.check("http://127.0.0.1:8080/x")).hasMessageContaining("本機位址");
        assertThatThrownBy(() -> guard.check("http://localhost/x")).hasMessageContaining("本機位址");
        assertThatThrownBy(() -> guard.check("http://0.0.0.0/x")).hasMessageContaining("本機位址");
    }

    @Test
    void nonHttpSchemeIsRejected() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("10.0.0.5")).check("ftp://10.0.0.5/x"))
            .hasMessageContaining("只允許 http 或 https");
    }
}
```

`backend/src/test/java/jev/dashboard/datasource/HttpApiDataSourceProviderTest.java`：

```java
package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HttpApiDataSourceProviderTest {
    @Test
    void headerValuesMustBeEnvReferences() {
        assertThatThrownBy(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("Authorization", "Bearer abc")))
            .hasMessageContaining("${環境變數名}");
        assertThatCode(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("Authorization", "${ERP_API_TOKEN}")))
            .doesNotThrowAnyException();
    }

    @Test
    void resolvesHeadersFromEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("ERP_API_TOKEN", "Bearer t0k");
        HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), env);
        assertThat(provider.resolveHeaders(Map.of("Authorization", "${ERP_API_TOKEN}")))
            .containsEntry("Authorization", "Bearer t0k");
        assertThatThrownBy(() -> provider.resolveHeaders(Map.of("X-Key", "${MISSING_VAR}")))
            .hasMessageContaining("MISSING_VAR");
    }
}
```

在 `DatasourceControllerTest` 的 class 最後加兩個測試，並補上 `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;`：

```java
    @Test
    void registerApiRejectsHostNotInAllowList() throws Exception {
        mvc.perform(post("/api/datasources/api").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"https://evil.example/sales\",\"headers\":{}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("不在允許清單")));
    }

    @Test
    void registerApiRejectsLiteralHeaderValues() throws Exception {
        mvc.perform(post("/api/datasources/api").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"http://10.0.0.5/sales\",\"headers\":{\"Authorization\":\"Bearer x\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("${環境變數名}")));
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest='ApiUrlGuardTest,HttpApiDataSourceProviderTest,DatasourceControllerTest'`
Expected: FAIL，編譯錯誤 `cannot find symbol: class ApiUrlGuard`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/datasource/ApiUrlGuard.java`：

```java
package jev.dashboard.datasource;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 允許清單就是 SSRF 的防線。ERP API 多半在內網，所以私有 IP 不擋，只擋 loopback。
 * ponytail: 檢查和實際連線各解析一次 DNS，擋不了 DNS rebinding；要防就把解析結果釘在連線上
 */
@Component
public class ApiUrlGuard {
    private final Set<String> allowedHosts;

    @Autowired
    public ApiUrlGuard(JevProperties props) {
        this(props.datasource() == null ? null : props.datasource().allowedHosts());
    }

    ApiUrlGuard(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? Set.of()
            : allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public URI check(String url) {
        if (url == null) throw bad("網址是空的");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw bad("網址格式錯誤：" + url);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw bad("只允許 http 或 https");
        String host = uri.getHost();
        if (host == null) throw bad("網址沒有 host：" + url);
        if (allowedHosts.isEmpty()) throw bad("未開放註冊外部 API：jev.datasource.allowed-hosts 是空的");
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) throw bad("host 不在允許清單：" + host);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw bad("無法解析 host：" + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isAnyLocalAddress()) throw bad("host 解析到本機位址：" + host);
        }
        return uri;
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
```

`backend/src/main/java/jev/dashboard/datasource/HttpApiDataSourceProvider.java`：

```java
package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jev.dashboard.ApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpApiDataSourceProvider implements DataSourceProvider {
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final RestClient http;
    private final ApiUrlGuard guard;
    private final Environment env;

    public HttpApiDataSourceProvider(ApiUrlGuard guard, Environment env) {
        // JDK HttpClient 預設不跟隨 redirect，符合 spec
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.guard = guard;
        this.env = env;
    }

    @Override
    public boolean supports(String kind) {
        return "api".equals(kind);
    }

    @Override
    public List<Map<String, Object>> fetchRows(Datasource ds) {
        URI uri = guard.check(ds.apiUrl());
        Map<String, String> headers = resolveHeaders(ds.apiHeaders());
        try {
            List<Map<String, Object>> rows = http.get().uri(uri)
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return rows == null ? List.of() : rows;
        } catch (RestClientException e) {
            throw new ApiException(BAD_GATEWAY, "外部 API 呼叫失敗（回應必須是 JSON 陣列）：" + e.getMessage());
        }
    }

    public static void validateHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (value == null || !ENV_REF.matcher(value).matches()) {
                throw new ApiException(BAD_REQUEST, "header「" + name + "」的值只能寫 ${環境變數名}");
            }
        });
    }

    Map<String, String> resolveHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) return out;
        validateHeaders(headers);
        headers.forEach((name, value) -> {
            Matcher m = ENV_REF.matcher(value);
            m.matches();
            String resolved = env.getProperty(m.group(1));
            if (resolved == null) throw new ApiException(INTERNAL_SERVER_ERROR, "環境變數 " + m.group(1) + " 未設定");
            out.put(name, resolved);
        });
        return out;
    }
}
```

修改 `DatasourceService.java`：加欄位 `private final ApiUrlGuard guard;`，建構子改成：

```java
    public DatasourceService(DatasourceRepository repo, List<DataSourceProvider> providers, JevProperties props,
                             ApiUrlGuard guard) {
        this.repo = repo;
        this.providers = providers;
        this.maxRows = props.upload().maxRows();
        this.guard = guard;
    }
```

在 `list` 方法前面加：

```java
    public Datasource registerApi(String userId, String name, String url, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        guard.check(url);
        HttpApiDataSourceProvider.validateHeaders(h);
        Datasource draft = new Datasource(UUID.randomUUID(), name, "api", List.of(), url, h, userId, null);
        List<Map<String, Object>> raw = provider("api").fetchRows(draft);
        checkRows(raw.size());
        Datasource ds = draft.withSchema(SchemaInference.infer(new ArrayList<>(raw.get(0).keySet()), raw));
        repo.insert(ds);
        return get(userId, ds.id());
    }
```

import 加 `java.util.ArrayList`。

修改 `DatasourceController.java`：加 record 和端點（import 加 `java.util.Map`）：

```java
    public record ApiRegistration(String name, String url, Map<String, String> headers) {}

    @PostMapping("/api")
    public Datasource registerApi(@RequestHeader("X-User-Id") String userId, @RequestBody ApiRegistration body) {
        return service.registerApi(userId, body.name(), body.url(), body.headers());
    }
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest='ApiUrlGuardTest,HttpApiDataSourceProviderTest,DatasourceControllerTest'`
Expected: PASS（5 + 2 + 7 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): external API datasource with host allow-list and env-var headers"
```

---

### Task 7: JEV client

**Files:**
- Create: `backend/src/main/java/jev/dashboard/jev/Question.java`、`Answer.java`、`JevClient.java`、`JevConfig.java`
- Create: `backend/src/test/resources/jev/round1.json`、`round2.json`、`highlight.json`
- Create: `backend/src/test/java/jev/dashboard/Fixtures.java`、`FakeJevServer.java`
- Test: `backend/src/test/java/jev/dashboard/jev/JevClientTest.java`

**Interfaces:**
- Consumes: `JevProperties`、`ApiException`（Task 1）；`FieldSchema`（Task 3）
- Produces:
  - `record Question(String type, String instructions, Object criteria)`，加上 `Question.choice(String, Map<String, String>)`、`Question.score(String, List<String>)`、`Question.noul(String)`
  - `record Answer(String type, String choice, Double score, Double confidence, Double noul)`
  - `JevClient(String apiUrl, String apiKey, String model, Duration readTimeout)`；`ask(Object state, Map<String, Question> questions) → Map<String, Answer>`。沒設 key 時丟 503，JEV 失敗或逾時丟 502。
  - bean `analyzeJevClient`（讀取逾時 30 秒）、`highlightJevClient`（10 秒）
  - 測試工具：`FakeJevServer.start(Function<String, String> responder)`（responder 回 `null` 代表回 500）、`url()`、`bodies`、`auths`、`route(String body)`；`Fixtures.json(name)`、`Fixtures.answers(name)`、`Fixtures.SALES_SCHEMA`

- [ ] **Step 1: 建立 fixture 和測試工具**

`backend/src/test/resources/jev/round1.json`（第一輪：欄位索引對應 `sales.csv` 的欄位順序）：

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
    "chart_count": {"type": "score", "score": 2.2, "confidence": 0.6}
  },
  "usage": {"input_tokens": 1200, "output_tokens": 90}
}
```

`backend/src/test/resources/jev/round2.json`（第二輪：combo 順序見 Task 8 的排序測試）：

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "combo_0": {"type": "choice", "choice": "line", "confidence": 0.95},
    "combo_1": {"type": "choice", "choice": "stacked_bar", "confidence": 0.9},
    "combo_2": {"type": "choice", "choice": "none", "confidence": 0.8},
    "combo_3": {"type": "choice", "choice": "heatmap", "confidence": 0.4},
    "combo_4": {"type": "choice", "choice": "bar", "confidence": 0.7},
    "combo_5": {"type": "choice", "choice": "pie", "confidence": 0.85}
  },
  "usage": {"input_tokens": 900, "output_tokens": 40}
}
```

`backend/src/test/resources/jev/highlight.json`：

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "anomaly_c1": {"type": "noul", "noul": 0.85},
    "severity_c1": {"type": "score", "score": 2.4, "confidence": 0.8},
    "anomaly_c2": {"type": "noul", "noul": 0.3},
    "severity_c2": {"type": "score", "score": 0.2, "confidence": 0.9}
  },
  "usage": {"input_tokens": 400, "output_tokens": 20}
}
```

`backend/src/test/java/jev/dashboard/Fixtures.java`：

```java
package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.Answer;

public final class Fixtures {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 對應 sales.csv 的 schema；memo 標為敏感。 */
    public static final List<FieldSchema> SALES_SCHEMA = List.of(
        new FieldSchema("order_date", FieldType.DATE, 12, List.of("2024-01-05"), false, "2024-01-05", "2024-06-20"),
        new FieldSchema("amount", FieldType.NUMBER, 12, List.of("1200"), false, null, null),
        new FieldSchema("region", FieldType.STRING, 4, List.of("北區"), false, null, null),
        new FieldSchema("product", FieldType.STRING, 3, List.of("A"), false, null, null),
        new FieldSchema("qty", FieldType.NUMBER, 8, List.of("3"), false, null, null),
        new FieldSchema("memo", FieldType.STRING, 12, List.of("機密備註"), true, null, null));

    private Fixtures() {}

    public static String json(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/jev/" + name)) {
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Answer> answers(String name) {
        try {
            return JSON.convertValue(JSON.readTree(json(name)).get("answers"), new TypeReference<Map<String, Answer>>() {});
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`backend/src/test/java/jev/dashboard/FakeJevServer.java`：

```java
package jev.dashboard;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 用錄好的 JEV 回應當 fixture 的本機假伺服器，不打真的 API。 */
public final class FakeJevServer implements AutoCloseable {
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<String> auths = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    private FakeJevServer(Function<String, String> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            auths.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = responder.apply(body);
            byte[] out = (response == null ? "{\"error\":\"boom\"}" : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response == null ? 500 : 200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    public static FakeJevServer start(Function<String, String> responder) {
        try {
            return new FakeJevServer(responder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 依請求內容挑 fixture：異常檢查、第二輪、第一輪。 */
    public static String route(String body) {
        if (body.contains("\"anomaly_")) return Fixtures.json("highlight.json");
        if (body.contains("\"combo_0\"")) return Fixtures.json("round2.json");
        return Fixtures.json("round1.json");
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/jev/JevClientTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import jev.dashboard.ApiException;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class JevClientTest {
    static final Map<String, Question> ONE = Map.of("imp_0", Question.score("重要嗎？", List.of("低", "高")));

    @Test
    void sendsModelStateAndQuestionsAndParsesAnswers() {
        try (FakeJevServer server = FakeJevServer.start(body -> Fixtures.json("round1.json"))) {
            JevClient client = new JevClient(server.url(), "k-123", "jev-1.13.0", Duration.ofSeconds(5));
            Map<String, Answer> a = client.ask(Map.of("rowCount", 12), ONE);
            assertThat(a.get("role_0").choice()).isEqualTo("time");
            assertThat(a.get("role_0").confidence()).isEqualTo(0.95);
            assertThat(a.get("imp_1").score()).isEqualTo(2.75);
            assertThat(server.auths).containsExactly("Bearer k-123");
            assertThat(server.bodies.get(0))
                .contains("\"model\":\"jev-1.13.0\"")
                .contains("\"rowCount\":12")
                .contains("\"imp_0\"")
                .contains("\"type\":\"score\"")
                .contains("\"criteria\":[\"低\",\"高\"]");
        }
    }

    @Test
    void serverErrorBecomesBadGateway() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.ask(Map.of(), ONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("JEV 呼叫失敗")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
    }

    @Test
    void missingApiKeyIsServiceUnavailable() {
        JevClient client = new JevClient("http://127.0.0.1:1/v1/systemone", "", "jev-1.13.0", Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.ask(Map.of(), ONE))
            .hasMessageContaining("TYPESAFE_API_KEY")
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=JevClientTest`
Expected: FAIL，編譯錯誤 `cannot find symbol: class Answer`

- [ ] **Step 4: 實作**

`backend/src/main/java/jev/dashboard/jev/Question.java`：

```java
package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Question(String type, String instructions, Object criteria) {
    public static Question choice(String instructions, Map<String, String> criteria) {
        return new Question("choice", instructions, criteria);
    }

    public static Question score(String instructions, List<String> levels) {
        return new Question("score", instructions, levels);
    }

    public static Question noul(String instructions) {
        return new Question("noul", instructions, null);
    }
}
```

`backend/src/main/java/jev/dashboard/jev/Answer.java`：

```java
package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** score 是從 0 起算的小數（4 個選項回 0 到 3）；noul 只有 noul 題才有值。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Answer(String type, String choice, Double score, Double confidence, Double noul) {}
```

`backend/src/main/java/jev/dashboard/jev/JevClient.java`：

```java
package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class JevClient {
    record Request(String model, Object state, Map<String, Question> questions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String model, Map<String, Answer> answers) {}

    private final RestClient http;
    private final String apiUrl;
    private final String model;
    private final boolean configured;

    public JevClient(String apiUrl, String apiKey, String model, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.apiUrl = apiUrl;
        this.model = model;
        this.configured = apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Answer> ask(Object state, Map<String, Question> questions) {
        if (!configured) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "未設定 TYPESAFE_API_KEY");
        try {
            Response r = http.post().uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, state, questions))
                .retrieve()
                .body(Response.class);
            if (r == null || r.answers() == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗：沒有 answers");
            return r.answers();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗：" + e.getMessage());
        }
    }
}
```

`backend/src/main/java/jev/dashboard/jev/JevConfig.java`：

```java
package jev.dashboard.jev;

import java.time.Duration;
import jev.dashboard.JevProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JevConfig {
    @Bean
    JevClient analyzeJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(30));
    }

    @Bean
    JevClient highlightJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(10));
    }
}
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=JevClientTest`
Expected: PASS（3 個測試）

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(backend): JEV client with recorded-fixture fake server"
```

---

### Task 8: ConfigAssembler（純函式）

**Files:**
- Create: `backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`
- Test: `backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`

**Interfaces:**
- Consumes: `FieldSchema`、`FieldType`（Task 3）；`Mapping`、`ChartSpec`（Task 4）；`Answer`（Task 7）
- Produces:
  - `record ConfigAssembler.FieldEval(FieldSchema field, double importance, String role)`，加上 `name()`
  - `record ConfigAssembler.Combo(List<FieldEval> fields, List<String> chartTypes, double score)`，加上 `byRole(String)`
  - `evaluate(List<FieldSchema>, Map<String, Answer>) → List<FieldEval>`（讀 `imp_i`、`role_i`）
  - `chartCount(Answer) → int`（`round(score) + 1`，限制在 1 到 6）
  - `candidates(List<FieldEval>) → List<FieldEval>`
  - `combos(List<FieldEval> candidates, int k) → List<Combo>`（最多 2k 組，照平均重要性排序）
  - `assemble(List<Combo>, Map<String, Answer> round2, int k, List<String> tableColumns) → List<ChartSpec>`（讀 `combo_j`）
  - `timeGrain(FieldSchema) → "day" | "month" | "quarter"`
  - `CHART_TYPES_ZH`：圖表類型的中文說明，給 AnalyzeService 出題用

測試資料的預期排序（importance：order_date 3、amount 2.75、region 2、product 1.25、qty 1）：

| combo | 欄位 | 平均 | 合法圖表 |
|---|---|---|---|
| 0 | order_date, amount | 2.875 | line, bar |
| 1 | order_date, amount, region | 2.583 | stacked_bar, heatmap |
| 2 | amount, region | 2.375 | bar, pie |
| 3 | order_date, amount, product | 2.333 | stacked_bar, heatmap |
| 4 | order_date, qty | 2.0 | line, bar |
| 5 | amount, product | 2.0 | bar, pie |

同分 2.0 的還有 (order_date, region, qty) 和 (amount, region, product)，但兩欄組合先產生，排序又是穩定的，所以 combo_4、combo_5 是兩欄組合。

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/jev/ConfigAssemblerTest.java`：

```java
package jev.dashboard.jev;

import static jev.dashboard.datasource.FieldType.DATE;
import static jev.dashboard.datasource.FieldType.NUMBER;
import static jev.dashboard.datasource.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jev.dashboard.Fixtures;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class ConfigAssemblerTest {
    static final List<String> TABLE_COLUMNS = List.of("order_date", "amount", "region", "product", "qty");

    final List<FieldEval> evals = ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, Fixtures.answers("round1.json"));

    static Answer score(Double s) {
        return new Answer("score", null, s, 0.9, null);
    }

    static FieldEval eval(String name, FieldType type, int distinct, double importance, String role) {
        return new FieldEval(new FieldSchema(name, type, distinct, List.of(), false, null, null), importance, role);
    }

    static List<String> names(Combo c) {
        return c.fields().stream().map(FieldEval::name).toList();
    }

    @Test
    void evaluateReadsImportanceAndRole() {
        assertThat(evals).extracting(FieldEval::name, FieldEval::importance, FieldEval::role).containsExactly(
            tuple("order_date", 3.0, "time"), tuple("amount", 2.75, "measure"), tuple("region", 2.0, "dimension"),
            tuple("product", 1.25, "dimension"), tuple("qty", 1.0, "measure"), tuple("memo", 0.25, "ignore"));
    }

    @Test
    void evaluateIgnoresLowConfidenceAndTypeMismatchedRoles() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("role_2", new Answer("choice", "dimension", null, 0.4, null));
        a.put("role_3", new Answer("choice", "measure", null, 0.9, null)); // product 是字串，不能當 measure
        List<FieldEval> e = ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a);
        assertThat(e.get(2).role()).isEqualTo("ignore");
        assertThat(e.get(3).role()).isEqualTo("ignore");
        assertThat(e.get(0).role()).isEqualTo("time");
    }

    @Test
    void chartCountIsRoundedScorePlusOneWithin1To6() {
        assertThat(ConfigAssembler.chartCount(score(2.2))).isEqualTo(3);
        assertThat(ConfigAssembler.chartCount(score(0.0))).isEqualTo(1);
        assertThat(ConfigAssembler.chartCount(score(5.4))).isEqualTo(6);
        assertThat(ConfigAssembler.chartCount(score(5.6))).isEqualTo(6);
        assertThat(ConfigAssembler.chartCount(null)).isEqualTo(1);
    }

    @Test
    void candidatesSkipIgnoredAndUnimportantFields() {
        assertThat(ConfigAssembler.candidates(evals)).extracting(FieldEval::name)
            .containsExactly("order_date", "amount", "region", "product", "qty");
    }

    @Test
    void combosAreRankedByAverageImportance() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        assertThat(combos).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("order_date", "amount"),
            List.of("order_date", "amount", "region"),
            List.of("amount", "region"),
            List.of("order_date", "amount", "product"),
            List.of("order_date", "qty"),
            List.of("amount", "product"));
        assertThat(combos).extracting(Combo::chartTypes).containsExactly(
            List.of("line", "bar"),
            List.of("stacked_bar", "heatmap"),
            List.of("bar", "pie"),
            List.of("stacked_bar", "heatmap"),
            List.of("line", "bar"),
            List.of("bar", "pie"));
    }

    @Test
    void pieNeedsAtMost8Categories() {
        List<Combo> combos = ConfigAssembler.combos(List.of(
            eval("customer", STRING, 20, 2, "dimension"), eval("amount", NUMBER, 50, 2, "measure")), 3);
        assertThat(combos.get(0).chartTypes()).containsExactly("bar");
    }

    @Test
    void radarNeedsThreeMeasuresAndAtMost5Categories() {
        List<FieldEval> cands = List.of(
            eval("channel", STRING, 3, 2, "dimension"),
            eval("m1", NUMBER, 9, 2, "measure"), eval("m2", NUMBER, 9, 2, "measure"), eval("m3", NUMBER, 9, 2, "measure"));
        assertThat(ConfigAssembler.combos(cands, 20)).anySatisfy(c -> {
            assertThat(c.chartTypes()).containsExactly("radar");
            assertThat(names(c)).containsExactly("channel", "m1", "m2", "m3");
        });
    }

    @Test
    void assemblePicksTopKAnsweredCombosAndLaysOutGrid() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        List<ChartSpec> layout = ConfigAssembler.assemble(combos, Fixtures.answers("round2.json"), 3, TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type).containsExactly(
            tuple("c1", "line"), tuple("c2", "stacked_bar"), tuple("c3", "bar"), tuple("c4", "table"));
        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("order_date", null, null, "amount", null, null, "sum", "month"));
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("order_date", null, "region", "amount", null, null, "sum", "month"));
        assertThat(layout.get(2).mapping()).isEqualTo(new Mapping("order_date", null, null, "qty", null, null, "sum", "month"));
        assertThat(layout.get(3).mapping()).isEqualTo(new Mapping(null, null, null, null, null, TABLE_COLUMNS, "none", null));
        assertThat(layout).extracting(ChartSpec::gridPos).containsExactly(
            new GridPos(0, 0, 6, 4), new GridPos(6, 0, 6, 4), new GridPos(0, 4, 6, 4), new GridPos(0, 8, 12, 5));
        assertThat(layout).extracting(ChartSpec::title).containsExactly(
            "amount 依 order_date", "amount 依 order_date 與 region", "qty 依 order_date", "明細");
    }

    @Test
    void noUsableChartLeavesOnlyTheTable() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        List<ChartSpec> layout = ConfigAssembler.assemble(combos, Map.of(), 3, TABLE_COLUMNS);
        assertThat(layout).extracting(ChartSpec::type).containsExactly("table");
        assertThat(layout.get(0).gridPos()).isEqualTo(new GridPos(0, 0, 12, 5));
    }

    @Test
    void timeGrainFollowsDateSpan() {
        assertThat(ConfigAssembler.timeGrain(date("2024-01-01", "2024-03-01"))).isEqualTo("day");
        assertThat(ConfigAssembler.timeGrain(date("2024-01-05", "2024-06-20"))).isEqualTo("month");
        assertThat(ConfigAssembler.timeGrain(date("2020-01-01", "2024-06-20"))).isEqualTo("quarter");
        assertThat(ConfigAssembler.timeGrain(date(null, null))).isEqualTo("month");
    }

    static FieldSchema date(String min, String max) {
        return new FieldSchema("d", DATE, 2, List.of(), false, min, max);
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: FAIL，編譯錯誤 `cannot find symbol: class ConfigAssembler`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/jev/ConfigAssembler.java`：

```java
package jev.dashboard.jev;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;

/** 把 JEV 的答案組成儀表板配置。全部是純函式，不呼叫 JEV。 */
public final class ConfigAssembler {
    public static final double MIN_CONFIDENCE = 0.5;
    static final int MAX_CANDIDATES = 8;

    public static final Map<String, String> CHART_TYPES_ZH = new LinkedHashMap<>();
    static {
        CHART_TYPES_ZH.put("bar", "長條圖：比較各分類或各期的數值");
        CHART_TYPES_ZH.put("line", "折線圖：看數值隨時間的變化");
        CHART_TYPES_ZH.put("pie", "圓餅圖：看各分類佔整體的比例");
        CHART_TYPES_ZH.put("scatter", "散佈圖：看兩個數值之間的關係");
        CHART_TYPES_ZH.put("radar", "雷達圖：比較少數幾個分類在多個指標上的表現");
        CHART_TYPES_ZH.put("heatmap", "熱力圖：看兩個維度交叉後的數值分布");
        CHART_TYPES_ZH.put("stacked_bar", "堆疊長條圖：比較各分類的組成");
    }

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

    private ConfigAssembler() {}

    public static List<FieldEval> evaluate(List<FieldSchema> schema, Map<String, Answer> answers) {
        List<FieldEval> out = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            FieldSchema f = schema.get(i);
            Answer imp = answers.get("imp_" + i);
            Answer role = answers.get("role_" + i);
            double importance = imp == null || imp.score() == null ? 0 : imp.score();
            String r = role == null || role.choice() == null || role.confidence() == null
                || role.confidence() < MIN_CONFIDENCE ? "ignore" : role.choice();
            if (r.equals("measure") && f.type() != FieldType.NUMBER) r = "ignore";
            if (r.equals("time") && f.type() != FieldType.DATE) r = "ignore";
            out.add(new FieldEval(f, importance, r));
        }
        return out;
    }

    public static int chartCount(Answer a) {
        if (a == null || a.score() == null) return 1;
        return (int) Math.max(1, Math.min(6, Math.round(a.score()) + 1));
    }

    public static List<FieldEval> candidates(List<FieldEval> evals) {
        return evals.stream()
            .filter(e -> !e.role().equals("ignore") && Math.round(e.importance()) >= 1)
            .sorted(Comparator.comparingDouble(FieldEval::importance).reversed())
            .limit(MAX_CANDIDATES)
            .toList();
    }

    public static List<Combo> combos(List<FieldEval> c, int k) {
        List<Combo> all = new ArrayList<>();
        int n = c.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) add(all, List.of(c.get(i), c.get(j)), pairTypes(c.get(i), c.get(j)));
        }
        List<FieldEval> measures = c.stream().filter(e -> e.role().equals("measure")).toList();
        if (measures.size() >= 3) {
            for (FieldEval d : c) {
                if (!d.role().equals("dimension") || d.field().distinctCount() > 5) continue;
                List<FieldEval> fields = new ArrayList<>();
                fields.add(d);
                fields.addAll(measures.subList(0, Math.min(8, measures.size())));
                add(all, fields, List.of("radar"));
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int l = j + 1; l < n; l++) {
                    add(all, List.of(c.get(i), c.get(j), c.get(l)), tripleTypes(c.get(i), c.get(j), c.get(l)));
                }
            }
        }
        all.sort(Comparator.comparingDouble(Combo::score).reversed()); // 穩定排序，同分照產生順序
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }

    public static List<ChartSpec> assemble(List<Combo> combos, Map<String, Answer> round2, int k,
                                           List<String> tableColumns) {
        List<ChartSpec> charts = new ArrayList<>();
        for (int i = 0; i < combos.size() && charts.size() < k; i++) {
            Answer a = round2.get("combo_" + i);
            if (a == null || a.choice() == null || a.choice().equals("none")) continue;
            if (a.confidence() == null || a.confidence() < MIN_CONFIDENCE) continue;
            if (!combos.get(i).chartTypes().contains(a.choice())) continue;
            Mapping m = mapping(a.choice(), combos.get(i));
            int n = charts.size();
            charts.add(new ChartSpec("c" + (n + 1), a.choice(), title(a.choice(), m),
                new GridPos((n % 2) * 6, (n / 2) * 4, 6, 4), m, Map.of()));
        }
        int rowsUsed = (charts.size() + 1) / 2;
        charts.add(new ChartSpec("c" + (charts.size() + 1), "table", "明細", new GridPos(0, rowsUsed * 4, 12, 5),
            new Mapping(null, null, null, null, null, tableColumns, "none", null), Map.of()));
        return charts;
    }

    public static String timeGrain(FieldSchema f) {
        if (f.min() == null || f.max() == null) return "month";
        LocalDate min = LocalDate.parse(f.min());
        LocalDate max = LocalDate.parse(f.max());
        if (ChronoUnit.DAYS.between(min, max) <= 90) return "day";
        return max.isAfter(min.plusYears(3)) ? "quarter" : "month";
    }

    static Mapping mapping(String type, Combo c) {
        List<FieldEval> t = c.byRole("time");
        List<FieldEval> d = c.byRole("dimension");
        List<FieldEval> m = c.byRole("measure");
        String time = t.isEmpty() ? null : t.get(0).name();
        String grain = t.isEmpty() ? null : timeGrain(t.get(0).field());
        return switch (type) {
            case "line", "bar" -> time != null
                ? new Mapping(time, null, null, m.get(0).name(), null, null, "sum", grain)
                : new Mapping(d.get(0).name(), null, null, m.get(0).name(), null, null, "sum", null);
            case "pie" -> new Mapping(d.get(0).name(), null, null, m.get(0).name(), null, null, "sum", null);
            case "scatter" -> new Mapping(m.get(0).name(), m.get(1).name(), null, null, null, null, "none", null);
            case "radar" -> new Mapping(null, null, d.get(0).name(), null, m.stream().map(FieldEval::name).toList(), null, "sum", null);
            case "stacked_bar" -> {
                if (time != null) yield new Mapping(time, null, d.get(0).name(), m.get(0).name(), null, null, "sum", grain);
                FieldEval series = d.get(0).field().distinctCount() < d.get(1).field().distinctCount() ? d.get(0) : d.get(1);
                FieldEval x = series == d.get(0) ? d.get(1) : d.get(0);
                yield new Mapping(x.name(), null, series.name(), m.get(0).name(), null, null, "sum", null);
            }
            case "heatmap" -> time != null
                ? new Mapping(time, d.get(0).name(), null, m.get(0).name(), null, null, "sum", grain)
                : new Mapping(d.get(0).name(), d.get(1).name(), null, m.get(0).name(), null, null, "sum", null);
            default -> throw new IllegalArgumentException("未知的圖表類型：" + type);
        };
    }

    static String title(String type, Mapping m) {
        if (type.equals("scatter")) return m.x() + " 與 " + m.y();
        if (type.equals("radar")) return m.series() + " 各指標比較";
        String second = m.series() != null ? m.series() : m.y();
        return m.value() + " 依 " + m.x() + (second == null ? "" : " 與 " + second);
    }

    private static void add(List<Combo> all, List<FieldEval> fields, List<String> types) {
        if (types.isEmpty()) return;
        all.add(new Combo(fields, types, fields.stream().mapToDouble(FieldEval::importance).average().orElse(0)));
    }

    private static String roles(FieldEval... fields) {
        return Stream.of(fields).map(FieldEval::role).sorted().collect(Collectors.joining(","));
    }

    private static List<String> pairTypes(FieldEval a, FieldEval b) {
        return switch (roles(a, b)) {
            case "measure,time" -> List.of("line", "bar");
            case "dimension,measure" -> {
                FieldEval dim = a.role().equals("dimension") ? a : b;
                yield dim.field().distinctCount() <= 8 ? List.of("bar", "pie") : List.of("bar");
            }
            case "measure,measure" -> List.of("scatter");
            default -> List.of();
        };
    }

    private static List<String> tripleTypes(FieldEval a, FieldEval b, FieldEval c) {
        int minDimDistinct = Stream.of(a, b, c).filter(f -> f.role().equals("dimension"))
            .mapToInt(f -> f.field().distinctCount()).min().orElse(Integer.MAX_VALUE);
        return switch (roles(a, b, c)) {
            case "dimension,measure,time", "dimension,dimension,measure" ->
                minDimDistinct <= 10 ? List.of("stacked_bar", "heatmap") : List.of("heatmap");
            default -> List.of();
        };
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=ConfigAssemblerTest`
Expected: PASS（10 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): ConfigAssembler builds dashboard config from JEV answers"
```

---

### Task 9: 兩輪分析 AnalyzeService 與 `/api/jev/analyze`

**Files:**
- Create: `backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java`
- Create: `backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`、`JevController.java`
- Test: `backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`、`JevControllerTest.java`

**Interfaces:**
- Consumes: `ConfigAssembler`（Task 8）；`JevClient`、`Question`、`Answer`（Task 7）；`DatasourceService.load`（Task 5）；`ChartSpec`（Task 4）
- Produces:
  - `record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout)`，JSON 省略 null
  - `AnalyzeService.analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount) → DashboardConfig`（id 是 null，不存檔）
  - REST `POST /api/jev/analyze`，body `{"datasourceId": "..."}`，回傳配置預覽
  - `JevControllerTest` 的 `@DynamicPropertySource` 設定，Task 11 會沿用

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/jev/AnalyzeServiceTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.transform.ChartSpec;
import org.junit.jupiter.api.Test;

class AnalyzeServiceTest {
    @Test
    void runsTwoRoundsAndSkipsSamplesOfSensitiveFields() {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            AnalyzeService service = new AnalyzeService(
                new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
            UUID ds = UUID.randomUUID();

            DashboardConfig c = service.analyze("銷售 總覽", ds, Fixtures.SALES_SCHEMA, 12);

            assertThat(c.id()).isNull();
            assertThat(c.name()).isEqualTo("銷售 總覽");
            assertThat(c.datasourceId()).isEqualTo(ds);
            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("line", "stacked_bar", "bar", "table");
            assertThat(server.bodies).hasSize(2);
            assertThat(server.bodies.get(0))
                .contains("\"imp_5\"").contains("\"role_5\"").contains("\"chart_count\"")
                .contains("\"rowCount\":12")
                .contains("memo").doesNotContain("機密備註")
                .contains("北區");
            assertThat(server.bodies.get(1))
                .contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("\"none\"");
        }
    }
}
```

`backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jev.dashboard.FakeJevServer;
import jev.dashboard.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JevControllerTest {
    static final FakeJevServer JEV = FakeJevServer.start(FakeJevServer::route);

    @DynamicPropertySource
    static void jevUrl(DynamicPropertyRegistry registry) {
        registry.add("jev.api-url", JEV::url);
    }

    @AfterAll
    static void stopJev() {
        JEV.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
        JEV.bodies.clear();
    }

    @Test
    void analyzeReturnsPreviewWithoutSaving() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").doesNotExist())
            .andExpect(jsonPath("$.name").value("銷售 總覽"))
            .andExpect(jsonPath("$.layout[*].type").value(contains("line", "stacked_bar", "bar", "table")))
            .andExpect(jsonPath("$.layout[0].mapping.timeGrain").value("month"));
        assertThat(jdbc.sql("select count(*) from dashboard").query(Long.class).single()).isZero();
        assertThat(JEV.bodies).hasSize(2);
    }

    @Test
    void analyzeOtherUsersDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isNotFound());
        assertThat(JEV.bodies).isEmpty();
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest'`
Expected: FAIL，編譯錯誤 `cannot find symbol: class AnalyzeService`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/dashboard/DashboardConfig.java`：

```java
package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout) {}
```

`backend/src/main/java/jev/dashboard/jev/AnalyzeService.java`：

```java
package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeService {
    static final List<String> IMPORTANCE = List.of("不重要，略過", "次要", "重要", "核心指標");
    static final List<String> CHART_COUNTS = List.of("1 張", "2 張", "3 張", "4 張", "5 張", "6 張");
    static final Map<String, String> ROLES = new LinkedHashMap<>();
    static final Map<String, String> ROLE_ZH = Map.of("dimension", "分類", "measure", "數值", "time", "時間");
    static {
        ROLES.put("dimension", "分類維度，用來分組，例如地區、產品");
        ROLES.put("measure", "數值指標，可以加總或平均，例如金額、數量");
        ROLES.put("time", "時間，例如訂單日期");
        ROLES.put("ignore", "忽略，例如流水號、備註");
    }

    private final JevClient jev;

    public AnalyzeService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount) {
        Map<String, Object> state = state(schema, rowCount);

        Map<String, Question> round1 = new LinkedHashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            String field = schema.get(i).name();
            round1.put("imp_" + i, Question.score("欄位「" + field + "」對分析這份資料有多重要？", IMPORTANCE));
            round1.put("role_" + i, Question.choice("欄位「" + field + "」在圖表裡適合扮演什麼角色？", ROLES));
        }
        round1.put("chart_count", Question.score("這份資料適合做成幾張圖表？", CHART_COUNTS));
        Map<String, Answer> a1 = jev.ask(state, round1);

        List<FieldEval> candidates = ConfigAssembler.candidates(ConfigAssembler.evaluate(schema, a1));
        int k = ConfigAssembler.chartCount(a1.get("chart_count"));
        List<Combo> combos = ConfigAssembler.combos(candidates, k);

        Map<String, Answer> a2 = Map.of();
        if (!combos.isEmpty()) {
            Map<String, Question> round2 = new LinkedHashMap<>();
            for (int j = 0; j < combos.size(); j++) {
                Combo c = combos.get(j);
                round2.put("combo_" + j, Question.choice(describe(c), criteria(c)));
            }
            a2 = jev.ask(state, round2);
        }

        List<String> tableColumns = (candidates.isEmpty()
            ? schema.stream().map(FieldSchema::name)
            : candidates.stream().map(FieldEval::name)).toList();
        return new DashboardConfig(null, name, datasourceId, null, ConfigAssembler.assemble(combos, a2, k, tableColumns));
    }

    static Map<String, Object> state(List<FieldSchema> schema, long rowCount) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("distinctCount", f.distinctCount());
            if (!f.sensitive()) m.put("samples", f.samples());
            fields.add(m);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("rowCount", rowCount);
        state.put("fields", fields);
        return state;
    }

    static String describe(Combo c) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        return "用這組欄位畫一張圖：" + fields + "。選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
    }

    static Map<String, String> criteria(Combo c) {
        Map<String, String> m = new LinkedHashMap<>();
        c.chartTypes().forEach(t -> m.put(t, ConfigAssembler.CHART_TYPES_ZH.get(t)));
        m.put("none", "這組欄位不值得畫圖");
        return m;
    }
}
```

`backend/src/main/java/jev/dashboard/jev/JevController.java`：

```java
package jev.dashboard.jev;

import java.util.UUID;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.DatasourceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jev")
public class JevController {
    public record AnalyzeRequest(UUID datasourceId) {}

    private final DatasourceService datasources;
    private final AnalyzeService analyzer;

    public JevController(DatasourceService datasources, AnalyzeService analyzer) {
        this.datasources = datasources;
        this.analyzer = analyzer;
    }

    @PostMapping("/analyze")
    public DashboardConfig analyze(@RequestHeader("X-User-Id") String userId, @RequestBody AnalyzeRequest body) {
        DatasourceService.Loaded loaded = datasources.load(userId, body.datasourceId());
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size());
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest='AnalyzeServiceTest,JevControllerTest'`
Expected: PASS（1 + 2 個測試）

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(backend): two-round JEV analysis endpoint"
```

---

### Task 10: 個人儀表板 CRUD 與資料端點

**Files:**
- Create: `backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`、`DashboardService.java`、`DashboardDataService.java`、`DashboardController.java`
- Modify: `backend/src/test/java/jev/dashboard/TestData.java`（加 `dashboardJson`）
- Test: `backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Consumes: `DashboardConfig`（Task 9）；`DatasourceService`（Task 5）；`UniversalTransformer`、`ChartData`（Task 4）
- Produces:
  - `record DashboardRepository.DashboardSummary(UUID id, String name, UUID datasourceId, OffsetDateTime updatedAt)`
  - `DashboardService`：`create`、`get`（404）、`list`、`update`、`delete`、`static requireComplete(DashboardConfig)`
  - `DashboardDataService.load(String userId, DashboardConfig) → List<ChartData>`，順序和 `layout` 一樣
  - REST：`POST /api/dashboards`、`GET /api/dashboards`、`GET/PUT/DELETE /api/dashboards/{id}`、`GET /api/dashboards/{id}/data`、`POST /api/dashboards/preview/data`
  - `TestData.dashboardJson(String datasourceId) → String`：c1 line、c2 bar（兩張都有 timeGrain）、c3 pie、c4 bar（欄位 `profit` 不存在）

- [ ] **Step 1: 加測試資料、寫會失敗的測試**

在 `TestData.java` 加：

```java
    /** c1、c2 有時間欄位；c3 沒有；c4 用了不存在的欄位 profit。 */
    public static String dashboardJson(String datasourceId) {
        return """
            {"name":"銷售總覽","datasourceId":"%s","layout":[
              {"chartId":"c1","type":"line","title":"月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","type":"bar","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}},
              {"chartId":"c3","type":"pie","title":"地區佔比","gridPos":{"x":0,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c4","type":"bar","title":"利潤","gridPos":{"x":6,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"profit","aggregate":"sum"}}
            ]}""".formatted(datasourceId);
    }
```

`backend/src/test/java/jev/dashboard/dashboard/DashboardControllerTest.java`：

```java
package jev.dashboard.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    String create(String user, String json) throws Exception {
        String body = mvc.perform(post("/api/dashboards").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    @Test
    void crudIsScopedToTheOwner() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);

        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("銷售總覽"))
            .andExpect(jsonPath("$.createdBy").value("u1"))
            .andExpect(jsonPath("$.layout.length()").value(4))
            .andExpect(jsonPath("$.layout[0].gridPos.x").value(0));
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u2")).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u2")).andExpect(jsonPath("$.length()").value(0));

        String moved = json.replaceFirst("\\{\"x\":0,\"y\":0,", "{\"x\":6,\"y\":8,");
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(moved))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[0].gridPos.x").value(6))
            .andExpect(jsonPath("$.layout[0].gridPos.y").value(8));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(moved))
            .andExpect(status().isNotFound());

        mvc.perform(delete("/api/dashboards/" + id).header("X-User-Id", "u2")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/dashboards/" + id).header("X-User-Id", "u1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1")).andExpect(status().isNotFound());
    }

    @Test
    void createWithSomeoneElsesDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isNotFound());
    }

    @Test
    void dataTransformsEachCardAndFlagsMissingField() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String id = create("u1", TestData.dashboardJson(ds));
        mvc.perform(get("/api/dashboards/" + id + "/data").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].chartId").value("c1"))
            .andExpect(jsonPath("$[0].rows.length()").value(6))
            .andExpect(jsonPath("$[0].rows[0][0]").value("2024-01"))
            .andExpect(jsonPath("$[0].rows[0][1]").value(2000.0))
            .andExpect(jsonPath("$[0].rows[5][1]").value(4300.0))
            .andExpect(jsonPath("$[2].rows[0][0]").value("北區"))
            .andExpect(jsonPath("$[3].error").value("FIELD_MISSING"))
            .andExpect(jsonPath("$[3].missingField").value("profit"));
    }

    @Test
    void previewDataWorksWithoutSaving() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards/preview/data").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rows.length()").value(6));
        mvc.perform(post("/api/dashboards/preview/data").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest=DashboardControllerTest`
Expected: FAIL，`POST /api/dashboards` 回 404

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/dashboard/DashboardRepository.java`：

```java
package jev.dashboard.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    public record DashboardSummary(UUID id, String name, UUID datasourceId, OffsetDateTime updatedAt) {}

    record Stored(List<ChartSpec> layout) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DashboardRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insert(DashboardConfig c, String userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into dashboard (id, name, datasource_id, created_by, config_json)
                values (:id, :name, :ds, :by, cast(:config as jsonb))""")
            .param("id", id)
            .param("name", c.name())
            .param("ds", c.datasourceId())
            .param("by", userId)
            .param("config", write(new Stored(c.layout())))
            .update();
        return id;
    }

    public Optional<DashboardConfig> find(UUID id, String userId) {
        return jdbc.sql("select * from dashboard where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    public List<DashboardSummary> list(String userId) {
        return jdbc.sql("select id, name, datasource_id, updated_at from dashboard where created_by = :by order by updated_at desc")
            .param("by", userId)
            .query((rs, n) -> new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("datasource_id", UUID.class), rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }

    public boolean update(UUID id, String userId, DashboardConfig c) {
        return jdbc.sql("""
                update dashboard set name = :name, config_json = cast(:config as jsonb), updated_at = now()
                where id = :id and created_by = :by""")
            .param("name", c.name())
            .param("config", write(new Stored(c.layout())))
            .param("id", id)
            .param("by", userId)
            .update() == 1;
    }

    public boolean delete(UUID id, String userId) {
        return jdbc.sql("delete from dashboard where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .update() == 1;
    }

    private DashboardConfig map(ResultSet rs, int rowNum) throws SQLException {
        Stored stored;
        try {
            stored = json.readValue(rs.getString("config_json"), Stored.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return new DashboardConfig(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getObject("datasource_id", UUID.class), rs.getString("created_by"), stored.layout());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardService.java`：

```java
package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.datasource.DatasourceService;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final DashboardRepository repo;
    private final DatasourceService datasources;

    public DashboardService(DashboardRepository repo, DatasourceService datasources) {
        this.repo = repo;
        this.datasources = datasources;
    }

    public DashboardConfig create(String userId, DashboardConfig c) {
        requireComplete(c);
        datasources.get(userId, c.datasourceId());
        return get(userId, repo.insert(c, userId));
    }

    public DashboardConfig get(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(DashboardService::notFound);
    }

    public List<DashboardSummary> list(String userId) {
        return repo.list(userId);
    }

    public DashboardConfig update(String userId, UUID id, DashboardConfig c) {
        requireComplete(c);
        if (!repo.update(id, userId, c)) throw notFound();
        return get(userId, id);
    }

    public void delete(String userId, UUID id) {
        if (!repo.delete(id, userId)) throw notFound();
    }

    public static void requireComplete(DashboardConfig c) {
        if (c == null || c.name() == null || c.datasourceId() == null || c.layout() == null) {
            throw new ApiException(BAD_REQUEST, "name、datasourceId、layout 都必填");
        }
    }

    private static ApiException notFound() {
        return new ApiException(NOT_FOUND, "找不到儀表板");
    }
}
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardDataService.java`：

```java
package jev.dashboard.dashboard;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private final DatasourceService datasources;

    public DashboardDataService(DatasourceService datasources) {
        this.datasources = datasources;
    }

    public List<ChartData> load(String userId, DashboardConfig c) {
        DatasourceService.Loaded loaded = datasources.load(userId, c.datasourceId());
        // 資料源改版時欄位會從資料列消失，用第一列的 key 判斷欄位還在不在
        Set<String> available = loaded.rows().isEmpty()
            ? loaded.datasource().schema().stream().map(FieldSchema::name).collect(Collectors.toSet())
            : loaded.rows().get(0).keySet();
        return c.layout().stream().map(s -> UniversalTransformer.transform(s, loaded.rows(), available)).toList();
    }
}
```

`backend/src/main/java/jev/dashboard/dashboard/DashboardController.java`：

```java
package jev.dashboard.dashboard;

import java.util.List;
import java.util.UUID;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.transform.ChartData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {
    private final DashboardService service;
    private final DashboardDataService data;

    public DashboardController(DashboardService service, DashboardDataService data) {
        this.service = service;
        this.data = data;
    }

    @PostMapping
    public DashboardConfig create(@RequestHeader("X-User-Id") String userId, @RequestBody DashboardConfig body) {
        return service.create(userId, body);
    }

    @GetMapping
    public List<DashboardSummary> list(@RequestHeader("X-User-Id") String userId) {
        return service.list(userId);
    }

    @GetMapping("/{id}")
    public DashboardConfig get(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        return service.get(userId, id);
    }

    @PutMapping("/{id}")
    public DashboardConfig update(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                  @RequestBody DashboardConfig body) {
        return service.update(userId, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        service.delete(userId, id);
    }

    @GetMapping("/{id}/data")
    public List<ChartData> data(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        return data.load(userId, service.get(userId, id));
    }

    @PostMapping("/preview/data")
    public List<ChartData> preview(@RequestHeader("X-User-Id") String userId, @RequestBody DashboardConfig body) {
        DashboardService.requireComplete(body);
        return data.load(userId, body);
    }
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest=DashboardControllerTest`
Expected: PASS（4 個測試）

- [ ] **Step 5: 跑全部測試**

Run: `cd backend && ./mvnw -q test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(backend): personal dashboard CRUD, data and preview endpoints"
```

---

### Task 11: 異常檢查

**Files:**
- Create: `backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java`、`Highlight.java`、`HighlightService.java`
- Modify: `backend/src/main/java/jev/dashboard/jev/JevController.java`（加 `GET /highlight/{dashboardId}`）
- Test: `backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java`、`HighlightServiceTest.java`
- Modify test: `backend/src/test/java/jev/dashboard/jev/JevControllerTest.java`

**Interfaces:**
- Consumes: `ChartSpec`、`ChartData`、`Mapping`（Task 4）；`JevClient`、`Question`、`Answer`（Task 7）；`DashboardService.get`、`DashboardDataService.load`（Task 10）
- Produces:
  - `HighlightSummarizer.summarize(ChartSpec, ChartData) → Map<String, Object>`，不適用時回 `null`
  - `record Highlight(String chartId, String severity, double noul)`
  - `HighlightService.check(UUID dashboardId, int fingerprint, List<ChartSpec>, List<ChartData>) → List<Highlight>`，JEV 失敗回 `null`，不進快取
  - REST `GET /api/jev/highlight/{dashboardId}` → `[{"chartId","severity","noul"}]`，失敗時回 `[]`

- [ ] **Step 1: 寫會失敗的測試**

`backend/src/test/java/jev/dashboard/jev/HighlightSummarizerTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class HighlightSummarizerTest {
    static List<Object> r(Object... values) {
        return Arrays.asList(values);
    }

    static ChartSpec spec(String type, Mapping m) {
        return new ChartSpec("c1", type, "月銷售額", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    static final Mapping LINE = new Mapping("order_date", null, null, "amount", null, null, "sum", "month");

    @Test
    void comparesLastTwoPeriods() {
        ChartData d = new ChartData("c1", "line", List.of("order_date", "amount"),
            List.of(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 150.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", LINE), d);
        assertThat(s).containsEntry("title", "月銷售額").containsEntry("metric", "amount")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 100.0, "current", 150.0, "changePercent", 50.0)));
    }

    @Test
    void comparesEachSeriesAndTreatsMissingAsZero() {
        Mapping m = new Mapping("order_date", null, "region", "amount", null, null, "sum", "month");
        ChartData d = new ChartData("c1", "stacked_bar", List.of("order_date", "region", "amount"),
            List.of(r("2024-05", "北", 10.0), r("2024-05", "南", 20.0), r("2024-06", "北", 5.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("stacked_bar", m), d);
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 10.0, "current", 5.0, "changePercent", -50.0),
            Map.of("name", "南", "previous", 20.0, "current", 0.0, "changePercent", -100.0)));
    }

    @Test
    void skipsCardsWithoutTimeAxisOrWithOnePeriodOrWithErrors() {
        Mapping pie = new Mapping("region", null, null, "amount", null, null, "sum", null);
        ChartData pieData = new ChartData("c1", "pie", List.of("region", "amount"), List.of(r("北", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("pie", pie), pieData)).isNull();

        ChartData onePeriod = new ChartData("c1", "line", List.of("order_date", "amount"), List.of(r("2024-06", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), onePeriod)).isNull();

        assertThat(HighlightSummarizer.summarize(spec("line", LINE), ChartData.missing("c1", "line", "amount"))).isNull();
    }
}
```

`backend/src/test/java/jev/dashboard/jev/HighlightServiceTest.java`：

```java
package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.FakeJevServer;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class HighlightServiceTest {
    static List<Object> r(Object... values) {
        return Arrays.asList(values);
    }

    static final ChartSpec LINE = new ChartSpec("c1", "line", "月銷售額", new GridPos(0, 0, 6, 4),
        new Mapping("order_date", null, null, "amount", null, null, "sum", "month"), Map.of());
    static final ChartSpec BAR = new ChartSpec("c2", "bar", "月數量", new GridPos(6, 0, 6, 4),
        new Mapping("order_date", null, null, "qty", null, null, "sum", "month"), Map.of());
    static final ChartSpec PIE = new ChartSpec("c3", "pie", "地區佔比", new GridPos(0, 4, 6, 4),
        new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
    static final List<ChartData> DATA = List.of(
        new ChartData("c1", "line", List.of("order_date", "amount"), List.of(r("2024-05", 3200.0), r("2024-06", 4300.0)), null, null),
        new ChartData("c2", "bar", List.of("order_date", "qty"), List.of(r("2024-05", 10.0), r("2024-06", 13.0)), null, null),
        new ChartData("c3", "pie", List.of("region", "amount"), List.of(r("北區", 100.0)), null, null));

    static HighlightService service(FakeJevServer server) {
        return new HighlightService(new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
    }

    @Test
    void flagsCardsAboveThresholdWithSeverityLabel() {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            List<Highlight> result = service(server).check(UUID.randomUUID(), 1, List.of(LINE, BAR, PIE), DATA);
            assertThat(result).containsExactly(new Highlight("c1", "需要關注", 0.85));
            assertThat(server.bodies).hasSize(1);
            assertThat(server.bodies.get(0))
                .contains("\"anomaly_c1\"").contains("\"severity_c2\"").doesNotContain("anomaly_c3")
                .contains("34.4");
        }
    }

    @Test
    void jevFailureReturnsNullSoTheResultIsNotCached() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            assertThat(service(server).check(UUID.randomUUID(), 1, List.of(LINE), List.of(DATA.get(0)))).isNull();
        }
    }

    @Test
    void noTimeAxisCardsSkipsJev() {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            assertThat(service(server).check(UUID.randomUUID(), 1, List.of(PIE), List.of(DATA.get(2)))).isEmpty();
            assertThat(server.bodies).isEmpty();
        }
    }
}
```

在 `JevControllerTest` 加一個測試，並補上三個 import：`static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get`、`com.jayway.jsonpath.JsonPath`、`static java.nio.charset.StandardCharsets.UTF_8`：

```java
    @Test
    void highlightFlagsAnomalousCardsAndCachesTheResult() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String body = mvc.perform(post("/api/dashboards").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chartId").value("c1"))
                .andExpect(jsonPath("$[0].severity").value("需要關注"));
        }
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c2\"").doesNotContain("anomaly_c3").doesNotContain("anomaly_c4");

        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u2")).andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd backend && ./mvnw -q test -Dtest='HighlightSummarizerTest,HighlightServiceTest,JevControllerTest'`
Expected: FAIL，編譯錯誤 `cannot find symbol: class HighlightSummarizer`

- [ ] **Step 3: 實作**

`backend/src/main/java/jev/dashboard/jev/HighlightSummarizer.java`：

```java
package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;

/** 把一張卡片的資料摘要成「最後一期 vs 前一期」，只送這段摘要給 JEV，不送原始資料。 */
public final class HighlightSummarizer {
    private HighlightSummarizer() {}

    public static Map<String, Object> summarize(ChartSpec spec, ChartData data) {
        if (data.error() != null || data.rows() == null || spec.mapping() == null || spec.mapping().timeGrain() == null) {
            return null;
        }
        List<String> periods = data.rows().stream().map(r -> String.valueOf(r.get(0))).distinct().sorted().toList();
        if (periods.size() < 2) return null;
        String current = periods.getLast();
        String previous = periods.get(periods.size() - 2);
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
        out.put("metric", spec.mapping().value() == null ? "count" : spec.mapping().value());
        out.put("previousPeriod", previous);
        out.put("period", current);
        out.put("series", series);
        return out;
    }
}
```

`backend/src/main/java/jev/dashboard/jev/Highlight.java`：

```java
package jev.dashboard.jev;

public record Highlight(String chartId, String severity, double noul) {}
```

`backend/src/main/java/jev/dashboard/jev/HighlightService.java`：

```java
package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class HighlightService {
    private static final Logger log = LoggerFactory.getLogger(HighlightService.class);
    static final List<String> SEVERITY = List.of("正常波動", "值得注意", "需要關注", "嚴重警示");
    static final double THRESHOLD = 0.7;

    private final JevClient jev;

    public HighlightService(@Qualifier("highlightJevClient") JevClient jev) {
        this.jev = jev;
    }

    // ponytail: 預設的 ConcurrentMapCache 沒有上限也不會過期；儀表板 × 資料版本變多時換 Caffeine 加 TTL
    @Cacheable(cacheNames = "highlights", key = "#dashboardId + ':' + #fingerprint", unless = "#result == null")
    public List<Highlight> check(UUID dashboardId, int fingerprint, List<ChartSpec> specs, List<ChartData> data) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            ChartSpec spec = specs.get(i);
            Map<String, Object> summary = HighlightSummarizer.summarize(spec, data.get(i));
            if (summary == null) continue;
            String id = spec.chartId();
            state.put(id, summary);
            questions.put("anomaly_" + id, Question.noul("圖表「" + spec.title() + "」最新一期和前一期相比，有沒有值得標註的異常？"));
            questions.put("severity_" + id, Question.score("圖表「" + spec.title() + "」這次變化的嚴重程度？", SEVERITY));
        }
        if (questions.isEmpty()) return List.of();

        Map<String, Answer> answers;
        try {
            answers = jev.ask(state, questions);
        } catch (ApiException e) {
            log.warn("異常檢查失敗，略過標註：{}", e.getMessage());
            return null;
        }

        List<Highlight> out = new ArrayList<>();
        for (String id : state.keySet()) {
            Answer a = answers.get("anomaly_" + id);
            if (a == null || a.noul() == null || a.noul() <= THRESHOLD) continue;
            Answer s = answers.get("severity_" + id);
            int level = s == null || s.score() == null ? 1 : (int) Math.max(0, Math.min(3, Math.round(s.score())));
            out.add(new Highlight(id, SEVERITY.get(level), a.noul()));
        }
        return out;
    }
}
```

修改 `JevController.java`：建構子加 `DashboardService dashboards`、`DashboardDataService dashboardData`、`HighlightService highlights` 三個欄位，並加端點：

```java
    @GetMapping("/highlight/{dashboardId}")
    public List<Highlight> highlight(@RequestHeader("X-User-Id") String userId, @PathVariable UUID dashboardId) {
        DashboardConfig c = dashboards.get(userId, dashboardId);
        List<ChartData> data = dashboardData.load(userId, c);
        List<Highlight> result = highlights.check(dashboardId, data.hashCode(), c.layout(), data);
        return result == null ? List.of() : result;
    }
```

改完的建構子：

```java
    public JevController(DatasourceService datasources, AnalyzeService analyzer, DashboardService dashboards,
                         DashboardDataService dashboardData, HighlightService highlights) {
        this.datasources = datasources;
        this.analyzer = analyzer;
        this.dashboards = dashboards;
        this.dashboardData = dashboardData;
        this.highlights = highlights;
    }
```

import 加：`java.util.List`、`jev.dashboard.dashboard.DashboardDataService`、`jev.dashboard.dashboard.DashboardService`、`jev.dashboard.transform.ChartData`、`org.springframework.web.bind.annotation.GetMapping`、`org.springframework.web.bind.annotation.PathVariable`。

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd backend && ./mvnw -q test -Dtest='HighlightSummarizerTest,HighlightServiceTest,JevControllerTest'`
Expected: PASS（3 + 3 + 3 個測試）

- [ ] **Step 5: 跑全部測試**

Run: `cd backend && ./mvnw -q test`
Expected: PASS，沒有失敗也沒有跳過的測試

- [ ] **Step 6: 啟動確認**

```bash
cd backend && ./mvnw -q spring-boot:run
```

另開一個 Git Bash：

```bash
curl -s -H "X-User-Id: demo-user" http://localhost:8080/api/datasources
```

Expected: `[]`（或已上傳的資料源清單）。確認後按 Ctrl+C 停掉。

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(backend): per-card anomaly highlight with cache"
```
