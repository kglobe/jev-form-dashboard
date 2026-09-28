package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Union;

/** 合併來源的規則（spec 第 5、6、9 節）：檢查定義、合併資料列、算出輸出欄位。全是純函式，不碰資料庫。 */
public final class MergeEngine {
    static final int MAX_UNION_INPUTS = 5;
    static final int MAX_NAME_LENGTH = 50;
    static final int MAX_KEYS = 3;
    static final Map<FieldType, String> TYPE_ZH =
        Map.of(FieldType.NUMBER, "數字", FieldType.DATE, "日期", FieldType.STRING, "文字");

    /** columns 照輸出順序排；warnings 是預覽要顯示的提示。 */
    public record Result(List<Output> columns, List<Map<String, Object>> rows, List<String> warnings) {}

    /** 輸出欄位的名稱、型別（照輸入欄位）和繼承來的顯示名稱。 */
    public record Output(String name, FieldType type, String label) {}

    /**
     * B2 spec 4.2、5.5：pairs 是對照組（來自不同來源的兩個數字欄位，名稱組成的 Set）→ 可以當 x 軸的欄位，
     * 也就是兩個數值的來源都有提供的欄位；sourceColumn 是來源欄名稱。
     */
    public record Comparison(Map<Set<String>, Set<String>> pairs, String sourceColumn) {}

    private MergeEngine() {}

    /** 依 op 分派；def 是 null 代表 request 沒帶 merge。 */
    public static void validate(MergeDefinition def, Function<UUID, Datasource> input) {
        switch (def) {
            case null -> throw bad("請選擇合併方式");
            case Union u -> validateUnion(u, input);
            case Lookup l -> validateLookup(l, input);
        }
    }

    /** spec 第 9 節。input 依 id 取輸入來源，找不到或不是這位使用者的，由 input 自己丟 404。 */
    public static void validateUnion(Union u, Function<UUID, Datasource> input) {
        List<UUID> ids = u.inputs() == null ? List.of() : u.inputs();
        if (ids.size() < 2 || ids.size() > MAX_UNION_INPUTS) throw bad("疊加要選 2 到 5 個來源");
        // List.of 的 contains(null) 會丟 NPE，用 anyMatch 檢查
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw bad("疊加的來源不能重複");
        Map<UUID, Datasource> byId = new LinkedHashMap<>();
        for (UUID id : ids) byId.put(id, input.apply(id));
        if (u.columns() == null || u.columns().isEmpty()) throw bad("至少要有一個輸出欄位");
        Set<String> taken = new HashSet<>();
        Set<UUID> numberSources = new HashSet<>(); // 對照：提供數字欄位的來源
        for (Union.Column c : u.columns()) {
            requireName(c == null ? null : c.name(), taken, "欄位名稱");
            if (c.from() == null || c.from().isEmpty()) throw bad("欄位「" + c.name() + "」至少要對應一個來源");
            FieldSchema first = null;
            Datasource firstSource = null;
            for (Map.Entry<UUID, String> e : c.from().entrySet()) {
                Datasource ds = byId.get(e.getKey());
                if (ds == null) throw bad("欄位「" + c.name() + "」對應到不在疊加裡的來源");
                FieldSchema f = field(ds, e.getValue());
                if (first == null) {
                    first = f;
                    firstSource = ds;
                } else if (f.type() != first.type()) {
                    throw bad("「" + c.name() + "」在" + firstSource.name() + "是" + TYPE_ZH.get(first.type())
                        + "、在" + ds.name() + "是" + TYPE_ZH.get(f.type()) + "，不能併成同一欄");
                }
            }
            // B2 spec 5.1：對照時數字欄位依來源分開
            if (u.compare() && first.type() == FieldType.NUMBER) {
                if (c.from().size() > 1) throw bad("對照時，數字欄位「" + c.name() + "」只能對應一個來源");
                numberSources.add(c.from().keySet().iterator().next());
            }
        }
        if (u.compare() && numberSources.size() < 2) throw bad("對照至少要有兩個來源各提供一個數字欄位");
        requireName(u.sourceColumn(), taken, "來源欄名稱");
    }

