package jev.dashboard.datasource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class CsvParserTest {
    @Test
    void parsesUtf8WithBom() {
        byte[] bytes = ("﻿" + "name,amount\n北區,100\n南區,200\n").getBytes(UTF_8);
        ParsedTable t = CsvParser.parse(bytes);
        assertThat(t.headers()).containsExactly("name", "amount");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(0)).containsEntry("name", "北區").containsEntry("amount", "100");
    }

    @Test
    void fallsBackToMs950WhenNotUtf8() {
        byte[] bytes = "地區,金額\n北區,100\n".getBytes(Charset.forName("MS950"));
        ParsedTable t = CsvParser.parse(bytes);
        assertThat(t.headers()).containsExactly("地區", "金額");
        assertThat(t.rows().get(0)).containsEntry("地區", "北區");
    }

    @Test
    void reportsLineNumberOfRowWithWrongColumnCount() {
        byte[] bytes = "a,b\n1,2\n3\n".getBytes(UTF_8);
        assertThatThrownBy(() -> CsvParser.parse(bytes))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("第 3 行");
    }

    @Test
    void malformedRecordIsReportedInChineseWithItsPhysicalLine() {
        assertThatThrownBy(() -> CsvParser.parse("a,b\n1,2\n\"3,4\n5,6\n".getBytes(UTF_8)))
            .isInstanceOf(UploadParseException.class)
            .hasMessage("CSV 第 3 行格式錯誤");
        assertThatThrownBy(() -> CsvParser.parse("a,b\n1,\"x\"y\n".getBytes(UTF_8)))
            .isInstanceOf(UploadParseException.class)
            .hasMessage("CSV 第 2 行格式錯誤");
    }

    @Test
    void lineNumbersCountLineBreaksInsideQuotedCells() {
        assertThatThrownBy(() -> CsvParser.parse("a,b\n\"x\ny\",1\n3\n".getBytes(UTF_8)))
            .isInstanceOf(UploadParseException.class)
            .hasMessageStartingWith("第 4 行有 1 欄");
    }

    @Test
    void blankOrDuplicateHeaderIsRejected() {
        assertThatThrownBy(() -> CsvParser.parse("a,,c\n1,2,3\n".getBytes(UTF_8)))
            .isInstanceOf(UploadParseException.class)
            .hasMessage("欄位名稱不能空白");
        assertThatThrownBy(() -> CsvParser.parse("a, ,c\n1,2,3\n".getBytes(UTF_8)))
            .hasMessage("欄位名稱不能空白");
        assertThatThrownBy(() -> CsvParser.parse("a,a\n1,2\n".getBytes(UTF_8)))
            .isInstanceOf(UploadParseException.class)
            .hasMessage("欄位名稱重複：a");
    }

    @Test
    void stopsParsingAsSoonAsRowCountExceedsCap() {
        byte[] bytes = "a\n1\n2\n3\n".getBytes(UTF_8);
        assertThatThrownBy(() -> CsvParser.parse(bytes, 2))
            .isInstanceOf(UploadParseException.class)
            .hasMessageContaining("超過上限 2 筆");
    }
}
