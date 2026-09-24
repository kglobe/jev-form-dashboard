package jev.dashboard.jev;

import java.time.Duration;
import jev.dashboard.JevProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JevConfig {
    @Bean
    JevClient analyzeJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(30));
    }

    @Bean
    JevClient highlightJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(10));
    }
}
