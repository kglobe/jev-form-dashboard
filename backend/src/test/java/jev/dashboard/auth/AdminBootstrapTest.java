package jev.dashboard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 唯讀分享 spec 2.1、8.1「管理員建立」：直接 new 這個 runner 呼叫 run()。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBootstrapTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    void run(String username, String password) {
        new AdminBootstrap(new AuthProperties(username, password, null), accounts, encoder).run(null);
    }

    List<String> usernames() {
        return jdbc.sql("select username from account order by username").query(String.class).list();
    }

    @Test
    void createsAnEnabledAdminWhenTheAccountIsMissing() throws Exception {
        run("Boss", "boss-password-1");

        Account boss = accounts.find("boss").orElseThrow();
        assertThat(boss.admin()).isTrue();
        assertThat(boss.enabled()).isTrue();
        mvc.perform(formLogin("/api/auth/login").user("boss").password("boss-password-1"))
            .andExpect(status().isNoContent());
    }

    @Test
    void leavesAnExistingAccountAlone() throws Exception {
        TestData.addAccount(jdbc, "boss", true);
        run("boss", "another-password-1");
        assertThat(accounts.find("boss").orElseThrow().passwordHash()).isEqualTo(TestData.PASSWORD_HASH);
        mvc.perform(formLogin("/api/auth/login").user("boss").password(TestData.PASSWORD))
            .andExpect(status().isNoContent());

        run("boss", null); // 已經有帳號，密碼沒填也不會失敗

        jdbc.sql("update account set enabled = false where username = 'boss'").update();
        Account disabled = accounts.find("boss").orElseThrow();
        run("boss", "another-password-1");
        assertThat(accounts.find("boss").orElseThrow()).isEqualTo(disabled);

        jdbc.sql("update account set enabled = true, admin = false where username = 'boss'").update();
        Account notAdmin = accounts.find("boss").orElseThrow();
        run("boss", "another-password-1");
        assertThat(accounts.find("boss").orElseThrow()).isEqualTo(notAdmin);
    }

    @Test
    void doesNothingWithoutAUsername() {
        run("", "");
        run(null, null);
        assertThat(usernames()).containsExactly("u1", "u2", "u3");
    }

    @Test
    void failsOnABadUsernameOrPassword() {
        assertThrows(IllegalStateException.class, () -> run("-boss", "boss-password-1"));
        assertThrows(IllegalStateException.class, () -> run("boss", null));
        assertThrows(IllegalStateException.class, () -> run("boss", "short"));
        assertThrows(IllegalStateException.class, () -> run("boss", "has space 12345"));
        assertThrows(IllegalStateException.class, () -> run("boss", "密碼密碼密碼密碼密碼密碼密碼"));
        assertThrows(IllegalStateException.class, () -> run("long-account-1", "long-account-1"));
        assertThat(usernames()).containsExactly("u1", "u2", "u3");
    }
}
