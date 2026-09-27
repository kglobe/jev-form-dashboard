package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DashboardServiceTest {
    static final GridPos POS = new GridPos(0, 0, 6, 4);
    static final UUID DS = UUID.randomUUID();

    static ChartSpec card(String type, Mapping m) {
        return new ChartSpec("c1", type, "t", POS, m, Map.of(), DS);
    }

    static ChartSpec at(GridPos pos) {
        return new ChartSpec("c1", "bar", "t", pos, x("region"), Map.of(), DS);
    }

    static Mapping x(String x) {
        return new Mapping(x, null, null, "amount", null, null, null, null);
    }

    static DashboardConfig config(ChartSpec... cards) {
        return new DashboardConfig(null, "銷售總覽", null, Arrays.asList(cards));
    }

    static void rejects(DashboardConfig c, String message) {
        assertThatThrownBy(() -> DashboardService.requireComplete(c))
            .isInstanceOf(ApiException.class)
            .hasMessage(message)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    static DashboardConfig withIntents(List<String> intents) {
        return new DashboardConfig(null, "銷售總覽", null, List.of(), intents, null);
    }

    @Test
    void blankNameIsRejected() {
        rejects(new DashboardConfig(null, "  ", null, List.of()), "儀表板名稱不能空白");
    }

    @Test
    void everyCardNeedsAUniqueChartId() {
        rejects(config((ChartSpec) null), "第 1 張圖表是空的");
        rejects(config(card("bar", x("region")), new ChartSpec(" ", "bar", "t", POS, x("region"), Map.of())),
            "第 2 張圖表缺少 chartId");
        rejects(config(card("bar", x("region")), card("pie", x("region"))), "chartId 重複：c1");
    }

    @Test
    void typeMustBeOneOfTheEight() {
        String message = "圖表 c1：type 必須是 line、bar、pie、scatter、table、radar、heatmap、stacked_bar 其中之一";
        rejects(config(card("donut", x("region"))), message);
        rejects(config(card(null, x("region"))), message);
    }

    @Test
    void mappingMustHaveTheFieldsTheTypeNeeds() {
        rejects(config(card("bar", null)), "圖表 c1：缺少 mapping");
        rejects(config(card("line", x(null))), "圖表 c1：line 需要 mapping.x");
        rejects(config(card("pie", x(" "))), "圖表 c1：pie 需要 mapping.x");
        rejects(config(card("stacked_bar", x("order_date"))), "圖表 c1：stacked_bar 需要 mapping.series");
        rejects(config(card("heatmap", x("region"))), "圖表 c1：heatmap 需要 mapping.y");
        rejects(config(card("scatter", x("amount"))), "圖表 c1：scatter 需要 mapping.y");
        rejects(config(card("radar", new Mapping(null, null, "region", null, List.of(), null, null, null))),
            "圖表 c1：radar 需要 mapping.measures");
        rejects(config(card("table", new Mapping(null, null, null, null, null, Arrays.asList("region", null), null, null))),
            "圖表 c1：table 需要 mapping.columns");
    }

    @Test
    void aggregateAndTimeGrainMustBeSupported() {
        rejects(config(card("bar", new Mapping("region", null, null, "amount", null, null, "median", null))),
            "圖表 c1：aggregate 必須是 sum、count、avg、none 其中之一");
        rejects(config(card("line", new Mapping("order_date", null, null, "amount", null, null, "sum", "week"))),
            "圖表 c1：timeGrain 必須是 day、month、quarter 其中之一");
    }

    @Test
    void gridPosMustFitTheTwelveColumnGrid() {
        rejects(config(at(null)), "圖表 c1：缺少 gridPos");
        for (GridPos bad : List.of(new GridPos(-1, 0, 6, 4), new GridPos(0, -1, 6, 4), new GridPos(0, 0, 0, 4),
                new GridPos(0, 0, 6, 0), new GridPos(7, 0, 6, 4), new GridPos(Integer.MAX_VALUE, 0, 1, 4))) {
            rejects(config(at(bad)), "圖表 c1：gridPos 要 x ≥ 0、y ≥ 0、w ≥ 1、h ≥ 1，而且 x + w ≤ 12");
        }
    }

    @Test
    void validCardsPass() {
        ChartSpec table = new ChartSpec("c2", "table", "明細", new GridPos(0, 4, 12, 5),
            new Mapping(null, null, null, null, null, List.of("region"), "none", null), Map.of(), DS);
        assertThatCode(() -> DashboardService.requireComplete(config(at(new GridPos(6, 0, 6, 4)), table)))
            .doesNotThrowAnyException();
    }

    @Test
    void intentsMustBeAtMostTenNonBlankSentencesOf200Chars() {
        rejects(withIntents(Collections.nCopies(11, "看營收")), "需求紀錄最多 10 句");
        rejects(withIntents(List.of("看營收", "  ")), "需求紀錄不能有空白的句子");
        rejects(withIntents(Arrays.asList("看營收", null)), "需求紀錄不能有空白的句子");
        rejects(withIntents(List.of("營".repeat(201))), "想看什麼最多 200 字");
        assertThatCode(() -> DashboardService.requireComplete(withIntents(Collections.nCopies(10, "營".repeat(200)))))
            .doesNotThrowAnyException();
        assertThatCode(() -> DashboardService.requireComplete(withIntents(null))).doesNotThrowAnyException();
    }

    @Test
    void everyCardNeedsADatasourceId() {
        rejects(config(card("bar", x("region")).withDatasourceId(null)), "圖表 c1：缺少 datasourceId");
    }

    @Test
    void basisGroupsNeedADatasourceIdAndValidIntents() {
        assertThatThrownBy(() -> DashboardService.requireValidBasis(Arrays.asList(new DashboardConfig.Basis(DS, List.of("看營收")), null)))
            .isInstanceOf(ApiException.class).hasMessage("依據缺少 datasourceId");
        assertThatThrownBy(() -> DashboardService.requireValidBasis(List.of(new DashboardConfig.Basis(null, List.of("看營收")))))
            .hasMessage("依據缺少 datasourceId");
        assertThatThrownBy(() -> DashboardService.requireValidBasis(List.of(new DashboardConfig.Basis(DS, List.of(" ")))))
            .hasMessage("需求紀錄不能有空白的句子");
        assertThatCode(() -> DashboardService.requireValidBasis(null)).doesNotThrowAnyException();
    }
}
