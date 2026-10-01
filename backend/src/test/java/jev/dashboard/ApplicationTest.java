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
        // 預設只聽本機；要對外開用 --server.address=0.0.0.0
        assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void flywayCreatesTablesInTestSchema() {
        String schema = env.getProperty("spring.flyway.schemas"); // 環境變數 SPRING_FLYWAY_SCHEMAS 會蓋過 application-test.yml
        List<String> tables = jdbc.sql("""
                select table_name from information_schema.tables
                where table_schema = :s order by table_name""")
            .param("s", schema).query(String.class).list();
        assertThat(tables).contains("account", "dashboard", "dashboard_share", "datasource", "datasource_rows", "jev_flyway_history");
    }
}
