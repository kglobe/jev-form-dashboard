package jev.dashboard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StringUtils;

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

    void fails(String username, String password, String message) {
        assertThatThrownBy(() -> run(username, password)).isInstanceOf(IllegalStateException.class).hasMessage(message);
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
    @ExtendWith(OutputCaptureExtension.class)
    void doesNothingWithoutAUsername(CapturedOutput output) {
        String warn = "沒有管理員帳號：在 backend/.env 設定 ADMIN_USERNAME、ADMIN_PASSWORD";
        run("", "");
        run(null, null);
        assertThat(usernames()).containsExactly("u1", "u2", "u3");
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), warn)).isEqualTo(2);

        TestData.addAccount(jdbc, "boss", true); // 已經有啟用中的管理員就不警告
        run("", "");
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), warn)).isEqualTo(2);
    }

    @Test
    void failsOnABadUsernameOrPassword() {
        String charset = "ADMIN_PASSWORD 只能用英數字和符號，不能有空白或中文（backend/.env 是用 ISO-8859-1 讀的）";
        fails("-boss", "boss-password-1", "ADMIN_USERNAME 不合規則：帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64");
        fails("boss", null, "要建立管理員帳號「boss」，但 ADMIN_PASSWORD 沒填");
        fails("boss", "", "要建立管理員帳號「boss」，但 ADMIN_PASSWORD 沒填");
        fails("boss", "short", "ADMIN_PASSWORD 不合規則：密碼至少要 12 個字元");
        fails("boss", "has space 12345", charset);
        fails("boss", "密碼密碼密碼密碼密碼密碼密碼", charset);
        fails("long-account-1", "long-account-1", "ADMIN_PASSWORD 不合規則：密碼不能跟帳號一樣");
        assertThat(usernames()).containsExactly("u1", "u2", "u3");
    }
}
