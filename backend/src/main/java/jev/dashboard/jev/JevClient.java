package jev.dashboard.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jev.dashboard.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class JevClient {
    private static final Logger log = LoggerFactory.getLogger(JevClient.class);

    // jevai.wiki/zh-Hant/models：每次請求上下文最多 64k tokens；state 加最長的一題另有 32k 的上限
    static final int CONTEXT_TOKENS = 64_000;
    static final int SINGLE_TOKENS = 32_000;
    // ponytail: 沒有 Jev 的 tokenizer，estimateTokens 用字元數粗估，再只拿上限的 75% 當預算；
    // 要精準再改用官方 tokenizer，或拿回應的 usage 回頭校正
    static final double BUDGET = 0.75;
    // 429 沒給 retry-after 時等多久再試
    static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(1);
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    record Request(String model, Object state, Map<String, Question> questions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String model, Map<String, Answer> answers) {}

    /** 等 retry-after 用；測試換成不真的睡。 */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final RestClient http;
    private final String apiUrl;
    private final String model;
    private final boolean configured;
    private final Duration maxRetryWait;
    private final Sleeper sleeper;

    public JevClient(String apiUrl, String apiKey, String model, Duration readTimeout) {
        this(apiUrl, apiKey, model, readTimeout, Duration.ofSeconds(5));
    }

    /** maxRetryWait：429 的 retry-after 在這個時間內才等一次再試，更久就直接回忙碌，不硬等。 */
    public JevClient(String apiUrl, String apiKey, String model, Duration readTimeout, Duration maxRetryWait) {
        this(apiUrl, apiKey, model, readTimeout, maxRetryWait, d -> Thread.sleep(d.toMillis()));
    }

    JevClient(String apiUrl, String apiKey, String model, Duration readTimeout, Duration maxRetryWait, Sleeper sleeper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.apiUrl = apiUrl;
        this.model = model;
        this.configured = apiKey != null && !apiKey.isBlank();
        this.maxRetryWait = maxRetryWait;
        this.sleeper = sleeper;
    }

    /** 題目加總超過上下文預算就拆成幾次請求再合併：每題都是對同一份 state 各自平行評估，拆開不影響答案。 */
    public Map<String, Answer> ask(Object state, Map<String, Question> questions) {
        if (!configured) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "未設定 TYPESAFE_API_KEY");
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Map<String, Question> batch : batches(state, questions)) answers.putAll(send(state, batch));
        // jevai.wiki/zh-Hant/confidence：出錯時要看完整機率（第二名是誰），不能只看 argmax；開 DEBUG 才印
        log.debug("JEV 回答（含 probabilities、legend）：{}", answers);
        return answers;
    }

    List<Map<String, Question>> batches(Object state, Map<String, Question> questions) {
        int stateTokens = estimateTokens(state);
        int longest = questions.values().stream().mapToInt(JevClient::estimateTokens).max().orElse(0);
        if (stateTokens + longest > SINGLE_TOKENS * BUDGET) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "送給 JEV 的資料太大（約 " + (stateTokens + longest) + " tokens，單次上限 32k）："
                    + "請減少欄位，或把長文字欄位標成敏感（敏感欄位不送樣本）");
        }
        List<Map<String, Question>> out = new ArrayList<>();
        Map<String, Question> batch = new LinkedHashMap<>();
        int size = stateTokens;
        for (Map.Entry<String, Question> q : questions.entrySet()) {
            int t = estimateTokens(Map.of(q.getKey(), q.getValue()));
            if (!batch.isEmpty() && size + t > CONTEXT_TOKENS * BUDGET) {
                out.add(batch);
                batch = new LinkedHashMap<>();
                size = stateTokens;
            }
            batch.put(q.getKey(), q.getValue());
            size += t;
        }
        if (!batch.isEmpty()) out.add(batch);
        return out;
    }

    private Map<String, Answer> send(Object state, Map<String, Question> questions) {
        for (int attempt = 0; ; attempt++) {
            try {
                Response r = http.post().uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(model, state, questions))
                    .retrieve()
                    .body(Response.class);
                if (r == null || r.answers() == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗：沒有 answers");
                return r.answers();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    // jevai.wiki/zh-Hant/api：遇到 429 照 retry-after 重試；只重試一次，等太久就不等，別一直打同一個限制時窗
                    Duration wait = retryAfter(e.getResponseHeaders(), Instant.now());
                    if (attempt == 0 && wait.compareTo(maxRetryWait) <= 0) {
                        pause(wait);
                        continue;
                    }
                    log.warn("JEV 請求太多（HTTP 429），retry-after {} 秒", wait.toSeconds());
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JEV 目前請求太多，請稍後再試");
                }
                // 不印 stack trace：JEV 掛掉時每次載入儀表板都會失敗一次，原因都在 message 裡
                log.warn("JEV 呼叫失敗：{}", e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗（HTTP " + e.getStatusCode().value() + "）");
            } catch (RestClientException e) {
                // 第一層原因才看得出是連線被拒、找不到主機還是逾時（ConnectException 沒有 message，再往下是 ClosedChannelException）
                log.warn("JEV 無法連線：{}", Objects.requireNonNullElse(e.getCause(), e).toString());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "JEV 呼叫失敗（無法連線）");
            }
        }
    }

    private void pause(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JEV 目前請求太多，請稍後再試");
        }
    }

    /** retry-after 可以是秒數（可帶小數）或 HTTP 日期；沒給或看不懂就等 DEFAULT_RETRY_WAIT。 */
    static Duration retryAfter(HttpHeaders headers, Instant now) {
        String v = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null || v.isBlank()) return DEFAULT_RETRY_WAIT;
        try {
            double seconds = Double.parseDouble(v.trim());
            return Double.isFinite(seconds) ? Duration.ofMillis(Math.max(0, Math.round(seconds * 1000))) : DEFAULT_RETRY_WAIT;
        } catch (NumberFormatException ignored) {
            // 不是秒數，改試 HTTP 日期
        }
        try {
            Duration d = Duration.between(now, ZonedDateTime.parse(v.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
            return d.isNegative() ? Duration.ZERO : d;
        } catch (DateTimeParseException e) {
            return DEFAULT_RETRY_WAIT;
        }
    }

    /** 粗估 token 數：序列化成 JSON，ASCII 每 3 個字元算 1 個 token，其他字元（中文等）每字算 1 個。 */
    static int estimateTokens(Object value) {
        String json;
        try {
            json = JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            json = String.valueOf(value);
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) < 128) ascii++;
            else other++;
        }
        return ascii / 3 + other + 1;
    }
}
