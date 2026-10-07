package jev.dashboard.transform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jev.dashboard.datasource.Ratio;
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

    static ChartData runWith(Map<String, Object> extra, String type, Mapping m) {
        List<Map<String, Object>> rows = new ArrayList<>(ROWS);
        rows.add(extra);
        return UniversalTransformer.transform(spec(type, m), rows, FIELDS);
    }

    @Test
    void aBlankCategoryIsItsOwnGroupInsteadOfBeingDropped() {
        Map<String, Object> noRegion = row("2024-03-01", 150.0, null, null, 5.0);
        String blank = UniversalTransformer.BLANK;
        assertThat(blank).isEqualTo("（空白）");
        // 依分類：空值那列歸到「（空白）」，合計照樣是 500
        assertThat(runWith(noRegion, "bar", xv("region", "amount", null, null)).rows()).isEqualTo(List.of(
            List.of("北", 300.0), List.of(blank, 150.0), List.of("南", 50.0)));
        // 堆疊長條的 series、熱力圖的 y
        assertThat(runWith(noRegion, "stacked_bar", new Mapping("order_date", null, "region", "amount", null, null, null, "quarter"))
            .rows()).isEqualTo(List.of(
                List.of("2024-Q1", "北", 300.0), List.of("2024-Q1", "南", 50.0), List.of("2024-Q1", blank, 150.0)));
        assertThat(runWith(noRegion, "heatmap", new Mapping("region", "product", null, "amount", null, null, null, null))
            .rows()).isEqualTo(List.of(
                List.of("北", "A", 100.0), List.of("南", "A", 50.0), List.of("北", "B", 200.0), List.of(blank, blank, 150.0)));
        // 兩個數值的長條圖（沒有 timeGrain）、雷達圖的 series
        assertThat(runWith(noRegion, "bar", new Mapping("region", null, null, null, List.of("amount", "qty"), null, "sum", null))
            .rows()).isEqualTo(List.of(List.of("北", 300.0, 4.0), List.of(blank, 150.0, 5.0), List.of("南", 50.0, 6.0)));
        assertThat(runWith(noRegion, "radar", new Mapping(null, null, "region", null, List.of("amount", "qty"), null, null, null))
            .rows()).isEqualTo(List.of(List.of("北", 300.0, 4.0), List.of("南", 50.0, 6.0), List.of(blank, 150.0, 5.0)));
    }

    @Test
    void aBlankDateUnderATimeGrainIsStillSkipped() {
        // 期別照字串排，「（空白）」會排在最後、被當成最新一期，所以分期的日期是空值時照舊不算
        Map<String, Object> noDate = row(null, 999.0, "北", "A", 1.0);
        assertThat(runWith(noDate, "line", xv("order_date", "amount", "sum", "month")).rows())
            .isEqualTo(List.of(List.of("2024-01", 150.0), List.of("2024-02", 200.0)));
        assertThat(runWith(noDate, "stacked_bar", new Mapping("order_date", null, "region", "amount", null, null, null, "quarter"))
            .rows()).isEqualTo(List.of(List.of("2024-Q1", "北", 300.0), List.of("2024-Q1", "南", 50.0)));
        assertThat(runWith(noDate, "line", new Mapping("order_date", null, null, null, List.of("amount", "qty"), null, "sum", "month"))
            .rows()).isEqualTo(List.of(
                List.of("2024-01", 150.0, 3.0), List.of("2024-02", 200.0, 3.0), Arrays.asList("2024-04", null, 4.0)));
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

    /** B3：對照來源的資料列，銷售、退貨各自一列。4 月那列兩邊都沒有值，最後一列沒有日期。 */
    static final List<Map<String, Object>> RATIO_ROWS = List.of(
        Map.of("date", "2026-01-05", "region", "北區", "sales", 100.0),
        Map.of("date", "2026-01-20", "region", "南區", "sales", 300.0),
        Map.of("date", "2026-02-03", "region", "北區", "sales", 400.0),
        Map.of("date", "2026-02-10", "region", "北區", "refund", 10.0),
        Map.of("date", "2026-02-11", "region", "東區", "refund", 5.0),
        Map.of("date", "2026-03-01", "region", "南區", "sales", 0.0),
        Map.of("date", "2026-03-02", "region", "南區", "refund", 3.0),
        Map.of("date", "2026-04-01", "region", "西區"),
        Map.of("region", "北區", "refund", 7.0));
    static final Set<String> RATIO_FIELDS = Set.of("date", "region", "sales", "refund");
    static final Ratio RETURN_RATE = new Ratio("退貨率", "refund", "sales");

    static ChartSpec spec(String type, Mapping m) {
        return new ChartSpec("c1", type, "t", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    static ChartData ratioChart(String type, Mapping m) {
        return UniversalTransformer.transform(spec(type, m), RATIO_ROWS, RATIO_FIELDS, List.of(RETURN_RATE));
    }

    @Test
    void aRatioSumsTheNumeratorAndTheDenominatorOfEachGroupBeforeDividing() {
        List<Map<String, Object>> rows = List.of(
            Map.of("region", "北區", "sales", 100.0, "refund", 10.0),
            Map.of("region", "北區", "sales", 300.0, "refund", 10.0));

        ChartData d = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), rows, RATIO_FIELDS,
            List.of(RETURN_RATE));

        // 20 ÷ 400；逐列相除再加總會是 0.1 + 0.033…，再平均是 0.066…
        assertThat(d.dimensions()).containsExactly("region", "退貨率");
        assertThat(d.rows()).isEqualTo(List.of(List.of("北區", 0.05)));
    }

    @Test
    void aGroupWithoutANumeratorOrADenominatorOrWithAZeroDenominatorIsNullButKeepsItsPlace() {
        ChartData d = ratioChart("line", xv("date", "退貨率", "sum", "month"));

        // 1 月沒有退貨、3 月銷售加總是 0，這兩期是 null；4 月兩邊都沒有值，不輸出；沒有日期的那列跳過
        assertThat(d.rows()).isEqualTo(List.of(
            Arrays.asList("2026-01", null), List.of("2026-02", 0.0375), Arrays.asList("2026-03", null)));
    }

    @Test
    void byCategoryARatioSortsLargestFirstWithNullsLastWhateverTheAggregateSays() {
        // 北區 17 ÷ 500、南區 3 ÷ 300、東區沒有銷售；西區兩邊都沒有值，不輸出
        List<List<Object>> expected = List.of(List.of("北區", 0.034), List.of("南區", 0.01), Arrays.asList("東區", null));
        for (String agg : Arrays.asList("sum", "avg", "count", "none", null)) {
            assertThat(ratioChart("bar", xv("region", "退貨率", agg, null)).rows()).as(String.valueOf(agg)).isEqualTo(expected);
        }
        // 堆疊長條走同一段分組：先照期別、再照分類排。有 timeGrain 就照 x 對齊：退貨 2～3 月、銷售 1～3 月，
        // 1 月的銷售不算，北區 10 ÷ 400，南區只剩 3 月銷售 0
        ChartData stacked = ratioChart("stacked_bar", new Mapping("date", null, "region", "退貨率", null, null, "sum", "quarter"));
        assertThat(stacked.dimensions()).containsExactly("date", "region", "退貨率");
        assertThat(stacked.rows()).isEqualTo(List.of(
            List.of("2026-Q1", "北區", 0.025), Arrays.asList("2026-Q1", "南區", null), Arrays.asList("2026-Q1", "東區", null)));
    }

    @Test
    void onlyRatioChartsArePercent() throws Exception {
        assertThat(ratioChart("line", xv("date", "退貨率", "sum", "month")).percent()).isTrue();
        ChartData plain = run("bar", xv("region", "amount", "sum", null));
        assertThat(plain.percent()).isNull();
        assertThat(JSON.writeValueAsString(plain)).doesNotContain("percent");
    }

    @Test
    void aRatioIsMissingWhenItsFieldsAreGoneOrWhenItSitsOutsideValue() {
        ChartData noRefund = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), RATIO_ROWS,
            Set.of("date", "region", "sales"), List.of(RETURN_RATE));
        assertThat(noRefund.error()).isEqualTo("FIELD_MISSING");
        assertThat(noRefund.missingField()).isEqualTo("refund");

        assertThat(ratioChart("bar", xv("退貨率", "sales", "sum", null)).missingField()).isEqualTo("退貨率");
        assertThat(ratioChart("line", new Mapping("date", null, null, null, List.of("sales", "退貨率"), null, "sum", "month"))
            .missingField()).isEqualTo("退貨率");
        // 比率刪掉之後，用到它的卡片是 FIELD_MISSING
        ChartData deleted = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), RATIO_ROWS,
            RATIO_FIELDS, List.of());
        assertThat(deleted.missingField()).isEqualTo("退貨率");
    }

    @Test
    void aTimeGrainOnAFieldThatIsNotADateIsACardErrorInsteadOfACrash() {
        // 手動編的卡片把 timeGrain 配到 region：以前 LocalDate.parse 丟例外，整份 /data 回 500
        for (ChartData d : List.of(
                run("line", xv("region", "amount", "sum", "month")),
                run("bar", new Mapping("region", null, null, null, List.of("amount", "qty"), null, "sum", "month")),
                ratioChart("bar", xv("region", "退貨率", "sum", "quarter")))) {
            assertThat(d.error()).isEqualTo("NOT_A_DATE");
            assertThat(d.missingField()).isEqualTo("region");
            assertThat(d.rows()).isNull();
        }
    }

    /** 對照來源的樣子：銷售 1～9 月，退貨 8～9 月；return_date 只有退貨列有，最後一列退貨沒有日期。 */
    static final List<Map<String, Object>> MISALIGNED = List.of(
        Map.of("date", "2026-01-08", "region", "北區", "sales", 100.0),
        Map.of("date", "2026-07-07", "region", "北區", "sales", 200.0),
        Map.of("date", "2026-08-04", "region", "北區", "sales", 400.0),
        Map.of("date", "2026-09-16", "region", "南區", "sales", 400.0),
        Map.of("date", "2026-08-06", "return_date", "2026-08-06", "region", "北區", "refund", 10.0),
        Map.of("date", "2026-09-19", "return_date", "2026-09-19", "region", "南區", "refund", 6.0),
        Map.of("region", "北區", "refund", 7.0));
    static final Set<String> MISALIGNED_FIELDS = Set.of("date", "return_date", "region", "sales", "refund");

    static ChartData aligned(String type, Mapping m, List<Map<String, Object>> rows) {
        return UniversalTransformer.transform(spec(type, m), rows, MISALIGNED_FIELDS, List.of(RETURN_RATE),
            List.of("return_date", "date"));
    }

    @Test
    void byCategoryARatioOnlyCountsTheMonthsWhereBothTheNumeratorAndTheDenominatorHaveData() throws Exception {
        ChartData d = aligned("bar", xv("region", "退貨率", "sum", null), MISALIGNED);

        // return_date 只有退貨有，改用 date；重疊 8～9 月：北區 10 ÷ 400，南區 6 ÷ 400；
        // 1、7 月的銷售和沒有日期的退貨都不算（沒對齊時北區是 17 ÷ 700）
        assertThat(d.rows()).isEqualTo(List.of(List.of("北區", 0.025), List.of("南區", 0.015)));
        assertThat(d.ratioPeriod()).isEqualTo(new ChartData.RatioPeriod("2026-08", "2026-09"));
        assertThat(JSON.writeValueAsString(d)).contains("\"ratioPeriod\":{\"from\":\"2026-08\",\"to\":\"2026-09\"}");
    }

    @Test
    void overTimeARatioAlignsOnTheXFieldAndMonthsOutsideTheOverlapKeepTheirPlace() {
        // 按月跟沒對齊時一樣：1、7 月沒有退貨，留空
        assertThat(aligned("line", xv("date", "退貨率", "sum", "month"), MISALIGNED).rows()).isEqualTo(List.of(
            Arrays.asList("2026-01", null), Arrays.asList("2026-07", null),
            List.of("2026-08", 0.025), List.of("2026-09", 0.015)));
        // 按季：Q3 只算 8、9 月，7 月的銷售不稀釋（沒對齊時是 16 ÷ 1000）
        ChartData quarter = aligned("bar", xv("date", "退貨率", "sum", "quarter"), MISALIGNED);
        assertThat(quarter.rows()).isEqualTo(List.of(Arrays.asList("2026-Q1", null), List.of("2026-Q3", 0.02)));
        assertThat(quarter.ratioPeriod()).isEqualTo(new ChartData.RatioPeriod("2026-08", "2026-09"));
    }

    @Test
    void aRatioIsNotAlignedWhenBothSidesCoverTheSameMonthsOrThereIsNoDateField() throws Exception {
        List<Map<String, Object>> same = List.of(
            Map.of("date", "2026-08-01", "region", "北區", "sales", 100.0),
            Map.of("date", "2026-09-30", "region", "北區", "sales", 100.0),
            Map.of("date", "2026-08-20", "region", "北區", "refund", 4.0),
            Map.of("date", "2026-09-02", "region", "北區", "refund", 6.0),
            Map.of("region", "北區", "refund", 10.0));
        ChartData d = aligned("bar", xv("region", "退貨率", "sum", null), same);

        // 兩邊都是 8～9 月，不用對齊，沒有日期的列照算：20 ÷ 200
        assertThat(d.rows()).isEqualTo(List.of(List.of("北區", 0.1)));
        assertThat(d.ratioPeriod()).isNull();
        assertThat(JSON.writeValueAsString(d)).doesNotContain("ratioPeriod");
        // 來源沒有日期欄位：照舊整段加總（MISALIGNED 的北區 17 ÷ 700）
        ChartData noDates = UniversalTransformer.transform(spec("bar", xv("region", "退貨率", "sum", null)), MISALIGNED,
            MISALIGNED_FIELDS, List.of(RETURN_RATE), List.of());
        assertThat(noDates.rows().get(0)).isEqualTo(List.of("北區", 17.0 / 700));
        assertThat(noDates.ratioPeriod()).isNull();
    }

    @Test
    void whenTheMonthsDoNotOverlapEveryGroupIsNull() {
        List<Map<String, Object>> apart = List.of(
            Map.of("date", "2026-01-08", "region", "北區", "sales", 100.0),
            Map.of("date", "2026-08-06", "region", "北區", "refund", 10.0));
        ChartData d = aligned("bar", xv("region", "退貨率", "sum", null), apart);

        assertThat(d.rows()).isEqualTo(List.of(Arrays.asList("北區", null)));
        assertThat(d.ratioPeriod()).isEqualTo(new ChartData.RatioPeriod("2026-08", "2026-01"));
    }
}