    public static void validateLookup(Lookup l, Function<UUID, Datasource> input) {
        if (l.main() == null || l.lookup() == null) throw bad("查表要選主來源和主檔");
        if (l.main().equals(l.lookup())) throw bad("主來源和主檔不能是同一個");
        Datasource main = input.apply(l.main());
        Datasource lookup = input.apply(l.lookup());
        if (l.keys() == null || l.keys().isEmpty() || l.keys().size() > MAX_KEYS || l.keys().stream().anyMatch(Objects::isNull)) {
            throw bad("對接欄位要 1 到 3 組");
        }
        for (Lookup.Key k : l.keys()) {
            FieldSchema a = field(main, k.main());
            FieldSchema b = field(lookup, k.lookup());
            if (a.type() != b.type()) {
                throw bad("對接欄位「" + a.name() + "」是" + TYPE_ZH.get(a.type()) + "、「" + b.name() + "」是"
                    + TYPE_ZH.get(b.type()) + "，型別要相同");
            }
        }
        if (l.bring() == null || l.bring().isEmpty()) throw bad("至少要補上一個欄位");
        Set<String> mainNames = main.schema().stream().map(FieldSchema::name).collect(Collectors.toSet());
        Set<String> taken = new HashSet<>();
        for (Lookup.Bring b : l.bring()) {
            String name = b == null ? null : b.name();
            if (name != null && mainNames.contains(name)) throw bad("欄位名稱「" + name + "」跟主來源的欄位重複");
            requireName(name, taken, "欄位名稱");
            field(lookup, b.from());
        }
    }

