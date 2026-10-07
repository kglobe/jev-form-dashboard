package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.dashboard.DashboardDataService.FilterOptions;
import jev.dashboard.dashboard.Filters.Field;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.datasource.Ratio;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DashboardDataServiceTest {
    /** 今天是 2026-10-07。 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), ZoneOffset.UTC);
    static final GridPos POS = new GridPos(0, 0, 6, 4);
    static final UUID SALES = UUID.randomUUID();
    static final UUID RETURNS = UUID.randomUUID();
    /** 銷售只有 region 有顯示名稱，退貨只有 qty 有。 */
    static final Map<UUID, Datasource> TWO = Map.of(
        SALES, new Datasource(SALES, "銷售", "upload",
            List.of(new FieldSchema("region", FieldType.STRING, 2, List.of(), false, null, null, "地區")), null, Map.of(), "u1", null),
        RETURNS, new Datasource(RETURNS, "退貨", "upload",
            List.of(new FieldSchema("qty", FieldType.NUMBER, 2, List.of(), false, null, null, "數量")), null, Map.of(), "u1", null));
    static final Map<UUID, List<Map<String, Object>>> ROWS = Map.of(
        SALES, List.of(Map.of("region", "北區", "amount", 5.0)),
        RETURNS, List.of(Map.of("reason", "瑕疵", "qty", 2.0)));

    /** byId 裡的來源照常載入；fail 裡的來源載入時丟 502；不在 byId 的來源連名稱都查不到。loads 記每個來源被載入幾次。 */
    static DatasourceService sources(Map<UUID, Datasource> byId, Map<UUID, List<Map<String, Object>>> rows,
                                     Set<UUID> fail, Map<UUID, Integer> loads) {
        return new DatasourceService(null, List.of(),
                new JevProperties(null, null, null, new JevProperties.Upload(100), null), null) {
            @Override
            public Datasource get(String userId, UUID id) {
                if (!byId.containsKey(id)) throw new ApiException(HttpStatus.NOT_FOUND, "找不到資料源");
                return byId.get(id);
            }

            @Override
            public boolean exists(UUID id) {
                return byId.containsKey(id);
            }

            @Override
            public Function<UUID, Loaded> loader(String userId) {
                return id -> {
                    loads.merge(id, 1, Integer::sum);
                    Datasource ds = get(userId, id);
                    if (fail.contains(id)) throw new ApiException(HttpStatus.BAD_GATEWAY, "外部 API 無法連線");
                    return new Loaded(ds, rows.get(id));
                };
            }
        };
    }

    static ChartSpec bar(String chartId, UUID ds, String x, String value) {
        return new ChartSpec(chartId, "bar", value + " 依 " + x, POS,
            new Mapping(x, null, null, value, null, null, "sum", null), Map.of(), ds);
    }

    static DashboardConfig config(ChartSpec... layout) {
        return new DashboardConfig(null, "x", "u1", List.of(layout));
    }

    @Test
    void fieldIsMissingOnlyWhenNoRowHasIt() {
        UUID id = UUID.randomUUID();
        Datasource ds = new Datasource(id, "erp", "api", List.of(), "http://10.0.0.5/x", Map.of(), "u1", null);
        // 第一列省略了 amount（上游不送 null 欄位），第二列才有
        List<Map<String, Object>> rows = List.of(Map.of("region", "北區"), Map.of("region", "南區", "amount", 5.0));
        DatasourceService datasources = new DatasourceService(null, List.of(),
                new JevProperties(null, null, null, new JevProperties.Upload(100), null), null) {
            @Override
            public Function<UUID, Loaded> loader(String userId) {
                return datasourceId -> new Loaded(ds, rows);
            }
        };
        GridPos pos = new GridPos(0, 0, 6, 4);
        ChartSpec bar = new ChartSpec("c1", "bar", "t", pos, new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
        ChartSpec gone = new ChartSpec("c2", "bar", "t", pos, new Mapping("region", null, null, "profit", null, null, "sum", null), Map.of());

        List<ChartData> data = new DashboardDataService(datasources, CLOCK).load("u1", new DashboardConfig(null, "x", "u1", List.of(bar, gone)), null, Set.of());

        assertThat(data.get(0).error()).isNull();
        assertThat(data.get(0).rows()).isEqualTo(List.of(List.of("南區", 5.0)));
        assertThat(data.get(1).missingField()).isEqualTo("profit");
    }

    @Test
    void displayUsesFieldLabelsForDimensionsAndAutoTitles() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("region", FieldType.STRING, 2, List.of(), false, null, null, "地區"),
            new FieldSchema("amount", FieldType.NUMBER, 2, List.of(), false, null, null, "金額"));
        Datasource ds = new Datasource(id, "erp", "upload", schema, null, Map.of(), "u1", null);
        List<Map<String, Object>> rows = List.of(Map.of("region", "北區", "amount", 5.0));
        DatasourceService datasources = new DatasourceService(null, List.of(),
                new JevProperties(null, null, null, new JevProperties.Upload(100), null), null) {
            @Override
            public Function<UUID, Loaded> loader(String userId) {
                return datasourceId -> new Loaded(ds, rows);
            }
        };
        GridPos pos = new GridPos(0, 0, 6, 4);
        Mapping m = new Mapping("region", null, null, "amount", null, null, "sum", null);
        ChartSpec bar = new ChartSpec("c1", "bar", "amount 依 region", pos, m, Map.of());
        ChartSpec named = new ChartSpec("c2", "bar", "自己取的名字", pos, m, Map.of());
        DashboardConfig c = new DashboardConfig(null, "x", "u1", List.of(bar, named));

        List<ChartData> shown = new DashboardDataService(datasources, CLOCK).display("u1", c, null, Set.of());

        assertThat(shown.get(0).dimensions()).isEqualTo(List.of("地區", "金額"));
        assertThat(shown.get(0).title()).isEqualTo("金額 依 地區");
        assertThat(shown.get(1).title()).isNull();
        // 異常檢查走 load，維持原始名稱
        assertThat(new DashboardDataService(datasources, CLOCK).load("u1", c, null, Set.of()).get(0).dimensions()).isEqualTo(List.of("region", "amount"));
    }

    @Test
    void displayRelabelsBothMeasuresOfATwoMeasureChart() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 2, List.of(), false, null, null, "日期"),
            new FieldSchema("sales", FieldType.NUMBER, 1, List.of(), false, null, null, "銷售額"),
            new FieldSchema("refund", FieldType.NUMBER, 1, List.of(), false, null, null, "退貨額"));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null);
        List<Map<String, Object>> rows = List.of(
            Map.of("date", "2026-07-03", "sales", 100.0), Map.of("date", "2026-07-09", "refund", 5.0));
        DatasourceService datasources = sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>());
        Mapping m = new Mapping("date", null, null, null, List.of("sales", "refund"), null, "sum", "month");
        ChartSpec line = new ChartSpec("c1", "line", "sales 與 refund 依 date", POS, m, Map.of(), id);

        ChartData shown = new DashboardDataService(datasources, CLOCK).display("u1", config(line), null, Set.of()).get(0);

        assertThat(shown.dimensions()).isEqualTo(List.of("日期", "銷售額", "退貨額"));
        assertThat(shown.title()).isEqualTo("銷售額 與 退貨額 依 日期");
        assertThat(shown.rows()).isEqualTo(List.of(List.of("2026-07", 100.0, 5.0)));
    }

    @Test
    void eachSourceIsLoadedOnceAndEachCardUsesItsOwnSource() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(), loads), CLOCK);

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", SALES, "region", "amount")), null, Set.of());

        assertThat(loads).isEqualTo(Map.of(SALES, 1, RETURNS, 1));
        assertThat(shown).extracting(ChartData::chartId).containsExactly("c1", "c2", "c3");
        assertThat(shown).extracting(ChartData::source).containsExactly("銷售", "退貨", "銷售");
        // 顯示名稱各用各的 schema
        assertThat(shown.get(0).dimensions()).isEqualTo(List.of("地區", "amount"));
        assertThat(shown.get(1).dimensions()).isEqualTo(List.of("reason", "數量"));
        assertThat(shown.get(1).rows()).isEqualTo(List.of(List.of("瑕疵", 2.0)));
    }

    @Test
    void aFailingSourceOnlyBreaksItsOwnCards() {
        UUID gone = UUID.randomUUID();
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(RETURNS), new HashMap<>()), CLOCK);

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", gone, "region", "amount")), null, Set.of());

        assertThat(shown.get(0).error()).isNull();
        assertThat(shown.get(0).rows()).isEqualTo(List.of(List.of("北區", 5.0)));
        assertThat(shown.get(1)).isEqualTo(ChartData.sourceFailed("c2", "bar", "退貨"));
        assertThat(shown.get(2)).isEqualTo(ChartData.sourceDeleted("c3", "bar")); // 來源本身找不到，當作已刪除
    }

    @Test
    void whenEverySourceIsDeletedEachCardSaysSoInsteadOfThrowing() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(), new HashMap<>()), CLOCK);

        List<ChartData> shown = service.display("u1", config(
            bar("c1", UUID.randomUUID(), "region", "amount"), bar("c2", UUID.randomUUID(), "region", "amount")), null, Set.of());

        assertThat(shown).containsExactly(ChartData.sourceDeleted("c1", "bar"), ChartData.sourceDeleted("c2", "bar"));
    }

    @Test
    void aDeletedSourceDoesNotCountTowardsEverySourceFailing() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES), new HashMap<>()), CLOCK);

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", UUID.randomUUID(), "region", "amount")), null, Set.of());

        assertThat(shown.get(0)).isEqualTo(ChartData.sourceFailed("c1", "bar", "銷售"));
        assertThat(shown.get(1)).isEqualTo(ChartData.sourceDeleted("c2", "bar"));
    }

    @Test
    void whenEverySourceFailsTheFirstErrorIsThrownLikeBefore() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES, RETURNS), new HashMap<>()), CLOCK);

        assertThatThrownBy(() -> service.load("u1", config(bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty")), null, Set.of()))
            .isInstanceOf(ApiException.class)
            .hasMessage("外部 API 無法連線");
    }

    @Test
    void ratioChartsGetTheSourcesRatiosTheirTitleUsesTheXLabelAndBothViewsArePercent() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("date", FieldType.DATE, 3, List.of(), false, null, null, "日期"),
            new FieldSchema("sales", FieldType.NUMBER, 2, List.of(), false, null, null, "銷售額"),
            new FieldSchema("refund", FieldType.NUMBER, 1, List.of(), false, null, null, "退貨額"));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null)
            .withRatios(List.of(new Ratio("退貨率", "refund", "sales")));
        List<Map<String, Object>> rows = List.of(Map.of("date", "2026-06-10", "sales", 50.0),
            Map.of("date", "2026-07-03", "sales", 100.0), Map.of("date", "2026-07-09", "refund", 5.0));
        DashboardDataService service = new DashboardDataService(sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>()), CLOCK);
        ChartSpec line = new ChartSpec("c1", "line", "退貨率 依 date", POS,
            new Mapping("date", null, null, "退貨率", null, null, "sum", "month"), Map.of(), id);

        ChartData shown = service.display("u1", config(line), null, Set.of()).get(0);

        // 比率名稱是使用者取的，沒有顯示名稱；x 軸照舊換
        assertThat(shown.title()).isEqualTo("退貨率 依 日期");
        assertThat(shown.dimensions()).isEqualTo(List.of("日期", "退貨率"));
        assertThat(shown.rows()).isEqualTo(List.of(Arrays.asList("2026-06", null), List.of("2026-07", 0.05)));
        assertThat(shown.percent()).isTrue();
        ChartData loaded = service.load("u1", config(line), null, Set.of()).get(0);
        assertThat(loaded.dimensions()).isEqualTo(List.of("date", "退貨率"));
        assertThat(loaded.percent()).isTrue();
    }

    @Test
    void aRatioByCategoryIsAlignedOnTheSourcesDateField() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("region", FieldType.STRING, 1, List.of(), false, null, null, "地區"),
            new FieldSchema("date", FieldType.DATE, 3, List.of(), false, null, null, null),
            new FieldSchema("sales", FieldType.NUMBER, 2, List.of(), false, null, null, null),
            new FieldSchema("refund", FieldType.NUMBER, 1, List.of(), false, null, null, null));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null)
            .withRatios(List.of(new Ratio("退貨率", "refund", "sales")));
        List<Map<String, Object>> rows = List.of(Map.of("date", "2026-06-10", "region", "北區", "sales", 50.0),
            Map.of("date", "2026-07-03", "region", "北區", "sales", 100.0),
            Map.of("date", "2026-07-09", "region", "北區", "refund", 5.0));
        DashboardDataService service = new DashboardDataService(sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>()), CLOCK);

        ChartData shown = service.display("u1", config(bar("c1", id, "region", "退貨率")), null, Set.of()).get(0);

        // 退貨只有 7 月，6 月的銷售不算：5 ÷ 100
        assertThat(shown.rows()).isEqualTo(List.of(List.of("北區", 0.05)));
        assertThat(shown.ratioPeriod()).isEqualTo(new ChartData.RatioPeriod("2026-07", "2026-07"));
        assertThat(shown.source()).isEqualTo("對照");
    }

    static final UUID MONTHLY = UUID.randomUUID();
    static final ChartSpec LINE = new ChartSpec("c1", "line", "amount 依 d", POS,
        new Mapping("d", null, null, "amount", null, null, "sum", "month"), Map.of(), MONTHLY);

    static DashboardDataService monthly(Map<UUID, Integer> loads) {
        Datasource ds = new Datasource(MONTHLY, "銷售", "upload",
            List.of(new FieldSchema("amount", FieldType.NUMBER, 1, List.of(), false, null, null, "金額")), null, Map.of(), "u1", null);
        List<Map<String, Object>> rows = List.of(Map.of("d", "2026-07-01", "amount", 5.0));
        return new DashboardDataService(sources(Map.of(MONTHLY, ds), Map.of(MONTHLY, rows), Set.of(), loads), CLOCK);
    }

    static DashboardConfig saved(UUID id, ChartSpec... layout) {
        return new DashboardConfig(id, "x", "u1", List.of(layout));
    }

    @Test
    void theHighlightRightAfterDataReusesWhatDataLoadedOnce() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = monthly(loads);
        ChartSpec table = new ChartSpec("c2", "table", "明細", POS,
            new Mapping(null, null, null, null, null, List.of("d"), null, null), Map.of(), MONTHLY);
        UUID dashboard = UUID.randomUUID();

        service.display("u1", saved(dashboard, LINE, table), null, Set.of());
        // 異常檢查只要摘要得了的卡片，/data 剛讀過來源，不再讀一次；拿到的是原始欄位名稱
        List<ChartData> raw = service.load("u1", saved(dashboard, LINE), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 1));
        assertThat(raw).hasSize(1);
        assertThat(raw.get(0).dimensions()).isEqualTo(List.of("d", "amount"));
        assertThat(raw.get(0).rows()).isEqualTo(List.of(List.of("2026-07", 5.0)));

        // 只用一次，再問就重讀
        service.load("u1", saved(dashboard, LINE), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 2));
    }

    @Test
    void onlyTheSameUserAndDashboardWithTheSameCardsReuseTheData() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = monthly(loads);
        UUID dashboard = UUID.randomUUID();
        ChartSpec quarterly = new ChartSpec("c1", "line", "amount 依 d", POS,
            new Mapping("d", null, null, "amount", null, null, "sum", "quarter"), Map.of(), MONTHLY);

        // 預覽沒有 id，不留
        service.display("u1", config(LINE), null, Set.of());
        service.load("u1", saved(dashboard, LINE), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 2));

        // 卡片在兩次請求之間改了
        service.display("u1", saved(dashboard, LINE), null, Set.of());
        service.load("u1", saved(dashboard, quarterly), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 4));

        // 別的使用者、別的儀表板
        service.display("u1", saved(dashboard, LINE), null, Set.of());
        service.load("u2", saved(dashboard, LINE), null, Set.of());
        service.load("u1", saved(UUID.randomUUID(), LINE), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 7));
    }

    // 篩選器 spec 2：每張卡片轉換前先過濾資料列

    static final UUID ORDERS = UUID.randomUUID();
    /** 日期欄位照 schema 順序是 d、ship；region 的顯示名稱是「地區」，product 沒有顯示名稱。 */
    static final Datasource ORDERS_DS = new Datasource(ORDERS, "訂單", "upload", List.of(
        new FieldSchema("d", FieldType.DATE, 4, List.of(), false, null, null, "訂單日"),
        new FieldSchema("ship", FieldType.DATE, 4, List.of(), false, null, null),
        new FieldSchema("region", FieldType.STRING, 3, List.of(), false, null, null, "地區"),
        new FieldSchema("product", FieldType.STRING, 2, List.of(), false, null, null),
        new FieldSchema("amount", FieldType.NUMBER, 5, List.of(), false, null, null)), null, Map.of(), "u1", null);
    /** 不可修改的清單和列：過濾時要是動到 loader 共用的這一份就會丟例外。 */
    static final List<Map<String, Object>> ORDER_ROWS = List.of(
        row("d", "2026-07-01", "ship", "2026-10-02", "region", "北區", "product", "A", "amount", 1.0),
        row("d", "2026-09-30", "ship", "2026-10-15", "region", "南區", "product", "B", "amount", 2.0),
        row("d", "2026-10-01", "ship", null, "region", null, "product", "A", "amount", 4.0),
        row("d", "2026-06-30", "ship", "2026-07-01", "region", "北區", "product", "B", "amount", 8.0),
        row("d", null, "ship", null, "region", "中區", "product", "A", "amount", 16.0));
    static final ChartSpec BY_PRODUCT = bar("c1", ORDERS, "product", "amount");

    /** 值可以是 null 的一列。 */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    static ChartSpec monthlyOn(String chartId, String x) {
        return new ChartSpec(chartId, "line", "amount 依 " + x, POS,
            new Mapping(x, null, null, "amount", null, null, "sum", "month"), Map.of(), ORDERS);
    }

    static Filters by(String field, String... values) {
        return new Filters(null, List.of(new Field(field, List.of(values))));
    }

    static List<ChartData> orders(Filters f, ChartSpec... cards) {
        return new DashboardDataService(sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK)
            .display("u1", config(cards), f, Set.of());
    }

    @Test
    void aCategoryKeepsRowsWithAnyOfItsValuesAndBlankIsAValue() {
        assertThat(orders(null, BY_PRODUCT).get(0).rows()).isEqualTo(List.of(List.of("A", 21.0), List.of("B", 10.0)));
        assertThat(orders(by("地區", "北區"), BY_PRODUCT).get(0).rows()).isEqualTo(List.of(List.of("B", 8.0), List.of("A", 1.0)));
        assertThat(orders(by("地區", "北區", "南區"), BY_PRODUCT).get(0).rows())
            .isEqualTo(List.of(List.of("B", 10.0), List.of("A", 1.0)));
        assertThat(orders(by("地區", UniversalTransformer.BLANK), BY_PRODUCT).get(0).rows()).isEqualTo(List.of(List.of("A", 4.0)));
        assertThat(orders(by("地區", "北區"), BY_PRODUCT).get(0).filterSkipped()).isNull();
    }

    @Test
    void categoriesAndThePeriodAllHaveToMatch() {
        Filters twoCategories = new Filters(null, List.of(new Field("地區", List.of("北區")), new Field("product", List.of("B"))));
        assertThat(orders(twoCategories, BY_PRODUCT).get(0).rows()).isEqualTo(List.of(List.of("B", 8.0)));

        // 沒有 timeGrain：用第一個日期欄位 d；上季是 2026-07-01～09-30，日期是空的列不算
        assertThat(orders(new Filters("last_quarter", null), BY_PRODUCT).get(0).rows())
            .isEqualTo(List.of(List.of("B", 2.0), List.of("A", 1.0)));
        Filters both = new Filters("last_quarter", List.of(new Field("地區", List.of("北區"))));
        assertThat(orders(both, BY_PRODUCT).get(0).rows()).isEqualTo(List.of(List.of("A", 1.0)));
    }

    @Test
    void thePeriodUsesTheCardsTimeGrainFieldSoTwoCardsOnOneSourceFilterApart() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), loads), CLOCK);

        // 本月是 2026-10：出貨日在 10 月的是第 1、2 列，訂單日在 10 月的只有第 3 列
        List<ChartData> shown = service.display("u1", config(monthlyOn("c1", "ship"), monthlyOn("c2", "d"), BY_PRODUCT),
            new Filters("this_month", null), Set.of());

        assertThat(loads).isEqualTo(Map.of(ORDERS, 1));
        assertThat(shown.get(0).rows()).isEqualTo(List.of(List.of("2026-10", 3.0)));
        assertThat(shown.get(1).rows()).isEqualTo(List.of(List.of("2026-10", 4.0)));
        assertThat(shown.get(2).rows()).isEqualTo(List.of(List.of("A", 4.0)));
        assertThat(ORDER_ROWS).hasSize(5);
    }

    @Test
    void aKpiOnlyGetsEndedPeriodsFromTheFilteredRowsAndItsTitleUsesTheValueLabel() {
        ChartSpec kpi = new ChartSpec("c1", "kpi", "amount", new GridPos(0, 0, 4, 2),
            new Mapping("d", null, null, "amount", null, null, "sum", "month"), Map.of(), ORDERS);
        Datasource labelled = ORDERS_DS.withSchema(ORDERS_DS.schema().stream()
            .map(f -> f.name().equals("amount") ? f.withLabel("金額") : f).toList());
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, labelled), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK);

        // 今天 2026-10-07：10 月還沒結束，那一列不算；沒有日期的列也不算
        ChartData all = service.display("u1", config(kpi), null, Set.of()).get(0);
        assertThat(all.rows()).isEqualTo(List.of(List.of("2026-06", 8.0), List.of("2026-07", 1.0), List.of("2026-09", 2.0)));
        assertThat(all.dimensions()).isEqualTo(List.of("訂單日", "金額"));
        assertThat(all.title()).isEqualTo("金額");
        // 上季配月：本期是上季最後一個已經結束的月，6 月在上季之外
        assertThat(service.display("u1", config(kpi), new Filters("last_quarter", null), Set.of()).get(0).rows())
            .isEqualTo(List.of(List.of("2026-07", 1.0), List.of("2026-09", 2.0)));
        // 異常檢查拿到的也是已經結束的期
        assertThat(service.load("u1", config(kpi), null, Set.of()).get(0).rows().getLast()).isEqualTo(List.of("2026-09", 2.0));
    }

    @Test
    void aTimeGrainFieldThatIsNotADateSaysSoUnderAPeriod() {
        ChartData shown = orders(new Filters("this_month", null), monthlyOn("c1", "region")).get(0);

        assertThat(shown.error()).isEqualTo("NOT_A_DATE");
        assertThat(shown.missingField()).isEqualTo("region");
    }

    @Test
    void aFilterTheSourceHasNoFieldForIsSkippedAndTheOthersStillApply() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(), new HashMap<>()), CLOCK);
        Filters f = new Filters("this_year", List.of(new Field("地區", List.of("南區")), new Field("產品", List.of("A"))));

        ChartData shown = service.display("u1", config(bar("c1", SALES, "region", "amount")), f, Set.of()).get(0);

        // 地區對得到 region，照樣篩掉北區；產品找不到欄位、來源沒有日期欄位，都不生效
        assertThat(shown.rows()).isEmpty();
        assertThat(shown.filterSkipped()).containsExactly("產品", "期間");
    }

    @Test
    void categoriesMatchFieldsByTheirOnScreenNameAcrossSources() {
        UUID shops = UUID.randomUUID();
        Map<UUID, Datasource> byId = new HashMap<>(TWO);
        byId.put(shops, new Datasource(shops, "分店", "upload",
            List.of(new FieldSchema("地區", FieldType.STRING, 2, List.of(), false, null, null)), null, Map.of(), "u1", null));
        Map<UUID, List<Map<String, Object>>> rows = new HashMap<>(ROWS);
        rows.put(shops, List.of(Map.of("地區", "北區", "amount", 1.0), Map.of("地區", "南區", "amount", 2.0)));
        DashboardDataService service = new DashboardDataService(sources(byId, rows, Set.of(), new HashMap<>()), CLOCK);

        List<ChartData> shown = service.display("u1", config(bar("c1", SALES, "region", "amount"),
            bar("c2", shops, "地區", "amount"), bar("c3", RETURNS, "reason", "qty")), by("地區", "南區"), Set.of());

        // 銷售的 region 顯示名稱是「地區」，分店的欄位本來就叫「地區」，一起篩；退貨沒有這個欄位
        assertThat(shown.get(0).rows()).isEmpty();
        assertThat(shown.get(0).filterSkipped()).isNull();
        assertThat(shown.get(1).rows()).isEqualTo(List.of(List.of("南區", 2.0)));
        assertThat(shown.get(2).rows()).isEqualTo(List.of(List.of("瑕疵", 2.0)));
        assertThat(shown.get(2).filterSkipped()).containsExactly("地區");
        // 只對文字欄位：退貨的 qty 顯示名稱是「數量」，但它是數字欄位
        assertThat(service.display("u1", config(bar("c3", RETURNS, "reason", "qty")), by("數量", "2"), Set.of()).get(0).filterSkipped())
            .containsExactly("數量");
    }

    @Test
    void aRatioIsAlignedOnTheFilteredRows() {
        UUID id = UUID.randomUUID();
        List<FieldSchema> schema = List.of(
            new FieldSchema("region", FieldType.STRING, 2, List.of(), false, null, null, "地區"),
            new FieldSchema("date", FieldType.DATE, 6, List.of(), false, null, null),
            new FieldSchema("sales", FieldType.NUMBER, 4, List.of(), false, null, null),
            new FieldSchema("refund", FieldType.NUMBER, 2, List.of(), false, null, null));
        Datasource ds = new Datasource(id, "對照", "merged", schema, null, Map.of(), "u1", null)
            .withRatios(List.of(new Ratio("退貨率", "refund", "sales")));
        List<Map<String, Object>> rows = List.of(
            Map.of("date", "2026-06-10", "region", "北區", "sales", 50.0),
            Map.of("date", "2026-07-03", "region", "北區", "sales", 100.0),
            Map.of("date", "2026-07-09", "region", "北區", "refund", 5.0),
            Map.of("date", "2026-05-03", "region", "南區", "sales", 40.0),
            Map.of("date", "2026-06-09", "region", "南區", "refund", 2.0),
            Map.of("date", "2026-06-20", "region", "南區", "sales", 20.0));
        DashboardDataService service = new DashboardDataService(sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>()), CLOCK);
        ChartSpec card = bar("c1", id, "region", "退貨率");

        // 全部的列：退貨 6～7 月、銷售 5～7 月，對齊到 6～7 月
        assertThat(service.display("u1", config(card), null, Set.of()).get(0).ratioPeriod())
            .isEqualTo(new ChartData.RatioPeriod("2026-06", "2026-07"));
        // 只看南區：退貨只有 6 月，對齊到 6 月，5 月的銷售不算
        ChartData south = service.display("u1", config(card), by("地區", "南區"), Set.of()).get(0);
        assertThat(south.ratioPeriod()).isEqualTo(new ChartData.RatioPeriod("2026-06", "2026-06"));
        assertThat(south.rows()).isEqualTo(List.of(List.of("南區", 0.1)));
    }

    @Test
    void theDataKeptForHighlightIsKeyedByTheFilters() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = monthly(loads);
        UUID dashboard = UUID.randomUUID();
        Filters lastYear = new Filters("last_year", null);

        // 有篩選的 /data 留下來的是篩選過的資料，沒篩選的 /highlight 不能拿去用
        service.display("u1", saved(dashboard, LINE), lastYear, Set.of());
        List<ChartData> raw = service.load("u1", saved(dashboard, LINE), null, Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 2));
        assertThat(raw.get(0).rows()).isEqualTo(List.of(List.of("2026-07", 5.0)));

        // 同一組篩選（不同的物件）就沿用，異常檢查拿到的是篩選後的資料
        service.display("u1", saved(dashboard, LINE), lastYear, Set.of());
        List<ChartData> filtered = service.load("u1", saved(dashboard, LINE), new Filters("last_year", null), Set.of());
        assertThat(loads).isEqualTo(Map.of(MONTHLY, 3));
        assertThat(filtered.get(0).rows()).isEmpty();
    }

    @Test
    void loadFiltersTooSoTheHighlightSeesWhatTheScreenShows() {
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK);

        ChartData raw = service.load("u1", config(monthlyOn("c1", "d")), by("地區", "北區"), Set.of()).get(0);

        assertThat(raw.dimensions()).isEqualTo(List.of("d", "amount"));
        assertThat(raw.rows()).isEqualTo(List.of(List.of("2026-06", 8.0), List.of("2026-07", 1.0)));
    }

    @Test
    void filterOptionsMergeTextFieldsByOnScreenNameAndSkipSourcesThatFail() {
        UUID shops = UUID.randomUUID();
        Map<UUID, Datasource> byId = new HashMap<>(TWO);
        byId.put(shops, new Datasource(shops, "分店", "upload", List.of(
            new FieldSchema("地區", FieldType.STRING, 3, List.of(), false, null, null),
            new FieldSchema("memo", FieldType.STRING, 201, List.of(), false, null, null, "備註"),
            new FieldSchema("amount", FieldType.NUMBER, 1, List.of(), false, null, null)), null, Map.of(), "u1", null));
        List<Map<String, Object>> shopRows = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            shopRows.add(row("地區", i == 0 ? "南區" : i == 1 ? null : "中區", "memo", "備註" + i, "amount", 1.0));
        }
        Map<UUID, List<Map<String, Object>>> rows = new HashMap<>(ROWS);
        rows.put(shops, shopRows);
        DashboardDataService service = new DashboardDataService(sources(byId, rows, Set.of(RETURNS), new HashMap<>()), CLOCK);

        FilterOptions options = service.filterOptions("u1", config(bar("c1", SALES, "region", "amount"),
            bar("c2", shops, "地區", "amount"), bar("c3", RETURNS, "reason", "qty"), bar("c4", UUID.randomUUID(), "x", "y")), false);

        // 只有文字欄位；銷售的 region 和分店的地區合併，值照字串排、「（空白）」最後；備註有 201 個值，不列
        assertThat(options.fields()).containsExactly(new Field("地區", List.of("中區", "北區", "南區", UniversalTransformer.BLANK)));
    }

    @Test
    void filterOptionsListFieldsInCardThenSchemaOrder() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), loads), CLOCK);

        FilterOptions options = service.filterOptions("u1", config(BY_PRODUCT, monthlyOn("c2", "d")), false);

        assertThat(loads).isEqualTo(Map.of(ORDERS, 1));
        assertThat(options.fields()).containsExactly(
            new Field("地區", List.of("中區", "北區", "南區", UniversalTransformer.BLANK)), new Field("product", List.of("A", "B")));
    }

    @Test
    void filterOptionsAreEmptyWhenEverySourceFails() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES, RETURNS), new HashMap<>()), CLOCK);

        assertThat(service.filterOptions("u1", config(bar("c1", SALES, "region", "amount")), false).fields()).isEmpty();
    }

    @Test
    void filterOptionsLeaveOutWhatTheFiltersWouldReject() {
        UUID id = UUID.randomUUID();
        String longLabel = "欄".repeat(51);
        Datasource ds = new Datasource(id, "長", "upload", List.of(
            new FieldSchema("note", FieldType.STRING, 1, List.of(), false, null, null, longLabel),
            new FieldSchema("region", FieldType.STRING, 2, List.of(), false, null, null)), null, Map.of(), "u1", null);
        List<Map<String, Object>> rows = List.of(Map.of("note", "x", "region", "北區"), Map.of("note", "y", "region", "值".repeat(201)),
            Map.of("note", "z", "region", "值".repeat(200)));
        DashboardDataService service = new DashboardDataService(sources(Map.of(id, ds), Map.of(id, rows), Set.of(), new HashMap<>()), CLOCK);

        // 顯示名稱超過 50 字的欄位整欄不列，超過 200 字的值只拿掉那個值（篩選器 spec 3 的上限）
        assertThat(service.filterOptions("u1", config(bar("c1", id, "region", "amount")), false).fields())
            .containsExactly(new Field("region", List.of("值".repeat(200), "北區")));
    }

    static DashboardConfig withDefault(Filters defaults, ChartSpec... layout) {
        return new DashboardConfig(null, "x", "u1", List.of(layout), null, null, defaults);
    }

    @Test
    void someoneOtherThanTheOwnerOnlyGetsFieldsTheCardsUseOrTheDefaultFilters() {
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK);
        Field product = new Field("product", List.of("A", "B"));
        Field region = new Field("地區", List.of("中區", "北區", "南區", UniversalTransformer.BLANK));

        // BY_PRODUCT 用到 product、amount；地區沒有卡片用到
        assertThat(service.filterOptions("u1", config(BY_PRODUCT), false).fields()).containsExactly(region, product);
        assertThat(service.filterOptions("u1", config(BY_PRODUCT), true).fields()).containsExactly(product);
        // 預設篩選裡的欄位照畫面上的名稱算進來
        assertThat(service.filterOptions("u1", withDefault(by("地區", "北區"), BY_PRODUCT), true).fields()).containsExactly(region, product);

        Filters both = new Filters(null, List.of(new Field("地區", List.of("北區")), new Field("product", List.of("A"))));
        assertThat(service.denied("u1", config(BY_PRODUCT), both)).containsExactly("地區");
        assertThat(service.denied("u1", withDefault(by("地區", "南區"), BY_PRODUCT), both)).isEmpty();
        assertThat(service.denied("u1", config(BY_PRODUCT), null)).isEmpty();
    }

    @Test
    void aDeniedFieldIsSkippedOnEveryCardAndSlicesNothing() {
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), new HashMap<>()), CLOCK);
        Filters both = new Filters(null, List.of(new Field("地區", List.of("北區")), new Field("product", List.of("A"))));

        List<ChartData> shown = service.display("u1", config(BY_PRODUCT, monthlyOn("c2", "d")), both, Set.of("地區"));

        // 只剩 product = A 生效：第 1、3、5 列
        assertThat(shown).extracting(ChartData::filterSkipped).containsExactly(List.of("地區"), List.of("地區"));
        assertThat(shown.get(0).rows()).isEqualTo(List.of(List.of("A", 21.0)));
    }

    @Test
    void theDataKeptForHighlightIsKeyedByWhatWasDeniedToo() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(
            sources(Map.of(ORDERS, ORDERS_DS), Map.of(ORDERS, ORDER_ROWS), Set.of(), loads), CLOCK);
        UUID dashboard = UUID.randomUUID();
        DashboardConfig saved = new DashboardConfig(dashboard, "x", "u1", List.of(monthlyOn("c1", "d")));
        Filters north = by("地區", "北區");

        // 擁有者的 /data 篩過地區，檢視者的 /highlight 不能拿去用
        service.display("u1", saved, north, Set.of());
        List<ChartData> raw = service.load("u1", saved, north, Set.of("地區"));

        assertThat(loads).isEqualTo(Map.of(ORDERS, 2));
        assertThat(raw.get(0).rows()).isEqualTo(List.of(List.of("2026-06", 8.0), List.of("2026-07", 1.0), List.of("2026-09", 2.0),
            List.of("2026-10", 4.0)));
    }

    @Test
    void aCardNoFilterAppliesToGetsTheSharedRowsWithoutACopy() {
        // 篩選對這張卡都不生效時直接用 loader 的那份；複製會用到 stream()
        List<Map<String, Object>> rows = new ArrayList<>(ROWS.get(SALES)) {
            @Override
            public java.util.stream.Stream<Map<String, Object>> stream() {
                throw new AssertionError("不該複製資料列");
            }
        };
        DashboardDataService service = new DashboardDataService(sources(TWO, Map.of(SALES, rows), Set.of(), new HashMap<>()), CLOCK);

        ChartData shown = service.display("u1", config(bar("c1", SALES, "region", "amount")), by("產品", "A"), Set.of()).get(0);

        assertThat(shown.rows()).isEqualTo(List.of(List.of("北區", 5.0)));
        assertThat(shown.filterSkipped()).containsExactly("產品");
    }
}
