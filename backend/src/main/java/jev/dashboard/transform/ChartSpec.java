package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/** datasourceId：這張圖讀哪個資料來源。ConfigAssembler 產生的圖還沒有，由 AnalyzeService 補上。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                        Map<String, Object> options, UUID datasourceId) {
    public ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                     Map<String, Object> options) {
        this(chartId, type, title, gridPos, mapping, options, null);
    }

    public ChartSpec withDatasourceId(UUID value) {
        return new ChartSpec(chartId, type, title, gridPos, mapping, options, value);
    }

    public record GridPos(int x, int y, int w, int h) {}
}
