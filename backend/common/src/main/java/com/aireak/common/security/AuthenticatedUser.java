package com.aireak.common.security;

/**
 * Identity extracted from a validated access token's claims (sub, email, role, tokenType) by
 * {@link com.aireak.common.web.filter.JwtAuthenticationFilter}. {@code token} is the original
 * raw bearer token — services that make their own outbound calls as part of handling this
 * request (e.g. booking-service's saga steps) forward it downstream so the callee can validate
 * the same end-user identity independently, instead of the caller re-asserting it unverified.
 *
 * <p>{@code role} is the account's authorization role ("USER"/"ADMIN") as issued by
 * identity-service — null for tokens minted before the claim existed. Services without Spring
 * Security wired in (e.g. match-catalog-service) compare it directly instead of relying on
 * {@code @PreAuthorize}.
 *
 * <p>{@code tokenType} distinguishes a real end-user token from one minted by
 * {@code InternalServiceTokenProvider} for service-to-service calls that have no authenticated
 * end-user request to forward (Kafka listener/scheduled job threads) — null for every token
 * issued before this claim existed, which must still be treated as a genuine user token.
 */
public record AuthenticatedUser(String userId, String email, String role, String token, String tokenType) {

    public static final String TOKEN_TYPE_INTERNAL_SERVICE = "internal-service";

    public AuthenticatedUser(String userId, String email, String role, String token) {
        this(userId, email, role, token, null);
    }

    public boolean isInternalService() {
        return TOKEN_TYPE_INTERNAL_SERVICE.equals(tokenType);
    }
}
