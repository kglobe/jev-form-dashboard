package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

public final class TestData {
    private TestData() {}

    /** 測試帳號的密碼；PASSWORD_HASH 是事先算好的 bcrypt（cost 4），clean 不必每次重算。 */
    public static final String PASSWORD = "test-password-1";
    public static final String PASSWORD_HASH = "{bcrypt}$2a$04$YjwA8WPDUz9gUv6VAUQbOudjRmUno73kxXve8EeD/tOS7NvU546tO";

    public static void clean(JdbcClient jdbc) {
        jdbc.sql("truncate dashboard_share, account, dashboard, datasource_rows, datasource").update();
        for (String u : List.of("u1", "u2", "u3")) addAccount(jdbc, u, false);
    }

    public static void addAccount(JdbcClient jdbc, String username, boolean admin) {
        jdbc.sql("insert into account (username, password_hash, admin) values (:u, :h, :a)")
            .param("u", username).param("h", PASSWORD_HASH).param("a", admin).update();
    }

    /** 以這個帳號登入加上 CSRF token（唯讀分享 spec 8.1）。帳號要先在 account 表裡。 */
    public static RequestPostProcessor as(String username) {
        return req -> csrf().postProcessRequest(user(username).postProcessRequest(req));
    }

    public static String uploadSales(MockMvc mvc, String userId) throws Exception {
        byte[] csv;
        try (InputStream in = TestData.class.getResourceAsStream("/sales.csv")) {
            csv = in.readAllBytes();
        }
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", "sales.csv", "text/csv", csv))
                .param("name", "銷售")
                .with(TestData.as(userId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    /** 上傳一段 CSV 文字，檔名是「name.csv」，回傳新來源的 id。 */
    public static String uploadCsv(MockMvc mvc, String userId, String name, String csv) throws Exception {
        String body = mvc.perform(multipart("/api/datasources/upload")
                .file(new MockMultipartFile("file", name + ".csv", "text/csv", csv.getBytes(UTF_8)))
                .param("name", name)
                .with(TestData.as(userId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        return JsonPath.read(body, "$.id");
    }

    /** c1、c2 有時間欄位；c3 沒有；c4 用了不存在的欄位 profit。每張圖都讀 datasourceId 這個來源。 */
    public static String dashboardJson(String datasourceId) {
        return """
            {"name":"銷售總覽","layout":[
              {"chartId":"c1","datasourceId":"%1$s","type":"line","title":"月銷售額","gridPos":{"x":0,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"amount","aggregate":"sum"}},
              {"chartId":"c2","datasourceId":"%1$s","type":"bar","title":"月數量","gridPos":{"x":6,"y":0,"w":6,"h":4},
               "mapping":{"x":"order_date","timeGrain":"month","value":"qty","aggregate":"sum"}},
              {"chartId":"c3","datasourceId":"%1$s","type":"pie","title":"地區佔比","gridPos":{"x":0,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"amount","aggregate":"sum"}},
              {"chartId":"c4","datasourceId":"%1$s","type":"bar","title":"利潤","gridPos":{"x":6,"y":4,"w":6,"h":4},
               "mapping":{"x":"region","value":"profit","aggregate":"sum"}}
            ]}""".formatted(datasourceId);
    }
}
