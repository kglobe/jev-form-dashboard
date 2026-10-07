package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jev.dashboard.JevProperties;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 寬表轉長表 spec 2.4～2.6：上傳時轉換、存下轉換方式，更新資料時照同樣方式轉。用記憶體裡的 repository。 */
class UnpivotServiceTest {
    final MergedDatasourceServiceTest.MemoryRepo repo = new MergedDatasourceServiceTest.MemoryRepo();
    final DatasourceService service = service(100);
    static final Unpivot.Options Y2026 = new Unpivot.Options("2026", null, null);
    // 研發的 2 月、3 月和業務的 3 月是空白，還沒到的月份不產生資料列
    static final String BUDGET = "部門,1月,2月,3月,全年合計\n業務,100,200,,300\n研發,\"1,000\",,,1000\n";

    DatasourceService service(int maxRows) {
        return new DatasourceService(repo, List.of(new UploadDataSourceProvider(repo)),
            new JevProperties(null, null, null, new JevProperties.Upload(maxRows), null), new ApiUrlGuard(List.of()));
    }

    static MockMultipartFile csv(String text) {
        return ReloadServiceTest.csv(text);
    }

    String summary(UUID id) {
        return String.join("；", repo.rows(id).stream().map(r -> r.get("部門") + " " + r.get("月份") + " " + r.get("數值")).toList());
    }

    @Test
    void uploadConvertsStoresTheDefinitionAndCountsTheLongRows() {
        Datasource ds = service.upload("u1", "預算", csv(BUDGET), null, Y2026);

        assertThat(ds.unpivot()).isEqualTo(new Unpivot(List.of("1月", "2月", "3月"), List.of("全年合計"), "月份", "數值", 2026));
        assertThat(repo.sources.get(ds.id()).unpivot()).isEqualTo(ds.unpivot());
        assertThat(ds.rowCount()).isEqualTo(3);
        assertThat(ds.schema()).extracting(FieldSchema::name, FieldSchema::type).containsExactly(
            tuple("部門", FieldType.STRING), tuple("月份", FieldType.DATE), tuple("數值", FieldType.NUMBER));
        assertThat(ds.schema().get(1).min()).isEqualTo("2026-01-01");
        assertThat(ds.schema().get(1).max()).isEqualTo("2026-02-01");
        assertThat(summary(ds.id())).isEqualTo("業務 2026-01-01 100.0；業務 2026-02-01 200.0；研發 2026-01-01 1000.0");
    }

    @Test
    void withoutTheOptionTheFileIsUploadedAsIs() {
        Datasource ds = service.upload("u1", "預算", csv(BUDGET), null, null);

        assertThat(ds.unpivot()).isNull();
        assertThat(ds.schema()).extracting(FieldSchema::name).containsExactly("部門", "1月", "2月", "3月", "全年合計");
        assertThat(ds.rowCount()).isEqualTo(2);
    }

    @Test
    void excelDateCellsAsHeadersNeedNoYear() throws IOException {
        Workbook wb = new XSSFWorkbook();
        CellStyle month = wb.createCellStyle();
        month.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy\"年\"m\"月\""));
        Row h = wb.createSheet("Data").createRow(0);
        h.createCell(0).setCellValue("部門");
        for (int m = 1; m <= 2; m++) {
            h.createCell(m).setCellValue(LocalDate.of(2025, m, 1));
            h.getCell(m).setCellStyle(month);
        }
        Row r = wb.getSheet("Data").createRow(1);
        r.createCell(0).setCellValue("業務");
        r.createCell(1).setCellValue(5);
        r.createCell(2).setCellValue(6);
        MockMultipartFile file = new MockMultipartFile("file", "b.xlsx", "application/octet-stream", ExcelParserTest.bytes(wb));

        Datasource ds = service.upload("u1", "預算", file, null, new Unpivot.Options(null, null, null));

        assertThat(ds.unpivot().columns()).containsExactly("2025-01-01", "2025-02-01");
        assertThat(ds.unpivot().year()).isNull();
        assertThat(summary(ds.id())).isEqualTo("業務 2025-01-01 5.0；業務 2025-02-01 6.0");
    }

    @Test
    void theRowLimitCountsConvertedRowsAndAllBlankIsNoRows() {
        assertThatThrownBy(() -> service(2).upload("u1", "預算", csv(BUDGET), null, Y2026))
            .hasMessage("資料有 3 筆，超過上限 2 筆");
        assertThatThrownBy(() -> service.upload("u1", "預算", csv("部門,1月\n業務,\n"), null, Y2026))
            .hasMessage("沒有資料列");
        assertThat(repo.sources).isEmpty();
    }

    @Test
    void reloadConvertsTheNewFileTheSameWayWithTheSameYear() {
        UUID id = service.upload("u1", "預算", csv(BUDGET), null, Y2026).id();

        // 欄位順序可以不一樣，轉出來照第一次記下的欄位順序
        Datasource ds = service.reload("u1", id, csv("部門,3月,全年合計,1月,2月\n業務,3,6,1,2\n"), null, "replace");

        assertThat(ds.rowCount()).isEqualTo(3);
        assertThat(ds.unpivot().year()).isEqualTo(2026);
        assertThat(summary(id)).isEqualTo("業務 2026-01-01 1.0；業務 2026-02-01 2.0；業務 2026-03-01 3.0");
        assertThat(ds.schema().get(1).max()).isEqualTo("2026-03-01");

        service.reload("u1", id, csv(BUDGET), null, "append");
        assertThat(repo.sources.get(id).rowCount()).isEqualTo(6);
    }

    @Test
    void reloadListsTheWideColumnsThatDifferAndChecksTypesAfterConverting() {
        UUID id = service.upload("u1", "預算", csv(BUDGET), null, Y2026).id();

        assertThatThrownBy(() -> service.reload("u1", id, csv("部門,1月,2月,4月\n業務,1,2,4\n"), null, "replace"))
            .hasMessage("欄位跟原本的不一樣：少了 3月、全年合計；多了 4月");
        // 長表的欄位名稱不算數，新檔案也要是寬表
        assertThatThrownBy(() -> service.reload("u1", id, csv("部門,月份,數值\n業務,2026-01-01,1\n"), null, "replace"))
            .hasMessage("欄位跟原本的不一樣：少了 1月、2月、3月、全年合計；多了 月份、數值");
        assertThatThrownBy(() -> service.reload("u1", id, csv("部門,1月,2月,3月,全年合計\n業務,abc,,,\n"), null, "replace"))
            .hasMessage("欄位「數值」原本是數字，新檔案裡有不是數字的值");
        assertThat(summary(id)).isEqualTo("業務 2026-01-01 100.0；業務 2026-02-01 200.0；研發 2026-01-01 1000.0");
    }
}
