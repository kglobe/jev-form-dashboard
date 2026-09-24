package jev.dashboard.datasource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class ExcelParser {
    private ExcelParser() {}

    public static ParsedTable parse(InputStream in, String sheetName) {
        return parse(in, sheetName, Integer.MAX_VALUE);
    }

    public static ParsedTable parse(InputStream in, String sheetName, int maxRows) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = sheetName == null || sheetName.isBlank() ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) throw new UploadParseException("找不到工作表：" + sheetName);
            Row head = sheet.getRow(sheet.getFirstRowNum());
            if (head == null) throw new UploadParseException("第 1 行沒有標題列");
            // ponytail: 用列數上限先檔掉超大檔，不必先把每一列都讀進記憶體才發現太大
            if (sheet.getLastRowNum() - head.getRowNum() > maxRows) {
                throw new UploadParseException("資料超過上限 " + maxRows + " 筆");
            }
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < head.getLastCellNum(); c++) {
                String name = String.valueOf(value(head.getCell(c))).trim();
                if (name.isEmpty()) throw new UploadParseException("第 1 行第 " + (c + 1) + " 欄的標題是空的");
                headers.add(name);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = head.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                boolean hasValue = false;
                for (int c = 0; c < headers.size(); c++) {
                    Object v = value(row.getCell(c));
                    values.put(headers.get(c), v);
                    if (!"".equals(v)) hasValue = true;
                }
                if (hasValue) rows.add(values);
            }
            return new ParsedTable(headers, rows);
        } catch (UploadParseException e) {
            throw e;
        } catch (Exception e) {
            throw new UploadParseException("Excel 解析失敗：" + e.getMessage());
        }
    }

    static Object value(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : (Object) cell.getNumericCellValue();
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
