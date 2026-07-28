package com.aireak.common.security;

import java.util.Optional;

/**
 * Request-scoped holder for the {@link AuthenticatedUser} extracted by
 * {@link com.aireak.common.web.filter.JwtAuthenticationFilter}, readable from the
 * application layer without threading it through every method signature.
 *
 * <p>Backed by a {@link ThreadLocal}, same pattern as {@code CorrelationIdFilter}'s MDC usage —
 * the filter sets it before {@code chain.doFilter} and clears it in a {@code finally} block, so
 * it never leaks across requests on a pooled request-handling thread.
 */
public final class AuthenticatedUserContext {

    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();

    private AuthenticatedUserContext() {
    }

    public static void set(AuthenticatedUser user) {
        CURRENT.set(user);
    }

    public static Optional<AuthenticatedUser> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
