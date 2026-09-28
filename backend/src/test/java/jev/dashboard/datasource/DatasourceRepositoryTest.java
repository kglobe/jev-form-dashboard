package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.TestData;
import jev.dashboard.datasource.MergeDefinition.Union;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DatasourceRepositoryTest {
    @Autowired DatasourceRepository repo;
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
}
