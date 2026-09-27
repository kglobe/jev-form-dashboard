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

@Repository
public class DatasourceRepository {
    private static final TypeReference<List<FieldSchema>> SCHEMA = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};
    private static final TypeReference<MergeDefinition> MERGE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json;

    public DatasourceRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    public void insert(Datasource d) {
        jdbc.sql("""
                insert into datasource (id, name, kind, schema_json, api_url, api_headers_json, created_by, merge_json)
                values (:id, :name, :kind, cast(:schema as jsonb), :apiUrl, cast(:headers as jsonb), :createdBy,
                        cast(:merge as jsonb))""")
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

    public void updateSchema(UUID id, List<FieldSchema> schema) {
        jdbc.sql("update datasource set schema_json = cast(:schema as jsonb) where id = :id")
            .param("schema", write(schema)).param("id", id)
            .update();
    }

    public void insertRows(UUID id, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) args.add(new Object[] {id, i, write(rows.get(i))});
        jdbcTemplate.batchUpdate(
            "insert into datasource_rows (datasource_id, row_no, row_json) values (?, ?, cast(? as jsonb))", args);
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
            merge == null ? null : read(merge, MERGE));
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
