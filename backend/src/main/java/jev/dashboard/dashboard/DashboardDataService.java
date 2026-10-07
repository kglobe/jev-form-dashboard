package jev.dashboard.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.datasource.Ratio;
import jev.dashboard.datasource.SchemaInference;
import jev.dashboard.jev.ConfigAssembler;
import jev.dashboard.jev.HighlightSummarizer;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.Mapping;
import jev.dashboard.transform.UniversalTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DashboardDataService {
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);

    private final DatasourceService datasources;
    private final Clock clock;

    /** 篩選器 spec 4：filter-options 的回傳。 */
    public record FilterOptions(List<Filters.Field> fields) {}

    /**
     * 一個來源載入一次的結果。載入失敗時 rows 是 null；name 是查得到的來源名稱，查不到是 null；
     * deleted 是來源本身已經不在（刪除來源 spec 4.1）。
     * ratios 是這個來源的比率定義（B3），dates 是日期欄位（比率的期間對齊用），都交給轉換器。
     * texts 是文字欄位，畫面上的名稱（顯示名稱，沒有就是欄位名稱）對到欄位名稱，照 schema 順序；篩選器用。
     */
    private record Source(String name, List<Map<String, Object>> rows, Set<String> available, Map<String, String> labels,
                          List<Ratio> ratios, List<String> dates, Map<String, String> texts, boolean deleted) {
        static Source failed(String name) {
            return new Source(name, null, null, null, null, null, null, false);
        }

        static Source ofDeleted() {
            return new Source(null, null, null, null, null, null, null, true);
        }
    }

    /** 一張卡套上篩選後的資料列；skipped 是對這張卡沒生效的篩選，全部生效時是 null。 */
    private record Filtered(List<Map<String, Object>> rows, List<String> skipped) {}

    /** 一張卡的原始資料（原始欄位名稱、帶來源名稱），加上它來源的顯示名稱。 */
    private record Built(ChartSpec spec, ChartData data, Map<String, String> labels) {}

    /** 已存儀表板 /data 留給異常檢查的原始資料；at 是 System.nanoTime()。 */
    private record Recent(Map<ChartSpec, ChartData> data, long at) {}

    // 打開已存儀表板時，前端拿到 /data 就接著打 GET /highlight。/data 把異常檢查用得到的卡片原始資料留一下，
    // /highlight 用一次就丟，不必再讀一次資料源（外部 API 不用再抓一次）。明細表、散佈圖可能很大，不留。
    // ponytail: 單機、最多 RECENT_MAX 份、留 RECENT_NANOS；後端多台時 /highlight 打到另一台就照舊重讀
    static final int RECENT_MAX = 100;
    static final long RECENT_NANOS = TimeUnit.MINUTES.toNanos(1);
    private final Map<String, Recent> recent = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Recent> eldest) {
            return size() > RECENT_MAX;
        }
    });

    public DashboardDataService(DatasourceService datasources, Clock clock) {
        this.datasources = datasources;
        this.clock = clock;
    }

    /**
     * 原始欄位名稱的資料；異常檢查用這個，送給 JEV 的是原始名稱。剛打過同一份儀表板、同一組篩選的 /data 就沿用那次的資料。
     * filters 是 null 時不篩選；denied 是這個人不能用的分類篩選（見 denied()），每張卡都不生效。
     */
    public List<ChartData> load(String userId, DashboardConfig c, Filters filters, Set<String> denied) {
        Recent r = c.id() == null ? null : recent.remove(recentKey(userId, c, filters, denied));
        if (r != null && System.nanoTime() - r.at() < RECENT_NANOS && r.data().keySet().containsAll(c.layout())) {
            return c.layout().stream().map(r.data()::get).toList();
        }
        return build(userId, c.layout(), filters, denied).stream().map(Built::data).toList();
    }

    /** 給畫面用：維度名稱和自動產生的標題換成欄位的顯示名稱。filters、denied 同 load()。 */
    public List<ChartData> display(String userId, DashboardConfig c, Filters filters, Set<String> denied) {
        List<Built> built = build(userId, c.layout(), filters, denied);
        if (c.id() != null) {
            Map<ChartSpec, ChartData> keep = new HashMap<>();
            built.stream().filter(b -> HighlightSummarizer.summarizable(b.spec())).forEach(b -> keep.put(b.spec(), b.data()));
            recent.put(recentKey(userId, c, filters, denied), new Recent(keep, System.nanoTime()));
        }
        return built.stream().map(b -> relabelled(b.spec(), b.data(), b.labels())).toList();
    }

    /**
     * 篩選器 spec 5：擁有者:儀表板 id，有篩選時再接篩選的標準 JSON 和不生效的欄位；篩選過的資料不會被沒篩選的 /highlight、
     * 擁有者篩過的資料也不會被檢視者的 /highlight 拿去用。
     */
    private static String recentKey(String userId, DashboardConfig c, Filters filters, Set<String> denied) {
        return userId + ":" + c.id() + (filters == null ? "" : ":" + filters.key() + ":" + String.join("\u0000", new TreeSet<>(denied)));
    }

    /**
     * 篩選器 spec 2.3：擁有者以外的人（唯讀檢視者、沒登入的訪客）只能用卡片 mapping 用到的欄位和預設篩選裡的欄位；
     * 回傳這組篩選裡不能用的分類（畫面上的名稱）。只查來源的 schema，不載入資料列；查不到的來源（已刪除）沒有欄位可用。
     */
    public Set<String> denied(String owner, DashboardConfig c, Filters filters) {
        if (filters == null || filters.fields() == null) return Set.of();
        Set<String> allowed = new HashSet<>();
        for (UUID ds : c.layout().stream().map(ChartSpec::datasourceId).collect(Collectors.toSet())) {
            try {
                allowed.addAll(viewerTexts(texts(datasources.get(owner, ds).schema()), ds, c).keySet());
            } catch (ApiException e) {
                // 已刪除的來源
            }
        }
        return filters.fields().stream().map(Filters.Field::field).filter(f -> !allowed.contains(f)).collect(Collectors.toSet());
    }

    /** 篩選器 spec 2.3：非擁有者在這個來源能用的文字欄位：這個來源的卡片用到的，加上畫面名稱在預設篩選裡的。 */
    private static Map<String, String> viewerTexts(Map<String, String> texts, UUID ds, DashboardConfig c) {
        Set<String> used = c.layout().stream().filter(s -> ds.equals(s.datasourceId()))
            .flatMap(s -> UniversalTransformer.referencedFields(s.mapping()).stream()).collect(Collectors.toSet());
        Set<String> defaults = c.filters() == null || c.filters().fields() == null ? Set.of()
            : c.filters().fields().stream().map(Filters.Field::field).collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>(texts);
        out.entrySet().removeIf(e -> !used.contains(e.getValue()) && !defaults.contains(e.getKey()));
        return out;
    }

    // 「（空白）」排最後，其他照字串排
    private static final Comparator<String> BLANK_LAST =
        Comparator.comparing((String v) -> v.equals(UniversalTransformer.BLANK)).thenComparing(Comparator.naturalOrder());

    /**
     * 篩選器 spec 4：每張卡片來源的文字欄位，照畫面上的名稱合併、值取聯集，欄位照卡片、schema 的順序；
     * 聯集超過 200 個值的欄位不列。載入失敗、已刪除的來源跳過，全部失敗也只是沒有欄位可選。
     * 驗證不收的東西不列：畫面名稱超過 50 字的欄位整欄不列，超過 200 字的值只拿掉那個值。
     * viewer 是擁有者以外的人：每個來源只取 viewerTexts() 的欄位再合併（spec 2.3）。
     */
    public FilterOptions filterOptions(String userId, DashboardConfig c, boolean viewer) {
        Map<UUID, Source> loaded;
        try {
            loaded = sources(userId, c.layout());
        } catch (ApiException e) {
            return new FilterOptions(List.of());
        }
        Map<String, Set<String>> values = new LinkedHashMap<>();
        loaded.forEach((id, src) -> {
            if (src.rows() == null) return;
            (viewer ? viewerTexts(src.texts(), id, c) : src.texts()).forEach((label, field) -> {
                if (label.length() > Filters.MAX_FIELD_LENGTH) return;
                Set<String> set = values.computeIfAbsent(label, k -> new TreeSet<>(BLANK_LAST));
                for (Map<String, Object> row : src.rows()) {
                    if (set.size() > Filters.MAX_VALUES) break; // 已經不會列出來，不必再收
                    String v = text(row.get(field));
                    if (v.length() <= Filters.MAX_VALUE_LENGTH) set.add(v);
                }
            });
        });
        return new FilterOptions(values.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty() && e.getValue().size() <= Filters.MAX_VALUES)
            .map(e -> new Filters.Field(e.getKey(), List.copyOf(e.getValue()))).toList());
    }

    /** 回傳的順序跟 layout 一樣，每一筆都帶來源名稱。 */
    private List<Built> build(String userId, List<ChartSpec> layout, Filters filters, Set<String> denied) {
        Map<UUID, Source> sources = sources(userId, layout);
        LocalDate today = LocalDate.now(clock);
        List<Built> out = new ArrayList<>();
        for (ChartSpec s : layout) {
            Source src = sources.get(s.datasourceId());
            if (src.deleted()) {
                out.add(new Built(s, ChartData.sourceDeleted(s.chartId(), s.type()), Map.of()));
                continue;
            }
            if (src.rows() == null) {
                out.add(new Built(s, ChartData.sourceFailed(s.chartId(), s.type(), src.name()), Map.of()));
                continue;
            }
            ChartData d;
            try {
                Filtered f = filtered(s, src, filters, denied, today);
                d = UniversalTransformer.transform(s, f.rows(), src.available(), src.ratios(), src.dates()).withFilterSkipped(f.skipped());
            } catch (DateTimeParseException e) {
                // 期間篩選遇到不是日期的值：schema 的日期欄位都整理過，只有手動編的卡片 timeGrain 的 x 不是日期才會這樣，跟轉換時一樣
                d = ChartData.notADate(s.chartId(), s.type(), s.mapping().x());
            }
            out.add(new Built(s, d.withSource(src.name()), src.labels()));
        }
        return out;
    }

    /**
     * 篩選器 spec 2.2：分類照畫面上的名稱對到這個來源的文字欄位；期間用卡片 timeGrain 的 x，沒有就用來源第一個日期欄位，
     * 日期是空的列不算。對不到欄位、或在 denied 裡的篩選不生效，記在 skipped，其他篩選照樣生效。
     * 每張卡各自過濾成新的清單，不動 loader 共用的那份資料列：同一個來源的卡片，期間用的日期欄位可能不同。
     * 一個篩選都沒生效時直接用那份資料列，不必複製。
     */
    private static Filtered filtered(ChartSpec s, Source src, Filters filters, Set<String> denied, LocalDate today) {
        if (filters == null) return new Filtered(src.rows(), null);
        List<String> skipped = new ArrayList<>();
        List<Predicate<Map<String, Object>>> keep = new ArrayList<>();
        for (Filters.Field f : filters.fields() == null ? List.<Filters.Field>of() : filters.fields()) {
            String field = src.texts().get(f.field());
            if (field == null || denied.contains(f.field())) {
                skipped.add(f.field());
                continue;
            }
            Set<String> values = Set.copyOf(f.values());
            keep.add(row -> values.contains(text(row.get(field))));
        }
        if (filters.period() != null) {
            String field = s.mapping().timeGrain() != null ? s.mapping().x() : src.dates().isEmpty() ? null : src.dates().getFirst();
            if (field == null) {
                skipped.add(Filters.PERIOD);
            } else {
                LocalDate[] range = filters.range(today);
                keep.add(row -> {
                    Object v = row.get(field);
                    if (v == null) return false;
                    LocalDate d = LocalDate.parse(String.valueOf(v));
                    return !d.isBefore(range[0]) && !d.isAfter(range[1]);
                });
            }
        }
        List<Map<String, Object>> rows = keep.isEmpty() ? src.rows()
            : src.rows().stream().filter(row -> keep.stream().allMatch(k -> k.test(row))).toList();
        return new Filtered(rows, skipped.isEmpty() ? null : skipped);
    }

    /** 分類篩選比對的值：空值歸到「（空白）」，跟分組一樣。 */
    private static String text(Object v) {
        return v == null ? UniversalTransformer.BLANK : String.valueOf(v);
    }

    /**
     * 每個來源只載入一次，卡片直接用的和合併來源裡用到的共用同一份（同一個 loader）。
     * 有來源載入失敗（外部 API 掛掉、超過筆數上限等）時，只有用到它的卡片是 SOURCE_FAILED；
     * 全部來源都失敗（而且沒有一個是已刪除）就丟出第一個錯誤，單一來源的儀表板和預覽跟以前一樣整份報錯。
     */
    private Map<UUID, Source> sources(String userId, List<ChartSpec> layout) {
        Function<UUID, DatasourceService.Loaded> load = datasources.loader(userId);
        Map<UUID, Source> out = new LinkedHashMap<>();
        ApiException first = null;
        for (ChartSpec s : layout) {
            UUID id = s.datasourceId();
            if (out.containsKey(id)) continue;
            try {
                out.put(id, loaded(load.apply(id)));
            } catch (ApiException e) {
                if (first == null) first = e;
                log.warn("資料來源 {} 載入失敗：{}", id, e.getMessage());
                out.put(id, failedSource(userId, id));
            }
        }
        if (first != null && out.values().stream().allMatch(src -> src.rows() == null && !src.deleted())) throw first;
        return out;
    }

    private static Source loaded(DatasourceService.Loaded loaded) {
        List<FieldSchema> schema = loaded.datasource().schema();
        // 資料源改版時欄位會從資料列消失；只要有任何一列還有這個 key 就算欄位還在（上游常省略 null 欄位）
        Set<String> available = loaded.rows().isEmpty()
            ? schema.stream().map(FieldSchema::name).collect(Collectors.toSet())
            : SchemaInference.fieldNames(loaded.rows());
        Map<String, String> labels = schema.stream().filter(f -> f.label() != null)
            .collect(Collectors.toMap(FieldSchema::name, FieldSchema::label));
        List<String> dates = schema.stream().filter(f -> f.type() == FieldType.DATE).map(FieldSchema::name).toList();
        return new Source(loaded.datasource().name(), loaded.rows(), available, labels, loaded.datasource().ratios(), dates,
            texts(schema), false);
    }

    /** 文字欄位：畫面上的名稱（顯示名稱，沒有就是欄位名稱）對到欄位名稱，照 schema 順序。 */
    private static Map<String, String> texts(List<FieldSchema> schema) {
        return schema.stream().filter(f -> f.type() == FieldType.STRING).collect(Collectors.toMap(
            f -> f.label() != null ? f.label() : f.name(), FieldSchema::name, (a, b) -> a, LinkedHashMap::new));
    }

    /** 載入失敗時才查名稱給卡片顯示；來源本身找不到（NOT_FOUND）就是已刪除，其他錯誤只是沒有名稱。 */
    private Source failedSource(String userId, UUID id) {
        try {
            return Source.failed(datasources.get(userId, id).name());
        } catch (ApiException e) {
            return e.status() == HttpStatus.NOT_FOUND ? Source.ofDeleted() : Source.failed(null);
        }
    }

    private static ChartData relabelled(ChartSpec s, ChartData d, Map<String, String> labels) {
        if (labels.isEmpty()) return d;
        List<String> dims = d.dimensions() == null ? null : d.dimensions().stream().map(n -> labels.getOrDefault(n, n)).toList();
        // 只換自動產生的標題；明細表之類另外取名的標題照舊
        String title = s.mapping() != null && s.title() != null && s.title().equals(ConfigAssembler.title(s.type(), s.mapping()))
            ? ConfigAssembler.title(s.type(), relabel(s.mapping(), labels)) : null;
        return d.withLabels(dims, title);
    }

    private static Mapping relabel(Mapping m, Map<String, String> labels) {
        return new Mapping(labels.getOrDefault(m.x(), m.x()), labels.getOrDefault(m.y(), m.y()),
            labels.getOrDefault(m.series(), m.series()), labels.getOrDefault(m.value(), m.value()),
            m.measures() == null ? null : m.measures().stream().map(n -> labels.getOrDefault(n, n)).toList(),
            m.columns(), m.aggregate(), m.timeGrain());
    }
}
