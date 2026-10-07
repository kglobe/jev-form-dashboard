package jev.dashboard.datasource;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.dashboard.DashboardRepository.DatasourceUse;
import jev.dashboard.dashboard.DashboardService;
import jev.dashboard.datasource.DatasourceRepository.Share;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {
    public record SensitiveFields(List<String> fields) {}
    public record FieldLabels(Map<String, String> labels) {}
    public record RatioList(List<Ratio> ratios) {}
    public record ApiRegistration(String name, String url, Map<String, String> headers) {}
    /** merge 是合併定義，JSON 用 op 分辨疊加（union）和查表（lookup），見 spec 4.2、4.3。 */
    public record MergeRequest(String name, MergeDefinition merge) {}

    public record Ref(UUID id, String name) {}
    /** 刪除前的確認：哪些合併來源直接用它、哪些儀表板有圖用它；shared 是有沒有分享出去（資料來源共享 spec 4.2）。 */
    public record Usage(List<Ref> mergedBy, List<DatasourceUse> dashboards, boolean shared) {}

    private final DatasourceService service;
    private final DashboardService dashboards;

    public DatasourceController(DatasourceService service, DashboardService dashboards) {
        this.service = service;
        this.dashboards = dashboards;
    }

    @PostMapping("/upload")
    public Datasource upload(Principal principal,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam("name") String name,
                             @RequestParam(value = "sheet", required = false) String sheet) {
        return service.upload(principal.getName(), name, file, sheet);
    }

    /** 更新資料 spec 3.1：mode 是 replace（整份覆蓋）或 append（接在後面），只收自己的上傳來源。 */
    @PostMapping("/{id}/rows")
    public Datasource reload(Principal principal, @PathVariable UUID id,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam("mode") String mode,
                             @RequestParam(value = "sheet", required = false) String sheet) {
        return service.reload(principal.getName(), id, file, sheet, mode);
    }

    @PostMapping("/api")
    public Datasource registerApi(Principal principal, @RequestBody ApiRegistration body) {
        return service.registerApi(principal.getName(), body.name(), body.url(), body.headers());
    }

    @PostMapping("/merged/preview")
    public DatasourceService.MergePreview previewMerged(Principal principal,
                                                        @RequestBody MergeRequest body) {
        return service.previewMerged(principal.getName(), body.merge());
    }

    @PostMapping("/merged")
    public Datasource createMerged(Principal principal, @RequestBody MergeRequest body) {
        return service.createMerged(principal.getName(), body.name(), body.merge());
    }

    @GetMapping
    public List<Datasource> list(Principal principal) {
        return service.list(principal.getName());
    }

    @GetMapping("/{id}/usage")
    public Usage usage(Principal principal, @PathVariable UUID id) {
        Share share = service.share(principal.getName(), id);
        return new Usage(service.mergedBy(principal.getName(), id).stream().map(d -> new Ref(d.id(), d.name())).toList(),
            dashboards.usingDatasource(principal.getName(), id), share.allUsers() || !share.users().isEmpty());
    }

    /** 資料來源共享 spec 4.1：只有擁有者，別人 404。 */
    @GetMapping("/{id}/share")
    public Share share(Principal principal, @PathVariable UUID id) {
        return service.share(principal.getName(), id);
    }

    @PutMapping("/{id}/share")
    public Share setShare(Principal principal, @PathVariable UUID id, @RequestBody Share body) {
        return service.setShare(principal.getName(), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal, @PathVariable UUID id) {
        service.delete(principal.getName(), id);
    }

    @PatchMapping("/{id}/sensitive")
    public Datasource sensitive(Principal principal, @PathVariable UUID id,
                                @RequestBody SensitiveFields body) {
        return service.markSensitive(principal.getName(), id, body.fields() == null ? List.of() : body.fields());
    }

    @PatchMapping("/{id}/labels")
    public Datasource labels(Principal principal, @PathVariable UUID id,
                             @RequestBody FieldLabels body) {
        return service.setLabels(principal.getName(), id, body.labels() == null ? Map.of() : body.labels());
    }

    /** B3 spec 第 7 節：整份取代，新增、刪除都用這支。 */
    @PatchMapping("/{id}/ratios")
    public Datasource ratios(Principal principal, @PathVariable UUID id,
                             @RequestBody RatioList body) {
        return service.setRatios(principal.getName(), id, body.ratios() == null ? List.of() : body.ratios());
    }
}
