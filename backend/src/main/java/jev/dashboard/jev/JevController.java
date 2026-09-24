package jev.dashboard.jev;

import java.util.UUID;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.DatasourceService;
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

    public JevController(DatasourceService datasources, AnalyzeService analyzer) {
        this.datasources = datasources;
        this.analyzer = analyzer;
    }

    @PostMapping("/analyze")
    public DashboardConfig analyze(@RequestHeader("X-User-Id") String userId, @RequestBody AnalyzeRequest body) {
        DatasourceService.Loaded loaded = datasources.load(userId, body.datasourceId());
        return analyzer.analyze(loaded.datasource().name() + " 總覽", loaded.datasource().id(),
            loaded.datasource().schema(), loaded.rows().size());
    }
}
