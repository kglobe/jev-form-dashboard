package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.transform.ChartSpec;
import org.junit.jupiter.api.Test;

class AnalyzeServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void runsTwoRoundsAndSkipsSamplesOfSensitiveFields() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            AnalyzeService service = new AnalyzeService(
                new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
            UUID ds = UUID.randomUUID();

            DashboardConfig c = service.analyze("銷售 總覽", ds, Fixtures.SALES_SCHEMA, 12);

            assertThat(c.id()).isNull();
            assertThat(c.name()).isEqualTo("銷售 總覽");
            assertThat(c.datasourceId()).isEqualTo(ds);
            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("line", "stacked_bar", "bar", "table");
            assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
            assertThat(server.bodies).hasSize(2);
            assertThat(server.bodies.get(0))
                .contains("\"imp_5\"").contains("\"role_5\"").contains("\"chart_count\"")
                .contains("\"rowCount\":12")
                .contains("memo").doesNotContain("機密備註")
                .contains("北區");
            assertThat(memoField(server.bodies.get(0))).isEqualTo(Map.of("name", "memo", "type", "string"));
            assertThat(server.bodies.get(1))
                .contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("\"none\"").doesNotContain("機密備註");
        }
    }

    /** 解析 request body，找出 memo 欄位在 state.fields 裡的完整內容。 */
    private static Map<String, Object> memoField(String body) throws Exception {
        for (JsonNode f : JSON.readTree(body).at("/state/fields")) {
            if (f.get("name").asText().equals("memo")) return JSON.convertValue(f, new TypeReference<Map<String, Object>>() {});
        }
        throw new AssertionError("memo field not found in body: " + body);
    }
}
