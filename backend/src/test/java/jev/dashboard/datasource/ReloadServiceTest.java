package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 更新資料 spec 第 2 節：整份覆蓋、接在後面和各種擋下來的情況。用記憶體裡的 repository，不連資料庫。 */
class ReloadServiceTest {
    final MergedDatasourceServiceTest.MemoryRepo repo = new MergedDatasourceServiceTest.MemoryRepo() {
        @Override
        public void updateRatios(UUID id, List<Ratio> ratios) {
            sources.put(id, sources.get(id).withRatios(ratios));
        }
    };

    DatasourceService service(int maxRows) {
        return new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
            new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null), new ApiUrlGuard(List.of()));
    }

    final DatasourceService service = service(100);

    /** 幾行 CSV 變成這個使用者的上傳來源，筆數跟真的上傳一樣記下來，回傳 id。 */
    UUID uploadFor(String owner, String name, String... lines) {
        DatasourceService.Loaded l = CsvSources.csv(name, lines);
        Datasource ds = new Datasource(l.datasource().id(), name, "upload", l.datasource().schema(), null, Map.of(), owner, null);
        repo.insert(ds.withRowCount(l.rows().size()));
        repo.insertRows(ds.id(), l.rows());
        return ds.id();
    }

    UUID upload(String name, String... lines) {
        return uploadFor("u1", name, lines);
    }

    static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "new.csv", "text/csv", text.getBytes(UTF_8));
    }

    Datasource reload(DatasourceService s, UUID id, String mode, String text) {
        return s.reload("u1", id, csv(text), null, mode);
    }

    Datasource reload(UUID id, String mode, String text) {
        return reload(service, id, mode, text);
    }

    static FieldSchema field(Datasource ds, String name) {
        return MergedDatasourceServiceTest.field(ds, name);
    }

    @Test
    void replaceMakesTheRowsExactlyTheNewFilesRowsInTheSchemasColumnOrder() {
        UUID id = upload("銷售", "region,amount", "北區,100", "南區,200");

        // 欄位順序可以不一樣；新的列照原本的 schema 整理
        Datasource ds = reload(id, "replace", "amount,region\n300,東區\n");

        assertThat(ds.rowCount()).isEqualTo(1);
        assertThat(repo.rows(id)).containsExactly(Map.of("region", "東區", "amount", 300.0));
        assertThat(repo.rows(id).getFirst().keySet()).containsExactly("region", "amount");
        assertThat(service.load("u1", id).rows()).extracting(r -> r.get("amount")).containsExactly(300.0);
    }

    @Test
    void appendAddsTheNewRowsAfterTheOldOnes() {
        UUID id = upload("銷售", "region,amount", "北區,100", "南區,200");

        Datasource ds = reload(id, "append", "region,amount\n東區,300\n西區,400\n");

        assertThat(ds.rowCount()).isEqualTo(4);
        assertThat(repo.sources.get(id).rowCount()).isEqualTo(4);
        assertThat(service.load("u1", id).rows()).extracting(r -> r.get("region")).containsExactly("北區", "南區", "東區", "西區");
    }

    @Test
    void theModeMustBeReplaceOrAppend() {
        UUID id = upload("銷售", "region,amount", "北區,100");

        for (String mode : new String[] {null, "", "merge", "REPLACE"}) {
            assertThatThrownBy(() -> reload(id, mode, "region,amount\n南區,200\n"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(BAD_REQUEST))
                .hasMessage("更新方式只能是 replace（整份覆蓋）或 append（接在後面）");
        }
        assertThat(repo.rows(id)).extracting(r -> r.get("region")).containsExactly("北區");
    }

    @Test
    void onlyTheCallersOwnUploadSourcesCanBeUpdated() {
        UUID mine = upload("北區", "region,amount", "北區,100");
        UUID south = upload("南區", "region,amount", "南區,50");
        UUID other = uploadFor("u2", "別人的", "region,amount", "南區,50");
        UUID api = UUID.randomUUID();
        repo.insert(new Datasource(api, "erp", "api", repo.sources.get(mine).schema(), "http://10.0.0.5/x", Map.of(), "u1", null));
        UUID merged = service.createMerged("u1", "全區", MergedDatasourceServiceTest.union(List.of(mine, south), "region")).id();
        String file = "region,amount\n東區,1\n";

        for (UUID id : List.of(other, UUID.randomUUID())) {
            assertThatThrownBy(() -> reload(id, "replace", file))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(NOT_FOUND))
                .hasMessage("找不到資料源");
        }
        for (UUID id : List.of(api, merged)) {
            assertThatThrownBy(() -> reload(id, "append", file))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(BAD_REQUEST))
                .hasMessage("只有上傳的檔案可以更新資料");
        }
        assertThat(repo.rows(other)).extracting(r -> r.get("region")).containsExactly("南區");
    }

    @Test
    void theNewFileMustHaveExactlyTheSameColumns() {
        UUID id = upload("銷售", "region,amount,qty", "北區,100,1");

        assertThatThrownBy(() -> reload(id, "replace", "amount,note\n1,x\n"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(BAD_REQUEST))
            .hasMessage("欄位跟原本的不一樣：少了 region、qty；多了 note");
        assertThatThrownBy(() -> reload(id, "append", "region,amount\n南區,1\n"))
            .hasMessage("欄位跟原本的不一樣：少了 qty");
        assertThatThrownBy(() -> reload(id, "append", "region,amount,qty,note,memo\n南區,1,2,x,y\n"))
            .hasMessage("欄位跟原本的不一樣：多了 note、memo");
        assertThat(repo.rows(id)).hasSize(1);
    }

    @Test
    void numberAndDateColumnsKeepTheirTypeAndTextColumnsTakeAnything() {
        UUID id = upload("銷售", "order_date,amount,code", "2024-01-05,1200,A1");

        assertThatThrownBy(() -> reload(id, "append", "order_date,amount,code\n2024-01-06,1200,B\n2024-01-07,約 300,C\n"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(BAD_REQUEST))
            .hasMessage("欄位「amount」原本是數字，新檔案裡有不是數字的值");
        assertThatThrownBy(() -> reload(id, "replace", "order_date,amount,code\n下週一,5,B\n"))
            .hasMessage("欄位「order_date」原本是日期，新檔案裡有不是日期的值");
        assertThat(repo.rows(id)).hasSize(1);

        // 文字欄位放數字也可以，照文字存，不會變成 5.0
        reload(id, "append", "order_date,amount,code\n113/02/01,\"1,500\",5\n");
        Datasource ds = repo.sources.get(id);
        assertThat(ds.schema()).extracting(FieldSchema::type).containsExactly(FieldType.DATE, FieldType.NUMBER, FieldType.STRING);
        assertThat(repo.rows(id).getLast()).isEqualTo(Map.of("order_date", "2024-02-01", "amount", 1500.0, "code", "5"));
    }

    @Test
    void aColumnThatIsAllBlankInTheNewFileIsFine() {
        UUID id = upload("銷售", "order_date,amount", "2024-01-05,1200");

        Datasource ds = reload(id, "append", "order_date,amount\n,\n2024-02-01,\n");

        assertThat(ds.rowCount()).isEqualTo(3);
        assertThat(field(ds, "amount").type()).isEqualTo(FieldType.NUMBER);
        assertThat(repo.rows(id)).extracting(r -> r.get("amount")).containsExactly(1200.0, null, null);
    }

    @Test
    void theRowLimitCountsTheOldRowsTooWhenAppending() {
        DatasourceService small = service(3);
        UUID id = upload("銷售", "region,amount", "北區,100", "南區,200");

        assertThatThrownBy(() -> reload(small, id, "append", "region,amount\n東區,1\n西區,2\n"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(BAD_REQUEST))
            .hasMessage("資料有 4 筆，超過上限 3 筆");
        assertThatThrownBy(() -> reload(small, id, "replace", "region,amount\n"))
            .hasMessage("沒有資料列");
        assertThat(repo.rows(id)).hasSize(2);

        // 整份覆蓋只算新檔案的筆數
        assertThat(reload(small, id, "replace", "region,amount\n東區,1\n西區,2\n中區,3\n").rowCount()).isEqualTo(3);
    }

    @Test
    void displayNamesSensitiveFlagsAndRatiosStayExactlyAsTheyWere() {
        UUID id = upload("銷售", "region,sales,refund", "北區,100,5");
        service.setLabels("u1", id, Map.of("sales", "銷售金額"));
        service.markSensitive("u1", id, List.of("refund"));
        service.setRatios("u1", id, List.of(new Ratio("退貨率", "refund", "sales")));

        Datasource replaced = reload(id, "replace", "refund,region,sales\n7,南區,300\n");
        Datasource appended = reload(id, "append", "region,sales,refund\n東區,50,1\n");

        for (Datasource ds : List.of(replaced, appended)) {
            assertThat(ds.schema()).extracting(FieldSchema::name).containsExactly("region", "sales", "refund");
            assertThat(ds.schema()).extracting(FieldSchema::label).containsExactly(null, "銷售金額", null);
            assertThat(ds.schema()).extracting(FieldSchema::sensitive).containsExactly(false, false, true);
            assertThat(ds.ratios()).containsExactly(new Ratio("退貨率", "refund", "sales").withSensitive(true));
        }
    }

    @Test
    void statsAreRecomputedFromAllTheResultingRowsAndLookLikeAFirstUpload() {
        UUID id = upload("銷售", "order_date,amount,code", "2024-01-05,1200,007");
        assertThat(field(repo.sources.get(id), "amount").samples()).containsExactly("1200");

        Datasource appended = reload(id, "append", "order_date,amount,code\n2024/03/01,1500.50,0012\n2024-01-05,1200,007\n");

        FieldSchema date = field(appended, "order_date");
        assertThat(date.min()).isEqualTo("2024-01-05");
        assertThat(date.max()).isEqualTo("2024-03-01");
        assertThat(date.distinctCount()).isEqualTo(2);
        // 數字的樣本不會變成 1200.0；0 開頭的代碼照原樣
        assertThat(field(appended, "amount").samples()).containsExactly("1200", "1500.5");
        assertThat(field(appended, "amount").min()).isNull();
        assertThat(field(appended, "code").samples()).containsExactly("007", "0012");
        assertThat(field(appended, "code").distinctCount()).isEqualTo(2);

        // 整份覆蓋只看新檔案
        Datasource replaced = reload(id, "replace", "order_date,amount,code\n2024-02-01,5,1\n");
        assertThat(field(replaced, "order_date").min()).isEqualTo("2024-02-01");
        assertThat(field(replaced, "order_date").max()).isEqualTo("2024-02-01");
        assertThat(field(replaced, "amount").samples()).containsExactly("5");
        assertThat(field(replaced, "code").samples()).containsExactly("1");
        assertThat(field(replaced, "code").distinctCount()).isEqualTo(1);
    }

    @Test
    void everyMergedSourceThatUsesItDirectlyOrIndirectlyForgetsItsRowCountAndLoadsTheNewRows() {
        UUID a = upload("a", "region,amount", "北區,1");
        UUID b = upload("b", "region,amount", "南區,2");
        UUID c = upload("c", "region,amount", "東區,3");
        UUID m1 = service.createMerged("u1", "m1", MergedDatasourceServiceTest.union(List.of(a, b), "region", "amount")).id();
        UUID m2 = service.createMerged("u1", "m2", MergedDatasourceServiceTest.union(List.of(m1, c), "region", "amount")).id();
        UUID other = service.createMerged("u1", "沒用到 a", MergedDatasourceServiceTest.union(List.of(b, c), "region", "amount")).id();
        assertThat(repo.sources.get(m2).rowCount()).isEqualTo(3);

        reload(a, "append", "region,amount\n西區,4\n");

        assertThat(repo.sources.get(m1).rowCount()).isNull();
        assertThat(repo.sources.get(m2).rowCount()).isNull();
        assertThat(repo.sources.get(other).rowCount()).isEqualTo(2);
        assertThat(service.load("u1", m2).rows()).extracting(r -> r.get("region")).containsExactly("北區", "西區", "南區", "東區");
    }
}
