package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import jev.dashboard.ApiException;
import org.junit.jupiter.api.Test;

class ApiUrlGuardTest {
    @Test
    void emptyAllowListRejectsEverything() {
        // API_ALLOWED_HOSTS=, 經 Spring 綁定會變成 ["", ""]，一樣算空清單
        for (List<String> hosts : List.of(List.<String>of(), List.of("", ""), List.of(" "))) {
            assertThatThrownBy(() -> new ApiUrlGuard(hosts).check("http://10.0.0.5/api"))
                .isInstanceOf(ApiException.class).hasMessage("未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS");
        }
    }

    @Test
    void allowListIgnoresCase() {
        // 走到「本機位址」這步，表示已經過了允許清單；localhost 在本機就解析得到，不必查 DNS
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("LocalHost")).check("http://LOCALHOST/x"))
            .hasMessageContaining("本機位址");
    }

    @Test
    void hostNotInListIsRejected() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("10.0.0.5")).check("https://evil.example/x"))
            .hasMessageContaining("不在允許清單");
    }

    @Test
    void listedPrivateIpIsAllowed() {
        assertThat(new ApiUrlGuard(List.of("10.0.0.5")).check("http://10.0.0.5:8080/api/sales").getHost())
            .isEqualTo("10.0.0.5");
    }

    @Test
    void loopbackIsRejectedEvenWhenListed() {
        ApiUrlGuard guard = new ApiUrlGuard(List.of("127.0.0.1", "localhost", "0.0.0.0"));
        assertThatThrownBy(() -> guard.check("http://127.0.0.1:8080/x")).hasMessageContaining("本機位址");
        assertThatThrownBy(() -> guard.check("http://localhost/x")).hasMessageContaining("本機位址");
        assertThatThrownBy(() -> guard.check("http://0.0.0.0/x")).hasMessageContaining("本機位址");
    }

    @Test
    void credentialsInTheUrlAreRejected() {
        ApiUrlGuard guard = new ApiUrlGuard(List.of("10.0.0.5"));
        String message = "網址不能包含帳密或 token，請改放在 headers，值寫 ${DS_環境變數名}";
        for (String url : List.of("https://user:pass@10.0.0.5/x", "https://user@10.0.0.5/x",
                "http://10.0.0.5/x?%74oken=abc", "http://10.0.0.5/x?token")) {
            assertThatThrownBy(() -> guard.check(url)).isInstanceOf(ApiException.class).hasMessage(message);
        }
        for (String name : List.of("access_token", "token", "api_key", "apikey", "secret", "password", "passwd", "pwd",
                "auth", "authorization", "signature", "sig")) {
            assertThatThrownBy(() -> guard.check("http://10.0.0.5/x?page=1&" + name.toUpperCase(Locale.ROOT) + "=abc"))
                .isInstanceOf(ApiException.class).hasMessage(message);
        }
    }

    @Test
    void ordinaryQueryParametersAreAllowed() {
        assertThat(new ApiUrlGuard(List.of("10.0.0.5")).check("http://10.0.0.5/x?page=2&tokenizer=cjk&q=token").getQuery())
            .isEqualTo("page=2&tokenizer=cjk&q=token");
    }

    @Test
    void nonHttpSchemeIsRejected() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("10.0.0.5")).check("ftp://10.0.0.5/x"))
            .hasMessageContaining("只允許 http 或 https");
    }
}
