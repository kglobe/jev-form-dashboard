package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    public record DashboardSummary(UUID id, String name, UUID datasourceId, OffsetDateTime updatedAt) {}

    /** config_json 的內容。intents 是 null 時不寫出來，所以舊資料和沒有需求的儀表板長得一樣。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stored(List<ChartSpec> layout, List<String> intents) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DashboardRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insert(DashboardConfig c, String userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into dashboard (id, name, datasource_id, created_by, config_json)
                values (:id, :name, :ds, :by, cast(:config as jsonb))""")
            .param("id", id)
            .param("name", c.name())
            .param("ds", c.datasourceId())
            .param("by", userId)
            .param("config", write(new Stored(c.layout(), c.intents())))
            .update();
        return id;
    }

    public Optional<DashboardConfig> find(UUID id, String userId) {
        return jdbc.sql("select * from dashboard where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    public List<DashboardSummary> list(String userId) {
        return jdbc.sql("select id, name, datasource_id, updated_at from dashboard where created_by = :by order by updated_at desc")
            .param("by", userId)
            .query((rs, n) -> new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("datasource_id", UUID.class), rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }

    public boolean update(UUID id, String userId, DashboardConfig c) {
        return jdbc.sql("""
                update dashboard set name = :name, config_json = cast(:config as jsonb), updated_at = now()
                where id = :id and created_by = :by""")
            .param("name", c.name())
            .param("config", write(new Stored(c.layout(), c.intents())))
            .param("id", id)
            .param("by", userId)
            .update() == 1;
    }

    public boolean delete(UUID id, String userId) {
        return jdbc.sql("delete from dashboard where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .update() == 1;
    }

    private DashboardConfig map(ResultSet rs, int rowNum) throws SQLException {
        Stored stored;
        try {
            stored = json.readValue(rs.getString("config_json"), Stored.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return new DashboardConfig(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getObject("datasource_id", UUID.class), rs.getString("created_by"), stored.layout(), stored.intents());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
