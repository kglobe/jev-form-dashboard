package jev.dashboard.dashboard;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
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
        return service.get(principal.getName(), id);
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

    @GetMapping("/{id}/data")
    public List<ChartData> data(Principal principal, @PathVariable UUID id) {
        return data.display(principal.getName(), service.get(principal.getName(), id));
    }

    @PostMapping("/preview/data")
    public List<ChartData> preview(Principal principal, @RequestBody DashboardConfig body) {
        DashboardService.requireComplete(body);
        return data.display(principal.getName(), body);
    }
}
