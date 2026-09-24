package jev.dashboard.datasource;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import jev.dashboard.ApiException;
import jev.dashboard.JevProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 允許清單就是 SSRF 的防線。ERP API 多半在內網，所以私有 IP 不擋，只擋 loopback。
 * ponytail: 檢查和實際連線各解析一次 DNS，擋不了 DNS rebinding；要防就把解析結果釘在連線上
 */
@Component
public class ApiUrlGuard {
    private final Set<String> allowedHosts;

    @Autowired
    public ApiUrlGuard(JevProperties props) {
        this(props.datasource() == null ? null : props.datasource().allowedHosts());
    }

    ApiUrlGuard(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? Set.of()
            : allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
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
        String host = uri.getHost();
        if (host == null) throw bad("網址沒有 host：" + url);
        if (allowedHosts.isEmpty()) throw bad("未開放註冊外部 API：jev.datasource.allowed-hosts 是空的");
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) throw bad("host 不在允許清單：" + host);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw bad("無法解析 host：" + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isAnyLocalAddress()) throw bad("host 解析到本機位址：" + host);
        }
        return uri;
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
