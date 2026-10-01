package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

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
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].kind").value("upload"))
            .andExpect(jsonPath("$[0].schema[0].name").value("order_date"))
            .andExpect(jsonPath("$[0].schema[0].type").value("date"))
            .andExpect(jsonPath("$[0].schema[0].min").value("2024-01-05"))
            .andExpect(jsonPath("$[0].schema[1].type").value("number"))
            .andExpect(jsonPath("$[0].schema[2].distinctCount").value(4));
        assertThat(jdbc.sql("select count(*) from datasource_rows").query(Long.class).single()).isEqualTo(12L);
        mvc.perform(get("/api/datasources").with(TestData.as("u2")))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void markSensitiveFields() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema[5].sensitive").value(true))
            .andExpect(jsonPath("$.schema[0].sensitive").value(false));
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void setFieldLabels() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + id + "/labels").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":{\"order_date\":\" 訂單日期 \",\"amount\":\"  \"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema[0].label").value("訂單日期"))
            .andExpect(jsonPath("$.schema[1].label").doesNotExist());
        // 顯示名稱不能和另一個欄位重複
        mvc.perform(patch("/api/datasources/" + id + "/labels").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":{\"order_date\":\"amount\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("顯示名稱「amount」重複"));
    }

    @Test
    void badCsvReportsLineNumber() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("bad.csv", "a,b\n1,2\n3\n"))
                .param("name", "壞檔").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("第 3 行")));
    }

    @Test
    void tooManyRowsIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("a\n");
        for (int i = 0; i < 21; i++) sb.append(i).append('\n');
        mvc.perform(multipart("/api/datasources/upload").file(csv("big.csv", sb.toString()))
                .param("name", "大檔").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("超過上限 20 筆")));
    }

    @Test
    void unsupportedExtensionIsRejected() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("data.txt", "a\n1\n"))
                .param("name", "文字檔").with(TestData.as("u1")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdminsCanRegisterApiSources() throws Exception { // 唯讀分享 spec 2.6
        mvc.perform(post("/api/datasources/api").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"http://10.0.0.5/sales\",\"headers\":{}}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("沒有權限"));
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void registerApiRejectsHostNotInAllowList() throws Exception {
        TestData.addAccount(jdbc, "boss", true);
        mvc.perform(post("/api/datasources/api").with(TestData.asAdmin("boss"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"https://evil.example/sales\",\"headers\":{}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("不在允許清單")));
    }

    @Test
    void registerApiRejectsLiteralHeaderValues() throws Exception {
        TestData.addAccount(jdbc, "boss", true);
        mvc.perform(post("/api/datasources/api").with(TestData.asAdmin("boss"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"http://10.0.0.5/sales\",\"headers\":{\"Authorization\":\"Bearer x\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("${DS_環境變數名}")));
    }

    @Test
    void registerApiRejectsSecretsNotBoundToTheHost() throws Exception { // 密鑰綁定 spec 3.2
        TestData.addAccount(jdbc, "boss", true);
        mvc.perform(post("/api/datasources/api").with(TestData.asAdmin("boss"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"http://10.0.0.5/sales\",\"headers\":{\"X-Key\":\"${DS_TEST_UNBOUND}\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(HttpApiDataSourceProviderTest.notBound("DS_TEST_UNBOUND", "10.0.0.5")));
    }

    static final String NORTH_SOUTH = """
        {"op":"union","inputs":["%1$s","%2$s"],"columns":[
          {"name":"region","from":{"%1$s":"region","%2$s":"area"}},
          {"name":"amount","from":{"%1$s":"amount","%2$s":"amount"}},
          {"name":"memo","from":{"%1$s":"memo"}}],"sourceColumn":"來源"}""";

    void mergeIsRejected(String body, int status, String message) throws Exception {
        mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void mergedSourcesArePreviewedThenCreatedAndListedWithTheirDefinition() throws Exception {
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount,memo\n北區,100,首購\n北區,200,\n");
        String s = TestData.uploadCsv(mvc, "u1", "南區", "area,amount\n南區,50\n");
        String body = "{\"name\":\"全區\",\"merge\":" + NORTH_SOUTH.formatted(n, s) + "}";

        mvc.perform(post("/api/datasources/merged/preview").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(3))
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[2].region").value("南區"))
            .andExpect(jsonPath("$.rows[2]['來源']").value("南區"))
            .andExpect(jsonPath("$.warnings[0]").value("「memo」只有北區有，其他來源的列這欄是空的"))
            .andExpect(jsonPath("$.schema[3].name").value("來源"));
        assertThat(jdbc.sql("select count(*) from datasource").query(Long.class).single()).isEqualTo(2L);

        mvc.perform(patch("/api/datasources/" + n + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        String created = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("merged"))
            .andExpect(jsonPath("$.merge.op").value("union"))
            .andExpect(jsonPath("$.rowCount").value(3))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
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

    @Test
    void aCompareSourceKeepsCompareInItsDefinition() throws Exception {
        String sales = TestData.uploadCsv(mvc, "u1", "銷售", "order_date,amount\n2026-07-03,1000\n");
        String refunds = TestData.uploadCsv(mvc, "u1", "退貨", "return_date,amount\n2026-08-05,50\n");
        String merge = """
            {"op":"union","compare":true,"inputs":["%1$s","%2$s"],"columns":[
              {"name":"date","from":{"%1$s":"order_date","%2$s":"return_date"}},
              {"name":"銷售·amount","from":{"%1$s":"amount"}},
              {"name":"退貨·amount","from":{"%2$s":"amount"}}],"sourceColumn":"來源"}""".formatted(sales, refunds);

        String created = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售／退貨 對照\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merge.compare").value(true))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        String list = mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        List<Boolean> compare = JsonPath.read(list, "$[?(@.id == '" + id + "')].merge.compare");
        assertThat(compare).containsExactly(true);
    }

    @Test
    void ratiosArePatchedAsAWholeListValidatedAndListedWithTheirSensitivity() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].ratios.length()").value(0));

        mvc.perform(patch("/api/datasources/" + id + "/ratios").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\" 單價 \",\"numerator\":\"amount\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios[0].name").value("單價"))
            .andExpect(jsonPath("$.ratios[0].numerator").value("amount"))
            .andExpect(jsonPath("$.ratios[0].denominator").value("qty"))
            .andExpect(jsonPath("$.ratios[0].sensitive").value(false));

        // 分母標成敏感，比率跟著敏感
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"qty\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios[0].sensitive").value(true));
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].ratios[0].sensitive").value(true));

        mvc.perform(patch("/api/datasources/" + id + "/ratios").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\"x\",\"numerator\":\"region\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("分子「region」不是這個來源的數字欄位"));
        mvc.perform(patch("/api/datasources/" + id + "/ratios").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"ratios\":[]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));

        // 沒帶 ratios 就是清空
        mvc.perform(patch("/api/datasources/" + id + "/ratios").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratios.length()").value(0));
    }

    /** 用 region、amount 兩欄疊加這些來源（每個來源都要有這兩欄），回傳新來源的 id。 */
    String union(String name, String... inputs) throws Exception {
        String ids = String.join("\",\"", inputs);
        String from = java.util.Arrays.stream(inputs).map(i -> "\"" + i + "\":\"%s\"").collect(java.util.stream.Collectors.joining(","));
        String merge = "{\"op\":\"union\",\"inputs\":[\"" + ids + "\"],\"columns\":["
            + "{\"name\":\"region\",\"from\":{" + from.formatted((Object[]) java.util.Collections.nCopies(inputs.length, "region").toArray()) + "}},"
            + "{\"name\":\"amount\",\"from\":{" + from.formatted((Object[]) java.util.Collections.nCopies(inputs.length, "amount").toArray()) + "}}"
            + "],\"sourceColumn\":\"來源\"}";
        String body = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    static String card(String chartId, String ds, String type, int y) {
        String mapping = "table".equals(type) ? "{\"columns\":[\"region\"]}"
            : "{\"x\":\"region\",\"value\":\"amount\",\"aggregate\":\"sum\"}";
        return "{\"chartId\":\"" + chartId + "\",\"datasourceId\":\"" + ds + "\",\"type\":\"" + type
            + "\",\"title\":\"t\",\"gridPos\":{\"x\":0,\"y\":" + y + ",\"w\":6,\"h\":4},\"mapping\":" + mapping + "}";
    }

    void createDashboard(String name, String... cards) throws Exception {
        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"layout\":[" + String.join(",", cards) + "]}"))
            .andExpect(status().isOk());
    }

    @Test
    void usageListsDirectMergedSourcesAndDashboardsWithCardCounts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String north = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,1\n");
        String idle = TestData.uploadCsv(mvc, "u1", "沒人用", "region,amount\n北區,1\n");
        String all = union("全區", sales, north);
        union("全區二層", all, north);
        createDashboard("週報", card("c1", sales, "bar", 0), card("c2", sales, "bar", 4), card("c3", sales, "table", 8));
        createDashboard("月報", card("c1", all, "bar", 0));

        mvc.perform(get("/api/datasources/" + sales + "/usage").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mergedBy.length()").value(1))
            .andExpect(jsonPath("$.mergedBy[0].name").value("全區"))
            .andExpect(jsonPath("$.mergedBy[0].id").value(all))
            .andExpect(jsonPath("$.dashboards.length()").value(1))
            .andExpect(jsonPath("$.dashboards[0].name").value("週報"))
            .andExpect(jsonPath("$.dashboards[0].charts").value(3));
        mvc.perform(get("/api/datasources/" + idle + "/usage").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mergedBy.length()").value(0))
            .andExpect(jsonPath("$.dashboards.length()").value(0));
    }

    @Test
    void deleteIsBlockedWhileAMergedSourceUsesIt() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String north = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,1\n");
        union("全區", sales, north);
        union("全區B", sales, north);

        mvc.perform(delete("/api/datasources/" + sales).with(TestData.as("u1")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("「銷售」是這些合併來源的輸入，要先刪掉：全區B、全區"));
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$[?(@.id == '" + sales + "')]").isNotEmpty());
    }

    @Test
    void deleteRemovesTheSourceAndItsRowsButLeavesDashboards() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        createDashboard("週報", card("c1", sales, "bar", 0), card("c2", sales, "bar", 4));
        String dash = JsonPath.read(mvc.perform(get("/api/dashboards").with(TestData.as("u1")))
            .andReturn().getResponse().getContentAsString(UTF_8), "$[0].id");

        mvc.perform(delete("/api/datasources/" + sales).with(TestData.as("u1"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/datasources").with(TestData.as("u1"))).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.sql("select count(*) from datasource_rows where datasource_id = :id")
            .param("id", java.util.UUID.fromString(sales)).query(Long.class).single()).isZero();
        mvc.perform(get("/api/dashboards/" + dash).with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.length()").value(2));
        mvc.perform(delete("/api/datasources/" + sales).with(TestData.as("u1")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void deleteAndUsageAreScopedToTheOwner() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources/" + sales + "/usage").with(TestData.as("u2")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("找不到資料源"));
        mvc.perform(delete("/api/datasources/" + sales).with(TestData.as("u2")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("找不到資料源"));
        mvc.perform(get("/api/datasources").with(TestData.as("u1"))).andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 更新資料（更新資料 spec 第 3 節） ----------

    ResultActions reload(String user, String id, MockMultipartFile file, String... params)
            throws Exception {
        var req = multipart("/api/datasources/" + id + "/rows").file(file).with(TestData.as(user));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req);
    }

    @Test
    void reloadAppendsOrReplacesAndReturnsTheSourceTheWayTheListShowsIt() throws Exception {
        String id = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,100\n");
        createDashboard("週報", card("c1", id, "bar", 0));
        String dash = JsonPath.read(mvc.perform(get("/api/dashboards").with(TestData.as("u1")))
            .andReturn().getResponse().getContentAsString(UTF_8), "$[0].id");

        reload("u1", id, csv("new.csv", "amount,region\n200,南區\n"), "mode", "append")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.kind").value("upload"))
            .andExpect(jsonPath("$.rowCount").value(2))
            .andExpect(jsonPath("$.schema[0].name").value("region"))
            .andExpect(jsonPath("$.schema[1].samples[1]").value("200"))
            .andExpect(jsonPath("$.ratios.length()").value(0));
        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].rowCount").value(2));
        // 圖下次載入就讀到新的資料列
        mvc.perform(get("/api/dashboards/" + dash + "/data").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].rows.length()").value(2));

        reload("u1", id, csv("new.csv", "region,amount\n東區,300\n"), "mode", "replace")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(1));
        mvc.perform(get("/api/dashboards/" + dash + "/data").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].rows.length()").value(1))
            .andExpect(jsonPath("$[0].rows[0][0]").value("東區"));
    }

    @Test
    void reloadNeedsAValidModeAFileAndTheCallersOwnUploadSource() throws Exception {
        String id = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,100\n");
        String north = TestData.uploadCsv(mvc, "u1", "南區", "region,amount\n南區,50\n");
        String merged = union("全區", id, north);
        MockMultipartFile file = csv("new.csv", "region,amount\n東區,1\n");

        reload("u1", id, file).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("缺少參數：mode"));
        reload("u1", id, file, "mode", "merge").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("更新方式只能是 replace（整份覆蓋）或 append（接在後面）"));
        mvc.perform(multipart("/api/datasources/" + id + "/rows").param("mode", "append").with(TestData.as("u1")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("缺少參數：file"));
        reload("u1", id, csv("new.txt", "region,amount\n東區,1\n"), "mode", "append")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("只支援 .csv、.xlsx、.xls"));
        reload("u2", id, file, "mode", "append").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        reload("u1", merged, file, "mode", "append").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("只有上傳的檔案可以更新資料"));
        reload("u1", id, csv("new.csv", "region,qty\n東區,1\n"), "mode", "replace").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("欄位跟原本的不一樣：少了 amount；多了 qty"));
        reload("u1", id, csv("new.csv", "region,amount\n東區,很多\n"), "mode", "replace").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("欄位「amount」原本是數字，新檔案裡有不是數字的值"));
        StringBuilder big = new StringBuilder("region,amount\n");
        for (int i = 0; i < 20; i++) big.append("東區,").append(i).append('\n');
        reload("u1", id, csv("new.csv", big.toString()), "mode", "append").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("資料有 21 筆，超過上限 20 筆"));
        assertThat(jdbc.sql("select count(*) from datasource_rows where datasource_id = :id")
            .param("id", java.util.UUID.fromString(id)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anExcelFileIsReadFromTheChosenSheet() throws Exception {
        String id = TestData.uploadCsv(mvc, "u1", "銷售", "order_date,amount,region\n2024-01-01,100,南區\n");
        MockMultipartFile xlsx = new MockMultipartFile("file", "new.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ExcelParserTest.workbook());

        reload("u1", id, xlsx, "mode", "append", "sheet", "不存在").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("找不到工作表：不存在"));
        reload("u1", id, xlsx, "mode", "append", "sheet", "銷售")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(2))
            .andExpect(jsonPath("$.schema[0].max").value("2024-01-05"))
            // Excel 的數字儲存格讀進來是 1200.0，樣本照樣寫成 1200
            .andExpect(jsonPath("$.schema[1].samples[1]").value("1200"));
    }

    @Test
    void mergedSourcesThatUseItEvenTwoLevelsUpForgetTheirRowCount() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String north = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,1\n");
        String idle = TestData.uploadCsv(mvc, "u1", "沒人用", "region,amount\n北區,1\n");
        String all = union("全區", sales, north);
        // top 只透過 all 用到 north，沒有往上追的話 top 的筆數不會清掉
        String top = union("全區二層", all, idle);
        String unrelated = union("不相干", sales, idle);

        reload("u1", north, csv("new.csv", "region,amount\n北區,2\n"), "mode", "append").andExpect(status().isOk());

        String list = mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andReturn().getResponse().getContentAsString(UTF_8);
        List<Integer> counts = JsonPath.read(list, "$[?(@.id in ['" + all + "','" + top + "'])].rowCount");
        assertThat(counts).containsExactly(null, null);
        List<Integer> kept = JsonPath.read(list, "$[?(@.id == '" + unrelated + "')].rowCount");
        assertThat(kept).containsExactly(13);
        List<Integer> own = JsonPath.read(list, "$[?(@.id == '" + north + "')].rowCount");
        assertThat(own).containsExactly(2);
    }
}
