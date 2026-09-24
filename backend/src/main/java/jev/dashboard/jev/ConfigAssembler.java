package jev.dashboard.jev;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;

/** 把 JEV 的答案組成儀表板配置。全部是純函式，不呼叫 JEV。 */
public final class ConfigAssembler {
    public static final double MIN_CONFIDENCE = 0.5;
    static final int MAX_CANDIDATES = 8;

    public static final Map<String, String> CHART_TYPES_ZH = new LinkedHashMap<>();
    static {
        CHART_TYPES_ZH.put("bar", "長條圖：比較各分類或各期的數值");
        CHART_TYPES_ZH.put("line", "折線圖：看數值隨時間的變化");
        CHART_TYPES_ZH.put("pie", "圓餅圖：看各分類佔整體的比例");
        CHART_TYPES_ZH.put("scatter", "散佈圖：看兩個數值之間的關係");
        CHART_TYPES_ZH.put("radar", "雷達圖：比較少數幾個分類在多個指標上的表現");
        CHART_TYPES_ZH.put("heatmap", "熱力圖：看兩個維度交叉後的數值分布");
        CHART_TYPES_ZH.put("stacked_bar", "堆疊長條圖：比較各分類的組成");
    }

    public record FieldEval(FieldSchema field, double importance, String role) {
        public String name() {
            return field.name();
        }
    }

    public record Combo(List<FieldEval> fields, List<String> chartTypes, double score) {
        List<FieldEval> byRole(String role) {
            return fields.stream().filter(f -> f.role().equals(role)).toList();
        }
    }

    private ConfigAssembler() {}

