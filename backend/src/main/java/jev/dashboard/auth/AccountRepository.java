package jev.dashboard.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> find(String username) {
        return jdbc.sql("select * from account where username = :u")
            .param("u", username)
            .query(this::map).optional();
    }

    /** 唯讀分享 spec 2.1：改密碼一併更新 sessions_valid_after，舊的工作階段全部失效。 */
    public boolean setPassword(String username, String passwordHash, Instant sessionsValidAfter) {
        return jdbc.sql("update account set password_hash = :h, sessions_valid_after = :t where username = :u")
            .param("h", passwordHash)
            .param("t", sessionsValidAfter.atOffset(ZoneOffset.UTC))
            .param("u", username)
            .update() == 1;
    }

    public List<Account> list() {
        return jdbc.sql("select * from account order by username").query(this::map).list();
    }

    /** 帳號已經存在時，JdbcClient 丟 DuplicateKeyException。 */
    public void insert(String username, String passwordHash, boolean admin, String oidcIssuer, String oidcSubject) {
        jdbc.sql("""
                insert into account (username, password_hash, admin, oidc_issuer, oidc_subject)
                values (:u, :h, :a, :i, :s)""")
            .param("u", username)
            .param("h", passwordHash)
            .param("a", admin)
            .param("i", oidcIssuer)
            .param("s", oidcSubject)
            .update();
    }

    /** 唯讀分享 spec 2.1：停用、重新啟用一併更新 sessions_valid_after。 */
    public boolean setEnabled(String username, boolean enabled, Instant validAfter) {
        return jdbc.sql("update account set enabled = :e, sessions_valid_after = :t where username = :u")
            .param("e", enabled)
            .param("t", validAfter.atOffset(ZoneOffset.UTC))
            .param("u", username)
            .update() == 1;
    }

    /** 唯讀分享 spec 2.1：設成或取消管理員一併更新 sessions_valid_after。 */
    public boolean setAdmin(String username, boolean admin, Instant validAfter) {
        return jdbc.sql("update account set admin = :a, sessions_valid_after = :t where username = :u")
            .param("a", admin)
            .param("t", validAfter.atOffset(ZoneOffset.UTC))
            .param("u", username)
            .update() == 1;
    }

    private Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("admin"),
            rs.getBoolean("enabled"), rs.getString("oidc_issuer"), rs.getString("oidc_subject"),
            instant(rs, "sessions_valid_after"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
