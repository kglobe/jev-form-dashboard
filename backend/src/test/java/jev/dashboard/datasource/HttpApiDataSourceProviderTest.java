package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class HttpApiDataSourceProviderTest {
    private static JevProperties props(int maxRows) {
        return new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null);
    }

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
        HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), env, props(100));
        assertThat(provider.resolveHeaders(Map.of("Authorization", "${DS_ERP_API_TOKEN}")))
            .containsEntry("Authorization", "Bearer t0k");
        assertThatThrownBy(() -> provider.resolveHeaders(Map.of("X-Key", "${DS_MISSING_VAR}")))
            .hasMessageContaining("DS_MISSING_VAR");
    }

    @Test
    void nonDsPrefixedVariableIsRejectedEvenWhenPresentInEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("DB_PASSWORD", "s3cr3t");
        HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), env, props(100));
        assertThatThrownBy(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
        assertThatThrownBy(() -> provider.resolveHeaders(Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
    }

    @Test
    void parseCappedStopsAsSoonAsCapIsExceeded() {
        InputStream withinCap = new ByteArrayInputStream("[{\"a\":1},{\"a\":2}]".getBytes(UTF_8));
        assertThat(HttpApiDataSourceProvider.parseCapped(withinCap, 2)).hasSize(2);

        InputStream overCap = new ByteArrayInputStream("[{\"a\":1},{\"a\":2},{\"a\":3}]".getBytes(UTF_8));
        assertThatThrownBy(() -> HttpApiDataSourceProvider.parseCapped(overCap, 2))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("資料超過上限 2 筆");
    }

    @Test
    void errorResponseMessageDoesNotLeakUpstreamBody() throws Exception {
        String secretBody = "{\"error\":\"DB_PASSWORD=super-secret-value\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            byte[] body = secretBody.getBytes(UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpApiDataSourceProvider provider =
                new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), new MockEnvironment(), props(100));
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            assertThatThrownBy(() -> provider.fetchFrom(uri, Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("HTTP 500")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("DB_PASSWORD", "super-secret-value"));
        } finally {
            server.stop(0);
        }
    }
}
