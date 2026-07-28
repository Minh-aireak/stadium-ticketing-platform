package com.aireak.notification.adapter.in.web;

import com.aireak.notification.application.port.in.ListNotificationsUseCase;
import com.aireak.notification.application.port.in.MarkNotificationReadUseCase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
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
        "jwt.audience=stadium-clients"
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
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
