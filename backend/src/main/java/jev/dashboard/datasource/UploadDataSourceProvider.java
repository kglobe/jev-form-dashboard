package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UploadDataSourceProvider implements DataSourceProvider {
    private final DatasourceRepository repo;

    public UploadDataSourceProvider(DatasourceRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean supports(String kind) {
        return "upload".equals(kind);
    }

    @Override
    public List<Map<String, Object>> fetchRows(Datasource ds) {
        return repo.rows(ds.id());
    }
}
