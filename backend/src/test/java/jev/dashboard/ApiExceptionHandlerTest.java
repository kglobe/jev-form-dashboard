package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// 真的起 Tomcat：上傳大小限制只有真的 multipart 解析才會觸發，MockMvc 模擬不出來
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.servlet.multipart.max-file-size=1KB")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {
    @Autowired MockMvc mockMvc;
    @Autowired TestRestTemplate http;

    @Test
    void missingRequestHeaderReturnsErrorBody() throws Exception {
        mockMvc.perform(get("/api/test/echo-user"))
            .andExpect(status().is4xxClientError())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unknownRouteSaysSo() throws Exception {
        mockMvc.perform(get("/api/nope").header("X-User-Id", "u1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到這個 API"));
    }

    @Test
    void wrongHttpMethodSaysSoAndKeepsTheAllowHeader() throws Exception {
        mockMvc.perform(delete("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.error").value("不支援這個 HTTP 方法"));
    }

    @Test
    void unreadableJsonBodySaysSo() throws Exception {
        mockMvc.perform(post("/api/dashboards").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("請求內容不是正確的 JSON"));
    }

    @Test
    void badPathVariableNamesTheParameter() throws Exception {
        mockMvc.perform(get("/api/dashboards/not-a-uuid").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("參數格式錯誤：id"));
    }

    @Test
    void missingParameterOrPartNamesIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.csv", "text/csv", "a\n1\n".getBytes(UTF_8));
        mockMvc.perform(multipart("/api/datasources/upload").file(file).header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("缺少參數：name"));
        mockMvc.perform(multipart("/api/datasources/upload").param("name", "空").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("缺少參數：file"));
    }

    @Test
    void uploadOverTheLimitIs413WithTheConfiguredLimit() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[2048]) {
            @Override
            public String getFilename() {
                return "big.csv";
            }
        });
        body.add("name", "大檔");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-User-Id", "u1");
        ResponseEntity<String> r = http.postForEntity("/api/datasources/upload", new HttpEntity<>(body, headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(JsonPath.<String>read(r.getBody(), "$.error")).isEqualTo("檔案太大，上限 1KB");
    }

    @RestController
    static class TestOnlyController {
        @GetMapping("/api/test/echo-user")
        String echo(@RequestHeader("X-User-Id") String userId) {
            return userId;
        }
    }
}
