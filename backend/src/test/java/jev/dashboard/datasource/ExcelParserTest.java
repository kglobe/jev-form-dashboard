package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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

    static byte[] bytes(Workbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 工作表 Data，每個陣列是一列：String 寫成文字、Number 寫成數字、Boolean 寫成布林、null 不建儲存格。 */
    static byte[] sheet(Workbook wb, Object[]... rows) throws IOException {
        Sheet s = wb.createSheet("Data");
        for (int r = 0; r < rows.length; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                Object v = rows[r][c];
                if (v instanceof String str) row.createCell(c).setCellValue(str);
                else if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                else if (v instanceof Boolean b) row.createCell(c).setCellValue(b);
            }
        }
        return bytes(wb);
    }

    /** 和 workbook() 同樣內容，換成別種 Workbook（.xls、串流寫出的 .xlsx、1904 日期系統）。 */
    static byte[] sales(Workbook wb) throws IOException {
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
        r.createCell(2).setCellValue(" 北區 ");
        return bytes(wb);
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

    @Test
    void legacyXlsInlineStringXlsxAnd1904DatesGiveTheSameValues() throws Exception {
        XSSFWorkbook date1904 = new XSSFWorkbook();
        date1904.getCTWorkbook().getWorkbookPr().setDate1904(true);
        for (byte[] bytes : List.of(sales(new HSSFWorkbook()), sales(new SXSSFWorkbook()), sales(date1904))) {
            ParsedTable t = parse(bytes);
            assertThat(t.headers()).containsExactly("order_date", "amount", "region");
            assertThat(t.rows()).containsExactly(Map.of("order_date", "2024-01-05", "amount", 1200.0, "region", "北區"));
        }
    }

    @Test
    void formulasBooleansErrorsAndMissingCellsFollowTheCellRules() throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Data");
        Row h = s.createRow(0);
        List<String> names = List.of("sum", "label", "flag", "ratio", "note", "text");
        for (int c = 0; c < names.size(); c++) h.createCell(c).setCellValue(names.get(c));
        Row r = s.createRow(1);
        r.createCell(0).setCellFormula("1+2");
        r.createCell(1).setCellFormula("\"a\"&\"b\"");
        r.createCell(2).setCellValue(true);
        r.createCell(3).setCellFormula("1/0");
        r.createCell(5).setCellValue("  x  ");
        r.createCell(6).setCellValue("標題以外的欄不讀");
        wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        ParsedTable t = parse(bytes(wb));
        assertThat(t.rows().get(0)).containsExactly(entry("sum", 3.0), entry("label", "ab"), entry("flag", "true"),
            entry("ratio", ""), entry("note", ""), entry("text", "x"));
    }

    @Test
    void sheetNameMatchIgnoresCase() throws Exception {
        byte[] bytes = sheet(new XSSFWorkbook(), new Object[] {"a"}, new Object[] {1});
        assertThat(ExcelParser.parse(new ByteArrayInputStream(bytes), "data").rows()).hasSize(1);
    }

    @Test
    void blankRowsAreSkippedAndDoNotCountTowardsTheCap() throws Exception {
        byte[] bytes = sheet(new XSSFWorkbook(), new Object[] {"a"}, new Object[] {1}, new Object[] {""},
            new Object[] {""}, new Object[] {""}, new Object[] {2});
        assertThat(ExcelParser.parse(new ByteArrayInputStream(bytes), null, 2).rows())
            .extracting(row -> row.get("a")).containsExactly(1.0, 2.0);
    }

    @Test
    void blankOrDuplicateHeaderIsRejected() throws Exception {
        assertThatThrownBy(() -> parse(sheet(new XSSFWorkbook(), new Object[] {"a", "", "c"}, new Object[] {1, 2, 3})))
            .isInstanceOf(UploadParseException.class).hasMessage("欄位名稱不能空白");
        assertThatThrownBy(() -> parse(sheet(new XSSFWorkbook(), new Object[] {"a", "a"}, new Object[] {1, 2})))
            .isInstanceOf(UploadParseException.class).hasMessage("欄位名稱重複：a");
        assertThatThrownBy(() -> parse(sheet(new HSSFWorkbook(), new Object[] {"a", "a"}, new Object[] {1, 2})))
            .isInstanceOf(UploadParseException.class).hasMessage("欄位名稱重複：a");
    }

    @Test
    void unreadableFilesGetAChineseMessage() throws Exception {
        byte[] xlsx = workbook();
        for (byte[] bad : List.of("not a workbook".getBytes(UTF_8), Arrays.copyOf(xlsx, xlsx.length / 2), new byte[0])) {
            assertThatThrownBy(() -> parse(bad)).isInstanceOf(UploadParseException.class).hasMessage("Excel 檔案無法讀取");
        }
    }

    @Test
    void passwordProtectedWorkbookSaysSo() throws Exception {
        POIFSFileSystem fs = new POIFSFileSystem();
        Encryptor enc = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
        enc.confirmPassword("secret");
        try (OutputStream os = enc.getDataStream(fs)) {
            os.write(workbook());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fs.writeFilesystem(out);
        assertThatThrownBy(() -> parse(out.toByteArray()))
            .isInstanceOf(UploadParseException.class).hasMessage("Excel 檔案有密碼保護，請先移除密碼");
    }

    @Test
    void programmingErrorsAreNotReportedAsParseErrors() {
        assertThatThrownBy(() -> ExcelParser.parse(null, null)).isInstanceOf(NullPointerException.class);
    }
}
