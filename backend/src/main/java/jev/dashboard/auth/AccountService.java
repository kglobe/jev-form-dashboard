package jev.dashboard.auth;

import java.time.Instant;
import jev.dashboard.ApiException;
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
}
