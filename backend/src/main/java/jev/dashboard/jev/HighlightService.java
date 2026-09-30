package jev.dashboard.jev;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.stereotype.Service;

@Service
public class HighlightService {
    private static final Logger log = LoggerFactory.getLogger(HighlightService.class);
    static final List<String> SEVERITY = List.of("正常波動", "值得注意", "需要關注", "嚴重警示");
    /** noul 超過這個值才標註；照 JevConfig.TUNED_MODEL（jev-1.13.0）調的，換模型要重新調校。 */
    static final double THRESHOLD = 0.7;

    private final JevClient jev;

    public HighlightService(@Qualifier("highlightJevClient") JevClient jev) {
        this.jev = jev;
    }

    /**
     * 快取的 key 是儀表板加上實際送給 JEV 的內容（每張圖的摘要和題目）：標題、資料、今天算到哪一期變了就重問；
     * 沒被摘要的卡片、資料列的順序不影響。內容一樣，JEV 的答案就一樣，所以不設過期。
     * ponytail: 單機、最多 CACHE_MAX 份的 LRU；後端多台時各自一份，要共用再換 Redis
     */
    static final int CACHE_MAX = 500;
    private final Map<List<Object>, List<Highlight>> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<List<Object>, List<Highlight>> eldest) {
                return size() > CACHE_MAX;
            }
        });

    /** JEV 失敗時回傳 null，不快取，下次再問。 */
    public List<Highlight> check(UUID dashboardId, LocalDate today, List<ChartSpec> specs, List<ChartData> data) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            ChartSpec spec = specs.get(i);
            Map<String, Object> summary = HighlightSummarizer.summarize(spec, data.get(i), today);
            if (summary == null) continue;
            String id = spec.chartId();
            state.put(id, summary);
            questions.put("anomaly_" + id, Question.noul("圖表「" + spec.title() + "」最新一期和前一期相比，有沒有值得標註的異常？"));
            questions.put("severity_" + id, Question.score("圖表「" + spec.title() + "」這次變化的嚴重程度？", SEVERITY));
        }
        if (questions.isEmpty()) return List.of();
        List<Object> key = List.of(dashboardId, state, questions);
        List<Highlight> cached = cache.get(key);
        if (cached != null) return cached;

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
        cache.put(key, out);
        return out;
    }
}
