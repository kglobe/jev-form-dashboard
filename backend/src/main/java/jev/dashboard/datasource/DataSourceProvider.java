package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;

/** 宿主系統實作這個介面、註冊成 Spring bean，就能接自家的資料來源。 */
public interface DataSourceProvider {
    boolean supports(String kind);

    /** 回傳原始資料列；上傳檔案的資料列在存檔時已經正規化，其他來源由 DatasourceService 依 schema 正規化。 */
    List<Map<String, Object>> fetchRows(Datasource ds);
}
