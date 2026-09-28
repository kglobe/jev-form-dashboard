package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import jev.dashboard.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasourceControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(UTF_8));
    }

    @Test
    void uploadInfersSchemaStoresRowsAndIsPrivateToTheUser() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].kind").value("upload"))
            .andExpect(jsonPath("$[0].schema[0].name").value("order_date"))
            .andExpect(jsonPath("$[0].schema[0].type").value("date"))
            .andExpect(jsonPath("$[0].schema[0].min").value("2024-01-05"))
            .andExpect(jsonPath("$[0].schema[1].type").value("number"))
            .andExpect(jsonPath("$[0].schema[2].distinctCount").value(4));
        assertThat(jdbc.sql("select count(*) from datasource_rows").query(Long.class).single()).isEqualTo(12L);
        mvc.perform(get("/api/datasources").header("X-User-Id", "u2"))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void markSensitiveFields() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema[5].sensitive").value(true))
            .andExpect(jsonPath("$.schema[0].sensitive").value(false));
        mvc.perform(patch("/api/datasources/" + id + "/sensitive").header("X-User-Id", "u2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("找不到資料源"));
    }

    @Test
    void setFieldLabels() throws Exception {
        String id = TestData.uploadSales(mvc, "u1");
        mvc.perform(patch("/api/datasources/" + id + "/labels").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":{\"order_date\":\" 訂單日期 \",\"amount\":\"  \"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema[0].label").value("訂單日期"))
            .andExpect(jsonPath("$.schema[1].label").doesNotExist());
        // 顯示名稱不能和另一個欄位重複
        mvc.perform(patch("/api/datasources/" + id + "/labels").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":{\"order_date\":\"amount\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("顯示名稱「amount」重複"));
    }

    @Test
    void badCsvReportsLineNumber() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("bad.csv", "a,b\n1,2\n3\n"))
                .param("name", "壞檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("第 3 行")));
    }

    @Test
    void tooManyRowsIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("a\n");
        for (int i = 0; i < 21; i++) sb.append(i).append('\n');
        mvc.perform(multipart("/api/datasources/upload").file(csv("big.csv", sb.toString()))
                .param("name", "大檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("超過上限 20 筆")));
    }

    @Test
    void unsupportedExtensionIsRejected() throws Exception {
        mvc.perform(multipart("/api/datasources/upload").file(csv("data.txt", "a\n1\n"))
                .param("name", "文字檔").header("X-User-Id", "u1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void registerApiRejectsHostNotInAllowList() throws Exception {
        mvc.perform(post("/api/datasources/api").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"https://evil.example/sales\",\"headers\":{}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("不在允許清單")));
    }

    @Test
    void registerApiRejectsLiteralHeaderValues() throws Exception {
        mvc.perform(post("/api/datasources/api").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"erp\",\"url\":\"http://10.0.0.5/sales\",\"headers\":{\"Authorization\":\"Bearer x\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("${DS_環境變數名}")));
    }

    static final String NORTH_SOUTH = """
        {"op":"union","inputs":["%1$s","%2$s"],"columns":[
          {"name":"region","from":{"%1$s":"region","%2$s":"area"}},
          {"name":"amount","from":{"%1$s":"amount","%2$s":"amount"}},
          {"name":"memo","from":{"%1$s":"memo"}}],"sourceColumn":"來源"}""";

    void mergeIsRejected(String body, int status, String message) throws Exception {
        mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.error").value(message));
    }

    @Test
    void mergedSourcesArePreviewedThenCreatedAndListedWithTheirDefinition() throws Exception {
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount,memo\n北區,100,首購\n北區,200,\n");
        String s = TestData.uploadCsv(mvc, "u1", "南區", "area,amount\n南區,50\n");
        String body = "{\"name\":\"全區\",\"merge\":" + NORTH_SOUTH.formatted(n, s) + "}";

        mvc.perform(post("/api/datasources/merged/preview").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(3))
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[2].region").value("南區"))
            .andExpect(jsonPath("$.rows[2]['來源']").value("南區"))
            .andExpect(jsonPath("$.warnings[0]").value("「memo」只有北區有，其他來源的列這欄是空的"))
            .andExpect(jsonPath("$.schema[3].name").value("來源"));
        assertThat(jdbc.sql("select count(*) from datasource").query(Long.class).single()).isEqualTo(2L);

        mvc.perform(patch("/api/datasources/" + n + "/sensitive").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"memo\"]}"))
            .andExpect(status().isOk());
        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("merged"))
            .andExpect(jsonPath("$.merge.op").value("union"))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(jsonPath("$[0].id").value(id))
            .andExpect(jsonPath("$[0].merge.inputs[1]").value(s))
            .andExpect(jsonPath("$[0].schema[2].sensitive").value(true))
            .andExpect(jsonPath("$[0].schema[2].sensitiveFrom").value("北區"))
            .andExpect(jsonPath("$[0].schema[0].sensitiveFrom").doesNotExist())
            .andExpect(jsonPath("$[1].merge").doesNotExist());
    }

    @Test
    void badMergeRequestsAreRejected() throws Exception {
        String n = TestData.uploadCsv(mvc, "u1", "北區", "region,amount,memo\n北區,100,首購\n");
        String others = TestData.uploadCsv(mvc, "u2", "別人的", "area,amount\n南區,50\n");
        mergeIsRejected("{\"name\":\"x\",\"merge\":{\"op\":\"join\"}}", 400, "請求內容不是正確的 JSON");
        mergeIsRejected("{\"name\":\"x\"}", 400, "請選擇合併方式");
        mergeIsRejected("{\"name\":\" \",\"merge\":" + NORTH_SOUTH.formatted(n, others) + "}", 400, "請輸入名稱");
        mergeIsRejected("{\"name\":\"x\",\"merge\":{\"op\":\"union\",\"inputs\":[\"" + n + "\"],\"columns\":[],\"sourceColumn\":\"來源\"}}",
            400, "疊加要選 2 到 5 個來源");
        mergeIsRejected("{\"name\":\"x\",\"merge\":" + NORTH_SOUTH.formatted(n, others) + "}", 404, "找不到資料源");
    }

    @Test
    void aCompareSourceKeepsCompareInItsDefinition() throws Exception {
        String sales = TestData.uploadCsv(mvc, "u1", "銷售", "order_date,amount\n2026-07-03,1000\n");
        String refunds = TestData.uploadCsv(mvc, "u1", "退貨", "return_date,amount\n2026-08-05,50\n");
        String merge = """
            {"op":"union","compare":true,"inputs":["%1$s","%2$s"],"columns":[
              {"name":"date","from":{"%1$s":"order_date","%2$s":"return_date"}},
              {"name":"銷售·amount","from":{"%1$s":"amount"}},
              {"name":"退貨·amount","from":{"%2$s":"amount"}}],"sourceColumn":"來源"}""".formatted(sales, refunds);

        String created = mvc.perform(post("/api/datasources/merged").header("X-User-Id", "u1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"銷售／退貨 對照\",\"merge\":" + merge + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merge.compare").value(true))
            .andReturn().getResponse().getContentAsString(UTF_8);
        String id = JsonPath.read(created, "$.id");

        String list = mvc.perform(get("/api/datasources").header("X-User-Id", "u1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(UTF_8);
        List<Boolean> compare = JsonPath.read(list, "$[?(@.id == '" + id + "')].merge.compare");
        assertThat(compare).containsExactly(true);
    }
}
