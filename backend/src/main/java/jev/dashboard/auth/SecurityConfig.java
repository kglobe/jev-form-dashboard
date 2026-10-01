package jev.dashboard.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, AccountRepository accounts)
            throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                .requestMatchers("/api/auth/options").permitAll() // spec 4.1：登入頁要先知道有沒有單一登入
                .requestMatchers("/api/admin/**").hasRole("ADMIN") // spec 4.2
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()) // 之後放前端靜態檔
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .formLogin(f -> f
                .loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .failureHandler((req, res, e) -> writeError(res, json, 401,
                    e instanceof DisabledException ? "帳號已停用，請聯絡管理員" : "帳號或密碼錯誤")))
            .logout(l -> l
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .sessionManagement(s -> s.sessionFixation(f -> f.newSession())) // 工作階段的建立時間就是登入時間（spec 2.1）
            .requestCache(r -> r.requestCache(new NullRequestCache()))
            .exceptionHandling(e -> e // 設了 entry point，Spring 就不會自己產生 /login 頁
                .authenticationEntryPoint((req, res, ex) -> writeError(res, json, 401, "請先登入"))
                .accessDeniedHandler((req, res, ex) -> writeError(res, json, 403,
                    ex instanceof CsrfException ? "頁面已過期，請重新整理" : "沒有權限")))
            .addFilterAfter(new AccountCheckFilter(accounts, json), AnonymousAuthenticationFilter.class); // spec 2.1
        return http.build();
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
