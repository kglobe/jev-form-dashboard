package jev.dashboard.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jev.dashboard.ApiException;
import org.junit.jupiter.api.Test;

/** 寬表轉長表 spec 第 2 節：認得哪些標題、年份、合計欄、轉出來的兩欄和擋下的情況。 */
class UnpivotTest {
    static final Unpivot.Options NONE = new Unpivot.Options(null, null, null);

    static Unpivot detect(Unpivot.Options options, String... headers) {
        return Unpivot.detect(List.of(headers), options);
    }

    /** 標題「部門」加上 header，一列值 1，轉完回傳期間欄的值。 */
    static String periodOf(String header, String year) {
        Unpivot u = detect(new Unpivot.Options(year, null, null), "部門", header);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("部門", "業務");
        row.put(header, "1");
        return (String) u.apply(new ParsedTable(List.of("部門", header), List.of(row))).rows().getFirst().get(u.periodName());
    }

    @Test
    void recognisesEveryHeaderFormInTheSpecTable() {
        Map<String, String> withYear = Map.ofEntries(
            Map.entry("2024-01", "2024-01-01"), Map.entry("2024/1", "2024-01-01"), Map.entry("2024.01", "2024-01-01"),
            Map.entry("2024年1月", "2024-01-01"), Map.entry("113年1月", "2024-01-01"), Map.entry("113/01", "2024-01-01"),
            Map.entry("202401", "2024-01-01"), Map.entry("2024-01-15", "2024-01-01"), Map.entry("2024/1/1", "2024-01-01"),
            Map.entry("2024Q2", "2024-04-01"), Map.entry("2024-q2", "2024-04-01"), Map.entry("2024年Q2", "2024-04-01"),
            Map.entry("2024年第2季", "2024-04-01"), Map.entry("113年第二季", "2024-04-01"), Map.entry("2024 Q4", "2024-10-01"));
        withYear.forEach((header, date) -> assertThat(periodOf(header, null)).as(header).isEqualTo(date));

        Map<String, String> needYear = Map.ofEntries(
            Map.entry("1月", "2026-01-01"), Map.entry("01月", "2026-01-01"), Map.entry("12月份", "2026-12-01"),
            Map.entry("一月", "2026-01-01"), Map.entry("十二月", "2026-12-01"), Map.entry("Jan", "2026-01-01"),
            Map.entry("sept", "2026-09-01"), Map.entry("December", "2026-12-01"), Map.entry("Q1", "2026-01-01"),
            Map.entry("3q", "2026-07-01"), Map.entry("第4季", "2026-10-01"), Map.entry("第三季", "2026-07-01"),
            Map.entry("１月", "2026-01-01"), Map.entry(" 2 月 ", "2026-02-01"));
        needYear.forEach((header, date) -> assertThat(periodOf(header, "2026")).as(header).isEqualTo(date));
    }

    @Test
    void plainNumbersAndOtherHeadersAreNotPeriods() {
        for (String h : new String[] {"1", "12", "13月", "2024-13", "Q5", "金額", "12.5", "10/12", "May Day"}) {
            assertThatThrownBy(() -> detect(NONE, "部門", h)).as(h)
                .isInstanceOf(ApiException.class).hasMessage("找不到標題是月份或季的欄位，例如 1月、2024-01、Q1");
        }
    }

    @Test
    void theYearFillsInHeadersWithoutOneAndMayBeMinguo() {
        assertThat(detect(new Unpivot.Options("115", null, null), "1月", "2月").year()).isEqualTo(2026);
        assertThat(detect(new Unpivot.Options(" 2025 ", null, null), "1月").year()).isEqualTo(2025);
        // 標題自己有年份時不看填的年份，填錯也沒關係
        assertThat(detect(new Unpivot.Options("abc", null, null), "2024-01", "2024-02").year()).isNull();
        assertThatThrownBy(() -> detect(NONE, "部門", "1月", "2月")).hasMessage("欄位「1月」沒寫年份，請填年份");
        for (String bad : new String[] {"1", "2100", "1899", "20x6", "12345"}) {
            assertThatThrownBy(() -> detect(new Unpivot.Options(bad, null, null), "1月")).as(bad)
                .hasMessage("年份要填西元 1900～2099 年，或民國年");
        }
    }

