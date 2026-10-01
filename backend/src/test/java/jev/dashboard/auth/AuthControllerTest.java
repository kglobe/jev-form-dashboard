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

import java.util.UUID;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

        // 讀資料庫，不是讀 session 裡的權限
        jdbc.sql("update account set admin = false where username = 'boss'").update();
        mvc.perform(get("/api/auth/me").session(s))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admin").value(false));
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
}
