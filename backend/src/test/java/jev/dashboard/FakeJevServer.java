package jev.dashboard;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 用錄好的 JEV 回應當 fixture 的本機假伺服器，不打真的 API。 */
public final class FakeJevServer implements AutoCloseable {
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<String> auths = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    private FakeJevServer(Function<String, String> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            auths.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = responder.apply(body);
            byte[] out = (response == null ? "{\"error\":\"boom\"}" : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response == null ? 500 : 200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    public static FakeJevServer start(Function<String, String> responder) {
        try {
            return new FakeJevServer(responder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 依請求內容挑 fixture：異常檢查、第二輪、第一輪。 */
    public static String route(String body) {
        if (body.contains("\"anomaly_")) return Fixtures.json("highlight.json");
        if (body.contains("\"combo_0\"")) return Fixtures.json("round2.json");
        return Fixtures.json("round1.json");
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