    @Test
    void totalColumnsAreDroppedOnlyWhenUnpivoting() {
        Unpivot u = detect(new Unpivot.Options("2026", null, null), "部門", "1月", "2月", "全年合計", "Total", "備註");
        assertThat(u.columns()).containsExactly("1月", "2月");
        assertThat(u.dropped()).containsExactly("全年合計", "Total");

        Map<String, Object> row = new LinkedHashMap<>(Map.of("部門", "業務", "1月", "10", "2月", "", "全年合計", "10",
            "Total", "10", "備註", "x"));
        ParsedTable result = u.apply(new ParsedTable(List.of("部門", "1月", "2月", "全年合計", "Total", "備註"), List.of(row)));
        assertThat(result.headers()).containsExactly("部門", "備註", "月份", "數值");
        assertThat(result.rows()).containsExactly(Map.of("部門", "業務", "備註", "x", "月份", "2026-01-01", "數值", "10"));
    }

    @Test
    void blankCellsMakeNoRowsAndRowsKeepFileThenColumnOrder() {
        Unpivot u = detect(new Unpivot.Options("2026", "期", "金額"), "部門", "1月", "2月", "3月");
        List<Map<String, Object>> wide = List.of(
            row("部門", "業務", "1月", "1", "2月", "2", "3月", " "),
            row("部門", "研發", "1月", null, "2月", 3.0, "3月", "4"));
        ParsedTable t = u.apply(new ParsedTable(List.of("部門", "1月", "2月", "3月"), wide));

        assertThat(t.headers()).containsExactly("部門", "期", "金額");
        assertThat(t.rows()).extracting(r -> r.get("部門") + " " + r.get("期") + " " + r.get("金額")).containsExactly(
            "業務 2026-01-01 1", "業務 2026-02-01 2", "研發 2026-02-01 3.0", "研發 2026-03-01 4");
        assertThat(t.rows().getFirst().keySet()).containsExactly("部門", "期", "金額");
    }

    @Test
    void namesDefaultByGrainAndAreTrimmed() {
        assertThat(detect(new Unpivot.Options("2026", " ", ""), "1月").periodName()).isEqualTo("月份");
        assertThat(detect(new Unpivot.Options("2026", null, null), "Q1").periodName()).isEqualTo("季");
        Unpivot u = detect(new Unpivot.Options("2026", " 期間 ", " 營收 "), "1月");
        assertThat(u.periodName()).isEqualTo("期間");
        assertThat(u.valueName()).isEqualTo("營收");
        assertThat(detect(new Unpivot.Options("2026", null, null), "1月").valueName()).isEqualTo("數值");
    }

    @Test
    void rejectsMixedGrainsSamePeriodsAndClashingNames() {
        assertThatThrownBy(() -> detect(new Unpivot.Options("2026", null, null), "部門", "1月", "2月", "Q1"))
            .hasMessage("月份和季的欄位不能混在一起：1月、Q1");
        assertThatThrownBy(() -> detect(new Unpivot.Options("2026", null, null), "1月", "2026-01"))
            .hasMessage("欄位「1月」和「2026-01」是同一個期間");
        assertThatThrownBy(() -> detect(new Unpivot.Options("2026", "部門", null), "部門", "1月"))
            .hasMessage("欄位名稱「部門」跟其他欄位重複，請換一個");
        assertThatThrownBy(() -> detect(new Unpivot.Options("2026", null, "部門"), "部門", "1月"))
            .hasMessage("欄位名稱「部門」跟其他欄位重複，請換一個");
        assertThatThrownBy(() -> detect(new Unpivot.Options("2026", "x", "x"), "1月"))
            .hasMessage("欄位名稱「x」跟其他欄位重複，請換一個");
        // 拿掉的合計欄可以拿來當名稱
        assertThat(detect(new Unpivot.Options("2026", null, "合計"), "1月", "合計").valueName()).isEqualTo("合計");
    }

    @Test
    void wideColumnsAddBackTheConvertedAndDroppedColumns() {
        Unpivot u = detect(new Unpivot.Options("2026", null, null), "部門", "1月", "2月", "合計");
        assertThat(u.wideColumns(List.of("部門", "月份", "數值"))).containsExactly("部門", "1月", "2月", "合計");
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
