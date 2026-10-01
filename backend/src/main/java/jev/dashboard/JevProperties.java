package jev.dashboard;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jev")
public record JevProperties(String apiUrl, String apiKey, String model, Upload upload, Datasource datasource) {
    public record Upload(int maxRows) {}

    /** secretHosts：DS_ 密鑰名稱 → 這把密鑰可以送去的 host（密鑰綁定 spec 3.1）。 */
    public record Datasource(List<String> allowedHosts, Map<String, List<String>> secretHosts) {}
}
