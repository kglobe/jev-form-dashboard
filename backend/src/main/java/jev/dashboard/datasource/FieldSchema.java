package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * label 是使用者設定的顯示名稱，沒設定時是 null，畫面上直接顯示 name。
 * sensitiveFrom 只在讀取合併來源時填入，是讓這欄變敏感的來源名稱（spec 6.3）；不存進資料庫，null 時 JSON 不輸出。
 */
public record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                          boolean sensitive, String min, String max, String label,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String sensitiveFrom) {
    public FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                       boolean sensitive, String min, String max) {
        this(name, type, distinctCount, samples, sensitive, min, max, null, null);
    }

    public FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                       boolean sensitive, String min, String max, String label) {
        this(name, type, distinctCount, samples, sensitive, min, max, label, null);
    }

    public FieldSchema withSensitive(boolean value) {
        return new FieldSchema(name, type, distinctCount, samples, value, min, max, label, sensitiveFrom);
    }

    public FieldSchema withLabel(String value) {
        return new FieldSchema(name, type, distinctCount, samples, sensitive, min, max, value, sensitiveFrom);
    }

    /** 從輸入來源繼承來的敏感設定；from 是在哪個來源標成敏感的。 */
    public FieldSchema withInheritedSensitive(String from) {
        return new FieldSchema(name, type, distinctCount, samples, true, min, max, label, from);
    }
}
