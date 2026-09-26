package jev.dashboard.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.SchemaInference;
import jev.dashboard.jev.ConfigAssembler;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private final DatasourceService datasources;

    public DashboardDataService(DatasourceService datasources) {
        this.datasources = datasources;
    }

    /** 原始欄位名稱的資料；異常檢查用這個，送給 JEV 的是原始名稱。 */
    public List<ChartData> load(String userId, DashboardConfig c) {
        return transform(datasources.load(userId, c.datasourceId()), c);
    }

    /** 給畫面用：維度名稱和自動產生的標題換成欄位的顯示名稱。 */
    public List<ChartData> display(String userId, DashboardConfig c) {
        DatasourceService.Loaded loaded = datasources.load(userId, c.datasourceId());
        Map<String, String> labels = loaded.datasource().schema().stream().filter(f -> f.label() != null)
            .collect(Collectors.toMap(FieldSchema::name, FieldSchema::label));
        List<ChartData> data = transform(loaded, c);
        if (labels.isEmpty()) return data;
        List<ChartData> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            ChartSpec s = c.layout().get(i);
            ChartData d = data.get(i);
            List<String> dims = d.dimensions() == null ? null : d.dimensions().stream().map(n -> labels.getOrDefault(n, n)).toList();
            // 只換自動產生的標題；明細表之類另外取名的標題照舊
            String title = s.mapping() != null && s.title() != null && s.title().equals(ConfigAssembler.title(s.type(), s.mapping()))
                ? ConfigAssembler.title(s.type(), relabel(s.mapping(), labels)) : null;
            out.add(d.withLabels(dims, title));
        }
        return out;
    }

    private List<ChartData> transform(DatasourceService.Loaded loaded, DashboardConfig c) {
        // 資料源改版時欄位會從資料列消失；只要有任何一列還有這個 key 就算欄位還在（上游常省略 null 欄位）
        Set<String> available = loaded.rows().isEmpty()
            ? loaded.datasource().schema().stream().map(FieldSchema::name).collect(Collectors.toSet())
            : SchemaInference.fieldNames(loaded.rows());
        return c.layout().stream().map(s -> UniversalTransformer.transform(s, loaded.rows(), available)).toList();
    }

    private static Mapping relabel(Mapping m, Map<String, String> labels) {
        return new Mapping(labels.getOrDefault(m.x(), m.x()), labels.getOrDefault(m.y(), m.y()),
            labels.getOrDefault(m.series(), m.series()), labels.getOrDefault(m.value(), m.value()),
            m.measures(), m.columns(), m.aggregate(), m.timeGrain());
    }
}
