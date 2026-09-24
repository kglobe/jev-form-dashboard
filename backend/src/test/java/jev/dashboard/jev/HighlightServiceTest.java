package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.FakeJevServer;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class HighlightServiceTest {
    static List<Object> r(Object... values) {
        return Arrays.asList(values);
    }

    static final ChartSpec LINE = new ChartSpec("c1", "line", "月銷售額", new GridPos(0, 0, 6, 4),
        new Mapping("order_date", null, null, "amount", null, null, "sum", "month"), Map.of());
    static final ChartSpec BAR = new ChartSpec("c2", "bar", "月數量", new GridPos(6, 0, 6, 4),
        new Mapping("order_date", null, null, "qty", null, null, "sum", "month"), Map.of());
    static final ChartSpec PIE = new ChartSpec("c3", "pie", "地區佔比", new GridPos(0, 4, 6, 4),
        new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
    static final List<ChartData> DATA = List.of(
        new ChartData("c1", "line", List.of("order_date", "amount"), List.of(r("2024-05", 3200.0), r("2024-06", 4300.0)), null, null),
        new ChartData("c2", "bar", List.of("order_date", "qty"), List.of(r("2024-05", 10.0), r("2024-06", 13.0)), null, null),
        new ChartData("c3", "pie", List.of("region", "amount"), List.of(r("北區", 100.0)), null, null));

    static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    static HighlightService service(FakeJevServer server) {
        return new HighlightService(new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
    }

    @Test
    void flagsCardsAboveThresholdWithSeverityLabel() {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            List<Highlight> result = service(server).check(UUID.randomUUID(), 1, TODAY, List.of(LINE, BAR, PIE), DATA);
            assertThat(result).containsExactly(new Highlight("c1", "需要關注", 0.85));
            assertThat(server.bodies).hasSize(1);
            assertThat(server.bodies.get(0))
                .contains("\"anomaly_c1\"").contains("\"severity_c2\"").doesNotContain("anomaly_c3")
                .contains("34.4");
        }
    }

    @Test
    void jevFailureReturnsNullSoTheResultIsNotCached() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            assertThat(service(server).check(UUID.randomUUID(), 1, TODAY, List.of(LINE), List.of(DATA.get(0)))).isNull();
        }
    }

    @Test
    void noTimeAxisCardsSkipsJev() {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            assertThat(service(server).check(UUID.randomUUID(), 1, TODAY, List.of(PIE), List.of(DATA.get(2)))).isEmpty();
            assertThat(server.bodies).isEmpty();
        }
    }
}
