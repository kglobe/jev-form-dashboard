package jev.dashboard.auth;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 唯讀分享 spec 4.2、8.1「帳號管理」「工作階段失效」。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
        TestData.addAccount(jdbc, "boss", true);
    }

    /** 真的登入一次，回傳登入後的工作階段。 */
    MockHttpSession login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(formLogin("/api/auth/login").user(username).password(password))
            .andExpect(status().isNoContent())
            .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** 用 TestData.PASSWORD（cost 4）重新登入前先等 2 毫秒（見 B2「同一毫秒的問題」）。 */
    MockHttpSession relogin(String username) throws Exception {
        Thread.sleep(2);
        return login(username, TestData.PASSWORD);
    }

    ResultActions create(String username, String password) throws Exception {
        return mvc.perform(post("/api/admin/accounts").with(TestData.asAdmin("boss"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"%s\",\"password\":\"%s\",\"admin\":false}".formatted(username, password)));
    }

    ResultActions patchAccount(String username, String body) throws Exception {
        return mvc.perform(patch("/api/admin/accounts/" + username).with(TestData.asAdmin("boss"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions resetPassword(String username, String password) throws Exception {
        return mvc.perform(put("/api/admin/accounts/" + username + "/password").with(TestData.asAdmin("boss"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"%s\"}".formatted(password)));
    }

    static void error(ResultActions r, int status, String message) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void nonAdminsGet403() throws Exception {
        error(mvc.perform(get("/api/admin/accounts").with(TestData.as("u1"))), 403, "沒有權限");
        error(mvc.perform(get("/api/admin/accounts")), 401, "請先登入");
    }

    @Test
    void aRealLoginGetsItsRoleFromTheAccount() throws Exception { // spec 2.2：TestData.as 不經過帳密登入，這裡真的登入
        error(mvc.perform(get("/api/admin/accounts").session(login("u1", TestData.PASSWORD))), 403, "沒有權限");
        mvc.perform(get("/api/admin/accounts").session(login("boss", TestData.PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void listIsSortedAndShowsHowEachAccountLogsIn() throws Exception {
        jdbc.sql("""
                insert into account (username, oidc_issuer, oidc_subject)
                values ('sso-a', 'https://idp.example', 'sub-1')""").update();
        jdbc.sql("""
                insert into account (username, password_hash, oidc_issuer, oidc_subject)
                values ('both-a', :h, 'https://idp.example', 'sub-2')""")
            .param("h", TestData.PASSWORD_HASH).update();

        mvc.perform(get("/api/admin/accounts").with(TestData.asAdmin("boss")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].username").value(contains("boss", "both-a", "sso-a", "u1", "u2", "u3")))
            .andExpect(jsonPath("$[1].password").value(true))
            .andExpect(jsonPath("$[1].sso").value(true))
            .andExpect(jsonPath("$[2].password").value(false))
            .andExpect(jsonPath("$[2].sso").value(true))
            .andExpect(jsonPath("$[3].admin").value(false))
            .andExpect(jsonPath("$[3].enabled").value(true))
            .andExpect(jsonPath("$[3].password").value(true))
            .andExpect(jsonPath("$[3].sso").value(false))
            .andExpect(jsonPath("$[3].createdAt").isString());
    }

    @Test
    void createsAnAccountThatCanLogIn() throws Exception {
        create(" Alice ", "alice-password-1")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.admin").value(false))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.password").value(true))
            .andExpect(jsonPath("$.sso").value(false));
        login("alice", "alice-password-1");
    }

    @Test
    void createRejectsDuplicatesAndBadNames() throws Exception {
        create("alice", "alice-password-1").andExpect(status().isCreated());
        error(create("alice", "alice-password-1"), 409, "帳號「alice」已經存在");
        error(create("ALICE", "alice-password-1"), 409, "帳號「alice」已經存在");

        for (String bad : List.of("..", "-boss", "a", "a".repeat(65), "中文名")) {
            error(create(bad, "alice-password-1"), 400,
                "帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64");
        }
    }

    @Test
    void createChecksThePasswordRules() throws Exception { // spec 2.1
        error(create("alice", "short-1"), 400, "密碼至少要 8 個字元"); // 7 個字
        error(create("alice", "營".repeat(25)), 400, "密碼太長，最多 72 個位元組（約 24 個中文字）"); // 75 bytes
        error(create("long-account-1", "long-account-1"), 400, "密碼不能跟帳號一樣");
        create("alice", "11111111").andExpect(status().isCreated()); // 剛好 8 個字可以
    }

    @Test
    void adminsCannotLockThemselvesOut() throws Exception {
        error(patchAccount("boss", "{\"enabled\":false}"), 400, "不能停用自己的帳號");
        error(patchAccount("boss", "{\"admin\":false}"), 400, "不能取消自己的管理員");
        error(resetPassword("boss", "reset-password-1"), 400, "自己的密碼請用「改密碼」");

        error(patchAccount("nobody", "{\"enabled\":false}"), 404, "找不到帳號");
        error(resetPassword("nobody", "reset-password-1"), 404, "找不到帳號");
    }

    @Test
    void patchTogglesEnabledAndAdminAndReturnsTheAccount() throws Exception {
        patchAccount("u1", "{\"enabled\":false}").andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("u1"))
            .andExpect(jsonPath("$.enabled").value(false));
        patchAccount("u1", "{\"enabled\":true}").andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
        patchAccount("u1", "{\"admin\":true}").andExpect(status().isOk())
            .andExpect(jsonPath("$.admin").value(true));
        patchAccount("u1", "{}").andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void everyChangeEndsThatAccountsSessions() throws Exception { // spec 8.1「工作階段失效」
        MockHttpSession s1 = login("u1", TestData.PASSWORD);
        patchAccount("u1", "{\"enabled\":false}").andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(s1)).andExpect(status().isUnauthorized());

        patchAccount("u1", "{\"enabled\":true}").andExpect(status().isOk());
        MockHttpSession s2 = relogin("u1");
        patchAccount("u1", "{\"admin\":true}").andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(s2)).andExpect(status().isUnauthorized());

        MockHttpSession s3 = relogin("u1");
        mvc.perform(get("/api/auth/me").session(s3))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.admin").value(true));
        patchAccount("u1", "{\"admin\":false}").andExpect(status().isOk());
        error(mvc.perform(get("/api/admin/accounts").session(s3)), 401, "請先登入"); // 不是 200，也不是 403

        MockHttpSession s4 = relogin("u1");
        resetPassword("u1", "reset-password-1").andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(s4)).andExpect(status().isUnauthorized());
        login("u1", "reset-password-1");
        mvc.perform(formLogin("/api/auth/login").user("u1").password(TestData.PASSWORD))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unchangedValuesKeepTheSessions() throws Exception {
        MockHttpSession s = login("u1", TestData.PASSWORD);
        patchAccount("u1", "{\"enabled\":true}").andExpect(status().isOk());
        patchAccount("u1", "{\"admin\":false}").andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());
    }

    @Test
    void ssoAccountsCanGetAPassword() throws Exception { // spec 4.2
        jdbc.sql("""
                insert into account (username, oidc_issuer, oidc_subject)
                values ('sso-a', 'https://idp.example', 'sub-1')""").update();
        resetPassword("sso-a", "sso-password-12").andExpect(status().isNoContent());
        login("sso-a", "sso-password-12");

        mvc.perform(get("/api/admin/accounts").with(TestData.asAdmin("boss")))
            .andExpect(jsonPath("$[?(@.username == 'sso-a')].password").value(contains(true)))
            .andExpect(jsonPath("$[?(@.username == 'sso-a')].sso").value(contains(true)));
    }
}
