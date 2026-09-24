package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** truncated 只在明細表被截斷時是 true，其他時候是 null、JSON 不輸出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField, Boolean truncated) {
    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField) {
        this(chartId, type, dimensions, rows, error, missingField, null);
    }

    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }
}
