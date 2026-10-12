package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChartExportTest {
    /** 可以放 null 的一列。 */
    static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    static ChartData data(List<String> dims, List<List<Object>> rows, Boolean percent) {
        return new ChartData("c1", "table", dims, rows, null, null, null, null, null, percent);
    }

    static Sheet xlsx(ChartData d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartExport.xlsx(d, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheet("資料");
    }

    static String csv(ChartData d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartExport.csv(d, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    static boolean bold(Cell cell) {
        return cell.getSheet().getWorkbook().getFontAt(cell.getCellStyle().getFontIndex()).getBold();
    }

    @Test
    void xlsxHasOneSheetABoldHeaderNumbersAsNumbersAndTextAsText() throws IOException {
        Sheet s = xlsx(data(List.of("代碼", "名稱", "金額"),
            List.of(row("007", "北區", 1200.0), row("=1+1", null, -5.0)), null));

        assertThat(s.getWorkbook().getNumberOfSheets()).isEqualTo(1);
        assertThat(s.getLastRowNum()).isEqualTo(2);
        Row header = s.getRow(0);
        assertThat(header.getCell(0).getStringCellValue()).isEqualTo("代碼");
        assertThat(bold(header.getCell(0))).isTrue();

        Row first = s.getRow(1);
        assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(first.getCell(0).getStringCellValue()).isEqualTo("007");
        assertThat(bold(first.getCell(0))).isFalse();
        assertThat(first.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(1200.0);
        assertThat(first.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("General");

        // 公式字元開頭的值照樣是文字格，不是 FORMULA；空值不建格子
        Row second = s.getRow(2);
        assertThat(second.getCell(0).getCellType()).isEqualTo(CellType.STRING);
        assertThat(second.getCell(0).getStringCellValue()).isEqualTo("=1+1");
        assertThat(second.getCell(1)).isNull();
        assertThat(second.getCell(2).getNumericCellValue()).isEqualTo(-5.0);
    }

    @Test
    void xlsxShowsTheRatioColumnAsAPercentButStoresTheFraction() throws IOException {
        Sheet s = xlsx(data(List.of("月份", "退貨率"), List.of(row("2026-09", 0.043), row("2026-10", null)), true));

        Cell rate = s.getRow(1).getCell(1);
        assertThat(rate.getNumericCellValue()).isEqualTo(0.043);
        assertThat(rate.getCellStyle().getDataFormatString()).isEqualTo("0.0%");
        assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2026-09");
        assertThat(s.getRow(2).getCell(1)).isNull();
    }

    @Test
    void xlsxStopsAtExcelsRowLimit() {
        ChartExport.requireXlsxRows(1_048_575);
        assertThatThrownBy(() -> ChartExport.requireXlsxRows(1_048_576))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessage("資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV");
    }

    @Test
    void csvStartsWithABomAndWritesPlainNumbersAndEmptyNulls() throws IOException {
        String text = csv(data(List.of("代碼", "金額"), List.of(
            row("007", 1200.0), row("北區", 0.043), row("南區", 1.0E-7), row("東區", 1.0E20),
            row(null, -5.0), row("西區", null), row("中區", 3L)), true));

        assertThat(text).isEqualTo("﻿代碼,金額\r\n007,1200\r\n北區,0.043\r\n南區,0.0000001\r\n東區,100000000000000000000\r\n"
            + ",-5\r\n西區,\r\n中區,3\r\n");
    }

    @Test
    void textStartingWithAFormulaCharacterGetsAQuote() {
        assertThat(Stream.of("=SUM(A1)", "+1", "-x", "@a", "\tb", "\rc", "a=b", "", "北區").map(ChartExport::csvText))
            .containsExactly("'=SUM(A1)", "'+1", "'-x", "'@a", "'\tb", "'\rc", "a=b", "", "北區");
    }

    @Test
    void csvQuotesTextAndHeadersButNotNegativeNumbers() throws IOException {
        assertThat(csv(data(List.of("=標題", "值"), List.of(row("=SUM(A1)", -5.0)), null)))
            .isEqualTo("﻿'=標題,值\r\n'=SUM(A1),-5\r\n");
    }

    @Test
    void filenameDropsWhatWindowsRejectsTrimsAndCutsAt100() {
        assertThat(ChartExport.filename(" 營收/成本: \"北區\" <2026>?*| ", "xlsx")).isEqualTo("營收成本 北區 2026.xlsx");
        assertThat(ChartExport.filename("a\tb\u0007c", "csv")).isEqualTo("abc.csv");
        assertThat(ChartExport.filename("  /?:  ", "csv")).isEqualTo("圖表.csv");
        assertThat(ChartExport.filename(null, "csv")).isEqualTo("圖表.csv");
        assertThat(ChartExport.filename("長".repeat(120), "xlsx")).isEqualTo("長".repeat(100) + ".xlsx");
        // 照 code point 算，emoji 不會被切一半
        assertThat(ChartExport.filename("😀".repeat(101), "csv")).isEqualTo("😀".repeat(100) + ".csv");
    }
}
