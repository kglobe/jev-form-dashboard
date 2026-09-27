package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardConfig.Basis;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    static final int MAX_INTENTS = 10;
    static final int MAX_INTENT_LENGTH = 200;

    private final DashboardRepository repo;
    private final DatasourceService datasources;

    public DashboardService(DashboardRepository repo, DatasourceService datasources) {
        this.repo = repo;
        this.datasources = datasources;
    }

    /** body 的 intents 變成第一組依據；body 帶了 basis 也不理。 */
    public DashboardConfig create(String userId, DashboardConfig c) {
        requireComplete(c);
        UUID ds = requireBatch(c.layout());
        datasources.get(userId, ds);
        List<Basis> basis = c.intents() == null || c.intents().isEmpty() ? null : List.of(new Basis(ds, c.intents()));
        return get(userId, repo.insert(new DashboardConfig(null, c.name(), null, c.layout(), null, basis), userId));
    }

    public DashboardConfig get(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(DashboardService::notFound);
    }

    public List<DashboardSummary> list(String userId) {
        return repo.list(userId);
    }

    /** 拖拉版面、移除單張圖都走這裡：layout 可以是空的、可以混著不同來源；body 帶了 intents 也不理。 */
    public DashboardConfig update(String userId, UUID id, DashboardConfig c) {
        requireComplete(c);
        requireValidBasis(c.basis());
        get(userId, id); // 別人的儀表板先回「找不到儀表板」，才輪到查來源
        Set<UUID> used = c.layout().stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        for (UUID ds : used) datasources.get(userId, ds);
        // 已經沒有任何圖的來源，它的依據分組跟著刪掉
        List<Basis> basis = c.basis() == null ? List.of()
            : c.basis().stream().filter(b -> used.contains(b.datasourceId())).toList();
        if (!repo.update(id, userId, new DashboardConfig(id, c.name(), null, c.layout(), null, basis.isEmpty() ? null : basis))) {
            throw notFound();
        }
        return get(userId, id);
    }

    public void delete(String userId, UUID id) {
        if (!repo.delete(id, userId)) throw notFound();
    }

    /** 存檔和預覽前檢查每張卡片，免得存進去的配置之後每次載入 /data 都 500。 */
    public static void requireComplete(DashboardConfig c) {
        if (c == null || c.name() == null || c.layout() == null) {
            throw new ApiException(BAD_REQUEST, "name、layout 都必填");
        }
        if (c.name().isBlank()) throw new ApiException(BAD_REQUEST, "儀表板名稱不能空白");
        requireValidCards(c.layout());
        requireValidIntents(c.intents());
    }

    /** 建立和加入時，一批圖就是一次分析的結果：至少一張，而且來自同一個來源。回傳那個來源。卡片要先驗證過。 */
    static UUID requireBatch(List<ChartSpec> layout) {
        if (layout.isEmpty()) throw new ApiException(BAD_REQUEST, "沒有要存入的圖表");
        Set<UUID> sources = layout.stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        if (sources.size() > 1) throw new ApiException(BAD_REQUEST, "一次存入的圖表要來自同一個資料來源");
        return sources.iterator().next();
    }

    /** 每一組依據都要有來源，需求紀錄照 requireValidIntents 的規則。 */
    static void requireValidBasis(List<Basis> basis) {
        if (basis == null) return;
        for (Basis b : basis) {
            if (b == null || b.datasourceId() == null) throw new ApiException(BAD_REQUEST, "依據缺少 datasourceId");
            requireValidIntents(b.intents());
        }
    }

    /** 每張卡片都要 transform() 處理得了；存檔、預覽和「調整」送來的 current 共用同一套訊息。 */
    public static void requireValidCards(List<ChartSpec> layout) {
        Set<String> chartIds = new HashSet<>();
        for (int i = 0; i < layout.size(); i++) {
            ChartSpec s = layout.get(i);
            if (s == null) throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表是空的");
            if (s.chartId() == null || s.chartId().isBlank()) {
                throw new ApiException(BAD_REQUEST, "第 " + (i + 1) + " 張圖表缺少 chartId");
            }
            if (!chartIds.add(s.chartId())) throw new ApiException(BAD_REQUEST, "chartId 重複：" + s.chartId());
            String problem = cardProblem(s);
            if (problem != null) throw new ApiException(BAD_REQUEST, "圖表 " + s.chartId() + "：" + problem);
        }
    }

    /** spec 4、7：需求紀錄最多 10 句、每句最多 200 字、不能是空白。 */
    public static void requireValidIntents(List<String> intents) {
        if (intents == null) return;
        for (String s : intents) {
            if (s == null || s.isBlank()) throw new ApiException(BAD_REQUEST, "需求紀錄不能有空白的句子");
            if (s.length() > MAX_INTENT_LENGTH) throw new ApiException(BAD_REQUEST, "想看什麼最多 200 字");
        }
        if (intents.size() > MAX_INTENTS) throw new ApiException(BAD_REQUEST, "需求紀錄最多 10 句");
    }

    private static String cardProblem(ChartSpec s) {
        String problem = UniversalTransformer.problem(s);
        if (problem != null) return problem;
        GridPos g = s.gridPos();
        if (g == null) return "缺少 gridPos";
        // 前端格線是 12 欄
        if (g.x() < 0 || g.y() < 0 || g.w() < 1 || g.h() < 1 || g.w() > 12 || g.x() > 12 - g.w()) {
            return "gridPos 要 x ≥ 0、y ≥ 0、w ≥ 1、h ≥ 1，而且 x + w ≤ 12";
        }
        // 多來源 spec 4.1：來源記在每張圖上
        if (s.datasourceId() == null) return "缺少 datasourceId";
        return null;
    }

    private static ApiException notFound() {
        return new ApiException(NOT_FOUND, "找不到儀表板");
    }
}
