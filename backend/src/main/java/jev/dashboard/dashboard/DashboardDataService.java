package jev.dashboard.dashboard;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private final DatasourceService datasources;

    public DashboardDataService(DatasourceService datasources) {
        this.datasources = datasources;
    }

    public List<ChartData> load(String userId, DashboardConfig c) {
        DatasourceService.Loaded loaded = datasources.load(userId, c.datasourceId());
        // 資料源改版時欄位會從資料列消失，用第一列的 key 判斷欄位還在不在
        Set<String> available = loaded.rows().isEmpty()
            ? loaded.datasource().schema().stream().map(FieldSchema::name).collect(Collectors.toSet())
            : loaded.rows().get(0).keySet();
        return c.layout().stream().map(s -> UniversalTransformer.transform(s, loaded.rows(), available)).toList();
    }
}
