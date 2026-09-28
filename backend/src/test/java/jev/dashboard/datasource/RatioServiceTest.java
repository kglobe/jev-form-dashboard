package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jev.dashboard.JevProperties;
import org.junit.jupiter.api.Test;

/** B3 spec 4.2、4.3、第 8 節：比率清單的驗證、整份取代和敏感。用記憶體裡的 repository，不連資料庫。 */
class RatioServiceTest {
    final MergedDatasourceServiceTest.MemoryRepo repo = new MergedDatasourceServiceTest.MemoryRepo() {
        @Override
        public void updateRatios(UUID id, List<Ratio> ratios) {
            sources.put(id, sources.get(id).withRatios(ratios));
        }
    };
    final DatasourceService service = new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
        new JevProperties(null, null, null, new JevProperties.Upload(100), null), new ApiUrlGuard(List.of()));

    /** 幾行 CSV 變成 u1 的上傳來源，回傳 id。 */
    UUID upload(String name, String... lines) {
        DatasourceService.Loaded l = CsvSources.csv(name, lines);
        repo.insert(l.datasource());
        repo.insertRows(l.datasource().id(), l.rows());
        return l.datasource().id();
    }

    Datasource set(UUID id, Ratio... ratios) {
        return service.setRatios("u1", id, List.of(ratios));
    }

    @Test
    void everyRuleIsCheckedAndTheNameIsStripped() {
        UUID id = upload("銷售", "region,sales,refund", "北區,100,5");
        service.setLabels("u1", id, Map.of("sales", "銷售金額"));

        assertThat(set(id, new Ratio(" 退貨率 ", "refund", "sales")).ratios())
            .containsExactly(new Ratio("退貨率", "refund", "sales"));

        assertThatThrownBy(() -> set(id, new Ratio("  ", "refund", "sales"))).hasMessage("比率名稱不能空白");
        assertThatThrownBy(() -> set(id, new Ratio("率".repeat(51), "refund", "sales"))).hasMessage("比率名稱最多 50 個字");
        assertThatThrownBy(() -> set(id, new Ratio("region", "refund", "sales")))
            .hasMessage("比率名稱「region」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("銷售金額", "refund", "sales")))
            .hasMessage("比率名稱「銷售金額」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("退貨率", "refund", "sales"), new Ratio(" 退貨率", "sales", "refund")))
            .hasMessage("比率名稱「退貨率」跟欄位或其他比率重複");
        assertThatThrownBy(() -> set(id, new Ratio("率", "region", "sales"))).hasMessage("分子「region」不是這個來源的數字欄位");
        assertThatThrownBy(() -> set(id, new Ratio("率", "refund", "profit"))).hasMessage("分母「profit」不是這個來源的數字欄位");
        assertThatThrownBy(() -> set(id, new Ratio("率", "sales", "sales"))).hasMessage("分子和分母不能是同一個欄位");
        // 驗證失敗不會動到已經存的清單
        assertThat(service.get("u1", id).ratios()).containsExactly(new Ratio("退貨率", "refund", "sales"));
    }

    @Test
    void theWholeListIsReplacedInTheOrderGiven() {
        UUID id = upload("銷售", "sales,refund,qty", "100,5,2");

        set(id, new Ratio("退貨率", "refund", "sales"), new Ratio("件單價", "sales", "qty"));
        assertThat(service.get("u1", id).ratios()).extracting(Ratio::name).containsExactly("退貨率", "件單價");

        set(id, new Ratio("件單價", "sales", "qty"));
        assertThat(service.get("u1", id).ratios()).extracting(Ratio::name).containsExactly("件單價");
        assertThat(set(id).ratios()).isEmpty();
    }

    @Test
    void aRatioIsSensitiveWhenItsNumeratorOrDenominatorIsIncludingInheritedSensitivity() {
        UUID n = upload("北區", "sales,refund", "100,5");
        UUID s = upload("南區", "sales,refund", "80,2");
        set(n, new Ratio("退貨率", "refund", "sales"));
        assertThat(service.get("u1", n).ratios().get(0).sensitive()).isFalse();

        service.markSensitive("u1", n, List.of("sales"));
        assertThat(service.get("u1", n).ratios().get(0).sensitive()).isTrue();
        assertThat(service.list("u1")).filteredOn(d -> d.id().equals(n)).singleElement()
            .satisfies(d -> assertThat(d.ratios().get(0).sensitive()).isTrue());

        // 合併來源不會帶著輸入來源的比率；自己加的比率，分母從北區繼承了敏感
        UUID m = service.createMerged("u1", "全區", MergedDatasourceServiceTest.union(List.of(n, s), "sales", "refund")).id();
        assertThat(service.get("u1", m).ratios()).isEmpty();
        set(m, new Ratio("退貨率", "refund", "sales"));
        assertThat(service.get("u1", m).ratios().get(0).sensitive()).isTrue();

        // 輸入來源取消敏感，比率跟著不敏感
        service.markSensitive("u1", n, List.of());
        assertThat(service.get("u1", m).ratios().get(0).sensitive()).isFalse();
    }

    @Test
    void aDisplayNameCannotTakeARatiosName() {
        UUID id = upload("銷售", "sales,refund", "100,5");
        set(id, new Ratio("退貨率", "refund", "sales"));

        assertThatThrownBy(() -> service.setLabels("u1", id, Map.of("sales", "退貨率"))).hasMessage("顯示名稱「退貨率」重複");
    }
}
