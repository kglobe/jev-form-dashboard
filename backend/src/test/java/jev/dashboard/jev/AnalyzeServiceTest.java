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
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.datasource.MergeEngine;
import jev.dashboard.datasource.Ratio;
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
            assertThat(c.layout()).extracting(ChartSpec::datasourceId).containsOnly(ds);
            // KPI spec 2.3：最上面一排是 KPI 卡，amount 是重要的數值；不另外問 JEV
            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("kpi", "line", "stacked_bar", "bar", "table");
            assertThat(c.layout().get(0).mapping()).isEqualTo(new Mapping("order_date", null, null, "amount", null, null, "sum", "month"));
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
                    tuple("c1", "kpi", "amount", Map.of()),
                    tuple("c2", "bar", "amount 依 product", Map.of("focus", true)),
                    tuple("c3", "bar", "qty 依 product", Map.of("focus", true)),
                    tuple("c4", "table", "明細", Map.of()));
            // 明細表的欄位是候選欄位，焦點欄位排最前面
            assertThat(c.layout().get(3).mapping().columns()).containsExactly("product", "order_date", "amount", "region", "qty");

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
            UUID ds = UUID.randomUUID();
            DashboardConfig c = service(server).analyze("銷售 總覽", ds, Fixtures.SALES_SCHEMA, 12,
                intents, List.of(line, pie, table));

            assertThat(c.intents()).isEqualTo(intents);
            assertThat(c.layout()).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos).containsExactly(
                tuple("c1", "line", new GridPos(0, 0, 6, 4)),     // keep_c1：保留
                tuple("c4", "bar", new GridPos(6, 0, 6, 4)),      // 新圖的編號接在 c3 後面
                tuple("c5", "bar", new GridPos(0, 4, 6, 4)),
                tuple("c3", "table", new GridPos(0, 8, 12, 5)));  // keep_c2：移除；明細表放最後
            assertThat(c.layout().get(0)).isEqualTo(line.withDatasourceId(ds)); // 保留的圖只多了這次分析的來源
            assertThat(c.layout()).extracting(ChartSpec::datasourceId).containsOnly(ds);
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

    @Test
    void theKeepQuestionOfAKpiUsesItsChineseName() {
        ChartSpec kpi = new ChartSpec("c1", "kpi", "amount", new GridPos(0, 0, 4, 2),
            new Mapping("order_date", null, null, "amount", null, null, "sum", "month"), Map.of());
        assertThat(AnalyzeService.keepQuestion("不要 KPI", kpi)).isEqualTo(
            "使用者最新的要求：「不要 KPI」。目前有一張圖「amount」（KPI 大數字卡，欄位：order_date、amount），這張圖要保留還是移除？");
    }

    @Test
    void longSamplesAreClippedInTheStateSoOneMemoFieldCannotBlowTheRequestSize() {
        String memo = "備".repeat(200);
        List<FieldSchema> schema = List.of(new FieldSchema("memo", FieldType.STRING, 2, List.of(memo, "短"), false, null, null));
        Map<String, Object> state = AnalyzeService.state(schema, 2, List.of(), null);
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) ((List<Map<String, Object>>) state.get("fields")).get(0).get("samples");
        assertThat(samples).containsExactly("備".repeat(AnalyzeService.MAX_SAMPLE_CHARS) + "…", "短");
    }

    @Test
    void aCompareSourceAsksTwoMeasureCombosFirstAndNeverDrawsTheSourceColumn() throws Exception {
        // 欄位順序對齊 round1.json 的角色：0 時間、1 數值、2 分類、3 分類（來源欄）、4 數值、5 忽略
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 9, List.of("2026-01-05"), false, "2026-01-05", "2026-09-20"),
            new FieldSchema("銷售·amount", FieldType.NUMBER, 9, List.of("1000"), false, null, null),
            new FieldSchema("region", FieldType.STRING, 2, List.of("北區"), false, null, null),
            new FieldSchema("來源", FieldType.STRING, 2, List.of("銷售", "退貨"), false, null, null),
            new FieldSchema("退貨·amount", FieldType.NUMBER, 3, List.of("50"), false, null, null),
            new FieldSchema("memo", FieldType.STRING, 9, List.of(), false, null, null));
        MergeEngine.Comparison compare = new MergeEngine.Comparison(Map.of(
            "銷售·amount", Set.of("date", "region", "銷售·amount"),
            "退貨·amount", Set.of("date", "region", "退貨·amount")), "來源");

        try (FakeJevServer server = FakeJevServer.start(FakeJevServer::route)) {
            DashboardConfig c = service(server).analyze("對照 總覽", UUID.randomUUID(), schema, 12,
                List.of(), null, Set.of(), compare);

            // round2.json：combo_0（對照組合配時間）答 line；combo_4（銷售·amount 依 region）答 bar
            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("kpi", "line", "bar", "table");
            assertThat(c.layout().get(0).title()).isEqualTo("銷售·amount");
            assertThat(c.layout().get(1).mapping()).isEqualTo(
                new Mapping("date", null, null, null, List.of("銷售·amount", "退貨·amount"), null, "sum", "month"));
            assertThat(c.layout().get(1).title()).isEqualTo("銷售·amount 與 退貨·amount 依 date");
            assertThat(c.layout().get(3).mapping().columns()).containsExactly("date", "銷售·amount", "region", "退貨·amount");
            assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
            assertThat(server.bodies.get(1)).contains("兩個數值來自不同來源，要放在同一張圖上對照。")
                .doesNotContain("來源（分類）");
        }
    }

    @Test
    void theSourceColumnStaysOutOfTheTableEvenWhenNoFieldIsACandidate() throws Exception {
        List<FieldSchema> schema = List.of(
            new FieldSchema("銷售·amount", FieldType.NUMBER, 9, List.of("1000"), false, null, null),
            new FieldSchema("來源", FieldType.STRING, 2, List.of("銷售", "退貨"), false, null, null));
        // 每個欄位都被判成 ignore：沒有候選欄位，明細表退回全部 schema 欄位
        String allIgnored = """
            {"model":"jev-1.13.0","answers":{
              "imp_0":{"type":"score","score":0.0,"confidence":0.9},"role_0":{"type":"choice","choice":"ignore","confidence":0.9},
              "imp_1":{"type":"score","score":0.0,"confidence":0.9},"role_1":{"type":"choice","choice":"ignore","confidence":0.9},
              "chart_count":{"type":"score","score":1.0,"confidence":0.9}}}""";

        try (FakeJevServer server = FakeJevServer.start(body -> allIgnored)) {
            DashboardConfig c = service(server).analyze("對照 總覽", UUID.randomUUID(), schema, 2, List.of(), null, Set.of(),
                new MergeEngine.Comparison(Map.of("銷售·amount", Set.of("銷售·amount")), "來源"));

            assertThat(c.layout()).extracting(ChartSpec::type).containsExactly("table");
            assertThat(c.layout().get(0).mapping().columns()).containsExactly("銷售·amount");
        }
    }

    @Test
    void ratiosAreAskedAfterTheFieldsWithTheirFormulaAndDrawnAsSingleValueCharts() throws Exception {
        List<FieldSchema> schema = List.of(
            new FieldSchema("order_date", FieldType.DATE, 12, List.of("2024-01-05"), false, "2024-01-05", "2024-06-20"),
            new FieldSchema("region", FieldType.STRING, 4, List.of("北區"), false, null, null),
            new FieldSchema("revenue", FieldType.NUMBER, 12, List.of("1200"), false, null, null),
            new FieldSchema("gross_profit", FieldType.NUMBER, 12, List.of("300"), false, null, null));
        List<Ratio> ratios = List.of(new Ratio("毛利率", "gross_profit", "revenue"));
        // 毛利率的重要性是 0，照樣一定進候選；gross_profit 不重要，不進候選
        String round1 = """
            {"model":"jev-1.13.0","answers":{
              "imp_0":{"type":"score","score":3.0,"confidence":0.9},"role_0":{"type":"choice","choice":"time","confidence":0.9},
              "imp_1":{"type":"score","score":2.0,"confidence":0.9},"role_1":{"type":"choice","choice":"dimension","confidence":0.9},
              "imp_2":{"type":"score","score":2.0,"confidence":0.9},"role_2":{"type":"choice","choice":"measure","confidence":0.9},
              "imp_3":{"type":"score","score":0.0,"confidence":0.9},"role_3":{"type":"choice","choice":"measure","confidence":0.9},
              "imp_4":{"type":"score","score":0.0,"confidence":0.9},
              "chart_count":{"type":"score","score":1.0,"confidence":0.9}}}""";
        // 比率組合排在一般組合前面：combo_0 是毛利率配 order_date，combo_1 是毛利率配 region
        String round2 = """
            {"model":"jev-1.13.0","answers":{
              "combo_0":{"type":"choice","choice":"line","confidence":0.9},
              "combo_1":{"type":"choice","choice":"bar","confidence":0.9}}}""";

        try (FakeJevServer server = FakeJevServer.start(body -> body.contains("\"combo_0\"") ? round2 : round1)) {
            DashboardConfig c = service(server).analyze("毛利 總覽", UUID.randomUUID(), schema, 12,
                List.of(), null, Set.of(), null, ratios);

            // 毛利率不重要，不放 KPI 卡；revenue 重要，放一張
            assertThat(c.layout()).extracting(ChartSpec::type, ChartSpec::title).containsExactly(
                tuple("kpi", "revenue"), tuple("line", "毛利率 依 order_date"), tuple("bar", "毛利率 依 region"), tuple("table", "明細"));
            assertThat(c.layout().get(1).mapping())
                .isEqualTo(new Mapping("order_date", null, null, "毛利率", null, null, "sum", "month"));
            // 明細表不放比率
            assertThat(c.layout().get(3).mapping().columns()).containsExactly("order_date", "region", "revenue");
            assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();

            JsonNode fields = JSON.readTree(server.bodies.get(0)).at("/state/fields");
            assertThat(fields).hasSize(5);
            assertThat(JSON.convertValue(fields.get(4), new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("name", "毛利率", "type", "ratio", "formula", "gross_profit ÷ revenue"));
            assertThat(server.bodies.get(0)).contains("\"imp_4\"").contains("比率「毛利率」對分析這份資料有多重要？")
                .doesNotContain("\"role_4\"").doesNotContain("rel_");
            assertThat(server.bodies.get(1)).contains("「毛利率」是比率＝gross_profit ÷ revenue，每組先各自加總再相除。");

            // 有需求時也問比率的相關性
            service(server).analyze("毛利 總覽", UUID.randomUUID(), schema, 12, List.of("毛利"), null, Set.of(), null, ratios);
            assertThat(server.bodies.get(2)).contains("\"rel_4\"").contains("比率「毛利率」跟這個需求有多相關？");
        }
    }
}
