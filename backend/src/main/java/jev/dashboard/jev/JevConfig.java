package jev.dashboard.jev;

import java.time.Clock;
import java.time.Duration;
import jev.dashboard.JevProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JevConfig {
    private static final Logger log = LoggerFactory.getLogger(JevConfig.class);

    /**
     * 各個門檻（ConfigAssembler.MIN_CONFIDENCE、HighlightService.THRESHOLD，以及 Score 四捨五入對到哪一級）
     * 都是照這個版本調的。jevai.wiki/zh-Hant/confidence：換模型 ID 就要重新調校，jev-latest 也可能悄悄變。
     */
    public static final String TUNED_MODEL = "jev-1.13.0";

    JevConfig(JevProperties p) {
        if (!TUNED_MODEL.equals(p.model())) {
            log.warn("jev.model 設成 {}，但門檻是照 {} 調的；換模型後要重新調校門檻", p.model(), TUNED_MODEL);
        }
    }

    /** 分析是使用者按了按鈕在等：429 最多等 10 秒再試一次。 */
    @Bean
    JevClient analyzeJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    /** 異常標註比圖表晚到沒關係，但不要拖太久：429 最多等 2 秒。 */
    @Bean
    JevClient highlightJevClient(JevProperties p) {
        return new JevClient(p.apiUrl(), p.apiKey(), p.model(), Duration.ofSeconds(10), Duration.ofSeconds(2));
    }

    /** 異常檢查要知道「今天」在哪一期；做成 bean 讓測試可以換成固定時間。 */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
