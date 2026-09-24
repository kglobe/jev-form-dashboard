package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelParserTest {
    static byte[] workbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("銷售");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("order_date");
            h.createCell(1).setCellValue("amount");
            h.createCell(2).setCellValue("region");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Row r = s.createRow(1);
            Cell d = r.createCell(0);
            d.setCellValue(LocalDate.of(2024, 1, 5));
            d.setCellStyle(dateStyle);
            r.createCell(1).setCellValue(1200);
            r.createCell(2).setCellValue("北區");
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsFirstSheetWithDatesAndNumbers() throws Exception {
        ParsedTable t = ExcelParser.parse(new ByteArrayInputStream(workbook()), null);
        assertThat(t.headers()).containsExactly("order_date", "amount", "region");
        assertThat(t.rows()).hasSize(1);
        assertThat(t.rows().get(0))
            .containsEntry("order_date", "2024-01-05")
            .containsEntry("amount", 1200.0)
            .containsEntry("region", "北區");
    }

    @Test
    void readsNamedSheet() throws Exception {
        ParsedTable t = ExcelParser.parse(new ByteArrayInputStream(workbook()), "銷售");
        assertThat(t.rows()).hasSize(1);
    }

    @Test
    void missingSheetIsAnError() {
        assertThatThrownBy(() -> ExcelParser.parse(new ByteArrayInputStream(workbook()), "不存在"))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("找不到工作表");
    }

    @Test
    void stopsParsingAsSoonAsRowCountExceedsCap() throws Exception {
        assertThatThrownBy(() -> ExcelParser.parse(new ByteArrayInputStream(workbook()), null, 0))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("超過上限 0 筆");
    }

    /** 每個陣列是一列：String 寫成文字儲存格、Number 寫成數字、Boolean 寫成布林、null 不建儲存格。 */
    static byte[] sheet(Workbook wb, Object[]... rows) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("資料");
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof String str) row.createCell(c).setCellValue(str);
                    else if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else if (v instanceof Boolean b) row.createCell(c).setCellValue(b);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static ParsedTable parse(byte[] bytes) {
        return ExcelParser.parse(new ByteArrayInputStream(bytes), null);
    }

    @Test
    void zeroPaddedTextCellsKeepTheirText() throws Exception {
        ParsedTable t = parse(sheet(new XSSFWorkbook(), new Object[] {"phone", "qty"}, new Object[] {"0912345678", 7}));
        List<FieldSchema> schema = SchemaInference.infer(t.headers(), t.rows());
        assertThat(schema).extracting(FieldSchema::type).containsExactly(FieldType.STRING, FieldType.NUMBER);
        assertThat(SchemaInference.normalize(t.rows(), schema).get(0)).containsEntry("phone", "0912345678");
    }
}
