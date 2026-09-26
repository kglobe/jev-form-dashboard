package jev.dashboard.datasource;

import java.util.List;

/** label 是使用者設定的顯示名稱，沒設定時是 null，畫面上直接顯示 name。 */
public record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                          boolean sensitive, String min, String max, String label) {
    public FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                       boolean sensitive, String min, String max) {
        this(name, type, distinctCount, samples, sensitive, min, max, null);
    }

    public FieldSchema withSensitive(boolean value) {
        return new FieldSchema(name, type, distinctCount, samples, value, min, max, label);
    }

    public FieldSchema withLabel(String value) {
        return new FieldSchema(name, type, distinctCount, samples, sensitive, min, max, value);
    }
}
