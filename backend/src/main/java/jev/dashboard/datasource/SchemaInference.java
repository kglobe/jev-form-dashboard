package jev.dashboard.datasource;

import java.math.BigDecimal;
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
            List<Object> values = values(rows, h);
            FieldType type = values.isEmpty() ? FieldType.STRING
                // 8 碼日期也是合法的數字，要比數字先判斷
                : values.stream().allMatch(v -> compactDate(v) != null) ? FieldType.DATE
                : values.stream().allMatch(v -> parseNumber(v) != null) ? FieldType.NUMBER
                : values.stream().allMatch(v -> parseDate(v) != null) ? FieldType.DATE
                : FieldType.STRING;
            out.add(stats(h, type, values));
        }
        return out;
    }

    /**
     * 更新資料 spec 2.5：型別、顯示名稱、敏感設定照舊，相異值數、樣本、日期範圍用 rows 重算，算法跟第一次上傳一樣。
     * 整份覆蓋給檔案讀出來的值；接在後面給整理過的值（舊的資料列只剩這個）。
     */
    public static List<FieldSchema> restat(List<FieldSchema> schema, List<Map<String, Object>> rows) {
        return schema.stream().map(f -> stats(f.name(), f.type(), values(rows, f.name()))
            .withSensitive(f.sensitive()).withLabel(f.label())).toList();
    }

    private static List<Object> values(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(r -> r.get(name)).filter(v -> !isBlank(v)).toList();
    }

    private static FieldSchema stats(String name, FieldType type, List<Object> values) {
        Set<String> distinct = new LinkedHashSet<>();
        values.forEach(v -> distinct.add(text(v)));
        String min = null;
        String max = null;
        if (type == FieldType.DATE && !values.isEmpty()) {
            List<LocalDate> dates = values.stream().map(SchemaInference::parseDate).sorted().toList();
            min = dates.getFirst().toString();
            max = dates.getLast().toString();
        }
        return new FieldSchema(name, type, distinct.size(), distinct.stream().limit(5).toList(), false, min, max);
    }

    /**
     * 值寫成文字。Excel 的數字儲存格讀進來是 Double，去掉多餘的 .0（5.0 → 5），跟同一份 CSV 一樣；
     * 不然 CSV 接 Excel 時，文字欄位裡同一個代碼會變成 5 和 5.0 兩類。
     */
    private static String text(Object v) {
        return v instanceof Double d ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : String.valueOf(v);
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
                    case STRING -> text(v);
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
