package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jev.dashboard.ApiException;
import jev.dashboard.transform.ChartData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 匯出 spec 2.2：一張卡的 dimensions 和 rows 寫成 xlsx 或 CSV，第一列是表頭，資料照原本的順序。 */
final class ChartExport {
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String CSV_TYPE = "text/csv; charset=UTF-8";
    /** Excel 一個工作表最多 1,048,576 列，扣掉表頭。 */
    static final int XLSX_MAX_ROWS = 1_048_575;
    static final int MAX_NAME = 100;

    private ChartExport() {}

    static void requireXlsxRows(int rows) {
        if (rows > XLSX_MAX_ROWS) throw new ApiException(BAD_REQUEST, "資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV");
    }

    /**
     * 一個工作表「資料」，表頭粗體。數字存成數字格，其他值存成文字格（Excel 不會當成公式執行，補零的代碼也不會掉 0），空值不建格子。
     * 比率卡（percent）的最後一欄用 0.0% 顯示，存的還是小數。SXSSF 只把最近 100 列留在記憶體，其他寫進暫存檔，close() 時刪掉。
     * ponytail: 一格超過 32,767 字時 POI 丟 IllegalArgumentException（400、英文訊息）；真的遇到再截斷或換訊息，CSV 沒有這個限制
     */
    static void xlsx(ChartData d, OutputStream out) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook()) {
            Sheet sheet = wb.createSheet("資料");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            CellStyle percent = wb.createCellStyle();
            percent.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            int ratioColumn = Boolean.TRUE.equals(d.percent()) ? d.dimensions().size() - 1 : -1;

            Row top = sheet.createRow(0);
            for (int i = 0; i < d.dimensions().size(); i++) {
                Cell cell = top.createCell(i);
                cell.setCellValue(d.dimensions().get(i));
                cell.setCellStyle(header);
            }
            int r = 1;
            for (List<Object> values : d.rows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.size(); i++) {
                    Object v = values.get(i);
                    if (v == null) continue;
                    Cell cell = row.createCell(i);
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        if (i == ratioColumn) cell.setCellStyle(percent);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
        }
    }

    /** UTF-8 加 BOM（Excel 直接打開才不會亂碼），逗號分隔、CRLF 換行；數字不用科學記號，文字擋掉公式字元，空值寫空字串。 */
    static void csv(ChartData d, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write('\uFEFF');
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.EXCEL)) {
            printer.printRecord(d.dimensions().stream().map(ChartExport::csvText).toList());
            for (List<Object> row : d.rows()) printer.printRecord(row.stream().map(ChartExport::csvValue).toList());
        }
    }

    // null 原樣交給 CSVPrinter：寫成空字串，在第一欄也不加引號
    private static String csvValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return csvText(String.valueOf(v));
    }

    /** 匯出 spec 2.2：以 = + - @ Tab CR 開頭的文字前面加 '，Excel 才不會當成公式執行（CSV injection）。 */
    static String csvText(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    /** 匯出 spec 2.2：拿掉 Windows 檔名不能用的字元和控制字元，去掉頭尾空白，最多 100 字（照 code point 算），什麼都不剩就用「圖表」。 */
    static String filename(String title, String format) {
        String name = (title == null ? "" : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").strip();
        if (name.codePointCount(0, name.length()) > MAX_NAME) name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME)).strip();
        return (name.isEmpty() ? "圖表" : name) + "." + format;
    }
}
