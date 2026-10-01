package jev.dashboard.auth;

import java.util.Optional;
import jev.dashboard.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 唯讀分享 spec 2.1、6.4：啟動時建立 backend/.env 的第一個管理員。帳號已經存在就什麼都不改。 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthProperties props;
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    AdminBootstrap(AuthProperties props, AccountRepository accounts, PasswordEncoder encoder) {
        this.props = props;
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(props.adminUsername())) {
            if (!accounts.anyEnabledAdmin()) {
                log.warn("沒有管理員帳號：在 backend/.env 設定 ADMIN_USERNAME、ADMIN_PASSWORD");
            }
            return;
        }
        String username;
        try {
            username = Account.requireUsername(props.adminUsername());
        } catch (ApiException e) {
            throw new IllegalStateException("ADMIN_USERNAME 不合規則：" + e.getMessage());
        }

        Optional<Account> existing = accounts.find(username);
        if (existing.isPresent()) { // 不看密碼，管理員自己改過的密碼不會被 .env 蓋回去
            if (existing.get().enabled() && existing.get().admin()) {
                log.info("管理員帳號「{}」已經存在，不會改它的密碼", username);
            } else {
                log.warn("管理員帳號「{}」已經存在，但已經停用或不是管理員，不會改它", username);
            }
            return;
        }

        String password = props.adminPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("要建立管理員帳號「" + username + "」，但 ADMIN_PASSWORD 沒填");
        }
        if (!password.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new IllegalStateException(
                "ADMIN_PASSWORD 只能用英數字和符號，不能有空白或中文（backend/.env 是用 ISO-8859-1 讀的）");
        }
        try {
            Account.requirePassword(username, password);
        } catch (ApiException e) {
            throw new IllegalStateException("ADMIN_PASSWORD 不合規則：" + e.getMessage());
        }
        accounts.insert(username, encoder.encode(password), true, null, null);
        log.info("已建立管理員帳號「{}」", username);
    }
}
