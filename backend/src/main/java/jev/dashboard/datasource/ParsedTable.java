package jev.dashboard.datasource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ParsedTable(List<String> headers, List<Map<String, Object>> rows) {
    /** 標題不能空白也不能重複，不然同名欄位的值會互相蓋掉。 */
    static List<String> checkHeaders(List<String> headers) {
        Set<String> seen = new HashSet<>();
        for (String h : headers) {
            if (h == null || h.isBlank()) throw new UploadParseException("欄位名稱不能空白");
            if (!seen.add(h)) throw new UploadParseException("欄位名稱重複：" + h);
        }
        return headers;
    }
}
