package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import jev.dashboard.ApiException;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class JevClientTest {
    static final Map<String, Question> ONE = Map.of("imp_0", Question.score("重要嗎？", List.of("低", "高")));

    @Test
    void sendsModelStateAndQuestionsAndParsesAnswers() {
        try (FakeJevServer server = FakeJevServer.start(body -> Fixtures.json("round1.json"))) {
            JevClient client = new JevClient(server.url(), "k-123", "jev-1.13.0", Duration.ofSeconds(5));
            Map<String, Answer> a = client.ask(Map.of("rowCount", 12), ONE);
            assertThat(a.get("role_0").choice()).isEqualTo("time");
            assertThat(a.get("role_0").confidence()).isEqualTo(0.95);
            assertThat(a.get("imp_1").score()).isEqualTo(2.75);
            assertThat(server.auths).containsExactly("Bearer k-123");
            assertThat(server.bodies.get(0))
                .contains("\"model\":\"jev-1.13.0\"")
                .contains("\"rowCount\":12")
                .contains("\"imp_0\"")
                .contains("\"type\":\"score\"")
                .contains("\"criteria\":[\"低\",\"高\"]");
        }
    }

    @Test
    void serverErrorBecomesBadGateway() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.ask(Map.of(), ONE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("JEV 呼叫失敗")
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("boom")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failuresAreLoggedAsOneLineWithoutAStackTrace(CapturedOutput output) {
        // JEV 掛掉時每次載入儀表板都會失敗一次，stack trace 會洗版；原因都在 message 裡
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.ask(Map.of(), ONE)).isInstanceOf(ApiException.class);
        }
        JevClient unreachable = new JevClient("http://127.0.0.1:1/v1/systemone", "k", "jev-1.13.0", Duration.ofSeconds(1));
        assertThatThrownBy(() -> unreachable.ask(Map.of(), ONE)).isInstanceOf(ApiException.class);

        assertThat(output.getOut() + output.getErr())
            .contains("JEV 呼叫失敗：500")
            .contains("JEV 無法連線：java.net.ConnectException")
            .doesNotContain("\tat ");
    }

    @Test
    void missingApiKeyIsServiceUnavailable() {
        JevClient client = new JevClient("http://127.0.0.1:1/v1/systemone", "", "jev-1.13.0", Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.ask(Map.of(), ONE))
            .hasMessageContaining("TYPESAFE_API_KEY")
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---------- 429：照 retry-after 重試一次 ----------

    record Reply(int status, String retryAfter, String body) {}

    /** 照順序回覆的假 JEV：FakeJevServer 只會回 200／500，這裡要 429 加 retry-after。 */
    static final class ScriptedJev implements AutoCloseable {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        ScriptedJev(Reply... replies) throws IOException {
            AtomicInteger n = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/systemone", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Reply r = replies[Math.min(n.getAndIncrement(), replies.length - 1)];
                byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (r.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", r.retryAfter());
                exchange.sendResponseHeaders(r.status(), out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static final Reply BUSY = new Reply(429, "2", "{\"error\":\"rate limited\"}");
    static final Reply OK = new Reply(200, null,
        "{\"model\":\"jev-1.13.0\",\"answers\":{\"imp_0\":{\"type\":\"score\",\"score\":1.0,\"confidence\":0.9}}}");

    @Test
    void tooManyRequestsWaitsForRetryAfterThenRetriesOnce() throws IOException {
        List<Duration> slept = new ArrayList<>();
        try (ScriptedJev jev = new ScriptedJev(BUSY, OK)) {
            JevClient client = new JevClient(jev.url(), "k", "jev-1.13.0", Duration.ofSeconds(5), Duration.ofSeconds(10), slept::add);
            Map<String, Answer> a = client.ask(Map.of(), ONE);
            assertThat(a.get("imp_0").score()).isEqualTo(1.0);
            assertThat(slept).containsExactly(Duration.ofSeconds(2));
            assertThat(jev.bodies).hasSize(2);
        }
    }

    @Test
    void aSecond429GivesUpAsBusyInsteadOfHammeringTheEndpoint() throws IOException {
        List<Duration> slept = new ArrayList<>();
        try (ScriptedJev jev = new ScriptedJev(BUSY, BUSY, OK)) {
            JevClient client = new JevClient(jev.url(), "k", "jev-1.13.0", Duration.ofSeconds(5), Duration.ofSeconds(10), slept::add);
            assertThatThrownBy(() -> client.ask(Map.of(), ONE))
                .hasMessageContaining("請求太多")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(jev.bodies).hasSize(2);
            assertThat(slept).hasSize(1);
        }
    }

    @Test
    void aRetryAfterLongerThanTheCapIsNotWaitedFor() throws IOException {
        List<Duration> slept = new ArrayList<>();
        try (ScriptedJev jev = new ScriptedJev(new Reply(429, "30", "{}"), OK)) {
            JevClient client = new JevClient(jev.url(), "k", "jev-1.13.0", Duration.ofSeconds(5), Duration.ofSeconds(2), slept::add);
            assertThatThrownBy(() -> client.ask(Map.of(), ONE))
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(slept).isEmpty();
            assertThat(jev.bodies).hasSize(1);
        }
    }

    @Test
    void retryAfterReadsSecondsFractionsAndHttpDatesAndFallsBackToOneSecond() {
        Instant now = Instant.parse("2026-09-27T08:00:00Z");
        HttpHeaders h = new HttpHeaders();
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ofSeconds(1));
        h.set(HttpHeaders.RETRY_AFTER, "3");
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ofSeconds(3));
        h.set(HttpHeaders.RETRY_AFTER, "0.5");
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ofMillis(500));
        h.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(10).atOffset(ZoneOffset.UTC)));
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ofSeconds(10));
        h.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(10).atOffset(ZoneOffset.UTC)));
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ZERO);
        h.set(HttpHeaders.RETRY_AFTER, "soon");
        assertThat(JevClient.retryAfter(h, now)).isEqualTo(Duration.ofSeconds(1));
    }

    // ---------- 請求大小：64k 上下文、state 加最長一題 32k ----------

    /** 每一題都回一個 noul，用來確認拆開的請求各自答了哪些題。 */
    static String answerEveryQuestion(String body) {
        try {
            ObjectMapper json = new ObjectMapper();
            Map<String, Object> answers = new LinkedHashMap<>();
            json.readTree(body).get("questions").fieldNames()
                .forEachRemaining(k -> answers.put(k, Map.of("type", "noul", "noul", 0.5)));
            return json.writeValueAsString(Map.of("model", "jev-1.13.0", "answers", answers));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void questionsOverTheContextBudgetAreSplitIntoSeveralRequestsThatEachCarryTheWholeState() throws IOException {
        Map<String, Question> many = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) many.put("q_" + i, Question.noul("第 " + i + " 題：" + "很".repeat(1000)));
        try (FakeJevServer server = FakeJevServer.start(JevClientTest::answerEveryQuestion)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            Map<String, Answer> a = client.ask(Map.of("marker", "state-A"), many);

            assertThat(a).containsOnlyKeys(many.keySet());
            assertThat(server.bodies).hasSizeGreaterThan(1);
            Set<String> asked = new HashSet<>();
            ObjectMapper json = new ObjectMapper();
            for (String body : server.bodies) {
                JsonNode req = json.readTree(body);
                assertThat(req.get("state").get("marker").asText()).isEqualTo("state-A");
                assertThat(JevClient.estimateTokens(req)).isLessThanOrEqualTo((int) (JevClient.CONTEXT_TOKENS * JevClient.BUDGET) + 50);
                req.get("questions").fieldNames().forEachRemaining(k -> assertThat(asked.add(k)).isTrue()); // 每題只問一次
            }
            assertThat(asked).isEqualTo(many.keySet());
        }
    }

    @Test
    void aNormalSizedRequestStaysOneCall() {
        try (FakeJevServer server = FakeJevServer.start(JevClientTest::answerEveryQuestion)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            client.ask(Map.of("rowCount", 12), ONE);
            assertThat(server.bodies).hasSize(1);
        }
    }

    @Test
    void aStateTooBigForOneRequestIsRejectedBeforeCallingJev() {
        try (FakeJevServer server = FakeJevServer.start(JevClientTest::answerEveryQuestion)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.ask(Map.of("memo", "字".repeat(25_000)), ONE))
                .hasMessageContaining("太大")
                .hasMessageContaining("敏感")
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
            assertThat(server.bodies).isEmpty();
        }
    }

    @Test
    void estimateTokensCountsAsciiByThreesAndOtherCharactersOneEach() {
        assertThat(JevClient.estimateTokens("abcdef")).isEqualTo(3); // 連引號 8 個 ASCII → 2，再 +1
        assertThat(JevClient.estimateTokens("中文字")).isEqualTo(4); // 2 個引號 → 0，3 個中文 → 3，再 +1
    }

    // ---------- 完整機率 ----------

    @Test
    void keepsProbabilitiesAndLegendAndStillParsesWhenTheirShapeIsUnexpected() {
        String reply = """
            {"model":"jev-1.13.0","answers":{
              "role_0":{"type":"choice","choice":"time","confidence":0.8,"probabilities":{"time":0.8,"measure":0.15,"ignore":0.05}},
              "imp_0":{"type":"score","score":2.4,"confidence":0.6,"legend":{"0":"不重要","3":"核心"},"probabilities":{"0":0.0,"1":0.1,"2":0.4,"3":0.5}},
              "odd":{"type":"choice","choice":"a","confidence":0.9,"probabilities":[0.9,0.1]}
            }}""";
        try (FakeJevServer server = FakeJevServer.start(body -> reply)) {
            JevClient client = new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5));
            Map<String, Answer> a = client.ask(Map.of(), ONE);
            assertThat(a.get("role_0").probabilities().get("measure").asDouble()).isEqualTo(0.15);
            assertThat(a.get("imp_0").legend().get("3").asText()).isEqualTo("核心");
            assertThat(a.get("imp_0").probabilities().get("2").asDouble()).isEqualTo(0.4);
            assertThat(a.get("odd").probabilities().isArray()).isTrue();
        }
    }
}
