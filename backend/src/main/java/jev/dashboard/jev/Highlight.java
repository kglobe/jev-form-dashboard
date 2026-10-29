package jev.dashboard.jev;

public record Highlight(String chartId, String severity, int level, double noul, Change change) {
    public record Change(String previousPeriod, String period, Integer measure, String series,
                         double previous, double current, boolean percent) {}
}
