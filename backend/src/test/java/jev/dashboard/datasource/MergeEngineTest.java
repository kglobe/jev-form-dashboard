package jev.dashboard.datasource;

import static jev.dashboard.datasource.CsvSources.csv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Lookup.Bring;
import jev.dashboard.datasource.MergeDefinition.Lookup.Key;
import jev.dashboard.datasource.MergeDefinition.Union;
import jev.dashboard.datasource.MergeEngine.Output;
import jev.dashboard.datasource.MergeEngine.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MergeEngineTest {
    final Loaded north = csv("北區", "region,amount", "北區,100", "北區,200");
    final Loaded south = csv("南區", "area,amount,reason", "南區,50,瑕疵");
    final Loaded returns = csv("退貨", "product,qty,owner", "A,1,甲", "B,2,乙", "Z,3,丙", ",4,丁");
    final Loaded products = csv("產品主檔", "code,category,owner", "A,家電,王小明", "B,文具,李小華", "A,重複,張三");

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

    final Loaded sales = csv("銷售", "order_date,region,amount", "2026-07-03,北區,1000", "2026-08-02,南區,1200");
    final Loaded refunds = csv("退貨", "return_date,region,amount,qty", "2026-08-05,北區,50,1");

    /** 對照：銷售、退貨，來源欄叫「來源」。 */
    Union compare(Union.Column... columns) {
        return new Union(ids(sales, refunds), List.of(columns), "來源", true);
    }

    Union.Column date() {
        return new Union.Column("date", from(sales, "order_date", refunds, "return_date"));
    }

    static Datasource mergedSource(Union u, Loaded... inputs) {
        List<FieldSchema> schema = MergeEngine.schema(MergeEngine.union(u, List.of(inputs)));
        return new Datasource(UUID.randomUUID(), "對照", "merged", schema, null, Map.of(), "u1", null, u);
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

    Lookup productLookup() {
        return new Lookup(returns.datasource().id(), products.datasource().id(), List.of(new Key("product", "code")),
            List.of(new Bring("category", "category"), new Bring("產品主檔·owner", "owner")));
    }

    void lookupIsRejected(Lookup l, String message) {
        assertThatThrownBy(() -> MergeEngine.validate(l, only(returns, products)))
            .isInstanceOf(ApiException.class).hasMessage(message);
    }

    @Test
    void lookupKeepsEveryMainRowBringsColumnsFromTheFirstMatchAndCountsProblems() {
        Result r = MergeEngine.lookup(productLookup(), returns, labeled(products, "category", "類別"));

        assertThat(r.rows()).hasSize(4);
        assertThat(r.rows().get(0))
            .isEqualTo(Map.of("product", "A", "qty", 1.0, "owner", "甲", "category", "家電", "產品主檔·owner", "王小明"));
        assertThat(r.rows().get(1)).containsEntry("category", "文具");
        assertThat(r.rows().get(2)).containsEntry("product", "Z").doesNotContainKeys("category", "產品主檔·owner");
        assertThat(r.rows().get(3)).containsEntry("qty", 4.0).doesNotContainKey("category");
        assertThat(r.columns()).extracting(Output::name)
            .containsExactly("product", "qty", "owner", "category", "產品主檔·owner");
        assertThat(r.columns().get(3)).isEqualTo(new Output("category", FieldType.STRING, "類別"));
        assertThat(r.warnings()).containsExactly("主檔有 1 個對接值重複，用排最前面的那一列", "2 筆對不到主檔，補上的欄位是空的");
    }

    @Test
    void keysIgnoreTrailingZerosNeedEveryPartAndDoNotRunTogether() {
        List<Key> one = List.of(new Key("a", "b"));
        assertThat(MergeEngine.key(Map.of("a", 1.0), one, Key::main)).isEqualTo(MergeEngine.key(Map.of("b", 1), one, Key::lookup));
        List<Key> two = List.of(new Key("a", "a"), new Key("c", "c"));
        Map<String, Object> half = new HashMap<>();
        half.put("a", "x");
        half.put("c", null);
        assertThat(MergeEngine.key(half, two, Key::main)).isNull();
        assertThat(MergeEngine.key(Map.of("a", "x", "c", "y"), two, Key::main))
            .isNotEqualTo(MergeEngine.key(Map.of("a", "xy", "c", ""), two, Key::main));
    }

    @Test
    void lookupDefinitionsAreCheckedAgainstTheInputs() {
        UUID r = returns.datasource().id();
        UUID p = products.datasource().id();
        List<Key> keys = productLookup().keys();
        List<Bring> bring = productLookup().bring();
        lookupIsRejected(new Lookup(null, p, keys, bring), "查表要選主來源和主檔");
        lookupIsRejected(new Lookup(r, r, keys, bring), "主來源和主檔不能是同一個");
        lookupIsRejected(new Lookup(r, p, List.of(), bring), "對接欄位要 1 到 3 組");
        lookupIsRejected(new Lookup(r, p, Collections.nCopies(4, keys.get(0)), bring), "對接欄位要 1 到 3 組");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("sku", "code")), bring), "退貨沒有 sku 這個欄位");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("product", "sku")), bring), "產品主檔沒有 sku 這個欄位");
        lookupIsRejected(new Lookup(r, p, List.of(new Key("qty", "code")), bring), "對接欄位「qty」是數字、「code」是文字，型別要相同");
        lookupIsRejected(new Lookup(r, p, keys, List.of()), "至少要補上一個欄位");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("owner", "owner"))), "欄位名稱「owner」跟主來源的欄位重複");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("category", "category"), new Bring("category", "owner"))),
            "欄位名稱「category」重複");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring(" ", "category"))), "欄位名稱不能空白");
        lookupIsRejected(new Lookup(r, p, keys, List.of(new Bring("price", "price"))), "產品主檔沒有 price 這個欄位");

        MergeEngine.validate(productLookup(), only(returns, products));
    }

    @Test
    void validateNeedsADefinitionAndDispatchesOnOp() {
        assertThatThrownBy(() -> MergeEngine.validate(null, only())).isInstanceOf(ApiException.class).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> MergeEngine.validate(new Union(List.of(), List.of(), "來源"), only()))
            .hasMessage("疊加要選 2 到 5 個來源");
    }

    @Test
    void sourcesNameTheInputFieldsBehindEachOutputColumn() {
        assertThat(MergeEngine.sources(regionAmountReason(), "region")).isEqualTo(from(north, "region", south, "area"));
        assertThat(MergeEngine.sources(regionAmountReason(), "來源")).isEmpty();
        assertThat(MergeEngine.sources(productLookup(), "產品主檔·owner")).isEqualTo(Map.of(products.datasource().id(), "owner"));
        assertThat(MergeEngine.sources(productLookup(), "qty")).isEqualTo(Map.of(returns.datasource().id(), "qty"));
    }

    @Test
    void compareKeepsEveryNumberColumnToOneSourceAndNeedsTwoSourcesWithNumbers() {
        Function<UUID, Datasource> input = only(sales, refunds);
        Union merged = compare(date(), new Union.Column("amount", from(sales, "amount", refunds, "amount")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(merged, input))
            .isInstanceOf(ApiException.class).hasMessage("對照時，數字欄位「amount」只能對應一個來源");

        Union oneSide = compare(date(), new Union.Column("銷售·amount", from(sales, "amount")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(oneSide, input))
            .isInstanceOf(ApiException.class).hasMessage("對照至少要有兩個來源各提供一個數字欄位");
        Union sameSource = compare(date(),
            new Union.Column("退貨·amount", from(refunds, "amount")), new Union.Column("退貨·qty", from(refunds, "qty")));
        assertThatThrownBy(() -> MergeEngine.validateUnion(sameSource, input))
            .isInstanceOf(ApiException.class).hasMessage("對照至少要有兩個來源各提供一個數字欄位");

        Union ok = compare(date(), new Union.Column("region", from(sales, "region", refunds, "region")),
            new Union.Column("銷售·amount", from(sales, "amount")), new Union.Column("退貨·amount", from(refunds, "amount")));
        assertThatCode(() -> MergeEngine.validateUnion(ok, input)).doesNotThrowAnyException();
        // 一般疊加照舊可以把兩邊的數字併成一欄
        Union plain = new Union(ids(sales, refunds), List.of(date(),
            new Union.Column("amount", from(sales, "amount", refunds, "amount"))), "來源");
        assertThatCode(() -> MergeEngine.validateUnion(plain, input)).doesNotThrowAnyException();
    }

    @Test
    void compareOnlyWarnsAboutTextAndDateColumnsThatSomeSourcesLack() {
        Union u = compare(date(), new Union.Column("region", from(sales, "region")),
            new Union.Column("銷售·amount", from(sales, "amount")), new Union.Column("退貨·amount", from(refunds, "amount")));
        Result r = MergeEngine.union(u, List.of(sales, refunds));

        assertThat(r.warnings()).containsExactly("「region」只有銷售有，其他來源的列這欄是空的");
        assertThat(r.rows().get(2).keySet()).containsExactly("date", "退貨·amount", "來源");
        assertThat(r.rows().get(2).get("退貨·amount")).isEqualTo(50.0);
    }

    @Test
    void comparisonPairsNumberColumnsFromDifferentSourcesOnly() {
        Union u = compare(date(), new Union.Column("銷售·amount", from(sales, "amount")),
            new Union.Column("退貨·amount", from(refunds, "amount")), new Union.Column("退貨·qty", from(refunds, "qty")));

        MergeEngine.Comparison c = MergeEngine.comparison(mergedSource(u, sales, refunds));

        assertThat(c.sourceColumn()).isEqualTo("來源");
        // 退貨·amount 和 退貨·qty 來自同一個來源，不配對
        assertThat(c.pairs()).containsExactlyInAnyOrder(Set.of("銷售·amount", "退貨·amount"), Set.of("銷售·amount", "退貨·qty"));
        // 一般疊加、上傳來源都不是對照
        Union plain = new Union(u.inputs(), u.columns(), "來源");
        assertThat(MergeEngine.comparison(mergedSource(plain, sales, refunds))).isNull();
        assertThat(MergeEngine.comparison(sales.datasource())).isNull();
    }
}
