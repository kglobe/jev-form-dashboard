package jev.dashboard.auth;

import java.time.Instant;
import jev.dashboard.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    public AccountService(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    /** 唯讀分享 spec 2.1、4.1：成功後這個帳號所有工作階段都失效，包括目前這個。 */
    public void changeOwnPassword(String username, String current, String password) {
        Account a = accounts.find(username).orElseThrow(); // 帳號檢查 filter 擋過，帳號也不會被刪
        if (a.passwordHash() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "這個帳號用單一登入，沒有密碼可以改");
        }
        if (current == null || !encoder.matches(current, a.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目前的密碼不對");
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

    /** 唯讀分享 spec 4.2：管理員設定或重設別人的密碼，單一登入的帳號也可以。 */
    public void resetPassword(String self, String username, String password) {
        String u = Account.normalize(username);
        if (u.equals(self)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "自己的密碼請用「改密碼」");
        }
        find(u);
        Account.requirePassword(u, password);
        accounts.setPassword(u, encoder.encode(password), Instant.now());
    }

    private Account find(String username) {
        return accounts.find(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到帳號"));
    }
}
