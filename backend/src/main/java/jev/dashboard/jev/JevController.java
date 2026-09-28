package jev.dashboard.jev;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardDataService;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.MergeEngine;
import jev.dashboard.datasource.Ratio;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jev")
public class JevController {
    /** intents：需求紀錄（選填），最後一句是這次的需求。current：有值就是調整模式，放目前預覽裡的圖（含明細表）。 */
    /** fields 是使用者勾選想看的欄位，分析時當成焦點欄位優先出圖；沒勾就是空的。 */
    public record AnalyzeRequest(UUID datasourceId, List<String> intents, List<ChartSpec> current, List<String> fields) {}

    private final DatasourceService datasources;
    private final AnalyzeService analyzer;
    private final DashboardService dashboards;
    private final DashboardDataService dashboardData;
    private final HighlightService highlights;
    private final Clock clock;

    public JevController(DatasourceService datasources, AnalyzeService analyzer, DashboardService dashboards,
                         DashboardDataService dashboardData, HighlightService highlights, Clock clock) {
        this.datasources = datasources;
        this.analyzer = analyzer;
        this.dashboards = dashboards;
        this.dashboardData = dashboardData;
        this.highlights = highlights;
        this.clock = clock;
    }

    @PostMapping("/analyze")
    public DashboardConfig analyze(@RequestHeader("X-User-Id") String userId, @RequestBody AnalyzeRequest body) {
        List<String> intents = clean(body.intents());
        DashboardService.requireValidIntents(intents);
        if (body.current() != null) {
            if (intents.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "請輸入想怎麼調整");
            DashboardService.requireValidCards(body.current());
        }
        DatasourceService.Loaded loaded = datasources.load(userId, body.datasourceId());
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size(), intents, body.current(),
            body.fields() == null ? Set.of() : body.fields().stream().filter(Objects::nonNull).collect(Collectors.toSet()),
            MergeEngine.comparison(loaded.datasource()), loaded.datasource().ratios());
    }

    /** spec 4：每句去掉頭尾空白（含全形空白），空白的句子直接丟掉。 */
    static List<String> clean(List<String> intents) {
        if (intents == null) return List.of();
        return intents.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/highlight/{dashboardId}")
    public List<Highlight> highlight(@RequestHeader("X-User-Id") String userId, @PathVariable UUID dashboardId) {
        DashboardConfig c = dashboards.get(userId, dashboardId);
        List<ChartData> data = dashboardData.load(userId, c);
        // 每個來源各自的敏感欄位（多來源 spec 第 7 節），加上分子或分母敏感的比率（B3 spec 5.4）
        Map<UUID, Set<String>> sensitive = datasources.list(userId).stream().collect(Collectors.toMap(Datasource::id,
            ds -> Stream.concat(ds.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name),
                ds.ratios().stream().filter(Ratio::sensitive).map(Ratio::name)).collect(Collectors.toSet())));

        // 卡片只要用到它自己來源裡任何被標記敏感的欄位就整張跳過，不送進異常檢查；
        // 清單裡找不到的來源查不到敏感設定，也不送
        List<ChartSpec> eligibleSpecs = new ArrayList<>();
        List<ChartData> eligibleData = new ArrayList<>();
        List<ChartSpec> layout = c.layout();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec spec = layout.get(i);
            Set<String> fields = sensitive.get(spec.datasourceId());
            if (fields != null && UniversalTransformer.referencedFields(spec.mapping()).stream().noneMatch(fields::contains)) {
                eligibleSpecs.add(spec);
                eligibleData.add(data.get(i));
            }
        }

        List<Highlight> result = highlights.check(dashboardId, eligibleData.hashCode(), LocalDate.now(clock),
            eligibleSpecs, eligibleData);
        return result == null ? List.of() : result;
    }
}
