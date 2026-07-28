package com.aireak.common.security;

/**
 * Identity extracted from a validated access token's claims (sub, email) by
 * {@link com.aireak.common.web.filter.JwtAuthenticationFilter}. {@code token} is the original
 * raw bearer token — services that make their own outbound calls as part of handling this
 * request (e.g. booking-service's saga steps) forward it downstream so the callee can validate
 * the same end-user identity independently, instead of the caller re-asserting it unverified.
 */
public record AuthenticatedUser(String userId, String email, String token) {
}
