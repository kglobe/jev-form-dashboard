package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class HttpApiDataSourceProviderTest {
    private static JevProperties props(int maxRows) {
        return new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null);
    }

    /** 直接打 fetchFrom（繞過 guard，因為測試 server 在 loopback），其他照正式設定。 */
    private static HttpApiDataSourceProvider provider(int maxRows) {
        return new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), new MockEnvironment(), props(maxRows));
    }

    /**
     * 讀完請求標頭後原樣寫出 response。dropAfterWrite 就立刻斷線（模擬讀到一半斷線）；
     * 否則連線掛著不收尾，直到 client 自己關（模擬後面永遠不來的壞尾巴）。
     */
    private static ServerSocket rawServer(String response, boolean dropAfterWrite) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                s.setSoTimeout(15_000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF_8));
                for (String line = r.readLine(); line != null && !line.isEmpty(); line = r.readLine()) {
                    // 丟掉請求標頭
                }
                s.getOutputStream().write(response.getBytes(UTF_8));
                s.getOutputStream().flush();
                if (!dropAfterWrite) s.getInputStream().read();
            } catch (IOException ignored) {
                // 測試結束關掉 server 時會走到這裡
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    private static URI uri(ServerSocket server) {
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x");
    }

    @Test
    void connectionDroppedMidBodyIsAReadFailureNotAJsonError() throws Exception {
        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n[{\"a\":1},";
        try (ServerSocket server = rawServer(response, true)) {
            assertThatThrownBy(() -> provider(100).fetchFrom(uri(server), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("外部 API 回應讀取失敗")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/x", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            targetRequests.incrementAndGet();
            byte[] body = "[{\"a\":1}]".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> provider(100).fetchFrom(URI.create(base + "/x"), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("外部 API 回應錯誤（HTTP 302）")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
            assertThat(targetRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void overCapResponseFailsWithTheCapMessageThroughRealHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            byte[] body = "[{\"a\":1},{\"a\":2},{\"a\":3}]".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            assertThatThrownBy(() -> provider(2).fetchFrom(uri, Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("資料超過上限 2 筆")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void capIsHitWhileStreamingBeforeABrokenTail() throws Exception {
        // 送完上限 + 1 筆後，陣列沒收尾、chunk 也沒結束，連線就掛著；先把 body 讀完才數的做法永遠等不到結尾
        String chunk = "[{\"a\":1},{\"a\":2},{\"a\":3},{\"a\":";
        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
            + Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n";
        try (ServerSocket server = rawServer(response, false)) {
            assertThatThrownBy(() -> provider(2).fetchFrom(uri(server), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("資料超過上限 2 筆")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void jsonStructureProblemsKeepTheJsonArrayMessage() {
        for (String body : List.of("{\"a\":1}", "[{\"a\":1},", "not json")) {
            assertThatThrownBy(() -> HttpApiDataSourceProvider.parseCapped(new ByteArrayInputStream(body.getBytes(UTF_8)), 10))
                .isInstanceOf(ApiException.class)
                .hasMessage("外部 API 呼叫失敗（回應必須是 JSON 陣列）");
        }
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
    void parseCappedStopsAsSoonAsCapIsExceeded() throws Exception {
        InputStream withinCap = new ByteArrayInputStream("[{\"a\":1},{\"a\":2}]".getBytes(UTF_8));
        assertThat(HttpApiDataSourceProvider.parseCapped(withinCap, 2)).hasSize(2);

        InputStream overCap = new ByteArrayInputStream("[{\"a\":1},{\"a\":2},{\"a\":3}]".getBytes(UTF_8));
        assertThatThrownBy(() -> HttpApiDataSourceProvider.parseCapped(overCap, 2))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("資料超過上限 2 筆");
    }

    @Test
    void arrayElementsMustBeObjects() {
        for (String body : List.of("[null]", "[1]", "[{\"a\":1},\"x\"]", "[[1]]")) {
            assertThatThrownBy(() -> HttpApiDataSourceProvider.parseCapped(new ByteArrayInputStream(body.getBytes(UTF_8)), 10))
                .isInstanceOf(ApiException.class)
                .hasMessage("外部 API 回應格式錯誤：陣列元素必須是物件")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
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
