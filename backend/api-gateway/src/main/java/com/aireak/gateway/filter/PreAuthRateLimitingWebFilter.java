package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.LocalIpTokenBucketLimiter;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

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
 * them regardless of any Bearer token. Adding a second round-trip ahead of that would only add
 * latency, not protection — this filter's job is narrowly to close the gap on protected routes.
 *
 * <p>This is a coarse safety net, not a replacement for {@link RateLimitingWebFilter}'s
 * per-route/per-user policies: it uses a single generous {@link RateLimitPolicy#PRE_AUTH_IP}
 * bucket keyed purely by client IP, sized to only trip under actual flooding.
 *
 * <p><b>Why the bucket is local, not in Redis.</b> This filter used to spend ~4 Redis commands on
 * every inbound request — the single largest per-request Redis cost on the browse path, paid
 * before authentication had even happened. The bucket now lives in this JVM
 * ({@link LocalIpTokenBucketLimiter}), which costs the limit its cluster-wide exactness: the
 * allowance is divided across instances and enforced approximately, so an IP spreading its
 * requests unevenly across instances can drift above the nominal rate. For a flood guard
 * explicitly sized "well above any single route's allowance" (see {@link RateLimitPolicy}) that
 * drift is immaterial — it still catches an IP hammering the gateway, which is the only thing it
 * was ever meant to catch. {@link RateLimitingWebFilter}'s per-user limits stay on Redis, where
 * cross-instance exactness genuinely matters. Nothing about the rejection changes: same 429, same
 * {@code Retry-After}, same body, same metric.
 */
@Component
@Order(-60)
public class PreAuthRateLimitingWebFilter implements WebFilter, Ordered {

    private final LocalIpTokenBucketLimiter limiter;
    private final JwtValidationProperties jwtProperties;
    private final com.aireak.gateway.config.GatewayProperties gatewayProperties;
    private final MeterRegistry meterRegistry;

    public PreAuthRateLimitingWebFilter(LocalIpTokenBucketLimiter preAuthIpLimiter,
                                         JwtValidationProperties jwtProperties,
                                         com.aireak.gateway.config.GatewayProperties gatewayProperties,
                                         MeterRegistry meterRegistry) {
        this.limiter = preAuthIpLimiter;
        this.jwtProperties = jwtProperties;
        this.gatewayProperties = gatewayProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int getOrder() {
        return -60;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(exchange.getRequest().getMethod().name(), path)) {
            return chain.filter(exchange);
        }

        String key = "ip:" + clientIp(exchange);
        LocalIpTokenBucketLimiter.Decision decision = limiter.tryConsume(key);

        if (decision.allowed()) {
            addRateLimitHeaders(exchange, decision);
            return chain.filter(exchange);
        }

        meterRegistry.counter("gateway.ratelimit.exceeded", "policy",
                RateLimitPolicy.PRE_AUTH_IP.name(), "keyType", "IP").increment();
        return tooManyRequests(exchange);
    }

    // Must agree exactly with JwtAuthenticationWebFilter about what "public" means — hence the
    // shared matcher. A pattern this filter read as public but that one did not would leave a
    // protected route unmetered by the flood guard while still requiring a token.
    private boolean isPublic(String method, String path) {
        return com.aireak.gateway.util.PublicPathMatcher.isPublic(jwtProperties.publicPaths(), method, path);
    }

    private String clientIp(ServerWebExchange exchange) {
        return com.aireak.gateway.util.TrustedProxyUtils.extractClientIp(exchange, gatewayProperties.trustedProxies());
    }

    /**
     * The same four headers {@link RedisRateLimiter} used to attach on an allowed request — named
     * from its own constants so they cannot drift apart — so a client sees no difference now that
     * the bucket is local.
     */
    private void addRateLimitHeaders(ServerWebExchange exchange, LocalIpTokenBucketLimiter.Decision decision) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.add(RedisRateLimiter.REMAINING_HEADER, String.valueOf(decision.remainingTokens()));
        headers.add(RedisRateLimiter.REPLENISH_RATE_HEADER, String.valueOf(limiter.replenishRate()));
        headers.add(RedisRateLimiter.BURST_CAPACITY_HEADER, String.valueOf(limiter.burstCapacity()));
        headers.add(RedisRateLimiter.REQUESTED_TOKENS_HEADER, String.valueOf(limiter.requestedTokens()));
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
