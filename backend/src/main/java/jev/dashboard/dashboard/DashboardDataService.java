package jev.dashboard.dashboard;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.SchemaInference;
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
        // 資料源改版時欄位會從資料列消失；只要有任何一列還有這個 key 就算欄位還在（上游常省略 null 欄位）
        Set<String> available = loaded.rows().isEmpty()
            ? loaded.datasource().schema().stream().map(FieldSchema::name).collect(Collectors.toSet())
            : SchemaInference.fieldNames(loaded.rows());
        return c.layout().stream().map(s -> UniversalTransformer.transform(s, loaded.rows(), available)).toList();
    }
}
