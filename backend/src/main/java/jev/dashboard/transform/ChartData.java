package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * truncated 只在明細表被截斷時是 true，其他時候是 null、JSON 不輸出。
 * title 是套上欄位顯示名稱後的卡片標題；null 時前端照用 spec 的標題。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField, Boolean truncated, String title) {
    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField) {
        this(chartId, type, dimensions, rows, error, missingField, null, null);
    }

    public ChartData withLabels(List<String> dimensions, String title) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title);
    }

    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }
}
