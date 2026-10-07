package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.auth.Account;
import jev.dashboard.auth.AccountRepository;
import jev.dashboard.dashboard.DashboardConfig.Basis;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.dashboard.DashboardRepository.DatasourceUse;
import jev.dashboard.dashboard.DashboardRepository.Share;
import jev.dashboard.dashboard.DashboardRepository.SharedDashboard;
import jev.dashboard.datasource.DatasourceService;
import jev.dashboard.jev.ConfigAssembler;
import jev.dashboard.transform.ChartSpec;
import jev.dashboard.transform.ChartSpec.GridPos;
import jev.dashboard.transform.UniversalTransformer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    static final int MAX_INTENTS = 10;
    static final int MAX_INTENT_LENGTH = 200;

    private final DashboardRepository repo;
    private final DatasourceService datasources;
    private final AccountRepository accounts;

    public DashboardService(DashboardRepository repo, DatasourceService datasources, AccountRepository accounts) {
        this.repo = repo;
        this.datasources = datasources;
        this.accounts = accounts;
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

    /**
     * 唯讀分享 spec 6.3：唯讀的存取檢查，回傳的 createdBy 就是擁有者。get 照舊只認擁有者。
     * 資料來源共享 spec 4.4：userId 是 null（沒登入）時只認「不用登入也能看」的。
     */
    public DashboardConfig view(String userId, UUID id) {
        return (userId == null ? repo.findAnonymous(id) : repo.findViewable(id, userId)).orElseThrow(DashboardService::notFound);
    }

    public List<DashboardSummary> list(String userId) {
        return repo.list(userId);
    }

    public List<DatasourceUse> usingDatasource(String userId, UUID datasourceId) {
        return repo.usingDatasource(userId, datasourceId);
    }

    /** 拖拉版面、移除單張圖都走這裡：layout 可以是空的、可以混著不同來源；body 帶了 intents 也不理。 */
    public DashboardConfig update(String userId, UUID id, DashboardConfig c) {
        requireComplete(c);
        requireValidBasis(c.basis());
        DashboardConfig stored = get(userId, id); // 別人的儀表板先回「找不到儀表板」，才輪到查來源
        Set<UUID> used = c.layout().stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        // 刪除來源 spec 4.2：原本版面裡的來源可能已經刪掉，只檢查這次新帶進來的
        Set<UUID> existing = stored.layout().stream().map(ChartSpec::datasourceId).collect(Collectors.toSet());
        for (UUID ds : used) if (!existing.contains(ds)) datasources.get(userId, ds);
        // 已經沒有任何圖的來源，它的依據分組跟著刪掉
        List<Basis> basis = c.basis() == null ? List.of()
            : c.basis().stream().filter(b -> used.contains(b.datasourceId())).toList();
        if (!repo.update(id, userId, new DashboardConfig(id, c.name(), null, c.layout(), null, basis.isEmpty() ? null : basis))) {
            throw notFound();
        }
        return get(userId, id);
    }

    /**
     * 加到既有儀表板（多來源 spec 6.3）：這批圖的 chartId 接著既有的編號，整批往下移到既有圖的最下面，
     * 彼此的相對位置不變；有需求紀錄就追加一組依據，名稱不變。
     * 讀到寫在同一個 transaction，讀的時候鎖住那一列，兩次同時加入不會吃掉其中一批。
     */
    @Transactional
    public DashboardConfig append(String userId, UUID id, DashboardConfig batch) {
        DashboardConfig current = repo.findForUpdate(id, userId).orElseThrow(DashboardService::notFound);
        List<ChartSpec> charts = batch == null || batch.layout() == null ? List.of() : batch.layout();
        requireValidCards(charts);
        UUID ds = requireBatch(charts);
        List<String> intents = batch == null ? null : batch.intents();
        requireValidIntents(intents);
        datasources.get(userId, ds);

        int n = ConfigAssembler.maxChartNumber(current.layout());
        int offset = current.layout().stream().mapToInt(s -> s.gridPos().y() + s.gridPos().h()).max().orElse(0);
        int top = charts.stream().mapToInt(s -> s.gridPos().y()).min().orElse(0);
        List<ChartSpec> layout = new ArrayList<>(current.layout());
        for (ChartSpec s : charts) {
            GridPos g = s.gridPos();
            layout.add(new ChartSpec("c" + (++n), s.type(), s.title(), new GridPos(g.x(), g.y() - top + offset, g.w(), g.h()),
                s.mapping(), s.options(), s.datasourceId()));
        }
        List<Basis> basis = new ArrayList<>(current.basis() == null ? List.of() : current.basis());
        if (intents != null && !intents.isEmpty()) basis.add(new Basis(ds, intents));
        repo.update(id, userId, new DashboardConfig(id, current.name(), null, layout, null, basis.isEmpty() ? null : basis));
        return get(userId, id);
    }

    public void delete(String userId, UUID id) {
        if (!repo.delete(id, userId)) throw notFound();
    }

    /** 唯讀分享 spec 4.3：只有擁有者看得到分享設定，別人 404。 */
    public Share share(String userId, UUID id) {
        get(userId, id);
        return repo.share(id);
    }

    /**
     * 唯讀分享 spec 4.3：整份取代。users 沒給當作空的，丟掉 null 和空白，照 2.1 轉換、去重複、排序；
     * 停用的帳號可以加。更新 public_link、刪掉舊的、寫入新的在同一個 transaction。
     * 資料來源共享 spec 4.3：anonymous 要先打開公開連結。
     */
    @Transactional
    public Share setShare(String userId, UUID id, Share s) {
        get(userId, id);
        if (s.anonymous() && !s.publicLink()) throw new ApiException(BAD_REQUEST, "要先打開公開連結");
        Set<String> users = new TreeSet<>();
        if (s.users() != null) {
            s.users().stream().filter(Objects::nonNull).filter(u -> !u.isBlank()).map(Account::normalize).forEach(users::add);
        }
        if (users.contains(userId)) throw new ApiException(BAD_REQUEST, "不用分享給自己");
        for (String u : users) {
            if (accounts.find(u).isEmpty()) {
                throw new ApiException(BAD_REQUEST, "找不到帳號「" + u + "」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結");
            }
        }
        repo.setShare(id, new Share(s.publicLink(), s.anonymous(), List.copyOf(users)));
        return repo.share(id);
    }

    /** 唯讀分享 spec 4.3：分享給我的。 */
    public List<SharedDashboard> shared(String userId) {
        return repo.sharedWith(userId);
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
