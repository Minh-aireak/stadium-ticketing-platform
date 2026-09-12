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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Field-level bounds on {@code POST /api/v1/matches}, distinct from
 * {@link MatchControllerJwtAuthenticationIntegrationTest}, which covers who may reach the endpoint
 * at all.
 *
 * <p>{@code matches.home_team}, {@code away_team} and {@code competition} are all VARCHAR(100)
 * (see {@code V1__init_schema.sql}), and the request record carried only {@code @NotBlank}. A
 * longer name therefore travelled the whole way to the INSERT, where Postgres answered "value too
 * long for type character varying(100)" — a DataIntegrityViolationException raised at commit, so
 * {@code GlobalExceptionHandler} could only report it as 409 "The request conflicts with existing
 * data". The admin got a conflict message for what is a plain input-length problem, and no
 * indication of which of the three fields was at fault.
 */
@WebMvcTest(MatchController.class)
// The web slice registers every @ControllerAdvice, and CatalogOverloadExceptionHandler counts
// its rejections on a MeterRegistry — which @WebMvcTest does not auto-configure.
@Import(SimpleMeterRegistry.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "jwt.excluded-paths[0]=GET:/api/v1/matches",
        "jwt.excluded-paths[1]=GET:/api/v1/matches/*"
})
class MatchControllerRequestValidationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    /** The width of every one of the three columns in V1__init_schema.sql. */
    private static final int COLUMN_WIDTH = 100;

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
    void rejectsAHomeTeamWiderThanItsColumn() throws Exception {
        mockMvc.perform(createMatch(oversized(), "Away FC", "V.League 1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("homeTeam")));

        verifyNoInteractions(createMatchUseCase);
    }

    @Test
    void rejectsAnAwayTeamWiderThanItsColumn() throws Exception {
        mockMvc.perform(createMatch("Home FC", oversized(), "V.League 1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("awayTeam")));

        verifyNoInteractions(createMatchUseCase);
    }

    @Test
    void rejectsACompetitionWiderThanItsColumn() throws Exception {
        mockMvc.perform(createMatch("Home FC", "Away FC", oversized()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("competition")));

        verifyNoInteractions(createMatchUseCase);
    }

    /** The bound is the column width, not one less than it — a name that fits must still pass. */
    @Test
    void acceptsNamesOfExactlyTheColumnWidth() throws Exception {
        String atLimit = "A".repeat(COLUMN_WIDTH);
        when(createMatchUseCase.createMatch(atLimit, atLimit, atLimit)).thenReturn("match-1");

        mockMvc.perform(createMatch(atLimit, atLimit, atLimit))
                .andExpect(status().isCreated());
    }

    private static String oversized() {
        return "A".repeat(COLUMN_WIDTH + 1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createMatch(
            String homeTeam, String awayTeam, String competition) {
        return post("/api/v1/matches")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"homeTeam":"%s","awayTeam":"%s","competition":"%s"}"""
                        .formatted(homeTeam, awayTeam, competition));
    }

    private String adminToken() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .claim("role", "ADMIN")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
