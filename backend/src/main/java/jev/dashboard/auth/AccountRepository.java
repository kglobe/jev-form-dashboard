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

    /** 唯讀分享 spec 2.1：沒填 ADMIN_USERNAME 時，用它決定要不要寫 WARN。 */
    public boolean anyEnabledAdmin() {
        return jdbc.sql("select exists(select 1 from account where admin and enabled)").query(Boolean.class).single();
    }

    /** 唯讀分享 spec 2.3：單一登入只認 (issuer, sub)。 */
    public Optional<Account> findByOidc(String issuer, String subject) {
        return jdbc.sql("select * from account where oidc_issuer = :i and oidc_subject = :s")
            .param("i", issuer)
            .param("s", subject)
            .query(this::map).optional();
    }

    /** 唯讀分享 spec 2.3：dashboard、datasource 有沒有 created_by 是這個名稱的舊資料。 */
    public boolean hasLegacyData(String username) {
        return jdbc.sql("""
                select exists(select 1 from dashboard where created_by = :u)
                    or exists(select 1 from datasource where created_by = :u)""")
            .param("u", username)
            .query(Boolean.class).single();
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
