package jev.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTest {
    @Autowired JdbcClient jdbc;
    @Autowired Environment env;

    @Test
    void listensOnLoopbackByDefault() {
        // 身分只靠 X-User-Id header，預設不能對外網開；要開用 --server.address=0.0.0.0
        assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void flywayCreatesTablesInTestSchema() {
        List<String> tables = jdbc.sql("""
                select table_name from information_schema.tables
                where table_schema = 'jev_dashboard_test' order by table_name""")
            .query(String.class).list();
        assertThat(tables).contains("dashboard", "datasource", "datasource_rows", "jev_flyway_history");
    }
}
