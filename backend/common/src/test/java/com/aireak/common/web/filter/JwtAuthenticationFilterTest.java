package com.aireak.common.web.filter;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.security.JwtAuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
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
            SECRET, ISSUER, AUDIENCE, List.of("/actuator/**"));

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);

    private final SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

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
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "a-completely-different-secret-key-that-is-long-enough".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(wrongKey)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer("some-other-issuer")
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add("some-other-audience").and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, neverInvokedChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(secretKey)
                .compact();
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
        assertThat(seenDuringChain.get()).contains(new AuthenticatedUser(accountId, "user@example.com", token));
        // Must not leak into whatever request the pooled thread handles next.
        assertThat(AuthenticatedUserContext.get()).isEmpty();
    }

    @Test
    void validTokenWithoutEmailClaimLeavesEmailNull() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String tokenWithoutEmail = Jwts.builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookings/123");
        request.addHeader("Authorization", "Bearer " + tokenWithoutEmail);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Optional<AuthenticatedUser>> seenDuringChain = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seenDuringChain.set(AuthenticatedUserContext.get()));

        assertThat(seenDuringChain.get()).contains(new AuthenticatedUser(accountId, null, tokenWithoutEmail));
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

    private String validToken(String subject, String email) {
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("email", email)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }

    private FilterChain neverInvokedChain() {
        return (req, res) -> {
            throw new AssertionError("Filter chain must not be invoked when the token is rejected");
        };
    }
}
