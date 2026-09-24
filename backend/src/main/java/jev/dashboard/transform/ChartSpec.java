package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(String chartId, String type, String title, GridPos gridPos, Mapping mapping,
                        Map<String, Object> options) {
    public record GridPos(int x, int y, int w, int h) {}
}
