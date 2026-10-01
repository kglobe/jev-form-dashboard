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

    /** 唯讀分享 spec 4.3：分享設定，users 是轉換過、排好序的帳號名稱。 */
    public record Share(boolean publicLink, List<String> users) {}

    /** 唯讀分享 spec 4.3：「分享給我的」清單的一筆。 */
    public record SharedDashboard(UUID id, String name, String owner, OffsetDateTime updatedAt) {}

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

    /** 唯讀分享 spec 4.5：擁有者、被指定的帳號、公開連結打開時任何人都能看；擁有者停用就當作不存在（2.4）。 */
    public Optional<DashboardConfig> findViewable(UUID id, String userId) {
        return jdbc.sql("""
                select d.* from dashboard d
                join account o on o.username = d.created_by and o.enabled
                where d.id = :id
                  and (d.created_by = :by or d.public_link
                       or exists (select 1 from dashboard_share s where s.dashboard_id = d.id and s.username = :by))""")
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

    public Share share(UUID id) {
        boolean publicLink = jdbc.sql("select public_link from dashboard where id = :id")
            .param("id", id).query(Boolean.class).single();
        List<String> users = jdbc.sql("select username from dashboard_share where dashboard_id = :id order by username")
            .param("id", id).query(String.class).list();
        return new Share(publicLink, users);
    }

    /** 唯讀分享 spec 4.3：整份取代。不動 updated_at，它只代表內容的更新時間。 */
    public void setShare(UUID id, Share s) {
        jdbc.sql("update dashboard set public_link = :p where id = :id").param("p", s.publicLink()).param("id", id).update();
        jdbc.sql("delete from dashboard_share where dashboard_id = :id").param("id", id).update();
        for (String u : s.users()) {
            jdbc.sql("insert into dashboard_share (dashboard_id, username) values (:id, :u)")
                .param("id", id).param("u", u).update();
        }
    }

    /** 唯讀分享 spec 4.3：只算指定帳號，不算公開連結；擁有者停用的不列。 */
    public List<SharedDashboard> sharedWith(String userId) {
        return jdbc.sql("""
                select d.id, d.name, d.created_by, d.updated_at
                from dashboard d
                join dashboard_share s on s.dashboard_id = d.id and s.username = :by
                join account o on o.username = d.created_by and o.enabled
                order by d.updated_at desc""")
            .param("by", userId)
            .query((rs, n) -> new SharedDashboard(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("created_by"), rs.getObject("updated_at", OffsetDateTime.class)))
            .list();
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
