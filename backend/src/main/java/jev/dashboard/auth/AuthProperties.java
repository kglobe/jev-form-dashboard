package jev.dashboard.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** 唯讀分享 spec 第 3 節：backend/.env 的登入設定。不加進 JevProperties（測試直接 new 它）。 */
@ConfigurationProperties("jev.auth")
public record AuthProperties(String adminUsername, String adminPassword, Oidc oidc) {
    public record Oidc(String issuerUri, String clientId, String clientSecret, String label) {
        /** spec 第 3 節：用 hasText 判斷，${X:} 的空字串也算沒設。 */
        public boolean enabled() {
            return StringUtils.hasText(issuerUri) && StringUtils.hasText(clientId);
        }
    }
}
