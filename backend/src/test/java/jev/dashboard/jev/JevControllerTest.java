package jev.dashboard.jev;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.test.web.servlet.ResultActions;

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

    static final String TABLE_CARD = """
        {"chartId":"c2","datasourceId":"00000000-0000-0000-0000-000000000001","type":"table","title":"明細",
         "gridPos":{"x":0,"y":4,"w":12,"h":5},"mapping":{"columns":["region"]}}""";
    static final String BAR_CARD = """
        {"chartId":"c1","datasourceId":"00000000-0000-0000-0000-000000000001","type":"bar","title":"amount 依 region",
         "gridPos":{"x":0,"y":0,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}""";

    void analyzeIsRejected(String body, String message) throws Exception {
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(message));
    }

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
        JEV.bodies.clear();
    }

    @Test
    void analyzeReturnsPreviewWithoutSaving() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
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
    void analyzeUsesTheRowCountRecordedAtUploadWithoutReadingTheRows() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        // 資料列拿掉了還送出 12 筆，代表 analyze 沒有為了算筆數去讀資料列（外部 API 就不用整批抓回來）
        jdbc.sql("delete from datasource_rows where datasource_id = :id").param("id", UUID.fromString(ds)).update();

        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isOk());

        assertThat(JEV.bodies.get(0)).contains("\"rowCount\":12");
    }

    @Test
    void analyzeOtherUsersDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }

    @Test
    void highlightFlagsAnomalousCardsAndCachesTheResult() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chartId").value("c1"))
                .andExpect(jsonPath("$[0].severity").value("需要關注"));
        }
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c2\"").doesNotContain("anomaly_c3").doesNotContain("anomaly_c4");

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void aViewersHighlightUsesTheOwnersSources() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":false,\"users\":[\"u2\"]}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].chartId").value("c1"))
            .andExpect(jsonPath("$[0].severity").value("需要關注"));
        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u3")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void withoutLoginTheHighlightOpensOnlyForAnAnonymousDashboard() throws Exception { // 資料來源共享 spec 4.4
        String ds = TestData.uploadSales(mvc, "u1");
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":true,\"users\":[]}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/jev/highlight/" + id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));

        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":true,\"anonymous\":true,\"users\":[]}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/jev/highlight/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].chartId").value("c1"));
    }

    @Test
    void highlightRightAfterDataUsesTheRowsDataAlreadyRead() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1"))).andExpect(status().isOk());
        // 資料列拿掉了還標得出來，代表異常檢查用的是 /data 剛讀的資料，沒有再讀一次來源
        jdbc.sql("delete from datasource_rows where datasource_id = :id").param("id", UUID.fromString(ds)).update();

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].chartId").value("c1"));
    }

    @Test
    void highlightSkipsCardsThatReferenceSensitiveFields() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String dashboardJson = """
            {"name":"分區銷售","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"stacked_bar","title":"分區月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"line","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}}
            ]}""".formatted(ds);
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(dashboardJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(patch("/api/datasources/" + ds + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"region\"]}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1"))).andExpect(status().isOk());

        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c2\"").doesNotContain("anomaly_c1").doesNotContain("北區");
    }

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
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceId\":\"" + ds + "\",\"intents\":[\"  各區每月營收　\",\"\",\"   \",null]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").value(contains("各區每月營收")))
            .andExpect(jsonPath("$.layout[*].type").value(contains("line", "stacked_bar", "bar", "table")));
        assertThat(JEV.bodies.get(0)).contains("\"intent\":\"各區每月營收\"").contains("\"rel_0\"");
    }

    @Test
    void adjustSendsTheCurrentChartsAndKeepsThemWhenJevAddsNothing() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
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
        mvc.perform(post("/api/jev/analyze").with(TestData.as("u2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasourceId\":\"" + ds + "\",\"intents\":[\"不要圓餅圖\"],\"current\":[" + TABLE_CARD + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }

    @Test
    void analyzingAMergedSourceKeepsInheritedSensitiveSamplesAwayFromJev() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String products = TestData.uploadCsv(mvc, "u1", "產品主檔", "code,category,owner\nA,家電,王小明\nB,文具,李小華\nC,食品,陳大文\n");
        mvc.perform(patch("/api/datasources/" + sales + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/datasources/" + products + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"owner\"]}"))
            .andExpect(status().isOk());
        String merge = """
            {"op":"lookup","main":"%s","lookup":"%s","keys":[{"main":"product","lookup":"code"}],
             "bring":[{"name":"category","from":"category"},{"name":"owner","from":"owner"}]}""".formatted(sales, products);
        String created = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售（補產品主檔）\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String merged = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + merged + "\"}"))
            .andExpect(status().isOk());

        // 第一輪的 state：category 有樣本；從 sales、產品主檔繼承來的敏感欄位沒有
        assertThat(JEV.bodies.get(0)).contains("家電").doesNotContain("首購").doesNotContain("王小明");
    }

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
        String created = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售／退貨 對照\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String compared = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + compared + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[0].type").value("line"))
            .andExpect(jsonPath("$.layout[0].mapping.measures[0]").value("銷售·amount"))
            .andExpect(jsonPath("$.layout[0].mapping.measures[1]").value("退貨·amount"));

        assertThat(JEV.bodies.get(1)).contains("兩個數值來自不同來源，要放在同一張圖上對照。");
    }

    @Test
    void highlightChecksSensitiveFieldsAgainstEachCardsOwnSource() throws Exception {
        String plain = TestData.uploadSales(mvc, "u1");
        String secret = TestData.uploadSales(mvc, "u1");
        // 兩個來源都有 region，只有 secret 那邊標成敏感
        mvc.perform(patch("/api/datasources/" + secret + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"region\"]}"))
            .andExpect(status().isOk());
        String card = """
            {"chartId":"%s","datasourceId":"%s","type":"stacked_bar","title":"分區月銷售額",
             "gridPos":{"x":%d,"y":0,"w":6,"h":4},
             "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}}""";
        String first = card.formatted("c1", plain, 0);
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"兩個來源\",\"layout\":[" + first + "]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"兩個來源\",\"layout\":["
                    + first + "," + card.formatted("c2", secret, 6) + "]}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1"))).andExpect(status().isOk());

        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c1\"").doesNotContain("anomaly_c2");
    }

    @Test
    void highlightSendsRatioCardsInPercentPointsAndSkipsThemOnceTheRatioIsSensitive() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + ds + "/ratios").with(TestData.as("u1"))
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
        String body = mvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(dashboardJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1"))).andExpect(status().isOk());
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("\"anomaly_c1\"").contains("\"unit\":\"%\"").contains("\"changePoints\"");

        // 分子標成敏感，比率跟著敏感，那張卡整張不送
        mvc.perform(patch("/api/datasources/" + ds + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"amount\"]}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/jev/highlight/" + id).with(TestData.as("u1"))).andExpect(status().isOk());
        assertThat(JEV.bodies).hasSize(2);
        assertThat(JEV.bodies.get(1)).contains("\"anomaly_c2\"").doesNotContain("anomaly_c1");
    }

    @Test
    void analyzingASourceWithARatioAsksAboutItAfterTheFields() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + ds + "/ratios").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratios\":[{\"name\":\"單價\",\"numerator\":\"amount\",\"denominator\":\"qty\"}]}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/jev/analyze").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasourceId\":\"" + ds + "\"}"))
            .andExpect(status().isOk());

        // sales.csv 有 6 個欄位，比率是第 7 個（imp_6），不問角色
        assertThat(JEV.bodies.get(0)).contains("{\"name\":\"單價\",\"type\":\"ratio\",\"formula\":\"amount ÷ qty\"}")
            .contains("\"imp_6\"").doesNotContain("\"role_6\"");
    }

    static final String ORDERS_CSV = "訂單日期,地區,金額,出貨日\n2024-07-05,北區,1200,2024-07-08\n2024-07-20,南區,800,2024-07-22\n";

    ResultActions mapping(String userId, String body) throws Exception {
        return mvc.perform(post("/api/jev/mapping").with(TestData.as(userId))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void mappingAsksJevOnceAndReturnsOnlyTheMatchesThatPassTheRules() throws Exception {
        String orders = TestData.uploadCsv(mvc, "u1", "訂單", ORDERS_CSV);
        String sales = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + sales + "/sensitive").with(TestData.as("u1"))
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
    void mappingAMergedSourceKeepsInheritedSensitiveSamplesAwayFromJev() throws Exception {
        String orders = TestData.uploadCsv(mvc, "u1", "訂單", ORDERS_CSV);
        String sales = TestData.uploadSales(mvc, "u1");
        String products = TestData.uploadCsv(mvc, "u1", "產品主檔", "code,category,owner\nA,家電,王小明\nB,文具,李小華\nC,食品,陳大文\n");
        mvc.perform(patch("/api/datasources/" + sales + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/datasources/" + products + "/sensitive").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"owner\"]}"))
            .andExpect(status().isOk());
        String merge = """
            {"op":"lookup","main":"%s","lookup":"%s","keys":[{"main":"product","lookup":"code"}],
             "bring":[{"name":"category","from":"category"},{"name":"owner","from":"owner"}]}""".formatted(sales, products);
        String created = mvc.perform(post("/api/datasources/merged").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售（補產品主檔）\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String merged = JsonPath.read(created, "$.id");

        mapping("u1", "{\"inputs\":[\"" + orders + "\",\"" + merged + "\"]}").andExpect(status().isOk());

        // 合併來源的 memo、owner 是從 sales、產品主檔繼承來的敏感欄位，只送名稱和型別；category 照常有樣本
        assertThat(JEV.bodies).hasSize(1);
        assertThat(JEV.bodies.get(0)).contains("家電").doesNotContain("首購").doesNotContain("王小明");
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
    void mappingInCompareModeNeverAsksAboutNumberFields() throws Exception {
        String orders = TestData.uploadCsv(mvc, "u1", "訂單", ORDERS_CSV);
        String sales = TestData.uploadSales(mvc, "u1");

        // mapping.json 有 amount、qty 的答案，但對照時沒問，不採用
        mapping("u1", "{\"inputs\":[\"" + orders + "\",\"" + sales + "\"],\"compare\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matches[*].field").value(contains("order_date", "region")));
        assertThat(JEV.bodies.get(0)).doesNotContain("\"map_1_1\"").doesNotContain("\"map_1_4\"");
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
        // null 元素跟建立合併來源一樣，當成重複
        for (String body : List.of("{\"inputs\":[\"" + a + "\",\"" + a + "\"]}", "{\"inputs\":[null,\"" + a + "\"]}")) {
            mapping("u1", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("疊加的來源不能重複"));
        }
        mapping("u2", "{\"inputs\":[\"" + b + "\",\"" + a + "\"]}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }
}
