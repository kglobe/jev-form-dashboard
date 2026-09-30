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
import jev.dashboard.dashboard.DashboardConfig.Basis;
import jev.dashboard.transform.ChartSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    public record DashboardSummary(UUID id, String name, OffsetDateTime updatedAt) {}

    /** 這份儀表板有 charts 張圖用到某個來源。 */
    public record DatasourceUse(UUID id, String name, int charts) {}

    /** config_json 的內容。basis 是 null 時不寫出來，沒有依據的儀表板就只有 layout。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Stored(List<ChartSpec> layout, List<Basis> basis) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DashboardRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID insert(DashboardConfig c, String userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into dashboard (id, name, created_by, config_json)
                values (:id, :name, :by, cast(:config as jsonb))""")
            .param("id", id)
            .param("name", c.name())
            .param("by", userId)
            .param("config", write(new Stored(c.layout(), c.basis())))
            .update();
        return id;
    }

    public Optional<DashboardConfig> find(UUID id, String userId) {
        return jdbc.sql("select * from dashboard where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    /** 加到既有儀表板用：讀的時候鎖住那一列，同一個 transaction 寫回之前，另一次加入要等。 */
    public Optional<DashboardConfig> findForUpdate(UUID id, String userId) {
        return jdbc.sql("select * from dashboard where id = :id and created_by = :by for update")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    public List<DashboardSummary> list(String userId) {
        return jdbc.sql("select id, name, updated_at from dashboard where created_by = :by order by updated_at desc")
            .param("by", userId)
            .query((rs, n) -> new DashboardSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
    }

    /** 有圖用到這個來源的儀表板，和各自用到的張數；照清單的順序。 */
    public List<DatasourceUse> usingDatasource(String userId, UUID datasourceId) {
        return jdbc.sql("""
                select d.id, d.name, count(*) as charts
                from dashboard d, jsonb_array_elements(d.config_json -> 'layout') card
                where d.created_by = :by and card ->> 'datasourceId' = :ds
                group by d.id, d.name, d.updated_at
                order by d.updated_at desc""")
            .param("by", userId).param("ds", datasourceId.toString())
            .query((rs, n) -> new DatasourceUse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("charts")))
            .list();
    }

    public boolean update(UUID id, String userId, DashboardConfig c) {
        return jdbc.sql("""
                update dashboard set name = :name, config_json = cast(:config as jsonb), updated_at = now()
                where id = :id and created_by = :by""")
            .param("name", c.name())
            .param("config", write(new Stored(c.layout(), c.basis())))
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
        return new DashboardConfig(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("created_by"),
            stored.layout(), null, stored.basis());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
