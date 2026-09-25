package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

/** intents：需求紀錄（選填），依序是使用者說過的每一句。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout,
                              List<String> intents) {
    /** 沒有需求紀錄的設定：舊儀表板，或沒填需求的分析結果。 */
    public DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout) {
        this(id, name, datasourceId, createdBy, layout, null);
    }
}
