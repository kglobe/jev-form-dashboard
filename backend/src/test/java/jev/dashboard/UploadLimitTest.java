package jev.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

// 真的起 Tomcat：上傳大小限制只有真的 multipart 解析才會觸發，MockMvc 模擬不出來。
// 不加 @AutoConfigureMockMvc：MockMvc 的 csrf() 會用反射把共用的 CsrfFilter 換成 session 版的 token repository，
// 同一個 context 裡真的 Tomcat 就拿不到 XSRF-TOKEN cookie（見 plan「實作上的決定」第 5 點）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.servlet.multipart.max-file-size=1KB")
@ActiveProfiles("test")
class UploadLimitTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    /** 同名的取最後一個：登入的回應會先清掉舊的 XSRF-TOKEN 再設新的。 */
    static String cookie(HttpHeaders headers, String name) {
        String value = null;
        for (String c : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (c.startsWith(name + "=")) value = c.substring(name.length() + 1, c.indexOf(';'));
        }
        return value;
    }

    @Test
    void uploadOverTheLimitIs413WithTheConfiguredLimit() {
        // 唯讀分享 spec 8.1：照前端的走法，cookie 版的 CSRF 走一次
        ResponseEntity<String> me = http.getForEntity("/api/auth/me", String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String t1 = cookie(me.getHeaders(), "XSRF-TOKEN");
        assertThat(t1).isNotEmpty();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "u1");
        form.add("password", TestData.PASSWORD);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        loginHeaders.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + t1);
        loginHeaders.set("X-XSRF-TOKEN", t1);
        ResponseEntity<String> login = http.postForEntity("/api/auth/login", new HttpEntity<>(form, loginHeaders), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String session = cookie(login.getHeaders(), "JSESSIONID");
        String t2 = cookie(login.getHeaders(), "XSRF-TOKEN");
        assertThat(session).isNotEmpty();
        assertThat(t2).isNotEmpty().isNotEqualTo(t1); // 登入時換了 token

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
        headers.set(HttpHeaders.COOKIE, "JSESSIONID=" + session + "; XSRF-TOKEN=" + t2);
        headers.set("X-XSRF-TOKEN", t2);
        ResponseEntity<String> r = http.postForEntity("/api/datasources/upload", new HttpEntity<>(body, headers), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(JsonPath.<String>read(r.getBody(), "$.error")).isEqualTo("檔案太大，上限 1KB");
    }
}
