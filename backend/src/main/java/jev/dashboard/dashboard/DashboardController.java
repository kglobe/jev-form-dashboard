package jev.dashboard.dashboard;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.dashboard.DashboardRepository.Share;
import jev.dashboard.dashboard.DashboardRepository.SharedDashboard;
import jev.dashboard.transform.ChartData;
import jev.dashboard.transform.ChartSpec;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

    /** 資料來源共享 spec 4.4：這支、/data、/filter-options 和 /highlight 沒登入也打得到，principal 是 null。 */
    @GetMapping("/{id}")
    public DashboardConfig get(Principal principal, @PathVariable UUID id) {
        return service.view(principal == null ? null : principal.getName(), id);
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
        String viewer = principal == null ? null : principal.getName();
        DashboardConfig c = service.view(viewer, id);
        try {
            return data.display(c.createdBy(), c, f, c.createdBy().equals(viewer) ? Set.of() : data.denied(c.createdBy(), c, f));
        } catch (ApiException e) {
            if (c.createdBy().equals(viewer)) throw e;
            throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
        }
    }

    /**
     * 篩選器 spec 4：能看這份儀表板的人都能用，用擁有者的身分載入；載入失敗的來源跳過，不報錯。
     * 擁有者以外的人只列卡片用到的欄位和預設篩選的欄位（spec 2.3）。
     */
    @GetMapping("/{id}/filter-options")
    public DashboardDataService.FilterOptions filterOptions(Principal principal, @PathVariable UUID id) {
        String viewer = principal == null ? null : principal.getName();
        DashboardConfig c = service.view(viewer, id);
        return data.filterOptions(c.createdBy(), c, !c.createdBy().equals(viewer));
    }

    /**
     * 匯出 spec 3：一張卡的資料下載成 xlsx 或 CSV，內容跟 /data 裡這張卡一樣（同一組篩選、顯示名稱），明細表不截斷。
     * 要登入，SecurityConfig 只放行 /{id}、/data、/filter-options；看得到這份儀表板的帳號都能下載，用擁有者的身分載入。
     * 檢查都在寫檔之前做完。只有載入錯誤會替檢視者換成通用訊息（spec 2.3），「找不到這張圖」和卡片錯誤照原文。
     */
    @GetMapping("/{id}/charts/{chartId}/export")
    public void export(Principal principal, @PathVariable UUID id, @PathVariable String chartId,
                       @RequestParam(required = false) String format, @RequestParam(required = false) String filters,
                       HttpServletResponse response) throws IOException {
        if (!"xlsx".equals(format) && !"csv".equals(format)) throw new ApiException(HttpStatus.BAD_REQUEST, "format 只能是 xlsx 或 csv");
        Filters f = Filters.parse(filters);
        String viewer = principal.getName();
        DashboardConfig c = service.view(viewer, id);
        ChartSpec spec = c.layout().stream().filter(s -> s.chartId().equals(chartId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到這張圖"));
        boolean owner = c.createdBy().equals(viewer);
        ChartData d;
        try {
            d = data.exportData(c.createdBy(), spec, f, owner ? Set.of() : data.denied(c.createdBy(), c, f));
        } catch (ApiException e) {
            if (owner) throw e;
            throw new ApiException(e.status(), "資料來源載入失敗，請聯絡儀表板擁有者");
        }
        // 匯出 spec 2.4：欄位遺失、不是日期、來源已刪除或停止分享
        if (d.error() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "這張圖目前沒有資料可以下載");
        boolean xlsx = format.equals("xlsx");
        if (xlsx) ChartExport.requireXlsxRows(d.rows().size());

        response.setContentType(xlsx ? ChartExport.XLSX_TYPE : ChartExport.CSV_TYPE);
        String name = ChartExport.filename(d.title() != null ? d.title() : spec.title(), format);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
        if (xlsx) ChartExport.xlsx(d, response.getOutputStream());
        else ChartExport.csv(d, response.getOutputStream());
    }

    /** 篩選器 spec 4：預覽不收篩選。 */
    @PostMapping("/preview/data")
    public List<ChartData> preview(Principal principal, @RequestBody DashboardConfig body) {
        DashboardService.requireComplete(body);
        return data.display(principal.getName(), body, null, Set.of());
    }
}
