package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
        final Map<UUID, Integer> findCalls = new ConcurrentHashMap<>();

        MemoryRepo() {
            super(null, null, null);
        }

        @Override
        public void insert(Datasource d) {
            sources.put(d.id(), d);
        }

        @Override
        public Optional<Datasource> find(UUID id, String userId) {
            findCalls.merge(id, 1, Integer::sum);
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
    void aMissingInputMakesItsFieldsSensitive() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,memo", "北區,首購");
        UUID s = upload("南區", "region,memo", "南區,備註");
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "memo")).id();
        assertThat(field(service.get("u1", m), "region").sensitive()).isFalse();

        repo.sources.remove(s);
        FieldSchema region = field(service.get("u1", m), "region");
        assertThat(region.sensitive()).isTrue();
        assertThat(region.sensitiveFrom()).isEqualTo(DatasourceService.DELETED_SOURCE);
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
    void ownFlagOnAFieldThatIsAlsoInheritedSurvivesUnrelatedToggles() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,memo", "北區,首購");
        UUID s = upload("南區", "region,memo", "南區,備註");
        UUID m = service.createMerged("u1", "全區", union(List.of(n, s), "region", "memo")).id();

        // (a) 使用者自己在合併來源把 memo 勾成敏感
        service.markSensitive("u1", m, List.of("memo"));
        // (b) 之後輸入來源 s 也把 memo 標成敏感，畫面上 memo 現在是繼承來的
        service.markSensitive("u1", s, List.of("memo"));
        // (c) 使用者在 m 上切換其他開關；畫面會把目前看到的敏感欄位（含繼承來的 memo）一起送回來
        service.markSensitive("u1", m, List.of("region", "memo"));
        // (d) s 把 memo 取消敏感，m 應該還記得自己當初勾過 memo，不會因為 (c) 被清空
        service.markSensitive("u1", s, List.of());

        assertThat(field(service.get("u1", m), "memo").sensitive()).isTrue();
        assertThat(field(repo.sources.get(m), "memo").sensitive()).isTrue();
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
    void getLooksUpEachNestedSourceAtMostOncePerCall() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,amount,memo", "北區,100,a");
        UUID s = upload("南區", "region,amount,memo", "南區,50,b");
        UUID m1 = service.createMerged("u1", "小計", union(List.of(n, s), "region", "amount", "memo")).id();
        UUID p = upload("產品主檔", "region,owner", "北區,王小明");
        Lookup enrich = new Lookup(m1, p, List.of(new Lookup.Key("region", "region")),
            List.of(new Lookup.Bring("負責人", "owner")));
        UUID m2 = service.createMerged("u1", "全區", enrich).id();
        repo.findCalls.clear();

        service.get("u1", m2);

        assertThat(repo.findCalls.values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(1));
    }

    @Test
    void anIntermediateMergedSourcesOwnSensitiveFlagShowsUpAtTheTopEvenWhenNoInputIsSensitive() {
        DatasourceService service = service(100);
        UUID n = upload("北區", "region,amount", "北區,100");
        UUID s = upload("南區", "region,amount", "南區,50");
        UUID m1 = service.createMerged("u1", "小計", union(List.of(n, s), "region", "amount")).id();
        // 中間層自己勾了敏感，兩個輸入來源的 amount 都沒勾
        service.markSensitive("u1", m1, List.of("amount"));
        UUID c = upload("東區", "region,amount", "東區,30");
        UUID m2 = service.createMerged("u1", "全區", union(List.of(m1, c), "region", "amount")).id();

        FieldSchema amount = field(service.get("u1", m2), "amount");
        assertThat(amount.sensitive()).isTrue();
        assertThat(amount.sensitiveFrom()).isEqualTo("小計");
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

    @Test
    void oneLoaderFetchesEachApiSourceOnceEvenWhenMergedSourcesUseItAgainAndFailuresAreNotRetried() {
        // 外部 API 來源 erp：每次抓都記在 fetches，down[0] 是 true 時抓取丟 502
        Map<UUID, Integer> fetches = new ConcurrentHashMap<>();
        boolean[] down = {false};
        DataSourceProvider api = new DataSourceProvider() {
            @Override
            public boolean supports(String kind) {
                return "api".equals(kind);
            }

            @Override
            public List<Map<String, Object>> fetchRows(Datasource ds) {
                fetches.merge(ds.id(), 1, Integer::sum);
                if (down[0]) throw new ApiException(BAD_GATEWAY, "外部 API 無法連線");
                return List.of(Map.of("region", "北區", "amount", "100"));
            }
        };
        Loaded l = CsvSources.csv("erp", "region,amount", "北區,100");
        UUID erp = l.datasource().id();
        repo.insert(new Datasource(erp, "erp", "api", l.datasource().schema(), "http://10.0.0.5/x", Map.of(), "u1", null));
        DatasourceService service = new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo), api),
            new JevProperties(null, null, null, new JevProperties.Upload(20), null), new ApiUrlGuard(List.of()));
        UUID s = upload("南區", "region,amount", "南區,50");
        UUID m = service.createMerged("u1", "全區", union(List.of(erp, s), "region", "amount")).id();
        UUID top = service.createMerged("u1", "再疊一次", union(List.of(m, erp), "region", "amount")).id();

        // 巢狀：erp 在 top 的結構裡出現兩次
        fetches.clear();
        assertThat(service.load("u1", top).rows()).extracting(r -> r.get("amount")).containsExactly(100.0, 50.0, 100.0);
        assertThat(fetches).isEqualTo(Map.of(erp, 1));

        // 一張卡直接用 erp、其他卡用到含 erp 的合併來源：同一個 loader 只抓一次
        fetches.clear();
        Function<UUID, Loaded> load = service.loader("u1");
        load.apply(erp);
        load.apply(m);
        load.apply(top);
        assertThat(fetches).isEqualTo(Map.of(erp, 1));

        // 抓失敗也只試一次，之後用到它的來源都拿到同一個錯誤
        down[0] = true;
        fetches.clear();
        Function<UUID, Loaded> failing = service.loader("u1");
        for (UUID id : List.of(erp, m, top)) {
            assertThatThrownBy(() -> failing.apply(id)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(BAD_GATEWAY));
        }
        assertThat(fetches).isEqualTo(Map.of(erp, 1));
    }

    @Test
    void theInputsOfAMergedSourceAreFetchedAtTheSameTime() {
        // 兩個外部 API 都開始抓了才放行：一個抓完才抓下一個的話，第一個會等到逾時
        CyclicBarrier bothFetching = new CyclicBarrier(2);
        DataSourceProvider api = new DataSourceProvider() {
            @Override
            public boolean supports(String kind) {
                return "api".equals(kind);
            }

            @Override
            public List<Map<String, Object>> fetchRows(Datasource ds) {
                try {
                    bothFetching.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new ApiException(BAD_GATEWAY, "沒有同時抓：" + e);
                }
                return List.of(Map.of("region", ds.name(), "amount", "100"));
            }
        };
        List<FieldSchema> schema = CsvSources.csv("erp", "region,amount", "北區,100").datasource().schema();
        UUID north = UUID.randomUUID();
        UUID south = UUID.randomUUID();
        repo.insert(new Datasource(north, "北區", "api", schema, "http://10.0.0.5/n", Map.of(), "u1", null));
        repo.insert(new Datasource(south, "南區", "api", schema, "http://10.0.0.5/s", Map.of(), "u1", null));
        DatasourceService service = new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo), api),
            new JevProperties(null, null, null, new JevProperties.Upload(20), null), new ApiUrlGuard(List.of()));

        UUID m = service.createMerged("u1", "全區", union(List.of(north, south), "region", "amount")).id();

        // 同時抓，合併後的順序還是照輸入來源的順序
        assertThat(service.load("u1", m).rows()).extracting(r -> r.get("region")).containsExactly("北區", "南區");
    }
}
