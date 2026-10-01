package jev.dashboard.auth;

import java.security.Principal;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登入、登出由 Spring Security 的 formLogin、logout 處理（SecurityConfig），這裡只有其他的 /api/auth。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    /** 唯讀分享 spec 4.1：三個值都讀資料庫；password 是這個帳號有沒有密碼。 */
    public record Me(String username, boolean admin, boolean password) {}

    private final AccountRepository accounts;

    public AuthController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public Me me(Principal principal) {
        Account a = accounts.find(principal.getName())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "請先登入"));
        return new Me(a.username(), a.admin(), a.passwordHash() != null);
    }
}
