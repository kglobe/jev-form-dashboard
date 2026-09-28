package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.MergeEngine;
import jev.dashboard.jev.ConfigAssembler.Combo;
import jev.dashboard.jev.ConfigAssembler.FieldEval;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeService {
    static final List<String> IMPORTANCE = List.of("不重要，略過", "次要", "重要", "核心指標");
    static final List<String> CHART_COUNTS = List.of("1 張", "2 張", "3 張", "4 張", "5 張", "6 張");
    static final List<String> RELEVANCE = List.of("無關", "間接相關", "直接相關");
    static final Map<String, String> ROLES = new LinkedHashMap<>();
    static final Map<String, String> KEEP = new LinkedHashMap<>();
    static final Map<String, String> ROLE_ZH = Map.of("dimension", "分類", "measure", "數值", "time", "時間");
    static {
        ROLES.put("dimension", "分類維度，用來分組，例如地區、產品");
        ROLES.put("measure", "數值指標，可以加總或平均，例如金額、數量");
        ROLES.put("time", "時間，例如訂單日期");
        ROLES.put("ignore", "忽略，例如流水號、備註");
        KEEP.put("keep", "保留這張圖");
        KEEP.put("drop", "移除這張圖");
    }

    private final JevClient jev;

    public AnalyzeService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    /** 沒有需求的第一次分析：題目、state 和結果都跟加入需求功能之前一樣。 */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount) {
        return analyze(name, datasourceId, schema, rowCount, List.of(), null);
    }

    /**
     * @param intents 清理過的需求紀錄，最後一句是這次的需求；空的代表沒有需求
     * @param current 調整模式時目前預覽裡的圖（含明細表）；null 是第一次分析
     */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current) {
        return analyze(name, datasourceId, schema, rowCount, intents, current, Set.of());
    }

    /** @param picked 使用者勾選想看的欄位，跟 JEV 判定「直接相關」的欄位一樣當焦點欄位 */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked) {
        return analyze(name, datasourceId, schema, rowCount, intents, current, picked, null);
    }

    /** @param compare 對照來源的對照組和來源欄（B2 spec 5.5）；其他來源是 null */
    public DashboardConfig analyze(String name, UUID datasourceId, List<FieldSchema> schema, long rowCount,
                                   List<String> intents, List<ChartSpec> current, Set<String> picked,
                                   MergeEngine.Comparison compare) {
        String intent = intents.isEmpty() ? null : intents.get(intents.size() - 1);
        Map<String, Object> state = state(schema, rowCount, intents, current);

        Map<String, Question> round1 = new LinkedHashMap<>();
        for (int i = 0; i < schema.size(); i++) {
            String field = schema.get(i).name();
            round1.put("imp_" + i, Question.score("欄位「" + field + "」對分析這份資料有多重要？", IMPORTANCE));
            round1.put("role_" + i, Question.choice("欄位「" + field + "」在圖表裡適合扮演什麼角色？", ROLES));
            if (intent != null) {
                round1.put("rel_" + i, Question.score(
                    "使用者想看：「" + intent + "」。欄位「" + field + "」跟這個需求有多相關？", RELEVANCE));
            }
        }
        if (current == null) {
            round1.put("chart_count", Question.score("這份資料適合做成幾張圖表？", CHART_COUNTS));
        } else {
            // 調整不補一般性的圖，所以不問 chart_count；明細表不問要不要留
            for (ChartSpec s : current) {
                if (!s.type().equals("table")) {
                    round1.put("keep_" + s.chartId(), Question.choice(keepQuestion(intent, s), KEEP));
                }
            }
        }
        Map<String, Answer> a1 = jev.ask(state, round1);

        List<FieldEval> evals = ConfigAssembler.evaluate(schema, a1).stream()
            .map(e -> picked.contains(e.name()) ? new FieldEval(e.field(), e.importance(), e.role(), true) : e)
            // B2 spec 5.5：對照來源的來源欄不拿來畫圖，也不進明細表
            .map(e -> compare != null && e.name().equals(compare.sourceColumn())
                ? new FieldEval(e.field(), e.importance(), "ignore", e.focus()) : e)
            .toList();
        Map<Set<String>, Set<String>> pairs = compare == null ? Map.of() : compare.pairs();
        List<FieldEval> candidates = ConfigAssembler.candidates(evals);
        int k = ConfigAssembler.chartCount(a1.get("chart_count"));
        List<ChartSpec> kept = current == null ? null : ConfigAssembler.kept(current, a1);
        List<Combo> combos = current == null
            ? ConfigAssembler.combos(candidates, k, pairs)
            : ConfigAssembler.adjustCombos(ConfigAssembler.ranked(candidates, pairs), kept);

        Map<String, Answer> a2 = Map.of();
        if (!combos.isEmpty()) {
            Map<String, Question> round2 = new LinkedHashMap<>();
            for (int j = 0; j < combos.size(); j++) {
                Combo c = combos.get(j);
                round2.put("combo_" + j, Question.choice(describe(c, intent), criteria(c)));
            }
            a2 = jev.ask(state, round2);
        }

        List<ChartSpec> layout;
        if (current == null) {
            List<String> tableColumns = (candidates.isEmpty()
                ? schema.stream().map(FieldSchema::name)
                : candidates.stream().map(FieldEval::name)).toList();
            layout = ConfigAssembler.assemble(combos, a2, k, tableColumns);
        } else {
            Set<String> focus = evals.stream().filter(FieldEval::focus).map(FieldEval::name).collect(Collectors.toSet());
            layout = ConfigAssembler.adjust(current, kept, combos, a2, focus);
        }
        // 這次分析的圖都讀同一個來源，調整模式留下來的圖也一樣
        layout = layout.stream().map(s -> s.withDatasourceId(datasourceId)).toList();
        return new DashboardConfig(null, name, null, layout, intents.isEmpty() ? null : intents, null);
    }

    /** 樣本值只是讓 JEV 看出欄位像什麼，截短就夠；一個長備註欄不會把整個請求撐過上限。 */
    static final int MAX_SAMPLE_CHARS = 60;

    static String clip(String sample) {
        return sample == null || sample.length() <= MAX_SAMPLE_CHARS ? sample : sample.substring(0, MAX_SAMPLE_CHARS) + "…";
    }

    static Map<String, Object> state(List<FieldSchema> schema, long rowCount, List<String> intents,
                                     List<ChartSpec> current) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSchema f : schema) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            if (!f.sensitive()) {
                m.put("distinctCount", f.distinctCount());
                m.put("samples", f.samples() == null ? null : f.samples().stream().map(AnalyzeService::clip).toList());
            }
            fields.add(m);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("rowCount", rowCount);
        state.put("fields", fields);
        // spec 5.1：有需求才加；調整模式才列目前的圖，而且只有欄位名稱，沒有任何資料值
        if (!intents.isEmpty()) {
            state.put("intent", intents.get(intents.size() - 1));
            if (intents.size() > 1) state.put("history", intents.subList(0, intents.size() - 1));
        }
        if (current != null) {
            List<Map<String, Object>> charts = new ArrayList<>();
            for (ChartSpec s : current) {
                if (s.type().equals("table")) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("chartId", s.chartId());
                c.put("title", s.title());
                c.put("type", s.type());
                c.put("fields", UniversalTransformer.referencedFields(s.mapping()));
                charts.add(c);
            }
            state.put("current", charts);
        }
        return state;
    }

    static String describe(Combo c, String intent) {
        String fields = c.fields().stream()
            .map(f -> f.name() + "（" + ROLE_ZH.get(f.role()) + "）")
            .collect(Collectors.joining("、"));
        String text = "用這組欄位畫一張圖：" + fields + "。"
            + (c.compare() ? "兩個數值來自不同來源，要放在同一張圖上對照。" : "")
            + "選最適合的圖表類型；如果這組欄位不值得畫，選 none。";
        return intent == null ? text : text + "使用者想看：「" + intent + "」。";
    }

    /** 圖型中文名取 CHART_TYPES_ZH 冒號前面那段，例如「圓餅圖」。 */
    static String keepQuestion(String intent, ChartSpec s) {
        String zh = ConfigAssembler.CHART_TYPES_ZH.get(s.type());
        String typeName = zh.substring(0, zh.indexOf('：'));
        return "使用者最新的要求：「" + intent + "」。目前有一張圖「" + s.title() + "」（" + typeName + "，欄位："
            + String.join("、", UniversalTransformer.referencedFields(s.mapping())) + "），這張圖要保留還是移除？";
    }

    static Map<String, String> criteria(Combo c) {
        Map<String, String> m = new LinkedHashMap<>();
        c.chartTypes().forEach(t -> m.put(t, ConfigAssembler.CHART_TYPES_ZH.get(t)));
        m.put("none", "這組欄位不值得畫圖");
        return m;
    }
}