    /** spec 5.1：照 inputs 的順序接起來；沒對應或值是空的就不放 key，最後一欄標出來源名稱。 */
    public static Result union(Union u, List<Loaded> inputs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Loaded in : inputs) {
            UUID id = in.datasource().id();
            for (Map<String, Object> row : in.rows()) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Union.Column c : u.columns()) {
                    String from = c.from().get(id);
                    Object v = from == null ? null : row.get(from);
                    if (v != null) out.put(c.name(), v);
                }
                out.put(u.sourceColumn(), in.datasource().name());
                rows.add(out);
            }
        }
        List<Output> columns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Union.Column c : u.columns()) {
            FieldType type = null;
            String label = null;
            List<String> has = new ArrayList<>();
            for (Loaded in : inputs) {
                String from = c.from().get(in.datasource().id());
                if (from == null) continue;
                has.add(in.datasource().name());
                FieldSchema f = find(in.datasource(), from);
                if (f == null) continue; // 外部 API 輸入後來少了這欄（spec 第 9 節）：值是空的
                if (type == null) type = f.type();
                if (label == null) label = f.label();
            }
            columns.add(new Output(c.name(), type == null ? FieldType.STRING : type, label));
            // B2 spec 5.2：對照的數字欄位本來就只來自一個來源，不出提示
            if (has.size() < inputs.size() && !(u.compare() && type == FieldType.NUMBER)) {
                warnings.add("「" + c.name() + "」只有" + String.join("、", has) + "有，其他來源的列這欄是空的");
            }
        }
        columns.add(new Output(u.sourceColumn(), FieldType.STRING, null));
        return new Result(columns, rows, warnings);
    }

    /** spec 5.2：left join，主來源一列不增也不減；主檔的對接值重複時用排最前面的那一列。 */
    public static Result lookup(Lookup l, Loaded main, Loaded lookup) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (Map<String, Object> row : lookup.rows()) {
            String k = key(row, l.keys(), Lookup.Key::lookup);
            if (k != null && index.putIfAbsent(k, row) != null) duplicated.add(k);
        }
        List<Map<String, Object>> rows = new ArrayList<>(main.rows().size());
        int unmatched = 0;
        for (Map<String, Object> row : main.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            String k = key(row, l.keys(), Lookup.Key::main);
            Map<String, Object> hit = k == null ? null : index.get(k);
            if (hit == null) {
                unmatched++;
            } else {
                for (Lookup.Bring b : l.bring()) {
                    Object v = hit.get(b.from());
                    if (v != null) out.put(b.name(), v);
                }
            }
            rows.add(out);
        }
        List<Output> columns = new ArrayList<>();
        for (FieldSchema f : main.datasource().schema()) columns.add(new Output(f.name(), f.type(), f.label()));
        for (Lookup.Bring b : l.bring()) {
            FieldSchema f = find(lookup.datasource(), b.from());
            columns.add(new Output(b.name(), f == null ? FieldType.STRING : f.type(), f == null ? null : f.label()));
        }
        List<String> warnings = new ArrayList<>();
        if (!duplicated.isEmpty()) warnings.add("主檔有 " + duplicated.size() + " 個對接值重複，用排最前面的那一列");
        if (unmatched > 0) warnings.add(unmatched + " 筆對不到主檔，補上的欄位是空的");
        return new Result(columns, rows, warnings);
    }

    /** 所有對接欄位都有值才算得出 key；數字去掉多餘的小數零，1 和 1.0 算同一個值。 */
    static String key(Map<String, Object> row, List<Lookup.Key> keys, Function<Lookup.Key, String> side) {
        StringBuilder sb = new StringBuilder();
        for (Lookup.Key k : keys) {
            Object v = row.get(side.apply(k));
            if (v == null) return null;
            sb.append(v instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros().toPlainString() : v)
                .append('\u0000');
        }
        return sb.toString();
    }

    /**
     * spec 6.1、6.2：型別照輸入欄位，相異值、樣本、日期範圍從合併後的資料列算。
     * 繼承的顯示名稱跟任何一個欄位名稱、或前面欄位的顯示名稱重複，就不繼承。
     */
    public static List<FieldSchema> schema(Result r) {
        List<String> names = r.columns().stream().map(Output::name).toList();
        List<FieldSchema> inferred = SchemaInference.infer(names, r.rows());
        Set<String> used = new HashSet<>(names);
        List<FieldSchema> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Output o = r.columns().get(i);
            FieldSchema f = inferred.get(i);
            String label = o.label() != null && used.add(o.label()) ? o.label() : null;
            boolean date = o.type() == FieldType.DATE;
            out.add(new FieldSchema(o.name(), o.type(), f.distinctCount(), f.samples(), false,
                date ? f.min() : null, date ? f.max() : null, label));
        }
        return out;
    }

    /** 不是對照來源回 null。型別看合併來源自己的 schema；只看最上層的定義。 */
    public static Comparison comparison(Datasource ds) {
        if (!(ds.merge() instanceof Union u) || !u.compare()) return null;
        Set<String> numbers = ds.schema().stream().filter(f -> f.type() == FieldType.NUMBER)
            .map(FieldSchema::name).collect(Collectors.toSet());
        List<Union.Column> measured = u.columns().stream()
            .filter(c -> numbers.contains(c.name()) && c.from().size() == 1).toList();
        Map<Set<String>, Set<String>> pairs = new HashMap<>();
        for (int i = 0; i < measured.size(); i++) {
            for (int j = i + 1; j < measured.size(); j++) {
                Set<UUID> a = measured.get(i).from().keySet();
                Set<UUID> b = measured.get(j).from().keySet();
                if (a.equals(b)) continue;
                // 只有一邊有的欄位當 x 軸，另一個數值整條都是空的
                Set<String> axes = u.columns().stream()
                    .filter(c -> c.from().keySet().containsAll(a) && c.from().keySet().containsAll(b))
                    .map(Union.Column::name).collect(Collectors.toSet());
                pairs.put(Set.of(measured.get(i).name(), measured.get(j).name()), axes);
            }
        }
        return new Comparison(pairs, u.sourceColumn());
    }

    /** spec 6.3：提供這個輸出欄位的輸入欄位，輸入來源 id → 欄位名稱；來源欄沒有輸入欄位。 */
    public static Map<UUID, String> sources(MergeDefinition def, String field) {
        return switch (def) {
            case Union u -> u.columns().stream().filter(c -> c.name().equals(field)).findFirst()
                .map(Union.Column::from).orElse(Map.of());
            case Lookup l -> l.bring().stream().filter(b -> b.name().equals(field)).findFirst()
                .map(b -> Map.of(l.lookup(), b.from())).orElse(Map.of(l.main(), field));
        };
    }

    static FieldSchema field(Datasource ds, String name) {
        FieldSchema f = find(ds, name);
        if (f == null) throw bad(ds.name() + "沒有 " + name + " 這個欄位");
        return f;
    }

    private static FieldSchema find(Datasource ds, String name) {
        return ds.schema().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    private static void requireName(String name, Set<String> taken, String what) {
        if (name == null || name.isBlank()) throw bad(what + "不能空白");
        if (name.length() > MAX_NAME_LENGTH) throw bad(what + "最多 50 個字");
        if (!taken.add(name)) throw bad("欄位名稱「" + name + "」重複");
    }

    private static ApiException bad(String message) {
        return new ApiException(BAD_REQUEST, message);
    }
}
