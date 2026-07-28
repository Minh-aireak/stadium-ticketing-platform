package com.aireak.notification.web;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * notification-service is a pure Kafka consumer with no business REST controller (only
 * actuator, which is excluded — see {@code jwt.excluded-paths} in application.yaml), so there's
 * no real endpoint to assert a 2xx/4xx against. Instead this confirms {@code
 * JwtAuthenticationFilter} (from common) intercepts every non-excluded path regardless: a
 * missing token is rejected with 401 before Spring MVC ever attempts routing, and a valid
 * token is let through to routing instead (whatever it then does with an unmapped path is
 * routing's concern, not this filter's — asserting "not 401" here is deliberately the only
 * claim being made). See {@code JwtAuthenticationFilterTest} in the common module for the
 * filter's own validation-logic coverage.
 */
@WebMvcTest
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients"
})
class JwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/anything")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsLetThroughToRouting() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/anything")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(result -> assertNotUnauthorized(result.getResponse().getStatus()));
    }

    @Test
    void excludedActuatorPathSkipsValidation() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertNotUnauthorized(result.getResponse().getStatus()));
    }

    private void assertNotUnauthorized(int status) {
        if (status == 401) {
            throw new AssertionError("Must not be rejected by the JWT filter, got 401");
        }
    }

    private String validToken() {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
