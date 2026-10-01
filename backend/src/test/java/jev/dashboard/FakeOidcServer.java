package jev.dashboard;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 測試用的 OIDC IdP。discovery 不給 userinfo_endpoint，OidcUserService 就不會去打 userinfo。 */
public final class FakeOidcServer implements AutoCloseable {
    public static final String CLIENT_ID = "jev-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Grant(String nonce, Map<String, Object> claims) {}

    private final HttpServer server;
    private final RSAKey key;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    private FakeOidcServer() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeOidcServer start() {
        try {
            return new FakeOidcServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** nonce 照 authorize 網址上的原樣（那已經是 Spring 算好的雜湊）；claims 至少要有 sub。 */
    public String code(String nonce, Map<String, Object> claims) {
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(nonce, claims));
        return code;
    }

    private void handle(HttpExchange ex) throws IOException {
        Object body = switch (ex.getRequestURI().getPath()) {
            case "/.well-known/openid-configuration" -> Map.of(
                "issuer", issuer(),
                "authorization_endpoint", issuer() + "/authorize",
                "token_endpoint", issuer() + "/token",
                "jwks_uri", issuer() + "/jwks",
                "response_types_supported", new String[] {"code"},
                "subject_types_supported", new String[] {"public"}, // nimbus 解析時必填
                "id_token_signing_alg_values_supported", new String[] {"RS256"});
            case "/jwks" -> new JWKSet(key.toPublicJWK()).toJSONObject();
            case "/token" -> token(new String(ex.getRequestBody().readAllBytes(), UTF_8));
            default -> null;
        };
        byte[] out = (body == null ? "{\"error\":\"not_found\"}" : JSON.writeValueAsString(body)).getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(body == null ? 404 : 200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** token endpoint：用 form body 的 code 換一張簽好的 id_token；找不到的 code 回 null（404）。 */
    private Map<String, Object> token(String form) {
        String code = null;
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("code")) code = URLDecoder.decode(kv[1], UTF_8);
        }
        Grant g = code == null ? null : grants.remove(code);
        if (g == null) return null;
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer())
            .audience(CLIENT_ID)
            .issueTime(new Date(now))
            .expirationTime(new Date(now + 5 * 60 * 1000))
            .claim("nonce", g.nonce());
        g.claims().forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return Map.of("access_token", "at", "token_type", "Bearer", "expires_in", 300, "id_token", jwt.serialize());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
