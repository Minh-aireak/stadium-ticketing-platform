package com.aireak.gateway.util;

import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches a request against the configured {@code jwt.public-paths} patterns.
 *
 * <p>Patterns are Ant paths, optionally prefixed with an HTTP method — {@code "GET:/api/v1/matches/*"}
 * is public for GET only, while a bare {@code "/actuator/health"} is public for every method. Same
 * syntax the services' own {@code com.aireak.common.web.filter.JwtAuthenticationFilter} accepts for
 * {@code jwt.excluded-paths}, so a path can be scoped identically at the edge and at the service.
 *
 * <p>Lives here rather than inside one filter because three of them ask the same question —
 * {@code JwtAuthenticationWebFilter} (skip token validation), {@code PreAuthRateLimitingWebFilter}
 * (skip the per-IP flood guard) and {@code RateLimitingWebFilter} (choose an anonymous policy).
 * When each kept its own copy they could disagree about what "public" means, and a filter reading
 * a method-scoped pattern with an unscoped matcher silently treats an admin-only mutation as
 * public.
 */
public final class PublicPathMatcher {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private PublicPathMatcher() {
    }

    /** True if {@code method} + {@code path} matches any of {@code publicPaths}. */
    public static boolean isPublic(List<String> publicPaths, String method, String path) {
        if (publicPaths == null || publicPaths.isEmpty()) {
            return false;
        }
        return publicPaths.stream().anyMatch(pattern -> matches(pattern, method, path));
    }

    private static boolean matches(String pattern, String method, String path) {
        int colon = pattern.indexOf(':');
        if (colon > 0 && HTTP_METHODS.contains(pattern.substring(0, colon).toUpperCase(Locale.ROOT))) {
            return pattern.substring(0, colon).equalsIgnoreCase(method)
                    && PATH_MATCHER.match(pattern.substring(colon + 1), path);
        }
        return PATH_MATCHER.match(pattern, path);
    }
}
