package jev.dashboard.datasource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.util.RecordFormatException;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbookPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorkbookDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * .xlsx 用 SAX 串流讀：整本活頁簿載進 POI usermodel 會用掉好幾 GB（10 萬列 × 50 欄在 4 GB heap 也 OOM）。
 * .xls 最多 65536 列，照舊用 usermodel。兩條路都經過 Rows，標題檢查、空白列、筆數上限規則一樣。
 */
public final class ExcelParser {
    private static final Logger log = LoggerFactory.getLogger(ExcelParser.class);

    private ExcelParser() {}

    public static ParsedTable parse(InputStream in, String sheetName) {
        return parse(in, sheetName, Integer.MAX_VALUE);
    }

    public static ParsedTable parse(InputStream in, String sheetName, int maxRows) {
        Rows out = new Rows(maxRows);
        try {
            InputStream is = FileMagic.prepareToCheckMagic(in);
            if (FileMagic.valueOf(is) == FileMagic.OOXML) readXlsx(is, sheetName, out);
            else readXls(is, sheetName, out);
            return out.table();
        } catch (EncryptedDocumentException e) {
            throw new UploadParseException("Excel 檔案有密碼保護，請先移除密碼");
        } catch (IOException | OpenXML4JException | SAXException | XmlException | IllegalArgumentException
                 | POIXMLException | RecordFormatException e) {
            // 檔案壞掉或根本不是 Excel；NPE 之類的程式錯誤不在這裡攔，照常變成 500
            log.warn("Excel 檔案無法讀取", e);
            throw new UploadParseException("Excel 檔案無法讀取");
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("SAX parser 設定錯誤", e);
        }
    }

    private static void readXls(InputStream in, String sheetName, Rows out) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = blank(sheetName) ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) throw new UploadParseException("找不到工作表：" + sheetName);
            for (Row row : sheet) {
                List<Object> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) cells.add(value(row.getCell(c)));
                out.add(cells);
            }
        }
    }

    private static void readXlsx(InputStream in, String sheetName, Rows out)
            throws IOException, OpenXML4JException, SAXException, XmlException, ParserConfigurationException {
        // 從 InputStream 開會把每個解壓後的 part 讀進記憶體（還有 100 MB 上限），先落地成暫存檔再用檔案開
        Path tmp = Files.createTempFile("jev-upload-", ".xlsx");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            OPCPackage pkg = OPCPackage.open(tmp.toFile(), PackageAccess.READ);
            try {
                XSSFReader reader = new XSSFReader(pkg);
                XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                while (sheets.hasNext()) {
                    try (InputStream sheet = sheets.next()) {
                        if (!blank(sheetName) && !sheets.getSheetName().equalsIgnoreCase(sheetName)) continue;
                        XMLReader xml = XMLHelper.newXMLReader();
                        xml.setContentHandler(new SheetHandler(new ReadOnlySharedStringsTable(pkg, false),
                            reader.getStylesTable(), date1904(reader), out));
                        xml.parse(new InputSource(sheet));
                        return;
                    }
                }
                throw new UploadParseException("找不到工作表：" + sheetName);
            } finally {
                pkg.revert();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean date1904(XSSFReader reader) throws IOException, OpenXML4JException, XmlException {
        try (InputStream in = reader.getWorkbookData()) {
            CTWorkbookPr pr = WorkbookDocument.Factory.parse(in, POIXMLTypeLoader.DEFAULT_XML_OPTIONS).getWorkbook().getWorkbookPr();
            return pr != null && pr.getDate1904();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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

    /** 一列一列收儲存格值：第一列是標題，整列空白就跳過，超過上限立刻中止。 */
    private static final class Rows {
        private final int maxRows;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private List<String> headers;

        Rows(int maxRows) {
            this.maxRows = maxRows;
        }

        void add(List<Object> cells) {
            if (headers == null) {
                headers = ParsedTable.checkHeaders(cells.stream().map(v -> String.valueOf(v).trim()).toList());
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            boolean hasValue = false;
            for (int c = 0; c < headers.size(); c++) {
                Object v = c < cells.size() ? cells.get(c) : "";
                values.put(headers.get(c), v);
                if (!"".equals(v)) hasValue = true;
            }
            if (!hasValue) return;
            // ponytail: 一超過上限就中止解析，不必把整份檔案讀完才發現太大
            if (rows.size() >= maxRows) throw new UploadParseException("資料超過上限 " + maxRows + " 筆");
            rows.add(values);
        }

        ParsedTable table() {
            if (headers == null) throw new UploadParseException("第 1 行沒有標題列");
            return new ParsedTable(headers, rows);
        }
    }

    /** 工作表 XML 的 SAX handler：一次只留一列；儲存格轉值的規則和 value(Cell) 一樣。 */
    private static final class SheetHandler extends DefaultHandler {
        private final ReadOnlySharedStringsTable strings;
        private final Map<Integer, String> sharedStrings = new HashMap<>();
        private final StylesTable styles;
        private final Map<Integer, Boolean> dateStyles = new HashMap<>();
        private final boolean date1904;
        private final Rows out;
        private final List<Object> cells = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String type;
        private int style;
        private int col;
        private boolean inText;
        private boolean inInlineString;
        private boolean inPhonetic;

        SheetHandler(ReadOnlySharedStringsTable strings, StylesTable styles, boolean date1904, Rows out) {
            this.strings = strings;
            this.styles = styles;
            this.date1904 = date1904;
            this.out = out;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if (!XSSFRelation.NS_SPREADSHEETML.equals(uri)) return;
            switch (localName) {
                case "row" -> {
                    cells.clear();
                    col = -1;
                }
                case "c" -> {
                    String ref = attrs.getValue("r");
                    col = ref == null ? col + 1 : new CellAddress(ref).getColumn();
                    type = attrs.getValue("t");
                    String s = attrs.getValue("s");
                    style = s == null ? 0 : Integer.parseInt(s);
                    text.setLength(0);
                }
                case "v" -> inText = true;
                case "is" -> inInlineString = true;
                case "rPh" -> inPhonetic = true;
                case "t" -> inText = inInlineString && !inPhonetic;
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!XSSFRelation.NS_SPREADSHEETML.equals(uri)) return;
            switch (localName) {
                case "v", "t" -> inText = false;
                case "is" -> inInlineString = false;
                case "rPh" -> inPhonetic = false;
                case "c" -> {
                    while (cells.size() <= col) cells.add("");
                    cells.set(col, value());
                }
                case "row" -> out.add(cells);
                default -> { }
            }
        }

        private Object value() {
            String v = text.toString();
            if (type == null || type.equals("n")) {
                if (v.isEmpty()) return "";
                double d = Double.parseDouble(v);
                return isDateStyle() && DateUtil.isValidExcelDate(d)
                    ? DateUtil.getLocalDateTime(d, date1904).toLocalDate().toString()
                    : (Object) d;
            }
            return switch (type) {
                case "s" -> sharedStrings.computeIfAbsent(Integer.parseInt(v), i -> strings.getItemAt(i).getString().trim());
                case "b" -> String.valueOf("1".equals(v));
                case "e" -> "";
                default -> v.trim(); // str（公式的字串結果）、inlineStr、d
            };
        }

        private boolean isDateStyle() {
            return dateStyles.computeIfAbsent(style, i -> {
                XSSFCellStyle st = styles == null ? null : styles.getStyleAt(i);
                return st != null && DateUtil.isADateFormat(st.getDataFormat(), st.getDataFormatString());
            });
        }
    }
}
