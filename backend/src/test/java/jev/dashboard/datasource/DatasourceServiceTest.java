package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;

class DatasourceServiceTest {
    @Test
    void apiSchemaUsesTheKeysOfEveryRowInFirstSeenOrder() {
        List<Datasource> saved = new ArrayList<>();
        DatasourceRepository repo = new DatasourceRepository(null, null, null) {
            @Override
            public void insert(Datasource d) {
                saved.add(d);
            }

            @Override
            public Optional<Datasource> find(UUID id, String userId) {
                return saved.stream().filter(d -> d.id().equals(id)).findFirst();
            }
        };
        // 上游常省略值是 null 的欄位：第一列沒有 region、memo
        DataSourceProvider api = new DataSourceProvider() {
            @Override
            public boolean supports(String kind) {
                return "api".equals(kind);
            }

            @Override
            public List<Map<String, Object>> fetchRows(Datasource ds) {
                return List.of(Map.of("amount", 1), Map.of("amount", 2, "region", "北區"), Map.of("memo", "x"));
            }
        };
        DatasourceService service = new DatasourceService(repo, List.of(api),
            new JevProperties(null, null, null, new JevProperties.Upload(100), null), new ApiUrlGuard(List.of("10.0.0.5")));

        Datasource ds = service.registerApi("u1", "erp", "http://10.0.0.5/sales", Map.of());

        assertThat(ds.schema()).extracting(FieldSchema::name).containsExactly("amount", "region", "memo");
    }
}
