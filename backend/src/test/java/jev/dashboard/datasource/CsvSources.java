package jev.dashboard.datasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 測試用：幾行 CSV（第一行是表頭）變成一份 u1 的上傳來源，schema 推斷和正規化都跟真的上傳一樣。 */
final class CsvSources {
    private CsvSources() {}

    static DatasourceService.Loaded csv(String name, String... lines) {
        List<String> headers = List.of(lines[0].split(","));
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), cells[j]);
            raw.add(row);
        }
        List<FieldSchema> schema = SchemaInference.infer(headers, raw);
        Datasource ds = new Datasource(UUID.randomUUID(), name, "upload", schema, null, Map.of(), "u1", null);
        return new DatasourceService.Loaded(ds, SchemaInference.normalize(raw, schema));
    }
}
