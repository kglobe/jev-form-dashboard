package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * truncated 只在明細表被截斷時是 true，其他時候是 null、JSON 不輸出。
 * title 是套上欄位顯示名稱後的卡片標題；null 時前端照用 spec 的標題。
 * source 是這張圖的來源名稱，多來源的儀表板用它標在卡片上。
 * percent 只在數值欄是比率時是 true（B3 spec 5.1），前端把 Y 軸和 tooltip 顯示成百分比；其他時候是 null、JSON 不輸出。
 * ratioPeriod 只在比率有排除期間時出現：只算 from～to 這幾個月（分子、分母都有資料的月份），from 比 to 晚代表沒有重疊。
 * filterSkipped 是對這張圖沒生效的篩選（篩選器 spec 4），分類寫欄位名稱、期間寫「期間」；全部生效或沒有篩選時是 null、JSON 不輸出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                        String error, String missingField, Boolean truncated, String title, String source,
                        Boolean percent, RatioPeriod ratioPeriod, List<String> filterSkipped) {
    /** 月份寫成 2026-07。 */
    public record RatioPeriod(String from, String to) {}

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title, String source,
                     Boolean percent, RatioPeriod ratioPeriod) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, source, percent, ratioPeriod, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title, String source,
                     Boolean percent) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, source, percent, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title, String source) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, source, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated, String title) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, title, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField, Boolean truncated) {
        this(chartId, type, dimensions, rows, error, missingField, truncated, null, null);
    }

    public ChartData(String chartId, String type, List<String> dimensions, List<List<Object>> rows,
                     String error, String missingField) {
        this(chartId, type, dimensions, rows, error, missingField, null, null, null);
    }

    public ChartData withLabels(List<String> dimensions, String title) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, source, percent, ratioPeriod,
            filterSkipped);
    }

    public ChartData withSource(String value) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, value, percent, ratioPeriod,
            filterSkipped);
    }

    public ChartData withFilterSkipped(List<String> value) {
        return new ChartData(chartId, type, dimensions, rows, error, missingField, truncated, title, source, percent, ratioPeriod,
            value);
    }

    public static ChartData missing(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "FIELD_MISSING", field);
    }

    /** 有 timeGrain 的 x 欄位裡有不是日期的值（手動編的卡片才會這樣）；missingField 放那個欄位的名稱。 */
    public static ChartData notADate(String chartId, String type, String field) {
        return new ChartData(chartId, type, null, null, "NOT_A_DATE", field);
    }

    /** 多來源 spec 第 7 節：這張圖的來源載入失敗；source 是查得到的來源名稱，查不到是 null。 */
    public static ChartData sourceFailed(String chartId, String type, String source) {
        return new ChartData(chartId, type, null, null, "SOURCE_FAILED", null, null, null, source);
    }

    /** 刪除來源 spec 4.1：這張圖的來源已經刪掉。 */
    public static ChartData sourceDeleted(String chartId, String type) {
        return new ChartData(chartId, type, null, null, "SOURCE_DELETED", null, null, null, null);
    }
}
