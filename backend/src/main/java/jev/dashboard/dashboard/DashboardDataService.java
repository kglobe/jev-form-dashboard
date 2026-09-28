package jev.dashboard.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.SchemaInference;
import jev.dashboard.jev.ConfigAssembler;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);

    private final DatasourceService datasources;

    /** 一個來源載入一次的結果。載入失敗時 rows 是 null；name 是查得到的來源名稱，查不到是 null。 */
    private record Source(String name, List<Map<String, Object>> rows, Set<String> available, Map<String, String> labels) {
        static Source failed(String name) {
            return new Source(name, null, null, null);
        }
    }

    public DashboardDataService(DatasourceService datasources) {
        this.datasources = datasources;
    }

    /** 原始欄位名稱的資料；異常檢查用這個，送給 JEV 的是原始名稱。 */
    public List<ChartData> load(String userId, DashboardConfig c) {
        return build(userId, c, false);
    }

    /** 給畫面用：維度名稱和自動產生的標題換成欄位的顯示名稱。 */
    public List<ChartData> display(String userId, DashboardConfig c) {
        return build(userId, c, true);
    }

    /** 回傳的順序跟 layout 一樣，每一筆都帶來源名稱。 */
    private List<ChartData> build(String userId, DashboardConfig c, boolean labelled) {
        Map<UUID, Source> sources = sources(userId, c.layout());
        List<ChartData> out = new ArrayList<>();
        for (ChartSpec s : c.layout()) {
            Source src = sources.get(s.datasourceId());
            if (src.rows() == null) {
                out.add(ChartData.sourceFailed(s.chartId(), s.type(), src.name()));
                continue;
            }
            ChartData d = UniversalTransformer.transform(s, src.rows(), src.available());
            out.add((labelled ? relabelled(s, d, src.labels()) : d).withSource(src.name()));
        }
        return out;
    }

    /**
     * 每個來源只載入一次。有來源載入失敗（外部 API 掛掉、超過筆數上限等）時，只有用到它的卡片是 SOURCE_FAILED；
     * 全部來源都失敗就丟出第一個錯誤，單一來源的儀表板和預覽跟以前一樣整份報錯。
     */
    private Map<UUID, Source> sources(String userId, List<ChartSpec> layout) {
        Map<UUID, Source> out = new LinkedHashMap<>();
        ApiException first = null;
        for (ChartSpec s : layout) {
            UUID id = s.datasourceId();
            if (out.containsKey(id)) continue;
            try {
                out.put(id, loaded(datasources.load(userId, id)));
            } catch (ApiException e) {
                if (first == null) first = e;
                log.warn("資料來源 {} 載入失敗：{}", id, e.getMessage());
                out.put(id, Source.failed(nameOf(userId, id)));
            }
        }
        if (first != null && out.values().stream().allMatch(src -> src.rows() == null)) throw first;
        return out;
    }

    private static Source loaded(DatasourceService.Loaded loaded) {
        List<FieldSchema> schema = loaded.datasource().schema();
        // 資料源改版時欄位會從資料列消失；只要有任何一列還有這個 key 就算欄位還在（上游常省略 null 欄位）
        Set<String> available = loaded.rows().isEmpty()
            ? schema.stream().map(FieldSchema::name).collect(Collectors.toSet())
            : SchemaInference.fieldNames(loaded.rows());
        Map<String, String> labels = schema.stream().filter(f -> f.label() != null)
            .collect(Collectors.toMap(FieldSchema::name, FieldSchema::label));
        return new Source(loaded.datasource().name(), loaded.rows(), available, labels);
    }

    /** 載入失敗時才查名稱給卡片顯示；來源本身找不到就沒有名稱。 */
    private String nameOf(String userId, UUID id) {
        try {
            return datasources.get(userId, id).name();
        } catch (ApiException e) {
            return null;
        }
    }

    private static ChartData relabelled(ChartSpec s, ChartData d, Map<String, String> labels) {
        if (labels.isEmpty()) return d;
        List<String> dims = d.dimensions() == null ? null : d.dimensions().stream().map(n -> labels.getOrDefault(n, n)).toList();
        // 只換自動產生的標題；明細表之類另外取名的標題照舊
        String title = s.mapping() != null && s.title() != null && s.title().equals(ConfigAssembler.title(s.type(), s.mapping()))
            ? ConfigAssembler.title(s.type(), relabel(s.mapping(), labels)) : null;
        return d.withLabels(dims, title);
    }

    private static Mapping relabel(Mapping m, Map<String, String> labels) {
        return new Mapping(labels.getOrDefault(m.x(), m.x()), labels.getOrDefault(m.y(), m.y()),
            labels.getOrDefault(m.series(), m.series()), labels.getOrDefault(m.value(), m.value()),
            m.measures() == null ? null : m.measures().stream().map(n -> labels.getOrDefault(n, n)).toList(),
            m.columns(), m.aggregate(), m.timeGrain());
    }
}
