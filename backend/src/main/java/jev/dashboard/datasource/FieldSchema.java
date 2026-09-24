package jev.dashboard.datasource;

import java.util.List;

public record FieldSchema(String name, FieldType type, int distinctCount, List<String> samples,
                          boolean sensitive, String min, String max) {
    public FieldSchema withSensitive(boolean value) {
        return new FieldSchema(name, type, distinctCount, samples, value, min, max);
    }
}
