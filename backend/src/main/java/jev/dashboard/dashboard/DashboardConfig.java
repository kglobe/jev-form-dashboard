package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

/**
 * 來源記在每張圖上（ChartSpec.datasourceId），儀表板本身不綁來源。
 * intents：一次分析的需求紀錄（選填），分析結果、建立和「加到既有儀表板」時帶；已存的儀表板不回傳。
 * basis：已存儀表板的依據，每組是一次分析的來源和需求紀錄，照加入順序排；沒有時是 null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout,
                              List<String> intents, List<Basis> basis) {
    public record Basis(UUID datasourceId, List<String> intents) {}

    /** 沒有需求紀錄、也沒有依據的設定。 */
    public DashboardConfig(UUID id, String name, String createdBy, List<ChartSpec> layout) {
        this(id, name, createdBy, layout, null, null);
    }
}
