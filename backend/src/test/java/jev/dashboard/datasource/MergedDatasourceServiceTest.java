package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import jev.dashboard.datasource.DatasourceService.Loaded;
import jev.dashboard.datasource.DatasourceService.MergePreview;
import jev.dashboard.datasource.MergeDefinition.Lookup;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.junit.jupiter.api.Test;

class MergedDatasourceServiceTest {
    /** 記憶體裡的 repository：只做 DatasourceService 會用到的方法，不連資料庫。 */
    static class MemoryRepo extends DatasourceRepository {
        final Map<UUID, Datasource> sources = new LinkedHashMap<>();
        final Map<UUID, List<Map<String, Object>>> rows = new HashMap<>();

        MemoryRepo() {
            super(null, null, null);
        }

        @Override
        public void insert(Datasource d) {
            sources.put(d.id(), d);
        }

        @Override
        public Optional<Datasource> find(UUID id, String userId) {
            return Optional.ofNullable(sources.get(id)).filter(d -> d.createdBy().equals(userId));
        }

        @Override
        public List<Datasource> list(String userId) {
            return sources.values().stream().filter(d -> d.createdBy().equals(userId)).toList();
        }

        @Override
        public void updateSchema(UUID id, List<FieldSchema> schema) {
            sources.put(id, sources.get(id).withSchema(schema));
        }

        @Override
        public void insertRows(UUID id, List<Map<String, Object>> value) {
            rows.put(id, value);
        }

        @Override
        public List<Map<String, Object>> rows(UUID id) {
            return rows.getOrDefault(id, List.of());
        }
    }

    final MemoryRepo repo = new MemoryRepo();

