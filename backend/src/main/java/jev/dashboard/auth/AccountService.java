package jev.dashboard.auth;

import java.time.Instant;
import java.util.Optional;
import jev.dashboard.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final LoginLockout lockout;

    public AccountService(AccountRepository accounts, PasswordEncoder encoder, LoginLockout lockout) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.lockout = lockout;
    }

    /**
     * 唯讀分享 spec 2.1、4.1：成功後這個帳號所有工作階段都失效，包括目前這個。
     * 登入鎖定 spec 4：比對目前的密碼也走 LoginLockout，錯了一樣算一次，鎖住時回 429、不比對。
     */
    public void changeOwnPassword(String username, String current, String password) {
        Account a = accounts.find(username).orElseThrow(); // 帳號檢查 filter 擋過，帳號也不會被刪
        if (a.passwordHash() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "這個帳號用單一登入，沒有密碼可以改");
        }
        try {
            lockout.check(username, () -> {
                if (current == null || !encoder.matches(current, a.passwordHash())) throw new BadCredentialsException("目前的密碼不對");
            });
        } catch (BadCredentialsException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (LockedException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
        Account.requirePassword(username, password);
        accounts.setPassword(username, encoder.encode(password), Instant.now());
    }

    /** 唯讀分享 spec 4.2：管理員新增帳號。 */
    public Account create(String username, String password, boolean admin) {
        String u = Account.requireUsername(username);
        Account.requirePassword(u, password);
        try {
            accounts.insert(u, encoder.encode(password), admin, null, null);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "帳號「" + u + "」已經存在");
        }
        return accounts.find(u).orElseThrow();
    }

    /** 唯讀分享 spec 2.1、4.2：值真的有變才寫入，才讓對方的工作階段失效。null 代表不改。 */
    public Account update(String self, String username, Boolean enabled, Boolean admin) {
        String u = Account.normalize(username);
        if (u.equals(self) && Boolean.FALSE.equals(enabled)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能停用自己的帳號");
        }
        if (u.equals(self) && Boolean.FALSE.equals(admin)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能取消自己的管理員");
        }
        Account a = find(u);
        Instant now = Instant.now();
        if (enabled != null && enabled != a.enabled()) accounts.setEnabled(u, enabled, now);
        if (admin != null && admin != a.admin()) accounts.setAdmin(u, admin, now);
        return find(u);
    }

    /** 唯讀分享 spec 4.2：管理員設定或重設別人的密碼，單一登入的帳號也可以。登入鎖定 spec 2.4：順便解鎖。 */
    public void resetPassword(String self, String username, String password) {
        String u = Account.normalize(username);
        if (u.equals(self)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "自己的密碼請用「改密碼」");
        }
        find(u);
        Account.requirePassword(u, password);
        accounts.setPassword(u, encoder.encode(password), Instant.now());
        lockout.clear(u);
    }

    /**
     * 唯讀分享 spec 2.3 帳號對應：只認 (issuer, sub)，不用帳號名稱接上既有帳號或舊資料。
     * ponytail: 兩個人同時用同一個新名稱第一次登入，主鍵衝突會變成 500；下次再按就是 username_taken
     */
    public Account forOidc(String issuer, String subject, String rawUsername) {
        Optional<Account> bound = accounts.findByOidc(issuer, subject);
        if (bound.isPresent()) {
            if (!bound.get().enabled()) throw new OAuth2AuthenticationException(new OAuth2Error("account_disabled"));
            return bound.get();
        }
        String u = Account.normalize(rawUsername);
        if (u == null || !Account.USERNAME.matcher(u).matches()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("no_username"));
        }
        if (accounts.find(u).isPresent() || accounts.hasLegacyData(u)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("username_taken"));
        }
        accounts.insert(u, null, false, issuer, subject);
        return accounts.find(u).orElseThrow();
    }

    private Account find(String username) {
        return accounts.find(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到帳號"));
    }
}
