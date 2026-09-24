package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import jev.dashboard.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class JevClient {
    record Request(String model, Object state, Map<String, Question> questions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String model, Map<String, Answer> answers) {}

    private final RestClient http;
    private final String apiUrl;
    private final String model;
    private final boolean configured;

    public JevClient(String apiUrl, String apiKey, String model, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.apiUrl = apiUrl;
        this.model = model;
        this.configured = apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Answer> ask(Object state, Map<String, Question> questions) {
        if (!configured) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "未設定 TYPESAFE_API_KEY");
        try {
            Response r = http.post().uri(apiUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, state, questions))
                .retrieve()
                .body(Response.class);
            if (r == null || r.answers() == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗：沒有 answers");
            return r.answers();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗：" + e.getMessage());
        }
    }
}
