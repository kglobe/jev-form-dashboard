package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasourceService {
    public record Loaded(Datasource datasource, List<Map<String, Object>> rows) {}

    private final DatasourceRepository repo;
    private final List<DataSourceProvider> providers;
    private final int maxRows;
    private final ApiUrlGuard guard;

    public DatasourceService(DatasourceRepository repo, List<DataSourceProvider> providers, JevProperties props,
                             ApiUrlGuard guard) {
        this.repo = repo;
        this.providers = providers;
        this.maxRows = props.upload().maxRows();
        this.guard = guard;
    }

    @Transactional
    public Datasource upload(String userId, String name, MultipartFile file, String sheet) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        ParsedTable table;
        try {
            if (filename.endsWith(".csv")) table = CsvParser.parse(file.getBytes(), maxRows);
            else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) table = ExcelParser.parse(file.getInputStream(), sheet, maxRows);
            else throw new ApiException(BAD_REQUEST, "只支援 .csv、.xlsx、.xls");
        } catch (IOException e) {
            throw new UploadParseException("讀取檔案失敗：" + e.getMessage());
        }
        checkRows(table.rows().size());
        List<FieldSchema> schema = SchemaInference.infer(table.headers(), table.rows());
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), userId, null);
        repo.insert(ds);
        repo.insertRows(ds.id(), SchemaInference.normalize(table.rows(), schema));
        return get(userId, ds.id());
    }

    public Datasource registerApi(String userId, String name, String url, Map<String, String> headers) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        guard.check(url);
        HttpApiDataSourceProvider.validateHeaders(h);
        Datasource draft = new Datasource(UUID.randomUUID(), name, "api", List.of(), url, h, userId, null);
        List<Map<String, Object>> raw = provider("api").fetchRows(draft);
        checkRows(raw.size());
        Datasource ds = draft.withSchema(SchemaInference.infer(new ArrayList<>(raw.get(0).keySet()), raw));
        repo.insert(ds);
        return get(userId, ds.id());
    }

    public List<Datasource> list(String userId) {
        return repo.list(userId);
    }

    public Datasource get(String userId, UUID id) {
        return repo.find(id, userId).orElseThrow(() -> new ApiException(NOT_FOUND, "找不到資料源"));
    }

    public Datasource markSensitive(String userId, UUID id, Collection<String> fields) {
        Datasource ds = get(userId, id);
        List<FieldSchema> schema = ds.schema().stream().map(f -> f.withSensitive(fields.contains(f.name()))).toList();
        repo.updateSchema(id, schema);
        return ds.withSchema(schema);
    }

    public Loaded load(String userId, UUID id) {
        Datasource ds = get(userId, id);
        List<Map<String, Object>> raw = provider(ds.kind()).fetchRows(ds);
        return new Loaded(ds, "upload".equals(ds.kind()) ? raw : SchemaInference.normalize(raw, ds.schema()));
    }

    void checkRows(int count) {
        if (count == 0) throw new ApiException(BAD_REQUEST, "沒有資料列");
        if (count > maxRows) throw new ApiException(BAD_REQUEST, "資料有 " + count + " 筆，超過上限 " + maxRows + " 筆");
    }

    DataSourceProvider provider(String kind) {
        return providers.stream().filter(p -> p.supports(kind)).findFirst()
            .orElseThrow(() -> new IllegalStateException("沒有處理 " + kind + " 的 DataSourceProvider"));
    }
}
