package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.datasource.DatasourceService;
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

    public static void requireComplete(DashboardConfig c) {
        if (c == null || c.name() == null || c.datasourceId() == null || c.layout() == null) {
            throw new ApiException(BAD_REQUEST, "name、datasourceId、layout 都必填");
        }
    }

    private static ApiException notFound() {
        return new ApiException(NOT_FOUND, "找不到儀表板");
    }
}
