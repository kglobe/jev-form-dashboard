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
import jev.dashboard.datasource.Ratio;
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

    /** 對照來源：sales 來自銷售，refund、qty 來自退貨；兩個來源都有 date 和 region。 */
    static final Map<String, Set<String>> SALES_VS_REFUNDS = Map.of(
        "sales", Set.of("date", "region", "sales"),
        "refund", Set.of("date", "region", "refund", "qty"),
        "qty", Set.of("date", "region", "refund", "qty"));

    /** 對照來源的候選欄位：date、region 是時間和分類，sales、refund、qty 是數值。 */
    static List<FieldEval> compareCandidates() {
        FieldEval time = new FieldEval(new FieldSchema("date", DATE, 9, List.of(), false, "2026-01-05", "2026-09-20"), 3, "time");
        return List.of(time, eval("sales", NUMBER, 9, 2.75, "measure"), eval("region", STRING, 3, 2, "dimension"),
            eval("refund", NUMBER, 5, 1, "measure"), eval("qty", NUMBER, 5, 1, "measure"));
    }

    @Test
    void comparePairsGetTimeAndCategoryCombosRankedFirst() {
        List<Combo> ranked = ConfigAssembler.ranked(compareCandidates(), SALES_VS_REFUNDS);

        // 跨來源的兩組（sales 配 refund、sales 配 qty）各配 date 和 region；refund 和 qty 同一個來源，不配
        assertThat(ranked).filteredOn(Combo::compare).extracting(ConfigAssemblerTest::names, Combo::chartTypes).containsExactly(
            tuple(List.of("date", "sales", "refund"), List.of("line", "bar")),
            tuple(List.of("date", "sales", "qty"), List.of("line", "bar")),
            tuple(List.of("region", "sales", "refund"), List.of("bar")),
            tuple(List.of("region", "sales", "qty"), List.of("bar")));
        assertThat(ranked.subList(0, 4)).allMatch(Combo::compare);
        // 沒有對照資訊就跟以前一樣
        assertThat(ConfigAssembler.ranked(compareCandidates(), Map.of())).noneMatch(Combo::compare);
    }

    @Test
    void focusCombosStillComeBeforeCompareCombos() {
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.set(2, focus(cands.get(2))); // region 是焦點欄位
        List<Combo> ranked = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);

        // 焦點層級裡對照組合排第一；焦點組合全部排完，才輪到沒有焦點的對照組合
        assertThat(names(ranked.get(0))).containsExactly("region", "sales", "refund");
        int focused = (int) ranked.stream().filter(Combo::hasFocus).count();
        assertThat(ranked.subList(0, focused)).allMatch(Combo::hasFocus);
        assertThat(names(ranked.get(focused))).containsExactly("date", "sales", "refund");
    }

    @Test
    void compareCombosBecomeTwoMeasureChartsThatPassValidation() {
        List<Combo> combos = ConfigAssembler.combos(compareCandidates(), 2, SALES_VS_REFUNDS);
        Map<String, Answer> round2 = Map.of("combo_0", choice("line", 0.9), "combo_2", choice("bar", 0.9));

        List<ChartSpec> layout = ConfigAssembler.assemble(combos, round2, 2, List.of("date", "sales", "region", "refund"));

        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month"));
        assertThat(layout.get(0).title()).isEqualTo("sales 與 refund 依 date");
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("region", null, null, null, List.of("sales", "refund"), null, "sum", null));
        assertThat(layout.get(1).title()).isEqualTo("sales 與 refund 依 region");
        DashboardConfig c = new DashboardConfig(null, "對照", null,
            layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList());
        assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
    }

    @Test
    void onACompareSourceNoComboUsesAFieldAMeasuresSourceLacks() {
        // region 只有銷售有；sales 來自銷售，refund、qty 來自退貨
        Map<String, Set<String>> regionOnlyInSales = Map.of(
            "sales", Set.of("date", "region", "sales"),
            "refund", Set.of("date", "refund", "qty"),
            "qty", Set.of("date", "refund", "qty"));

        List<Combo> ranked = ConfigAssembler.ranked(compareCandidates(), regionOnlyInSales);

        // 對照組合只配兩邊都有的 date
        assertThat(ranked).filteredOn(Combo::compare).extracting(ConfigAssemblerTest::names)
            .containsExactly(List.of("date", "sales", "refund"), List.of("date", "sales", "qty"));
        // 退貨的數值不跟 region 配（長條、圓餅、堆疊、熱力、雷達都一樣）：退貨的列沒有 region，畫出來是空的
        assertThat(ranked).extracting(ConfigAssemblerTest::names)
            .noneMatch(f -> f.contains("region") && (f.contains("refund") || f.contains("qty")));
        // 跨來源的兩個數值不畫散佈圖（同一列不會兩個都有），同一個來源的照舊
        assertThat(ranked).filteredOn(co -> co.chartTypes().contains("scatter")).extracting(ConfigAssemblerTest::names)
            .containsExactly(List.of("refund", "qty"));
        // 銷售的數值照舊可以依 region 畫
        assertThat(ranked).extracting(ConfigAssemblerTest::names).contains(List.of("sales", "region"));
    }

    static final Ratio RETURN_RATE = new Ratio("退貨率", "refund", "sales");

    /** 比率的 FieldEval，跟 ConfigAssembler.evaluate 做出來的一樣。 */
    static FieldEval ratio(Ratio r, double importance) {
        return new FieldEval(new FieldSchema(r.name(), NUMBER, 0, List.of(), false, null, null), importance, "measure", false, r);
    }

    @Test
    void evaluateAddsEachRatioAfterTheFieldsAsAMeasureWithItsOwnImportanceAndRelevance() {
        List<FieldSchema> schema = List.of(
            new FieldSchema("sales", NUMBER, 9, List.of(), false, null, null),
            new FieldSchema("refund", NUMBER, 5, List.of(), false, null, null));
        Ratio margin = new Ratio("毛利率", "refund", "sales");
        Map<String, Answer> answers = Map.of("imp_2", score(2.0), "rel_2", rel(2, 0.9), "imp_3", score(1.0));

        List<FieldEval> evals = ConfigAssembler.evaluate(schema, List.of(RETURN_RATE, margin), answers);

        assertThat(evals).extracting(FieldEval::name, FieldEval::importance, FieldEval::role, FieldEval::focus).containsExactly(
            tuple("sales", 0.0, "ignore", false), tuple("refund", 0.0, "ignore", false),
            tuple("退貨率", 2.0, "measure", true), tuple("毛利率", 1.0, "measure", false));
        assertThat(evals).extracting(FieldEval::ratio).containsExactly(null, null, RETURN_RATE, margin);
    }

    @Test
    void ratiosAreAlwaysCandidatesRightAfterTheFocusFields() {
        List<FieldEval> evals = List.of(
            eval("amount", NUMBER, 12, 3, "measure"),
            ratio(new Ratio("毛利率", "gross_profit", "amount"), 0),
            focus(eval("region", STRING, 4, 0, "dimension")),
            ratio(RETURN_RATE, 2),
            eval("memo", STRING, 12, 3, "ignore"));

        // 重要性是 0 的比率也進候選；比率之間照重要性排
        assertThat(ConfigAssembler.candidates(evals)).extracting(FieldEval::name)
            .containsExactly("region", "退貨率", "毛利率", "amount");
    }

    @Test
    void aRatioIsDrawnByTimeAsLineOrBarAndByCategoryAsBarOnly() {
        FieldEval date = compareCandidates().get(0);
        List<FieldEval> cands = List.of(date, eval("region", STRING, 4, 2, "dimension"),
            eval("sales", NUMBER, 9, 2, "measure"), eval("qty", NUMBER, 5, 1, "measure"), ratio(RETURN_RATE, 1));

        List<Combo> ranked = ConfigAssembler.ranked(cands);

        // 不出圓餅（region 只有 4 個分類也不出）、散佈、堆疊、熱力
        assertThat(ranked).filteredOn(c -> names(c).contains("退貨率"))
            .extracting(ConfigAssemblerTest::names, Combo::chartTypes).containsExactly(
                tuple(List.of("date", "退貨率"), List.of("line", "bar")),
                tuple(List.of("region", "退貨率"), List.of("bar")));
        // 比率不算進雷達圖的數值：只剩 sales、qty 兩個，不到三個
        assertThat(ranked).noneMatch(c -> c.chartTypes().contains("radar"));
        // region 配一般數值照舊有圓餅
        assertThat(ranked).filteredOn(c -> names(c).equals(List.of("region", "sales"))).singleElement()
            .extracting(Combo::chartTypes).isEqualTo(List.of("bar", "pie"));
    }

    @Test
    void ratioCombosShareTheCompareTierBetweenFocusCombosAndTheRest() {
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.add(ratio(RETURN_RATE, 0));

        List<Combo> ranked = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);

        // 同一層裡照平均重要性：四個對照組合在前，兩個比率組合接著；重要性更高的一般組合（例如 date 配 sales）排在後面
        assertThat(ranked.subList(0, 6)).allMatch(c -> c.compare() || names(c).contains("退貨率"));
        assertThat(names(ranked.get(4))).containsExactly("date", "退貨率");
        assertThat(names(ranked.get(5))).containsExactly("region", "退貨率");
        // 比率不跟其他數值組成對照組
        assertThat(ranked).filteredOn(c -> names(c).contains("退貨率")).allMatch(c -> c.fields().size() == 2);

        cands.set(2, focus(cands.get(2))); // region 是焦點欄位
        List<Combo> focused = ConfigAssembler.ranked(cands, SALES_VS_REFUNDS);
        assertThat(focused.subList(0, 3)).extracting(ConfigAssemblerTest::names).containsExactly(
            List.of("region", "sales", "refund"), List.of("region", "sales", "qty"), List.of("region", "退貨率"));
        assertThat(focused.get(3).hasFocus()).isTrue();
    }

    @Test
    void onACompareSourceARatioOnlyGoesWithFieldsBothOfItsSourcesHave() {
        // region 只有銷售有；退貨率的分子來自退貨、分母來自銷售，兩邊都有的只有 date
        Map<String, Set<String>> regionOnlyInSales = Map.of(
            "sales", Set.of("date", "region", "sales"),
            "refund", Set.of("date", "refund", "qty"),
            "qty", Set.of("date", "refund", "qty"));
        List<FieldEval> cands = new ArrayList<>(compareCandidates());
        cands.add(ratio(RETURN_RATE, 1));

        assertThat(ConfigAssembler.ranked(cands, regionOnlyInSales)).filteredOn(c -> names(c).contains("退貨率"))
            .extracting(ConfigAssemblerTest::names).containsExactly(List.of("date", "退貨率"));
    }

    @Test
    void ratioCombosBecomeSingleValueChartsThatPassValidation() {
        FieldEval date = compareCandidates().get(0);
        List<FieldEval> cands = List.of(date, eval("region", STRING, 4, 2, "dimension"), ratio(RETURN_RATE, 2));
        List<Combo> combos = ConfigAssembler.combos(cands, 2);
        Map<String, Answer> round2 = Map.of("combo_0", choice("line", 0.9), "combo_1", choice("bar", 0.9));

        List<ChartSpec> layout = ConfigAssembler.assemble(combos, round2, 2, List.of("date", "region"));

        assertThat(layout.get(0).mapping()).isEqualTo(new Mapping("date", null, null, "退貨率", null, null, "sum", "month"));
        assertThat(layout.get(0).title()).isEqualTo("退貨率 依 date");
        assertThat(layout.get(1).mapping()).isEqualTo(new Mapping("region", null, null, "退貨率", null, null, "sum", null));
        assertThat(layout.get(1).title()).isEqualTo("退貨率 依 region");
        DashboardConfig c = new DashboardConfig(null, "比率", null,
            layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList());
        assertThatCode(() -> DashboardService.requireComplete(c)).doesNotThrowAnyException();
    }

    // KPI spec 2.3：第一次分析自動放的 KPI 卡

    static FieldEval dateSpanning(String min, String max) {
        return new FieldEval(new FieldSchema("order_date", DATE, 12, List.of(), false, min, max), 3, "time");
    }

    static final FieldEval MONTHS = dateSpanning("2024-01-05", "2024-06-20");

    static ChartSpec kpiCard(String chartId, String value) {
        return new ChartSpec(chartId, "kpi", value, new GridPos(0, 0, 4, 2),
            new Mapping("order_date", null, null, value, null, null, "sum", "month"), Map.of());
    }

    @Test
    void noKpiWithoutATimeCandidateOrAnImportantMeasure() {
        assertThat(ConfigAssembler.kpis(List.of(eval("region", STRING, 4, 2, "dimension"), eval("amount", NUMBER, 12, 3, "measure"))))
            .isEmpty();
        // qty 四捨五入是 1（次要），不夠重要
        assertThat(ConfigAssembler.kpis(List.of(MONTHS, eval("qty", NUMBER, 8, 1.4, "measure")))).isEmpty();
    }

    @Test
    void kpisTakeFocusMeasuresFirstThenTheImportantOnesAtMostThree() {
        List<FieldEval> cands = List.of(
            eval("region", STRING, 4, 3, "dimension"),
            MONTHS,
            dateSpanning("2020-01-01", "2024-06-20"), // 第二個日期欄位不用
            eval("amount", NUMBER, 12, 2.75, "measure"),
            eval("qty", NUMBER, 8, 1, "measure"),            // 次要，不放
            focus(eval("refund", NUMBER, 5, 0.25, "measure")), // 不重要，但是焦點
            eval("cost", NUMBER, 8, 2.4, "measure"),
            eval("profit", NUMBER, 8, 1.5, "measure"));        // 四捨五入是 2，但已經 3 張

        List<ChartSpec> kpis = ConfigAssembler.kpis(cands);

        assertThat(kpis).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::title, ChartSpec::options).containsExactly(
            tuple("c1", "kpi", "refund", Map.of("focus", true)),
            tuple("c2", "kpi", "amount", Map.of()),
            tuple("c3", "kpi", "cost", Map.of()));
        assertThat(kpis.get(1).mapping()).isEqualTo(new Mapping("order_date", null, null, "amount", null, null, "sum", "month"));
        assertThat(kpis).extracting(ChartSpec::gridPos).containsOnlyNulls();
    }

    @Test
    void aRatioCanBeAKpiAndItsTitleIsTheValue() {
        List<ChartSpec> kpis = ConfigAssembler.kpis(List.of(MONTHS, ratio(RETURN_RATE, 2), ratio(new Ratio("毛利率", "gp", "sales"), 0)));
        assertThat(kpis).singleElement().satisfies(k -> {
            assertThat(k.mapping()).isEqualTo(new Mapping("order_date", null, null, "退貨率", null, null, "sum", "month"));
            assertThat(k.title()).isEqualTo("退貨率").isEqualTo(ConfigAssembler.title("kpi", k.mapping()));
        });
    }

    @Test
    void aKpiIsQuarterlyOverThreeYearsOfDataAndMonthlyOtherwise() {
        FieldEval amount = eval("amount", NUMBER, 12, 3, "measure");
        assertThat(ConfigAssembler.kpis(List.of(dateSpanning("2020-01-01", "2024-06-20"), amount)).get(0).mapping().timeGrain())
            .isEqualTo("quarter");
        // 三個月以內 timeGrain() 是 day，KPI 也用月
        assertThat(ConfigAssembler.kpis(List.of(dateSpanning("2024-01-01", "2024-03-01"), amount)).get(0).mapping().timeGrain())
            .isEqualTo("month");
        assertThat(ConfigAssembler.kpis(List.of(MONTHS, amount)).get(0).mapping().timeGrain()).isEqualTo("month");
    }

    @Test
    void assemblePutsTheKpisOnTheTopRowAndTheyDoNotCountTowardsK() {
        List<ChartSpec> kpis = ConfigAssembler.kpis(List.of(MONTHS, eval("amount", NUMBER, 12, 3, "measure"),
            eval("cost", NUMBER, 8, 2, "measure")));
        Map<String, Answer> round2 = Map.of("combo_1", choice("line", 0.9), "combo_2", choice("bar", 0.9));

        List<ChartSpec> layout = ConfigAssembler.assemble(kpis, regionFocusCombos(), round2, 2, TABLE_COLUMNS);

        assertThat(layout).extracting(ChartSpec::chartId, ChartSpec::type, ChartSpec::gridPos).containsExactly(
            tuple("c1", "kpi", new GridPos(0, 0, 4, 2)),
            tuple("c2", "kpi", new GridPos(4, 0, 4, 2)),
            tuple("c3", "bar", new GridPos(0, 2, 6, 4)),  // 焦點組合補在 KPI 之後的第一張
            tuple("c4", "line", new GridPos(6, 2, 6, 4)), // K = 2 張圖，KPI 不算
            tuple("c5", "table", new GridPos(0, 6, 12, 5)));
        List<ChartSpec> saved = layout.stream().map(s -> s.withDatasourceId(UUID.randomUUID())).toList();
        assertThatCode(() -> DashboardService.requireComplete(new DashboardConfig(null, "銷售 總覽", null, saved)))
            .doesNotThrowAnyException();
    }

    @Test
    void layOutPutsThreeKpisARowAndTheOtherChartsBelowThem() {
        List<ChartSpec> specs = List.of(card("c1", "line", xy("order_date", "amount")), kpiCard("c2", "a"), kpiCard("c3", "b"),
            table("c4", List.of("region")), kpiCard("c5", "c"), kpiCard("c6", "d"), card("c7", "bar", xy("region", "amount")),
            card("c8", "pie", xy("region", "amount")));

        assertThat(ConfigAssembler.layOut(specs)).extracting(ChartSpec::chartId, ChartSpec::gridPos).containsExactly(
            tuple("c2", new GridPos(0, 0, 4, 2)), tuple("c3", new GridPos(4, 0, 4, 2)), tuple("c5", new GridPos(8, 0, 4, 2)),
            tuple("c6", new GridPos(0, 2, 4, 2)),
            tuple("c1", new GridPos(0, 4, 6, 4)), tuple("c7", new GridPos(6, 4, 6, 4)), tuple("c8", new GridPos(0, 8, 6, 4)),
            tuple("c4", new GridPos(0, 12, 12, 5)));
    }

    @Test
    void adjustKeepsTheKpisOnTopAddsNoNewOnesAndDoesNotCountThemTowardsSix() {
        List<ChartSpec> kept = new ArrayList<>(List.of(kpiCard("c1", "amount"), kpiCard("c2", "qty"), kpiCard("c3", "cost")));
        IntStream.rangeClosed(4, 8).mapToObj(i -> card("c" + i, "line", xy("order_date", "m" + i))).forEach(kept::add);
        List<Combo> combos = List.of(
            new Combo(List.of(PRODUCT, eval("amount", NUMBER, 12, 2.75, "measure")), List.of("bar"), 2.0),
            new Combo(List.of(PRODUCT, eval("qty", NUMBER, 8, 1, "measure")), List.of("bar"), 1.125));
        Map<String, Answer> round2 = Map.of("combo_0", choice("bar", 0.9), "combo_1", choice("bar", 0.9));

        // 2 ×（6 − 5 張一般的圖），三張 KPI 不算
        assertThat(ConfigAssembler.adjustCombos(combos, kept)).hasSize(2);
        List<ChartSpec> layout = ConfigAssembler.adjust(kept, kept, combos, round2, Set.of("product"));
        assertThat(layout).extracting(ChartSpec::chartId)
            .containsExactly("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9");
        assertThat(layout.subList(0, 3)).extracting(ChartSpec::gridPos)
            .containsExactly(new GridPos(0, 0, 4, 2), new GridPos(4, 0, 4, 2), new GridPos(8, 0, 4, 2));
        assertThat(layout.get(3).gridPos()).isEqualTo(new GridPos(0, 2, 6, 4));
        assertThat(layout).filteredOn(s -> s.type().equals("kpi")).hasSize(3);
    }
}
