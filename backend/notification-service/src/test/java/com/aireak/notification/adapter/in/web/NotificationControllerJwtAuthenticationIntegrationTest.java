package com.aireak.notification.adapter.in.web;

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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
})
class NotificationControllerJwtAuthenticationIntegrationTest {

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
    void rejectsListRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMarkReadRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", "notif-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listMineScopesToTheAuthenticatedCaller() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(listNotificationsUseCase.listByRecipient(accountId, 0, 20))
                .thenReturn(new ListNotificationsUseCase.NotificationPage(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + validToken(accountId)))
                .andExpect(status().isOk());
    }

    @Test
    void markReadReturns404WhenUseCaseReportsNotFoundOrNotOwned() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(markNotificationReadUseCase.markRead("notif-1", accountId)).thenReturn(false);

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", "notif-1")
                        .header("Authorization", "Bearer " + validToken(accountId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void markReadReturnsOkWhenOwnedByTheCaller() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(markNotificationReadUseCase.markRead("notif-1", accountId)).thenReturn(true);

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", "notif-1")
                        .header("Authorization", "Bearer " + validToken(accountId)))
                .andExpect(status().isOk());
    }

    private String validToken(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
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
