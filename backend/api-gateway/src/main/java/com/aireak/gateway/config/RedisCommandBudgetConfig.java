package com.aireak.gateway.config;

import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounds how long one Redis command may take before the caller gives up — the gateway's copy of
 * {@code com.aireak.common.redis.RedissonCommandBudgetConfig}, which carries the full reasoning.
 *
 * <p>A copy rather than the shared bean because this module depends on neither {@code common} nor
 * its component scan: {@link com.aireak.gateway.ApiGatewayApplication} is a bare
 * {@code @SpringBootApplication}, so it scans {@code com.aireak.gateway} and nothing else. Adding
 * a {@code common} dependency to pick up one bean would pull an MVC {@code @RestControllerAdvice}
 * and a pile of JPA wiring into a WebFlux service that wants none of it.
 *
 * <p>What it guards here is different from the services. {@code RedisRateLimiter} is reactive, so
 * a slow Redis parks no thread — but every request still waits on it, and on the library defaults
 * that wait is up to four response timeouts plus three retry delays (13.5-18s) before
 * {@code RateLimitingWebFilter} can fail open. A limiter that eventually lets the request through
 * after eighteen seconds is not failing open; it is a queue in front of the front door.
 *
 * <p>Note the pool settings that used to sit in {@code application.yaml} were never involved:
 * {@code redisson-spring-boot-starter} excludes {@code lettuce-core}, and {@code commons-pool2} is
 * not on this classpath either, so {@code GatewayRedisAutoConfiguration}'s
 * {@code ReactiveStringRedisTemplate} runs on Redisson's connection factory like everything else.
 */
@Slf4j
@Configuration
public class RedisCommandBudgetConfig {

    @Bean
    public RedissonAutoConfigurationCustomizer redissonCommandBudgetCustomizer(
            @Value("${redis.command.timeout-ms:1500}") int timeoutMs,
            @Value("${redis.command.retry-attempts:2}") int retryAttempts) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("redis.command.timeout-ms must be positive: " + timeoutMs);
        }
        if (retryAttempts < 1) {
            throw new IllegalArgumentException("redis.command.retry-attempts must be at least 1: " + retryAttempts);
        }
        return config -> apply(config, timeoutMs, retryAttempts);
    }

    /** See the shared copy: reaches the config the starter built instead of replacing it. */
    private static void apply(Config config, int timeoutMs, int retryAttempts) {
        BaseConfig<?> serverConfig;
        if (config.isSingleConfig()) {
            serverConfig = config.useSingleServer();
        } else if (config.isSentinelConfig()) {
            serverConfig = config.useSentinelServers();
        } else if (config.isClusterConfig()) {
            serverConfig = config.useClusterServers();
        } else {
            log.warn("Redis command budget not applied: unrecognised Redisson topology, "
                    + "commands keep the library defaults (3000ms x 4 attempts)");
            return;
        }
        serverConfig.setTimeout(timeoutMs).setRetryAttempts(retryAttempts);
        log.info("Redis command budget: timeout={}ms, retryAttempts={}", timeoutMs, retryAttempts);
    }
}
