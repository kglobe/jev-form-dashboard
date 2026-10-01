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
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 唯讀分享 spec 2.1、6.2：已登入的請求檢查帳號還在、還啟用、身分跟資料庫一樣、登入不早於 sessions_valid_after。
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
        if (account.isPresent() && current(auth, account.get())) {
            chain.doFilter(req, res);
            return;
        }
        HttpSession session = req.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // 同一個工作階段的另一個請求先讓它失效了
            }
        }
        SecurityContextHolder.clearContext();
        SecurityConfig.writeError(res, json, 401, "請先登入");
    }

    /**
     * 比的是開始登入的時間（SecurityConfig 放在 details），不是工作階段的建立時間：登入先讀帳號、跑完 BCrypt 才建工作階段，
     * 這中間的變動也要讓這次登入失效。不看工作階段，同一個工作階段的另一個請求同時讓它失效也不會漏掉。
     * 同一刻也算舊的（Windows 的時鐘一格十幾毫秒，分不出先後）。admin 另外比，取消管理員一定擋得到。
     * ponytail: 變動算出 Instant.now() 到寫進資料庫之間（約 1 毫秒）開始的登入，還是可能讀到舊的密碼雜湊；
     * 要完全關掉，帳號表加版本號，登入時把讀到的版本存進工作階段
     */
    static boolean current(Authentication auth, Account a) {
        Instant validAfter = a.sessionsValidAfter();
        boolean admin = AuthorityUtils.authorityListToSet(auth.getAuthorities()).contains("ROLE_ADMIN");
        return a.enabled() && admin == a.admin()
            && (validAfter == null || auth.getDetails() instanceof Instant start && start.isAfter(validAfter));
    }
}
