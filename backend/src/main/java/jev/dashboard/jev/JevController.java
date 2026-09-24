package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardDataService;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.UniversalTransformer;
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
    public record AnalyzeRequest(UUID datasourceId) {}

    private final DatasourceService datasources;
    private final AnalyzeService analyzer;
    private final DashboardService dashboards;
    private final DashboardDataService dashboardData;
    private final HighlightService highlights;

    public JevController(DatasourceService datasources, AnalyzeService analyzer, DashboardService dashboards,
                         DashboardDataService dashboardData, HighlightService highlights) {
        this.datasources = datasources;
        this.analyzer = analyzer;
        this.dashboards = dashboards;
        this.dashboardData = dashboardData;
        this.highlights = highlights;
    }

    @PostMapping("/analyze")
    public DashboardConfig analyze(@RequestHeader("X-User-Id") String userId, @RequestBody AnalyzeRequest body) {
        DatasourceService.Loaded loaded = datasources.load(userId, body.datasourceId());
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size());
    }

    @GetMapping("/highlight/{dashboardId}")
    public List<Highlight> highlight(@RequestHeader("X-User-Id") String userId, @PathVariable UUID dashboardId) {
        DashboardConfig c = dashboards.get(userId, dashboardId);
        List<ChartData> data = dashboardData.load(userId, c);
        Set<String> sensitive = datasources.get(userId, c.datasourceId()).schema().stream()
            .filter(FieldSchema::sensitive).map(FieldSchema::name).collect(Collectors.toSet());

        // 卡片只要用到任何被標記敏感的欄位就整張跳過，不送進異常檢查
        List<ChartSpec> eligibleSpecs = new ArrayList<>();
        List<ChartData> eligibleData = new ArrayList<>();
        List<ChartSpec> layout = c.layout();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec spec = layout.get(i);
            if (UniversalTransformer.referencedFields(spec.mapping()).stream().noneMatch(sensitive::contains)) {
                eligibleSpecs.add(spec);
                eligibleData.add(data.get(i));
            }
        }

        List<Highlight> result = highlights.check(dashboardId, eligibleData.hashCode(), eligibleSpecs, eligibleData);
        return result == null ? List.of() : result;
    }
}
