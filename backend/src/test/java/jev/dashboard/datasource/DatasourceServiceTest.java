package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatasourceServiceTest {
    /** 只記在 saved 裡的假 repo。 */
    private static DatasourceRepository repo(List<Datasource> saved) {
        return new DatasourceRepository(null, null, null) {
            @Override
            public void insert(Datasource d) {
                saved.add(d);
            }

            @Override
            public Optional<Datasource> find(UUID id, String userId) {
                return saved.stream().filter(d -> d.id().equals(id)).findFirst();
            }
        };
    }

    @Test
    void apiSchemaUsesTheKeysOfEveryRowInFirstSeenOrder() {
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
        DatasourceService service = new DatasourceService(repo(new ArrayList<>()), List.of(api),
            new JevProperties(null, null, null, new JevProperties.Upload(100), null), new ApiUrlGuard(List.of("10.0.0.5")));

        Datasource ds = service.registerApi("u1", "erp", "http://10.0.0.5/sales", Map.of());

        assertThat(ds.schema()).extracting(FieldSchema::name).containsExactly("amount", "region", "memo");
        assertThat(ds.rowCount()).isEqualTo(3);
    }

    @Test
    void removingASecretBindingStopsTheRegisteredSourceOnItsNextFetch() { // 密鑰綁定 spec 3.2
        List<Datasource> saved = new ArrayList<>();
        DatasourceRepository repo = repo(saved);
        MockEnvironment env = new MockEnvironment().withProperty("DS_ERP_TOKEN", "Bearer t0k");
        List<Map<String, String>> sent = new ArrayList<>();
        // 每次都是重啟後端讀到的新設定；fetchFrom 不真的連線，只記下送出去的 header
        Function<Map<String, List<String>>, DatasourceService> started = secretHosts -> {
            JevProperties props = new JevProperties(null, null, null, new JevProperties.Upload(100),
                new JevProperties.Datasource(List.of("10.0.0.5"), secretHosts));
            HttpApiDataSourceProvider api = new HttpApiDataSourceProvider(new ApiUrlGuard(props), env, props) {
                @Override
                List<Map<String, Object>> fetchFrom(URI uri, Map<String, String> headers) {
                    sent.add(headers);
                    return List.of(Map.of("amount", 1));
                }
            };
            return new DatasourceService(repo, List.of(api), props, new ApiUrlGuard(props));
        };
        Map<String, String> headers = Map.of("Authorization", "${DS_ERP_TOKEN}");

        Datasource ds = started.apply(Map.of("DS_ERP_TOKEN", List.of("10.0.0.5")))
            .registerApi("u1", "erp", "http://10.0.0.5/sales", headers);
        assertThat(sent).containsExactly(Map.of("Authorization", "Bearer t0k"));

        DatasourceService unbound = started.apply(Map.of());
        String message = HttpApiDataSourceProviderTest.notBound("DS_ERP_TOKEN", "10.0.0.5");
        assertThatThrownBy(() -> unbound.load("u1", ds.id())).hasMessage(message);
        assertThatThrownBy(() -> unbound.registerApi("u1", "erp2", "http://10.0.0.5/sales", headers)).hasMessage(message);
        assertThat(sent).hasSize(1);
        assertThat(saved).hasSize(1);
    }
}
