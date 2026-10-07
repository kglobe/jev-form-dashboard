package jev.dashboard.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Collections;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        String body = mvc.perform(post("/api/dashboards").with(TestData.as(user))
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
        mvc.perform(post("/api/dashboards/" + id + "/charts").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(code))
            .andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void crudIsScopedToTheOwner() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);

        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("銷售總覽"))
            .andExpect(jsonPath("$.createdBy").value("u1"))
            .andExpect(jsonPath("$.layout.length()").value(4))
            .andExpect(jsonPath("$.layout[0].gridPos.x").value(0));
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        mvc.perform(get("/api/dashboards").with(TestData.as("u1"))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/dashboards").with(TestData.as("u2"))).andExpect(jsonPath("$.length()").value(0));

        String moved = json.replaceFirst("\\{\"x\":0,\"y\":0,", "{\"x\":6,\"y\":8,");
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(moved))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[0].gridPos.x").value(6))
            .andExpect(jsonPath("$.layout[0].gridPos.y").value(8));
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content(moved))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));

        mvc.perform(delete("/api/dashboards/" + id).with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        mvc.perform(delete("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(status().isNotFound());
    }

    void putShare(String user, String id, String body, int code) throws Exception {
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as(user))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(code));
    }

    @Test
    void theOwnerSharesAndReadsTheShareBack() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        mvc.perform(get("/api/dashboards/" + id + "/share").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicLink").value(false))
            .andExpect(jsonPath("$.users").value(empty()));

        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicLink\":true,\"users\":[\" U3 \",\"u2\",\"u2\",null,\"  \"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicLink").value(true))
            .andExpect(jsonPath("$.users").value(contains("u2", "u3")));
        mvc.perform(get("/api/dashboards/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.publicLink").value(true))
            .andExpect(jsonPath("$.users").value(contains("u2", "u3")));

        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicLink").value(false))
            .andExpect(jsonPath("$.users").value(empty()));
    }

    @Test
    void sharingRejectsUnknownAccountsAndYourself() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":false,\"users\":[\"Nobody\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("找不到帳號「nobody」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結"));
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":false,\"users\":[\"U1\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不用分享給自己"));

        jdbc.sql("update account set enabled = false where username = 'u3'").update();
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u3\"]}", 200);
        mvc.perform(get("/api/dashboards/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.users").value(contains("u3")));
    }

    @Test
    void onlyTheOwnerSeesOrChangesTheShare() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(get("/api/dashboards/" + id + "/share").with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        mvc.perform(put("/api/dashboards/" + id + "/share").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":true,\"users\":[\"u3\"]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
        mvc.perform(get("/api/dashboards/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.publicLink").value(false))
            .andExpect(jsonPath("$.users").value(contains("u2")));
    }

    @Test
    void sharedListsNamedSharesNewestFirstAndSkipsDisabledOwners() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String a = create("u1", TestData.dashboardJson(ds).replace("銷售總覽", "A"));
        String b = create("u1", TestData.dashboardJson(ds).replace("銷售總覽", "B"));
        String c = create("u3", TestData.dashboardJson(TestData.uploadSales(mvc, "u3")).replace("銷售總覽", "C"));
        putShare("u1", a, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        putShare("u1", b, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        putShare("u3", c, "{\"publicLink\":true,\"users\":[]}", 200);

        mvc.perform(get("/api/dashboards/shared").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(b))
            .andExpect(jsonPath("$[0].name").value("B"))
            .andExpect(jsonPath("$[0].owner").value("u1"))
            .andExpect(jsonPath("$[0].updatedAt").isNotEmpty())
            .andExpect(jsonPath("$[1].id").value(a))
            .andExpect(jsonPath("$[1].name").value("A"))
            .andExpect(jsonPath("$[1].owner").value("u1"))
            .andExpect(jsonPath("$[1].updatedAt").isNotEmpty());
        mvc.perform(get("/api/dashboards").with(TestData.as("u2"))).andExpect(jsonPath("$.length()").value(0));

        jdbc.sql("update account set enabled = false where username = 'u1'").update();
        mvc.perform(get("/api/dashboards/shared").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deletingADashboardDeletesItsShares() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\",\"u3\"]}", 200);
        assertThat(jdbc.sql("select count(*) from dashboard_share").query(Integer.class).single()).isEqualTo(2);
        mvc.perform(delete("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from dashboard_share").query(Integer.class).single()).isZero();
    }

    void notFound(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void aViewerReadsTheDashboardAndItsDataAsTheOwner() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdBy").value("u1"));
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].error").doesNotExist());
    }

    @Test
    void aViewerCannotChangeAnything() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        String moved = json.replaceFirst("\\{\"x\":0,\"y\":0,", "{\"x\":6,\"y\":8,");
        notFound(put("/api/dashboards/" + id).with(TestData.as("u2")).contentType(MediaType.APPLICATION_JSON).content(moved));
        notFound(delete("/api/dashboards/" + id).with(TestData.as("u2")));
        notFound(post("/api/dashboards/" + id + "/charts").with(TestData.as("u2"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"layout\":[" + card("c1", ds, 0) + "]}"));
        notFound(get("/api/dashboards/" + id + "/share").with(TestData.as("u2")));
        notFound(put("/api/dashboards/" + id + "/share").with(TestData.as("u2"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"publicLink\":true,\"users\":[\"u3\"]}"));

        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1")))
            .andExpect(jsonPath("$.layout.length()").value(4))
            .andExpect(jsonPath("$.layout[0].gridPos.x").value(0))
            .andExpect(jsonPath("$.layout[0].gridPos.y").value(0));
        mvc.perform(get("/api/datasources").with(TestData.as("u2"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void notSharedIs404AndThePublicLinkOpensItToAnyAccount() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        notFound(get("/api/dashboards/" + id).with(TestData.as("u3")));
        notFound(get("/api/dashboards/" + id + "/data").with(TestData.as("u3")));

        putShare("u1", id, "{\"publicLink\":true,\"users\":[]}", 200);
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u3"))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u3"))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboards/shared").with(TestData.as("u3"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revokingOrDisablingTheOwnerMakesIt404Again() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u2"))).andExpect(status().isOk());

        putShare("u1", id, "{\"publicLink\":false,\"users\":[]}", 200);
        notFound(get("/api/dashboards/" + id).with(TestData.as("u2")));

        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u2"))).andExpect(status().isOk());
        jdbc.sql("update account set enabled = false where username = 'u1'").update();
        notFound(get("/api/dashboards/" + id).with(TestData.as("u2")));
        notFound(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")));
    }

    @Test
    void aViewerSeesAGenericMessageWhenTheOwnersSourcesFail() throws Exception {
        // 測試的允許清單有 10.0.0.5；抓之前就因為環境變數不存在而失敗，不會連網路
        UUID ds = UUID.randomUUID();
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by)
                values (:id, '外部', 'api', '[]', 'http://10.0.0.5/sales', '{"Authorization":"${DS_JEV_TEST_UNSET}"}', 'u1')""")
            .param("id", ds).update();
        String id = create("u1", TestData.dashboardJson(ds.toString()));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        for (String path : new String[] {"/api/dashboards/" + id + "/data", "/api/jev/highlight/" + id}) {
            mvc.perform(get(path).with(TestData.as("u1")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("環境變數 DS_JEV_TEST_UNSET 未設定"));
            mvc.perform(get(path).with(TestData.as("u2")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("資料來源載入失敗，請聯絡儀表板擁有者"));
        }
    }

    @Test
    void createWithSomeoneElsesDatasourceIs404() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void dataTransformsEachCardAndFlagsMissingField() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String id = create("u1", TestData.dashboardJson(ds));
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")))
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
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到儀表板"));
    }

    @Test
    void invalidCardsAreRejectedOnCreateUpdateAndPreview() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);

        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"壞\",\"layout\":[null]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("第 1 張圖表是空的"));
        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\"", "\"name\":\" \"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("儀表板名稱不能空白"));
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content(json.replace(",\"gridPos\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4}", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("圖表 c1：缺少 gridPos"));
        mvc.perform(post("/api/dashboards/preview/data").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"type\":\"line\"", "\"type\":\"donut\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", startsWith("圖表 c1：type 必須是")));

        mvc.perform(get("/api/dashboards").with(TestData.as("u1"))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void previewDataWorksWithoutSaving() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards/preview/data").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rows.length()").value(6));
        // 別人的來源跟已刪除的一樣查不到，只回 SOURCE_DELETED，不會拿到資料（刪除來源 spec 4.1）
        mvc.perform(post("/api/dashboards/preview/data").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(ds)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").value("SOURCE_DELETED"))
            .andExpect(jsonPath("$[0].rows").doesNotExist());
    }

    @Test
    void intentsBecomeTheFirstBasisGroupAndAreValidated() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String withIntents = json.replace("\"name\":\"銷售總覽\",",
            "\"name\":\"銷售總覽\",\"intents\":[\"各區每月營收\",\"不要圓餅圖\"],");

        String id = create("u1", withIntents);
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intents").doesNotExist())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(ds))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區每月營收", "不要圓餅圖")));

        // 沒有需求紀錄就沒有依據
        String plain = create("u1", json);
        mvc.perform(get("/api/dashboards/" + plain).with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis").doesNotExist());

        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"intents\":[\" \"],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("需求紀錄不能有空白的句子"));
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content(json.replace("\"name\":\"銷售總覽\",", "\"name\":\"銷售總覽\",\"basis\":[{\"datasourceId\":\""
                    + ds + "\",\"intents\":[\"" + "營".repeat(201) + "\"]}],")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("想看什麼最多 200 字"));
    }

    @Test
    void createNeedsAtLeastOneCardAllFromOneSource() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String other = TestData.uploadSales(mvc, "u1");
        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"空\",\"layout\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("沒有要存入的圖表"));
        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"混\",\"layout\":[" + card("c1", ds, 0) + "," + card("c2", other, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("一次存入的圖表要來自同一個資料來源"));
        mvc.perform(post("/api/dashboards").with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺\",\"layout\":[" + card("c1", ds, 0).replace("\"datasourceId\":\"" + ds + "\",", "") + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("圖表 c1：缺少 datasourceId"));
        mvc.perform(get("/api/dashboards").with(TestData.as("u1"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void putChecksEverySourceAndDropsBasisGroupsThatLostTheirCharts() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String strangers = TestData.uploadSales(mvc, "u2");
        String id = create("u1", "{\"name\":\"週報\",\"intents\":[\"各區每月營收\"],\"layout\":[" + card("c1", sales, 0) + "]}");
        String basis = "\"basis\":[{\"datasourceId\":\"" + sales + "\",\"intents\":[\"各區每月營收\"]},"
            + "{\"datasourceId\":\"" + returns + "\",\"intents\":[\"各區退貨\"]}]";

        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c1", sales, 0) + "," + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[*].datasourceId").value(contains(sales, returns)))
            .andExpect(jsonPath("$.basis[*].datasourceId").value(contains(sales, returns)));

        // 銷售的圖全部拿掉：銷售那組依據跟著刪
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.basis.length()").value(1))
            .andExpect(jsonPath("$.basis[0].datasourceId").value(returns))
            .andExpect(jsonPath("$.basis[0].intents").value(contains("各區退貨")));

        // backlog 第 42 行：圖指到別人的來源就擋下來
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", strangers, 0) + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"basis\":[{\"intents\":[\"各區退貨\"]}],\"layout\":[" + card("c2", returns, 4) + "]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("依據缺少 datasourceId"));

        // 最後一張也可以拿掉：layout 變成空的，依據也沒了
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\"," + basis + ",\"layout\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.length()").value(0))
            .andExpect(jsonPath("$.basis").doesNotExist());
    }

    @Test
    void aDashboardKeepsWorkingAfterOneOfItsSourcesIsDeleted() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String returns = TestData.uploadSales(mvc, "u1");
        String id = create("u1", "{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 0) + "]}");
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 0) + "," + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/datasources/" + returns).with(TestData.as("u1"))).andExpect(status().isNoContent());

        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").doesNotExist())
            .andExpect(jsonPath("$[1].error").value("SOURCE_DELETED"));

        // 拖拉照樣存得進去
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 2) + "," + card("c2", returns, 4) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout[0].gridPos.y").value(2));
        // 移除已刪除的卡片
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 2) + "]}"))
            .andExpect(status().isOk());
        // 新帶進來的來源照舊要檢查
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 2) + "," + card("c3", UUID.randomUUID().toString(), 6) + "]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void aDashboardWhoseSourcesAreAllDeletedStillOpens() throws Exception {
        String sales = TestData.uploadSales(mvc, "u1");
        String id = create("u1", "{\"name\":\"週報\",\"layout\":[" + card("c1", sales, 0) + "," + card("c2", sales, 4) + "]}");
        mvc.perform(delete("/api/datasources/" + sales).with(TestData.as("u1"))).andExpect(status().isNoContent());

        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").value("SOURCE_DELETED"))
            .andExpect(jsonPath("$[1].error").value("SOURCE_DELETED"));
    }

    @Test
    void theListNoLongerCarriesADatasourceId() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        create("u1", TestData.dashboardJson(ds));
        mvc.perform(get("/api/dashboards").with(TestData.as("u1")))
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

        mvc.perform(post("/api/dashboards/" + id + "/charts").with(TestData.as("u1"))
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
        mvc.perform(post("/api/dashboards/" + id + "/charts").with(TestData.as("u1"))
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
        mvc.perform(post("/api/dashboards/" + id + "/charts").with(TestData.as("u2"))
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

        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1")))
            .andExpect(jsonPath("$.layout.length()").value(4));
    }

    static final String NORTH_SOUTH = "{\"period\":\"last_quarter\",\"fields\":[{\"field\":\"region\",\"values\":[\"北區\",\"南區\"]}]}";

    /** 在儀表板 JSON 最前面加上 filters。 */
    static String withFilters(String dashboardJson, String filters) {
        return "{\"filters\":" + filters + "," + dashboardJson.substring(1);
    }

    MockHttpServletRequestBuilder putJson(String id, String json) {
        return put("/api/dashboards/" + id).with(TestData.as("u1")).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void filtersAreSavedAndReadBackKeptByAppendAndClearedByAPutWithoutThem() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        // 建立時不收篩選，跟 basis 一樣
        String id = create("u1", withFilters(json, NORTH_SOUTH));
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(jsonPath("$.filters").doesNotExist());

        mvc.perform(putJson(id, withFilters(json, NORTH_SOUTH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters.period").value("last_quarter"))
            .andExpect(jsonPath("$.filters.fields[0].field").value("region"))
            .andExpect(jsonPath("$.filters.fields[0].values").value(contains("北區", "南區")));
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1")))
            .andExpect(jsonPath("$.filters.period").value("last_quarter"))
            .andExpect(jsonPath("$.filters.fields[0].values").value(contains("北區", "南區")));

        mvc.perform(post("/api/dashboards/" + id + "/charts").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"layout\":[" + card("c1", ds, 0) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.layout.length()").value(5))
            .andExpect(jsonPath("$.filters.period").value("last_quarter"));

        mvc.perform(putJson(id, json)).andExpect(status().isOk()).andExpect(jsonPath("$.filters").doesNotExist());
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(jsonPath("$.filters").doesNotExist());

        // 沒有任何篩選就當作沒存
        mvc.perform(putJson(id, withFilters(json, "{\"period\":null,\"fields\":[]}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filters").doesNotExist());
    }

    static final String[][] BAD_FILTERS = {
        {"{\"period\":\"yesterday\"}", "期間只能是 本月、上月、本季、上季、今年、去年"},
        {"{\"fields\":[{\"field\":\"a\",\"values\":[\"1\"]},{\"field\":\"b\",\"values\":[\"1\"]},{\"field\":\"c\",\"values\":[\"1\"]},"
            + "{\"field\":\"d\",\"values\":[\"1\"]}]}", "分類篩選最多 3 個"},
        {"{\"fields\":[{\"field\":\" \",\"values\":[\"1\"]}]}", "篩選的欄位名稱不能空白"},
        {"{\"fields\":[{\"field\":\"" + "欄".repeat(51) + "\",\"values\":[\"1\"]}]}", "篩選的欄位名稱最多 50 個字"},
        {"{\"fields\":[{\"field\":\"a\",\"values\":[\"1\"]},{\"field\":\"a\",\"values\":[\"2\"]}]}", "同一個欄位只能篩選一次"},
        {"{\"fields\":[{\"field\":\"a\",\"values\":[]}]}", "每個篩選要選 1～200 個值"},
        {"{\"fields\":[{\"field\":\"a\",\"values\":[null]}]}", "每個篩選要選 1～200 個值"},
        {"{\"fields\":[{\"field\":\"a\",\"values\":[\"" + "值".repeat(201) + "\"]}]}", "篩選的值最多 200 個字"},
        // 200 個「北區」：URL 編碼後約 5400 個字，放不進網址
        {"{\"fields\":[{\"field\":\"a\",\"values\":[" + String.join(",", Collections.nCopies(200, "\"北區\"")) + "]}]}",
            "篩選選太多了，請少選一些值"},
    };

    @Test
    void savingAndQueryingShareTheFilterRules() throws Exception {
        String json = TestData.dashboardJson(TestData.uploadSales(mvc, "u1"));
        String id = create("u1", json);

        for (String[] bad : BAD_FILTERS) {
            mvc.perform(putJson(id, withFilters(json, bad[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(bad[1]));
            for (String path : new String[] {"/api/dashboards/" + id + "/data", "/api/jev/highlight/" + id}) {
                mvc.perform(get(path).param("filters", bad[0]).with(TestData.as("u1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(bad[1]));
            }
        }
        mvc.perform(get("/api/dashboards/" + id + "/data").param("filters", "{\"period\":").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("篩選的格式不對"));
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u1"))).andExpect(jsonPath("$.filters").doesNotExist());
    }

    @Test
    void dataAppliesTheFiltersAndNamesTheOnesThatDidNotApply() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String id = create("u1", TestData.dashboardJson(ds));
        // 沒帶篩選：不會自動套預設
        mvc.perform(putJson(id, withFilters(TestData.dashboardJson(ds), "{\"period\":\"last_year\"}"))).andExpect(status().isOk());
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].rows.length()").value(6))
            .andExpect(jsonPath("$[0].filterSkipped").doesNotExist());

        // 北區：1、2、4、6 月
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1"))
                .param("filters", "{\"fields\":[{\"field\":\"region\",\"values\":[\"北區\"]},{\"field\":\"通路\",\"values\":[\"網路\"]}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].rows[*][0]").value(contains("2024-01", "2024-02", "2024-04", "2024-06")))
            .andExpect(jsonPath("$[0].filterSkipped").value(contains("通路")))
            .andExpect(jsonPath("$[2].rows.length()").value(1))
            .andExpect(jsonPath("$[2].rows[0][1]").value(7400.0))
            .andExpect(jsonPath("$[3].error").value("FIELD_MISSING"));

        // 資料都是 2024 年：去年（2025 年以後）沒有資料；沒有 timeGrain 的圓餅圖用第一個日期欄位 order_date
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u1")).param("filters", "{\"period\":\"last_year\"}"))
            .andExpect(jsonPath("$[0].rows.length()").value(0))
            .andExpect(jsonPath("$[2].rows.length()").value(0))
            .andExpect(jsonPath("$[2].filterSkipped").doesNotExist());
    }

    @Test
    void filterOptionsListTheTextFieldsForAnyoneWhoCanSeeTheDashboard() throws Exception {
        String id = create("u1", TestData.dashboardJson(TestData.uploadSales(mvc, "u1")));
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(get("/api/dashboards/" + id + "/filter-options").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fields[*].field").value(contains("region", "product", "memo")))
            .andExpect(jsonPath("$.fields[0].values").value(contains("中區", "北區", "南區", "東區")))
            .andExpect(jsonPath("$.fields[1].values").value(contains("A", "B", "C")));
        // 檢視者只有卡片用到的欄位（spec 2.3，詳見下一個測試）
        mvc.perform(get("/api/dashboards/" + id + "/filter-options").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fields[*].field").value(contains("region")))
            .andExpect(jsonPath("$.fields[0].values").value(contains("中區", "北區", "南區", "東區")));
        notFound(get("/api/dashboards/" + id + "/filter-options").with(TestData.as("u3")));
    }

    @Test
    void someoneOtherThanTheOwnerOnlyFiltersOnFieldsTheCardsUseOrTheDefaultFilters() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);
        putShare("u1", id, "{\"publicLink\":false,\"users\":[\"u2\"]}", 200);
        String options = "/api/dashboards/" + id + "/filter-options";
        String data = "/api/dashboards/" + id + "/data";
        String productA = "{\"fields\":[{\"field\":\"product\",\"values\":[\"A\"]}]}";

        // 卡片用到的文字欄位只有 region；擁有者看得到全部的文字欄位
        mvc.perform(get(options).with(TestData.as("u2"))).andExpect(jsonPath("$.fields[*].field").value(contains("region")));
        mvc.perform(get(options).with(TestData.as("u1")))
            .andExpect(jsonPath("$.fields[*].field").value(contains("region", "product", "memo")));

        // 擁有者照樣能用 product 篩（A 在 1、2、3、5、6 月）；檢視者帶了 product，每張卡都不生效
        mvc.perform(get(data).param("filters", productA).with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].rows.length()").value(5))
            .andExpect(jsonPath("$[0].filterSkipped").doesNotExist());
        mvc.perform(get(data).param("filters", productA).with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].rows.length()").value(6))
            .andExpect(jsonPath("$[*].filterSkipped[0]").value(contains("product", "product", "product", "product")));
        mvc.perform(get(data).param("filters", "{\"fields\":[{\"field\":\"region\",\"values\":[\"北區\"]}]}").with(TestData.as("u2")))
            .andExpect(jsonPath("$[2].rows.length()").value(1))
            .andExpect(jsonPath("$[2].filterSkipped").doesNotExist());

        // 擁有者把 product 存進預設之後，檢視者也能用
        mvc.perform(putJson(id, withFilters(json, productA))).andExpect(status().isOk());
        mvc.perform(get(options).with(TestData.as("u2"))).andExpect(jsonPath("$.fields[*].field").value(contains("region", "product")));
        mvc.perform(get(data).param("filters", productA).with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].rows.length()").value(5))
            .andExpect(jsonPath("$[0].filterSkipped").doesNotExist());
    }
}
