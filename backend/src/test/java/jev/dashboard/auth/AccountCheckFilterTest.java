package jev.dashboard.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** 唯讀分享 spec 2.1、2.2：帳號檢查擋下舊的登入；查資料庫失敗時回 500 JSON，不把例外往外丟。 */
class AccountCheckFilterTest {
    static final Instant CHANGED = Instant.parse("2026-10-01T00:00:00Z");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aDatabaseErrorIs500JsonAndStopsTheChain() throws Exception {
        AccountRepository down = new AccountRepository(null) {
            @Override
            public Optional<Account> find(String u) {
                throw new DataAccessResourceFailureException("down");
            }
        };
        SecurityContextHolder.getContext()
            .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new AccountCheckFilter(down, new ObjectMapper()).doFilter(new MockHttpServletRequest(), res, chain);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(res.getContentAsString(UTF_8)).isEqualTo("{\"error\":\"伺服器錯誤\"}");
        assertThat(chain.getRequest()).isNull();
    }

    /** 帳號 u1 的 sessions_valid_after 是 CHANGED；登入的 details 是開始登入的時間（SecurityConfig）。 */
    static MockHttpServletResponse check(boolean admin, String role, Object loginStarted, MockFilterChain chain,
            HttpServletRequest req) throws Exception {
        Account u1 = new Account("u1", null, admin, true, null, null, CHANGED, CHANGED);
        AccountRepository repo = new AccountRepository(null) {
            @Override
            public Optional<Account> find(String u) {
                return Optional.of(u1);
            }
        };
        UsernamePasswordAuthenticationToken auth =
            UsernamePasswordAuthenticationToken.authenticated("u1", null, AuthorityUtils.createAuthorityList(role));
        auth.setDetails(loginStarted);
        SecurityContextHolder.getContext().setAuthentication(auth);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new AccountCheckFilter(repo, new ObjectMapper()).doFilter(req, res, chain);
        return res;
    }

    static void assertRejected(MockHttpServletResponse res, MockFilterChain chain) throws Exception {
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo("{\"error\":\"請先登入\"}");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aLoginThatStartedAtOrBeforeTheChangeIs401EvenWithoutASession() throws Exception {
        // 讀完帳號、還在跑 BCrypt 時被取消管理員或重設密碼：工作階段建得比變動晚，還是要擋（spec 2.1）。
        // 同一刻分不出先後，也擋。沒有登入時間（也沒有工作階段）一律當作舊的。
        for (Object started : new Object[] {CHANGED.minusMillis(50), CHANGED, null}) {
            MockFilterChain chain = new MockFilterChain();
            assertRejected(check(false, "ROLE_USER", started, chain, new MockHttpServletRequest()), chain);
        }
        MockFilterChain chain = new MockFilterChain();
        check(false, "ROLE_USER", CHANGED.plusNanos(1), chain, new MockHttpServletRequest());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aRoleThatNoLongerMatchesTheAccountIs401() throws Exception {
        MockFilterChain demoted = new MockFilterChain();
        assertRejected(check(false, "ROLE_ADMIN", CHANGED.plusSeconds(1), demoted, new MockHttpServletRequest()), demoted);
        MockFilterChain promoted = new MockFilterChain();
        assertRejected(check(true, "ROLE_USER", CHANGED.plusSeconds(1), promoted, new MockHttpServletRequest()), promoted);
        MockFilterChain admin = new MockFilterChain();
        check(true, "ROLE_ADMIN", CHANGED.plusSeconds(1), admin, new MockHttpServletRequest());
        assertThat(admin.getRequest()).isNotNull();
    }

    @Test
    void aSessionAnotherRequestAlreadyInvalidatedIsStill401() throws Exception {
        MockHttpSession gone = new MockHttpSession();
        gone.invalidate(); // 再呼叫 invalidate() 會丟 IllegalStateException，跟 Tomcat 一樣
        HttpServletRequestWrapper req = new HttpServletRequestWrapper(new MockHttpServletRequest()) {
            @Override
            public HttpSession getSession(boolean create) {
                return gone;
            }
        };
        MockFilterChain chain = new MockFilterChain();
        assertRejected(check(false, "ROLE_USER", CHANGED, chain, req), chain);
    }

    @Test
    void anonymousRequestsPassWithoutQueryingTheDatabase() throws Exception {
        AccountRepository untouchable = new AccountRepository(null) {
            @Override
            public Optional<Account> find(String u) {
                throw new IllegalStateException("不該查資料庫");
            }
        };
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new AccountCheckFilter(untouchable, new ObjectMapper()).doFilter(new MockHttpServletRequest(), res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
