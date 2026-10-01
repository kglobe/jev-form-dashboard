package jev.dashboard.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.FakeOidcServer;
import jev.dashboard.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/** 唯讀分享 spec 2.3、8.1「OIDC」：另開一個接 FakeOidcServer 的 context。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcLoginTest {
    static final FakeOidcServer OIDC = FakeOidcServer.start();

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("jev.auth.oidc.issuer-uri", OIDC::issuer);
        r.add("jev.auth.oidc.client-id", () -> FakeOidcServer.CLIENT_ID);
        r.add("jev.auth.oidc.client-secret", () -> "test-secret"); // 有 secret 也要有 PKCE（requireProofKey）
    }

    @AfterAll
    static void stop() {
        OIDC.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRepository accounts;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    static MultiValueMap<String, String> query(MvcResult r) {
        return UriComponentsBuilder.fromUriString(r.getResponse().getRedirectedUrl()).build().getQueryParams();
    }

    /** spec 8.1 的五步：拿 authorize 網址、登記 code、帶同一個 session 回到 callback。 */
    MockHttpSession login(Map<String, Object> claims, String expectedRedirect) throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/oidc")).andExpect(status().isFound()).andReturn();
        MultiValueMap<String, String> q = query(start);
        String state = URLDecoder.decode(q.getFirst("state"), UTF_8); // state 有 base64 的 =，網址上是 %3D
        String code = OIDC.code(URLDecoder.decode(q.getFirst("nonce"), UTF_8), claims);
        MvcResult back = mvc.perform(get("/login/oauth2/code/oidc").param("code", code).param("state", state)
                .session((MockHttpSession) start.getRequest().getSession(false)))
            .andExpect(redirectedUrl(expectedRedirect))
            .andReturn();
        return (MockHttpSession) back.getRequest().getSession(false); // 登入時換了新的 session
    }

    List<String> usernames() {
        return jdbc.sql("select username from account order by username").query(String.class).list();
    }

    @Test
    void optionsShowTheButtonLabel() throws Exception { // label 在 application-test.yml 固定，不看 .env
        mvc.perform(get("/api/auth/options"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"oidc\":{\"label\":\"公司帳號\"}}", JsonCompareMode.STRICT));
    }

    @Test
    void issuerMustBeHttpsOrLoopback() {
        assertThatCode(() -> SecurityConfig.requireSecureIssuer("https://idp.example")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityConfig.requireSecureIssuer("http://127.0.0.1:9000")).doesNotThrowAnyException();
        for (String bad : List.of("http://idp.example", "http://127.0.0.1.evil.example", "ftp://127.0.0.1")) {
            assertThrows(IllegalStateException.class, () -> SecurityConfig.requireSecureIssuer(bad), bad);
        }
    }

    @Test
    void theAuthorizeUrlUsesPkceAndTheOidcScopes() throws Exception {
        MvcResult r = mvc.perform(get("/oauth2/authorization/oidc")).andExpect(status().isFound()).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).startsWith(OIDC.issuer() + "/authorize?");
        MultiValueMap<String, String> q = query(r);
        assertThat(q.getFirst("code_challenge")).isNotBlank();
        assertThat(q.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(URLDecoder.decode(q.getFirst("scope"), UTF_8).split(" ")).contains("openid", "profile", "email");
        assertThat(q.getFirst("nonce")).isNotBlank();
    }

    @Test
    void firstLoginCreatesAnAccountAndTheSameSubjectKeepsIt() throws Exception {
        MockHttpSession s = login(Map.of("sub", "s-1", "preferred_username", " Alice@Corp "), "/");
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"username\":\"alice@corp\",\"admin\":false,\"password\":false}",
                JsonCompareMode.STRICT));
        Account a = accounts.find("alice@corp").orElseThrow();
        assertThat(a.oidcIssuer()).isEqualTo(OIDC.issuer());
        assertThat(a.oidcSubject()).isEqualTo("s-1");
        int count = usernames().size();

        MockHttpSession again = login(Map.of("sub", "s-1", "preferred_username", "renamed"), "/");
        mvc.perform(get("/api/auth/me").session(again))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"username\":\"alice@corp\"}"));
        assertThat(usernames()).hasSize(count).doesNotContain("renamed");
    }

    @Test
    void aVerifiedEmailIsUsedWhenThereIsNoPreferredUsername() throws Exception {
        MockHttpSession s = login(Map.of("sub", "s-2", "email", "bob@corp", "email_verified", true), "/");
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"username\":\"bob@corp\"}"));
    }

    @Test
    void theIdpCannotPickTheLocalName() throws Exception { // spec 2.3 第 4 點：本機帳號名稱不能被 IdP 的同名 claim 換掉
        MockHttpSession s = login(Map.of("sub", "x-1", "preferred_username", "mallory", "jev_username", "u1"), "/");
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"username\":\"mallory\"}"));
    }

    @Test
    void apiStillAnswers401JsonInsteadOfRedirectingToTheIdp() throws Exception {
        mvc.perform(get("/api/dashboards").header("Accept", "text/html"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"請先登入\"}", JsonCompareMode.STRICT));
    }

    @Test
    void blockedLogins() throws Exception {
        // 同名的密碼帳號：不能用名稱接上（spec 2.3 第 4 點）
        TestData.addAccount(jdbc, "carol", false);
        login(Map.of("sub", "s-3", "preferred_username", "carol"), "/?login_error=username_taken");

        // 同名的舊資料
        jdbc.sql("""
                insert into dashboard (id, name, created_by, config_json)
                values (:id, '舊的', 'dave', cast('{"layout":[]}' as jsonb))""")
            .param("id", UUID.randomUUID()).update();
        login(Map.of("sub", "s-4", "preferred_username", "dave"), "/?login_error=username_taken");
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, created_by)
                values (:id, '舊的', 'upload', '[]', 'hank')""")
            .param("id", UUID.randomUUID()).update();
        login(Map.of("sub", "s-9", "preferred_username", "hank"), "/?login_error=username_taken");

        // 停用的帳號
        login(Map.of("sub", "s-5", "preferred_username", "erin"), "/");
        jdbc.sql("update account set enabled = false where username = 'erin'").update();
        login(Map.of("sub", "s-5", "preferred_username", "erin"), "/?login_error=account_disabled");

        // 沒有帳號名稱，或名稱不合規則
        login(Map.of("sub", "s-6", "email", "frank@corp", "email_verified", false), "/?login_error=no_username");
        login(Map.of("sub", "s-7", "email", "gina@corp"), "/?login_error=no_username");
        login(Map.of("sub", "s-8", "preferred_username", "-bad"), "/?login_error=no_username");

        assertThat(usernames()).containsExactly("carol", "erin", "u1", "u2", "u3");
        assertThat(accounts.find("carol").orElseThrow().oidcSubject()).isNull();
    }
}
