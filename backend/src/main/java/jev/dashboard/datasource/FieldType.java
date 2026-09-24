package jev.dashboard.datasource;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FieldType {
    NUMBER("number"), DATE("date"), STRING("string");

    private final String json;

    FieldType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
