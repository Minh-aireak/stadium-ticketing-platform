package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
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
import java.util.Map;

/**
 * Per-IP flood guard that runs BEFORE {@link JwtAuthenticationWebFilter} (see {@link #getOrder()}),
 * so it applies to every request on a PROTECTED (non-public) path — including ones a garbage or
 * expired bearer token would get rejected with 401 by that later filter. Without this,
 * {@link JwtAuthenticationWebFilter} short-circuits on rejection without calling
 * {@code chain.filter()}, so {@link RateLimitingWebFilter} (which runs after it) never sees —
 * and therefore never rate-limits — that traffic.
 *
 * <p>Public paths (login/register/refresh/logout/matches/health) are skipped here: unlike
 * protected paths, {@link JwtAuthenticationWebFilter} never short-circuits them (it always calls
 * {@code chain.filter()}), so {@link RateLimitingWebFilter} already runs for every request on
 * them regardless of any Bearer token. Adding a second Redis round-trip ahead of that would only
 * add latency, not protection — this filter's job is narrowly to close the gap on protected
 * routes.
 *
 * <p>This is a coarse safety net, not a replacement for {@link RateLimitingWebFilter}'s
 * per-route/per-user policies: it uses a single generous {@link RateLimitPolicy#PRE_AUTH_IP}
 * bucket keyed purely by client IP, sized to only trip under actual flooding.
 */
@Component
@Order(-60)
public class PreAuthRateLimitingWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PreAuthRateLimitingWebFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RedisRateLimiter limiter;
    private final JwtValidationProperties jwtProperties;
    private final MeterRegistry meterRegistry;

    public PreAuthRateLimitingWebFilter(Map<RateLimitPolicy, RedisRateLimiter> limiters,
                                         JwtValidationProperties jwtProperties,
                                         MeterRegistry meterRegistry) {
        this.limiter = limiters.get(RateLimitPolicy.PRE_AUTH_IP);
        this.jwtProperties = jwtProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int getOrder() {
        return -60;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String key = "ip:" + clientIp(exchange);
        return limiter.isAllowed(RateLimitPolicy.PRE_AUTH_IP.name(), key).flatMap(response -> {
            String remaining = response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER);
            if ("-1".equals(remaining)) {
                log.warn("Redis unavailable for pre-auth rate limiter (key={}); failing open", key);
                meterRegistry.counter("gateway.ratelimit.redis.unavailable", "policy",
                        RateLimitPolicy.PRE_AUTH_IP.name()).increment();
            }

            if (response.isAllowed()) {
                response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().add(name, value));
                return chain.filter(exchange);
            }

            meterRegistry.counter("gateway.ratelimit.exceeded", "policy",
                    RateLimitPolicy.PRE_AUTH_IP.name(), "keyType", "IP").increment();
            return tooManyRequests(exchange);
        });
    }

    private boolean isPublic(String path) {
        return jwtProperties.publicPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        long retryAfterSeconds = RateLimitPolicy.PRE_AUTH_IP.retryAfterSeconds();
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":" + retryAfterSeconds + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
