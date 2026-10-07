package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 資料來源共享 spec 7.1：分享 API、使用者能做和不能做的事、取消分享或刪除之後的卡片。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasourceSharingTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    void share(String user, String id, String body, int code) throws Exception {
        mvc.perform(put("/api/datasources/" + id + "/share").with(TestData.as(user))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(code));
    }

    /** owner 的儀表板，每張卡一個來源，照 sources 的順序；建立時只能一個來源，所以先建第一張再整份 PUT。回傳儀表板 id。 */
    String dashboard(String owner, String... sources) throws Exception {
        String[] cards = new String[sources.length];
        for (int i = 0; i < sources.length; i++) {
            cards[i] = """
                {"chartId":"c%d","datasourceId":"%s","type":"bar","title":"amount 依 region",
                 "gridPos":{"x":0,"y":%d,"w":6,"h":4},"mapping":{"x":"region","value":"amount","aggregate":"sum"}}"""
                .formatted(i + 1, sources[i], i * 4);
        }
        String body = mvc.perform(post("/api/dashboards").with(TestData.as(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"看板\",\"layout\":[" + cards[0] + "]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/dashboards/" + id).with(TestData.as(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"看板\",\"layout\":[" + String.join(",", cards) + "]}"))
            .andExpect(status().isOk());
        return id;
    }

    @Test
    void theOwnerSharesAndReadsTheShareBack() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources/" + id + "/share").with(TestData.as("u1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allUsers").value(false))
            .andExpect(jsonPath("$.users").value(empty()));

        mvc.perform(put("/api/datasources/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allUsers\":true,\"users\":[\" U3 \",\"u2\",\"u2\",null,\"  \"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allUsers").value(true))
            .andExpect(jsonPath("$.users").value(contains("u2", "u3")));
        mvc.perform(get("/api/datasources/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.allUsers").value(true))
            .andExpect(jsonPath("$.users").value(contains("u2", "u3")));

        mvc.perform(put("/api/datasources/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allUsers\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allUsers").value(false))
            .andExpect(jsonPath("$.users").value(empty()));
    }

    @Test
    void sharingRejectsUnknownAccountsAndYourselfButTakesDisabledOnes() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(put("/api/datasources/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allUsers\":false,\"users\":[\"Nobody\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "找不到帳號「nobody」。用單一登入的人要先登入過一次才會有帳號，也可以先打開「所有帳號都能用」"));
        mvc.perform(put("/api/datasources/" + id + "/share").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allUsers\":false,\"users\":[\"U1\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不用分享給自己"));

        jdbc.sql("update account set enabled = false where username = 'u3'").update();
        share("u1", id, "{\"allUsers\":false,\"users\":[\"u3\"]}", 200);
        mvc.perform(get("/api/datasources/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.users").value(contains("u3")));
    }

    void notFound(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req.with(TestData.as("u2"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void someoneItIsSharedWithCanBuildOnItButCannotChangeIt() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        share("u1", id, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);

        mvc.perform(get("/api/datasources").with(TestData.as("u2")))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].createdBy").value("u1"));
        mvc.perform(post("/api/dashboards").with(TestData.as("u2"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(id)))
            .andExpect(status().isOk());
        mvc.perform(post("/api/dashboards").with(TestData.as("u3"))
                .contentType(MediaType.APPLICATION_JSON).content(TestData.dashboardJson(id)))
            .andExpect(status().isNotFound());

        // 資料來源共享 spec 2.1、4.2：修改類一律 404，跟別人的來源一樣
        notFound(multipart("/api/datasources/" + id + "/rows")
            .file(new MockMultipartFile("file", "new.csv", "text/csv", "region\n北區\n".getBytes(UTF_8))).param("mode", "replace"));
        notFound(patch("/api/datasources/" + id + "/sensitive").contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"));
        notFound(patch("/api/datasources/" + id + "/labels").contentType(MediaType.APPLICATION_JSON).content("{\"labels\":{}}"));
        notFound(patch("/api/datasources/" + id + "/ratios").contentType(MediaType.APPLICATION_JSON).content("{\"ratios\":[]}"));
        notFound(delete("/api/datasources/" + id));
        notFound(get("/api/datasources/" + id + "/usage"));
        notFound(get("/api/datasources/" + id + "/share"));
        notFound(put("/api/datasources/" + id + "/share").contentType(MediaType.APPLICATION_JSON)
            .content("{\"allUsers\":true,\"users\":[\"u3\"]}"));
        mvc.perform(get("/api/datasources/" + id + "/share").with(TestData.as("u1")))
            .andExpect(jsonPath("$.allUsers").value(false))
            .andExpect(jsonPath("$.users").value(contains("u2")));
    }

    @Test
    void othersDoNotSeeTheApiUrlOrHeaders() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by, shared_all)
                values (:id, '外部', 'api', '[]', 'http://10.0.0.5/sales', '{"Authorization":"${DS_JEV_TEST_UNSET}"}', 'u1', true)""")
            .param("id", id).update();

        mvc.perform(get("/api/datasources").with(TestData.as("u1")))
            .andExpect(jsonPath("$[0].apiUrl").value("http://10.0.0.5/sales"))
            .andExpect(jsonPath("$[0].apiHeaders.Authorization").value("${DS_JEV_TEST_UNSET}"));
        mvc.perform(get("/api/datasources").with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].id").value(id.toString()))
            .andExpect(jsonPath("$[0].apiUrl").value(nullValue()))
            .andExpect(jsonPath("$[0].apiHeaders").value(anEmptyMap()));
    }

    @Test
    void usageSaysWhetherItIsShared() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources/" + id + "/usage").with(TestData.as("u1"))).andExpect(jsonPath("$.shared").value(false));
        share("u1", id, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);
        mvc.perform(get("/api/datasources/" + id + "/usage").with(TestData.as("u1"))).andExpect(jsonPath("$.shared").value(true));
        share("u1", id, "{\"allUsers\":true,\"users\":[]}", 200);
        mvc.perform(get("/api/datasources/" + id + "/usage").with(TestData.as("u1"))).andExpect(jsonPath("$.shared").value(true));
    }

    @Test
    void cardsSayWhenTheirSourceStopsBeingSharedOrIsDeletedAndTheDashboardStillOpens() throws Exception {
        // 資料來源共享 spec 2.3
        String a = TestData.uploadCsv(mvc, "u1", "甲", "region,amount\n北區,1\n");
        String b = TestData.uploadCsv(mvc, "u1", "乙", "region,amount\n南區,2\n");
        share("u1", a, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);
        share("u1", b, "{\"allUsers\":true,\"users\":[]}", 200);
        String id = dashboard("u2", a, b);
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].error").doesNotExist())
            .andExpect(jsonPath("$[1].error").doesNotExist());

        share("u1", a, "{\"allUsers\":false,\"users\":[]}", 200);
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").value("SOURCE_UNSHARED"))
            .andExpect(jsonPath("$[1].error").doesNotExist());

        // 全部都是停止分享或已刪除：不算「全部失敗」，照樣打得開
        mvc.perform(delete("/api/datasources/" + b).with(TestData.as("u1"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").value("SOURCE_UNSHARED"))
            .andExpect(jsonPath("$[1].error").value("SOURCE_DELETED"));

        // 擁有者停用也是停止分享
        share("u1", a, "{\"allUsers\":true,\"users\":[]}", 200);
        jdbc.sql("update account set enabled = false where username = 'u1'").update();
        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].error").value("SOURCE_UNSHARED"));
    }

    @Test
    void inOneRequestACardOnAMergedSourcesInputStillNeedsItsOwnShare() throws Exception {
        // 資料來源共享 spec 2.2：載入 m 時 n 進了快取，直接用 n 的卡片還是拿不到
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount\n北區,1\n");
        String s = TestData.uploadCsv(mvc, "u1", "南區", "region,amount\n南區,2\n");
        String m = merged("u1", "全區", n, s);
        share("u1", m, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);
        share("u1", n, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);
        String id = dashboard("u2", m, n);

        share("u1", n, "{\"allUsers\":false,\"users\":[]}", 200);

        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].error").doesNotExist())
            .andExpect(jsonPath("$[0].rows.length()").value(2))
            .andExpect(jsonPath("$[1].error").value("SOURCE_UNSHARED"));
    }

    @Test
    void aMergedSourceBuiltOnASharedSourceStopsWorkingWhenTheShareIsRevoked() throws Exception {
        String a = TestData.uploadCsv(mvc, "u1", "甲", "region,amount\n北區,1\n");
        String b = TestData.uploadCsv(mvc, "u2", "乙", "region,amount\n南區,2\n");
        share("u1", a, "{\"allUsers\":false,\"users\":[\"u2\"]}", 200);
        String m = merged("u2", "合起來", a, b);
        String id = dashboard("u2", m);

        share("u1", a, "{\"allUsers\":false,\"users\":[]}", 200);

        mvc.perform(get("/api/dashboards/" + id + "/data").with(TestData.as("u2")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("合併來源的輸入來源已刪除或停止分享，這個合併來源不能用了"));
        mvc.perform(get("/api/datasources").with(TestData.as("u2")))
            .andExpect(jsonPath("$[0].id").value(m))
            .andExpect(jsonPath("$[0].schema[0].sensitiveFrom").value("（已刪除或停止分享的來源）"));
    }

    /** owner 的疊加合併來源：region、amount 同名直接對起來。 */
    String merged(String owner, String name, String first, String second) throws Exception {
        String def = """
            {"op":"union","inputs":["%1$s","%2$s"],"columns":[
              {"name":"region","from":{"%1$s":"region","%2$s":"region"}},
              {"name":"amount","from":{"%1$s":"amount","%2$s":"amount"}}],"sourceColumn":"來源"}""".formatted(first, second);
        String body = mvc.perform(post("/api/datasources/merged").with(TestData.as(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"merge\":" + def + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }
}
