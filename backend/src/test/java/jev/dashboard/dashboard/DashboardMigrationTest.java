package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** V2：舊儀表板的來源寫進每張圖、intents 變成一組 basis，最後刪掉 datasource_id。用自己的 schema，不動測試共用的資料。 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardMigrationTest {
    static final String SCHEMA = "jev_dashboard_v2_migration_test";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired DataSource dataSource;

    @Test
    void v2MovesTheDatasourceIntoEveryCardAndTurnsIntentsIntoBasis() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("drop schema if exists " + SCHEMA + " cascade").update();
        flyway("1").migrate();

        UUID ds = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".datasource (id, name, kind, schema_json, created_by)"
                + " values (:id, '銷售', 'upload', '[]', 'u1')")
            .param("id", ds).update();
        UUID withIntents = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"},{"chartId":"c2","type":"table"}],"intents":["各區每月營收","不要圓餅圖"]}""");
        UUID emptyIntents = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"}],"intents":[]}""");
        UUID plain = insertDashboard(jdbc, ds, """
            {"layout":[{"chartId":"c1","type":"bar"}]}""");
        UUID empty = insertDashboard(jdbc, ds, """
            {"layout":[]}""");

        flyway(null).migrate();

        assertThat(config(jdbc, withIntents)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%1$s"},{"chartId":"c2","type":"table","datasourceId":"%1$s"}],
             "basis":[{"datasourceId":"%1$s","intents":["各區每月營收","不要圓餅圖"]}]}""".formatted(ds)));
        assertThat(config(jdbc, emptyIntents)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%s"}]}""".formatted(ds)));
        assertThat(config(jdbc, plain)).isEqualTo(JSON.readTree("""
            {"layout":[{"chartId":"c1","type":"bar","datasourceId":"%s"}]}""".formatted(ds)));
        assertThat(config(jdbc, empty)).isEqualTo(JSON.readTree("{\"layout\":[]}"));
        assertThat(jdbc.sql("select count(*) from information_schema.columns"
                + " where table_schema = :s and table_name = 'dashboard' and column_name = 'datasource_id'")
            .param("s", SCHEMA).query(Long.class).single()).isZero();

        jdbc.sql("drop schema " + SCHEMA + " cascade").update();
    }

    private Flyway flyway(String target) {
        FluentConfiguration conf = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).createSchemas(true)
            .table("jev_flyway_history");
        if (target != null) conf.target(target);
        return conf.load();
    }

    private static UUID insertDashboard(JdbcClient jdbc, UUID ds, String config) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into " + SCHEMA + ".dashboard (id, name, datasource_id, created_by, config_json)"
                + " values (:id, 'd', :ds, 'u1', cast(:config as jsonb))")
            .param("id", id).param("ds", ds).param("config", config).update();
        return id;
    }

    private static com.fasterxml.jackson.databind.JsonNode config(JdbcClient jdbc, UUID id) throws Exception {
        return JSON.readTree(jdbc.sql("select config_json::text from " + SCHEMA + ".dashboard where id = :id")
            .param("id", id).query(String.class).single());
    }
}
