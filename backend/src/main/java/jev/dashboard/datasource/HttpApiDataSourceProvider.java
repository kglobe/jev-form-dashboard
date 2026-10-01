package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpApiDataSourceProvider implements DataSourceProvider {
    private static final Logger log = LoggerFactory.getLogger(HttpApiDataSourceProvider.class);
    // 只允許 ${DS_XXX}：header 只能引用 DS_ 前綴的環境變數，避免拿到 DB 密碼等其他機敏設定
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{DS_([A-Z0-9_]+)}");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ROW_TYPE = new TypeReference<>() {};

    private final RestClient http;
    private final ApiUrlGuard guard;
    private final Environment env;
    private final int maxRows;
    private final Map<String, List<String>> secretHosts;

    public HttpApiDataSourceProvider(ApiUrlGuard guard, Environment env, JevProperties props) {
        // JDK HttpClient 預設不跟隨 redirect，符合 spec
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.guard = guard;
        this.env = env;
        this.maxRows = props.upload().maxRows();
        // ponytail: 綁定只在啟動時讀，改 .env 要重啟後端；要不重啟就生效，改成每次從 Environment 綁定
        Map<String, List<String>> bound = props.datasource() == null ? null : props.datasource().secretHosts();
        this.secretHosts = bound == null ? Map.of() : bound;
    }

    @Override
    public boolean supports(String kind) {
        return "api".equals(kind);
    }

    @Override
    public List<Map<String, Object>> fetchRows(Datasource ds) {
        URI uri = guard.check(ds.apiUrl());
        Map<String, String> headers = resolveHeaders(uri.getHost(), ds.apiHeaders());
        return fetchFrom(uri, headers);
    }

    /** guard 檢查通過後的實際呼叫；獨立出來方便測試用假 server 打，不必繞過 guard。 */
    List<Map<String, Object>> fetchFrom(URI uri, Map<String, String> headers) {
        try {
            return http.get().uri(uri)
                .headers(h -> headers.forEach(h::add))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        log.warn("外部 API 回應非 2xx：host={} status={}", uri.getHost(), response.getStatusCode());
                        throw new ApiException(BAD_GATEWAY,
                            "外部 API 回應錯誤（HTTP " + response.getStatusCode().value() + "）");
                    }
                    try (InputStream in = response.getBody()) {
                        return parseCapped(in, maxRows);
                    } catch (IOException e) {
                        // JSON 格式錯誤在 parseCapped 已經處理，這裡剩下讀到一半逾時、斷線
                        log.warn("外部 API 回應讀取失敗：host={}", uri.getHost(), e);
                        throw new ApiException(BAD_GATEWAY, "外部 API 回應讀取失敗");
                    }
                });
        } catch (RestClientException e) {
            log.warn("外部 API 無法連線：host={}", uri.getHost(), e);
            throw new ApiException(BAD_GATEWAY, "外部 API 無法連線");
        }
    }

    /**
     * 用 Jackson streaming API 邊讀邊數，一超過上限就中止，不必等整個回應塞進記憶體。
     * 獨立成 package-private 方法方便直接餵 InputStream 測試，不必真的發 HTTP 請求。
     * JSON 結構錯誤轉成 502；讀取本身失敗（逾時、斷線）照原樣丟 IOException，交給呼叫端帶 host 記 log。
     */
    static List<Map<String, Object>> parseCapped(InputStream in, int maxRows) throws IOException {
        try (JsonParser parser = MAPPER.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new ApiException(BAD_GATEWAY, "外部 API 呼叫失敗（回應必須是 JSON 陣列）");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonToken t = parser.nextToken(); t != JsonToken.END_ARRAY; t = parser.nextToken()) {
                if (t != JsonToken.START_OBJECT) {
                    throw new ApiException(BAD_GATEWAY, "外部 API 回應格式錯誤：陣列元素必須是物件");
                }
                rows.add(parser.readValueAs(ROW_TYPE));
                if (rows.size() > maxRows) {
                    throw new ApiException(BAD_REQUEST, "資料超過上限 " + maxRows + " 筆");
                }
            }
            return rows;
        } catch (JsonProcessingException e) {
            throw new ApiException(BAD_GATEWAY, "外部 API 呼叫失敗（回應必須是 JSON 陣列）");
        }
    }

    public static void validateHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (value == null || !ENV_REF.matcher(value).matches()) {
                throw new ApiException(BAD_REQUEST, "header「" + name + "」的值只能寫 ${DS_環境變數名}");
            }
        });
    }

    /**
     * 密鑰綁定 spec 3.2：每一把密鑰都有開放給這個 host，才開始讀值。註冊和之後每次載入都走這裡，
     * 所以 .env 拿掉綁定、重啟後端，已經註冊的來源下次載入就會被擋。
     */
    Map<String, String> resolveHeaders(String host, Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) return out;
        validateHeaders(headers);
        Map<String, String> varNames = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            Matcher m = ENV_REF.matcher(value);
            m.matches();
            String varName = "DS_" + m.group(1);
            if (secretHosts.getOrDefault(varName, List.of()).stream().noneMatch(host::equalsIgnoreCase)) {
                throw new ApiException(BAD_REQUEST, "密鑰 " + varName + " 沒有開放給 " + host
                    + "：請在 backend/.env 加上 jev.datasource.secret-hosts.[" + varName + "]=" + host);
            }
            varNames.put(name, varName);
        });
        varNames.forEach((name, varName) -> {
            String resolved = env.getProperty(varName);
            if (resolved == null) throw new ApiException(INTERNAL_SERVER_ERROR, "環境變數 " + varName + " 未設定");
            out.put(name, resolved);
        });
        return out;
    }
}
