package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) is actually wired into this service's
 * filter chain — not just unit-tested in isolation. See {@code JwtAuthenticationFilterTest} in
 * the common module for the filter's own validation-logic coverage.
 *
 * <p>Uses an intentionally invalid body ({@code {}}) so a valid token proves the request reached
 * {@code @Valid} argument resolution (400) without needing Elasticsearch/DB up for a real create.
 */
@WebMvcTest(MatchController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "jwt.excluded-paths[0]=GET:/api/v1/matches",
        "jwt.excluded-paths[1]=GET:/api/v1/matches/*"
})
class MatchControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateMatchUseCase createMatchUseCase;

    @MockitoBean
    private AddShowtimeUseCase addShowtimeUseCase;

    @MockitoBean
    private PublishMatchUseCase publishMatchUseCase;

    @MockitoBean
    private ListMatchesUseCase listMatchesUseCase;

    @MockitoBean
    private GetMatchUseCase getMatchUseCase;

    @MockitoBean
    private CancelMatchUseCase cancelMatchUseCase;

    @MockitoBean
    private CompleteMatchUseCase completeMatchUseCase;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(createMatchUseCase, addShowtimeUseCase, publishMatchUseCase);
    }

    @Test
    void listMatchesIsReachableWithoutABearerToken() throws Exception {
        org.mockito.Mockito.when(listMatchesUseCase.listMatches(null, 0, 20))
                .thenReturn(new ListMatchesUseCase.MatchPage(java.util.List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/matches"))
                .andExpect(status().isOk());
    }

    @Test
    void getMatchIsReachableWithoutABearerToken() throws Exception {
        org.mockito.Mockito.when(getMatchUseCase.getMatch("match-1")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/matches/match-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fixedStadiumCatalogIsPublicAndContainsThreeChoices() throws Exception {
        mockMvc.perform(get("/api/v1/matches/stadiums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("my-dinh"))
                .andExpect(jsonPath("$[0].totalSeats").value(432))
                .andExpect(jsonPath("$[1].id").value("thong-nhat"))
                .andExpect(jsonPath("$[2].id").value("hang-day"))
                .andExpect(jsonPath("$[2].levels").value(3));
    }

    @Test
    void rejectsRequestWithInvalidToken() throws Exception {
        mockMvc.perform(post("/api/v1/matches")
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReachesTheControllerWhichRejectsTheInvalidBody() throws Exception {
        mockMvc.perform(post("/api/v1/matches")
                        .header("Authorization", "Bearer " + validToken(null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Closes the gap requireAdminRole() itself was never exercised by any test (only 401/400). */
    @Test
    void nonAdminTokenIsRejectedWithForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/matches")
                        .header("Authorization", "Bearer " + validToken("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"homeTeam":"Home FC","awayTeam":"Away FC","competition":"V.League 1"}"""))
                .andExpect(status().isForbidden());

        verifyNoInteractions(createMatchUseCase);
    }

    @Test
    void adminTokenReachesTheControllerAndCreatesTheMatch() throws Exception {
        org.mockito.Mockito.when(createMatchUseCase.createMatch("Home FC", "Away FC", "V.League 1"))
                .thenReturn("match-1");

        mockMvc.perform(post("/api/v1/matches")
                        .header("Authorization", "Bearer " + validToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"homeTeam":"Home FC","awayTeam":"Away FC","competition":"V.League 1"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void adminCreatesShowtimeBySelectingStadiumWithoutSendingSeatCount() throws Exception {
        Instant startTime = Instant.parse("2099-08-02T12:00:00Z");

        mockMvc.perform(post("/api/v1/matches/match-1/showtimes")
                        .header("Authorization", "Bearer " + validToken("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startTime":"2099-08-02T12:00:00Z",
                                  "stadiumId":"hang-day",
                                  "basePrice":150000,
                                  "currency":"VND"
                                }"""))
                .andExpect(status().isOk());

        verify(addShowtimeUseCase).addShowtime(
                "match-1", startTime, "hang-day", new BigDecimal("150000"), "VND");
    }

    /** @param role the JWT "role" claim value, or null to omit the claim entirely. */
    private String validToken(String role) {
        try {
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (role != null) {
                claimsBuilder.claim("role", role);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsBuilder.build());
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
