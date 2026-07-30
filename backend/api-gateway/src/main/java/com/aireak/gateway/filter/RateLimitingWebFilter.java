package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-layer rate limiting, enforced after JWT validation (see {@link Ordered#getOrder()})
 * so a trusted {@code X-User-Id} header — set only by {@link JwtAuthenticationWebFilter} on
 * routes it actually authenticated — is available for user-keyed policies.
 *
 * <p>Path is matched to a {@link RateLimitPolicy}; each policy is backed by its own
 * {@link RedisRateLimiter} (the same Redis-backed token-bucket algorithm Spring Cloud Gateway's
 * stock {@code RequestRateLimiter} filter uses). This filter does not use that stock filter
 * directly because it needs a custom 429 JSON body with a computed {@code Retry-After}, a
 * fail-open metric/log on Redis errors, and per-path-group policy selection finer than a single
 * gateway route.
 *
 * <p><b>Fail-open on Redis unavailability:</b> {@link RedisRateLimiter#isAllowed} already
 * swallows Redis/Lua errors internally and returns {@code allowed=true} — the only observable
 * trace is the remaining-tokens header being {@code -1} (see its source). This filter treats
 * that as the fail-open signal: log a WARN and increment {@code gateway.ratelimit.redis.unavailable}.
 *
 * <p><b>Key trust:</b> only {@code ip:{clientIp}} (the raw socket remote address — this project
 * has no reverse proxy / trusted-proxy configuration anywhere, so {@code X-Forwarded-For} is
 * deliberately never read) or {@code user:{userId}} sourced from the gateway-verified JWT
 * {@code sub} claim (never the client-supplied {@code X-User-Id} header) are used as keys.
 *
 * <p>Migrated from JJWT 0.12.x to Nimbus JOSE+JWT. jjwt-jackson depended on
 * com.fasterxml.jackson (Jackson 2) which conflicts with Spring Boot 4.1's Jackson 3
 * auto-configuration. Nimbus has no Jackson dependency.
 */
@Component
@Order(-40)
public class RateLimitingWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingWebFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<String> HEALTH_PATHS = List.of("/actuator/health", "/actuator/info");

    /** Path -> policy, evaluated in order; first match wins. Order matters: most specific first. */
    private static final Map<String, RateLimitPolicy> PATH_POLICIES = new LinkedHashMap<>();
    static {
        PATH_POLICIES.put("/api/v1/auth/login", RateLimitPolicy.LOGIN);
        PATH_POLICIES.put("/api/v1/auth/register", RateLimitPolicy.REGISTER);
        PATH_POLICIES.put("/api/v1/auth/refresh", RateLimitPolicy.REFRESH_TOKEN);
        // Same trust model as refresh: cookie-authenticated, public path, best-effort Bearer
        // verification via tryVerifySubjectFromBearer() — reuses REFRESH_TOKEN rather than
        // falling back to DEFAULT_ANONYMOUS.
        PATH_POLICIES.put("/api/v1/auth/logout", RateLimitPolicy.REFRESH_TOKEN);
        PATH_POLICIES.put("/api/v1/matches/**", RateLimitPolicy.READ_ANONYMOUS);
        PATH_POLICIES.put("/api/v1/inventory/**", RateLimitPolicy.READ_AUTHENTICATED);
        PATH_POLICIES.put("/api/v1/bookings/**", RateLimitPolicy.BOOKING);
        PATH_POLICIES.put("/api/v1/payments/**", RateLimitPolicy.PAYMENT);
        PATH_POLICIES.put("/api/v1/notifications/**", RateLimitPolicy.NOTIFICATION);
    }

    private final Map<RateLimitPolicy, RedisRateLimiter> limiters;
    private final JwtValidationProperties jwtProperties;
    private final MeterRegistry meterRegistry;
    private final JWSVerifier verifier;

    public RateLimitingWebFilter(Map<RateLimitPolicy, RedisRateLimiter> limiters,
                                  JwtValidationProperties jwtProperties,
                                  MeterRegistry meterRegistry) {
        this.limiters = limiters;
        this.jwtProperties = jwtProperties;
        this.meterRegistry = meterRegistry;
        try {
            this.verifier = new MACVerifier(
                    jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Failed to initialise JWT verifier in RateLimitingWebFilter (secret too short?)", e);
        }
    }

    @Override
    public int getOrder() {
        return -40;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (matchesAny(HEALTH_PATHS, path)) {
            return chain.filter(exchange);
        }

        boolean isPublicPath = jwtProperties.publicPaths().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
        // X-User-Id is only trustworthy on routes JwtAuthenticationWebFilter actually validated
        // (non-public paths); it is stripped-and-never-set on public paths.
        String trustedUserId = isPublicPath ? null
                : exchange.getRequest().getHeaders().getFirst("X-User-Id");

        RateLimitPolicy explicitPolicy = resolveExplicitPolicy(path);
        RateLimitPolicy policy = explicitPolicy != null ? explicitPolicy
                : (trustedUserId != null ? RateLimitPolicy.DEFAULT_AUTHENTICATED : RateLimitPolicy.DEFAULT_ANONYMOUS);

        String key = resolveKey(policy, exchange, trustedUserId);
        RedisRateLimiter limiter = limiters.get(policy);

        return limiter.isAllowed(policy.name(), key).flatMap(response -> {
            String remaining = response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER);
            if ("-1".equals(remaining)) {
                log.warn("Redis unavailable for rate limiter (policy={}, key={}); failing open", policy, key);
                meterRegistry.counter("gateway.ratelimit.redis.unavailable", "policy", policy.name()).increment();
            }

            if (response.isAllowed()) {
                response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().add(name, value));
                return chain.filter(exchange);
            }

            meterRegistry.counter("gateway.ratelimit.exceeded",
                    "policy", policy.name(), "keyType", policy.keyStrategy().name()).increment();
            return tooManyRequests(exchange, policy);
        });
    }

    private static RateLimitPolicy resolveExplicitPolicy(String path) {
        for (Map.Entry<String, RateLimitPolicy> entry : PATH_POLICIES.entrySet()) {
            if (PATH_MATCHER.match(entry.getKey(), path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String resolveKey(RateLimitPolicy policy, ServerWebExchange exchange, String trustedUserId) {
        return switch (policy.keyStrategy()) {
            case IP -> "ip:" + clientIp(exchange);
            case USER -> "user:" + trustedUserId;
            case USER_OR_IP -> {
                String verifiedUserId = tryVerifySubjectFromBearer(exchange);
                yield verifiedUserId != null ? "user:" + verifiedUserId : "ip:" + clientIp(exchange);
            }
        };
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    /**
     * Best-effort identity check for the refresh-token endpoint, which is a public path (the
     * refresh flow is cookie-authenticated, not Bearer-authenticated) so {@code X-User-Id} is
     * never trustworthy there. If the caller happens to still attach a signature-valid Bearer
     * token — expired or not — its {@code sub} claim is used; otherwise falls back to IP. This
     * never trusts an unverified claim: signature/issuer/audience are still checked.
     */
    private String tryVerifySubjectFromBearer(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            // Verify signature — intentionally allow expired tokens (for the refresh-path
            // fallback) by NOT checking expiration here. The expiry check is JwtAuthenticationWebFilter's
            // responsibility for protected paths; for public paths (logout/refresh) we only need the sub.
            if (!signedJWT.verify(verifier)) {
                return null;
            }
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (!jwtProperties.issuer().equals(claims.getIssuer())) {
                return null;
            }
            if (!claims.getAudience().contains(jwtProperties.audience())) {
                return null;
            }
            return claims.getSubject();
        } catch (ParseException | JOSEException ex) {
            return null;
        }
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange, RateLimitPolicy policy) {
        long retryAfterSeconds = policy.retryAfterSeconds();
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":" + retryAfterSeconds + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
