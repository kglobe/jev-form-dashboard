package jev.dashboard.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.StringUtils;

/** 唯讀分享 spec 6.2：一個 SecurityFilterChain。權限只靠 URL 規則，不用 @PreAuthorize。 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // 存成 {bcrypt}…
    }

    /** 唯讀分享 spec 2.2：帳密登入。停用的檢查移到密碼比對之後，密碼錯一律「帳號或密碼錯誤」。 */
    @Bean
    DaoAuthenticationProvider passwordLogin(AccountRepository accounts, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(username -> accounts.find(Account.normalize(username))
            .filter(a -> a.passwordHash() != null) // 只用單一登入的帳號當作不存在，走 Spring 內建的防時間差處理
            .map(a -> User.withUsername(a.username()).password(a.passwordHash())
                .roles(a.admin() ? "ADMIN" : "USER").disabled(!a.enabled()).build())
            .orElseThrow(() -> new UsernameNotFoundException(username)));
        p.setPasswordEncoder(encoder);
        p.setPreAuthenticationChecks(user -> {});
        p.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) throw new DisabledException("帳號已停用，請聯絡管理員");
        });
        return p;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, AccountRepository accounts,
            AuthProperties props, AccountService accountService) throws Exception {
        // spec 2.1：開始登入的時間（讀帳號之前）放在 details，AccountCheckFilter 拿它跟 sessions_valid_after 比
        AuthenticationDetailsSource<HttpServletRequest, Instant> loginStarted = req -> Instant.now();
        http
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                .requestMatchers("/api/auth/options").permitAll() // spec 4.1：登入頁要先知道有沒有單一登入
                .requestMatchers("/api/admin/**").hasRole("ADMIN") // spec 4.2
                .requestMatchers(HttpMethod.POST, "/api/datasources/api").hasRole("ADMIN") // spec 2.6：header 能借用伺服器上的 DS_ 密鑰
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()) // 之後放前端靜態檔
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .formLogin(f -> f
                .loginProcessingUrl("/api/auth/login")
                .authenticationDetailsSource(loginStarted)
                .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .failureHandler((req, res, e) -> {
                    if (e instanceof InternalAuthenticationServiceException) writeError(res, json, 500, "伺服器錯誤"); // 查帳號時資料庫失敗
                    else writeError(res, json, 401, e instanceof DisabledException ? "帳號已停用，請聯絡管理員" : "帳號或密碼錯誤");
                }))
            .logout(l -> l
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .sessionManagement(s -> s.sessionFixation(f -> f.newSession())) // spec 2.1：登入一律換新的工作階段
            .requestCache(r -> r.requestCache(new NullRequestCache()))
            .exceptionHandling(e -> e // 設了 entry point，Spring 就不會自己產生 /login 頁
                .authenticationEntryPoint((req, res, ex) -> writeError(res, json, 401, "請先登入"))
                .accessDeniedHandler((req, res, ex) -> writeError(res, json, 403,
                    ex instanceof CsrfException ? "頁面已過期，請重新整理" : "沒有權限")))
            .addFilterAfter(new AccountCheckFilter(accounts, json), AnonymousAuthenticationFilter.class); // spec 2.1
        if (props.oidc() != null && props.oidc().enabled()) { // spec 2.3：兩個都有填才碰 OIDC
            AuthProperties.Oidc oidc = props.oidc();
            requireSecureIssuer(oidc.issuerUri());
            http.oauth2Login(o -> o
                .clientRegistrationRepository(lazyRegistration(oidc))
                .authenticationDetailsSource(loginStarted)
                .userInfoEndpoint(u -> u
                    .oidcUserService(localAccounts(accountService))
                    .userService(r -> { throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request")); })) // 只收 OIDC
                .defaultSuccessUrl("/", true)
                .failureHandler((req, res, e) -> res.sendRedirect("/?login_error=" + URLEncoder.encode(
                    e instanceof OAuth2AuthenticationException o2 ? o2.getError().getErrorCode() : "failed", UTF_8))));
        }
        return http.build();
    }

    /** spec 2.3：issuer 要用 https，本機測試才放行 http://127.0.0.1。看解析後的 scheme 和 host，不比字串開頭。 */
    static void requireSecureIssuer(String issuer) {
        URI u = URI.create(issuer);
        boolean ok = "https".equalsIgnoreCase(u.getScheme())
            || ("http".equalsIgnoreCase(u.getScheme()) && "127.0.0.1".equals(u.getHost())); // scheme 不分大小寫（RFC 3986）
        if (!ok) throw new IllegalStateException("OIDC_ISSUER_URI 要用 https（本機測試才用 http://127.0.0.1）：" + issuer);
    }

    /**
     * spec 2.3：第一次有人按按鈕才去讀 issuer 的 discovery，讀到就記住；IdP 掛掉時帳密登入和啟動都不受影響。
     * ponytail: IdP 掛掉時按按鈕會看到 Spring 的錯誤頁，下次再按會重試；真的常發生，再自己接 OAuth2AuthorizationRequestRedirectFilter 的失敗處理
     */
    static ClientRegistrationRepository lazyRegistration(AuthProperties.Oidc p) {
        AtomicReference<ClientRegistration> cached = new AtomicReference<>();
        return id -> {
            if (!"oidc".equals(id)) return null;
            ClientRegistration r = cached.get();
            if (r == null) {
                boolean secret = StringUtils.hasText(p.clientSecret());
                ClientRegistration.Builder b = ClientRegistrations.fromIssuerLocation(p.issuerUri()) // 會檢查 issuer 一字不差
                    .registrationId("oidc")
                    .clientId(p.clientId())
                    .clientAuthenticationMethod(secret ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC : ClientAuthenticationMethod.NONE)
                    .scope("openid", "profile", "email")
                    .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()); // 6.5 才有
                if (secret) b.clientSecret(p.clientSecret());
                r = b.build(); // redirectUri 預設 {baseUrl}/{action}/oauth2/code/{registrationId}
                cached.set(r);
            }
            return r;
        };
    }

    /** spec 2.3：包在 OidcUserService 外面，對應到本機帳號；name attribute 是本機帳號名稱，Principal.getName() 才跟帳密登入一致。 */
    static OAuth2UserService<OidcUserRequest, OidcUser> localAccounts(AccountService accounts) {
        OidcUserService delegate = new OidcUserService();
        return req -> {
            OidcUser idp = delegate.loadUser(req); // 驗 id_token；沒有 userinfo_endpoint 就不打 userinfo
            String username = StringUtils.hasText(idp.getPreferredUsername()) ? idp.getPreferredUsername()
                : Boolean.TRUE.equals(idp.getEmailVerified()) ? idp.getEmail() : null;
            Account a = accounts.forOidc(idp.getIssuer().toString(), idp.getSubject(), username);
            OidcIdToken t = idp.getIdToken();
            Map<String, Object> claims = new HashMap<>(t.getClaims());
            claims.put("jev_username", a.username()); // 放最後：DefaultOidcUser 讓 id_token 蓋過 userinfo，IdP 帶同名 claim 也不能換掉本機帳號
            return new DefaultOidcUser(AuthorityUtils.createAuthorityList(a.admin() ? "ROLE_ADMIN" : "ROLE_USER"),
                new OidcIdToken(t.getTokenValue(), t.getIssuedAt(), t.getExpiresAt(), claims), "jev_username");
        };
    }

    /** spec 2.2：安全設定裡的錯誤都從這裡寫，charset 一定要是 UTF-8，中文才不會變成問號。 */
    static void writeError(HttpServletResponse res, ObjectMapper json, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }

    /** Spring Security 6.5 文件的 SPA 作法，原樣照抄（7.0 才有 csrf.spa()）。 */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
            csrfToken.get(); // 讓 deferred token 載入，每個回應都會寫 XSRF-TOKEN cookie（沒登入的 401 也有）
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
