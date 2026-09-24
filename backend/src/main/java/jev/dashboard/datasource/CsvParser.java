package jev.dashboard.datasource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class CsvParser {
    private static final Charset MS950 = Charset.forName("MS950");

    private CsvParser() {}

    public static ParsedTable parse(byte[] bytes) {
        String text = decode(bytes);
        if (text.startsWith("﻿")) text = text.substring(1);
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).get();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            List<String> headers = parser.getHeaderNames();
            List<Map<String, Object>> rows = new ArrayList<>();
            // ponytail: 行號 = 資料列序號 + 2，儲存格內有換行時會算錯，遇到再改用 parser.getCurrentLineNumber()
            int line = 1;
            for (CSVRecord record : parser) {
                line++;
                if (record.size() != headers.size()) {
                    throw new UploadParseException("第 " + line + " 行有 " + record.size() + " 欄，標題列是 " + headers.size() + " 欄");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), record.get(i));
                rows.add(row);
            }
            return new ParsedTable(headers, rows);
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new UploadParseException("CSV 解析失敗：" + e.getMessage());
        }
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, MS950);
        }
    }
}