    public static List<FieldEval> evaluate(List<FieldSchema> schema, Map<String, Answer> answers) {
        List<FieldEval> out = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            FieldSchema f = schema.get(i);
            Answer imp = answers.get("imp_" + i);
            Answer role = answers.get("role_" + i);
            double importance = imp == null || imp.score() == null ? 0 : imp.score();
            String r = role == null || role.choice() == null || role.confidence() == null
                || role.confidence() < MIN_CONFIDENCE ? "ignore" : role.choice();
            if (r.equals("measure") && f.type() != FieldType.NUMBER) r = "ignore";
            if (r.equals("time") && f.type() != FieldType.DATE) r = "ignore";
            out.add(new FieldEval(f, importance, r));
        }
        return out;
    }

    public static int chartCount(Answer a) {
        if (a == null || a.score() == null) return 1;
        return (int) Math.max(1, Math.min(6, Math.round(a.score()) + 1));
    }

    public static List<FieldEval> candidates(List<FieldEval> evals) {
        return evals.stream()
            .filter(e -> !e.role().equals("ignore") && Math.round(e.importance()) >= 1)
            .sorted(Comparator.comparingDouble(FieldEval::importance).reversed())
            .limit(MAX_CANDIDATES)
            .toList();
    }

    public static List<Combo> combos(List<FieldEval> c, int k) {
        List<Combo> all = new ArrayList<>();
        int n = c.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) add(all, List.of(c.get(i), c.get(j)), pairTypes(c.get(i), c.get(j)));
        }
        List<FieldEval> measures = c.stream().filter(e -> e.role().equals("measure")).toList();
        if (measures.size() >= 3) {
            for (FieldEval d : c) {
                if (!d.role().equals("dimension") || d.field().distinctCount() > 5) continue;
                List<FieldEval> fields = new ArrayList<>();
                fields.add(d);
                fields.addAll(measures.subList(0, Math.min(8, measures.size())));
                add(all, fields, List.of("radar"));
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int l = j + 1; l < n; l++) {
                    add(all, List.of(c.get(i), c.get(j), c.get(l)), tripleTypes(c.get(i), c.get(j), c.get(l)));
                }
            }
        }
        all.sort(Comparator.comparingDouble(Combo::score).reversed()); // 穩定排序，同分照產生順序
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }

    public static List<ChartSpec> assemble(List<Combo> combos, Map<String, Answer> round2, int k,
                                           List<String> tableColumns) {
        List<ChartSpec> charts = new ArrayList<>();
        for (int i = 0; i < combos.size() && charts.size() < k; i++) {
            Answer a = round2.get("combo_" + i);
            if (a == null || a.choice() == null || a.choice().equals("none")) continue;
            if (a.confidence() == null || a.confidence() < MIN_CONFIDENCE) continue;
            if (!combos.get(i).chartTypes().contains(a.choice())) continue;
            Mapping m = mapping(a.choice(), combos.get(i));
            int n = charts.size();
            charts.add(new ChartSpec("c" + (n + 1), a.choice(), title(a.choice(), m),
                new GridPos((n % 2) * 6, (n / 2) * 4, 6, 4), m, Map.of()));
        }
        int rowsUsed = (charts.size() + 1) / 2;
        charts.add(new ChartSpec("c" + (charts.size() + 1), "table", "明細", new GridPos(0, rowsUsed * 4, 12, 5),
            new Mapping(null, null, null, null, null, tableColumns, "none", null), Map.of()));
        return charts;
    }

    public static String timeGrain(FieldSchema f) {
        if (f.min() == null || f.max() == null) return "month";
        LocalDate min = LocalDate.parse(f.min());
        LocalDate max = LocalDate.parse(f.max());
        if (ChronoUnit.DAYS.between(min, max) <= 90) return "day";
        return max.isAfter(min.plusYears(3)) ? "quarter" : "month";
    }

    static Mapping mapping(String type, Combo c) {
        List<FieldEval> t = c.byRole("time");
        List<FieldEval> d = c.byRole("dimension");
        List<FieldEval> m = c.byRole("measure");
        String time = t.isEmpty() ? null : t.get(0).name();
        String grain = t.isEmpty() ? null : timeGrain(t.get(0).field());
        return switch (type) {
            case "line", "bar" -> time != null
                ? new Mapping(time, null, null, m.get(0).name(), null, null, "sum", grain)
                : new Mapping(d.get(0).name(), null, null, m.get(0).name(), null, null, "sum", null);
            case "pie" -> new Mapping(d.get(0).name(), null, null, m.get(0).name(), null, null, "sum", null);
            case "scatter" -> new Mapping(m.get(0).name(), m.get(1).name(), null, null, null, null, "none", null);
            case "radar" -> new Mapping(null, null, d.get(0).name(), null, m.stream().map(FieldEval::name).toList(), null, "sum", null);
            case "stacked_bar" -> {
                if (time != null) yield new Mapping(time, null, d.get(0).name(), m.get(0).name(), null, null, "sum", grain);
                FieldEval series = d.get(0).field().distinctCount() < d.get(1).field().distinctCount() ? d.get(0) : d.get(1);
                FieldEval x = series == d.get(0) ? d.get(1) : d.get(0);
                yield new Mapping(x.name(), null, series.name(), m.get(0).name(), null, null, "sum", null);
            }
            case "heatmap" -> time != null
                ? new Mapping(time, d.get(0).name(), null, m.get(0).name(), null, null, "sum", grain)
                : new Mapping(d.get(0).name(), d.get(1).name(), null, m.get(0).name(), null, null, "sum", null);
            default -> throw new IllegalArgumentException("未知的圖表類型：" + type);
        };
    }

    static String title(String type, Mapping m) {
        if (type.equals("scatter")) return m.x() + " 與 " + m.y();
        if (type.equals("radar")) return m.series() + " 各指標比較";
        String second = m.series() != null ? m.series() : m.y();
        return m.value() + " 依 " + m.x() + (second == null ? "" : " 與 " + second);
    }

    private static void add(List<Combo> all, List<FieldEval> fields, List<String> types) {
        if (types.isEmpty()) return;
        all.add(new Combo(fields, types, fields.stream().mapToDouble(FieldEval::importance).average().orElse(0)));
    }

    private static String roles(FieldEval... fields) {
        return Stream.of(fields).map(FieldEval::role).sorted().collect(Collectors.joining(","));
    }

    private static List<String> pairTypes(FieldEval a, FieldEval b) {
        return switch (roles(a, b)) {
            case "measure,time" -> List.of("line", "bar");
            case "dimension,measure" -> {
                FieldEval dim = a.role().equals("dimension") ? a : b;
                yield dim.field().distinctCount() <= 8 ? List.of("bar", "pie") : List.of("bar");
            }
            case "measure,measure" -> List.of("scatter");
            default -> List.of();
        };
    }

    private static List<String> tripleTypes(FieldEval a, FieldEval b, FieldEval c) {
        int minDimDistinct = Stream.of(a, b, c).filter(f -> f.role().equals("dimension"))
            .mapToInt(f -> f.field().distinctCount()).min().orElse(Integer.MAX_VALUE);
        return switch (roles(a, b, c)) {
            case "dimension,measure,time", "dimension,dimension,measure" ->
                minDimDistinct <= 10 ? List.of("stacked_bar", "heatmap") : List.of("heatmap");
            default -> List.of();
        };
    }
}
