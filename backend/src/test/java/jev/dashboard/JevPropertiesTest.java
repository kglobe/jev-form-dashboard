package jev.dashboard;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class JevPropertiesTest {
    /** 跟正式啟動一樣：.env 當 properties 檔讀，排在 application.yml 前面；不讀開發者自己的 .env 和環境變數。 */
    private static JevProperties.Datasource bind(String dotEnv) throws IOException {
        MockEnvironment env = new MockEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        new PropertiesPropertySourceLoader().load(".env", new ByteArrayResource(dotEnv.getBytes(ISO_8859_1)))
            .forEach(sources::addLast);
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
            .forEach(sources::addLast);
        return Binder.get(env).bind("jev", JevProperties.class).get().datasource();
    }

    @Test
    void allowedHostsIsEmptyWhenApiAllowedHostsIsMissingOrBlank() throws IOException {
        assertThat(bind("").allowedHosts()).isEmpty();
        assertThat(bind("API_ALLOWED_HOSTS=").allowedHosts()).isEmpty();
    }

    @Test
    void apiAllowedHostsIsCommaSeparatedAndTrimmed() throws IOException {
        assertThat(bind("API_ALLOWED_HOSTS=a.com, B.com").allowedHosts()).containsExactly("a.com", "B.com");
    }

    @Test
    void secretHostsKeepsTheBracketedKeyVerbatim() throws IOException {
        assertThat(bind("").secretHosts()).isNullOrEmpty();
        assertThat(bind("jev.datasource.secret-hosts.[DS_ERP_TOKEN]=erp.example.com, erp2.example.com").secretHosts())
            .isEqualTo(Map.of("DS_ERP_TOKEN", List.of("erp.example.com", "erp2.example.com")));
    }
}
