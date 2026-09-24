package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            .andExpect(jsonPath("$.error", containsString("${DS_環境變數名}")));
    }
}
