package com.aireak.gateway.ratelimit;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * In-memory token bucket keyed by client IP, holding the same algorithm Spring Cloud Gateway's
 * {@code RedisRateLimiter} Lua script runs — tokens accrue at {@code replenishRate}/s up to
 * {@code burstCapacity}, a request costs {@code requestedTokens} — but entirely inside this JVM.
 *
 * <p><b>The trade-off.</b> The limit stops being cluster-wide and becomes an approximation
 * enforced per instance, so the effective allowance is divided by {@code instanceCount} (see
 * {@link #LocalIpTokenBucketLimiter}). That is a fair deal only for a coarse flood guard like
 * {@link RateLimitPolicy#PRE_AUTH_IP}, whose whole job is catching an IP that is hammering the
 * gateway, not metering anyone precisely; what it buys is the ~4 Redis commands that used to run
 * on every single inbound request before authentication even started. A per-user allowance is a
 * different question and must stay in Redis — see {@code RateLimitingWebFilter}.
 *
 * <p><b>Bounded on purpose.</b> A map keyed by attacker-chosen IPs is a memory leak waiting to
 * happen, so entries are capped by count and expire once idle long enough to have refilled
 * completely (at which point the bucket carries no information a fresh one wouldn't). Guava's
 * size eviction is LRU-ish, so a scan from very many IPs can evict a bucket early — an attacker
 * who can cycle that many source addresses has already defeated any per-IP limiter, Redis-backed
 * or not.
 */
public class LocalIpTokenBucketLimiter {

    /** Outcome of one {@link #tryConsume} call; {@code remainingTokens} feeds the response headers. */
    public record Decision(boolean allowed, long remainingTokens) {}

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final Cache<String, Bucket> buckets;
    private final Ticker ticker;
    private final int replenishRate;
    private final long burstCapacity;
    private final int requestedTokens;
    private final double refillTokensPerNano;

    /**
     * @param policy        the policy whose numbers this bucket enforces
     * @param instanceCount how many gateway instances share the traffic, so the cluster-wide
     *                      allowance can be split across them; 1 or less means "not replicated"
     * @param maxTrackedIps hard cap on tracked IPs, bounding worst-case heap
     * @param ticker        time source — injectable so tests advance time instead of sleeping
     */
    public LocalIpTokenBucketLimiter(RateLimitPolicy policy, int instanceCount, long maxTrackedIps, Ticker ticker) {
        int instances = Math.max(1, instanceCount);
        this.requestedTokens = policy.requestedTokens();
        this.replenishRate = Math.max(1, policy.replenishRate() / instances);
        // Never below the cost of a single request, or an instance's share would be too small to
        // admit even one call and every request would 429.
        this.burstCapacity = Math.max(this.requestedTokens, policy.burstCapacity() / instances);
        this.refillTokensPerNano = this.replenishRate / NANOS_PER_SECOND;
        this.ticker = ticker;
        // A bucket idle for the time it takes to refill from empty is indistinguishable from one
        // that has never been seen, so dropping it then costs nothing.
        Duration idleUntilFull = Duration.ofSeconds(
                (long) Math.ceil(this.burstCapacity / (double) this.replenishRate));
        this.buckets = CacheBuilder.newBuilder()
                .maximumSize(maxTrackedIps)
                .expireAfterAccess(idleUntilFull.toNanos(), TimeUnit.NANOSECONDS)
                .ticker(ticker)
                .build();
    }

    /** Charges one request of this policy's cost against {@code key}'s bucket. */
    public Decision tryConsume(String key) {
        long now = ticker.read();
        Bucket bucket;
        try {
            bucket = buckets.get(key, () -> new Bucket(burstCapacity, now));
        } catch (ExecutionException e) {
            // The loader is a plain constructor call and cannot throw a checked exception.
            throw new IllegalStateException("Unreachable: bucket creation failed for key " + key, e);
        }
        return bucket.tryConsume(requestedTokens, now, burstCapacity, refillTokensPerNano);
    }

    public int replenishRate() {
        return replenishRate;
    }

    public long burstCapacity() {
        return burstCapacity;
    }

    public int requestedTokens() {
        return requestedTokens;
    }

    /**
     * Number of tracked IPs — for tests/metrics, not exact under concurrent writes. Guava evicts
     * lazily, as a side effect of later reads and writes, so pending eviction work is forced first
     * rather than reporting buckets that are already expired.
     */
    public long trackedKeys() {
        buckets.cleanUp();
        return buckets.size();
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;

        Bucket(double initialTokens, long nowNanos) {
            this.tokens = initialTokens;
            this.lastRefillNanos = nowNanos;
        }

        synchronized Decision tryConsume(int cost, long nowNanos, double capacity, double refillPerNano) {
            long elapsedNanos = nowNanos - lastRefillNanos;
            if (elapsedNanos > 0) {
                tokens = Math.min(capacity, tokens + elapsedNanos * refillPerNano);
                lastRefillNanos = nowNanos;
            }
            if (tokens < cost) {
                return new Decision(false, (long) tokens);
            }
            tokens -= cost;
            return new Decision(true, (long) tokens);
        }
    }
}
