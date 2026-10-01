package jev.dashboard.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
