package jev.dashboard.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import jev.dashboard.ApiException;
import jev.dashboard.dashboard.Filters.Field;
import jev.dashboard.transform.UniversalTransformer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FiltersTest {
    static LocalDate[] range(String period, String today) {
        return new Filters(period, null).range(LocalDate.parse(today));
    }

    static LocalDate[] days(String from, String to) {
        return new LocalDate[] {LocalDate.parse(from), LocalDate.parse(to)};
    }

    @Test
    void eachPeriodIsAWholeCalendarSpan() {
        // 篩選器 spec 2.1：月初到月底、季初到季末，今天在哪一天都一樣
        assertThat(range("this_month", "2026-02-01")).isEqualTo(days("2026-02-01", "2026-02-28"));
        assertThat(range("this_month", "2026-02-28")).isEqualTo(days("2026-02-01", "2026-02-28"));
        assertThat(range("last_month", "2026-03-31")).isEqualTo(days("2026-02-01", "2026-02-28"));
        assertThat(range("this_quarter", "2026-10-07")).isEqualTo(days("2026-10-01", "2026-12-31"));
        assertThat(range("last_quarter", "2026-10-07")).isEqualTo(days("2026-07-01", "2026-09-30"));
        assertThat(range("this_year", "2026-10-07")).isEqualTo(days("2026-01-01", "2026-12-31"));
        assertThat(range("last_year", "2026-10-07")).isEqualTo(days("2025-01-01", "2025-12-31"));
    }

    @Test
    void lastMonthAndLastQuarterCrossTheYear() {
        assertThat(range("last_month", "2026-01-01")).isEqualTo(days("2025-12-01", "2025-12-31"));
        assertThat(range("last_quarter", "2026-03-31")).isEqualTo(days("2025-10-01", "2025-12-31"));
        assertThat(range("this_quarter", "2026-03-31")).isEqualTo(days("2026-01-01", "2026-03-31"));
    }

    static void rejects(String json, String message) {
        assertThatThrownBy(() -> Filters.parse(json))
            .isInstanceOf(ApiException.class)
            .hasMessage(message)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    static void rejects(Filters f, String message) {
        assertThatThrownBy(() -> Filters.requireValid(f)).isInstanceOf(ApiException.class).hasMessage(message);
    }

    static Filters fields(Field... fields) {
        return new Filters(null, Arrays.asList(fields));
    }

    @Test
    void everyRuleHasItsOwnMessage() {
        rejects(new Filters("yesterday", null), "期間只能是 本月、上月、本季、上季、今年、去年");
        Field one = new Field("地區", List.of("北區"));
        rejects(fields(one, new Field("產品", List.of("A")), new Field("通路", List.of("網路")), new Field("等級", List.of("A"))),
            "分類篩選最多 3 個");
        rejects(fields(new Field(" ", List.of("北區"))), "篩選的欄位名稱不能空白");
        rejects(fields(new Field(null, List.of("北區"))), "篩選的欄位名稱不能空白");
        rejects(fields((Field) null), "篩選的欄位名稱不能空白");
        rejects(fields(new Field("欄".repeat(51), List.of("北區"))), "篩選的欄位名稱最多 50 個字");
        rejects(fields(one, new Field("地區", List.of("南區"))), "同一個欄位只能篩選一次");
        rejects(fields(new Field("地區", List.of())), "每個篩選要選 1～200 個值");
        rejects(fields(new Field("地區", null)), "每個篩選要選 1～200 個值");
        rejects(fields(new Field("地區", Collections.nCopies(201, "北區"))), "每個篩選要選 1～200 個值");
        rejects(fields(new Field("地區", Arrays.asList("北區", null))), "每個篩選要選 1～200 個值");
        rejects(fields(new Field("地區", List.of("值".repeat(201)))), "篩選的值最多 200 個字");
    }

    @Test
    void theLimitsThemselvesAreFine() {
        List<String> values = new ArrayList<>(Collections.nCopies(199, "北區"));
        values.add("值".repeat(200));
        Filters.requireValid(new Filters("last_year", List.of(new Field("欄".repeat(50), values),
            new Field("產品", List.of("A")), new Field("通路", List.of(UniversalTransformer.BLANK)))));
        Filters.requireValid(null);
        Filters.requireValid(new Filters(null, null));
    }

    @Test
    void theQueryParameterIsParsedWithTheSameRules() {
        assertThat(Filters.parse("{\"period\":\"last_quarter\",\"fields\":[{\"field\":\"地區\",\"values\":[\"北區\",\"南區\"]}]}"))
            .isEqualTo(new Filters("last_quarter", List.of(new Field("地區", List.of("北區", "南區")))));
        rejects("{\"period\":\"yesterday\"}", "期間只能是 本月、上月、本季、上季、今年、去年");
        rejects("{\"fields\":[{\"field\":\"地區\",\"values\":[]}]}", "每個篩選要選 1～200 個值");
        rejects("{\"period\":", "篩選的格式不對");
        rejects("[]", "篩選的格式不對");
        rejects("北區", "篩選的格式不對");
    }

    @Test
    void noFilterAtAllIsNull() {
        // 沒帶、沒有任何篩選都是不篩選：存檔不寫、recent 的 key 跟沒篩選一樣
        assertThat(Filters.parse(null)).isNull();
        assertThat(Filters.parse("{}")).isNull();
        assertThat(Filters.parse("{\"period\":null,\"fields\":[]}")).isNull();
        assertThat(Filters.nonEmpty(new Filters(null, List.of()))).isNull();
        assertThat(Filters.nonEmpty(new Filters("this_year", List.of()))).isNotNull();
    }

    @Test
    void theKeyTellsApartValuesThatLookAlikeAsText() {
        Filters a = fields(new Field("地區", List.of("北區, 南區")));
        Filters b = fields(new Field("地區", List.of("北區", "南區")));
        assertThat(a.key()).isNotEqualTo(b.key());
        assertThat(a.key()).isEqualTo(fields(new Field("地區", List.of("北區, 南區"))).key());
    }
}
