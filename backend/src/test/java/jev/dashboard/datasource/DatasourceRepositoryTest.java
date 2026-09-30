package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Test
    void aFailedRowWriteLeavesNoHalfUploadedSource() {
        Datasource ds = new Datasource(UUID.randomUUID(), "銷售", "upload", List.of(), null, Map.of(), "u1", null);
        // PostgreSQL 的 jsonb 不收 \u0000，第二步寫資料列時才失敗
        assertThatThrownBy(() -> repo.insertWithRows(ds, List.of(Map.of("memo", "a\u0000b")))).isInstanceOf(DataAccessException.class);
        assertThat(repo.find(ds.id(), "u1")).isEmpty();
    }
}
