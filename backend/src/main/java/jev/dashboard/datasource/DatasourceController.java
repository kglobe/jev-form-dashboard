package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {
    public record SensitiveFields(List<String> fields) {}
    public record FieldLabels(Map<String, String> labels) {}
    public record ApiRegistration(String name, String url, Map<String, String> headers) {}
    /** merge 是合併定義，JSON 用 op 分辨疊加（union）和查表（lookup），見 spec 4.2、4.3。 */
    public record MergeRequest(String name, MergeDefinition merge) {}

    private final DatasourceService service;

    public DatasourceController(DatasourceService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Datasource upload(@RequestHeader("X-User-Id") String userId,
                             @RequestParam("file") MultipartFile file,
                             @RequestParam("name") String name,
                             @RequestParam(value = "sheet", required = false) String sheet) {
        return service.upload(userId, name, file, sheet);
    }

    @PostMapping("/api")
    public Datasource registerApi(@RequestHeader("X-User-Id") String userId, @RequestBody ApiRegistration body) {
        return service.registerApi(userId, body.name(), body.url(), body.headers());
    }

    @PostMapping("/merged/preview")
    public DatasourceService.MergePreview previewMerged(@RequestHeader("X-User-Id") String userId,
                                                        @RequestBody MergeRequest body) {
        return service.previewMerged(userId, body.merge());
    }

    @PostMapping("/merged")
    public Datasource createMerged(@RequestHeader("X-User-Id") String userId, @RequestBody MergeRequest body) {
        return service.createMerged(userId, body.name(), body.merge());
    }

    @GetMapping
    public List<Datasource> list(@RequestHeader("X-User-Id") String userId) {
        return service.list(userId);
    }

    @PatchMapping("/{id}/sensitive")
    public Datasource sensitive(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                @RequestBody SensitiveFields body) {
        return service.markSensitive(userId, id, body.fields() == null ? List.of() : body.fields());
    }

    @PatchMapping("/{id}/labels")
    public Datasource labels(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                             @RequestBody FieldLabels body) {
        return service.setLabels(userId, id, body.labels() == null ? Map.of() : body.labels());
    }
}
