package jev.dashboard.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 唯讀分享 spec 2.1、6.2：已登入的請求檢查帳號還在、還啟用、工作階段不早於 sessions_valid_after。
 * 不註冊成 bean：Boot 會把 Filter bean 再掛成一般的 servlet filter，跑在安全設定前面。由 SecurityConfig new 出來。
 * OncePerRequestFilter 預設跳過 ERROR dispatch。
 */
final class AccountCheckFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccountCheckFilter.class);
    private final AccountRepository accounts;
    private final ObjectMapper json;

    AccountCheckFilter(AccountRepository accounts, ObjectMapper json) {
        this.accounts = accounts;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(req, res);
            return;
        }
        // ponytail: 每個請求多查一次帳號（主鍵查詢）。真的變成瓶頸時，再在記憶體裡放幾秒
        Optional<Account> account;
        try {
            account = accounts.find(auth.getName());
        } catch (RuntimeException e) {
            log.error("帳號檢查查資料庫失敗", e);
            SecurityConfig.writeError(res, json, 500, "伺服器錯誤");
            return;
        }
        HttpSession session = req.getSession(false);
        boolean ok = account.isPresent() && account.get().enabled() && !(session != null
            && account.get().sessionsValidAfter() != null
            && Instant.ofEpochMilli(session.getCreationTime()).isBefore(account.get().sessionsValidAfter()));
        if (ok) {
            chain.doFilter(req, res);
            return;
        }
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityConfig.writeError(res, json, 401, "請先登入");
    }
}
