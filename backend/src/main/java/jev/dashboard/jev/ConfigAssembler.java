package jev.dashboard.jev;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;

/** 把 JEV 的答案組成儀表板配置。全部是純函式，不呼叫 JEV。 */
public final class ConfigAssembler {
    /** Choice／Score 的信心門檻；照 JevConfig.TUNED_MODEL（jev-1.13.0）調的，換模型要重新調校。 */
    public static final double MIN_CONFIDENCE = 0.5;
    static final int MAX_CANDIDATES = 8;
    /** spec 6.5：調整後最多 6 張圖（不含明細表）。 */
    static final int MAX_CHARTS = 6;

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

    /** focus：spec 6.1 的焦點欄位（rel_i 答「直接相關」），或使用者勾選想看的欄位。 */
    public record FieldEval(FieldSchema field, double importance, String role, boolean focus) {
        public FieldEval(FieldSchema field, double importance, String role) {
            this(field, importance, role, false);
        }

        public String name() {
            return field.name();
        }
    }

    public record Combo(List<FieldEval> fields, List<String> chartTypes, double score) {
        List<FieldEval> byRole(String role) {
            return fields.stream().filter(f -> f.role().equals(role)).toList();
        }

        boolean hasFocus() {
            return fields.stream().anyMatch(FieldEval::focus);
        }
    }

