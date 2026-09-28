package jev.dashboard.transform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jev.dashboard.transform.ChartSpec.GridPos;
import org.junit.jupiter.api.Test;

class UniversalTransformerTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static Map<String, Object> row(String d, Double amount, String region, String product, Double qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_date", d);
        m.put("amount", amount);
        m.put("region", region);
        m.put("product", product);
        m.put("qty", qty);
        return m;
    }

    static final List<Map<String, Object>> ROWS = List.of(
        row("2024-01-05", 100.0, "北", "A", 1.0),
        row("2024-01-20", 50.0, "南", "A", 2.0),
        row("2024-02-03", 200.0, "北", "B", 3.0),
        row("2024-04-10", null, "南", "B", 4.0));
    static final Set<String> FIELDS = Set.of("order_date", "amount", "region", "product", "qty");

    static ChartData run(String type, Mapping m) {
        return UniversalTransformer.transform(
            new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4), m, Map.of()), ROWS, FIELDS);
    }

    static Mapping xv(String x, String value, String aggregate, String grain) {
        return new Mapping(x, null, null, value, null, null, aggregate, grain);
    }

    @Test
    void lineBucketsByMonthAndSkipsNullValues() {
        ChartData d = run("line", xv("order_date", "amount", "sum", "month"));
        assertThat(d.dimensions()).containsExactly("order_date", "amount");
        assertThat(d.rows()).isEqualTo(List.of(List.of("2024-01", 150.0), List.of("2024-02", 200.0)));
    }

    @Test
    void lineByDay() {
        ChartData d = run("line", xv("order_date", "amount", "sum", "day"));
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("2024-01-05", 100.0), List.of("2024-01-20", 50.0), List.of("2024-02-03", 200.0)));
    }

    @Test
    void barDefaultsToSumAndSortsByValueDescending() {
        ChartData d = run("bar", xv("region", "amount", null, null));
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 300.0), List.of("南", 50.0)));
    }

    @Test
    void barAverage() {
        ChartData d = run("bar", xv("region", "amount", "avg", null));
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 150.0), List.of("南", 50.0)));
    }

    @Test
    void pieCountsRowsWithoutValueField() {
        ChartData d = run("pie", xv("product", null, "count", null));
        assertThat(d.dimensions()).containsExactly("product", "count");
        assertThat(d.rows()).isEqualTo(List.of(List.of("A", 2.0), List.of("B", 2.0)));
    }

    @Test
    void scatterDefaultsToNoAggregationAndSkipsNulls() {
        ChartData d = run("scatter", new Mapping("amount", "qty", null, null, null, null, null, null));
        assertThat(d.rows()).isEqualTo(List.of(List.of(100.0, 1.0), List.of(50.0, 2.0), List.of(200.0, 3.0)));
    }

    @Test
    void tableProjectsColumns() {
        ChartData d = run("table", new Mapping(null, null, null, null, null, List.of("order_date", "region"), null, null));
        assertThat(d.dimensions()).containsExactly("order_date", "region");
        assertThat(d.rows()).hasSize(4);
        assertThat(d.rows().get(0)).isEqualTo(List.of("2024-01-05", "北"));
    }

    static ChartData table(int rowCount) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) rows.add(row("2024-01-05", (double) i, "北", "A", 1.0));
        return UniversalTransformer.transform(new ChartSpec("c1", "table", "明細", new GridPos(0, 0, 12, 5),
            new Mapping(null, null, null, null, null, List.of("amount"), null, null), Map.of()), rows, FIELDS);
    }

    @Test
    void tableOverTheRowLimitIsCutAndFlaggedTruncated() throws Exception {
        ChartData d = table(UniversalTransformer.TABLE_ROW_LIMIT + 1);
        assertThat(d.rows()).hasSize(UniversalTransformer.TABLE_ROW_LIMIT);
        assertThat(JSON.writeValueAsString(d)).contains("\"truncated\":true");
    }

    @Test
    void tableWithinTheRowLimitHasNoTruncatedField() throws Exception {
        ChartData d = table(UniversalTransformer.TABLE_ROW_LIMIT);
        assertThat(d.rows()).hasSize(UniversalTransformer.TABLE_ROW_LIMIT);
        assertThat(JSON.writeValueAsString(d)).doesNotContain("truncated");
        assertThat(JSON.writeValueAsString(run("bar", xv("region", "amount", null, null)))).doesNotContain("truncated");
    }

    @Test
    void radarAggregatesEachMeasurePerSeries() {
        ChartData d = run("radar", new Mapping(null, null, "region", null, List.of("amount", "qty"), null, null, null));
        assertThat(d.dimensions()).containsExactly("region", "amount", "qty");
        assertThat(d.rows()).isEqualTo(List.of(List.of("北", 300.0, 4.0), List.of("南", 50.0, 6.0)));
    }

    @Test
    void heatmapGroupsByTwoKeysInFirstSeenOrder() {
        ChartData d = run("heatmap", new Mapping("region", "product", null, "amount", null, null, null, null));
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("北", "A", 100.0), List.of("南", "A", 50.0), List.of("北", "B", 200.0)));
    }

    @Test
    void stackedBarByQuarterSortsByTimeThenSeries() {
        ChartData d = run("stacked_bar", new Mapping("order_date", null, "region", "amount", null, null, null, "quarter"));
        assertThat(d.dimensions()).containsExactly("order_date", "region", "amount");
        assertThat(d.rows()).isEqualTo(List.of(List.of("2024-Q1", "北", 300.0), List.of("2024-Q1", "南", 50.0)));
    }

    @Test
    void missingFieldIsReportedInsteadOfThrowing() {
        ChartData d = run("bar", xv("region", "profit", null, null));
        assertThat(d.error()).isEqualTo("FIELD_MISSING");
        assertThat(d.missingField()).isEqualTo("profit");
        assertThat(d.rows()).isNull();
    }

    /** 對照來源的資料列：每一列只有一邊的數值（B2 spec 5.3）；最後一列沒有日期。 */
    static final List<Map<String, Object>> COMPARE = List.of(
        Map.of("date", "2026-01-05", "region", "北區", "sales", 100.0),
        Map.of("date", "2026-01-20", "region", "南區", "sales", 250.0),
        Map.of("date", "2026-02-03", "region", "北區", "sales", 200.0),
        Map.of("date", "2026-02-10", "region", "北區", "refund", 20.0),
        Map.of("date", "2026-02-11", "region", "東區", "refund", 5.0),
        Map.of("region", "北區", "refund", 7.0));
    static final Set<String> COMPARE_FIELDS = Set.of("date", "region", "sales", "refund");

    static ChartData compare(String type, String x, String aggregate, String grain) {
        return UniversalTransformer.transform(new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4),
            new Mapping(x, null, null, null, List.of("sales", "refund"), null, aggregate, grain), Map.of()),
            COMPARE, COMPARE_FIELDS);
    }

    @Test
    void twoMeasuresAreSummedSeparatelyAndAPeriodWithoutOneSideIsNull() {
        ChartData d = compare("line", "date", "sum", "month");
        assertThat(d.dimensions()).containsExactly("date", "sales", "refund");
        assertThat(d.rows()).isEqualTo(List.of(Arrays.asList("2026-01", 350.0, null), List.of("2026-02", 200.0, 25.0)));
    }

    @Test
    void twoMeasuresByCategorySortByTheFirstMeasureWithNullsLast() {
        ChartData d = compare("bar", "region", "sum", null);
        assertThat(d.rows()).isEqualTo(List.of(
            List.of("北區", 300.0, 27.0), Arrays.asList("南區", 250.0, null), Arrays.asList("東區", null, 5.0)));
    }

    @Test
    void twoMeasuresAverageAndCountLookOnlyAtEachMeasuresOwnValues() {
        assertThat(compare("bar", "region", "avg", null).rows()).isEqualTo(List.of(
            Arrays.asList("南區", 250.0, null), List.of("北區", 150.0, 13.5), Arrays.asList("東區", null, 5.0)));
        assertThat(compare("bar", "region", "count", null).rows()).isEqualTo(List.of(
            List.of("北區", 2.0, 2.0), Arrays.asList("南區", 1.0, null), Arrays.asList("東區", null, 1.0)));
    }

    @Test
    void countOnlyCountsRowsWhereTheValueIsPresent() {
        // 南區 2024-04-10 那列的 amount 是空的，不算
        assertThat(run("bar", xv("region", "amount", "count", null)).rows())
            .isEqualTo(List.of(List.of("北", 2.0), List.of("南", 1.0)));
        // 沒有 value 時照舊算列數
        assertThat(run("bar", xv("region", null, "count", null)).rows())
            .isEqualTo(List.of(List.of("北", 2.0), List.of("南", 2.0)));
        ChartData radar = run("radar", new Mapping(null, null, "region", null, List.of("amount", "qty"), null, "count", null));
        assertThat(radar.rows()).isEqualTo(List.of(List.of("北", 2.0, 2.0), List.of("南", 1.0, 2.0)));
    }
}
