package jev.dashboard.jev;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            {"name":"分區銷售","datasourceId":"%s","layout":[
              {"chartId":"c1","type":"stacked_bar","title":"分區月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","series":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","type":"line","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
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
}
