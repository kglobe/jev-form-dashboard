package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HttpApiDataSourceProviderTest {
    @Test
    void headerValuesMustBeEnvReferences() {
        assertThatThrownBy(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("Authorization", "Bearer abc")))
            .hasMessageContaining("${DS_環境變數名}");
        assertThatCode(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("Authorization", "${DS_ERP_API_TOKEN}")))
            .doesNotThrowAnyException();
    }

    @Test
    void resolvesHeadersFromEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("DS_ERP_API_TOKEN", "Bearer t0k");
        HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), env);
        assertThat(provider.resolveHeaders(Map.of("Authorization", "${DS_ERP_API_TOKEN}")))
            .containsEntry("Authorization", "Bearer t0k");
        assertThatThrownBy(() -> provider.resolveHeaders(Map.of("X-Key", "${DS_MISSING_VAR}")))
            .hasMessageContaining("DS_MISSING_VAR");
    }

    @Test
    void nonDsPrefixedVariableIsRejectedEvenWhenPresentInEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("DB_PASSWORD", "s3cr3t");
        HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), env);
        assertThatThrownBy(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
        assertThatThrownBy(() -> provider.resolveHeaders(Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
    }
}
