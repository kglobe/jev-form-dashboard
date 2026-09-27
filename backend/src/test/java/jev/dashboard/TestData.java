package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

public final class TestData {
    private TestData() {}

    public static void clean(JdbcClient jdbc) {
        jdbc.sql("truncate dashboard, datasource_rows, datasource").update();
    }

    public static String uploadSales(MockMvc mvc, String userId) throws Exception {
        byte[] csv;
        try (InputStream in = TestData.class.getResourceAsStream("/sales.csv")) {
            csv = in.readAllBytes();
        }
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", "sales.csv", "text/csv", csv))
                .param("name", "銷售")
                .header("X-User-Id", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    /** 上傳一段 CSV 文字，檔名是「name.csv」，回傳新來源的 id。 */
    public static String uploadCsv(MockMvc mvc, String userId, String name, String csv) throws Exception {
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", name + ".csv", "text/csv", csv.getBytes(UTF_8)))
                .param("name", name)
                .header("X-User-Id", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    /** c1、c2 有時間欄位；c3 沒有；c4 用了不存在的欄位 profit。 */
    public static String dashboardJson(String datasourceId) {
        return """
            {"name":"銷售總覽","datasourceId":"%s","layout":[
              {"chartId":"c1","type":"line","title":"月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","type":"bar","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}},
              {"chartId":"c3","type":"pie","title":"地區佔比","gridPos":{"x":0,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c4","type":"bar","title":"利潤","gridPos":{"x":6,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"profit","aggregate":"sum"}}
            ]}""".formatted(datasourceId);
    }
}
