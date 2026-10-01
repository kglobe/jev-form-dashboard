package jev.dashboard.auth;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    /** 真的登入一次，回傳登入後的工作階段。 */
    MockHttpSession login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(formLogin("/api/auth/login").user(username).password(password))
            .andExpect(status().isNoContent())
            .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    void loginFails(String username, String password, String message) throws Exception {
        mvc.perform(formLogin("/api/auth/login").user(username).password(password))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value(message));
    }

    static MockHttpServletRequestBuilder passwordChange(String current, String password) {
        return post("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"current\":\"%s\",\"password\":\"%s\"}".formatted(current, password));
    }

    @Test
    void passwordLoginReturns204AndMeReadsTheDatabase() throws Exception {
        TestData.addAccount(jdbc, "boss", true);
        MvcResult r = mvc.perform(formLogin("/api/auth/login").user("boss").password(TestData.PASSWORD))
            .andExpect(status().isNoContent())
            .andExpect(authenticated().withUsername("boss"))
            .andReturn();
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("boss"))
            .andExpect(jsonPath("$.admin").value(true))
            .andExpect(jsonPath("$.password").value(true));

        // 讀資料庫，不是讀 session（admin 跟 session 的權限不一樣時，帳號檢查會先回 401，所以用 password 看）
        jdbc.sql("update account set password_hash = null where username = 'boss'").update();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.password").value(false));
    }

    @Test
    void wrongPasswordUnknownAccountNoPasswordAndDisabledWithWrongPasswordAllSayTheSame() throws Exception {
        jdbc.sql("insert into account (username) values ('sso-only')").update();
        jdbc.sql("update account set enabled = false where username = 'u2'").update();

        loginFails("u1", "wrong-password-1", "帳號或密碼錯誤");
        loginFails("nobody", TestData.PASSWORD, "帳號或密碼錯誤");
        loginFails("sso-only", TestData.PASSWORD, "帳號或密碼錯誤");
        loginFails("u2", "wrong-password-1", "帳號或密碼錯誤");
    }

    @Test
    void aDisabledAccountWithTheRightPasswordIsToldSo() throws Exception {
        jdbc.sql("update account set enabled = false where username = 'u2'").update();
        loginFails("u2", TestData.PASSWORD, "帳號已停用，請聯絡管理員");
    }

    @Test
    void usernameIsTrimmedAndLowercased() throws Exception {
        MockHttpSession s = login("  U1 ", TestData.PASSWORD);
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("u1"));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession s = login("u1", TestData.PASSWORD);
        mvc.perform(post("/api/auth/logout").session(s).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void apiWithoutLoginIs401JsonInUtf8() throws Exception {
        mvc.perform(get("/api/dashboards"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
            .andExpect(content().string("{\"error\":\"請先登入\"}"));
    }

    @Test
    void optionsNeedNoLoginAndHaveNoOidcByDefault() throws Exception { // spec 4.1
        mvc.perform(get("/api/auth/options"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"oidc\":null}", JsonCompareMode.STRICT));
    }

    @Test
    void aPostWithoutCsrfIs403PageExpired() throws Exception {
        mvc.perform(post("/api/dashboards").with(user("u1")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("頁面已過期，請重新整理"));
    }

    @Test
    void legacyDataBelongsToTheAccountWithTheSameName() throws Exception { // spec 8.1「既有資料」
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into dashboard (id, name, created_by, config_json)
                values (:id, '舊的', 'u9', cast('{"layout":[]}' as jsonb))""")
            .param("id", id).update();
        TestData.addAccount(jdbc, "u9", false);

        mvc.perform(get("/api/dashboards").with(TestData.as("u9")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id").value(contains(id.toString())));
        mvc.perform(get("/api/dashboards/" + id).with(TestData.as("u9")))
            .andExpect(status().isOk());
    }

    @Test
    void changingYourOwnPasswordEndsEverySessionIncludingThisOne() throws Exception { // spec 4.1
        MockHttpSession s1 = login("u1", TestData.PASSWORD);
        MockHttpSession s2 = login("u1", TestData.PASSWORD);
        mvc.perform(passwordChange(TestData.PASSWORD, "new-password-123").session(s1).with(csrf()))
            .andExpect(status().isNoContent());

        for (MockHttpSession s : List.of(s1, s2)) {
            mvc.perform(get("/api/auth/me").session(s))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("請先登入"));
        }
        loginFails("u1", TestData.PASSWORD, "帳號或密碼錯誤");
        login("u1", "new-password-123");
    }

    @Test
    void changePasswordRejectsAWrongCurrentPasswordAndSsoAccounts() throws Exception {
        mvc.perform(passwordChange("wrong-password-1", "new-password-123").with(TestData.as("u1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("目前的密碼不對"));

        jdbc.sql("insert into account (username) values ('sso-a')").update();
        mvc.perform(passwordChange("wrong-password-1", "new-password-123").with(TestData.as("sso-a")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("這個帳號用單一登入，沒有密碼可以改"));
    }

    @Test
    void changePasswordChecksThePasswordRules() throws Exception { // spec 2.1
        mvc.perform(passwordChange(TestData.PASSWORD, "short-1").with(TestData.as("u1"))) // 7 個字
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("密碼至少要 8 個字元"));
        mvc.perform(passwordChange(TestData.PASSWORD, "營".repeat(25)).with(TestData.as("u1"))) // 75 bytes
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("密碼太長，最多 72 個位元組（約 24 個中文字）"));

        TestData.addAccount(jdbc, "long-account-1", false);
        mvc.perform(passwordChange(TestData.PASSWORD, "LONG-ACCOUNT-1").with(TestData.as("long-account-1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("密碼不能跟帳號一樣"));

        // 成功會讓 u1 的工作階段失效，放最後
        mvc.perform(passwordChange(TestData.PASSWORD, "營".repeat(24)).with(TestData.as("u1"))) // 72 bytes
            .andExpect(status().isNoContent());
    }

    @Test
    void aDisabledAccountsSessionGets401AndStaysDeadAfterReEnabling() throws Exception { // spec 2.1
        MockHttpSession s = login("u1", TestData.PASSWORD);
        jdbc.sql("update account set enabled = false where username = 'u1'").update();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isUnauthorized());

        jdbc.sql("update account set enabled = true where username = 'u1'").update();
        mvc.perform(get("/api/auth/me").session(s)) // 上一次就失效了
            .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionCreatedBeforeSessionsValidAfterGets401() throws Exception { // spec 2.1
        MockHttpSession s = login("u1", TestData.PASSWORD);
        jdbc.sql("update account set sessions_valid_after = :t where username = 'u1'")
            .param("t", Instant.ofEpochMilli(s.getCreationTime() + 1).atOffset(ZoneOffset.UTC)).update();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isUnauthorized());

        Thread.sleep(2); // 測試密碼是 cost 4，新的工作階段可能跟變動落在同一毫秒（plan「實作上的決定」第 6 點）
        MockHttpSession s2 = login("u1", TestData.PASSWORD);
        mvc.perform(get("/api/auth/me").session(s2))
            .andExpect(status().isOk());
    }
}
