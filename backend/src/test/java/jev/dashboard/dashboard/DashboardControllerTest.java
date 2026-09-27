package jev.dashboard.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
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

    static String card(String chartId, String ds, int y) {
        return """
            {"chartId":"%s","datasourceId":"%s","type":"bar","title":"amount 依 region",
             "gridPos":{"x":0,"y":%d,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}"""
            .formatted(chartId, ds, y);
    }

    static String tableCard(String chartId, String ds, int y) {
        return """
            {"chartId":"%s","datasourceId":"%s","type":"table","title":"明細",
             "gridPos":{"x":0,"y":%d,"w":12,"h":5},"mapping":{"columns":["region"]}}""".formatted(chartId, ds, y);
    }

    void appendFails(String id, String body, int code, String message) throws Exception {
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(code))
            .andExpect(jsonPath("$.error").value(message));
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
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
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
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));

        mvc.perform(delete("/api/dashboards/" + id).header("X-User-Id", "u2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        mvc.perform(delete("/api/dashboards/" + id).header("X-User-Id", "u1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1")).andExpect(status().isNotFound());
    }

    @Test
    void createWithSomeoneElsesDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
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
        mvc.perform(get("/api/dashboards/" + id + "/data").header("X-User-Id", "u2"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void invalidCardsAreRejectedOnCreateUpdateAndPreview() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);

        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"壞\",\"layout\":[null]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("第 1 張圖表是空的"));
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\"", "\"name\":\" \"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("儀表板名稱不能空白"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace(",\"gridPos\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4}", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("圖表 c1：缺少 gridPos"));
        mvc.perform(post("/api/dashboards/preview/data").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"type\":\"line\"", "\"type\":\"donut\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", startsWith("圖表 c1：type 必須是")));

        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/dashboards/" + id + "/data").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4));
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
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void intentsBecomeTheFirstBasisGroupAndAreValidated() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String withIntents = json.replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\",\"不要圓餅圖\"],");

        String id = create("u1", withIntents);
        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").doesNotExist())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(ds))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區每月營收", "不要圓餅圖")));

        // 沒有需求紀錄就沒有依據
        String plain = create("u1", json);
        mvc.perform(get("/api/dashboards/" + plain).header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis").doesNotExist());

        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"intents\":[\" \"],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("需求紀錄不能有空白的句子"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"basis\":[{\"datasourceId\":\""
                    + ds + "\",\"intents\":[\"" + "營".repeat(201) + "\"]}],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("想看什麼最多 200 字"));
    }

    @Test
    void createNeedsAtLeastOneCardAllFromOneSource() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String other = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"空\",\"layout\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("沒有要存入的圖表"));
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"混\",\"layout\":[" + card("c1", ds, 0) + "," + card("c2", other, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("一次存入的圖表要來自同一個資料來源"));
        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺\",\"layout\":[" + card("c1", ds, 0).replace("\"datasourceId\":\"" + ds + "\",", "") + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("圖表 c1：缺少 datasourceId"));
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void putChecksEverySourceAndDropsBasisGroupsThatLostTheirCharts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String strangers = TestData.uploadSales(mvc, "u2");
        String id = create("u1", "{\"name\":\"週報\",\"intents\":[\"各區每月營收\"],\"layout\":[" + card("c1", sales, 0) + "]}");
        String basis = "\"basis\":[{\"datasourceId\":\"" + sales + "\",\"intents\":[\"各區每月營收\"]},"
            + "{\"datasourceId\":\"" + returns + "\",\"intents\":[\"各區退貨\"]}]";

        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c1", sales, 0) + "," + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[*].datasourceId").value(contains(sales, returns)))
            .andExpect(jsonPath("$.basis[*].datasourceId").value(contains(sales, returns)));

        // 銷售的圖全部拿掉：銷售那組依據跟著刪
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(returns))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區退貨")));

        // backlog 第 42 行：圖指到別人的來源就擋下來
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", strangers, 0) + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"basis\":[{\"intents\":[\"各區退貨\"]}],\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("依據缺少 datasourceId"));

        // 最後一張也可以拿掉：layout 變成空的，依據也沒了
        mvc.perform(put("/api/dashboards/" + id).header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.length()").value(0))
            .andExpect(jsonPath("$.basis").doesNotExist());
    }

    @Test
    void theListNoLongerCarriesADatasourceId() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        create("u1", TestData.dashboardJson(ds));
        mvc.perform(get("/api/dashboards").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].name").value("銷售總覽"))
            .andExpect(jsonPath("$[0].updatedAt").exists())
            .andExpect(jsonPath("$[0].datasourceId").doesNotExist());
    }

    @Test
    void appendRenumbersTheBatchAndStacksItBelowTheExistingCharts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String id = create("u1", TestData.dashboardJson(sales).replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\"],"));
        // 預覽裡的一批圖：chartId 跟既有的重複也沒關係，會重新編號；name 不理
        String batch = "{\"name\":\"亂改\",\"intents\":[\"各區退貨\"],\"layout\":["
            + card("c1", returns, 0) + "," + tableCard("c2", returns, 4) + "]}";

        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("銷售總覽"))
            .andExpect(jsonPath("$.layout[*].chartId").value(contains("c1", "c2", "c3", "c4", "c5", "c6")))
            // 既有的圖最下面到 y 4 + h 4 = 8：整批往下移 8，彼此的相對位置不變
            .andExpect(jsonPath("$.layout[4].gridPos.y").value(8))
            .andExpect(jsonPath("$.layout[5].gridPos.y").value(12))
            .andExpect(jsonPath("$.layout[5].gridPos.w").value(12))
            .andExpect(jsonPath("$.layout[4].datasourceId").value(returns))
            .andExpect(jsonPath("$.basis[*].datasourceId").value(contains(sales, returns)))
            .andExpect(jsonPath("$.basis[1].intents").value(contains("各區退貨")));

        // 沒有需求紀錄：圖照加，依據不多一組
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"layout\":[" + card("c9", returns, 3) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[6].chartId").value("c7"))
            .andExpect(jsonPath("$.layout[6].gridPos.y").value(17))
            .andExpect(jsonPath("$.basis.length()").value(2));
    }

    @Test
    void appendIsValidatedAndScopedToTheOwner() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String strangers = TestData.uploadSales(mvc, "u2");
        String id = create("u1", TestData.dashboardJson(sales));
        String ok = "{\"layout\":[" + card("c1", returns, 0) + "]}";

        appendFails(UUID.randomUUID().toString(), ok, 404, "找不到儀表板");
        mvc.perform(post("/api/dashboards/" + id + "/charts").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content(ok))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        appendFails(id, "{\"layout\":[]}", 400, "沒有要存入的圖表");
        appendFails(id, "{\"layout\":[" + card("c1", returns, 0) + "," + card("c2", sales, 4) + "]}", 400,
            "一次存入的圖表要來自同一個資料來源");
        appendFails(id, "{\"layout\":[" + card("c1", strangers, 0) + "]}", 404, "找不到資料源");
        appendFails(id, "{\"layout\":[" + card("c1", returns, 0).replace("\"type\":\"bar\"", "\"type\":\"donut\"") + "]}", 400,
            "圖表 c1：type 必須是 line、bar、pie、scatter、table、radar、heatmap、stacked_bar 其中之一");
        appendFails(id, "{\"intents\":[\" \"],\"layout\":[" + card("c1", returns, 0) + "]}", 400, "需求紀錄不能有空白的句子");

        mvc.perform(get("/api/dashboards/" + id).header("X-User-Id", "u1"))
            .andExpect(jsonPath("$.layout.length()").value(4));
    }
}
