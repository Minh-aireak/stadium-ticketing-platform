package com.aireak.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.time.Duration;

/**
 * Spring Security configuration for identity-service.
 *
 * <p>Stateless — no server-side HTTP session. Auth endpoints are permitAll because they are
 * the entry points that establish identity in the first place; /refresh and /logout in
 * particular are authenticated by the HttpOnly refresh cookie, not by a Bearer token (the
 * access token may already be expired when either is called).
 *
 * <p>CSRF: refresh and logout are cookie-authenticated state-changing requests, so they need
 * CSRF protection (a JWT-in-header request would not, since an attacker's page can't read/set
 * an Authorization header cross-site — but the browser attaches cookies automatically).
 * Login/register are exempt: no session cookie exists yet to be ridden.
 *
 * <p>CORS is deliberately NOT configured here — the gateway is the single source of truth for
 * allowed origins (see api-gateway's CorsConfig) and this service is never reached directly by
 * a browser. A second CORS layer here would add its own Access-Control-Allow-Origin header on
 * top of the gateway's, and Spring Cloud Gateway forwards downstream response headers as-is —
 * the client ends up with a duplicated header, which browsers reject outright, masking every
 * response (including legitimate validation errors) as an opaque network failure.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, RefreshTokenProperties refreshTokenProperties) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository(refreshTokenProperties))
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/v1/auth/register", "/api/v1/auth/login")
            )
            // CsrfFilter only defers token resolution to a request attribute; without forcing
            // it here, CookieCsrfTokenRepository never actually writes the XSRF-TOKEN cookie.
            // Must be BEFORE CsrfFilter: if CsrfFilter rejects the request (403) it does not
            // call doFilter(), so a filter placed after it would never materialise the token.
            .addFilterBefore(new CsrfCookieFilter(), CsrfFilter.class)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/verify-email",
                    "/actuator/health",
                    "/actuator/info",
                    // Prometheus has no user JWT to present — public here for the same reason
                    // health/info are (see docker-compose.yaml's prometheus service).
                    "/actuator/prometheus"
                ).permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // strength 12 for adequate security
    }

    // Spring's default CookieCsrfTokenRepository writes XSRF-TOKEN as a session cookie (cleared
    // when the browser closes), while the refresh_token cookie survives across browser restarts
    // (see AuthController#refreshCookie, maxAge = time until expiresAt). Without matching the two
    // lifetimes, a returning user with a still-valid refresh token loses the XSRF-TOKEN cookie
    // first and gets a spurious 403 (session "expired") on the very next silent refresh.
    private static CookieCsrfTokenRepository csrfTokenRepository(RefreshTokenProperties refreshTokenProperties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(builder ->
            builder.maxAge(Duration.ofSeconds(refreshTokenProperties.absoluteTtlSeconds())));
        return repository;
    }
}
