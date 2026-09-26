package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

class DashboardDataServiceTest {
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
}
