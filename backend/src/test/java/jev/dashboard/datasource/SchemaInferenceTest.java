package jev.dashboard.datasource;

import static jev.dashboard.datasource.FieldType.DATE;
import static jev.dashboard.datasource.FieldType.NUMBER;
import static jev.dashboard.datasource.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaInferenceTest {
    static Map<String, Object> row(Object d, Object a, Object r, Object m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("d", d);
        row.put("a", a);
        row.put("r", r);
        row.put("m", m);
        return row;
    }

    static final List<Map<String, Object>> ROWS = List.of(
        row("2024/01/05", "1,200", "北區", ""),
        row("2024-06-20", "800", "南區", "x"),
        row("2024-03-01 13:45:00", "800", "北區", ""));

    @Test
    void infersTypesDistinctCountSamplesAndDateRange() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        assertThat(s.get(0)).isEqualTo(new FieldSchema("d", DATE, 3,
            List.of("2024/01/05", "2024-06-20", "2024-03-01 13:45:00"), false, "2024-01-05", "2024-06-20"));
        assertThat(s.get(1).type()).isEqualTo(NUMBER);
        assertThat(s.get(1).distinctCount()).isEqualTo(2);
        assertThat(s.get(2).type()).isEqualTo(STRING);
        assertThat(s.get(2).distinctCount()).isEqualTo(2);
        assertThat(s.get(3).type()).isEqualTo(STRING);
        assertThat(s.get(3).samples()).containsExactly("x");
    }

    @Test
    void excelNumbersStayNumbers() {
        List<FieldSchema> s = SchemaInference.infer(List.of("a"), List.of(Map.of("a", 1200.0)));
        assertThat(s.get(0).type()).isEqualTo(NUMBER);
    }

    @Test
    void normalizeConvertsValuesByType() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        Map<String, Object> first = SchemaInference.normalize(ROWS, s).get(0);
        assertThat(first).containsEntry("d", "2024-01-05").containsEntry("a", 1200.0).containsEntry("r", "北區");
        assertThat(first).containsKey("m");
        assertThat(first.get("m")).isNull();
    }

    @Test
    void zeroPaddedCodesStayTextExactly() {
        List<Map<String, Object>> rows = List.of(
            Map.of("phone", "0912345678", "tax_id", "04595257", "emp", "007"),
            Map.of("phone", "0987654321", "tax_id", "12345675", "emp", "12"));
        List<FieldSchema> s = SchemaInference.infer(List.of("phone", "tax_id", "emp"), rows);
        assertThat(s).extracting(FieldSchema::type).containsExactly(STRING, STRING, STRING);
        assertThat(s.get(0).samples()).containsExactly("0912345678", "0987654321");
        assertThat(SchemaInference.normalize(rows, s).get(0))
            .containsEntry("phone", "0912345678").containsEntry("tax_id", "04595257").containsEntry("emp", "007");
    }

    @Test
    void plainNumbersStillParseAsNumbers() {
        List<Map<String, Object>> rows = List.of(Map.of("a", "0"), Map.of("a", "0.5"), Map.of("a", "-12"), Map.of("a", "1,200"));
        List<FieldSchema> s = SchemaInference.infer(List.of("a"), rows);
        assertThat(s.get(0).type()).isEqualTo(NUMBER);
        assertThat(SchemaInference.normalize(rows, s)).extracting(r -> r.get("a")).containsExactly(0.0, 0.5, -12.0, 1200.0);
    }

    @Test
    void normalizeLeavesOutFieldsMissingFromTheRow() {
        List<FieldSchema> s = SchemaInference.infer(List.of("d", "a", "r", "m"), ROWS);
        Map<String, Object> normalized = SchemaInference.normalize(List.of(Map.of("a", "5")), s).get(0);
        assertThat(normalized).containsOnlyKeys("a");
    }
}
