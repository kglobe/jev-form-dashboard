package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class HighlightService {
    private static final Logger log = LoggerFactory.getLogger(HighlightService.class);
    static final List<String> SEVERITY = List.of("正常波動", "值得注意", "需要關注", "嚴重警示");
    static final double THRESHOLD = 0.7;

    private final JevClient jev;

    public HighlightService(@Qualifier("highlightJevClient") JevClient jev) {
        this.jev = jev;
    }

    // ponytail: 預設的 ConcurrentMapCache 沒有上限也不會過期；儀表板 × 資料版本變多時換 Caffeine 加 TTL
    @Cacheable(cacheNames = "highlights", key = "#dashboardId + ':' + #fingerprint", unless = "#result == null")
    public List<Highlight> check(UUID dashboardId, int fingerprint, List<ChartSpec> specs, List<ChartData> data) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            ChartSpec spec = specs.get(i);
            Map<String, Object> summary = HighlightSummarizer.summarize(spec, data.get(i));
            if (summary == null) continue;
            String id = spec.chartId();
            state.put(id, summary);
            questions.put("anomaly_" + id, Question.noul("圖表「" + spec.title() + "」最新一期和前一期相比，有沒有值得標註的異常？"));
            questions.put("severity_" + id, Question.score("圖表「" + spec.title() + "」這次變化的嚴重程度？", SEVERITY));
        }
        if (questions.isEmpty()) return List.of();

        Map<String, Answer> answers;
        try {
            answers = jev.ask(state, questions);
        } catch (ApiException e) {
            log.warn("異常檢查失敗，略過標註：{}", e.getMessage());
            return null;
        }

        List<Highlight> out = new ArrayList<>();
        for (String id : state.keySet()) {
            Answer a = answers.get("anomaly_" + id);
            if (a == null || a.noul() == null || a.noul() <= THRESHOLD) continue;
            Answer s = answers.get("severity_" + id);
            int level = s == null || s.score() == null ? 1 : (int) Math.max(0, Math.min(3, Math.round(s.score())));
            out.add(new Highlight(id, SEVERITY.get(level), a.noul()));
        }
        return out;
    }
}
