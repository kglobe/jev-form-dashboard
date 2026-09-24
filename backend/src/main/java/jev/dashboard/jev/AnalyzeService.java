package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeService {
    static final List<String> IMPORTANCE = List.of("不重要，略過", "次要", "重要", "核心指標");
    static final List<String> CHART_COUNTS = List.of("1 張", "2 張", "3 張", "4 張", "5 張", "6 張");
    static final Map<String, String> ROLES = new LinkedHashMap<>();
    static final Map<String, String> ROLE_ZH = Map.of("dimension", "分類", "measure", "數值", "time", "時間");
    static {
        ROLES.put("dimension", "分類維度，用來分組，例如地區、產品");
        ROLES.put("measure", "數值指標，可以加總或平均，例如金額、數量");
        ROLES.put("time", "時間，例如訂單日期");
        ROLES.put("ignore", "忽略，例如流水號、備註");
    }

    private final JevClient jev;

    public AnalyzeService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount) {
        Map<String, Object> state = state(schema, rowCount);

        Map<String, Question> round1 = new LinkedHashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            String field = schema.get(i).name();
            round1.put("imp_" + i, Question.score("欄位「" + field + "」對分析這份資料有多重要？", IMPORTANCE));
            round1.put("role_" + i, Question.choice("欄位「" + field + "」在圖表裡適合扮演什麼角色？", ROLES));
        }
        round1.put("chart_count", Question.score("這份資料適合做成幾張圖表？", CHART_COUNTS));
        Map<String, Answer> a1 = jev.ask(state, round1);

        List<FieldEval> candidates = ConfigAssembler.candidates(ConfigAssembler.evaluate(schema, a1));
        int k = ConfigAssembler.chartCount(a1.get("chart_count"));
        List<Combo> combos = ConfigAssembler.combos(candidates, k);

        Map<String, Answer> a2 = Map.of();
        if (!combos.isEmpty()) {
            Map<String, Question> round2 = new LinkedHashMap<>();
            for (int j = 0; j < combos.size(); j++) {
                Combo c = combos.get(j);
                round2.put("combo_" + j, Question.choice(describe(c), criteria(c)));
            }
            a2 = jev.ask(state, round2);
        }

        List<String> tableColumns = (candidates.isEmpty()
            ? schema.stream().map(FieldSchema::name)
            : candidates.stream().map(FieldEval::name)).toList();
        return new DashboardConfig(null, name, datasourceId, null, ConfigAssembler.assemble(combos, a2, k, tableColumns));
    }

    static Map<String, Object> state(List<FieldSchema> schema, long rowCount) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            if (!f.sensitive()) {
                m.put("distinctCount", f.distinctCount());
                m.put("samples", f.samples());
            }
            fields.add(m);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("rowCount", rowCount);
        state.put("fields", fields);
        return state;
    }

    static String describe(Combo c) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        return "用這組欄位畫一張圖：" + fields + "。選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
    }

    static Map<String, String> criteria(Combo c) {
        Map<String, String> m = new LinkedHashMap<>();
        c.chartTypes().forEach(t -> m.put(t, ConfigAssembler.CHART_TYPES_ZH.get(t)));
        m.put("none", "這組欄位不值得畫圖");
        return m;
    }
}
