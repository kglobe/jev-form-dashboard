package jev.dashboard.jev;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.TimeBucket;

/** 把一張卡片的資料摘要成「最後一期 vs 前一期」（只算已經結束的期），只送這段摘要給 JEV，不送原始資料。 */
public final class HighlightSummarizer {
    // 只有這幾種類型的預設聚合結果安全可送；table/scatter/radar 或 aggregate=none 會是投影過的原始資料列
    private static final Set<String> SUMMARIZABLE_TYPES = Set.of("line", "bar", "stacked_bar", "heatmap");
    // 高基數的 series（例如客戶）逐一送出會接近逐筆資料、請求也跟著變大
    private static final int MAX_SERIES = 10;

    private HighlightSummarizer() {}

    public static Map<String, Object> summarize(ChartSpec spec, ChartData data, LocalDate today) {
        if (data.error() != null || data.rows() == null || spec.mapping() == null || spec.mapping().timeGrain() == null
                || !SUMMARIZABLE_TYPES.contains(spec.type()) || "none".equals(spec.mapping().aggregate())) {
            return null;
        }
        // 接 ERP 的即時資料時，最後一期通常還沒結束（例如月初到今天），拿去比完整的前一期會一直被標成大跌；
        // 所以只比已經結束的期：今天所在的那一期和之後的期都不算。期的 key 和 TimeBucket 一樣，字串排序就是時間順序
        String inProgress = TimeBucket.of(today, spec.mapping().timeGrain());
        List<String> periods = data.rows().stream().map(r -> String.valueOf(r.get(0))).distinct()
            .filter(p -> p.compareTo(inProgress) < 0).sorted().toList();
        if (periods.size() < 2) return null;
        String current = periods.getLast();
        String previous = periods.get(periods.size() - 2);
        boolean hasSeries = data.dimensions().size() == 3;

        Map<String, double[]> bySeries = new LinkedHashMap<>(); // [前一期, 最後一期]
        for (List<Object> row : data.rows()) {
            String period = String.valueOf(row.get(0));
            int slot = period.equals(current) ? 1 : period.equals(previous) ? 0 : -1;
            if (slot < 0) continue;
            String name = hasSeries ? String.valueOf(row.get(1)) : "全部";
            Object v = row.get(row.size() - 1);
            bySeries.computeIfAbsent(name, k -> new double[2])[slot] += v instanceof Number n ? n.doubleValue() : 0;
        }
        if (bySeries.size() > MAX_SERIES) bySeries = keepBiggestChanges(bySeries);

        List<Map<String, Object>> series = new ArrayList<>();
        bySeries.forEach((name, v) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("previous", v[0]);
            s.put("current", v[1]);
            s.put("changePercent", v[0] == 0 ? null : Math.round((v[1] - v[0]) / Math.abs(v[0]) * 1000) / 10.0);
            series.add(s);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", spec.title());
        out.put("metric", spec.mapping().value() == null ? "count" : spec.mapping().value());
        out.put("previousPeriod", previous);
        out.put("period", current);
        out.put("series", series);
        return out;
    }

    /** 留變化 |本期 − 前期| 最大的 MAX_SERIES 個（同分照名字排），其餘加總成「其他」；資料本來就有「其他」就併進去。 */
    private static Map<String, double[]> keepBiggestChanges(Map<String, double[]> bySeries) {
        List<Map.Entry<String, double[]>> ranked = bySeries.entrySet().stream()
            .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -Math.abs(e.getValue()[1] - e.getValue()[0]))
                .thenComparing(Map.Entry::getKey))
            .toList();
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
        kept.merge("其他", rest, (a, b) -> new double[] {a[0] + b[0], a[1] + b[1]});
        return kept;
    }
}
