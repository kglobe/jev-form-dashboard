package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final DashboardRepository repo;
    private final DatasourceService datasources;

    public DashboardService(DashboardRepository repo, DatasourceService datasources) {
        this.repo = repo;
        this.datasources = datasources;
    }

    public DashboardConfig create(String userId, DashboardConfig c) {
        requireComplete(c);
        datasources.get(userId, c.datasourceId());
        return get(userId, repo.insert(c, userId));
    }

    public DashboardConfig get(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(DashboardService::notFound);
    }

    public List<DashboardSummary> list(String userId) {
        return repo.list(userId);
    }

    public DashboardConfig update(String userId, UUID id, DashboardConfig c) {
        requireComplete(c);
        if (!repo.update(id, userId, c)) throw notFound();
        return get(userId, id);
    }

    public void delete(String userId, UUID id) {
        if (!repo.delete(id, userId)) throw notFound();
    }

    /** 存檔和預覽前檢查每張卡片，免得存進去的配置之後每次載入 /data 都 500。 */
    public static void requireComplete(DashboardConfig c) {
        if (c == null || c.name() == null || c.datasourceId() == null || c.layout() == null) {
            throw new ApiException(BAD_REQUEST, "name、datasourceId、layout 都必填");
        }
        if (c.name().isBlank()) throw new ApiException(BAD_REQUEST, "儀表板名稱不能空白");
        Set<String> chartIds = new HashSet<>();
        for (int i = 0; i < c.layout().size(); i++) {
            ChartSpec s = c.layout().get(i);
            if (s == null) throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表是空的");
            if (s.chartId() == null || s.chartId().isBlank()) {
                throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表缺少 chartId");
            }
            if (!chartIds.add(s.chartId())) throw new ApiException(BAD_REQUEST, "chartId 重複：" + s.chartId());
            String problem = cardProblem(s);
            if (problem != null) throw new ApiException(BAD_REQUEST, "圖表 " + s.chartId() + "：" + problem);
        }
    }

    private static String cardProblem(ChartSpec s) {
        String problem = UniversalTransformer.problem(s);
        if (problem != null) return problem;
        GridPos g = s.gridPos();
        if (g == null) return "缺少 gridPos";
        // 前端格線是 12 欄
        if (g.x() < 0 || g.y() < 0 || g.w() < 1 || g.h() < 1 || g.w() > 12 || g.x() > 12 - g.w()) {
            return "gridPos 要 x ≥ 0、y ≥ 0、w ≥ 1、h ≥ 1，而且 x + w ≤ 12";
        }
        return null;
    }

    private static ApiException notFound() {
        return new ApiException(NOT_FOUND, "找不到儀表板");
    }
}
