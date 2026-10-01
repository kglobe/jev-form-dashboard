package jev.dashboard.datasource;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SchemaInference {
    // ponytail: 只取日期部分，時間捨去；分桶最細到 day，夠用
    // 年份 2～3 碼是民國年（113/09/24、99-1-5），加 1911
    private static final Pattern DATE = Pattern.compile(
        "(\\d{4}|\\d{2,3})[-/](\\d{1,2})[-/](\\d{1,2})([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    // 不帶分隔符號的 8 碼日期（20240924）；年份限 1900～2099，少把 8 位數的金額、編號誤判成日期
    private static final Pattern COMPACT_DATE = Pattern.compile("((?:19|20)\\d{2})(\\d{2})(\\d{2})");
    // 0 開頭又接數字的（手機 0912…、統編 04595257、工號 007）是代碼不是數字，當數字會掉前導零
    private static final Pattern NUMBER = Pattern.compile("[-+]?(0|[1-9]\\d*)(\\.\\d+)?");

    private SchemaInference() {}

    public static List<FieldSchema> infer(List<String> headers, List<Map<String, Object>> rows) {
        List<FieldSchema> out = new ArrayList<>();
        for (String h : headers) {
            List<Object> values = rows.stream().map(r -> r.get(h)).filter(v -> !isBlank(v)).toList();
            FieldType type = values.isEmpty() ? FieldType.STRING
                // 8 碼日期也是合法的數字，要比數字先判斷
                : values.stream().allMatch(v -> compactDate(v) != null) ? FieldType.DATE
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

    /**
     * 更新資料 spec 2.5：型別、顯示名稱、敏感設定照舊，相異值數、樣本、日期範圍用整份資料重算。
     * rows 是整理過的值，數字寫回字串時去掉多餘的 .0，樣本才跟第一次上傳一樣是 1200，不是 1200.0。
     */
    public static List<FieldSchema> restat(List<FieldSchema> schema, List<Map<String, Object>> rows) {
        return schema.stream().map(f -> {
            Set<String> distinct = new LinkedHashSet<>();
            rows.stream().map(r -> r.get(f.name())).filter(v -> !isBlank(v))
                .forEach(v -> distinct.add(v instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString() : String.valueOf(v)));
            // 整理過的日期都是 YYYY-MM-DD，照字串排就是照日期排
            boolean dated = f.type() == FieldType.DATE && !distinct.isEmpty();
            return new FieldSchema(f.name(), f.type(), distinct.size(), distinct.stream().limit(5).toList(), f.sensitive(),
                dated ? Collections.min(distinct) : null, dated ? Collections.max(distinct) : null, f.label());
        }).toList();
    }

    /** 所有資料列 key 的聯集，依第一次出現的順序；外部 API 常省略值是 null 的欄位，只看第一列會漏。 */
    public static Set<String> fieldNames(List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        rows.forEach(r -> names.addAll(r.keySet()));
        return names;
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
        LocalDate compact = compactDate(v);
        if (compact != null) return compact;
        Matcher m = DATE.matcher(String.valueOf(v).trim());
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        return date(m.group(1).length() < 4 ? year + 1911 : year, m.group(2), m.group(3));
    }

    /** 8 碼日期；外部 API 的整數、Excel 的數字儲存格（Double）也算。 */
    private static LocalDate compactDate(Object v) {
        String s = v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
            ? String.valueOf(n.longValue()) : String.valueOf(v).trim();
        Matcher m = COMPACT_DATE.matcher(s);
        return m.matches() ? date(Integer.parseInt(m.group(1)), m.group(2), m.group(3)) : null;
    }

    private static LocalDate date(int year, String month, String day) {
        try {
            return LocalDate.of(year, Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
