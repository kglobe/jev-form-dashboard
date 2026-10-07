package jev.dashboard.dashboard;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import jev.dashboard.ApiException;

/**
 * 篩選器 spec 3：儀表板的篩選。period 是 null 時不限期間；fields 是分類篩選，可以是空的或 null。
 * field 是畫面上的欄位名稱（顯示名稱，沒有就是欄位名稱），同一個分類裡的值是「或」，分類之間、分類和期間是「且」。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Filters(String period, List<Field> fields) {
    public record Field(String field, List<String> values) {}

    static final List<String> PERIODS = List.of("this_month", "last_month", "this_quarter", "last_quarter", "this_year", "last_year");
    static final int MAX_FIELDS = 3;
    static final int MAX_FIELD_LENGTH = 50;
    static final int MAX_VALUES = 200;
    static final int MAX_VALUE_LENGTH = 200;
    /** 篩選放在 GET 的查詢參數裡，Spring Boot 的 header 上限是 8KB；留一半給網址的其他部分和 cookie。 */
    static final int MAX_ENCODED_LENGTH = 4000;
    /** filterSkipped 裡代表期間的名稱。 */
    static final String PERIOD = "期間";

    // 跟 Spring Boot 的 ObjectMapper 一樣不理沒見過的欄位，查詢參數和 PUT 的 body 才會一致
    private static final ObjectMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** 篩選器 spec 4：查詢參數 filters=<JSON>。沒帶、沒有任何篩選都回 null（不篩選），不合法就 400。 */
    public static Filters parse(String raw) {
        if (raw == null) return null;
        Filters f;
        try {
            f = JSON.readValue(raw, Filters.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(BAD_REQUEST, "篩選的格式不對");
        }
        requireValid(f);
        return nonEmpty(f);
    }

    /** 篩選器 spec 3：存預設和查詢參數共用同一套訊息。 */
    public static void requireValid(Filters f) {
        if (f == null) return;
        if (f.period() != null && !PERIODS.contains(f.period())) throw invalid("期間只能是 本月、上月、本季、上季、今年、去年");
        if (f.fields() == null) return;
        if (f.fields().size() > MAX_FIELDS) throw invalid("分類篩選最多 3 個");
        Set<String> seen = new HashSet<>();
        for (Field x : f.fields()) {
            if (x == null || x.field() == null || x.field().isBlank()) throw invalid("篩選的欄位名稱不能空白");
            if (x.field().length() > MAX_FIELD_LENGTH) throw invalid("篩選的欄位名稱最多 50 個字");
            if (!seen.add(x.field())) throw invalid("同一個欄位只能篩選一次");
            if (x.values() == null || x.values().isEmpty() || x.values().size() > MAX_VALUES
                || x.values().stream().anyMatch(Objects::isNull)) {
                throw invalid("每個篩選要選 1～200 個值");
            }
            if (x.values().stream().anyMatch(v -> v.length() > MAX_VALUE_LENGTH)) throw invalid("篩選的值最多 200 個字");
        }
        // 存成預設的篩選之後每次打開都要放進網址，太長就連儀表板都打不開
        if (encodedLength(f.key()) > MAX_ENCODED_LENGTH) throw invalid("篩選選太多了，請少選一些值");
    }

    /**
     * 跟瀏覽器的 encodeURIComponent 算法一樣的長度：英數字和 -_.!~*'() 一個字，其他每個 UTF-8 位元組寫成 %XX。
     * 前端用 encodeURIComponent(JSON.stringify(filters)).length 擋，這裡量的 JSON 沒有 null 的 period，只會比前端短，
     * 前端放行的這裡一定放行。
     */
    static int encodedLength(String s) {
        int n = 0;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            n += b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || "-_.!~*'()".indexOf(b) >= 0 ? 1 : 3;
        }
        return n;
    }

    /** 沒有任何篩選時回 null：存檔不寫出來，recent 的 key 也跟不篩選一樣。 */
    public static Filters nonEmpty(Filters f) {
        return f == null || f.period() == null && (f.fields() == null || f.fields().isEmpty()) ? null : f;
    }

    /** 篩選器 spec 2.1：期間的第一天和最後一天，都是日曆上的整段，包含還沒到的日子。period 要先驗證過、不是 null。 */
    LocalDate[] range(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        YearMonth quarter = month.minusMonths((month.getMonthValue() - 1) % 3);
        YearMonth year = month.withMonth(1);
        YearMonth[] span = switch (period) {
            case "this_month" -> new YearMonth[] {month, month};
            case "last_month" -> new YearMonth[] {month.minusMonths(1), month.minusMonths(1)};
            case "this_quarter" -> new YearMonth[] {quarter, quarter.plusMonths(2)};
            case "last_quarter" -> new YearMonth[] {quarter.minusMonths(3), quarter.minusMonths(1)};
            case "this_year" -> new YearMonth[] {year, year.plusMonths(11)};
            case "last_year" -> new YearMonth[] {year.minusYears(1), year.minusMonths(1)};
            default -> throw new IllegalArgumentException("未知的期間：" + period);
        };
        return new LocalDate[] {span[0].atDay(1), span[1].atEndOfMonth()};
    }

    /** 篩選器 spec 5：recent 的 key 用的標準 JSON；寫成 JSON 而不是 toString，值裡有逗號、括號也不會跟別組撞在一起。 */
    String key() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(BAD_REQUEST, message);
    }
}
