package jev.dashboard.datasource;

import static jev.dashboard.datasource.CsvSources.csv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Union;
import jev.dashboard.datasource.MergeEngine.Output;
import jev.dashboard.datasource.MergeEngine.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MergeEngineTest {
    final Loaded north = csv("北區", "region,amount", "北區,100", "北區,200");
    final Loaded south = csv("南區", "area,amount,reason", "南區,50,瑕疵");

    /** 疊加的 from：依序是「來源、欄位」一對一對。 */
    static Map<UUID, String> from(Object... pairs) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(((Loaded) pairs[i]).datasource().id(), (String) pairs[i + 1]);
        return m;
    }

    static List<UUID> ids(Loaded... sources) {
        return Stream.of(sources).map(l -> l.datasource().id()).toList();
    }

    /** 驗證時取輸入來源：只認得這幾個，其他的回 404。 */
    static Function<UUID, Datasource> only(Loaded... sources) {
        return id -> Stream.of(sources).map(Loaded::datasource).filter(d -> d.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到資料源"));
    }

    static Loaded labeled(Loaded l, String field, String label) {
        return new Loaded(l.datasource().withSchema(l.datasource().schema().stream()
            .map(f -> f.name().equals(field) ? f.withLabel(label) : f).toList()), l.rows());
    }

    Union regionAmountReason() {
        return new Union(ids(north, south), List.of(
            new Union.Column("region", from(north, "region", south, "area")),
            new Union.Column("amount", from(north, "amount", south, "amount")),
            new Union.Column("reason", from(south, "reason"))), "來源");
    }

    void unionIsRejected(Union u, String message) {
        assertThatThrownBy(() -> MergeEngine.validateUnion(u, only(north, south)))
            .isInstanceOf(ApiException.class).hasMessage(message);
    }

    @Test
    void unionRenamesColumnsLeavesUnmappedOnesOutAndTagsEachRowWithItsSource() {
        Result r = MergeEngine.union(regionAmountReason(), List.of(labeled(north, "region", "地區"), south));

        assertThat(r.rows()).containsExactly(
            Map.of("region", "北區", "amount", 100.0, "來源", "北區"),
            Map.of("region", "北區", "amount", 200.0, "來源", "北區"),
            Map.of("region", "南區", "amount", 50.0, "reason", "瑕疵", "來源", "南區"));
        assertThat(r.rows().get(2).keySet()).containsExactly("region", "amount", "reason", "來源");
        assertThat(r.columns()).containsExactly(
            new Output("region", FieldType.STRING, "地區"),
            new Output("amount", FieldType.NUMBER, null),
            new Output("reason", FieldType.STRING, null),
            new Output("來源", FieldType.STRING, null));
        assertThat(r.warnings()).containsExactly("「reason」只有南區有，其他來源的列這欄是空的");
    }

    @Test
    void unionDefinitionsAreCheckedAgainstTheInputs() {
        List<Union.Column> ok = regionAmountReason().columns();
        UUID n = north.datasource().id();
        unionIsRejected(new Union(List.of(n), ok, "來源"), "疊加要選 2 到 5 個來源");
        unionIsRejected(new Union(Collections.nCopies(6, n), ok, "來源"), "疊加要選 2 到 5 個來源");
        unionIsRejected(new Union(List.of(n, n), ok, "來源"), "疊加的來源不能重複");
        unionIsRejected(new Union(ids(north, south), List.of(), "來源"), "至少要有一個輸出欄位");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column(" ", from(north, "region"))), "來源"),
            "欄位名稱不能空白");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("欄".repeat(51), from(north, "region"))), "來源"),
            "欄位名稱最多 50 個字");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", from(north, "region")),
            new Union.Column("region", from(south, "area"))), "來源"), "欄位名稱「region」重複");
        unionIsRejected(new Union(ids(north, south), ok, "region"), "欄位名稱「region」重複");
        unionIsRejected(new Union(ids(north, south), ok, ""), "來源欄名稱不能空白");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", Map.of())), "來源"),
            "欄位「region」至少要對應一個來源");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", Map.of(UUID.randomUUID(), "region"))), "來源"),
            "欄位「region」對應到不在疊加裡的來源");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("region", from(north, "region", south, "region"))), "來源"),
            "南區沒有 region 這個欄位");
        unionIsRejected(new Union(ids(north, south), List.of(new Union.Column("amount", from(north, "amount", south, "reason"))), "來源"),
            "「amount」在北區是數字、在南區是文字，不能併成同一欄");
        assertThatThrownBy(() -> MergeEngine.validateUnion(new Union(List.of(n, UUID.randomUUID()), ok, "來源"), only(north, south)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        MergeEngine.validateUnion(regionAmountReason(), only(north, south));
    }

    @Test
    void schemaUsesTheInputTypesCountsTheMergedRowsAndDropsInheritedLabelsThatWouldClash() {
        Result r = new Result(List.of(
                new Output("region", FieldType.STRING, "地區"),
                new Output("area", FieldType.STRING, "地區"),
                new Output("amount", FieldType.NUMBER, "region"),
                new Output("code", FieldType.STRING, null),
                new Output("day", FieldType.DATE, null)),
            List.of(Map.of("region", "北區", "amount", 1.0, "code", "1", "day", "2026-07-01"),
                Map.of("region", "南區", "code", "2", "day", "2026-08-01")),
            List.of());

        List<FieldSchema> schema = MergeEngine.schema(r);

        assertThat(schema).extracting(FieldSchema::name).containsExactly("region", "area", "amount", "code", "day");
        assertThat(schema).extracting(FieldSchema::label).containsExactly("地區", null, null, null, null);
        // code 的值看起來像數字，型別還是照輸入欄位的文字
        assertThat(schema).extracting(FieldSchema::type)
            .containsExactly(FieldType.STRING, FieldType.STRING, FieldType.NUMBER, FieldType.STRING, FieldType.DATE);
        assertThat(schema.get(0).distinctCount()).isEqualTo(2);
        assertThat(schema.get(4).min()).isEqualTo("2026-07-01");
        assertThat(schema.get(4).max()).isEqualTo("2026-08-01");
        assertThat(schema).noneMatch(FieldSchema::sensitive);
    }
}
