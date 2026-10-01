package jev.dashboard.auth;

import java.time.Instant;
import java.util.Locale;

/** account 表的一列。passwordHash 是 null 代表只能用單一登入（唯讀分享 spec 第 5 節）。 */
public record Account(String username, String passwordHash, boolean admin, boolean enabled,
                      String oidcIssuer, String oidcSubject, Instant sessionsValidAfter, Instant createdAt) {

    /** 唯讀分享 spec 2.1：帳號名稱一律去掉前後空白、轉小寫。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }
}
