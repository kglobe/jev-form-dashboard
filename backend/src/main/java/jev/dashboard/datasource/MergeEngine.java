package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Union;

/** 合併來源的規則（spec 第 5、6、9 節）：檢查定義、合併資料列、算出輸出欄位。全是純函式，不碰資料庫。 */
public final class MergeEngine {
    static final int MAX_UNION_INPUTS = 5;
    static final int MAX_NAME_LENGTH = 50;
    static final Map<FieldType, String> TYPE_ZH =
        Map.of(FieldType.NUMBER, "數字", FieldType.DATE, "日期", FieldType.STRING, "文字");

    /** columns 照輸出順序排；warnings 是預覽要顯示的提示。 */
    public record Result(List<Output> columns, List<Map<String, Object>> rows, List<String> warnings) {}

    /** 輸出欄位的名稱、型別（照輸入欄位）和繼承來的顯示名稱。 */
    public record Output(String name, FieldType type, String label) {}

    private MergeEngine() {}

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
        }
        requireName(u.sourceColumn(), taken, "來源欄名稱");
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
            if (has.size() < inputs.size()) {
                warnings.add("「" + c.name() + "」只有" + String.join("、", has) + "有，其他來源的列這欄是空的");
            }
        }
        columns.add(new Output(u.sourceColumn(), FieldType.STRING, null));
        return new Result(columns, rows, warnings);
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
