package com.aireak.notification.web;

import com.aireak.notification.application.port.in.ListNotificationsUseCase;
import com.aireak.notification.application.port.in.MarkNotificationReadUseCase;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) runs ahead of Spring MVC's dispatch in
 * this service — not merely that it sits somewhere on the chain.
 *
 * <p>The paths below map to no handler on purpose. {@code NotificationController} does exist and
 * serves {@code GET /api/v1/notifications} and {@code PATCH /api/v1/notifications/{id}/read};
 * {@code NotificationControllerJwtAuthenticationIntegrationTest} asserts real 200/404 responses
 * against those. {@code /api/v1/notifications/anything} matches neither, which is what makes it
 * useful here: an unauthenticated request answered 404 would prove the filter had let it reach
 * dispatch, so being answered 401 instead is what pins the ordering. For the same reason a valid
 * token is asserted only to be "not 401" — what routing does with a path it cannot map is
 * routing's business, not this filter's.
 *
 * <p>Uses a bare {@code @WebMvcTest} rather than naming a controller, so the chain under test is
 * the whole one. See {@code JwtAuthenticationFilterTest} in the common module for the filter's
 * own validation-logic coverage, and {@code jwt.excluded-paths} in application.yaml for the
 * actuator paths {@code excludedActuatorPathSkipsValidation} covers.
 */
@WebMvcTest
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
})
class JwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListNotificationsUseCase listNotificationsUseCase;

    @MockitoBean
    private MarkNotificationReadUseCase markNotificationReadUseCase;

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
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
