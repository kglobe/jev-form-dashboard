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
