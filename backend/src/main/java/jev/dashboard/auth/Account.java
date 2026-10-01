package jev.dashboard.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;

/** account 表的一列。passwordHash 是 null 代表只能用單一登入（唯讀分享 spec 第 5 節）。 */
public record Account(String username, String passwordHash, boolean admin, boolean enabled,
                      String oidcIssuer, String oidcSubject, Instant sessionsValidAfter, Instant createdAt) {

    /** 唯讀分享 spec 2.1：轉換後的帳號名稱。 */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._@-]{1,63}$");

    /** 唯讀分享 spec 2.1：帳號名稱一律去掉前後空白、轉小寫。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }

    /** 唯讀分享 spec 2.1：轉換後要符合 USERNAME，回傳轉換後的名稱。 */
    public static String requireUsername(String raw) {
        String username = normalize(raw);
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "帳號要以英文字母或數字開頭，只能用英文字母、數字和 . _ @ -，長度 2～64");
        }
        return username;
    }

    /** 唯讀分享 spec 2.1：至少 12 個字元（code point），最多 72 個位元組（UTF-8，BCrypt 的上限），不能跟帳號一樣。 */
    public static void requirePassword(String username, String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密碼至少要 12 個字元");
        }
        if (password.getBytes(UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密碼太長，最多 72 個位元組（約 24 個中文字）");
        }
        if (password.equalsIgnoreCase(username)) { // 帳號名稱一律是小寫
            throw new ApiException(HttpStatus.BAD_REQUEST, "密碼不能跟帳號一樣");
        }
    }
}
