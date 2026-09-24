package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jev.dashboard.ApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpApiDataSourceProvider implements DataSourceProvider {
    // 只允許 ${DS_XXX}：header 只能引用 DS_ 前綴的環境變數，避免拿到 DB 密碼等其他機敏設定
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{DS_([A-Z0-9_]+)}");

    private final RestClient http;
    private final ApiUrlGuard guard;
    private final Environment env;

    public HttpApiDataSourceProvider(ApiUrlGuard guard, Environment env) {
        // JDK HttpClient 預設不跟隨 redirect，符合 spec
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.guard = guard;
        this.env = env;
    }

    @Override
    public boolean supports(String kind) {
        return "api".equals(kind);
    }

    @Override
    public List<Map<String, Object>> fetchRows(Datasource ds) {
        URI uri = guard.check(ds.apiUrl());
        Map<String, String> headers = resolveHeaders(ds.apiHeaders());
        try {
            List<Map<String, Object>> rows = http.get().uri(uri)
                .headers(h -> headers.forEach(h::add))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return rows == null ? List.of() : rows;
        } catch (RestClientException e) {
            throw new ApiException(BAD_GATEWAY, "外部 API 呼叫失敗（回應必須是 JSON 陣列）：" + e.getMessage());
        }
    }

    public static void validateHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (value == null || !ENV_REF.matcher(value).matches()) {
                throw new ApiException(BAD_REQUEST, "header「" + name + "」的值只能寫 ${DS_環境變數名}");
            }
        });
    }

    Map<String, String> resolveHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) return out;
        validateHeaders(headers);
        headers.forEach((name, value) -> {
            Matcher m = ENV_REF.matcher(value);
            m.matches();
            String varName = "DS_" + m.group(1);
            String resolved = env.getProperty(varName);
            if (resolved == null) throw new ApiException(INTERNAL_SERVER_ERROR, "環境變數 " + varName + " 未設定");
            out.put(name, resolved);
        });
        return out;
    }
}
