package jev.dashboard.jev;

import static jev.dashboard.datasource.FieldType.DATE;
import static jev.dashboard.datasource.FieldType.NUMBER;
import static jev.dashboard.datasource.FieldType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jev.dashboard.Fixtures;
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
}
