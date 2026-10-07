package jev.dashboard.jev;

import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardConfig;
import jev.dashboard.dashboard.DashboardDataService;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.MergeEngine;
import jev.dashboard.datasource.Ratio;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jev")
public class JevController {
    /** intents：需求紀錄（選填），最後一句是這次的需求。current：有值就是調整模式，放目前預覽裡的圖（含明細表）。 */
    /** fields 是使用者勾選想看的欄位，分析時當成焦點欄位優先出圖；沒勾就是空的。 */
    public record AnalyzeRequest(UUID datasourceId, List<String> intents, List<ChartSpec> current, List<String> fields) {}
    /** C2 spec 第 4 節：inputs 的第一個是基準；compare 是對照，JSON 沒寫就是 false。 */
    public record MappingRequest(List<UUID> inputs, boolean compare) {}
    public record MappingResult(List<MappingService.Match> matches) {}

    private final DatasourceService datasources;
    private final AnalyzeService analyzer;
    private final DashboardService dashboards;
    private final DashboardDataService dashboardData;
    private final HighlightService highlights;
    private final MappingService mappings;
    private final Clock clock;

    public JevController(DatasourceService datasources, AnalyzeService analyzer, DashboardService dashboards,
                         DashboardDataService dashboardData, HighlightService highlights, MappingService mappings,
                         Clock clock) {
        this.datasources = datasources;
        this.analyzer = analyzer;
        this.dashboards = dashboards;
        this.dashboardData = dashboardData;
        this.highlights = highlights;
        this.mappings = mappings;
        this.clock = clock;
    }

    @PostMapping("/analyze")
    public DashboardConfig analyze(Principal principal, @RequestBody AnalyzeRequest body) {
        List<String> intents = clean(body.intents());
        DashboardService.requireValidIntents(intents);
        if (body.current() != null) {
            if (intents.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "請輸入想怎麼調整");
            DashboardService.requireValidCards(body.current());
        }
        Datasource ds = datasources.get(principal.getName(), body.datasourceId());
        // 筆數用建立時記下來的，外部 API 不用為了算筆數整批抓回來；V5 之前的外部 API、合併來源沒記，才載入來算
        long rowCount = ds.rowCount() != null ? ds.rowCount() : datasources.load(principal.getName(), ds.id()).rows().size();
        return analyzer.analyze(ds.name() + " 總覽", ds.id(), ds.schema(), rowCount, intents, body.current(),
            body.fields() == null ? Set.of() : body.fields().stream().filter(Objects::nonNull).collect(Collectors.toSet()),
            MergeEngine.comparison(ds), ds.ratios());
    }

    /** 不快取：同樣的來源再按一次，就再問一次（C2 spec 第 4 節）。 */
    @PostMapping("/mapping")
    public MappingResult mapping(Principal principal, @RequestBody MappingRequest body) {
        MergeEngine.requireUnionInputs(body.inputs());
        List<Datasource> inputs = body.inputs().stream().map(id -> datasources.get(principal.getName(), id)).toList();
        return new MappingResult(mappings.suggest(inputs, body.compare()));
    }

    /** spec 4：每句去掉頭尾空白（含全形空白），空白的句子直接丟掉。 */
    static List<String> clean(List<String> intents) {
        if (intents == null) return List.of();
        return intents.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/highlight/{dashboardId}")
    public List<Highlight> highlight(Principal principal, @PathVariable UUID dashboardId) {
        // 資料來源共享 spec 4.4：沒登入也打得到，principal 是 null
        String viewer = principal == null ? null : principal.getName();
        DashboardConfig c = dashboards.view(viewer, dashboardId);
        // 唯讀分享 spec 6.3：敏感欄位和資料都照擁有者的來源；recent 的 key 跟 /data 一樣是擁有者
        String owner = c.createdBy();
        // 每個來源各自的敏感欄位（多來源 spec 第 7 節），加上分子或分母敏感的比率（B3 spec 5.4）
        Map<UUID, Set<String>> sensitive = datasources.list(owner).stream().collect(Collectors.toMap(Datasource::id,
            ds -> Stream.concat(ds.schema().stream().filter(FieldSchema::sensitive).map(FieldSchema::name),
                ds.ratios().stream().filter(Ratio::sensitive).map(Ratio::name)).collect(Collectors.toSet())));

        // 卡片只要用到它自己來源裡任何被標記敏感的欄位就整張跳過，不送進異常檢查；
        // 清單裡找不到的來源查不到敏感設定，也不送。摘要不了的卡片也先拿掉，只為它們用到的來源就不必載入
        List<ChartSpec> eligible = c.layout().stream().filter(spec -> {
            Set<String> fields = sensitive.get(spec.datasourceId());
            return fields != null && HighlightSummarizer.summarizable(spec)
                && UniversalTransformer.referencedFields(spec.mapping()).stream().noneMatch(fields::contains);
        }).toList();
        if (eligible.isEmpty()) return List.of();
        List<ChartData> data;
        try {
            data = dashboardData.load(owner, new DashboardConfig(c.id(), c.name(), c.createdBy(), eligible));
        } catch (ApiException e) {
            // 唯讀分享 spec 11：檢視者看到的錯誤換成通用訊息，跟 /data 一樣
            if (owner.equals(viewer)) throw e;
            throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
        }

        List<Highlight> result = highlights.check(dashboardId, LocalDate.now(clock), eligible, data);
        return result == null ? List.of() : result;
    }
}
