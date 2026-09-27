package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.Mapping;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DashboardDataServiceTest {
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
            public Loaded load(String userId, UUID id) {
                loads.merge(id, 1, Integer::sum);
                Datasource ds = get(userId, id);
                if (fail.contains(id)) throw new ApiException(HttpStatus.BAD_GATEWAY, "外部 API 無法連線");
                return new Loaded(ds, rows.get(id));
            }
        };
    }

    static ChartSpec bar(String chartId, UUID ds, String x, String value) {
        return new ChartSpec(chartId, "bar", value + " 依 " + x, POS,
            new Mapping(x, null, null, value, null, null, "sum", null), Map.of(), ds);
    }

    static DashboardConfig config(ChartSpec... layout) {
        return new DashboardConfig(null, "x", null, "u1", List.of(layout));
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
            public Loaded load(String userId, UUID datasourceId) {
                return new Loaded(ds, rows);
            }
        };
        GridPos pos = new GridPos(0, 0, 6, 4);
        ChartSpec bar = new ChartSpec("c1", "bar", "t", pos, new Mapping("region", null, null, "amount", null, null, "sum", null), Map.of());
        ChartSpec gone = new ChartSpec("c2", "bar", "t", pos, new Mapping("region", null, null, "profit", null, null, "sum", null), Map.of());

        List<ChartData> data = new DashboardDataService(datasources).load("u1", new DashboardConfig(null, "x", id, "u1", List.of(bar, gone)));

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
            public Loaded load(String userId, UUID datasourceId) {
                return new Loaded(ds, rows);
            }
        };
        GridPos pos = new GridPos(0, 0, 6, 4);
        Mapping m = new Mapping("region", null, null, "amount", null, null, "sum", null);
        ChartSpec bar = new ChartSpec("c1", "bar", "amount 依 region", pos, m, Map.of());
        ChartSpec named = new ChartSpec("c2", "bar", "自己取的名字", pos, m, Map.of());
        DashboardConfig c = new DashboardConfig(null, "x", id, "u1", List.of(bar, named));

        List<ChartData> shown = new DashboardDataService(datasources).display("u1", c);

        assertThat(shown.get(0).dimensions()).isEqualTo(List.of("地區", "金額"));
        assertThat(shown.get(0).title()).isEqualTo("金額 依 地區");
        assertThat(shown.get(1).title()).isNull();
        // 異常檢查走 load，維持原始名稱
        assertThat(new DashboardDataService(datasources).load("u1", c).get(0).dimensions()).isEqualTo(List.of("region", "amount"));
    }

    @Test
    void eachSourceIsLoadedOnceAndEachCardUsesItsOwnSource() {
        Map<UUID, Integer> loads = new HashMap<>();
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(), loads));

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", SALES, "region", "amount")));

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
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(RETURNS), new HashMap<>()));

        List<ChartData> shown = service.display("u1", config(
            bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"), bar("c3", gone, "region", "amount")));

        assertThat(shown.get(0).error()).isNull();
        assertThat(shown.get(0).rows()).isEqualTo(List.of(List.of("北區", 5.0)));
        assertThat(shown.get(1)).isEqualTo(ChartData.sourceFailed("c2", "bar", "退貨"));
        assertThat(shown.get(2)).isEqualTo(ChartData.sourceFailed("c3", "bar", null)); // 來源本身找不到，沒有名稱
    }

    @Test
    void whenEverySourceFailsTheFirstErrorIsThrownLikeBefore() {
        DashboardDataService service = new DashboardDataService(sources(TWO, ROWS, Set.of(SALES, RETURNS), new HashMap<>()));

        assertThatThrownBy(() -> service.load("u1", config(bar("c1", SALES, "region", "amount"), bar("c2", RETURNS, "reason", "qty"))))
            .isInstanceOf(ApiException.class)
            .hasMessage("外部 API 無法連線");
    }
}
