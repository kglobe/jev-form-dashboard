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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CsvParser {
    private static final Logger log = LoggerFactory.getLogger(CsvParser.class);
    private static final Charset MS950 = Charset.forName("MS950");

    private CsvParser() {}

    public static ParsedTable parse(byte[] bytes) {
        return parse(bytes, Integer.MAX_VALUE);
    }

    public static ParsedTable parse(byte[] bytes, int maxRows) {
        String text = decode(bytes);
        if (text.startsWith("﻿")) text = text.substring(1);
        // 空白欄名不讓 commons-csv 丟英文錯誤，交給 checkHeaders 回中文
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true)
            .setAllowMissingColumnNames(true).get();
        // 目前這筆資料開始的實體行號；儲存格內有換行也算得對（空白行會被跳過，那時會指到空白行）
        long line = 1;
        try (CSVParser parser = CSVParser.parse(text, format)) {
            List<String> headers = ParsedTable.checkHeaders(parser.getHeaderNames());
            List<Map<String, Object>> rows = new ArrayList<>();
            line = parser.getCurrentLineNumber() + 1;
            for (CSVRecord record : parser) {
                if (record.size() != headers.size()) {
                    throw new UploadParseException("第 " + line + " 行有 " + record.size() + " 欄，標題列是 " + headers.size() + " 欄");
                }
                // ponytail: 一超過上限就中止解析，避免整檔先塞進記憶體才發現太大
                if (rows.size() >= maxRows) {
                    throw new UploadParseException("資料超過上限 " + maxRows + " 筆");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), record.get(i));
                rows.add(row);
                line = parser.getCurrentLineNumber() + 1;
            }
            return new ParsedTable(headers, rows);
        } catch (IOException | UncheckedIOException e) {
            // commons-csv 的訊息是英文，只寫進 log，不回給使用者
            log.warn("CSV 第 {} 行格式錯誤", line, e);
            throw new UploadParseException("CSV 第 " + line + " 行格式錯誤");
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
