package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField) {
    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }
}
