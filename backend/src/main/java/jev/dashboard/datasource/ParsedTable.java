package jev.dashboard.datasource;

import java.util.List;
import java.util.Map;

public record ParsedTable(List<String> headers, List<Map<String, Object>> rows) {}
