package jev.dashboard.dashboard;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.dashboard.DashboardRepository.Share;
import jev.dashboard.dashboard.DashboardRepository.SharedDashboard;
import jev.dashboard.transform.ChartData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {
    private final DashboardService service;
    private final DashboardDataService data;

    public DashboardController(DashboardService service, DashboardDataService data) {
        this.service = service;
        this.data = data;
    }

    @PostMapping
    public DashboardConfig create(Principal principal, @RequestBody DashboardConfig body) {
        return service.create(principal.getName(), body);
    }

    @GetMapping
    public List<DashboardSummary> list(Principal principal) {
        return service.list(principal.getName());
    }

    /** 唯讀分享 spec 4.3：分享給我的。固定字串的路徑優先於 /{id}。 */
    @GetMapping("/shared")
    public List<SharedDashboard> shared(Principal principal) {
        return service.shared(principal.getName());
    }

    @GetMapping("/{id}")
    public DashboardConfig get(Principal principal, @PathVariable UUID id) {
        return service.view(principal.getName(), id);
    }

    @PutMapping("/{id}")
    public DashboardConfig update(Principal principal, @PathVariable UUID id,
                                  @RequestBody DashboardConfig body) {
        return service.update(principal.getName(), id, body);
    }

    @PostMapping("/{id}/charts")
    public DashboardConfig append(Principal principal, @PathVariable UUID id,
                                  @RequestBody DashboardConfig body) {
        return service.append(principal.getName(), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal, @PathVariable UUID id) {
        service.delete(principal.getName(), id);
    }

    @GetMapping("/{id}/share")
    public Share share(Principal principal, @PathVariable UUID id) {
        return service.share(principal.getName(), id);
    }

    @PutMapping("/{id}/share")
    public Share setShare(Principal principal, @PathVariable UUID id, @RequestBody Share body) {
        return service.setShare(principal.getName(), id, body);
    }

    /**
     * 唯讀分享 spec 4.5：用擁有者的身分載入；檢視者看到的錯誤換成通用訊息，不露出擁有者那邊的細節。
     * 篩選器 spec 4：filters 是 URL 編碼過的 JSON，沒帶就是不篩選（不會自動套預設）。
     * 篩選器 spec 2.3：擁有者以外的人（沒登入的也算）只能用卡片用到的欄位和預設篩選的欄位，其他的不生效。
     */
    @GetMapping("/{id}/data")
    public List<ChartData> data(Principal principal, @PathVariable UUID id, @RequestParam(required = false) String filters) {
        Filters f = Filters.parse(filters);
        DashboardConfig c = service.view(principal.getName(), id);
        boolean viewer = principal == null || !c.createdBy().equals(principal.getName());
        try {
            return data.display(c.createdBy(), c, f, viewer ? data.denied(c.createdBy(), c, f) : Set.of());
        } catch (ApiException e) {
            if (c.createdBy().equals(principal.getName())) throw e;
            throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
        }
    }

    /**
     * 篩選器 spec 4：能看這份儀表板的人都能用，用擁有者的身分載入；載入失敗的來源跳過，不報錯。
     * 擁有者以外的人只列卡片用到的欄位和預設篩選的欄位（spec 2.3）。
     */
    @GetMapping("/{id}/filter-options")
    public DashboardDataService.FilterOptions filterOptions(Principal principal, @PathVariable UUID id) {
        DashboardConfig c = service.view(principal.getName(), id);
        return data.filterOptions(c.createdBy(), c, principal == null || !c.createdBy().equals(principal.getName()));
    }

    /** 篩選器 spec 4：預覽不收篩選。 */
    @PostMapping("/preview/data")
    public List<ChartData> preview(Principal principal, @RequestBody DashboardConfig body) {
        DashboardService.requireComplete(body);
        return data.display(principal.getName(), body, null, Set.of());
    }
}