    DatasourceService service(int maxRows) {
        return new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
            new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null), new ApiUrlGuard(List.of()));
    }

    UUID upload(String name, String... lines) {
        return uploadFor("u1", name, lines);
    }

    UUID uploadFor(String owner, String name, String... lines) {
        Loaded l = CsvSources.csv(name, lines);
        Datasource ds = new Datasource(l.datasource().id(), name, "upload", l.datasource().schema(), null, Map.of(), owner, null);
        repo.insert(ds);
        repo.insertRows(ds.id(), l.rows());
        return ds.id();
    }

    /** 同名欄位直接對起來的疊加，來源欄叫「來源」。 */
    static Union union(List<UUID> inputs, String... fields) {
        return new Union(inputs, Stream.of(fields).map(f -> new Union.Column(f,
            inputs.stream().collect(Collectors.toMap(id -> id, id -> f, (a, b) -> a, LinkedHashMap::new)))).toList(), "來源");
    }

    /** 表頭加上 count 列資料，第 i 列（從 1 開始）是 row(i)。 */
    static String[] lines(String header, int count, IntFunction<String> row) {
        return Stream.concat(Stream.of(header), IntStream.rangeClosed(1, count).mapToObj(row)).toArray(String[]::new);
    }

    static FieldSchema field(Datasource ds, String name) {
        return ds.schema().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void createStoresOnlyTheDefinitionAndEveryLoadMergesAgain() {
        DatasourceService service = service(20);
        UUID n = upload("北區", "region,amount", "北區,100", "北區,200");
        UUID s = upload("南區", "region,amount", "南區,50");

        Datasource m = service.createMerged("u1", " 全區 ", union(List.of(n, s), "region", "amount"));

        assertThat(m.kind()).isEqualTo("merged");
        assertThat(m.name()).isEqualTo("全區");
        assertThat(m.merge()).isEqualTo(union(List.of(n, s), "region", "amount"));
        assertThat(m.schema()).extracting(FieldSchema::name).containsExactly("region", "amount", "來源");
        assertThat(repo.rows).doesNotContainKey(m.id());
        assertThat(service.load("u1", m.id()).rows()).extracting(r -> r.get("amount")).containsExactly(100.0, 200.0, 50.0);

        repo.insertRows(s, List.of(Map.of("region", "南區", "amount", 60.0), Map.of("region", "南區", "amount", 70.0)));
        assertThat(service.load("u1", m.id()).rows()).extracting(r -> r.get("amount"))
            .containsExactly(100.0, 200.0, 60.0, 70.0);
    }

    @Test
    void previewReturnsTheFirst20RowsTheCountAndTheWarningsWithoutSaving() {
        DatasourceService service = service(100);
        UUID n = upload("北區", lines("amount", 15, i -> String.valueOf(i)));
        UUID s = upload("南區", lines("amount,reason", 15, i -> i + ",瑕疵"));
        Union u = new Union(List.of(n, s), List.of(
            new Union.Column("amount", Map.of(n, "amount", s, "amount")),
            new Union.Column("reason", Map.of(s, "reason"))), "來源");

        MergePreview p = service.previewMerged("u1", u);

        assertThat(p.rowCount()).isEqualTo(30);
        assertThat(p.rows()).hasSize(20);
        assertThat(p.warnings()).containsExactly("「reason」只有南區有，其他來源的列這欄是空的");
        assertThat(p.schema()).extracting(FieldSchema::name).containsExactly("amount", "reason", "來源");
        assertThat(repo.sources).hasSize(2);
    }

    @Test
    void theNameTheDefinitionTheRowCountAndTheOwnerAreChecked() {
        DatasourceService service = service(3);
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID c = upload("c", "v", "3");
        UUID d = upload("d", "v", "4");
        UUID empty1 = upload("空1", "v");
        UUID empty2 = upload("空2", "v");
        UUID others = uploadFor("u2", "別人的", "v", "9");

        assertThatThrownBy(() -> service.createMerged("u1", " ", union(List.of(a, b), "v"))).hasMessage("請輸入名稱");
        assertThatThrownBy(() -> service.createMerged("u1", "x", null)).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> service.previewMerged("u1", null)).hasMessage("請選擇合併方式");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(a, b, c, d), "v")))
            .hasMessage("資料有 4 筆，超過上限 3 筆");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(empty1, empty2), "v"))).hasMessage("沒有資料列");
        assertThatThrownBy(() -> service.createMerged("u1", "偷看", union(List.of(a, others), "v")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(NOT_FOUND))
            .hasMessage("找不到資料源");
        assertThat(repo.sources).hasSize(7);
    }

    @Test
    void mergesNestUpToThreeLevels() {
        DatasourceService service = service(100);
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID c = upload("c", "v", "3");
        UUID d = upload("d", "v", "4");
        UUID e = upload("e", "v", "5");
        UUID m1 = service.createMerged("u1", "m1", union(List.of(a, b), "v")).id();
        UUID m2 = service.createMerged("u1", "m2", union(List.of(m1, c), "v")).id();
        UUID m3 = service.createMerged("u1", "m3", union(List.of(m2, d), "v")).id();

        List<Map<String, Object>> rows = service.load("u1", m3).rows();
        assertThat(rows).extracting(r -> r.get("v")).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(rows).extracting(r -> r.get("來源")).containsExactly("m2", "m2", "m2", "d");
        assertThatThrownBy(() -> service.previewMerged("u1", union(List.of(m3, e), "v"))).hasMessage("合併最多 3 層");
    }

    @Test
    void loadingOnlyStopsAtTheUpperLimit() {
        UUID a = upload("a", "v", "1");
        UUID b = upload("b", "v", "2");
        UUID m = service(100).createMerged("u1", "m", union(List.of(a, b), "v")).id();

        assertThatThrownBy(() -> service(1).load("u1", m)).hasMessage("資料有 2 筆，超過上限 1 筆");
        repo.insertRows(a, List.of());
        repo.insertRows(b, List.of());
        assertThat(service(1).load("u1", m).rows()).isEmpty();
    }

    @Test
    void sensitivityFollowsTheInputsAndCannotBeTurnedOffOnTheMergedSource() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,memo", "北區,首購");
        UUID s = upload("南區", "region,memo", "南區,備註");
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "memo")).id();
        assertThat(field(service.get("u1", m), "memo").sensitive()).isFalse();

        service.markSensitive("u1", s, List.of("memo"));
        FieldSchema memo = field(service.get("u1", m), "memo");
        assertThat(memo.sensitive()).isTrue();
        assertThat(memo.sensitiveFrom()).isEqualTo("南區");
        Datasource listed = service.list("u1").stream().filter(d -> d.id().equals(m)).findFirst().orElseThrow();
        assertThat(field(listed, "memo").sensitiveFrom()).isEqualTo("南區");
        assertThat(field(service.load("u1", m).datasource(), "memo").sensitive()).isTrue();

        // 畫面會把繼承來的也一起送回來；在合併來源上關不掉，存下來的只有自己勾的
        Datasource toggled = service.markSensitive("u1", m, List.of("region", "memo"));
        assertThat(field(toggled, "memo").sensitiveFrom()).isEqualTo("南區");
        assertThat(field(repo.sources.get(m), "memo").sensitive()).isFalse();
        assertThat(field(repo.sources.get(m), "region").sensitive()).isTrue();
        assertThat(repo.sources.get(m).schema()).allMatch(f -> f.sensitiveFrom() == null);

        service.markSensitive("u1", s, List.of());
        assertThat(field(service.get("u1", m), "memo").sensitive()).isFalse();
    }

    @Test
    void nestedAndLookupSourcesPointAtTheSourceWhoseSwitchIsOn() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "product,memo", "A,首購");
        UUID s = upload("南區", "product,memo", "B,備註");
        UUID p = upload("產品主檔", "code,owner", "A,王小明", "B,李小華");
        service.markSensitive("u1", n, List.of("memo"));
        service.markSensitive("u1", p, List.of("owner"));
        UUID all = service.createMerged("u1", "全區", union(List.of(n, s), "product", "memo")).id();
        Lookup enrich = new Lookup(all, p, List.of(new Lookup.Key("product", "code")), List.of(new Lookup.Bring("負責人", "owner")));
        UUID enriched = service.createMerged("u1", "全區（補產品主檔）", enrich).id();

        Datasource e = service.get("u1", enriched);
        assertThat(field(e, "memo").sensitiveFrom()).isEqualTo("北區");
        assertThat(field(e, "負責人").sensitiveFrom()).isEqualTo("產品主檔");
        assertThat(field(e, "product").sensitive()).isFalse();
        assertThat(field(e, "來源").sensitive()).isFalse();
        assertThat(service.previewMerged("u1", enrich).schema()).filteredOn(FieldSchema::sensitive)
            .extracting(FieldSchema::name).containsExactly("memo", "負責人");
    }

    @Test
    void displayNamesComeFromTheInputsAndCanBeChangedOnTheMergedSource() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,amount", "北區,100");
        UUID s = upload("南區", "region,amount", "南區,50");
        service.setLabels("u1", n, Map.of("region", "地區"));
        service.markSensitive("u1", n, List.of("amount"));
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "amount")).id();
        assertThat(field(service.get("u1", m), "region").label()).isEqualTo("地區");

        Datasource renamed = service.setLabels("u1", m, Map.of("region", "區域", "來源", "資料來源"));

        assertThat(field(renamed, "region").label()).isEqualTo("區域");
        assertThat(field(renamed, "來源").label()).isEqualTo("資料來源");
        assertThat(field(renamed, "amount").sensitiveFrom()).isEqualTo("北區");
        // 改顯示名稱時不能把即時算出來的敏感設定一起存進去
        assertThat(field(repo.sources.get(m), "amount").sensitive()).isFalse();
        assertThat(repo.sources.get(m).schema()).allMatch(f -> f.sensitiveFrom() == null);
        assertThat(field(service.get("u1", n), "region").label()).isEqualTo("地區");
    }
}
