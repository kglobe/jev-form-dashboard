package jev.dashboard.datasource;

import java.util.List;
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

    @GetMapping
    public List<Datasource> list(@RequestHeader("X-User-Id") String userId) {
        return service.list(userId);
    }

    @PatchMapping("/{id}/sensitive")
    public Datasource sensitive(@RequestHeader("X-User-Id") String userId, @PathVariable UUID id,
                                @RequestBody SensitiveFields body) {
        return service.markSensitive(userId, id, body.fields() == null ? List.of() : body.fields());
    }
}
