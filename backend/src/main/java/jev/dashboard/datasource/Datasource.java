package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** merge 只有合併來源（kind = merged）才有，其他來源是 null，JSON 不輸出。 */
public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MergeDefinition merge) {
    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, null);
    }

    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt, merge);
    }
}
