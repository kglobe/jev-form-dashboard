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

    static final String TABLE_CARD = """
        {"chartId":"c2","datasourceId":"00000000-0000-0000-0000-000000000001","type":"table","title":"明細",
         "gridPos":{"x":0,"y":4,"w":12,"h":5},"mapping":{"columns":["region"]}}""";
    static final String BAR_CARD = """
        {"chartId":"c1","datasourceId":"00000000-0000-0000-0000-000000000001","type":"bar","title":"amount 依 region",
         "gridPos":{"x":0,"y":0,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}""";

    void analyzeIsRejected(String body, String message) throws Exception {
        mvc.perform(post("/api/jev/analyze").header("X-User-Id", "u1")
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
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        assertThat(JEV.bodies).isEmpty();
    }

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

        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void highlightSkipsCardsThatReferenceSensitiveFields() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String dashboardJson = """
            {"name":"分區銷售","datasourceId":"%1$s","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"stacked_bar","title":"分區月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"line","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}}
            ]}""".formatted(ds);
        String body = mvc.perform(post("/api/dashboards").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(dashboardJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(patch("/api/datasources/" + ds + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"region\"]}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/jev/highlight/" + id).header("X-User-Id", "u1")).andExpect(status().isOk());

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
}
