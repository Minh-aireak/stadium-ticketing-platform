package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.security.JwtAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    private final JwtAuthProperties properties = new JwtAuthProperties(
            SECRET, null, null, ISSUER, AUDIENCE, List.of("/actuator/health", "/actuator/info"));

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);

    @AfterEach
    void clearContext() {
        // Belt-and-braces: a failed assertion mid-test must never leak into the next test's thread.
        AuthenticatedUserContext.clear();
    }

    @Test
    void rejectsProtectedPathWithoutBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsNonBearerAuthorizationScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMalformedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer not-a-jwt-at-all");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTokenSignedWithAWrongSecret() throws Exception {
        String wrongSecret = "a-completely-different-secret-key-that-is-long-enough";
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), wrongSecret);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer("some-other-issuer")
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of("some-other-audience"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String expired = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .build(), SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + expired);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void validTokenPopulatesAuthenticatedUserContextForTheDurationOfTheChainThenClearsIt() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String token = validToken(accountId, "user@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Optional<AuthenticatedUser>> seenDuringChain = new AtomicReference<>();
        boolean[] chainInvoked = {false};
        filter.doFilter(request, response, (req, res) -> {
            chainInvoked[0] = true;
            seenDuringChain.set(AuthenticatedUserContext.get());
        });

        assertThat(chainInvoked[0]).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(seenDuringChain.get()).contains(new AuthenticatedUser(accountId, "user@example.com", null, token));
        // Must not leak into whatever request the pooled thread handles next.
        assertThat(AuthenticatedUserContext.get()).isEmpty();
    }

    @Test
    void validTokenWithRoleClaimPopulatesRole() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("email", "admin@example.com")
                .claim("role", "ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Optional<AuthenticatedUser>> seenDuringChain = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seenDuringChain.set(AuthenticatedUserContext.get()));

        assertThat(seenDuringChain.get()).contains(
                new AuthenticatedUser(accountId, "admin@example.com", "ADMIN", token));
    }

    @Test
    void validTokenWithoutEmailClaimLeavesEmailNull() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String tokenWithoutEmail = buildToken(new JWTClaimsSet.Builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + tokenWithoutEmail);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Optional<AuthenticatedUser>> seenDuringChain = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seenDuringChain.set(AuthenticatedUserContext.get()));

        assertThat(seenDuringChain.get()).contains(new AuthenticatedUser(accountId, null, null, tokenWithoutEmail));
    }

    @Test
    void excludedPathSkipsValidationEntirely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainInvoked = {false};
        filter.doFilter(request, response, (req, res) -> chainInvoked[0] = true);

        assertThat(chainInvoked[0]).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    void methodScopedExclusionSkipsValidationOnlyForThatMethod() throws Exception {
        JwtAuthProperties scoped = new JwtAuthProperties(
                SECRET, null, null, ISSUER, AUDIENCE, List.of("GET:/api/v1/matches"));
        JwtAuthenticationFilter scopedFilter = new JwtAuthenticationFilter(scoped);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/matches");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainInvoked = {false};

        scopedFilter.doFilter(request, response, (req, res) -> chainInvoked[0] = true);

        assertThat(chainInvoked[0]).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    void methodScopedExclusionStillProtectsOtherMethodsOnTheSamePath() throws Exception {
        JwtAuthProperties scoped = new JwtAuthProperties(
                SECRET, null, null, ISSUER, AUDIENCE, List.of("GET:/api/v1/matches"));
        JwtAuthenticationFilter scopedFilter = new JwtAuthenticationFilter(scoped);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/matches");
        MockHttpServletResponse response = new MockHttpServletResponse();

        scopedFilter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String validToken(String subject, String email) {
        return buildToken(new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("email", email)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
    }

    private static String buildToken(JWTClaimsSet claims, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }

    @Test
    void acceptsTokenSignedWithPreviousSecretDuringRotation() throws Exception {
        String previousSecret = "old-secret-key-at-least-32-bytes-long-for-hs256!!";
        JwtAuthProperties rotationProperties = new JwtAuthProperties(
                SECRET, previousSecret, null, ISSUER, AUDIENCE, List.of());
        JwtAuthenticationFilter rotationFilter = new JwtAuthenticationFilter(rotationProperties);

        String userId = UUID.randomUUID().toString();
        String tokenSignedWithOldSecret = buildToken(new JWTClaimsSet.Builder()
                .subject(userId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), previousSecret);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer " + tokenSignedWithOldSecret);
        MockHttpServletResponse response = new MockHttpServletResponse();

        rotationFilter.doFilter(request, response, (req, res) -> {
            assertThat(AuthenticatedUserContext.get()).isPresent();
            assertThat(AuthenticatedUserContext.get().get().userId()).isEqualTo(userId);
        });

        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ── internal-service tokens ─────────────────────────────────────────────
    // isInternalService() opens POST /inventory/{id}/confirm and lets
    // DELETE /inventory/{id}/reserve/{bookingId} skip its ownership check, so which key signed a
    // token claiming that type is the whole question — verification alone accepts any of the three.

    private static final String INTERNAL_SECRET = "internal-secret-at-least-32-bytes-long-hs256!!";

    private static String internalServiceToken(String secret) {
        return buildToken(new JWTClaimsSet.Builder()
                .subject("booking-service")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("tokenType", AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), secret);
    }

    @Test
    void honoursInternalServiceClaimOnATokenSignedWithTheInternalSecret() throws Exception {
        JwtAuthenticationFilter internalAwareFilter = new JwtAuthenticationFilter(new JwtAuthProperties(
                SECRET, null, INTERNAL_SECRET, ISSUER, AUDIENCE, List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory/s1/confirm");
        request.addHeader("Authorization", "Bearer " + internalServiceToken(INTERNAL_SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();
        internalAwareFilter.doFilter(request, response,
                (req, res) -> seen.set(AuthenticatedUserContext.get().orElse(null)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().isInternalService()).isTrue();
    }

    @Test
    void rejectsInternalServiceClaimOnATokenSignedWithTheUserFacingSecret() throws Exception {
        JwtAuthenticationFilter internalAwareFilter = new JwtAuthenticationFilter(new JwtAuthProperties(
                SECRET, null, INTERNAL_SECRET, ISSUER, AUDIENCE, List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory/s1/confirm");
        // Signed with jwt.secret — the key every service and the gateway holds — but claiming to be
        // the service token that only jwt.internal-secret is supposed to be able to mint.
        request.addHeader("Authorization", "Bearer " + internalServiceToken(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        internalAwareFilter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void stillHonoursInternalServiceClaimWhenNoInternalSecretIsConfigured() throws Exception {
        // The documented local/dev fallback: InternalServiceTokenProvider signs with jwt.secret
        // when jwt.internal-secret is unset, so there is no second key to demand.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory/s1/confirm");
        request.addHeader("Authorization", "Bearer " + internalServiceToken(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();
        filter.doFilter(request, response,
                (req, res) -> seen.set(AuthenticatedUserContext.get().orElse(null)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().isInternalService()).isTrue();
    }

    @Test
    void ordinaryUserTokenIsUnaffectedWhenAnInternalSecretIsConfigured() throws Exception {
        JwtAuthenticationFilter internalAwareFilter = new JwtAuthenticationFilter(new JwtAuthProperties(
                SECRET, null, INTERNAL_SECRET, ISSUER, AUDIENCE, List.of()));

        String userId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings");
        request.addHeader("Authorization", "Bearer " + validToken(userId, "user@example.com"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<AuthenticatedUser> seen = new AtomicReference<>();
        internalAwareFilter.doFilter(request, response,
                (req, res) -> seen.set(AuthenticatedUserContext.get().orElse(null)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().userId()).isEqualTo(userId);
        assertThat(seen.get().isInternalService()).isFalse();
    }

    @Test
    void rejectsUnresolvedPlaceholderSecret() {
        JwtAuthProperties badProps = new JwtAuthProperties(
                "${JWT_SECRET}", null, null, ISSUER, AUDIENCE, List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtAuthenticationFilter(badProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpanded placeholder");
    }

    @Test
    void rejectsShortSecret() {
        JwtAuthProperties badProps = new JwtAuthProperties(
                "too-short-secret", null, null, ISSUER, AUDIENCE, List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtAuthenticationFilter(badProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits (32 bytes)");
    }

    @Test
    void rejectsUnresolvedPreviousSecret() {
        JwtAuthProperties badProps = new JwtAuthProperties(
                SECRET, "${JWT_PREVIOUS_SECRET}", null, ISSUER, AUDIENCE, List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtAuthenticationFilter(badProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.previous-secret")
                .hasMessageContaining("unexpanded placeholder");
    }

    private FilterChain neverInvokedChain() {
        return (req, res) -> {
            throw new AssertionError("Filter chain must not be invoked when the token is rejected");
        };
    }
}
