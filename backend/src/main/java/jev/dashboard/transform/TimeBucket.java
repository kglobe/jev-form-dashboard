package jev.dashboard.transform;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

final class TimeBucket {
    /** of() 認得的 grain，和下面的 switch 對齊。 */
    static final List<String> GRAINS = List.of("day", "month", "quarter");

    private TimeBucket() {}

    static String of(Object value, String grain) {
        if (value == null) return null;
        LocalDate d = LocalDate.parse(String.valueOf(value));
        return switch (grain) {
            case "day" -> d.toString();
            case "month" -> YearMonth.from(d).toString();
            case "quarter" -> d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
            default -> throw new IllegalArgumentException("未知的 timeGrain：" + grain);
        };
    }
}
