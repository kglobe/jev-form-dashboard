package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    @Test
    void unknownRouteSaysSo() throws Exception {
        mockMvc.perform(get("/api/nope").with(TestData.as("u1")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到這個 API"));
    }

    @Test
    void wrongHttpMethodSaysSoAndKeepsTheAllowHeader() throws Exception {
        mockMvc.perform(delete("/api/datasources").with(TestData.as("u1")))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.error").value("不支援這個 HTTP 方法"));
    }

    @Test
    void unreadableJsonBodySaysSo() throws Exception {
        mockMvc.perform(post("/api/dashboards").with(TestData.as("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("請求內容不是正確的 JSON"));
    }

    @Test
    void badPathVariableNamesTheParameter() throws Exception {
        mockMvc.perform(get("/api/dashboards/not-a-uuid").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("參數格式錯誤：id"));
    }

    @Test
    void missingParameterOrPartNamesIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv", "a\n1\n".getBytes(UTF_8));
        mockMvc.perform(multipart("/api/datasources/upload").file(file).with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("缺少參數：name"));
        mockMvc.perform(multipart("/api/datasources/upload").param("name", "空").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("缺少參數：file"));
    }
}
