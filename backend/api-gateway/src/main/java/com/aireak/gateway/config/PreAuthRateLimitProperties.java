package com.aireak.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the pre-auth flood guard, which is enforced in-process rather than in Redis (see
 * {@code LocalIpTokenBucketLimiter}).
 *
 * @param instanceCount how many gateway instances are running behind the load balancer. Each one
 *                      keeps its own bucket, so the cluster-wide allowance is only respected if
 *                      every instance enforces its share — hence dividing by this rather than
 *                      hard-coding today's single instance.
 * @param maxTrackedIps hard cap on how many IPs are tracked at once, bounding heap against a
 *                      scan from many source addresses.
 */
@ConfigurationProperties(prefix = "gateway.pre-auth-rate-limit")
public record PreAuthRateLimitProperties(Integer instanceCount, Long maxTrackedIps) {

    public PreAuthRateLimitProperties {
        if (instanceCount == null || instanceCount < 1) {
            instanceCount = 1;
        }
        if (maxTrackedIps == null || maxTrackedIps < 1) {
            maxTrackedIps = 50_000L;
        }
    }
}
