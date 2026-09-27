package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasourceService {
    public record Loaded(Datasource datasource, List<Map<String, Object>> rows) {}

    /** spec 第 7 節：預覽的回傳，rows 只放前 20 列。 */
    public record MergePreview(List<FieldSchema> schema, List<Map<String, Object>> rows, int rowCount,
                               List<String> warnings) {}

    static final int MAX_DEPTH = 3;
    static final int PREVIEW_ROWS = 20;

    private final DatasourceRepository repo;
    private final List<DataSourceProvider> providers;
    private final int maxRows;
    private final ApiUrlGuard guard;

    public DatasourceService(DatasourceRepository repo, List<DataSourceProvider> providers, JevProperties props,
                             ApiUrlGuard guard) {
        this.repo = repo;
        this.providers = providers;
        this.maxRows = props.upload().maxRows();
        this.guard = guard;
    }

    @Transactional
    public Datasource upload(String userId, String name, MultipartFile file, String sheet) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        ParsedTable table;
        try {
            if (filename.endsWith(".csv")) table = CsvParser.parse(file.getBytes(), maxRows);
            else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) table = ExcelParser.parse(file.getInputStream(), sheet, maxRows);
            else throw new ApiException(BAD_REQUEST, "只支援 .csv、.xlsx、.xls");
        } catch (IOException e) {
            throw new UploadParseException("讀取檔案失敗：" + e.getMessage());
        }
        checkRows(table.rows().size());
        List<FieldSchema> schema = SchemaInference.infer(table.headers(), table.rows());
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), userId, null);
        repo.insert(ds);
        repo.insertRows(ds.id(), SchemaInference.normalize(table.rows(), schema));
        return get(userId, ds.id());
    }

    public Datasource registerApi(String userId, String name, String url, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        guard.check(url);
        HttpApiDataSourceProvider.validateHeaders(h);
        Datasource draft = new Datasource(UUID.randomUUID(), name, "api", List.of(), url, h, userId, null);
        List<Map<String, Object>> raw = provider("api").fetchRows(draft);
        checkRows(raw.size());
        Datasource ds = draft.withSchema(SchemaInference.infer(new ArrayList<>(SchemaInference.fieldNames(raw)), raw));
        repo.insert(ds);
        return get(userId, ds.id());
    }

    public List<Datasource> list(String userId) {
        List<Datasource> all = repo.list(userId);
        Map<UUID, Datasource> byId = all.stream().collect(Collectors.toMap(Datasource::id, d -> d));
        return all.stream().map(d -> effective(d, byId::get)).toList();
    }

    public Datasource get(String userId, UUID id) {
        return effective(stored(userId, id), i -> repo.find(i, userId).orElse(null));
    }

    /** 資料庫裡存的樣子；改 schema 一律從這裡拿。 */
    private Datasource stored(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(() -> new ApiException(NOT_FOUND, "找不到資料源"));
    }

    public Datasource markSensitive(String userId, UUID id, Collection<String> fields) {
        // spec 6.3：繼承來的不算自己勾的，輸入來源取消時，合併來源才會跟著取消
        Set<String> inherited = get(userId, id).schema().stream().filter(f -> f.sensitiveFrom() != null)
            .map(FieldSchema::name).collect(Collectors.toSet());
        List<FieldSchema> schema = stored(userId, id).schema().stream()
            .map(f -> f.withSensitive(fields.contains(f.name()) && !inherited.contains(f.name()))).toList();
        repo.updateSchema(id, schema);
        return get(userId, id);
    }

    /** labels 是「欄位 → 顯示名稱」；沒列到的欄位、空白的名稱都清掉。 */
    public Datasource setLabels(String userId, UUID id, Map<String, String> labels) {
        Datasource ds = stored(userId, id);
        List<FieldSchema> schema = ds.schema().stream().map(f -> {
            String label = labels.get(f.name());
            return f.withLabel(label == null || label.isBlank() ? null : label.strip());
        }).toList();
        // 圖表的 dataset 用顯示名稱當維度名，重複了 ECharts 會分不出來
        Set<String> seen = new HashSet<>();
        for (FieldSchema f : schema) {
            String shown = f.label() != null ? f.label() : f.name();
            if (!seen.add(shown)) throw new ApiException(BAD_REQUEST, "顯示名稱「" + shown + "」重複");
            if (shown.length() > 50) throw new ApiException(BAD_REQUEST, "顯示名稱最多 50 個字");
        }
        repo.updateSchema(id, schema);
        return get(userId, id);
    }

    public Loaded load(String userId, UUID id) {
        Datasource ds = get(userId, id);
        if ("merged".equals(ds.kind())) {
            List<Map<String, Object>> rows = merge(userId, ds.merge()).rows();
            // spec 5.4：載入時只擋超過上限；0 筆跟其他來源一樣照常回傳
            if (rows.size() > maxRows) checkRows(rows.size());
            return new Loaded(ds, rows);
        }
        List<Map<String, Object>> raw = provider(ds.kind()).fetchRows(ds);
        return new Loaded(ds, "upload".equals(ds.kind()) ? raw : SchemaInference.normalize(raw, ds.schema()));
    }

    /** 不存檔。預覽只看定義，body 裡的名稱用不到。 */
    public MergePreview previewMerged(String userId, MergeDefinition def) {
        MergeEngine.Result r = build(userId, def);
        List<FieldSchema> schema = inherit(def, MergeEngine.schema(r), i -> repo.find(i, userId).orElse(null));
        return new MergePreview(schema, r.rows().subList(0, Math.min(PREVIEW_ROWS, r.rows().size())),
            r.rows().size(), r.warnings());
    }

    /** 建立時照同一套規則再驗證、再合併一次，因為輸入來源可能在預覽之後變了（spec 第 7 節）。 */
    public Datasource createMerged(String userId, String name, MergeDefinition def) {
        if (name == null || name.isBlank()) throw new ApiException(BAD_REQUEST, "請輸入名稱");
        MergeEngine.Result r = build(userId, def);
        Datasource ds = new Datasource(UUID.randomUUID(), name.strip(), "merged", MergeEngine.schema(r), null, Map.of(),
            userId, null, def);
        repo.insert(ds);
        return get(userId, ds.id());
    }

    /** 檢查定義和層數、合併、檢查筆數；預覽和建立共用。 */
    private MergeEngine.Result build(String userId, MergeDefinition def) {
        MergeEngine.validate(def, id -> get(userId, id));
        int depth = 1 + MergeDefinition.inputIds(def).stream().mapToInt(id -> depth(userId, id)).max().orElse(0);
        if (depth > MAX_DEPTH) throw new ApiException(BAD_REQUEST, "合併最多 3 層");
        MergeEngine.Result r = merge(userId, def);
        checkRows(r.rows().size());
        return r;
    }

    /** spec 5.3：上傳和外部 API 是第 0 層，合併來源是輸入來源最大層數加 1。 */
    private int depth(String userId, UUID id) {
        Datasource ds = stored(userId, id);
        if (!"merged".equals(ds.kind())) return 0;
        return 1 + MergeDefinition.inputIds(ds.merge()).stream().mapToInt(i -> depth(userId, i)).max().orElse(0);
    }

    // ponytail: 同一個輸入來源在巢狀結構裡出現兩次，就會載入兩次（spec 5.3）；巢狀合併常見、載入變慢時，再在同一次 load 裡加快取
    private MergeEngine.Result merge(String userId, MergeDefinition def) {
        return switch (def) {
            case Union u -> MergeEngine.union(u, u.inputs().stream().map(id -> load(userId, id)).toList());
            case Lookup l -> MergeEngine.lookup(l, load(userId, l.main()), load(userId, l.lookup()));
        };
    }

    /** spec 6.3：合併來源實際的敏感設定＝自己勾的，或任何一個提供這欄的輸入欄位是敏感的。讀取時才算，不存。 */
    private Datasource effective(Datasource ds, Function<UUID, Datasource> find) {
        return "merged".equals(ds.kind()) ? ds.withSchema(inherit(ds.merge(), ds.schema(), find)) : ds;
    }

    private List<FieldSchema> inherit(MergeDefinition def, List<FieldSchema> schema, Function<UUID, Datasource> find) {
        return schema.stream().map(f -> {
            String from = sensitiveFrom(def, f.name(), find);
            return from == null ? f : f.withInheritedSensitive(from);
        }).toList();
    }

    /** 讓這欄變敏感的來源名稱：巢狀時一路往下，找到真正打開開關的那個來源；都不敏感就是 null。 */
    private String sensitiveFrom(MergeDefinition def, String field, Function<UUID, Datasource> find) {
        for (Map.Entry<UUID, String> e : MergeEngine.sources(def, field).entrySet()) {
            Datasource input = find.apply(e.getKey());
            if (input == null) continue;
            for (FieldSchema f : effective(input, find).schema()) {
                if (f.name().equals(e.getValue()) && f.sensitive()) {
                    return f.sensitiveFrom() != null ? f.sensitiveFrom() : input.name();
                }
            }
        }
        return null;
    }

    void checkRows(int count) {
        if (count == 0) throw new ApiException(BAD_REQUEST, "沒有資料列");
        if (count > maxRows) throw new ApiException(BAD_REQUEST, "資料有 " + count + " 筆，超過上限 " + maxRows + " 筆");
    }

    DataSourceProvider provider(String kind) {
        return providers.stream().filter(p -> p.supports(kind)).findFirst()
            .orElseThrow(() -> new IllegalStateException("沒有處理 " + kind + " 的 DataSourceProvider"));
    }
}
