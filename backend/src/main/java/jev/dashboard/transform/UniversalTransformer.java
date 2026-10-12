package jev.dashboard.transform;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.datasource.Ratio;

// ponytail: 在 Java 記憶體裡聚合，上限是 jev.upload.max-rows（10 萬筆）；資料再大就改成 SQL 的 jsonb 運算
public final class UniversalTransformer {
    // ponytail: 明細表只回前 500 筆，要看全部時再加分頁
    static final int TABLE_ROW_LIMIT = 500;
    /** 分組欄位是空值的列歸到這一組，不丟掉 */
    public static final String BLANK = "（空白）";
    static final List<String> TYPES = List.of("line", "bar", "pie", "scatter", "table", "radar", "heatmap", "stacked_bar", "kpi");
    static final List<String> AGGREGATES = List.of("sum", "count", "avg", "none");
    /** KPI spec 4：KPI 卡最多回最後 13 期；前端只用最後兩期，多留的給之後的走勢小圖。 */
    static final int KPI_PERIODS = 13;

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
            case "kpi" -> blank(m.x()) ? "x" : blank(m.value()) ? "value" : blank(m.timeGrain()) ? "timeGrain" : null;
            default -> blank(m.columns()) ? "columns" : null; // table
        };
        if (missing != null) return spec.type() + " 需要 mapping." + missing;
        if (m.aggregate() != null && !AGGREGATES.contains(m.aggregate())) {
            return "aggregate 必須是 " + String.join("、", AGGREGATES) + " 其中之一";
        }
        if (m.timeGrain() != null && !TimeBucket.GRAINS.contains(m.timeGrain())) {
            return "timeGrain 必須是 " + String.join("、", TimeBucket.GRAINS) + " 其中之一";
        }
        // KPI spec 3：一期一個數字，只照月或季分期，而且要聚合
        if (spec.type().equals("kpi")) {
            if (m.timeGrain().equals("day")) return "kpi 的 timeGrain 只能是 month、quarter";
            if ("none".equals(m.aggregate())) return "kpi 不能用 aggregate none";
        }
        // B2 spec 5.3、第 8 節：長條圖、折線圖可以用剛好兩個不同的 measures 取代 value
        if (m.measures() != null && (spec.type().equals("bar") || spec.type().equals("line"))) {
            if (m.value() != null) return "value 和 measures 只能擇一";
            if (m.measures().size() != 2 || blank(m.measures()) || m.measures().get(0).equals(m.measures().get(1))) {
                return "兩個數值的圖需要剛好兩個不同的 measures";
            }
            if ("none".equals(m.aggregate())) return "兩個數值的圖不能用 aggregate none";
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
        return transform(spec, rows, availableFields, List.of());
    }

    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios) {
        return transform(spec, rows, availableFields, ratios, List.of());
    }

    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios, List<String> dateFields) {
        return transform(spec, rows, availableFields, ratios, dateFields, LocalDate.now());
    }

    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios, List<String> dateFields, LocalDate today) {
        return transform(spec, rows, availableFields, ratios, dateFields, today, false);
    }

    /**
     * ratios 是這個來源的比率定義（B3 spec 5.1）；value 是比率名稱時，每組分子、分母各自加總再相除。
     * dateFields 是這個來源的日期欄位，依 schema 順序；比率只算分子、分母都有資料的月份（期間對齊）。
     * today 只有 KPI 卡用得到：只回已經結束的期（KPI spec 4）；DashboardDataService 傳它 Clock 的今天。
     * allRows 是匯出用的（匯出 spec 4）：明細表回傳全部的列；畫面用 false，只回前 TABLE_ROW_LIMIT 筆。
     */
    public static ChartData transform(ChartSpec spec, List<Map<String, Object>> rows, Set<String> availableFields,
                                      List<Ratio> ratios, List<String> dateFields, LocalDate today, boolean allRows) {
        Mapping m = spec.mapping();
        Ratio ratio = ratios.stream().filter(r -> r.name().equals(m.value())).findFirst().orElse(null);
        List<String> fields = new ArrayList<>(referencedFields(m));
        if (ratio != null) {
            // B3 spec 5.2：比率本身不在資料裡，要檢查的是分子、分母；放在 value 以外的比率名稱照樣找不到
            fields.remove(ratio.name());
            fields.addAll(List.of(ratio.numerator(), ratio.denominator()));
        }
        for (String field : fields) {
            if (!availableFields.contains(field)) return ChartData.missing(spec.chartId(), spec.type(), field);
        }
        String agg = m.aggregate() != null ? m.aggregate() : defaultAggregate(spec.type());
        try {
            return shaped(spec, rows, m, agg, ratio, dateFields, today, allRows ? Integer.MAX_VALUE : TABLE_ROW_LIMIT);
        } catch (DateTimeParseException e) {
            // TimeBucket 分期時遇到不是日期的值：只有這張卡出錯，其他卡照常畫
            return ChartData.notADate(spec.chartId(), spec.type(), m.x());
        }
    }

    private static ChartData shaped(ChartSpec spec, List<Map<String, Object>> rows, Mapping m, String agg, Ratio ratio,
                                    List<String> dateFields, LocalDate today, int tableLimit) {
        return switch (spec.type()) {
            case "bar", "line" -> m.measures() != null
                ? measured(spec, rows, m.x(), m.measures(), agg, m.timeGrain())
                : grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain(), ratio, dateFields);
            // KPI spec 4：分組跟有 timeGrain 的折線圖一樣，比率的期間對齊也一樣
            case "kpi" -> ended(grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain(), ratio, dateFields),
                m.timeGrain(), today);
            case "pie" -> grouped(spec, rows, List.of(m.x()), m.value(), agg, m.timeGrain(), ratio, dateFields);
            case "stacked_bar" -> grouped(spec, rows, List.of(m.x(), m.series()), m.value(), agg, m.timeGrain(), ratio, dateFields);
            case "heatmap" -> grouped(spec, rows, List.of(m.x(), m.y()), m.value(), agg, m.timeGrain(), ratio, dateFields);
            case "radar" -> radar(spec, rows, m.series(), m.measures(), agg.equals("none") ? "sum" : agg);
            case "scatter" -> project(spec, rows, List.of(m.x(), m.y()), true, Integer.MAX_VALUE);
            case "table" -> project(spec, rows, m.columns(), false, tableLimit);
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
                                     String value, String agg, String grain, Ratio ratio, List<String> dateFields) {
        List<String> dims = new ArrayList<>(keys);
        dims.add(value != null ? value : "count");
        // B3 spec 5.1：比率不管 aggregate 寫什麼（包括 none），都是每組先加總再相除
        if (agg.equals("none") && ratio == null) return project(spec, rows, dims, true, Integer.MAX_VALUE);

        // 有 timeGrain 時 x 就是日期欄位，照 x 對齊；沒有就找來源的日期欄位
        Overlap overlap = ratio == null ? null : overlap(rows, ratio, grain != null ? List.of(keys.get(0)) : dateFields);
        // [加總, 筆數]；比率是 [分子加總, 分子筆數, 分母加總, 分母筆數]
        Map<List<Object>, double[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                Object v = row.get(keys.get(i));
                key.add(i == 0 && grain != null ? TimeBucket.of(v, grain) : Objects.requireNonNullElse(v, BLANK));
            }
            // 分期的日期是空值時照舊不算：期別照字串排，「（空白）」會排在最後，異常檢查會把它當成最新一期，
            // 比率的期間對齊也是照字串比月份
            if (key.contains(null)) continue;
            if (ratio != null) {
                Object num = row.get(ratio.numerator());
                Object den = row.get(ratio.denominator());
                if (!(num instanceof Number) && !(den instanceof Number)) continue; // 兩邊都沒有值的列不算
                double[] a = acc.computeIfAbsent(key, k -> new double[4]);
                if (overlap != null && overlap.excludes(row)) continue; // 重疊月份以外的列不算，這一組照樣留著位置
                if (num instanceof Number nv) {
                    a[0] += nv.doubleValue();
                    a[1]++;
                }
                if (den instanceof Number dv) {
                    a[2] += dv.doubleValue();
                    a[3]++;
                }
                continue;
            }
            Object v = value == null ? null : row.get(value);
            // B2 spec 5.4：count 只算 value 不是空值的列；沒有 value 時算列數
            if (agg.equals("count") ? value != null && v == null : !(v instanceof Number)) continue;
            double[] a = acc.computeIfAbsent(key, k -> new double[2]);
            if (v instanceof Number n) a[0] += n.doubleValue();
            a[1]++;
        }

        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>(key);
            // 不能寫成三元運算：divide 回 Double、result 回 double，比率是 null 時會在拆箱丟 NPE
            if (ratio != null) r.add(divide(a));
            else r.add(result(agg, a));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> sortKey(r, keys.size())));
        } else if (keys.size() == 1) {
            // 比率可能是 null，排最後
            out.sort(Comparator.comparing((List<Object> r) -> (Double) r.get(1),
                Comparator.nullsLast(Comparator.<Double>reverseOrder())));
        }
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null, null, null, null,
            ratio != null ? Boolean.TRUE : null, overlap == null ? null : new ChartData.RatioPeriod(overlap.from(), overlap.to()));
    }

    /**
     * KPI spec 2.1、4：只留已經結束的期（跟異常檢查同一個判斷），已經照時間排好，最多留最後 KPI_PERIODS 期。
     * grouped() 回傳的是新的清單，直接裁。
     */
    private static ChartData ended(ChartData d, String grain, LocalDate today) {
        d.rows().removeIf(r -> !TimeBucket.ended(String.valueOf(r.get(0)), today, grain));
        d.rows().subList(0, Math.max(0, d.rows().size() - KPI_PERIODS)).clear();
        return d;
    }

    /** 比率只算 from～to 這幾個月；from 比 to 晚時沒有重疊，每一列都不算。月份是 TimeBucket 的 2026-07，照字串比大小。 */
    private record Overlap(String field, String from, String to) {
        boolean excludes(Map<String, Object> row) {
            String month = TimeBucket.of(row.get(field), "month");
            return month == null || month.compareTo(from) < 0 || month.compareTo(to) > 0;
        }
    }

    /**
     * 期間對齊：用第一個分子、分母都有日期的欄位，兩邊有資料的月份範圍取交集。兩邊範圍一樣時不用對齊，回 null。
     * ponytail: 只看整段的頭尾，中間缺的月份不管；某個月真的沒有退貨時不會被當成沒資料
     */
    private static Overlap overlap(List<Map<String, Object>> rows, Ratio ratio, List<String> dateFields) {
        for (String field : dateFields) {
            String[] num = months(rows, field, ratio.numerator());
            String[] den = months(rows, field, ratio.denominator());
            if (num == null || den == null) continue;
            if (Arrays.equals(num, den)) return null;
            return new Overlap(field, max(num[0], den[0]), min(num[1], den[1]));
        }
        return null;
    }

    /** value 有數字的列，日期落在的第一個和最後一個月；一列都沒有回 null。 */
    private static String[] months(List<Map<String, Object>> rows, String dateField, String value) {
        String first = null;
        String last = null;
        for (Map<String, Object> row : rows) {
            if (!(row.get(value) instanceof Number)) continue;
            String month = TimeBucket.of(row.get(dateField), "month");
            if (month == null) continue;
            first = first == null ? month : min(first, month);
            last = last == null ? month : max(last, month);
        }
        return first == null ? null : new String[] {first, last};
    }

    private static String min(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String max(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** B3 spec 5.1：缺分子、缺分母，或分母加總是 0，比率是 null（空白，不是 0%）。 */
    private static Double divide(double[] a) {
        return a[1] == 0 || a[3] == 0 || a[2] == 0 ? null : a[0] / a[2];
    }

    /**
     * B2 spec 5.3：兩個數值各自聚合；某一組沒有這個數值的任何值就是 null，兩個都沒有的組不輸出。
     * 有 timeGrain 照期別排，沒有就照第一個數值由大到小，null 排最後。
     */
    private static ChartData measured(ChartSpec spec, List<Map<String, Object>> rows, String x,
                                      List<String> measures, String agg, String grain) {
        Map<Object, double[][]> acc = new LinkedHashMap<>(); // 每個數值一組 [加總, 筆數]
        for (Map<String, Object> row : rows) {
            Object key = grain != null ? TimeBucket.of(row.get(x), grain) : Objects.requireNonNullElse(row.get(x), BLANK);
            if (key == null) continue; // 分期的日期是空值，理由同 grouped()
            for (int i = 0; i < measures.size(); i++) {
                Object v = row.get(measures.get(i));
                if (agg.equals("count") ? v == null : !(v instanceof Number)) continue;
                double[] a = acc.computeIfAbsent(key, k -> new double[measures.size()][2])[i];
                if (v instanceof Number n) a[0] += n.doubleValue();
                a[1]++;
            }
        }
        List<List<Object>> out = new ArrayList<>();
        acc.forEach((key, a) -> {
            List<Object> r = new ArrayList<>();
            r.add(key);
            for (double[] one : a) r.add(one[1] == 0 ? null : result(agg, one));
            out.add(r);
        });
        if (grain != null) {
            out.sort(Comparator.comparing((List<Object> r) -> String.valueOf(r.get(0))));
        } else {
            out.sort(Comparator.comparing((List<Object> r) -> (Double) r.get(1),
                Comparator.nullsLast(Comparator.<Double>reverseOrder())));
        }
        List<String> dims = new ArrayList<>();
        dims.add(x);
        dims.addAll(measures);
        return new ChartData(spec.chartId(), spec.type(), dims, out, null, null);
    }

    private static ChartData radar(ChartSpec spec, List<Map<String, Object>> rows, String series,
                                   List<String> measures, String agg) {
        Map<Object, double[][]> acc = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object s = Objects.requireNonNullElse(row.get(series), BLANK);
            double[][] a = acc.computeIfAbsent(s, k -> new double[measures.size()][2]);
            for (int i = 0; i < measures.size(); i++) {
                Object v = row.get(measures.get(i));
                if (v instanceof Number n) a[i][0] += n.doubleValue();
                if (v instanceof Number || agg.equals("count") && v != null) a[i][1]++;
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
