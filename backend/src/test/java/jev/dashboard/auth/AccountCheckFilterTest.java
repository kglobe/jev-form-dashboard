package jev.dashboard.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 唯讀分享 spec 2.2：帳號檢查查資料庫失敗時回 500 JSON，不把例外往外丟。 */
class AccountCheckFilterTest {

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
