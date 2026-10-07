package jev.dashboard.datasource;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jev.dashboard.ApiException;

/**
 * 寬表轉長表 spec：上傳時把「1月…12月」這類欄位轉成期間、數值兩欄。columns 是轉的欄位，dropped 是沒匯入的合計欄，
 * year 是標題沒寫年份時用的西元年（每個欄位都有寫時是 null）。跟著來源存下來，更新資料時照同樣方式轉（spec 2.6）。
 */
public record Unpivot(List<String> columns, List<String> dropped, String periodName, String valueName, Integer year) {
    /** 上傳時使用者填的選項，都可以空白（spec 第 3 節）。 */
    public record Options(String year, String periodName, String valueName) {}

    /** quarter 時 month 是那一季的第一個月；year 是 null 表示標題沒寫年份。 */
    private record Period(Integer year, int month, boolean quarter) {
        LocalDate date(Integer fallback) {
            return LocalDate.of(year != null ? year : fallback, month, 1);
        }
    }

    private static final List<String> TOTALS = List.of("合計", "總計", "小計", "累計", "全年", "total");
    private static final List<String> MONTH_NAMES = List.of("january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december");
    private static final List<String> CHINESE = List.of("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二");
    // 年份 2 碼只在後面接「年」時認，不然「12.5」「10/12」這類標題會被當成民國年
    private static final List<Pattern> YEAR_MONTH = List.of(
        Pattern.compile("(\\d{3,4})[-/.](\\d{1,2})"),
        Pattern.compile("(\\d{2,4})年(\\d{1,2})月?份?"),
        Pattern.compile("((?:19|20)\\d{2})(0[1-9]|1[0-2])"));
    private static final Pattern MONTH = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十]{1,2})月份?");
    private static final List<Pattern> YEAR_QUARTER = List.of(
        Pattern.compile("(\\d{3,4})[-_]?q([1-4])"),
        Pattern.compile("(\\d{2,4})年度?q([1-4])"),
        Pattern.compile("(\\d{2,4})年度?第([1-4一二三四])季"));
    private static final List<Pattern> QUARTER = List.of(
        Pattern.compile("q([1-4])"), Pattern.compile("([1-4])q"), Pattern.compile("第([1-4一二三四])季"));

    public static Unpivot detect(List<String> headers, Options options) {
        Map<String, Period> periods = new LinkedHashMap<>();
        for (String h : headers) {
            Period p = period(h);
            if (p != null) periods.put(h, p);
        }
        if (periods.isEmpty()) throw bad("找不到標題是月份或季的欄位，例如 1月、2024-01、Q1");
        String month = first(periods, false);
        String quarter = first(periods, true);
        if (month != null && quarter != null) throw bad("月份和季的欄位不能混在一起：" + month + "、" + quarter);
        String noYear = periods.entrySet().stream().filter(e -> e.getValue().year() == null)
            .map(Map.Entry::getKey).findFirst().orElse(null);
        Integer year = noYear == null ? null : year(options.year(), noYear);
        Map<LocalDate, String> seen = new HashMap<>();
        periods.forEach((h, p) -> {
            String before = seen.putIfAbsent(p.date(year), h);
            if (before != null) throw bad("欄位「" + before + "」和「" + h + "」是同一個期間");
        });
        List<String> dropped = headers.stream().filter(h -> !periods.containsKey(h) && total(h)).toList();
        String periodName = blank(options.periodName()) ? (quarter != null ? "季" : "月份") : options.periodName().strip();
        String valueName = blank(options.valueName()) ? "數值" : options.valueName().strip();
        List<String> kept = headers.stream().filter(h -> !periods.containsKey(h) && !dropped.contains(h)).toList();
        for (String n : List.of(periodName, valueName)) {
            if (kept.contains(n)) throw bad("欄位名稱「" + n + "」跟其他欄位重複，請換一個");
        }
        if (periodName.equals(valueName)) throw bad("欄位名稱「" + valueName + "」跟其他欄位重複，請換一個");
        return new Unpivot(List.copyOf(periods.keySet()), dropped, periodName, valueName, year);
    }

    /** spec 2.4：每列每個有值的期間欄位變成一列，空白的格子跳過；留下的欄位照原本順序，再接期間、數值。 */
    public ParsedTable apply(ParsedTable wide) {
        List<String> kept = wide.headers().stream().filter(h -> !columns.contains(h) && !dropped.contains(h)).toList();
        List<String> dates = columns.stream().map(c -> period(c).date(year).toString()).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : wide.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                Object v = row.get(columns.get(i));
                if (SchemaInference.isBlank(v)) continue;
                Map<String, Object> out = new LinkedHashMap<>();
                kept.forEach(k -> out.put(k, row.get(k)));
                out.put(periodName, dates.get(i));
                out.put(valueName, v);
                rows.add(out);
            }
        }
        List<String> headers = new ArrayList<>(kept);
        headers.add(periodName);
        headers.add(valueName);
        return new ParsedTable(headers, rows);
    }

    /** spec 2.6：更新資料時新檔案該有的欄位＝長表裡留下的欄位，加上轉的和拿掉的。 */
    public List<String> wideColumns(List<String> longColumns) {
        List<String> out = new ArrayList<>(longColumns.stream()
            .filter(n -> !n.equals(periodName) && !n.equals(valueName)).toList());
        out.addAll(columns);
        out.addAll(dropped);
        return out;
    }

    /** spec 2.1：認不出是某個月或某一季就回 null。全形字先轉半形，再拿掉空白、轉小寫。 */
    private static Period period(String header) {
        String normalized = Normalizer.normalize(header, Normalizer.Form.NFKC).strip();
        String s = normalized.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        for (Pattern p : YEAR_MONTH) {
            Matcher m = p.matcher(s);
            if (m.matches()) return month(year(m.group(1)), Integer.parseInt(m.group(2)));
        }
        // 日期後面帶時間時中間的空白要留著，所以拿沒去掉空白的那份比
        LocalDate date = SchemaInference.parseDate(normalized);
        if (date != null) return new Period(date.getYear(), date.getMonthValue(), false);
        Matcher m = MONTH.matcher(s);
        if (m.matches()) {
            String n = m.group(1);
            return month(null, CHINESE.contains(n) ? CHINESE.indexOf(n) + 1 : n.matches("\\d+") ? Integer.parseInt(n) : 0);
        }
        String name = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
        for (int i = 0; i < 12; i++) {
            String full = MONTH_NAMES.get(i);
            if (name.equals(full) || name.equals(full.substring(0, 3)) || (i == 8 && name.equals("sept"))) {
                return new Period(null, i + 1, false);
            }
        }
        for (Pattern p : YEAR_QUARTER) {
            Matcher q = p.matcher(s);
            if (q.matches()) return quarter(year(q.group(1)), q.group(2));
        }
        for (Pattern p : QUARTER) {
            Matcher q = p.matcher(s);
            if (q.matches()) return quarter(null, q.group(1));
        }
        return null;
    }

    private static Period month(Integer year, int month) {
        return month >= 1 && month <= 12 ? new Period(year, month, false) : null;
    }

    private static Period quarter(Integer year, String q) {
        int n = "一二三四".contains(q) ? "一二三四".indexOf(q) + 1 : Integer.parseInt(q);
        return new Period(year, (n - 1) * 3 + 1, true);
    }

    /** 年份 2～3 碼是民國年，跟日期欄位的規則一樣。 */
    private static int year(String digits) {
        int y = Integer.parseInt(digits);
        return digits.length() < 4 ? y + 1911 : y;
    }

    /** spec 2.2：標題沒寫年份時才用得到使用者填的年份。 */
    private static int year(String text, String header) {
        if (blank(text)) throw bad("欄位「" + header + "」沒寫年份，請填年份");
        String t = text.strip();
        if (!t.matches("\\d{2,4}")) throw bad("年份要填西元 1900～2099 年，或民國年");
        int y = year(t);
        if (y < 1900 || y > 2099) throw bad("年份要填西元 1900～2099 年，或民國年");
        return y;
    }

    private static String first(Map<String, Period> periods, boolean quarter) {
        return periods.entrySet().stream().filter(e -> e.getValue().quarter() == quarter)
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static boolean total(String header) {
        String s = header.toLowerCase(Locale.ROOT);
        return TOTALS.stream().anyMatch(s::contains);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ApiException bad(String message) {
        return new ApiException(BAD_REQUEST, message);
    }
}
