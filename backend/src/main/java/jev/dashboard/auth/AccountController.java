package jev.dashboard.auth;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 唯讀分享 spec 4.2：管理員的帳號管理。只有管理員打得到，由 SecurityConfig 的 URL 規則擋（不用 @PreAuthorize，spec 6.2）。 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {
    public record NewAccount(String username, String password, boolean admin) {}

    public record Changes(Boolean enabled, Boolean admin) {}

    public record NewPassword(String password) {}

    private final AccountRepository accounts;
    private final AccountService service;

    public AccountController(AccountRepository accounts, AccountService service) {
        this.accounts = accounts;
        this.service = service;
    }

    @GetMapping
    public List<Account.View> list() {
        return accounts.list().stream().map(Account.View::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account.View create(@RequestBody NewAccount body) {
        return Account.View.of(service.create(body.username(), body.password(), body.admin()));
    }

    @PatchMapping("/{username}")
    public Account.View update(Principal principal, @PathVariable String username, @RequestBody Changes body) {
        return Account.View.of(service.update(principal.getName(), username, body.enabled(), body.admin()));
    }

    @PutMapping("/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(Principal principal, @PathVariable String username, @RequestBody NewPassword body) {
        service.resetPassword(principal.getName(), username, body.password());
    }
}
