package jev.dashboard;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jev")
public record JevProperties(String apiUrl, String apiKey, String model, Upload upload, Datasource datasource) {
    public record Upload(int maxRows) {}

    public record Datasource(List<String> allowedHosts) {}
}
