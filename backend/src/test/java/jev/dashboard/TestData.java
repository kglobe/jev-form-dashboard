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
}
