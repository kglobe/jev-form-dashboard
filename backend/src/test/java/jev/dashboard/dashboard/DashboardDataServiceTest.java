package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.DatasourceService;
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
}
