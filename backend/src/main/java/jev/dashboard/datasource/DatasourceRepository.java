package jev.dashboard.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatasourceRepository {
    private static final TypeReference<List<FieldSchema>> SCHEMA = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};
    private static final TypeReference<MergeDefinition> MERGE = new TypeReference<>() {};
    private static final TypeReference<List<Ratio>> RATIOS = new TypeReference<>() {};

    /** 資料來源共享 spec 4.1：分享設定，users 是轉換過、排好序的帳號名稱。 */
    public record Share(boolean allUsers, List<String> users) {}

    /**
     * 資料來源共享 spec 第 3 節：「能用」＝自己的，或分享給我（指定帳號、所有帳號）而且擁有者啟用中。
     * 擁有者自己那條不看 account，沒有帳號的舊資料照樣用得到。
     */
    private static final String USABLE = """
        (d.created_by = :by
         or ((d.shared_all or exists (select 1 from datasource_share s where s.datasource_id = d.id and s.username = :by))
             and exists (select 1 from account o where o.username = d.created_by and o.enabled)))""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json;

    public DatasourceRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    /** ratios_json 不寫，用欄位預設的空清單：新建的來源一律沒有比率（B3 spec 第 7 節）。 */
    public void insert(Datasource d) {
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by, merge_json,
                                        row_count)
                values (:id, :name, :kind, cast(:schema as jsonb), :apiUrl, cast(:headers as jsonb), :createdBy,
                        cast(:merge as jsonb), :rowCount)""")
            .param("id", d.id())
            .param("name", d.name())
            .param("kind", d.kind())
            .param("schema", write(d.schema()))
            .param("apiUrl", d.apiUrl())
            .param("headers", write(d.apiHeaders() == null ? Map.of() : d.apiHeaders()))
            .param("createdBy", d.createdBy())
            // 一般來源要存 SQL 的 NULL，不能存 JSON 的 null，否則過不了 datasource_merge_json_check；
            // 指定 VARCHAR，null 才會跟其他 jsonb 參數一樣走 varchar → jsonb 的轉型
            .param("merge", d.merge() == null ? null : writeMerge(d.merge()), Types.VARCHAR)
            .param("rowCount", d.rowCount(), Types.INTEGER)
            .update();
    }

    public Optional<Datasource> find(UUID id, String userId) {
        return jdbc.sql("select * from datasource where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    public List<Datasource> list(String userId) {
        return jdbc.sql("select * from datasource where created_by = :by order by created_at desc")
            .param("by", userId)
            .query(this::map).list();
    }

    public Optional<Datasource> findUsable(UUID id, String userId) {
        return jdbc.sql("select d.* from datasource d where d.id = :id and " + USABLE)
            .param("id", id).param("by", userId)
            .query(this::map).optional();
    }

    /** 自己的在前，別人分享的在後，各自依建立時間由新到舊（spec 4.2）。 */
    public List<Datasource> listUsable(String userId) {
        return jdbc.sql("select d.* from datasource d where " + USABLE + " order by d.created_by <> :by, d.created_at desc")
            .param("by", userId)
            .query(this::map).list();
    }

    /** 不帶擁有者條件：算合併層數、判斷來源還在不在用（spec 2.2、2.3），不能拿來給使用者看。 */
    public Optional<Datasource> findAny(UUID id) {
        return jdbc.sql("select * from datasource where id = :id").param("id", id).query(this::map).optional();
    }

    /**
     * 所有人的合併來源；更新資料時找出直接或間接用到它的（spec 2.3）。
     * ponytail: 整批讀出來在 Java 裡篩（MergeDefinition.inputIds）；合併來源多到上千份再改成 SQL 篩 merge_json
     */
    public List<Datasource> allMerged() {
        return jdbc.sql("select * from datasource where kind = 'merged'").query(this::map).list();
    }

    public Share share(UUID id) {
        boolean all = jdbc.sql("select shared_all from datasource where id = :id").param("id", id).query(Boolean.class).single();
        List<String> users = jdbc.sql("select username from datasource_share where datasource_id = :id order by username")
            .param("id", id).query(String.class).list();
        return new Share(all, users);
    }

    /** spec 4.1：整份取代，更新 shared_all、刪掉舊的、寫入新的在同一個交易。 */
    @Transactional
    public void setShare(UUID id, Share s) {
        jdbc.sql("update datasource set shared_all = :all where id = :id").param("all", s.allUsers()).param("id", id).update();
        jdbc.sql("delete from datasource_share where datasource_id = :id").param("id", id).update();
        for (String u : s.users()) {
            jdbc.sql("insert into datasource_share (datasource_id, username) values (:id, :u)")
                .param("id", id).param("u", u).update();
        }
    }

    public boolean accountExists(String username) {
        return jdbc.sql("select exists (select 1 from account where username = :u)").param("u", username)
            .query(Boolean.class).single();
    }

    public boolean delete(UUID id, String userId) {
        return jdbc.sql("delete from datasource where id = :id and created_by = :by")
            .param("id", id).param("by", userId)
            .update() == 1;
    }

    public void updateSchema(UUID id, List<FieldSchema> schema) {
        jdbc.sql("update datasource set schema_json = cast(:schema as jsonb) where id = :id")
            .param("schema", write(schema)).param("id", id)
            .update();
    }

    public void updateRatios(UUID id, List<Ratio> ratios) {
        jdbc.sql("update datasource set ratios_json = cast(:ratios as jsonb) where id = :id")
            .param("ratios", write(ratios)).param("id", id)
            .update();
    }

    /** 上傳：來源和資料列在同一個交易裡寫，資料列寫失敗時來源也不留。 */
    @Transactional
    public void insertWithRows(Datasource d, List<Map<String, Object>> rows) {
        insert(d);
        insertRows(d.id(), rows);
    }

    public void insertRows(UUID id, List<Map<String, Object>> rows) {
        insertRows(id, 0, rows);
    }

    /** row_no 從 firstRowNo 開始編；接在後面時從現有筆數開始（更新資料 spec 2.4）。 */
    public void insertRows(UUID id, int firstRowNo, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) args.add(new Object[] {id, firstRowNo + i, write(rows.get(i))});
        jdbcTemplate.batchUpdate(
            "insert into datasource_rows (datasource_id, row_no, row_json) values (?, ?, cast(? as jsonb))", args);
    }

    public void deleteRows(UUID id) {
        jdbc.sql("delete from datasource_rows where datasource_id = :id").param("id", id).update();
    }

    public void updateRowCount(UUID id, Integer count) {
        jdbc.sql("update datasource set row_count = :n where id = :id")
            .param("n", count, Types.INTEGER).param("id", id)
            .update();
    }

    /**
     * 更新資料 spec 4.2：先鎖住來源那一列（select … for update）再做 work，整段是同一個交易。
     * 同一個來源同時更新時，後到的等前一個寫完才讀現有的資料列，接在後面的 row_no 不會撞在一起。
     * 改顯示名稱、敏感設定也走這裡，等更新寫完才讀 schema。
     */
    @Transactional
    public void locked(UUID id, Runnable work) {
        jdbc.sql("select id from datasource where id = :id for update").param("id", id).query(UUID.class).optional();
        work.run();
    }

    public List<Map<String, Object>> rows(UUID id) {
        return jdbc.sql("select row_json from datasource_rows where datasource_id = :id order by row_no")
            .param("id", id)
            .query(String.class).list()
            .stream().map(s -> read(s, ROW)).toList();
    }

    private Datasource map(ResultSet rs, int rowNum) throws SQLException {
        String merge = rs.getString("merge_json");
        return new Datasource(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("kind"),
            read(rs.getString("schema_json"), SCHEMA),
            rs.getString("api_url"),
            read(rs.getString("api_headers_json"), HEADERS),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class),
            merge == null ? null : read(merge, MERGE),
            read(rs.getString("ratios_json"), RATIOS),
            rs.getObject("row_count", Integer.class));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 指定型別寫出，JSON 才一定帶 op。 */
    private String writeMerge(MergeDefinition merge) {
        try {
            return json.writerFor(MergeDefinition.class).writeValueAsString(merge);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
