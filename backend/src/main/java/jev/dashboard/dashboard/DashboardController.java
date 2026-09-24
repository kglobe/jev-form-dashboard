package jev.dashboard.dashboard;

import java.util.List;
import java.util.UUID;
import jev.dashboard.dashboard.DashboardRepository.DashboardSummary;
import jev.dashboard.transform.ChartData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public DashboardConfig create(@RequestHeader("X-User-Id") String userId, @RequestBody DashboardConfig body) {
        return service.create(userId, body);
    }

    @GetMapping
    public List<DashboardSummary> list(@RequestHeader("X-User-Id") String userId) {
        return service.list(userId);
    }

    @GetMapping("/{id}")
    public DashboardConfig get(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        return service.get(userId, id);
    }

    @PutMapping("/{id}")
    public DashboardConfig update(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                  @RequestBody DashboardConfig body) {
        return service.update(userId, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        service.delete(userId, id);
    }

    @GetMapping("/{id}/data")
    public List<ChartData> data(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id) {
        return data.load(userId, service.get(userId, id));
    }

    @PostMapping("/preview/data")
    public List<ChartData> preview(@RequestHeader("X-User-Id") String userId, @RequestBody DashboardConfig body) {
        DashboardService.requireComplete(body);
        return data.load(userId, body);
    }
}
