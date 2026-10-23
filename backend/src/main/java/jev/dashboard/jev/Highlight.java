package jev.dashboard.jev;

/** noul 超過門檻的一張卡。level 是 severity 在 HighlightService.SEVERITY 裡的位置（0～3），本期重點照它排。 */
public record Highlight(String chartId, String severity, int level, double noul, Change change) {
    /**
     * 本期重點 spec 3：這張卡這一期變化最大的那條系列。measure 是兩個數值的圖在 mapping.measures 裡的位置，
     * series 是分系列的圖的系列值，兩個都是 null 時卡片只有一條系列。previous、current 是原始值，比率是小數。
     */
    public record Change(String previousPeriod, String period, Integer measure, String series,
                         double previous, double current, boolean percent) {}
}
