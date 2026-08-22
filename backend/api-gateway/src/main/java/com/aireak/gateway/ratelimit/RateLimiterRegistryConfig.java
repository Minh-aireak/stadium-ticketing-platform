package com.aireak.gateway.ratelimit;

import com.aireak.gateway.config.PreAuthRateLimitProperties;
import com.google.common.base.Ticker;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Builds one {@link RedisRateLimiter} per {@link RateLimitPolicy}, each with its own fixed
 * token-bucket config (no per-route YAML binding — every gateway route is matched to a policy
 * by path in {@link RateLimitingWebFilter}).
 *
 * <p>{@link RedisRateLimiter} implements {@code ApplicationContextAware}; each instance created
 * here is NOT itself registered as a bean (avoiding {@code @ConditionalOnMissingBean} ambiguity
 * with Spring Cloud Gateway's own auto-configured default {@code RedisRateLimiter} bean), so
 * {@link RedisRateLimiter#setApplicationContext} is called manually to wire up the shared
 * {@code ReactiveStringRedisTemplate} and Lua script beans that
 * {@code GatewayRedisAutoConfiguration} provides once Redisson is on the classpath.
 */
@Configuration
public class RateLimiterRegistryConfig {

    @Bean
    public Map<RateLimitPolicy, RedisRateLimiter> rateLimiters(ApplicationContext applicationContext) {
        Map<RateLimitPolicy, RedisRateLimiter> limiters = new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            RedisRateLimiter limiter = new RedisRateLimiter(
                    policy.replenishRate(), policy.burstCapacity(), policy.requestedTokens());
            limiter.setApplicationContext(applicationContext);
            limiters.put(policy, limiter);
        }
        return limiters;
    }

    /**
     * The pre-auth flood guard is the one policy NOT served from the map above: it runs on every
     * inbound request, so it is enforced in-process instead of costing ~4 Redis commands each time
     * (see {@link LocalIpTokenBucketLimiter} for what that approximation gives up). Its entry in
     * the map is left in place but unused — the map is built from the enum, and special-casing one
     * value there would hide the exception rather than document it.
     */
    @Bean
    public LocalIpTokenBucketLimiter preAuthIpLimiter(PreAuthRateLimitProperties properties) {
        return new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, properties.instanceCount(),
                properties.maxTrackedIps(), Ticker.systemTicker());
    }
}
