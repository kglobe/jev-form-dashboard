package jev.dashboard.jev;

import static jev.dashboard.datasource.FieldType.DATE;
import static jev.dashboard.datasource.FieldType.NUMBER;
import static jev.dashboard.datasource.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import jev.dashboard.Fixtures;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;

class ConfigAssemblerTest {
    static final List<String> TABLE_COLUMNS = List.of("order_date", "amount", "region", "product", "qty");

    final List<FieldEval> evals = ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, Fixtures.answers("round1.json"));

    static Answer score(Double s) {
        return new Answer("score", null, s, 0.9, null);
    }

    static FieldEval eval(String name, FieldType type, int distinct, double importance, String role) {
        return new FieldEval(new FieldSchema(name, type, distinct, List.of(), false, null, null), importance, role);
    }

    static List<String> names(Combo c) {
        return c.fields().stream().map(FieldEval::name).toList();
    }

    static FieldEval focus(FieldEval e) {
        return new FieldEval(e.field(), e.importance(), e.role(), true);
    }

    static Answer choice(String choice, double confidence) {
        return new Answer("choice", choice, null, confidence, null);
    }

    static Answer rel(double score, double confidence) {
        return new Answer("score", null, score, confidence, null);
    }

    /** combo_0 含焦點欄位 region；combo_1、combo_2 沒有。 */
    static List<Combo> regionFocusCombos() {
        FieldEval time = new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, "2024-01-05", "2024-06-20"), 3, "time");
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        FieldEval amount = eval("amount", NUMBER, 12, 2.75, "measure");
        FieldEval qty = eval("qty", NUMBER, 8, 1, "measure");
        return List.of(
            new Combo(List.of(region, amount), List.of("bar", "pie"), 2.375),
            new Combo(List.of(time, amount), List.of("line", "bar"), 2.875),
            new Combo(List.of(time, qty), List.of("line", "bar"), 2.0));
    }

    static final FieldEval PRODUCT = focus(eval("product", STRING, 3, 1.25, "dimension"));

    static Mapping xy(String x, String value) {
        return new Mapping(x, null, null, value, null, null, "sum", null);
    }

    static ChartSpec card(String chartId, String type, Mapping m) {
        return new ChartSpec(chartId, type, chartId + " 圖", new GridPos(0, 0, 6, 4), m, Map.of());
    }

    static ChartSpec table(String chartId, List<String> columns) {
        return new ChartSpec(chartId, "table", "明細", new GridPos(0, 4, 12, 5),
            new Mapping(null, null, null, null, null, columns, "none", null), Map.of());
    }

    @Test
    void evaluateReadsImportanceAndRole() {
        assertThat(evals).extracting(FieldEval::name, FieldEval::importance, FieldEval::role).containsExactly(
            tuple("order_date", 3.0, "time"), tuple("amount", 2.75, "measure"), tuple("region", 2.0, "dimension"),
            tuple("product", 1.25, "dimension"), tuple("qty", 1.0, "measure"), tuple("memo", 0.25, "ignore"));
    }

    @Test
    void evaluateIgnoresLowConfidenceAndTypeMismatchedRoles() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("role_2", new Answer("choice", "dimension", null, 0.4, null));
        a.put("role_3", new Answer("choice", "measure", null, 0.9, null)); // product 是字串，不能當 measure
        List<FieldEval> e = ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a);
        assertThat(e.get(2).role()).isEqualTo("ignore");
        assertThat(e.get(3).role()).isEqualTo("ignore");
        assertThat(e.get(0).role()).isEqualTo("time");
    }

    @Test
    void chartCountIsRoundedScorePlusOneWithin1To6() {
        assertThat(ConfigAssembler.chartCount(score(2.2))).isEqualTo(3);
        assertThat(ConfigAssembler.chartCount(score(0.0))).isEqualTo(1);
        assertThat(ConfigAssembler.chartCount(score(5.4))).isEqualTo(6);
        assertThat(ConfigAssembler.chartCount(score(5.6))).isEqualTo(6);
        assertThat(ConfigAssembler.chartCount(null)).isEqualTo(1);
    }

    @Test
    void candidatesSkipIgnoredAndUnimportantFields() {
        assertThat(ConfigAssembler.candidates(evals)).extracting(FieldEval::name)
            .containsExactly("order_date", "amount", "region", "product", "qty");
    }

    @Test
    void combosAreRankedByAverageImportance() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        assertThat(combos).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("order_date", "amount"),
            List.of("order_date", "amount", "region"),
            List.of("amount", "region"),
            List.of("order_date", "amount", "product"),
            List.of("order_date", "qty"),
            List.of("amount", "product"));
        assertThat(combos).extracting(Combo::chartTypes).containsExactly(
            List.of("line", "bar"),
            List.of("stacked_bar", "heatmap"),
            List.of("bar", "pie"),
            List.of("stacked_bar", "heatmap"),
            List.of("line", "bar"),
            List.of("bar", "pie"));
    }

    @Test
    void pieNeedsAtMost8Categories() {
        List<Combo> combos = ConfigAssembler.combos(List.of(
            eval("customer", STRING, 20, 2, "dimension"), eval("amount", NUMBER, 50, 2, "measure")), 3);
        assertThat(combos.get(0).chartTypes()).containsExactly("bar");
    }

    @Test
    void radarNeedsThreeMeasuresAndAtMost5Categories() {
        List<FieldEval> cands = List.of(
            eval("channel", STRING, 3, 2, "dimension"),
            eval("m1", NUMBER, 9, 2, "measure"), eval("m2", NUMBER, 9, 2, "measure"), eval("m3", NUMBER, 9, 2, "measure"));
        assertThat(ConfigAssembler.combos(cands, 20)).anySatisfy(c -> {
            assertThat(c.chartTypes()).containsExactly("radar");
            assertThat(names(c)).containsExactly("channel", "m1", "m2", "m3");
        });
    }

    @Test
    void assemblePicksTopKAnsweredCombosAndLaysOutGrid() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        List<ChartSpec> layout = ConfigAssembler.assemble(combos, Fixtures.answers("round2.json"), 3, TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type).containsExactly(
            tuple("c1", "line"), tuple("c2", "stacked_bar"), tuple("c3", "bar"), tuple("c4", "table"));
        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("order_date", null, null, "amount", null, null, "sum", "month"));
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("order_date", null, "region", "amount", null, null, "sum", "month"));
        assertThat(layout.get(2).mapping()).isEqualTo(new Mapping("order_date", null, null, "qty", null, null, "sum", "month"));
        assertThat(layout.get(3).mapping()).isEqualTo(new Mapping(null, null, null, null, null, TABLE_COLUMNS, "none", null));
        assertThat(layout).extracting(ChartSpec::gridPos).containsExactly(
            new GridPos(0, 0, 6, 4), new GridPos(6, 0, 6, 4), new GridPos(0, 4, 6, 4), new GridPos(0, 8, 12, 5));
        assertThat(layout).extracting(ChartSpec::title).containsExactly(
            "amount 依 order_date", "amount 依 order_date 與 region", "qty 依 order_date", "明細");
    }

    @Test
    void everyChartTheAssemblerBuildsPassesDashboardValidation() {
        FieldEval time = new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, "2024-01-05", "2024-06-20"), 3, "time");
        FieldEval region = eval("region", STRING, 4, 2, "dimension");
        FieldEval product = eval("product", STRING, 3, 2, "dimension");
        FieldEval amount = eval("amount", NUMBER, 12, 2, "measure");
        FieldEval qty = eval("qty", NUMBER, 8, 2, "measure");
        FieldEval cost = eval("cost", NUMBER, 8, 2, "measure");
        List<Combo> combos = List.of(
            new Combo(List.of(time, amount), List.of("line"), 2),
            new Combo(List.of(time, amount), List.of("bar"), 2),
            new Combo(List.of(region, amount), List.of("bar"), 2),
            new Combo(List.of(region, amount), List.of("pie"), 2),
            new Combo(List.of(amount, qty), List.of("scatter"), 2),
            new Combo(List.of(region, amount, qty, cost), List.of("radar"), 2),
            new Combo(List.of(time, region, amount), List.of("stacked_bar"), 2),
            new Combo(List.of(region, product, amount), List.of("stacked_bar"), 2),
            new Combo(List.of(time, region, amount), List.of("heatmap"), 2),
            new Combo(List.of(region, product, amount), List.of("heatmap"), 2));
        Map<String, Answer> answers = new HashMap<>();
        for (int i = 0; i < combos.size(); i++) {
            answers.put("combo_" + i, new Answer("choice", combos.get(i).chartTypes().get(0), null, 0.9, null));
        }
        List<ChartSpec> layout = ConfigAssembler.assemble(combos, answers, combos.size(), TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::type).containsExactly("line", "bar", "bar", "pie", "scatter", "radar",
            "stacked_bar", "stacked_bar", "heatmap", "heatmap", "table");
        // 存檔前 AnalyzeService 會把這次分析的來源寫進每張圖
        List<ChartSpec> saved = layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList();
        assertThatCode(() -> DashboardService.requireComplete(
            new DashboardConfig(null, "銷售 總覽", null, saved))).doesNotThrowAnyException();
    }

    @Test
    void noUsableChartLeavesOnlyTheTable() {
        List<Combo> combos = ConfigAssembler.combos(ConfigAssembler.candidates(evals), 3);
        List<ChartSpec> layout = ConfigAssembler.assemble(combos, Map.of(), 3, TABLE_COLUMNS);
        assertThat(layout).extracting(ChartSpec::type).containsExactly("table");
        assertThat(layout.get(0).gridPos()).isEqualTo(new GridPos(0, 0, 12, 5));
    }

    @Test
    void timeGrainFollowsDateSpan() {
        assertThat(ConfigAssembler.timeGrain(date("2024-01-01", "2024-03-01"))).isEqualTo("day");
        assertThat(ConfigAssembler.timeGrain(date("2024-01-05", "2024-06-20"))).isEqualTo("month");
        assertThat(ConfigAssembler.timeGrain(date("2020-01-01", "2024-06-20"))).isEqualTo("quarter");
        assertThat(ConfigAssembler.timeGrain(date(null, null))).isEqualTo("month");
    }

    static FieldSchema date(String min, String max) {
        return new FieldSchema("d", DATE, 2, List.of(), false, min, max);
    }

    @Test
    void evaluateMarksDirectlyRelevantFieldsAsFocus() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("rel_2", rel(2.2, 0.9)); // 四捨五入是 2：直接相關
        a.put("rel_3", rel(2.0, 0.4)); // 信心不足
        a.put("rel_4", rel(1.4, 0.9)); // 四捨五入是 1：間接相關
        assertThat(ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a)).extracting(FieldEval::name, FieldEval::focus)
            .containsExactly(tuple("order_date", false), tuple("amount", false), tuple("region", true),
                tuple("product", false), tuple("qty", false), tuple("memo", false));
    }

    @Test
    void focusFieldsLeadTheCandidatesEvenWhenUnimportant() {
        List<FieldEval> e = List.of(
            eval("amount", NUMBER, 12, 2.75, "measure"),
            focus(eval("region", STRING, 4, 0.25, "dimension")), // 不重要，但直接相關
            focus(eval("memo", STRING, 12, 3, "ignore")),        // 直接相關，但角色是 ignore
            eval("qty", NUMBER, 8, 0.25, "measure"));             // 不重要，也不相關
        assertThat(ConfigAssembler.candidates(e)).extracting(FieldEval::name).containsExactly("region", "amount");
    }

    @Test
    void atMostEightFocusFieldsAreKeptByImportance() {
        List<FieldEval> e = new ArrayList<>();
        for (int i = 1; i <= 9; i++) e.add(focus(eval("f" + i, NUMBER, 5, i, "measure")));
        e.add(eval("other", NUMBER, 5, 3, "measure"));
        assertThat(ConfigAssembler.candidates(e)).extracting(FieldEval::name)
            .containsExactly("f9", "f8", "f7", "f6", "f5", "f4", "f3", "f2");
    }

    @Test
    void combosWithFocusFieldsRankFirst() {
        Map<String, Answer> a = new HashMap<>(Fixtures.answers("round1.json"));
        a.put("rel_2", rel(2.0, 0.9)); // region 直接相關
        List<Combo> combos = ConfigAssembler.combos(
            ConfigAssembler.candidates(ConfigAssembler.evaluate(Fixtures.SALES_SCHEMA, a)), 4);
        assertThat(combos).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("region", "order_date", "amount"),
            List.of("region", "amount"),
            List.of("region", "order_date", "qty"),
            List.of("region", "amount", "product"),
            List.of("region", "qty"),
            List.of("region", "product", "qty"),
            List.of("order_date", "amount"),
            List.of("order_date", "amount", "product"));
    }

    @Test
    void firstAnalysisPutsTheTopFocusComboFirstWhenNoPickedChartUsesAFocusField() {
        Map<String, Answer> round2 = Map.of(
            "combo_0", choice("none", 0.9), "combo_1", choice("line", 0.9), "combo_2", choice("bar", 0.9));
        List<ChartSpec> layout = ConfigAssembler.assemble(regionFocusCombos(), round2, 2, TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::options).containsExactly(
            tuple("c1", "bar", Map.of("focus", true)), // 排最前面的焦點組合，用它第一個合法圖型
            tuple("c2", "line", Map.of()),             // 原本的第一張
            tuple("c3", "table", Map.of()));           // 原本的第二張拿掉，維持 K = 2
        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("region", null, null, "amount", null, null, "sum", null));
        assertThat(layout).extracting(ChartSpec::gridPos).containsExactly(
            new GridPos(0, 0, 6, 4), new GridPos(6, 0, 6, 4), new GridPos(0, 4, 12, 5));
    }

    @Test
    void firstAnalysisMarksChartsThatUseFocusFields() {
        Map<String, Answer> round2 = Map.of("combo_0", choice("pie", 0.9), "combo_1", choice("line", 0.9));
        List<ChartSpec> layout = ConfigAssembler.assemble(regionFocusCombos(), round2, 3, TABLE_COLUMNS);
        assertThat(layout).extracting(ChartSpec::type, ChartSpec::options).containsExactly(
            tuple("pie", Map.of("focus", true)), tuple("line", Map.of()), tuple("table", Map.of()));
    }

    @Test
    void keptDropsAChartOnlyWhenJevConfidentlySaysDrop() {
        List<ChartSpec> current = List.of(card("c1", "line", xy("order_date", "amount")),
            card("c2", "pie", xy("region", "amount")), card("c3", "bar", xy("product", "qty")), table("c4", List.of("region")));
        Map<String, Answer> round1 = Map.of(
            "keep_c1", choice("drop", 0.9),  // 移除
            "keep_c2", choice("drop", 0.4),  // 信心不足，保留
            "keep_c4", choice("drop", 0.9)); // 明細表不問，一律保留；c3 沒回答，保留
        assertThat(ConfigAssembler.kept(current, round1)).extracting(ChartSpec::chartId).containsExactly("c2", "c3", "c4");
    }

    @Test
    void adjustCombosAskOnlyFocusCombosAndSkipChartTypesTheKeptChartsAlreadyShow() {
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        FieldEval amount = eval("amount", NUMBER, 12, 2.75, "measure");
        FieldEval qty = eval("qty", NUMBER, 8, 1, "measure");
        FieldEval time = new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, "2024-01-05", "2024-06-20"), 3, "time");
        List<Combo> ranked = List.of(
            new Combo(List.of(region, amount), List.of("bar", "pie"), 2.375),
            new Combo(List.of(region, qty), List.of("bar", "pie"), 1.5),
            new Combo(List.of(time, amount), List.of("line", "bar"), 2.875)); // 沒有焦點欄位
        List<ChartSpec> kept = List.of(
            card("c1", "pie", xy("region", "amount")), // 同一組欄位已經有圓餅圖
            card("c2", "bar", xy("region", "qty")),
            card("c3", "pie", xy("region", "qty")),    // 第二組的 bar、pie 都有了，只剩 none，不問
            table("c4", List.of("region", "amount")));
        assertThat(ConfigAssembler.adjustCombos(ranked, kept)).extracting(ConfigAssemblerTest::names, Combo::chartTypes)
            .containsExactly(tuple(List.of("region", "amount"), List.of("bar")));
    }

    @Test
    void adjustCombosAskAtMostTwiceTheFreeSlots() {
        FieldEval region = focus(eval("region", STRING, 4, 2, "dimension"));
        List<Combo> ranked = List.of(
            new Combo(List.of(region, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar"), 2.375),
            new Combo(List.of(region, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar"), 1.5),
            new Combo(List.of(region, eval("cost", NUMBER, 8, 1, "measure")), List.of("bar"), 1.5));
        List<ChartSpec> kept = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> card("c" + i, "line", xy("order_date", "m" + i))).toList();
        assertThat(ConfigAssembler.adjustCombos(ranked, kept)).hasSize(2); // 2 ×（6 − 5）
    }

    @Test
    void adjustKeepsChartsAsTheyWereAppendsFocusChartsAndContinuesChartIds() {
        ChartSpec keptPie = new ChartSpec("c2", "pie", "amount 依 region", new GridPos(6, 0, 6, 4),
            xy("region", "amount"), Map.of("focus", true));
        ChartSpec keptTable = table("c5", List.of("region", "amount"));
        List<ChartSpec> current = List.of(card("c9", "line", xy("order_date", "amount")), keptPie, keptTable); // c9 這次被移除
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar", "pie"), 2.0),
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        Map<String, Answer> round2 = Map.of("combo_0", choice("bar", 0.9), "combo_1", choice("bar", 0.8));

        List<ChartSpec> layout = ConfigAssembler.adjust(current, List.of(keptPie, keptTable), combos, round2, Set.of("product"));

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos, ChartSpec::options).containsExactly(
            tuple("c2", "pie", new GridPos(0, 0, 6, 4), Map.of("focus", true)),  // 保留的圖設定不變，只重新排版
            tuple("c10", "bar", new GridPos(6, 0, 6, 4), Map.of("focus", true)), // 最大的是被移除的 c9，從 10 接下去
            tuple("c11", "bar", new GridPos(0, 4, 6, 4), Map.of("focus", true)),
            tuple("c5", "table", new GridPos(0, 8, 12, 5), Map.of()));           // 明細表放最後
        assertThat(layout.get(0).mapping()).isEqualTo(keptPie.mapping());
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("product", null, null, "amount", null, null, "sum", null));
        assertThat(layout.get(3).mapping()).isEqualTo(keptTable.mapping()); // 明細表沿用原本的欄位
        assertThat(ConfigAssembler.maxChartNumber(List.of(card("c3", "bar", xy("region", "amount")), table("c12", List.of("region")))))
            .isEqualTo(12); // 明細表的編號也算
    }

    @Test
    void adjustAddsAtMostSixCharts() {
        List<ChartSpec> kept = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> card("c" + i, "line", xy("order_date", "m" + i))).toList();
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar"), 2.0),
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar"), 1.125));
        Map<String, Answer> round2 = Map.of("combo_0", choice("bar", 0.9), "combo_1", choice("bar", 0.9));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, round2, Set.of("product")))
            .extracting(ChartSpec::chartId).containsExactly("c1", "c2", "c3", "c4", "c5", "c6");
    }

    @Test
    void adjustAddsNoGeneralChartsWhenAKeptChartAlreadyUsesAFocusField() {
        List<ChartSpec> kept = List.of(card("c1", "pie", xy("product", "amount")), table("c2", List.of("product")));
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, Map.of("combo_0", choice("none", 0.9)), Set.of("product")))
            .extracting(ChartSpec::chartId).containsExactly("c1", "c2");
    }

    @Test
    void adjustMarksKeptChartsThatUseAFocusFieldAndLeavesTheOthersAsTheyWere() {
        ChartSpec usesFocus = new ChartSpec("c1", "pie", "amount 依 product", new GridPos(0, 0, 6, 4),
            xy("product", "amount"), Map.of("color", "red"));
        ChartSpec noOptions = new ChartSpec("c2", "bar", "qty 依 product", new GridPos(6, 0, 6, 4),
            xy("product", "qty"), null);
        ChartSpec other = new ChartSpec("c3", "line", "amount 依 order_date", new GridPos(0, 4, 6, 4),
            xy("order_date", "amount"), Map.of("color", "blue"));
        ChartSpec keptTable = table("c4", List.of("product"));
        List<ChartSpec> kept = List.of(usesFocus, noOptions, other, keptTable);

        assertThat(ConfigAssembler.adjust(kept, kept, List.of(), Map.of(), Set.of("product")))
            .extracting(ChartSpec::chartId, ChartSpec::options).containsExactly(
                tuple("c1", Map.of("color", "red", "focus", true)), // 其他設定不動，只加 focus
                tuple("c2", Map.of("focus", true)),
                tuple("c3", Map.of("color", "blue")),              // 沒用到焦點欄位：原封不動
                tuple("c4", Map.of()));                            // 明細表不是圖，不標
    }

    @Test
    void adjustAddsTheTopFocusComboWhenNothingShowsTheFocus() {
        List<ChartSpec> kept = List.of(card("c1", "line", xy("order_date", "amount")), table("c2", List.of("order_date")));
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar", "pie"), 1.125));
        assertThat(ConfigAssembler.adjust(kept, kept, combos, Map.of(), Set.of("product")))
            .extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::options).containsExactly(
                tuple("c1", "line", Map.of()),
                tuple("c3", "bar", Map.of("focus", true)), // 加在保留的圖後面，用第一個合法圖型
                tuple("c2", "table", Map.of()));
    }

    @Test
    void layOutAndFocusKeepTheChartsDatasourceId() {
        UUID ds = UUID.randomUUID();
        ChartSpec kept = new ChartSpec("c1", "bar", "amount 依 region", new GridPos(6, 4, 6, 4),
            new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of(), ds);

        List<ChartSpec> out = ConfigAssembler.adjust(List.of(kept), List.of(kept), List.of(), Map.of(), Set.of("region"));

        // withFocus() 加上 focus、layOut() 重新排位置，兩步都要把來源帶過去
        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.datasourceId()).isEqualTo(ds);
            assertThat(s.options()).isEqualTo(Map.of("focus", true));
            assertThat(s.gridPos()).isEqualTo(new GridPos(0, 0, 6, 4));
        });
    }
}
