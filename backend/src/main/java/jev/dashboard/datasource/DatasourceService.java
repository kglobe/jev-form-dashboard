package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasourceService {
    public record Loaded(Datasource datasource, List<Map<String, Object>> rows) {}

    /** spec 第 7 節：預覽的回傳，rows 只放前 20 列。 */
    public record MergePreview(List<FieldSchema> schema, List<Map<String, Object>> rows, int rowCount,
                               List<String> warnings) {}

    static final int MAX_DEPTH = 3;
    static final int PREVIEW_ROWS = 20;
    // 合併來源等輸入來源載入時會卡住執行緒，用 virtual thread 就不會把有限的執行緒池等光
    private static final ExecutorService LOADS = Executors.newVirtualThreadPerTaskExecutor();

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

    /** 解析在交易外面做，解析大檔案時不占資料庫連線；只有寫入是一個交易。 */
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
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), userId, null)
            .withRowCount(table.rows().size());
        repo.insertWithRows(ds, SchemaInference.normalize(table.rows(), schema));
        return get(userId, ds.id());
    }

    public Datasource registerApi(String userId, String name, String url, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        guard.check(url);
        HttpApiDataSourceProvider.validateHeaders(h);
        Datasource draft = new Datasource(UUID.randomUUID(), name, "api", List.of(), url, h, userId, null);
        List<Map<String, Object>> raw = provider("api").fetchRows(draft);
        checkRows(raw.size());
        Datasource ds = draft.withSchema(SchemaInference.infer(new ArrayList<>(SchemaInference.fieldNames(raw)), raw))
            .withRowCount(raw.size());
        repo.insert(ds);
        return get(userId, ds.id());
    }

    public List<Datasource> list(String userId) {
        List<Datasource> all = repo.list(userId);
        Map<UUID, Datasource> byId = all.stream().collect(Collectors.toMap(Datasource::id, d -> d));
        return all.stream().map(d -> effective(d, byId::get)).toList();
    }

    public Datasource get(String userId, UUID id) {
        return effective(stored(userId, id), cachedFinder(userId));
    }

    /** 同一次 effective 計算裡，每個來源 id 只查一次（找不到、不是自己的都快取成 null）。 */
    private Function<UUID, Datasource> cachedFinder(String userId) {
        Map<UUID, Datasource> seen = new HashMap<>();
        return id -> seen.computeIfAbsent(id, i -> repo.find(i, userId).orElse(null));
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
            .map(f -> f.withSensitive(inherited.contains(f.name()) ? f.sensitive() : fields.contains(f.name()))).toList();
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
        // 圖表的 dataset 用顯示名稱當維度名，重複了 ECharts 會分不出來；比率名稱也會當維度名（B3）
        Set<String> seen = ds.ratios().stream().map(Ratio::name).collect(Collectors.toCollection(HashSet::new));
        for (FieldSchema f : schema) {
            String shown = f.label() != null ? f.label() : f.name();
            if (!seen.add(shown)) throw new ApiException(BAD_REQUEST, "顯示名稱「" + shown + "」重複");
            if (shown.length() > 50) throw new ApiException(BAD_REQUEST, "顯示名稱最多 50 個字");
        }
        repo.updateSchema(id, schema);
        return get(userId, id);
    }

    /**
     * B3 spec 4.2：整份取代。名稱去掉頭尾空白，不能跟欄位名稱、顯示名稱或其他比率重複；
     * 分子、分母要是這個來源的兩個不同的數字欄位。存進去的 sensitive 一律是 false，讀取時才算。
     */
    public Datasource setRatios(String userId, UUID id, List<Ratio> ratios) {
        Datasource ds = stored(userId, id);
        Set<String> taken = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (FieldSchema f : ds.schema()) {
            taken.add(f.name());
            if (f.label() != null) taken.add(f.label());
            if (f.type() == FieldType.NUMBER) numbers.add(f.name());
        }
        List<Ratio> out = new ArrayList<>();
        for (Ratio r : ratios) {
            String name = r == null || r.name() == null ? "" : r.name().strip();
            if (name.isEmpty()) throw new ApiException(BAD_REQUEST, "比率名稱不能空白");
            if (name.length() > 50) throw new ApiException(BAD_REQUEST, "比率名稱最多 50 個字");
            if (!taken.add(name)) throw new ApiException(BAD_REQUEST, "比率名稱「" + name + "」跟欄位或其他比率重複");
            if (!numbers.contains(r.numerator())) {
                throw new ApiException(BAD_REQUEST, "分子「" + r.numerator() + "」不是這個來源的數字欄位");
            }
            if (!numbers.contains(r.denominator())) {
                throw new ApiException(BAD_REQUEST, "分母「" + r.denominator() + "」不是這個來源的數字欄位");
            }
            if (r.numerator().equals(r.denominator())) throw new ApiException(BAD_REQUEST, "分子和分母不能是同一個欄位");
            out.add(new Ratio(name, r.numerator(), r.denominator()));
        }
        repo.updateRatios(id, out);
        return get(userId, id);
    }

    public Loaded load(String userId, UUID id) {
        return loader(userId).apply(id);
    }

    public Function<UUID, Loaded> loader(String userId) {
        return new Loader(userId);
    }

    /**
     * 同一個 loader 裡每個來源只載入一次，合併來源裡的輸入來源也算在內，外部 API 不會被重複打；
     * 載入失敗也只試一次，之後用到它的地方都拿到同一個錯誤。一次請求用一個，不跨請求。
     * 合併來源的輸入來源同時開始載入，好幾個外部 API 不必一個等完才換下一個。
     */
    private final class Loader implements Function<UUID, Loaded> {
        private final String userId;
        private final Map<UUID, CompletableFuture<Loaded>> loads = new ConcurrentHashMap<>();

        Loader(String userId) {
            this.userId = userId;
        }

        /** 讓這些來源同時開始載入，不等結果。 */
        void prefetch(List<UUID> ids) {
            ids.forEach(this::start);
        }

        @Override
        public Loaded apply(UUID id) {
            try {
                return start(id).join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }

        private CompletableFuture<Loaded> start(UUID id) {
            return loads.computeIfAbsent(id, i -> CompletableFuture.supplyAsync(() -> fetch(userId, i, this), LOADS));
        }
    }

    private Loaded fetch(String userId, UUID id, Loader load) {
        Datasource ds = get(userId, id);
        if ("merged".equals(ds.kind())) {
            List<Map<String, Object>> rows = merge(ds.merge(), load).rows();
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
        List<FieldSchema> schema = inherit(def, MergeEngine.schema(r), cachedFinder(userId));
        return new MergePreview(schema, r.rows().subList(0, Math.min(PREVIEW_ROWS, r.rows().size())),
            r.rows().size(), r.warnings());
    }

    /** 建立時照同一套規則再驗證、再合併一次，因為輸入來源可能在預覽之後變了（spec 第 7 節）。 */
    public Datasource createMerged(String userId, String name, MergeDefinition def) {
        if (name == null || name.isBlank()) throw new ApiException(BAD_REQUEST, "請輸入名稱");
        MergeEngine.Result r = build(userId, def);
        Datasource ds = new Datasource(UUID.randomUUID(), name.strip(), "merged", MergeEngine.schema(r), null, Map.of(),
            userId, null, def).withRowCount(r.rows().size());
        repo.insert(ds);
        return get(userId, ds.id());
    }

    /** 檢查定義和層數、合併、檢查筆數；預覽和建立共用。 */
    private MergeEngine.Result build(String userId, MergeDefinition def) {
        MergeEngine.validate(def, id -> get(userId, id));
        Map<UUID, Datasource> depthCache = new HashMap<>();
        int depth = 1 + MergeDefinition.inputIds(def).stream().mapToInt(id -> depth(userId, id, depthCache)).max().orElse(0);
        if (depth > MAX_DEPTH) throw new ApiException(BAD_REQUEST, "合併最多 3 層");
        MergeEngine.Result r = merge(def, new Loader(userId));
        checkRows(r.rows().size());
        return r;
    }

    /** spec 5.3：上傳和外部 API 是第 0 層，合併來源是輸入來源最大層數加 1。cache 讓同一個來源在巢狀結構裡只查一次。 */
    private int depth(String userId, UUID id, Map<UUID, Datasource> cache) {
        Datasource ds = cache.computeIfAbsent(id, i -> stored(userId, i));
        if (!"merged".equals(ds.kind())) return 0;
        return 1 + MergeDefinition.inputIds(ds.merge()).stream().mapToInt(i -> depth(userId, i, cache)).max().orElse(0);
    }

    private static MergeEngine.Result merge(MergeDefinition def, Loader load) {
        load.prefetch(MergeDefinition.inputIds(def));
        return switch (def) {
            case Union u -> MergeEngine.union(u, u.inputs().stream().map(load).toList());
            case Lookup l -> MergeEngine.lookup(l, load.apply(l.main()), load.apply(l.lookup()));
        };
    }

    /**
     * spec 6.3：合併來源實際的敏感設定＝自己勾的，或任何一個提供這欄的輸入欄位是敏感的。
     * B3 spec 4.3：分子或分母敏感（含繼承）時，比率也敏感。兩者都是讀取時才算，不存。
     */
    private Datasource effective(Datasource ds, Function<UUID, Datasource> find) {
        Datasource d = "merged".equals(ds.kind()) ? ds.withSchema(inherit(ds.merge(), ds.schema(), find)) : ds;
        Set<String> sensitive = d.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name)
            .collect(Collectors.toSet());
        return d.withRatios(d.ratios().stream()
            .map(r -> r.withSensitive(sensitive.contains(r.numerator()) || sensitive.contains(r.denominator()))).toList());
    }

    private List<FieldSchema> inherit(MergeDefinition def, List<FieldSchema> schema, Function<UUID, Datasource> find) {
        return schema.stream().map(f -> {
            String from = sensitiveFrom(def, f.name(), find);
            return from == null ? f : f.withInheritedSensitive(from);
        }).toList();
    }

    /**
     * 讓這欄變敏感的來源名稱：巢狀時只往下追同一個欄位（不重算整份 schema），
     * 找到真正打開開關的那個來源；都不敏感就是 null。
     */
    private String sensitiveFrom(MergeDefinition def, String field, Function<UUID, Datasource> find) {
        for (Map.Entry<UUID, String> e : MergeEngine.sources(def, field).entrySet()) {
            Datasource input = find.apply(e.getKey());
            if (input == null) continue;
            String deeper = "merged".equals(input.kind()) ? sensitiveFrom(input.merge(), e.getValue(), find) : null;
            if (deeper != null) return deeper;
            if (input.schema().stream().anyMatch(f -> f.name().equals(e.getValue()) && f.sensitive())) return input.name();
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
