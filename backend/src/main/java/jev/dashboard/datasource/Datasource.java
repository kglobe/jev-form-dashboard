package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * merge 只有合併來源（kind = merged）才有，其他來源是 null，JSON 不輸出。
 * ratios 是使用者加的比率（B3 spec 4.1），沒有就是空清單。
 * rowCount 是建立時的筆數，上傳來源更新資料後跟著換。null 有兩種：V5 之前建立的外部 API、合併來源沒記；
 * 合併來源的輸入來源更新過資料，筆數被清掉（更新資料 spec 2.6）。
 * unpivot 是上傳時寬表轉長表的轉換方式（寬表轉長表 spec 第 3 節），沒轉過是 null，JSON 不輸出。
 */
public record Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                         Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) MergeDefinition merge, List<Ratio> ratios,
                         Integer rowCount, @JsonInclude(JsonInclude.Include.NON_NULL) Unpivot unpivot) {
    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, null, List.of(), null, null);
    }

    public Datasource(UUID id, String name, String kind, List<FieldSchema> schema, String apiUrl,
                      Map<String, String> apiHeaders, String createdBy, OffsetDateTime createdAt, MergeDefinition merge) {
        this(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, List.of(), null, null);
    }

    public Datasource withSchema(List<FieldSchema> value) {
        return new Datasource(id, name, kind, value, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios, rowCount, unpivot);
    }

    public Datasource withRatios(List<Ratio> value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, value, rowCount, unpivot);
    }

    public Datasource withRowCount(Integer value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios, value, unpivot);
    }

    public Datasource withUnpivot(Unpivot value) {
        return new Datasource(id, name, kind, schema, apiUrl, apiHeaders, createdBy, createdAt, merge, ratios, rowCount, value);
    }

    /**
     * 資料來源共享 spec 2.1、4.2：給被分享的人看的。不露出外部 API 的網址和 header（含 ${DS_…} 名稱），
     * 也不露出合併來源用了哪些輸入來源：merge 不給，繼承來的敏感照舊是 true，但 sensitiveFrom 清掉。
     */
    public Datasource forOthers() {
        List<FieldSchema> hidden = schema.stream().map(f -> f.sensitiveFrom() == null ? f : f.withInheritedSensitive(null)).toList();
        return new Datasource(id, name, kind, hidden, null, Map.of(), createdBy, createdAt, null, ratios, rowCount, unpivot);
    }
}
