package jev.dashboard.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.startsWith;
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
    void invalidCardsAreRejectedOnCreateUpdateAndPreview() throws Exception {
        String ds = TestData.uploadSales(mvc, "u1");
        String json = TestData.dashboardJson(ds);
        String id = create("u1", json);

        mvc.perform(post("/api/dashboards").header("X-User-Id", "u1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"壞\",\"datasourceId\":\"" + ds + "\",\"layout\":[null]}"))
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
            .andExpect(status().isNotFound());
    }
}
