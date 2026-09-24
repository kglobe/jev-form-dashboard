package jev.dashboard.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mapping(String x, String y, String series, String value, List<String> measures,
                      List<String> columns, String aggregate, String timeGrain) {}
