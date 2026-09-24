package jev.dashboard.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import jev.dashboard.transform.ChartSpec;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardConfig(UUID id, String name, UUID datasourceId, String createdBy, List<ChartSpec> layout) {}
