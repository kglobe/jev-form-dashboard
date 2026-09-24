package jev.dashboard.transform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ponytail: 在 Java 記憶體裡聚合，上限是 jev.upload.max-rows（10 萬筆）；資料再大就改成 SQL 的 jsonb 運算
public final class UniversalTransformer {
    // ponytail: 明細表只回前 500 筆，要看全部時再加分頁
    static final int TABLE_ROW_LIMIT = 500;
    static final List<String> TYPES = List.of("line", "bar", "pie", "scatter", "table", "radar", "heatmap", "stacked_bar");
    static final List<String> AGGREGATES = List.of("sum", "count", "avg", "none");

    private UniversalTransformer() {}

    public static String defaultAggregate(String type) {
        return type.equals("scatter") || type.equals("table") ? "none" : "sum";
    }

    /**
     * 存檔、預覽前檢查 transform() 處理不處理得了這張卡片：可以回 null，不行回中文原因。
     * 每種圖表必填的 mapping 欄位跟 transform() 的 switch 對齊，改一邊要一起改。
     */
    public static String problem(ChartSpec spec) {
        if (spec.type() == null || !TYPES.contains(spec.type())) {
            return "type 必須是 " + String.join("、", TYPES) + " 其中之一";
        }
        Mapping m = spec.mapping();
        if (m == null) return "缺少 mapping";
        String missing = switch (spec.type()) {
            case "bar", "line", "pie" -> blank(m.x()) ? "x" : null;
            case "stacked_bar" -> blank(m.x()) ? "x" : blank(m.series()) ? "series" : null;
            case "heatmap", "scatter" -> blank(m.x()) ? "x" : blank(m.y()) ? "y" : null;
            case "radar" -> blank(m.series()) ? "series" : blank(m.measures()) ? "measures" : null;
            default -> blank(m.columns()) ? "columns" : null; // table
        };
        if (missing != null) return spec.type() + " 需要 mapping." + missing;
        if (m.aggregate() != null && !AGGREGATES.contains(m.aggregate())) {
            return "aggregate 必須是 " + String.join("、", AGGREGATES) + " 其中之一";
        }
        if (m.timeGrain() != null && !TimeBucket.GRAINS.contains(m.timeGrain())) {
            return "timeGrain 必須是 " + String.join("、", TimeBucket.GRAINS) + " 其中之一";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean blank(List<String> fields) {
        return fields == null || fields.isEmpty() || fields.stream().anyMatch(UniversalTransformer::blank);
    }

    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields) {
        Mapping m = spec.mapping();
        for (String field : referencedFields(m)) {
            if (!availableFields.contains(field)) return ChartData.missing(spec.chartId(), spec.type(), field);
        }
        String agg = m.aggregate() != null ? m.aggregate() : defaultAggregate(spec.type());
        return switch (spec.type()) {
            case "bar", "line", "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain());
            case "stacked_bar" -> grouped(spec, rows, List.of(m.x(), m.series()), m.value(), agg, m.timeGrain());
            case "heatmap" -> grouped(spec, rows, List.of(m.x(), m.y()), m.value(), agg, m.timeGrain());
            case "radar" -> radar(spec, rows, m.series(), m.measures(), agg.equals("none") ? "sum" : agg);
            case "scatter" -> project(spec, rows, List.of(m.x(), m.y()), true, Integer.MAX_VALUE);
            case "table" -> project(spec, rows, m.columns(), false, TABLE_ROW_LIMIT);
            default -> throw new IllegalArgumentException("未知的圖表類型：" + spec.type());
        };
    }

    public static List<String> referencedFields(Mapping m) {
        List<String> out = new ArrayList<>();
        Stream.of(m.x(), m.y(), m.series(), m.value()).filter(Objects::nonNull).forEach(out::add);
        if (m.measures() != null) out.addAll(m.measures());
        if (m.columns() != null) out.addAll(m.columns());
        return out;
    }

    private static ChartData grouped(ChartSpec spec, List<Map<String, Object>> rows, List<String> keys,
                                     String value, String agg, String grain) {
        List<String> dims = new ArrayList<>(keys);
        dims.add(value != null ? value : "count");
        if (agg.equals("none")) return project(spec, rows, dims, true, Integer.MAX_VALUE);

        Map<List<Object>, double[]> acc = new LinkedHashMap<>(); // [加總, 筆數]
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                Object v = row.get(keys.get(i));
                key.add(i == 0 && grain != null ? TimeBucket.of(v, grain) : v);
            }
            if (key.contains(null)) continue;
            Object v = value == null ? null : row.get(value);
            if (!agg.equals("count") && !(v instanceof Number)) continue;
            double[] a = acc.computeIfAbsent(key, k -> new double[2]);
            if (v instanceof Number n) a[0] += n.doubleValue();
            a[1]++;
        }

        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>(key);
            r.add(result(agg, a));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> sortKey(r, keys.size())));
        } else if (keys.size() == 1) {
            out.sort(Comparator.comparingDouble((List<Object> r) -> (Double) r.get(1)).reversed());
        }
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }

    private static ChartData radar(ChartSpec spec, List<Map<String, Object>> rows, String series,
                                   List<String> measures, String agg) {
        Map<Object, double[][]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object s = row.get(series);
            if (s == null) continue;
            double[][] a = acc.computeIfAbsent(s, k -> new double[measures.size()][2]);
            for (int i = 0; i < measures.size(); i++) {
                Object v = row.get(measures.get(i));
                if (v instanceof Number n) a[i][0] += n.doubleValue();
                if (v instanceof Number || agg.equals("count")) a[i][1]++;
            }
        }
        List<List<Object>> out = new ArrayList<>();
        acc.forEach((s, a) -> {
            List<Object> r = new ArrayList<>();
            r.add(s);
            for (double[] x : a) r.add(result(agg, x));
            out.add(r);
        });
        List<String> dims = new ArrayList<>();
        dims.add(series);
        dims.addAll(measures);
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }

    private static ChartData project(ChartSpec spec, List<Map<String, Object>> rows, List<String> fields,
                                     boolean skipNulls, int limit) {
        List<List<Object>> out = new ArrayList<>();
        boolean truncated = false;
        for (Map<String, Object> row : rows) {
            List<Object> r = new ArrayList<>(fields.size());
            for (String f : fields) r.add(row.get(f));
            if (skipNulls && r.contains(null)) continue;
            if (out.size() >= limit) {
                truncated = true;
                break;
            }
            out.add(r);
        }
        return new ChartData(spec.chartId(), spec.type(), List.copyOf(fields), out, null, null, truncated ? Boolean.TRUE : null);
    }

    private static double result(String agg, double[] a) {
        return switch (agg) {
            case "sum" -> a[0];
            case "count" -> a[1];
            case "avg" -> a[1] == 0 ? 0 : a[0] / a[1];
            default -> throw new IllegalArgumentException("未知的 aggregate：" + agg);
        };
    }

    private static String sortKey(List<Object> row, int keyCount) {
        return row.subList(0, keyCount).stream().map(String::valueOf).collect(Collectors.joining("\u0000"));
    }
}
