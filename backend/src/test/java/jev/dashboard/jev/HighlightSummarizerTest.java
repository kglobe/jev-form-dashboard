package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

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

    static final Mapping LINE = new Mapping("order_date", null, null, "amount", null, null, "sum", "month");

    @Test
    void comparesLastTwoPeriods() {
        ChartData d = new ChartData("c1", "line", List.of("order_date", "amount"),
            List.of(r("2024-04", 80.0), r("2024-05", 100.0), r("2024-06", 150.0)), null, null);
        Map<String, Object> s = HighlightSummarizer.summarize(spec("line", LINE), d);
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
        Map<String, Object> s = HighlightSummarizer.summarize(spec("stacked_bar", m), d);
        assertThat(s.get("series")).isEqualTo(List.of(
            Map.of("name", "北", "previous", 10.0, "current", 5.0, "changePercent", -50.0),
            Map.of("name", "南", "previous", 20.0, "current", 0.0, "changePercent", -100.0)));
    }

    @Test
    void skipsCardsWithoutTimeAxisOrWithOnePeriodOrWithErrors() {
        Mapping pie = new Mapping("region", null, null, "amount", null, null, "sum", null);
        ChartData pieData = new ChartData("c1", "pie", List.of("region", "amount"), List.of(r("北", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("pie", pie), pieData)).isNull();

        ChartData onePeriod = new ChartData("c1", "line", List.of("order_date", "amount"), List.of(r("2024-06", 1.0)), null, null);
        assertThat(HighlightSummarizer.summarize(spec("line", LINE), onePeriod)).isNull();

        assertThat(HighlightSummarizer.summarize(spec("line", LINE), ChartData.missing("c1", "line", "amount"))).isNull();
    }
}
