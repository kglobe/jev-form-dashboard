package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jev.dashboard.TestData;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
class DatasourceRepositoryTest {
    @Autowired DatasourceRepository repo;
    @Autowired DatasourceService service;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc);
    }

    @Test
    void aMergedSourceKeepsItsDefinitionAndItsJsonCarriesOp() throws Exception {
        UUID north = UUID.randomUUID();
        UUID south = UUID.randomUUID();
        MergeDefinition def = new Union(List.of(north, south),
            List.of(new Union.Column("region", Map.of(north, "region", south, "area"))), "來源");
        Datasource merged = new Datasource(UUID.randomUUID(), "全區", "merged",
            List.of(new FieldSchema("region", FieldType.STRING, 2, List.of("北區"), false, null, null)),
            null, Map.of(), "u1", null, def);
        Datasource upload = new Datasource(UUID.randomUUID(), "北區", "upload", List.of(), null, Map.of(), "u1", null);
        repo.insert(merged);
        repo.insert(upload);

        Datasource found = repo.find(merged.id(), "u1").orElseThrow();
        assertThat(found.merge()).isEqualTo(def);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().merge()).isNull();
        assertThat(json.writeValueAsString(found)).contains("\"merge\":{\"op\":\"union\"").doesNotContain("sensitiveFrom");
        assertThat(json.writeValueAsString(repo.find(upload.id(), "u1").orElseThrow())).doesNotContain("\"merge\"");
    }

    @Test
    void theDatabaseRejectsAMergedSourceWithoutADefinition() {
        Datasource broken = new Datasource(UUID.randomUUID(), "壞的", "merged", List.of(), null, Map.of(), "u1", null);
        assertThatThrownBy(() -> repo.insert(broken)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ratiosAreStoredInOrderAndRowsWrittenBeforeV4ReadAsAnEmptyList() {
        Datasource upload = new Datasource(UUID.randomUUID(), "銷售", "upload", List.of(), null, Map.of(), "u1", null);
        repo.insert(upload);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().ratios()).isEmpty();

        List<Ratio> ratios = List.of(new Ratio("退貨率", "refund", "sales"), new Ratio("毛利率", "gross_profit", "revenue"));
        repo.updateRatios(upload.id(), ratios);
        assertThat(repo.find(upload.id(), "u1").orElseThrow().ratios()).isEqualTo(ratios);

        // V4 之前寫進去的列沒有 ratios_json，靠欄位預設值
        UUID old = UUID.randomUUID();
        jdbc.sql("insert into datasource (id, name, kind, schema_json, created_by) values (:id, '舊的', 'upload', '[]', 'u1')")
            .param("id", old).update();
        assertThat(repo.find(old, "u1").orElseThrow().ratios()).isEmpty();
    }

    @Test
    void uploadReadsTheFileOutsideAnyTransaction() {
        // 讀檔、解析的時候還沒開交易，大檔案解析期間不會占著資料庫連線
        boolean[] inTransaction = {true};
        MockMultipartFile file = new MockMultipartFile("file", "sales.csv", "text/csv", "region,amount\n北區,100\n".getBytes(UTF_8)) {
            @Override
            public byte[] getBytes() throws IOException {
                inTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
                return super.getBytes();
            }
        };

        Datasource ds = service.upload("u1", "銷售", file, null);

        assertThat(inTransaction[0]).isFalse();
        assertThat(repo.rows(ds.id())).hasSize(1);
    }

    static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "new.csv", "text/csv", text.getBytes(UTF_8));
    }

    List<Integer> rowNumbers(UUID id) {
        return jdbc.sql("select row_no from datasource_rows where datasource_id = :id order by row_no")
            .param("id", id).query(Integer.class).list();
    }

    Integer storedRowCount(UUID id) {
        return jdbc.sql("select row_count from datasource where id = :id").param("id", id).query(Integer.class).single();
    }

    @Test
    void appendContinuesTheRowNumbersAndReplaceStartsOver() {
        UUID id = service.upload("u1", "銷售", csv("region,amount\n北區,100\n"), null).id();

        service.reload("u1", id, csv("region,amount\n南區,200\n東區,300\n"), null, "append");
        assertThat(rowNumbers(id)).containsExactly(0, 1, 2);
        assertThat(repo.rows(id)).extracting(r -> r.get("region")).containsExactly("北區", "南區", "東區");
        assertThat(storedRowCount(id)).isEqualTo(3);

        service.reload("u1", id, csv("region,amount\n西區,400\n"), null, "replace");
        assertThat(rowNumbers(id)).containsExactly(0);
        assertThat(repo.rows(id)).extracting(r -> r.get("region")).containsExactly("西區");
        assertThat(storedRowCount(id)).isEqualTo(1);
        assertThat(repo.find(id, "u1").orElseThrow().schema().getFirst().samples()).containsExactly("西區");
    }

    @Test
    void aFailedReloadKeepsTheOldRowsStatsAndCounts() {
        UUID id = service.upload("u1", "銷售", csv("memo\n首購\n"), null).id();
        UUID other = service.upload("u1", "備註", csv("memo\n備註\n"), null).id();
        UUID merged = service.createMerged("u1", "全部", MergedDatasourceServiceTest.union(List.of(id, other), "memo")).id();

        // 舊的資料列刪掉之後，寫新的資料列才失敗（jsonb 不收 \u0000）：整個交易退回
        assertThatThrownBy(() -> service.reload("u1", id, csv("memo\na\u0000b\n"), null, "replace"))
            .isInstanceOf(DataAccessException.class);

        assertThat(repo.rows(id)).extracting(r -> r.get("memo")).containsExactly("首購");
        assertThat(storedRowCount(id)).isEqualTo(1);
        assertThat(repo.find(id, "u1").orElseThrow().schema().getFirst().samples()).containsExactly("首購");
        assertThat(storedRowCount(merged)).isEqualTo(2);
    }

    @Test
    void twoAppendsAtTheSameTimeBothLandWithoutCollidingRowNumbers() throws Exception {
        UUID id = service.upload("u1", "銷售", csv("region,amount\n北區,100\n"), null).id();
        // 兩邊都讀完檔案才一起往下走，寫入的時間重疊；沒鎖的話兩邊都從 row_no 1 開始寫，後寫的會撞主鍵
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Datasource> append = () -> service.reload("u1", id, new MockMultipartFile("file", "new.csv", "text/csv",
            "region,amount\n南區,1\n東區,2\n西區,3\n".getBytes(UTF_8)) {
            @Override
            public byte[] getBytes() throws IOException {
                try {
                    together.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IOException(e);
                }
                return super.getBytes();
            }
        }, null, "append");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<Datasource> f : pool.invokeAll(List.of(append, append))) f.get();
        } finally {
            pool.shutdown();
        }

        assertThat(rowNumbers(id)).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(storedRowCount(id)).isEqualTo(7);
        assertThat(repo.find(id, "u1").orElseThrow().schema().getFirst().distinctCount()).isEqualTo(4);
    }

    @Test
    void reloadReadsTheFileOutsideAnyTransaction() {
        UUID id = service.upload("u1", "銷售", csv("region,amount\n北區,100\n"), null).id();
        boolean[] inTransaction = {true};
        MockMultipartFile file = new MockMultipartFile("file", "new.csv", "text/csv", "region,amount\n南區,200\n".getBytes(UTF_8)) {
            @Override
            public byte[] getBytes() throws IOException {
                inTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
                return super.getBytes();
            }
        };

        service.reload("u1", id, file, null, "append");

        assertThat(inTransaction[0]).isFalse();
        assertThat(repo.rows(id)).hasSize(2);
    }

    @Test
    void aFailedRowWriteLeavesNoHalfUploadedSource() {
        Datasource ds = new Datasource(UUID.randomUUID(), "銷售", "upload", List.of(), null, Map.of(), "u1", null);
        // PostgreSQL 的 jsonb 不收 \u0000，第二步寫資料列時才失敗
        assertThatThrownBy(() -> repo.insertWithRows(ds, List.of(Map.of("memo", "a\u0000b")))).isInstanceOf(DataAccessException.class);
        assertThat(repo.find(ds.id(), "u1")).isEmpty();
    }
}
