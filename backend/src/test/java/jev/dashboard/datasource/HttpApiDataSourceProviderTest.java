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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class HttpApiDataSourceProviderTest {
    private static JevProperties props(int maxRows) {
        return new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null);
    }

    /** 直接打 fetchFrom（測試 server 在 loopback），只替換連線的 DNS resolver。 */
    private static HttpApiDataSourceProvider provider(int maxRows) {
        return new HttpApiDataSourceProvider(new ApiUrlGuard(List.of()), new MockEnvironment(), props(maxRows),
            SystemDefaultDnsResolver.INSTANCE);
    }

    /** 允許清單只有 10.0.0.5；secretHosts 是 jev.datasource.secret-hosts。 */
    static HttpApiDataSourceProvider provider(MockEnvironment env, Map<String, List<String>> secretHosts) {
        JevProperties props = new JevProperties(null, null, null, new JevProperties.Upload(100),
            new JevProperties.Datasource(List.of("10.0.0.5"), secretHosts));
        return new HttpApiDataSourceProvider(new ApiUrlGuard(props), env, props);
    }

    /** 記下讀過哪些變數，用來確認沒開放的密鑰連讀都沒讀。 */
    private static MockEnvironment recording(List<String> read) {
        return new MockEnvironment() {
            @Override
            public String getProperty(String key) {
                read.add(key);
                return super.getProperty(key);
            }
        };
    }

    static String notBound(String varName, String host) {
        return "密鑰 " + varName + " 沒有開放給 " + host + "：請在 backend/.env 加上 jev.datasource.secret-hosts.["
            + varName + "]=" + host;
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
    void dnsChangingToLoopbackBeforeConnectionIsRejected() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            requests.incrementAndGet();
            byte[] body = "[{\"a\":1}]".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiUrlGuard guard = new ApiUrlGuard(List.of("localhost")) {
                @Override
                InetAddress[] lookup(String host) throws java.net.UnknownHostException {
                    return lookups.getAndIncrement() == 0
                        ? new InetAddress[] { InetAddress.getByName("10.0.0.5") }
                        : new InetAddress[] { InetAddress.getLoopbackAddress() };
                }
            };
            HttpApiDataSourceProvider provider = new HttpApiDataSourceProvider(guard, new MockEnvironment(), props(100));
            Datasource ds = api("http://localhost:" + server.getAddress().getPort() + "/x", Map.of());

            assertThatThrownBy(() -> provider.fetchRows(ds)).isInstanceOf(ApiException.class);
            assertThat(lookups).hasValue(2);
            assertThat(requests).hasValue(0);
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
        HttpApiDataSourceProvider provider = provider(env,
            Map.of("DS_ERP_API_TOKEN", List.of("erp.example.com"), "DS_MISSING_VAR", List.of("erp.example.com")));
        assertThat(provider.resolveHeaders("erp.example.com", Map.of("Authorization", "${DS_ERP_API_TOKEN}")))
            .containsEntry("Authorization", "Bearer t0k");
        assertThatThrownBy(() -> provider.resolveHeaders("erp.example.com", Map.of("X-Key", "${DS_MISSING_VAR}")))
            .hasMessageContaining("DS_MISSING_VAR");
    }

    @Test
    void nonDsPrefixedVariableIsRejectedEvenWhenPresentInEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty("DB_PASSWORD", "s3cr3t");
        HttpApiDataSourceProvider provider = provider(env, Map.of("DB_PASSWORD", List.of("erp.example.com")));
        assertThatThrownBy(() -> HttpApiDataSourceProvider.validateHeaders(Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
        assertThatThrownBy(() -> provider.resolveHeaders("erp.example.com", Map.of("X-Key", "${DB_PASSWORD}")))
            .hasMessageContaining("${DS_環境變數名}");
    }

    @Test
    void secretIsOnlySentToTheHostsItIsBoundTo() { // 密鑰綁定 spec 3.2
        List<String> read = new ArrayList<>();
        MockEnvironment env = recording(read).withProperty("DS_ERP_TOKEN", "Bearer t0k");
        Map<String, String> headers = Map.of("Authorization", "${DS_ERP_TOKEN}");

        for (HttpApiDataSourceProvider unbound : List.of(provider(env, Map.of()),
                provider(env, Map.of("DS_ERP_TOKEN", List.of("other.example.com"))),
                provider(env, Map.of("DS_OTHER_TOKEN", List.of("erp.example.com"))))) {
            assertThatThrownBy(() -> unbound.resolveHeaders("erp.example.com", headers))
                .isInstanceOf(ApiException.class)
                .hasMessage(notBound("DS_ERP_TOKEN", "erp.example.com"))
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(read).doesNotContain("DS_ERP_TOKEN");

        HttpApiDataSourceProvider bound =
            provider(env, Map.of("DS_ERP_TOKEN", List.of("erp2.example.com", "erp.example.com")));
        assertThat(bound.resolveHeaders("erp.example.com", headers)).containsEntry("Authorization", "Bearer t0k");
    }

    @Test
    void boundHostIgnoresCase() {
        MockEnvironment env = new MockEnvironment().withProperty("DS_ERP_TOKEN", "Bearer t0k");
        Map<String, String> headers = Map.of("Authorization", "${DS_ERP_TOKEN}");
        assertThat(provider(env, Map.of("DS_ERP_TOKEN", List.of("ERP.Example.com"))).resolveHeaders("erp.example.COM", headers))
            .containsEntry("Authorization", "Bearer t0k");
    }

    @Test
    void oneUnboundHeaderRejectsTheWholeRequest() {
        List<String> read = new ArrayList<>();
        MockEnvironment env = recording(read).withProperty("DS_ERP_TOKEN", "Bearer t0k").withProperty("DS_HR_KEY", "k3y");
        HttpApiDataSourceProvider provider = provider(env, Map.of("DS_ERP_TOKEN", List.of("erp.example.com")));
        assertThatThrownBy(() -> provider.resolveHeaders("erp.example.com",
                Map.of("Authorization", "${DS_ERP_TOKEN}", "X-Key", "${DS_HR_KEY}")))
            .hasMessage(notBound("DS_HR_KEY", "erp.example.com"));
        assertThat(read).doesNotContain("DS_HR_KEY");
    }

    @Test
    void everyBindingIsCheckedBeforeAnyValueIsRead() {
        // spec 3.2：先查完全部綁定才讀值；前一把綁了但沒設定值，也要回後一把沒綁定的 400，不是 500
        List<String> read = new ArrayList<>();
        HttpApiDataSourceProvider provider = provider(recording(read), Map.of("DS_A", List.of("10.0.0.5")));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-A", "${DS_A}");
        headers.put("X-B", "${DS_B}");
        assertThatThrownBy(() -> provider.resolveHeaders("10.0.0.5", headers))
            .isInstanceOf(ApiException.class)
            .hasMessage(notBound("DS_B", "10.0.0.5"))
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(read).isEmpty();
    }

    @Test
    void everyFetchChecksTheBindingAndSecretHostsDoNotOpenAHost() {
        MockEnvironment env = new MockEnvironment().withProperty("DS_ERP_TOKEN", "Bearer t0k");
        Map<String, String> headers = Map.of("Authorization", "${DS_ERP_TOKEN}");
        // 擋在發出請求之前：10.0.0.5 真的連不到，走到 HTTP 會變成 502
        assertThatThrownBy(() -> provider(env, Map.of()).fetchRows(api("http://10.0.0.5/sales", headers)))
            .hasMessage(notBound("DS_ERP_TOKEN", "10.0.0.5"));
        // 綁了 10.0.0.6 也不會讓它通過允許清單
        assertThatThrownBy(() -> provider(env, Map.of("DS_ERP_TOKEN", List.of("10.0.0.6")))
                .fetchRows(api("http://10.0.0.6/sales", headers)))
            .hasMessage("host 不在允許清單：10.0.0.6");
    }

    private static Datasource api(String url, Map<String, String> headers) {
        return new Datasource(UUID.randomUUID(), "erp", "api", List.of(), url, headers, "u1", null);
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
            HttpApiDataSourceProvider provider = provider(100);
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
