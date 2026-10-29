package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class HighlightSummarizerTest {
    static List<Object> r(Object... values) {
        return Arrays.asList(values);
    }

    static ChartSpec spec(String type, Mapping m) {
        return new ChartSpec("c1", type, "月銷售額", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    // 比所有測試資料的期間都晚，每一期都已經結束
    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    static final Mapping LINE = new Mapping("order_date", null, null, "amount", null, null, "sum", "month");

    @Test
    void comparesLastTwoPeriods() {
        ChartData d = new ChartData("c1", "line", List.of("order_date", "amount"),
            List.of(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 150.0)), null, null);
        HighlightSummarizer.Summary summary = HighlightSummarizer.summarize(spec("line", LINE), d, TODAY);
        Map<String, Object> s = summary.state();
        assertThat(s).containsEntry("title", "月銷售額").containsEntry("metric", "amount")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 100.0, "current", 150.0, "changePercent", 50.0)));
        assertThat(summary.change()).isEqualTo(new Highlight.Change("2024-05", "2024-06", null, null, 100.0, 150.0, false));
    }

    @Test
    void comparesEachSeriesAndTreatsMissingAsZero() {
        Mapping m = new Mapping("order_date", null, "region", "amount", null, null, "sum", "month");
        ChartData d = new ChartData("c1", "stacked_bar", List.of("order_date", "region", "amount"),
            List.of(r("2024-05", "北", 10.0), r("2024-05", "南", 20.0), r("2024-06", "北", 5.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("stacked_bar", m), d, TODAY).state();
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 10.0, "current", 5.0, "changePercent", -50.0),
            Map.of("name", "南", "previous", 20.0, "current", 0.0, "changePercent", -100.0)));
    }

    @Test
    void twoMeasureChartsCompareEachMeasureAsItsOwnSeries() {
        Mapping m = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        ChartData d = new ChartData("c1", "line", List.of("date", "sales", "refund"),
            List.of(r("2024-04", 90.0, null), r("2024-05", 100.0, 10.0), r("2024-06", 150.0, 5.0)), null, null);

        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", m), d, TODAY).state();

        assertThat(s).containsEntry("metric", "sales、refund")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "sales", "previous", 100.0, "current", 150.0, "changePercent", 50.0),
            Map.of("name", "refund", "previous", 10.0, "current", 5.0, "changePercent", -50.0)));
    }

    @Test
    void aTwoMeasureChangeNamesTheMeasureByItsPositionInTheMapping() {
        Mapping m = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        ChartData d = new ChartData("c1", "line", List.of("date", "sales", "refund"),
            List.of(r("2024-05", 100.0, 10.0), r("2024-06", 110.0, 60.0)), null, null);

        assertThat(HighlightSummarizer.summarize(spec("line", m), d, TODAY).change())
            .isEqualTo(new Highlight.Change("2024-05", "2024-06", 1, null, 10.0, 60.0, false));
    }

    @Test
    void aKpiIsSummarizedLikeALineWithoutSeries() {
        ChartSpec kpi = new ChartSpec("c1", "kpi", "amount", new GridPos(0, 0, 4, 2), LINE, Map.of());
        ChartData d = new ChartData("c1", "kpi", List.of("order_date", "amount"),
            List.of(r("2024-04", 80.0), r("2024-05", 100.0)), null, null);

        assertThat(HighlightSummarizer.summarizable(kpi)).isTrue();
        HighlightSummarizer.Summary summary = HighlightSummarizer.summarize(kpi, d, TODAY);
        Map<String, Object> s = summary.state();
        assertThat(s).containsEntry("title", "amount").containsEntry("metric", "amount")
            .containsEntry("previousPeriod", "2024-04").containsEntry("period", "2024-05");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 80.0, "current", 100.0, "changePercent", 25.0)));
        assertThat(summary.change())
            .isEqualTo(HighlightSummarizer.summarize(spec("line", LINE), line(r("2024-04", 80.0), r("2024-05", 100.0)), TODAY).change())
            .isEqualTo(new Highlight.Change("2024-04", "2024-05", null, null, 80.0, 100.0, false));
    }

    @SafeVarargs
    static ChartData line(List<Object>... rows) {
        return new ChartData("c1", "line", List.of("order_date", "amount"), List.of(rows), null, null);
    }

    @Test
    void monthStillInProgressAndLaterMonthsAreLeftOut() {
        ChartData d = line(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 20.0), r("2024-07", 5.0));
        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", LINE), d, LocalDate.of(2024, 6, 10)).state();
        assertThat(s).containsEntry("previousPeriod", "2024-04").containsEntry("period", "2024-05");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 80.0, "current", 100.0, "changePercent", 25.0)));
    }

    @Test
    void quarterStillInProgressIsLeftOut() {
        Mapping quarter = new Mapping("order_date", null, null, "amount", null, null, "sum", "quarter");
        ChartData d = new ChartData("c1", "bar", List.of("order_date", "amount"),
            List.of(r("2024-Q1", 80.0), r("2024-Q2", 100.0), r("2024-Q3", 10.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("bar", quarter), d, LocalDate.of(2024, 8, 15)).state();
        assertThat(s).containsEntry("previousPeriod", "2024-Q1").containsEntry("period", "2024-Q2");
    }

    @Test
    void lastPeriodAlreadyCompleteIsComparedAsBefore() {
        ChartData d = line(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 150.0));
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), d, LocalDate.of(2024, 7, 1)).state())
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
    }

    @Test
    void onlyOneCompletePeriodLeftSkipsTheCard() {
        ChartData d = line(r("2024-05", 100.0), r("2024-06", 20.0));
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), d, LocalDate.of(2024, 6, 10))).isNull();
    }

    static final Mapping BY_CUSTOMER = new Mapping("order_date", null, "customer", "amount", null, null, "sum", "month");

    static void addSeries(List<List<Object>> rows, String name, double previous, double current) {
        rows.add(r("2024-05", name, previous));
        rows.add(r("2024-06", name, current));
    }

    static HighlightSummarizer.Summary summary(List<List<Object>> rows) {
        ChartData d = new ChartData("c1", "stacked_bar", List.of("order_date", "customer", "amount"), rows, null, null);
        return HighlightSummarizer.summarize(spec("stacked_bar", BY_CUSTOMER), d, TODAY);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> series(List<List<Object>> rows) {
        return (List<Map<String, Object>>) summary(rows).state().get("series");
    }

    @Test
    void theChangeIsTheSeriesThatMovedTheMostTiesGoByNameWhateverTheRowOrder() {
        List<List<Object>> rows = new ArrayList<>();
        addSeries(rows, "南", 200, 170);
        addSeries(rows, "北", 100, 130);
        addSeries(rows, "東", 10, 30);
        Highlight.Change north = new Highlight.Change("2024-05", "2024-06", null, "北", 100.0, 130.0, false);

        assertThat(summary(rows).change()).isEqualTo(north);
        Collections.reverse(rows);
        assertThat(summary(rows).change()).isEqualTo(north);
    }

    @Test
    void keepsTheTenBiggestChangesAndSumsTheRestIntoOther() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 4; i <= 11; i++) addSeries(rows, "s%02d".formatted(i), 100, 100 + i);
        addSeries(rows, "s12", 112, 100); // 跌 12：比的是變化的絕對值
        addSeries(rows, "b", 100, 103);    // a、b 變化一樣，名字小的留下
        addSeries(rows, "a", 100, 103);
        addSeries(rows, "s01", 100, 101);
        addSeries(rows, "s02", 100, 102);

        List<Map<String, Object>> series = series(rows);

        assertThat(series).extracting(s -> s.get("name"))
            .containsExactly("s12", "s11", "s10", "s09", "s08", "s07", "s06", "s05", "s04", "a", "其他");
        assertThat(series.getLast()).isEqualTo(Map.of("name", "其他", "previous", 300.0, "current", 306.0, "changePercent", 2.0));
    }

    @Test
    void aRealOtherSeriesAbsorbsTheRestInsteadOfBeingOverwritten() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) addSeries(rows, "s%02d".formatted(i), 100, 110 + i); // 變化 11..20
        addSeries(rows, "其他", 100, 150); // 資料本來就有「其他」，變化 50 排第一
        addSeries(rows, "tiny", 100, 101);

        List<Map<String, Object>> series = series(rows);

        assertThat(series).hasSize(10);
        assertThat(series.getFirst()).containsEntry("name", "其他").containsEntry("previous", 300.0).containsEntry("current", 362.0);
        assertThat(summary(rows).change()).isEqualTo(new Highlight.Change("2024-05", "2024-06", null, "其他", 100.0, 150.0, false));
    }

    @Test
    void withMoreThanTenSeriesTheChangeIsARealSeriesNotTheMergedOther() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 5; i <= 14; i++) addSeries(rows, "s%02d".formatted(i), 100, 100 + i);
        for (int i = 1; i <= 6; i++) addSeries(rows, "r" + i, 100, 104);

        assertThat(series(rows).getLast()).isEqualTo(Map.of("name", "其他", "previous", 600.0, "current", 624.0, "changePercent", 4.0));
        assertThat(summary(rows).change()).isEqualTo(new Highlight.Change("2024-05", "2024-06", null, "s14", 100.0, 114.0, false));
    }

    @Test
    void tenSeriesOrFewerAreAllSentSortedByName() {
        // 照名稱排，跟資料列的順序無關：外部 API 每次給的順序不同，送出去的內容還是一樣
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 10; i >= 1; i--) addSeries(rows, "s" + i, 100, 100 + i);
        assertThat(series(rows)).extracting(s -> s.get("name"))
            .containsExactly("s1", "s10", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9");
    }

    @Test
    void skipsCardsWithoutTimeAxisOrWithOnePeriodOrWithErrors() {
        Mapping pie = new Mapping("region", null, null, "amount", null, null, "sum", null);
        ChartData pieData = new ChartData("c1", "pie", List.of("region", "amount"), List.of(r("北", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("pie", pie), pieData, TODAY)).isNull();

        ChartData onePeriod = new ChartData("c1", "line", List.of("order_date", "amount"), List.of(r("2024-06", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), onePeriod, TODAY)).isNull();

        assertThat(HighlightSummarizer.summarize(spec("line", LINE), ChartData.missing("c1", "line", "amount"), TODAY)).isNull();
    }

    @Test
    void skipsTableTypeAndExplicitNoneAggregateEvenWithTimeAxisAndTwoPeriods() {
        ChartData tableData = new ChartData("c1", "table", List.of("order_date", "amount"),
            List.of(r("2024-05", 100.0), r("2024-06", 150.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("table", LINE), tableData, TODAY)).isNull();

        Mapping noneAgg = new Mapping("order_date", null, null, "amount", null, null, "none", "month");
        ChartData barData = new ChartData("c1", "bar", List.of("order_date", "amount"),
            List.of(r("2024-05", 100.0), r("2024-06", 150.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("bar", noneAgg), barData, TODAY)).isNull();
    }

    static final Mapping RETURN_RATE = new Mapping("date", null, null, "退貨率", null, null, "sum", "month");

    static ChartData percent(String type, List<String> dimensions, List<List<Object>> rows) {
        return new ChartData("c1", type, dimensions, rows, null, null, null, null, null, true);
    }

    @Test
    void ratioChartsComparePercentagesAndTheChangeInPoints() {
        ChartData d = percent("line", List.of("date", "退貨率"),
            List.of(r("2024-04", 0.03), r("2024-05", 0.0521), r("2024-06", 0.0649)));

        HighlightSummarizer.Summary summary = HighlightSummarizer.summarize(spec("line", RETURN_RATE), d, TODAY);
        Map<String, Object> s = summary.state();

        assertThat(s).containsEntry("metric", "退貨率").containsEntry("unit", "%")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 5.2, "current", 6.5, "changePoints", 1.3)));
        assertThat(summary.change()).isEqualTo(new Highlight.Change("2024-05", "2024-06", null, null, 0.0521, 0.0649, true));
    }

    @Test
    void aRatioSeriesWithAnEmptyPeriodIsNotComparedAndACardWithNothingComparableIsSkipped() {
        Mapping bySeries = new Mapping("date", null, "region", "退貨率", null, null, "sum", "month");
        ChartData d = percent("stacked_bar", List.of("date", "region", "退貨率"), List.of(
            r("2024-05", "北", 0.04), r("2024-05", "南", null),
            r("2024-06", "北", 0.05), r("2024-06", "南", 0.02), r("2024-06", "東", 0.1)));

        // 南區前一期是空白、東區前一期沒有資料，都不比；沒有值不代表比率是 0
        HighlightSummarizer.Summary summary = HighlightSummarizer.summarize(spec("stacked_bar", bySeries), d, TODAY);
        assertThat(summary.state().get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 4.0, "current", 5.0, "changePoints", 1.0)));
        assertThat(summary.change()).isEqualTo(new Highlight.Change("2024-05", "2024-06", null, "北", 0.04, 0.05, true));

        ChartData empty = percent("line", List.of("date", "退貨率"), List.of(r("2024-05", null), r("2024-06", 0.05)));
        assertThat(HighlightSummarizer.summarize(spec("line", RETURN_RATE), empty, TODAY)).isNull();
    }
}
