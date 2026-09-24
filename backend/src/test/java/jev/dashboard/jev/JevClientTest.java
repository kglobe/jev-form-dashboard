package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import jev.dashboard.ApiException;
import jev.dashboard.FakeJevServer;
import jev.dashboard.Fixtures;
import org.junit.jupiter.api.Test;
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
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
    }

    @Test
    void missingApiKeyIsServiceUnavailable() {
        JevClient client = new JevClient("http://127.0.0.1:1/v1/systemone", "", "jev-1.13.0", Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.ask(Map.of(), ONE))
            .hasMessageContaining("TYPESAFE_API_KEY")
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
