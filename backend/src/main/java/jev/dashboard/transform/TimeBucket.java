package jev.dashboard.transform;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public final class TimeBucket {
    /** of() 認得的 grain，和下面的 switch 對齊。 */
    static final List<String> GRAINS = List.of("day", "month", "quarter");

    private TimeBucket() {}

    public static String of(Object value, String grain) {
        if (value == null) return null;
        LocalDate d = LocalDate.parse(String.valueOf(value));
        return switch (grain) {
            case "day" -> d.toString();
            case "month" -> YearMonth.from(d).toString();
            case "quarter" -> d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
            default -> throw new IllegalArgumentException("未知的 timeGrain：" + grain);
        };
    }

    /**
     * 已經結束的期：今天所在的那一期和之後的期都不算。期的 key 是 of() 的結果，字串排序就是時間順序。
     * 異常檢查和 KPI 卡（KPI spec 2.1）共用這個判斷。
     */
    public static boolean ended(String period, LocalDate today, String grain) {
        return period.compareTo(of(today, grain)) < 0;
    }
}
