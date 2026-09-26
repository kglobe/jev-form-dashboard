package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
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

    private static AnalyzeService service(FakeJevServer server) {
        return new AnalyzeService(new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
    }

    private static String intentRoute(String body) {
        return body.contains("\"combo_0\"") ? Fixtures.json("round2-intent.json") : Fixtures.json("round1-intent.json");
    }

    @Test
    void withoutIntentsNothingNewIsAsked() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12);

            assertThat(c.intents()).isNull();
            assertThat(c.layout()).allSatisfy(s -> assertThat(s.options()).isEmpty());
            assertThat(server.bodies.get(0)).doesNotContain("rel_").doesNotContain("keep_")
                .doesNotContain("\"intent\"").doesNotContain("\"history\"").doesNotContain("\"current\"");
            assertThat(server.bodies.get(1)).doesNotContain("使用者想看");
        }
    }

    @Test
    void pickedFieldsAreFocusFieldsEvenWithoutAnIntent() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                List.of(), null, Set.of("region"));

            ChartSpec table = c.layout().get(c.layout().size() - 1);
            assertThat(table.mapping().columns()).startsWith("region");
            List<ChartSpec> charts = c.layout().subList(0, c.layout().size() - 1);
            assertThat(charts).isNotEmpty().allSatisfy(s -> assertThat(s.options().containsKey("focus"))
                .isEqualTo(UniversalTransformer.referencedFields(s.mapping()).contains("region")));
            assertThat(charts).anySatisfy(s -> assertThat(s.options()).containsEntry("focus", true));
            assertThat(server.bodies.get(0)).doesNotContain("rel_");
        }
    }

    @Test
    void firstAnalysisWithAnIntentAsksRelevanceAndPutsFocusChartsFirst() throws Exception {
        try (FakeJevServer server = FakeJevServer.start(AnalyzeServiceTest::intentRoute)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                List.of("各產品的數量"), null);

            assertThat(c.intents()).containsExactly("各產品的數量");
            assertThat(c.layout()).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::title, ChartSpec::options)
                .containsExactly(
                    tuple("c1", "bar", "amount 依 product", Map.of("focus", true)),
                    tuple("c2", "bar", "qty 依 product", Map.of("focus", true)),
                    tuple("c3", "table", "明細", Map.of()));
            // 明細表的欄位是候選欄位，焦點欄位排最前面
            assertThat(c.layout().get(2).mapping().columns()).containsExactly("product", "order_date", "amount", "region", "qty");

            String round1 = server.bodies.get(0);
            assertThat(round1).contains("\"rel_0\"").contains("\"rel_5\"").contains("\"chart_count\"")
                .contains("\"intent\":\"各產品的數量\"").doesNotContain("\"history\"")
                .doesNotContain("keep_").doesNotContain("\"current\"")
                .contains("使用者想看：「各產品的數量」。欄位「product」跟這個需求有多相關？")
                .contains("[\"無關\",\"間接相關\",\"直接相關\"]")
                .doesNotContain("機密備註");
            assertThat(memoField(round1)).isEqualTo(Map.of("name", "memo", "type", "string"));
            assertThat(server.bodies.get(1)).contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("選 none。使用者想看：「各產品的數量」。");
        }
    }

    @Test
    void adjustAsksWhetherToKeepEachChartAndAddsFocusChartsAfterTheKeptOnes() throws Exception {
        ChartSpec line = new ChartSpec("c1", "line", "amount 依 order_date", new GridPos(0, 0, 6, 4),
            new Mapping("order_date", null, null, "amount", null, null, "sum", "month"), Map.of());
        ChartSpec pie = new ChartSpec("c2", "pie", "amount 依 region", new GridPos(6, 0, 6, 4),
            new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
        ChartSpec table = new ChartSpec("c3", "table", "明細", new GridPos(0, 4, 12, 5),
            new Mapping(null, null, null, null, null, List.of("order_date", "amount", "region"), "none", null), Map.of());
        List<String> intents = List.of("各區每月營收", "不要圓餅圖，加各產品的數量");

        try (FakeJevServer server = FakeJevServer.start(AnalyzeServiceTest::intentRoute)) {
            DashboardConfig c = service(server).analyze("銷售 總覽", UUID.randomUUID(), Fixtures.SALES_SCHEMA, 12,
                intents, List.of(line, pie, table));

            assertThat(c.intents()).isEqualTo(intents);
            assertThat(c.layout()).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos).containsExactly(
                tuple("c1", "line", new GridPos(0, 0, 6, 4)),     // keep_c1：保留
                tuple("c4", "bar", new GridPos(6, 0, 6, 4)),      // 新圖的編號接在 c3 後面
                tuple("c5", "bar", new GridPos(0, 4, 6, 4)),
                tuple("c3", "table", new GridPos(0, 8, 12, 5)));  // keep_c2：移除；明細表放最後
            assertThat(c.layout().get(0)).isEqualTo(line);        // 保留的圖設定原封不動
            assertThat(c.layout().get(1).options()).isEqualTo(Map.of("focus", true));
            assertThat(c.layout().get(3).mapping()).isEqualTo(table.mapping());

            String round1 = server.bodies.get(0);
            assertThat(round1).contains("\"keep_c1\"").contains("\"keep_c2\"").doesNotContain("\"keep_c3\"")
                .doesNotContain("\"chart_count\"").contains("\"rel_0\"")
                .contains("\"intent\":\"不要圓餅圖，加各產品的數量\"").contains("\"history\":[\"各區每月營收\"]")
                .contains("\"current\":[{\"chartId\":\"c1\",\"title\":\"amount 依 order_date\",\"type\":\"line\","
                    + "\"fields\":[\"order_date\",\"amount\"]},{\"chartId\":\"c2\",\"title\":\"amount 依 region\","
                    + "\"type\":\"pie\",\"fields\":[\"region\",\"amount\"]}]")
                .contains("使用者最新的要求：「不要圓餅圖，加各產品的數量」。目前有一張圖「amount 依 region」"
                    + "（圓餅圖，欄位：region、amount），這張圖要保留還是移除？")
                .contains("{\"keep\":\"保留這張圖\",\"drop\":\"移除這張圖\"}")
                .doesNotContain("機密備註");
            assertThat(server.bodies.get(1)).contains("\"combo_5\"").doesNotContain("\"combo_6\"")
                .contains("選 none。使用者想看：「不要圓餅圖，加各產品的數量」。");
            // 調整模式一樣不送敏感欄位的樣本
            assertThat(server.bodies).hasSize(2).allSatisfy(body -> assertThat(body).doesNotContain("機密備註"));
            assertThat(memoField(round1)).isEqualTo(Map.of("name", "memo", "type", "string"));
            assertThat(memoField(server.bodies.get(1))).isEqualTo(Map.of("name", "memo", "type", "string"));
        }
    }
}
