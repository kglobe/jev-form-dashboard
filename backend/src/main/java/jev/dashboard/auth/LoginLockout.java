package jev.dashboard.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

/**
 * 登入鎖定 spec 2.1：同一個帳號密碼錯 5 次，鎖 15 分鐘。只有查得到、有密碼的帳號會進來（spec 2.3），筆數不會超過帳號數。
 * ponytail: 存在記憶體，跟工作階段一樣，後端重啟就清掉；要跑多台後端時，跟 Spring Session JDBC 一起搬到資料庫
 */
@Component
public class LoginLockout {
    private static final Logger log = LoggerFactory.getLogger(LoginLockout.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    /** lockedUntil 不是 null 代表已經用掉 5 次：第 5 次一占下去就鎖，那次密碼對了才解開。 */
    private record Attempts(int count, Instant lockedUntil) {}

    // ponytail: 整個 map 一把鎖，裡面只有幾個 map 操作；登入量大到排隊時再改成每個帳號一把
    private final Map<String, Attempts> byAccount = new HashMap<>();
    private final Clock clock;

    public LoginLockout(Clock clock) {
        this.clock = clock;
    }

    /** spec 2.1：比對密碼之前先占一次，同時送很多個也只會有 5 次真的比對。鎖住時丟 LockedException，不比對密碼。 */
    public void check(String username, Runnable passwordCheck) {
        boolean last = reserve(username);
        try {
            passwordCheck.run();
        } catch (BadCredentialsException e) {
            if (last) log.warn("帳號「{}」密碼連續錯了 {} 次，鎖住 {} 分鐘", username, MAX_ATTEMPTS, LOCK.toMinutes()); // spec 2.5
            throw e;
        }
        clear(username); // 密碼對了就重新算，帳號停用也一樣（spec 2.3）
    }

    /** spec 2.4：登入成功、管理員重設密碼都會清掉。 */
    public synchronized void clear(String username) {
        byAccount.remove(username);
    }

    private synchronized boolean reserve(String username) {
        Instant now = clock.instant();
        Attempts a = byAccount.get(username);
        if (a != null && a.lockedUntil() != null) {
            if (now.isBefore(a.lockedUntil())) {
                long minutes = Duration.between(now, a.lockedUntil()).minusNanos(1).toMinutes() + 1; // 無條件進位，至少 1
                throw new LockedException("登入失敗太多次，請 " + minutes + " 分鐘後再試");
            }
            a = null; // 鎖過期了，重新算
        }
        int count = a == null ? 1 : a.count() + 1;
        byAccount.put(username, new Attempts(count, count == MAX_ATTEMPTS ? now.plus(LOCK) : null));
        return count == MAX_ATTEMPTS;
    }
}
