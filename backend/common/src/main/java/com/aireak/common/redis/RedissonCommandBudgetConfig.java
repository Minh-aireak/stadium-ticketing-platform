package com.aireak.common.redis;

import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounds how long one Redis command may take before the caller gives up.
 *
 * <p>Nothing configured Redisson before this: no client bean, no {@code redisson.*} property.
 * {@code RedissonAutoConfigurationV4#setTimeouts} maps only two Spring properties onto it —
 * {@code spring.data.redis.connect-timeout} and {@code spring.data.redis.timeout} — and every
 * service set the first and none set the second, so every command on the platform ran on
 * Redisson's own defaults: {@code timeout} 3000ms with {@code retryAttempts} 4 and a retry delay
 * of {@code EqualJitterDelay(1s, 2s)}. Against a Redis that accepts connections and then stops
 * answering — hung rather than down, since down fails fast with a connection refusal — a single
 * command could therefore take four response timeouts plus three delays, roughly 13.5 to 18
 * seconds.
 *
 * <p>That is the shape the Elasticsearch connection queue turned out to have: the wait is paid
 * while holding a {@code @Bulkhead} permit. {@code SeatInventoryService#execute} holds a
 * {@code seat-inventory} permit across the whole {@code executeWithLock}, and the comment there
 * promises "the 5s Redisson lock wait" — which is the budget for <em>acquiring</em> the lock, not
 * a bound on the command that does the acquiring. A merely-slow Redis collapses the buy path's
 * throughput to permits-per-18-seconds and holds it there. The REST call sitting in the same
 * position was given a deliberate 3s/5s budget for exactly this reason; the Redis call beside it
 * had none.
 *
 * <p>The defaults below put the worst case at two attempts plus one delay — about 3.5 to 5
 * seconds, in line with the REST budgets rather than four times looser than them. A healthy Redis
 * command is sub-millisecond, so this is not a limit any normal call approaches. Both values are
 * overridable per service: anything sitting in front of the contended buy path can go tighter,
 * and nothing here is load-bearing enough to need looser.
 *
 * <p>{@code connect-timeout} is deliberately left where it is. It is the budget for establishing
 * a connection, not for a command on an established one, and the 30s the services set buys
 * tolerance for a Redis that is still starting up under {@code docker compose up}. It plays no
 * part in the failure above, where the connection is already open.
 *
 * <p>Lives in {@code common} so there is one copy rather than five. Redisson is
 * {@code optional=true} there and optional dependencies are not transitive, so only a service
 * that declares it itself gets it — notification-service component-scans {@code com.aireak.common}
 * with no Redisson on its classpath, and the class guard below leaves it out. The guard is written
 * by name deliberately, the same way {@code SchedulerLockConfig}'s is: Spring evaluates it from
 * bytecode metadata, so this class is never loaded and its bean signature never resolved where
 * Redisson is absent. api-gateway is not covered from here at all — it neither depends on
 * {@code common} nor component-scans it, so it carries its own copy.
 */
@Configuration
@ConditionalOnClass(name = "org.redisson.spring.starter.RedissonAutoConfigurationCustomizer")
public class RedissonCommandBudgetConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonCommandBudgetConfig.class);

    @Bean
    public RedissonAutoConfigurationCustomizer redissonCommandBudgetCustomizer(
            @Value("${redis.command.timeout-ms:1500}") int timeoutMs,
            @Value("${redis.command.retry-attempts:2}") int retryAttempts) {
        // Refused at startup rather than at the first command: a non-positive timeout or a zero
        // retry count would look like a working budget and behave like no client at all.
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("redis.command.timeout-ms must be positive: " + timeoutMs);
        }
        if (retryAttempts < 1) {
            throw new IllegalArgumentException("redis.command.retry-attempts must be at least 1: " + retryAttempts);
        }
        return config -> apply(config, timeoutMs, retryAttempts);
    }

    /**
     * Reaches the {@link BaseConfig} the starter already built, rather than calling
     * {@code useSingleServer()} unconditionally — that would replace a sentinel or cluster setup
     * with an empty single-server one and point the client at nothing. Each {@code useXxx} below
     * returns the config the starter populated, because the mode is already chosen by the time
     * customizers run.
     */
    private static void apply(Config config, int timeoutMs, int retryAttempts) {
        BaseConfig<?> serverConfig;
        if (config.isSingleConfig()) {
            serverConfig = config.useSingleServer();
        } else if (config.isSentinelConfig()) {
            serverConfig = config.useSentinelServers();
        } else if (config.isClusterConfig()) {
            serverConfig = config.useClusterServers();
        } else {
            // Master-slave and replicated are the two modes the starter never builds. Loud rather
            // than silent: the whole point of this bean is that an unbounded command budget is
            // invisible until a Redis goes slow, and skipping it quietly would restore that.
            log.warn("Redis command budget not applied: unrecognised Redisson topology, "
                    + "commands keep the library defaults (3000ms x 4 attempts)");
            return;
        }
        serverConfig.setTimeout(timeoutMs).setRetryAttempts(retryAttempts);
        log.info("Redis command budget: timeout={}ms, retryAttempts={}", timeoutMs, retryAttempts);
    }
}
