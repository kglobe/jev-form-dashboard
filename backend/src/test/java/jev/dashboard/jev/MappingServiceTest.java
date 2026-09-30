package jev.dashboard.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.FakeJevServer;
import jev.dashboard.datasource.Datasource;
import jev.dashboard.datasource.FieldSchema;
import jev.dashboard.datasource.FieldType;
import jev.dashboard.jev.MappingService.Match;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MappingServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final UUID ORDERS = UUID.randomUUID();
    static final UUID SALES = UUID.randomUUID();
    static final UUID RETURNS = UUID.randomUUID();

    static FieldSchema f(String name, FieldType type) {
        return new FieldSchema(name, type, 3, List.of("x"), false, null, null);
    }

    static Datasource ds(UUID id, String name, FieldSchema... fields) {
        return new Datasource(id, name, "upload", List.of(fields), null, Map.of(), "u1", null);
    }

    static Answer choice(String choice, double confidence) {
        return new Answer("choice", choice, null, confidence, null);
    }

    @Test
    void asksOnlyFieldsWithoutASameNameMatchAndOffersSameTypeBaseFieldsThatSourceLacks() throws Exception {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER), f("出貨日", FieldType.DATE), f("product", FieldType.STRING));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("product", FieldType.STRING),
            f("金額", FieldType.NUMBER), f("region", FieldType.STRING), f("amount", FieldType.NUMBER));

        Map<String, Question> q = MappingService.questions(List.of(orders, sales), false);

        // product、金額 同名，自動對上不問；amount 唯一同型別的「金額」，銷售自己也有，沒有候選不問
        assertThat(q.keySet()).containsExactly("map_1_0", "map_1_3");
        assertThat(JSON.writeValueAsString(q.get("map_1_0"))).isEqualTo("""
            {"type":"choice","instructions":"來源「銷售」的欄位「order_date」，跟來源「訂單」的哪個欄位是同一種資料？",\
            "criteria":{"f0":"欄位「訂單日期」","f3":"欄位「出貨日」","none":"都不是，這是另一種資料"}}""");
        // 基準的 product 銷售也有，不當 region 的候選
        assertThat(JSON.writeValueAsString(q.get("map_1_3").criteria()))
            .isEqualTo("{\"f1\":\"欄位「地區」\",\"none\":\"都不是，這是另一種資料\"}");
    }

    @Test
    void compareModeNeverAsksAboutNumberFields() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("amount", FieldType.NUMBER));

        assertThat(MappingService.questions(List.of(orders, sales), true).keySet()).containsExactly("map_1_0");
        assertThat(MappingService.questions(List.of(orders, sales), false).keySet()).containsExactly("map_1_0", "map_1_1");
    }

    @Test
    void everyLaterSourceIsComparedToTheFirstOnly() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("owner", FieldType.STRING));
        Datasource returns = ds(RETURNS, "退貨", f("return_date", FieldType.DATE), f("owner", FieldType.STRING));

        Map<String, Question> q = MappingService.questions(List.of(orders, sales, returns), false);

        // 銷售和退貨都有 owner，但基準沒有文字欄位，兩邊不會互相對
        assertThat(q.keySet()).containsExactly("map_1_0", "map_2_0");
        assertThat(q.get("map_2_0").instructions())
            .isEqualTo("來源「退貨」的欄位「return_date」，跟來源「訂單」的哪個欄位是同一種資料？");
    }

    @Test
    void stateListsEachSourceLikeRoundOneAndKeepsSensitiveValuesOut() {
        Datasource orders = ds(ORDERS, "訂單",
            new FieldSchema("訂單日期", FieldType.DATE, 6, List.of("2024-07-05", "備".repeat(70)), false, null, null),
            new FieldSchema("客戶", FieldType.STRING, 6, List.of("王小明"), true, null, null)).withRowCount(6);
        // 合併來源：沒記筆數；備註是從輸入來源繼承來的敏感欄位
        Datasource merged = new Datasource(SALES, "銷售（合併）", "merged",
            List.of(new FieldSchema("備註", FieldType.STRING, 12, List.of("首購"), false, null, null)
                .withInheritedSensitive("銷售")),
            null, Map.of(), "u1", null);

        JsonNode s = JSON.valueToTree(MappingService.state(List.of(orders, merged)));

        assertThat(s.at("/sources/0/name").asText()).isEqualTo("訂單");
        assertThat(s.at("/sources/0/rowCount").asInt()).isEqualTo(6);
        // 樣本值照分析的規則截到 60 個字
        assertThat(s.at("/sources/0/fields/0").toString()).isEqualTo(
            "{\"name\":\"訂單日期\",\"type\":\"date\",\"distinctCount\":6,\"samples\":[\"2024-07-05\",\""
                + "備".repeat(60) + "…\"]}");
        assertThat(s.at("/sources/0/fields/1").toString()).isEqualTo("{\"name\":\"客戶\",\"type\":\"string\"}");
        assertThat(s.at("/sources/1").has("rowCount")).isFalse();
        assertThat(s.at("/sources/1/fields/0").toString()).isEqualTo("{\"name\":\"備註\",\"type\":\"string\"}");
    }

    @Test
    void dropsNoneMissingLowConfidenceAndOffListAnswersButTakesExactlyTheThreshold() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("region", FieldType.STRING),
            f("amount", FieldType.NUMBER), f("ship_date", FieldType.DATE), f("memo", FieldType.STRING));

        List<Match> m = MappingService.matches(List.of(orders, sales), false, Map.of(
            "map_1_0", choice("none", 0.9),
            "map_1_1", choice("f1", 0.49),
            "map_1_2", choice("f2", 0.5),
            "map_1_3", choice("f1", 0.9))); // ship_date 是日期，f1（地區）不在它的選項裡；memo 沒回答

        assertThat(m).containsExactly(new Match(SALES, "amount", "金額"));
    }

    @Test
    void withinOneSourceTheMostConfidentClaimWinsAndTiesGoToTheEarlierField() {
        Datasource orders = ds(ORDERS, "訂單", f("訂單日期", FieldType.DATE), f("地區", FieldType.STRING),
            f("金額", FieldType.NUMBER));
        Datasource sales = ds(SALES, "銷售", f("order_date", FieldType.DATE), f("amount", FieldType.NUMBER),
            f("qty", FieldType.NUMBER), f("region", FieldType.STRING), f("area", FieldType.STRING));
        Datasource returns = ds(RETURNS, "退貨", f("return_date", FieldType.DATE));

        List<Match> m = MappingService.matches(List.of(orders, sales, returns), false, Map.of(
            "map_1_0", choice("f0", 0.9),
            "map_1_1", choice("f2", 0.7),
            "map_1_2", choice("f2", 0.8),   // qty 比 amount 有把握，金額給 qty
            "map_1_3", choice("f1", 0.6),
            "map_1_4", choice("f1", 0.6),   // 跟 region 一樣高，留排前面的 region
            "map_2_0", choice("f0", 0.9))); // 不同來源對到同一個基準欄位，都留

        assertThat(m).containsExactly(
            new Match(SALES, "order_date", "訂單日期"),
            new Match(SALES, "qty", "金額"),
            new Match(SALES, "region", "地區"),
            new Match(RETURNS, "return_date", "訂單日期"));
    }

    @Test
    void nothingToAskSkipsJevAndAJevFailureIsNotSwallowed() {
        try (FakeJevServer server = FakeJevServer.start(body -> null)) {
            MappingService service = new MappingService(
                new JevClient(server.url(), "k", "jev-1.13.0", Duration.ofSeconds(5)));
            Datasource orders = ds(ORDERS, "訂單", f("region", FieldType.STRING), f("訂單日期", FieldType.DATE));

            assertThat(service.suggest(List.of(orders, ds(SALES, "銷售", f("region", FieldType.STRING))), false)).isEmpty();
            assertThat(server.bodies).isEmpty();

            // 假伺服器回 500；欄位對應是使用者按了按鈕在等，失敗要讓他看到
            assertThatThrownBy(() -> service.suggest(List.of(orders, ds(SALES, "銷售", f("order_date", FieldType.DATE))), false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessage("JEV 呼叫失敗（HTTP 500）");
            assertThat(server.bodies).hasSize(1);
        }
    }
}
