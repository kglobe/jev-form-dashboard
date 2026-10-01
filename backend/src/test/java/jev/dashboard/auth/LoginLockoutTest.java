package jev.dashboard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 登入鎖定 spec 第 6 節。Clock 換成 mock 才能跳過 15 分鐘，所以另開一個 context。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginLockoutTest {
    static final Instant T0 = Instant.parse("2026-10-02T01:00:00Z");
    static final String WRONG = "wrong-password-1";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired LoginLockout lockout;
    @MockitoBean Clock clock;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
        List.of("u1", "u2", "u3").forEach(lockout::clear); // 鎖的狀態在記憶體，不在資料庫
        at(Duration.ZERO);
    }

    void at(Duration sinceT0) {
        when(clock.instant()).thenReturn(T0.plus(sinceT0));
    }

    ResultActions login(String username, String password) throws Exception {
        return mvc.perform(formLogin("/api/auth/login").user(username).password(password));
    }

    void wrong(String username, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(username, WRONG).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("帳號或密碼錯誤"));
        }
    }

    void locked(String username, int minutes) throws Exception {
        login(username, TestData.PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.error").value("登入失敗太多次，請 " + minutes + " 分鐘後再試"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPassword() throws Exception { // spec 2.1、2.2
        wrong("u1", 5);
        locked("u1", 15);
        at(Duration.ofSeconds(30));
        locked("u1", 15); // 剩 14 分 30 秒，無條件進位
        login("u2", TestData.PASSWORD).andExpect(status().isNoContent()); // 一個帳號一個計數
    }

    @Test
    void theLockEndsAfter15Minutes() throws Exception { // spec 2.1
        wrong("u1", 5);
        at(Duration.ofMinutes(15).minusSeconds(1));
        locked("u1", 1);
        at(Duration.ofMinutes(15));
        login("u1", TestData.PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void afterTheLockEndsTheCountStartsOver() throws Exception { // spec 2.1
        wrong("u1", 5);
        at(Duration.ofMinutes(15));
        wrong("u1", 5);
        locked("u1", 15);
    }

    @Test
    void aSuccessfulLoginResetsTheCount() throws Exception { // spec 2.1
        wrong("u1", 4);
        login("u1", TestData.PASSWORD).andExpect(status().isNoContent());
        wrong("u1", 4);
        login("u1", TestData.PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void parallelGuessesGetAtMostFiveTries() throws Exception { // spec 2.1：比對密碼之前先占一次
        // 直接打 bean：比對密碼卡住，等 20 個都到了（占到的卡在比對裡、沒占到的被鎖）才放行。
        // 透過 MockMvc 同時送，請求實際上會一個一個跑，抓不到「比對完才記錄」的寫法
        int threads = 20;
        CountDownLatch arrived = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger checked = new AtomicInteger();
        Runnable slowWrongPassword = () -> {
            checked.incrementAndGet();
            arrived.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new BadCredentialsException("帳號或密碼錯誤");
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        lockout.check("u1", slowWrongPassword);
                    } catch (LockedException e) {
                        arrived.countDown();
                    }
                });
            }
            try {
                assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(checked).hasValue(5);
            } finally {
                release.countDown();
            }
        }
        locked("u1", 15);
    }

    @Test
    void anAdminPasswordResetUnlocksTheAccount() throws Exception { // spec 2.4
        TestData.addAccount(jdbc, "boss", true);
        wrong("u1", 5);
        mvc.perform(put("/api/admin/accounts/u1/password").with(TestData.asAdmin("boss"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"reset-password-1\"}"))
            .andExpect(status().isNoContent());
        login("u1", "reset-password-1").andExpect(status().isNoContent());
    }

    @Test
    void unknownAndSsoOnlyAccountsAreNeverLocked() throws Exception { // spec 2.3
        jdbc.sql("insert into account (username) values ('sso-only')").update();
        wrong("nobody", 10);
        wrong("sso-only", 10);
        login("sso-only", TestData.PASSWORD).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("帳號或密碼錯誤"));
    }

    @Test
    void theUsernameIsNormalizedBeforeCounting() throws Exception { // spec 2.1
        wrong(" U1 ", 3);
        wrong("u1", 2);
        locked("U1", 15);
    }

    @Test
    void aDisabledAccountKeepsItsMessagesAndTheRightPasswordResetsTheCount() throws Exception { // spec 2.3
        jdbc.sql("update account set enabled = false where username = 'u2'").update();
        wrong("u2", 4);
        login("u2", TestData.PASSWORD).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("帳號已停用，請聯絡管理員"));
        wrong("u2", 4);
        login("u2", TestData.PASSWORD).andExpect(jsonPath("$.error").value("帳號已停用，請聯絡管理員"));
    }

    @Test
    void lockingWritesOneWarnWithoutThePassword(CapturedOutput output) throws Exception { // spec 2.5
        wrong("u1", 5);
        locked("u1", 15);
        locked("u1", 15);
        assertThat(output.getOut().lines().filter(l -> l.contains("WARN") && l.contains("u1"))).hasSize(1);
        assertThat(output.getOut()).doesNotContain(WRONG);
    }
}
