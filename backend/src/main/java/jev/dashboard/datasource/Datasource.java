package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * merge 只有合併來源（kind = merged）才有，其他來源是 null，JSON 不輸出。
 * ratios 是使用者加的比率（B3 spec 4.1），沒有就是空清單。
 * rowCount 是建立時的筆數；V5 之前建立的外部 API、合併來源沒記，是 null。
 */
public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MergeDefinition merge, List<Ratio> ratios,
                         Integer rowCount) {
    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, null, List.of(), null);
    }

    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt, MergeDefinition merge) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, List.of(), null);
    }

    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios, rowCount);
    }

    public Datasource withRatios(List<Ratio> value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, value, rowCount);
    }

    public Datasource withRowCount(int value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios, value);
    }
}
