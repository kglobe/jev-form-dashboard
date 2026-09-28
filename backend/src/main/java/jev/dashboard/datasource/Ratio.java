package jev.dashboard.datasource;

/**
 * B3 spec 4.1：比率＝每組分子加總 ÷ 分母加總。numerator、denominator 是欄位的原始名稱。
 * sensitive 是讀取來源時從分子、分母算出來的（spec 4.3），存進 ratios_json 的一律是 false。
 */
public record Ratio(String name, String numerator, String denominator, boolean sensitive) {
    public Ratio(String name, String numerator, String denominator) {
        this(name, numerator, denominator, false);
    }

    public Ratio withSensitive(boolean value) {
        return new Ratio(name, numerator, denominator, value);
    }
}
