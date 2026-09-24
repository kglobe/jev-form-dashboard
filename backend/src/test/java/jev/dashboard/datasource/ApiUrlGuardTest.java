package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import jev.dashboard.ApiException;
import org.junit.jupiter.api.Test;

class ApiUrlGuardTest {
    @Test
    void emptyAllowListRejectsEverything() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of()).check("http://10.0.0.5/api"))
            .isInstanceOf(ApiException.class).hasMessageContaining("未開放");
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
    void nonHttpSchemeIsRejected() {
        assertThatThrownBy(() -> new ApiUrlGuard(List.of("10.0.0.5")).check("ftp://10.0.0.5/x"))
            .hasMessageContaining("只允許 http 或 https");
    }
}
