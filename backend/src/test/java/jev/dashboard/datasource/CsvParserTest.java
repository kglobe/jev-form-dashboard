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
}
