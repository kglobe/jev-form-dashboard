package jev.dashboard.jev;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.TimeBucket;

/** 把一張卡片的資料摘要成「最後一期 vs 前一期」（只算已經結束的期），只送這段摘要給 JEV，不送原始資料。 */
public final class HighlightSummarizer {
    // 只有這幾種類型的預設聚合結果安全可送；table/scatter/radar 或 aggregate=none 會是投影過的原始資料列
    // KPI spec 2.5：KPI 卡跟沒有系列的折線圖一樣摘要
    private static final Set<String> SUMMARIZABLE_TYPES = Set.of("line", "bar", "stacked_bar", "heatmap", "kpi");
    // 高基數的 series（例如客戶）逐一送出會接近逐筆資料、請求也跟著變大
    private static final int MAX_SERIES = 10;
    private static final Comparator<Map.Entry<String, double[]>> BIGGEST_CHANGE_FIRST =
        Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -Math.abs(e.getValue()[1] - e.getValue()[0]))
            .thenComparing(Map.Entry::getKey);

    public record Summary(Map<String, Object> state, Highlight.Change change) {}

    private HighlightSummarizer() {}

    /** 只看卡片設定，這張圖摘要得了嗎；還沒讀資料就能判斷要不要為它載入來源。 */
    public static boolean summarizable(ChartSpec spec) {
        return spec.mapping() != null && spec.mapping().timeGrain() != null && SUMMARIZABLE_TYPES.contains(spec.type())
            && !"none".equals(spec.mapping().aggregate());
    }

    public static Summary summarize(ChartSpec spec, ChartData data, LocalDate today) {
        if (!summarizable(spec) || data.error() != null || data.rows() == null) return null;
        // 接 ERP 的即時資料時，最後一期通常還沒結束（例如月初到今天），拿去比完整的前一期會一直被標成大跌；
        // 所以只比已經結束的期：今天所在的那一期和之後的期都不算
        List<String> periods = data.rows().stream().map(r -> String.valueOf(r.get(0))).distinct()
            .filter(p -> TimeBucket.ended(p, today, spec.mapping().timeGrain())).sorted().toList();
        if (periods.size() < 2) return null;
        String current = periods.getLast();
        String previous = periods.get(periods.size() - 2);
        List<String> measures = spec.mapping().measures();
        boolean hasSeries = measures == null && data.dimensions().size() == 3;
        // B3 spec 5.4：比率的圖比百分點；沒有值不代表比率是 0，不能當 0
        boolean percent = Boolean.TRUE.equals(data.percent());

        // [前一期, 最後一期]。系列照名稱排，跟資料列的順序無關（外部 API 每次給的順序可能不同），
        // 送給 JEV 的內容才會一樣、快取得到；兩個數值的圖照 mapping 的順序
        Map<String, double[]> bySeries = measures != null ? new LinkedHashMap<>() : new TreeMap<>();
        for (List<Object> row : data.rows()) {
            String period = String.valueOf(row.get(0));
            int slot = period.equals(current) ? 1 : period.equals(previous) ? 0 : -1;
            if (slot < 0) continue;
            if (measures != null) {
                // B2 spec 5.7：兩個數值的圖，每個數值當一條系列，名稱是原始欄位名稱
                for (int i = 1; i < row.size(); i++) add(bySeries, data.dimensions().get(i), slot, row.get(i));
            } else if (percent) {
                put(bySeries, hasSeries ? String.valueOf(row.get(1)) : "全部", slot, row.get(row.size() - 1));
            } else {
                add(bySeries, hasSeries ? String.valueOf(row.get(1)) : "全部", slot, row.get(row.size() - 1));
            }
        }
        if (percent) {
            // 前一期或最後一期是空白的系列不比；一條都不能比，這張圖就不做異常檢查
            bySeries.values().removeIf(v -> Double.isNaN(v[0]) || Double.isNaN(v[1]));
            if (bySeries.isEmpty()) return null;
        }
        Map.Entry<String, double[]> top = bySeries.entrySet().stream().min(BIGGEST_CHANGE_FIRST).orElseThrow();
        Highlight.Change change = new Highlight.Change(previous, current,
            measures != null ? data.dimensions().indexOf(top.getKey()) - 1 : null, hasSeries ? top.getKey() : null,
            top.getValue()[0], top.getValue()[1], percent);
        Map<String, double[]> sent = bySeries.size() > MAX_SERIES ? keepBiggestChanges(bySeries, !percent) : bySeries;

        List<Map<String, Object>> series = new ArrayList<>();
        sent.forEach((name, v) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            if (percent) {
                s.put("previous", points(v[0]));
                s.put("current", points(v[1]));
                s.put("changePoints", points(v[1] - v[0]));
            } else {
                s.put("previous", v[0]);
                s.put("current", v[1]);
                s.put("changePercent", v[0] == 0 ? null : Math.round((v[1] - v[0]) / Math.abs(v[0]) * 1000) / 10.0);
            }
            series.add(s);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", spec.title());
        out.put("metric", measures != null ? String.join("、", measures)
            : spec.mapping().value() == null ? "count" : spec.mapping().value());
        if (percent) out.put("unit", "%");
        out.put("previousPeriod", previous);
        out.put("period", current);
        out.put("series", series);
        return new Summary(out, change);
    }

    /** 空值當 0。 */
    private static void add(Map<String, double[]> bySeries, String name, int slot, Object v) {
        bySeries.computeIfAbsent(name, k -> new double[2])[slot] += v instanceof Number n ? n.doubleValue() : 0;
    }

    /** 比率：沒有值就是 NaN，不當 0。 */
    private static void put(Map<String, double[]> bySeries, String name, int slot, Object v) {
        bySeries.computeIfAbsent(name, k -> new double[] {Double.NaN, Double.NaN})[slot] =
            v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** 比率（0.052）換成百分比數字（5.2），四捨五入到小數一位。 */
    private static double points(double ratio) {
        return Math.round(ratio * 1000) / 10.0;
    }

    /**
     * 留變化 |本期 − 前期| 最大的 MAX_SERIES 個（同分照名字排），其餘加總成「其他」；資料本來就有「其他」就併進去。
     * sumRest 是 false 時（比率加起來沒有意義），其餘直接不送。
     */
    private static Map<String, double[]> keepBiggestChanges(Map<String, double[]> bySeries, boolean sumRest) {
        List<Map.Entry<String, double[]>> ranked = bySeries.entrySet().stream().sorted(BIGGEST_CHANGE_FIRST).toList();
        Map<String, double[]> kept = new LinkedHashMap<>();
        double[] rest = new double[2];
        for (int i = 0; i < ranked.size(); i++) {
            double[] v = ranked.get(i).getValue();
            if (i < MAX_SERIES) {
                kept.put(ranked.get(i).getKey(), v);
            } else {
                rest[0] += v[0];
                rest[1] += v[1];
            }
        }
        if (sumRest) kept.merge("其他", rest, (a, b) -> new double[] {a[0] + b[0], a[1] + b[1]});
        return kept;
    }
}
