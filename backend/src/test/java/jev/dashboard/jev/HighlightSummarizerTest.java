package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", LINE), d, TODAY);
        assertThat(s).containsEntry("title", "月銷售額").containsEntry("metric", "amount")
            .containsEntry("previousPeriod", "2024-05").containsEntry("period", "2024-06");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 100.0, "current", 150.0, "changePercent", 50.0)));
    }

    @Test
    void comparesEachSeriesAndTreatsMissingAsZero() {
        Mapping m = new Mapping("order_date", null, "region", "amount", null, null, "sum", "month");
        ChartData d = new ChartData("c1", "stacked_bar", List.of("order_date", "region", "amount"),
            List.of(r("2024-05", "北", 10.0), r("2024-05", "南", 20.0), r("2024-06", "北", 5.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("stacked_bar", m), d, TODAY);
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 10.0, "current", 5.0, "changePercent", -50.0),
            Map.of("name", "南", "previous", 20.0, "current", 0.0, "changePercent", -100.0)));
    }

    @SafeVarargs
    static ChartData line(List<Object>... rows) {
        return new ChartData("c1", "line", List.of("order_date", "amount"), List.of(rows), null, null);
    }

    @Test
    void monthStillInProgressAndLaterMonthsAreLeftOut() {
        ChartData d = line(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 20.0), r("2024-07", 5.0));
        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", LINE), d, LocalDate.of(2024, 6, 10));
        assertThat(s).containsEntry("previousPeriod", "2024-04").containsEntry("period", "2024-05");
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "全部", "previous", 80.0, "current", 100.0, "changePercent", 25.0)));
    }

    @Test
    void quarterStillInProgressIsLeftOut() {
        Mapping quarter = new Mapping("order_date", null, null, "amount", null, null, "sum", "quarter");
        ChartData d = new ChartData("c1", "bar", List.of("order_date", "amount"),
            List.of(r("2024-Q1", 80.0), r("2024-Q2", 100.0), r("2024-Q3", 10.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("bar", quarter), d, LocalDate.of(2024, 8, 15));
        assertThat(s).containsEntry("previousPeriod", "2024-Q1").containsEntry("period", "2024-Q2");
    }

    @Test
    void lastPeriodAlreadyCompleteIsComparedAsBefore() {
        ChartData d = line(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 150.0));
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), d, LocalDate.of(2024, 7, 1)))
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

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> series(List<List<Object>> rows) {
        ChartData d = new ChartData("c1", "stacked_bar", List.of("order_date", "customer", "amount"), rows, null, null);
        return (List<Map<String, Object>>) HighlightSummarizer.summarize(spec("stacked_bar", BY_CUSTOMER), d, TODAY).get("series");
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
    }

    @Test
    void tenSeriesOrFewerAreSentAsIs() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) addSeries(rows, "s" + i, 100, 100 + i);
        assertThat(series(rows)).extracting(s -> s.get("name"))
            .containsExactly("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10");
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
}
