package jev.dashboard.jev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** C2：請 JEV 判斷疊加、對照時哪兩個欄位是同一種資料。JEV 只回答題目，採不採用由這裡的規則決定。 */
@Service
public class MappingService {
    /** 來源 input 的欄位 field，對到基準的欄位 to。 */
    public record Match(UUID input, String field, String to) {}

    static final String NONE = "none";

    private final JevClient jev;

    public MappingService(@Qualifier("analyzeJevClient") JevClient jev) {
        this.jev = jev;
    }

    /** inputs 照輸入順序，第一個是基準（spec 3.1）；沒有要問的欄位就不呼叫 JEV。 */
    public List<Match> suggest(List<Datasource> inputs, boolean compare) {
        Map<String, Question> questions = questions(inputs, compare);
        if (questions.isEmpty()) return List.of();
        return matches(inputs, compare, jev.ask(state(inputs), questions));
    }

    /** k 是來源在輸入裡的位置（從 1 起算），j 是欄位在那個來源 schema 裡的位置。 */
    static String key(int k, int j) {
        return "map_" + k + "_" + j;
    }

    /**
     * spec 3.2：c 的候選是基準裡跟 c 同型別、而且 c 的來源 b 沒有同名欄位的欄位位置。
     * 基準有同名欄位（已經自動對上）、對照模式的數字欄位不問，回空的。候選跟 c 同型別，所以對照時也不會有數字欄位。
     */
    static List<Integer> candidates(List<FieldSchema> base, List<FieldSchema> b, FieldSchema c, boolean compare) {
        if ((compare && c.type() == FieldType.NUMBER) || base.stream().anyMatch(f -> f.name().equals(c.name()))) {
            return List.of();
        }
        Set<String> own = b.stream().map(FieldSchema::name).collect(Collectors.toSet());
        return IntStream.range(0, base.size())
            .filter(i -> base.get(i).type() == c.type() && !own.contains(base.get(i).name()))
            .boxed().toList();
    }

    /** spec 3.3：每個要問的欄位一題 choice；key 和選項都用位置，不用欄位名稱。 */
    static Map<String, Question> questions(List<Datasource> inputs, boolean compare) {
        Datasource base = inputs.get(0);
        Map<String, Question> out = new LinkedHashMap<>();
        for (int k = 1; k < inputs.size(); k++) {
            Datasource b = inputs.get(k);
            for (int j = 0; j < b.schema().size(); j++) {
                FieldSchema c = b.schema().get(j);
                List<Integer> candidates = candidates(base.schema(), b.schema(), c, compare);
                if (candidates.isEmpty()) continue;
                Map<String, String> criteria = new LinkedHashMap<>();
                for (int i : candidates) criteria.put("f" + i, "欄位「" + base.schema().get(i).name() + "」");
                criteria.put(NONE, "都不是，這是另一種資料");
                out.put(key(k, j), Question.choice("來源「" + b.name() + "」的欄位「" + c.name() + "」，跟來源「"
                    + base.name() + "」的哪個欄位是同一種資料？", criteria));
            }
        }
        return out;
    }

    /** spec 3.4：每個欄位跟分析第一輪一樣；沒記筆數的來源不放 rowCount，不為了算筆數去載入資料。不送資料列。 */
    static Map<String, Object> state(List<Datasource> inputs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Datasource ds : inputs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", ds.name());
            if (ds.rowCount() != null) s.put("rowCount", ds.rowCount());
            s.put("fields", ds.schema().stream().map(AnalyzeService::field).toList());
            sources.add(s);
        }
        return Map.of("sources", sources);
    }

    /**
     * spec 3.5：none、沒回答、信心不到 MIN_CONFIDENCE 都不採用。同一個來源裡搶同一個基準欄位時留信心最高的，
     * 一樣高留排前面的；不同來源對到同一個基準欄位都留。結果先照來源、再照欄位在來源裡的順序。
     */
    static List<Match> matches(List<Datasource> inputs, boolean compare, Map<String, Answer> answers) {
        List<FieldSchema> base = inputs.get(0).schema();
        List<Match> out = new ArrayList<>();
        for (int k = 1; k < inputs.size(); k++) {
            List<FieldSchema> b = inputs.get(k).schema();
            Map<Integer, Integer> winner = new HashMap<>(); // 基準欄位位置 → 採用的 b 欄位位置
            for (int j = 0; j < b.size(); j++) {
                Answer a = answers.get(key(k, j));
                if (a == null || a.confidence() == null || a.confidence() < ConfigAssembler.MIN_CONFIDENCE) continue;
                // 選項以外的答案（含 none）當作沒對上
                Integer i = candidates(base, b, b.get(j), compare).stream()
                    .filter(x -> ("f" + x).equals(a.choice())).findFirst().orElse(null);
                if (i == null) continue;
                Integer prev = winner.get(i);
                if (prev == null || a.confidence() > answers.get(key(k, prev)).confidence()) winner.put(i, j);
            }
            UUID id = inputs.get(k).id();
            winner.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> out.add(new Match(id, b.get(e.getValue()).name(), base.get(e.getKey()).name())));
        }
        return out;
    }
}
