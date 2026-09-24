package jev.dashboard.datasource;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SchemaInference {
    // ponytail: 只取日期部分，時間捨去；分桶最細到 day，夠用
    private static final Pattern DATE = Pattern.compile(
        "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    // 0 開頭又接數字的（手機 0912…、統編 04595257、工號 007）是代碼不是數字，當數字會掉前導零
    private static final Pattern NUMBER = Pattern.compile("[-+]?(0|[1-9]\\d*)(\\.\\d+)?");

    private SchemaInference() {}

    public static List<FieldSchema> infer(List<String> headers, List<Map<String, Object>> rows) {
        List<FieldSchema> out = new ArrayList<>();
        for (String h : headers) {
            List<Object> values = rows.stream().map(r -> r.get(h)).filter(v -> !isBlank(v)).toList();
            FieldType type = values.isEmpty() ? FieldType.STRING
                : values.stream().allMatch(v -> parseNumber(v) != null) ? FieldType.NUMBER
                : values.stream().allMatch(v -> parseDate(v) != null) ? FieldType.DATE
                : FieldType.STRING;
            Set<String> distinct = new LinkedHashSet<>();
            values.forEach(v -> distinct.add(String.valueOf(v)));
            String min = null;
            String max = null;
            if (type == FieldType.DATE) {
                List<LocalDate> dates = values.stream().map(SchemaInference::parseDate).sorted().toList();
                min = dates.getFirst().toString();
                max = dates.getLast().toString();
            }
            out.add(new FieldSchema(h, type, distinct.size(), distinct.stream().limit(5).toList(), false, min, max));
        }
        return out;
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> rows, List<FieldSchema> schema) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (FieldSchema f : schema) {
                if (!row.containsKey(f.name())) continue;
                Object v = row.get(f.name());
                m.put(f.name(), isBlank(v) ? null : switch (f.type()) {
                    case NUMBER -> parseNumber(v);
                    case DATE -> {
                        LocalDate d = parseDate(v);
                        yield d == null ? null : d.toString();
                    }
                    case STRING -> String.valueOf(v);
                });
            }
            out.add(m);
        }
        return out;
    }

    static boolean isBlank(Object v) {
        return v == null || (v instanceof String s && s.isBlank());
    }

    static Double parseNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim().replace(",", "");
        return NUMBER.matcher(s).matches() ? Double.valueOf(s) : null;
    }

    static LocalDate parseDate(Object v) {
        Matcher m = DATE.matcher(String.valueOf(v).trim());
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
