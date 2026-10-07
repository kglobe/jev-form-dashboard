package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
    @Autowired DataSource dataSource;

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

    /** 直接或間接排在 holder 後面等鎖的連線數。 */
    long waitingBehind(int holder) {
        return jdbc.sql("""
                with recursive w(pid) as (
                    select pid from pg_stat_activity where :holder = any(pg_blocking_pids(pid))
                    union
                    select a.pid from pg_stat_activity a join w on w.pid = any(pg_blocking_pids(a.pid)))
                select count(*) from w""")
            .param("holder", holder).query(Long.class).single();
    }

    void awaitWaiting(int holder, int n) throws InterruptedException {
        for (int i = 0; i < 400 && waitingBehind(holder) < n; i++) Thread.sleep(25);
        assertThat(waitingBehind(holder)).as("排在鎖後面的連線").isEqualTo(n);
    }

    /**
     * 更新資料 spec 4.2：先用另一個交易鎖住來源，讓「接在後面」卡在 for update，這時另一個分頁做 edit，
     * 等 edit 也排到鎖後面才放開。edit 要是沒上鎖就先讀了 schema，寫回去時會蓋掉更新資料剛重算的統計。
     */
    void editWhileAnAppendWaits(UUID id, String file, Runnable edit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int pid;
            try (PreparedStatement lock = holder.prepareStatement("select id from datasource where id = ? for update");
                 PreparedStatement me = holder.prepareStatement("select pg_backend_pid()")) {
                lock.setObject(1, id);
                lock.executeQuery();
                ResultSet rs = me.executeQuery();
                rs.next();
                pid = rs.getInt(1);
            }
            Future<?> reload = pool.submit(() -> service.reload("u1", id, csv(file), null, "append"));
            awaitWaiting(pid, 1);
            Future<?> edited = pool.submit(edit);
            awaitWaiting(pid, 2);
            holder.commit();
            reload.get(10, TimeUnit.SECONDS);
            edited.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aLabelOrSensitiveEditDuringAReloadKeepsTheNewStats() throws Exception {
        UUID labelled = service.upload("u1", "銷售", csv("order_date,amount\n2024-01-01,1\n"), null).id();
        UUID marked = service.upload("u1", "退貨", csv("order_date,amount\n2024-01-01,1\n"), null).id();

        editWhileAnAppendWaits(labelled, "order_date,amount\n2024-05-01,2\n",
            () -> service.setLabels("u1", labelled, Map.of("amount", "金額")));
        editWhileAnAppendWaits(marked, "order_date,amount\n2024-05-01,2\n",
            () -> service.markSensitive("u1", marked, List.of("amount")));

        for (UUID id : List.of(labelled, marked)) {
            List<FieldSchema> schema = repo.find(id, "u1").orElseThrow().schema();
            assertThat(repo.rows(id)).hasSize(2);
            assertThat(schema.get(0).max()).as("接在後面之後最晚的日期").isEqualTo("2024-05-01");
            assertThat(schema.get(1).distinctCount()).isEqualTo(2);
        }
        assertThat(repo.find(labelled, "u1").orElseThrow().schema().get(1).label()).isEqualTo("金額");
        assertThat(repo.find(marked, "u1").orElseThrow().schema().get(1).sensitive()).isTrue();
    }

    @Test
    void aFailedRowWriteLeavesNoHalfUploadedSource() {
        Datasource ds = new Datasource(UUID.randomUUID(), "銷售", "upload", List.of(), null, Map.of(), "u1", null);
        // PostgreSQL 的 jsonb 不收 \u0000，第二步寫資料列時才失敗
        assertThatThrownBy(() -> repo.insertWithRows(ds, List.of(Map.of("memo", "a\u0000b")))).isInstanceOf(DataAccessException.class);
        assertThat(repo.find(ds.id(), "u1")).isEmpty();
    }

    /** owner 的上傳來源，created_at 是 2026-10-0{day}，清單的順序才不靠插入時的時鐘。 */
    UUID sourceOf(String owner, String name, int day) {
        UUID id = UUID.randomUUID();
        repo.insert(new Datasource(id, name, "upload", List.of(), null, Map.of(), owner, null));
        jdbc.sql("update datasource set created_at = cast(:at as timestamptz) where id = :id")
            .param("at", "2026-10-0" + day + "T00:00:00Z").param("id", id).update();
        return id;
    }

    @Test
    void usableMeansOwnNamedOrSharedWithEveryoneWhileTheOwnerIsEnabled() {
        // 資料來源共享 spec 3、7.1
        UUID own = sourceOf("u1", "沒分享", 1);
        UUID named = sourceOf("u1", "給 u2", 2);
        UUID everyone = sourceOf("u1", "所有帳號", 3);
        repo.setShare(named, new DatasourceRepository.Share(false, List.of("u2")));
        repo.setShare(everyone, new DatasourceRepository.Share(true, List.of()));
        assertThat(repo.share(named)).isEqualTo(new DatasourceRepository.Share(false, List.of("u2")));
        assertThat(repo.share(everyone)).isEqualTo(new DatasourceRepository.Share(true, List.of()));

        for (UUID id : List.of(own, named, everyone)) assertThat(repo.findUsable(id, "u1")).isPresent();
        assertThat(repo.findUsable(own, "u2")).isEmpty();
        assertThat(repo.findUsable(named, "u2")).isPresent();
        assertThat(repo.findUsable(everyone, "u2")).isPresent();
        assertThat(repo.findUsable(named, "u3")).isEmpty();
        assertThat(repo.findUsable(everyone, "u3")).isPresent();

        // 擁有者停用：別人一律用不到，擁有者自己那條不看帳號
        jdbc.sql("update account set enabled = false where username = 'u1'").update();
        assertThat(repo.findUsable(named, "u2")).isEmpty();
        assertThat(repo.findUsable(everyone, "u3")).isEmpty();
        assertThat(repo.findUsable(own, "u1")).isPresent();
        UUID legacy = sourceOf("沒有帳號的人", "舊資料", 1);
        assertThat(repo.findUsable(legacy, "沒有帳號的人")).isPresent();
        assertThat(repo.findAny(named)).isPresent();
        assertThat(repo.findAny(UUID.randomUUID())).isEmpty();
    }

    @Test
    void listUsablePutsOwnSourcesFirstEachGroupNewestFirst() {
        UUID oldMine = sourceOf("u2", "我的舊的", 1);
        UUID newMine = sourceOf("u2", "我的新的", 4);
        UUID oldShared = sourceOf("u1", "分享的舊的", 2);
        UUID newShared = sourceOf("u3", "分享的新的", 5);
        sourceOf("u1", "沒分享", 6);
        repo.setShare(oldShared, new DatasourceRepository.Share(false, List.of("u2", "u3")));
        repo.setShare(newShared, new DatasourceRepository.Share(true, List.of()));

        assertThat(repo.listUsable("u2")).extracting(Datasource::id).containsExactly(newMine, oldMine, newShared, oldShared);
    }

    @Test
    void setShareReplacesTheWholeShare() {
        UUID id = sourceOf("u1", "銷售", 1);
        repo.setShare(id, new DatasourceRepository.Share(true, List.of("u2", "u3")));
        repo.setShare(id, new DatasourceRepository.Share(false, List.of("u3")));
        assertThat(repo.share(id)).isEqualTo(new DatasourceRepository.Share(false, List.of("u3")));
        assertThat(repo.accountExists("u3")).isTrue();
        assertThat(repo.accountExists("nobody")).isFalse();

        repo.delete(id, "u1");
        assertThat(jdbc.sql("select count(*) from datasource_share").query(Integer.class).single()).isZero();
    }
}
