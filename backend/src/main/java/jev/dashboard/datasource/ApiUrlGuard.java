package jev.dashboard.datasource;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 允許清單就是 SSRF 的防線。ERP API 多半在內網，所以私有 IP 不擋，只擋 loopback。 */
@Component
public class ApiUrlGuard {
    // 網址會存進資料庫、顯示在畫面上；驗證資訊不能放在這裡（spec §6.2），要放 headers 的 ${DS_XXX}
    private static final Set<String> SECRET_PARAMS = Set.of("access_token", "token", "api_key", "apikey", "secret",
        "password", "passwd", "pwd", "auth", "authorization", "signature", "sig");

    private final Set<String> allowedHosts;

    @Autowired
    public ApiUrlGuard(JevProperties props) {
        this(props.datasource() == null ? null : props.datasource().allowedHosts());
    }

    ApiUrlGuard(List<String> allowedHosts) {
        // API_ALLOWED_HOSTS=, 綁定出來是 ["", ""]：空白的項目不算，才會走到「未開放」那句訊息
        this.allowedHosts = allowedHosts == null ? Set.of()
            : allowedHosts.stream().filter(h -> !h.isBlank()).map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public URI check(String url) {
        if (url == null) throw bad("網址是空的");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw bad("網址格式錯誤：" + url);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw bad("只允許 http 或 https");
        if (uri.getRawUserInfo() != null || hasSecretParam(uri.getRawQuery())) {
            throw bad("網址不能包含帳密或 token，請改放在 headers，值寫 ${DS_環境變數名}");
        }
        String host = uri.getHost();
        if (host == null) throw bad("網址沒有 host：" + url);
        if (allowedHosts.isEmpty()) throw bad("未開放註冊外部 API：請在 backend/.env 設定 API_ALLOWED_HOSTS");
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) throw bad("host 不在允許清單：" + host);
        checkedAddresses(host);
        return uri;
    }

    /** 連線時的 DNS resolver 也走這裡，檢查的正是交給 socket 的位址。 */
    InetAddress[] checkedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = lookup(host);
        } catch (UnknownHostException e) {
            throw bad("無法解析 host：" + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isAnyLocalAddress()) throw bad("host 解析到本機位址：" + host);
        }
        return addresses;
    }

    InetAddress[] lookup(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    private static boolean hasSecretParam(String rawQuery) {
        if (rawQuery == null) return false;
        for (String pair : rawQuery.split("&")) {
            // new URI 已經驗過 % 編碼，這裡解碼不會失敗；解碼才擋得住 %74oken 這種寫法
            String name = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
            if (SECRET_PARAMS.contains(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
