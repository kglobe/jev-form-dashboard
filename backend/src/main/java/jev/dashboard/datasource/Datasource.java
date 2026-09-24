package jev.dashboard.datasource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt);
    }
}