    /** 選出來的一張圖：哪一組欄位、畫成哪種圖。 */
    private record Pick(Combo combo, String type) {}

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
            out.add(new FieldEval(f, importance, r, isFocus(answers.get("rel_" + i))));
        }
        return out;
    }

    /** spec 6.1：rel_i 的 round(score) 等於 2（直接相關），而且 confidence ≥ 0.5；JEV 沒回答就不算。四捨五入對級照 jev-1.13.0 調的。 */
    static boolean isFocus(Answer rel) {
        return rel != null && rel.score() != null && rel.confidence() != null
            && Math.round(rel.score()) == 2 && rel.confidence() >= MIN_CONFIDENCE;
    }

    public static int chartCount(Answer a) {
        if (a == null || a.score() == null) return 1;
        return (int) Math.max(1, Math.min(6, Math.round(a.score()) + 1));
    }

    /** spec 6.2：角色不是 ignore 的焦點欄位不看重要性，排在最前面；其他欄位照原本的規則補，總數最多 8 個。 */
    public static List<FieldEval> candidates(List<FieldEval> evals) {
        Comparator<FieldEval> byImportance = Comparator.comparingDouble(FieldEval::importance).reversed();
        Stream<FieldEval> focus = evals.stream()
            .filter(e -> e.focus() && !e.role().equals("ignore"))
            .sorted(byImportance);
        Stream<FieldEval> others = evals.stream()
            .filter(e -> !e.focus() && !e.role().equals("ignore") && Math.round(e.importance()) >= 1)
            .sorted(byImportance);
        return Stream.concat(focus, others).limit(MAX_CANDIDATES).toList();
    }

    /** 全部合法組合，照 spec 6.3 排序：含焦點欄位的排前面，再比平均重要性，同分照產生順序（穩定排序）。 */
    public static List<Combo> ranked(List<FieldEval> c) {
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
        all.sort(Comparator.comparing(Combo::hasFocus).reversed()
            .thenComparing(Comparator.comparingDouble(Combo::score).reversed()));
        return all;
    }

    /** 第一次分析要問的組合：排序後的前 2K 組。 */
    public static List<Combo> combos(List<FieldEval> c, int k) {
        List<Combo> all = ranked(c);
        return List.copyOf(all.subList(0, Math.min(all.size(), 2 * k)));
    }

    /**
     * 第一次分析的組裝（spec 6.4）：照排序取前 K 張能用的圖。有焦點組合、但選出來的圖都沒用到焦點欄位時，
     * 把排最前面的焦點組合用它第一個合法圖型放在第一張；原本已經 K 張就拿掉最後一張。
     */
    public static List<ChartSpec> assemble(List<Combo> combos, Map<String, Answer> round2, int k,
                                           List<String> tableColumns) {
        List<Pick> picks = picks(combos, round2, k);
        Combo top = combos.stream().filter(Combo::hasFocus).findFirst().orElse(null);
        if (top != null && picks.stream().noneMatch(p -> p.combo().hasFocus())) {
            if (picks.size() == k) picks.remove(picks.size() - 1);
            picks.add(0, new Pick(top, top.chartTypes().get(0)));
        }
        List<ChartSpec> charts = new ArrayList<>();
        for (Pick p : picks) charts.add(chart("c" + (charts.size() + 1), p));
        charts.add(table("c" + (charts.size() + 1), tableColumns));
        return layOut(charts);
    }

    /** 照組合順序取第二輪能用的答案（不是 none、信心夠、在合法圖型裡），最多 limit 張。 */
    private static List<Pick> picks(List<Combo> combos, Map<String, Answer> round2, int limit) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < combos.size() && out.size() < limit; i++) {
            Answer a = round2.get("combo_" + i);
            if (a == null || a.choice() == null || a.choice().equals("none")) continue;
            if (a.confidence() == null || a.confidence() < MIN_CONFIDENCE) continue;
            if (!combos.get(i).chartTypes().contains(a.choice())) continue;
            out.add(new Pick(combos.get(i), a.choice()));
        }
        return out;
    }

    /** gridPos 先留空，由 layOut 統一排；用到焦點欄位的圖標 options.focus。 */
    private static ChartSpec chart(String chartId, Pick p) {
        Mapping m = mapping(p.type(), p.combo());
        Map<String, Object> options = p.combo().hasFocus() ? Map.of("focus", true) : Map.of();
        return new ChartSpec(chartId, p.type(), title(p.type(), m), null, m, options);
    }

    private static ChartSpec table(String chartId, List<String> columns) {
        return new ChartSpec(chartId, "table", "明細", null,
            new Mapping(null, null, null, null, null, columns, "none", null), Map.of());
    }

    /** spec 6.6：圖表依順序每列兩張（6×4），明細表放最後（12×5）。其他設定原封不動。 */
    static List<ChartSpec> layOut(List<ChartSpec> specs) {
        List<ChartSpec> out = new ArrayList<>();
        for (ChartSpec s : specs) {
            if (s.type().equals("table")) continue;
            int n = out.size();
            out.add(at(s, new GridPos((n % 2) * 6, (n / 2) * 4, 6, 4)));
        }
        int y = (out.size() + 1) / 2 * 4;
        for (ChartSpec s : specs) {
            if (s.type().equals("table")) out.add(at(s, new GridPos(0, y, 12, 5)));
        }
        return out;
    }

    private static ChartSpec at(ChartSpec s, GridPos pos) {
        return new ChartSpec(s.chartId(), s.type(), s.title(), pos, s.mapping(), s.options(), s.datasourceId());
    }

    /** spec 6.5 第 1 點：keep_<chartId> 答 drop 而且信心 ≥ 0.5 才移除，其他情況（包含沒回答）一律保留；明細表不問，一律保留。 */
    public static List<ChartSpec> kept(List<ChartSpec> current, Map<String, Answer> round1) {
        return current.stream()
            .filter(s -> s.type().equals("table") || !dropped(round1.get("keep_" + s.chartId())))
            .toList();
    }

    private static boolean dropped(Answer a) {
        return a != null && "drop".equals(a.choice()) && a.confidence() != null && a.confidence() >= MIN_CONFIDENCE;
    }

    /**
     * 調整模式要問的組合（spec 5.3、6.3）：只留含焦點欄位的組合；欄位集合跟某張保留的圖一樣時，把那張圖的圖型從選項拿掉，
     * 選項只剩 none 的組合不問；最多 2 ×（6 − 保留張數）組。
     */
    public static List<Combo> adjustCombos(List<Combo> ranked, List<ChartSpec> kept) {
        List<ChartSpec> keptCharts = withoutTables(kept);
        List<Combo> out = new ArrayList<>();
        for (Combo c : ranked) {
            if (!c.hasFocus()) continue;
            Set<String> fields = c.fields().stream().map(FieldEval::name).collect(Collectors.toSet());
            List<String> types = c.chartTypes().stream()
                .filter(t -> keptCharts.stream().noneMatch(s -> s.type().equals(t) && fieldSet(s).equals(fields)))
                .toList();
            if (!types.isEmpty()) out.add(new Combo(c.fields(), types, c.score()));
        }
        int limit = 2 * Math.max(0, MAX_CHARTS - keptCharts.size());
        return List.copyOf(out.subList(0, Math.min(out.size(), limit)));
    }

    /**
     * 調整模式的組裝（spec 6.5–6.7）：保留的圖排前面，用到焦點欄位的加上 options.focus，其他設定不動；
     * 第二輪接受的焦點組合依序加在後面，總數最多 6 張；
     * 一張都沒加、保留的圖也沒用到焦點欄位、還有空位時，補上排最前面的焦點組合。
     * 新圖的 chartId 接在 current 所有 chartId 最大的數字後面；明細表沿用 current 的，放最後。不補一般性的圖。
     */
    public static List<ChartSpec> adjust(List<ChartSpec> current, List<ChartSpec> kept, List<Combo> combos,
                                         Map<String, Answer> round2, Set<String> focusFields) {
        List<ChartSpec> charts = new ArrayList<>();
        boolean keptUsesFocus = false;
        for (ChartSpec s : withoutTables(kept)) {
            boolean usesFocus = fieldSet(s).stream().anyMatch(focusFields::contains);
            keptUsesFocus |= usesFocus;
            charts.add(usesFocus ? withFocus(s) : s);
        }
        int room = Math.max(0, MAX_CHARTS - charts.size());
        List<Pick> picks = picks(combos, round2, room);
        if (picks.isEmpty() && !combos.isEmpty() && !keptUsesFocus && room > 0) {
            picks.add(new Pick(combos.get(0), combos.get(0).chartTypes().get(0)));
        }
        int n = maxChartNumber(current);
        for (Pick p : picks) charts.add(chart("c" + (++n), p));
        kept.stream().filter(s -> s.type().equals("table")).forEach(charts::add);
        return layOut(charts);
    }

    private static ChartSpec withFocus(ChartSpec s) {
        Map<String, Object> options = s.options() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.options());
        options.put("focus", true);
        return new ChartSpec(s.chartId(), s.type(), s.title(), s.gridPos(), s.mapping(), options, s.datasourceId());
    }

    private static List<ChartSpec> withoutTables(List<ChartSpec> specs) {
        return specs.stream().filter(s -> !s.type().equals("table")).toList();
    }

    private static Set<String> fieldSet(ChartSpec s) {
        return new HashSet<>(UniversalTransformer.referencedFields(s.mapping()));
    }

    /** 形如 c<數字> 的 chartId 取最大的數字（明細表、這次移除的都算），新圖從下一號開始，保證不重複。 */
    static int maxChartNumber(List<ChartSpec> specs) {
        return specs.stream().map(ChartSpec::chartId)
            .filter(id -> id != null && id.matches("c\\d{1,9}"))
            .mapToInt(id -> Integer.parseInt(id.substring(1)))
            .max().orElse(0);
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

    public static String title(String type, Mapping m) {
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
