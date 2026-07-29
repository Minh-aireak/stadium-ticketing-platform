package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.domain.model.Match;
import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Inbound REST adapter: match catalog management endpoints. */
@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String ROLE_ADMIN = "ADMIN";

    private final CreateMatchUseCase createMatchUseCase;
    private final AddShowtimeUseCase addShowtimeUseCase;
    private final PublishMatchUseCase publishMatchUseCase;
    private final ListMatchesUseCase listMatchesUseCase;
    private final GetMatchUseCase getMatchUseCase;

    /** POST /api/v1/matches — ADMIN only. */
    @PostMapping
    public ResponseEntity<CreateMatchResponse> create(@Valid @RequestBody CreateMatchRequest req) {
        requireAdminRole();
        String matchId = createMatchUseCase.createMatch(req.homeTeam(), req.awayTeam(), req.competition());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateMatchResponse(matchId));
    }

    /** POST /api/v1/matches/{matchId}/showtimes — ADMIN only. */
    @PostMapping("/{matchId}/showtimes")
    public ResponseEntity<Void> addShowtime(@PathVariable String matchId,
                                            @Valid @RequestBody AddShowtimeRequest req) {
        requireAdminRole();
        addShowtimeUseCase.addShowtime(matchId, req.startTime(), req.venueId(), req.totalSeats(),
                req.basePrice(), req.currency());
        return ResponseEntity.ok().build();
    }

    /** PUT /api/v1/matches/{matchId}/publish — ADMIN only. */
    @PutMapping("/{matchId}/publish")
    public ResponseEntity<Void> publish(@PathVariable String matchId) {
        requireAdminRole();
        publishMatchUseCase.publishMatch(matchId);
        return ResponseEntity.ok().build();
    }

    /**
     * No Spring Security in this service (see JwtAuthenticationFilter's javadoc) — so
     * {@code @PreAuthorize} isn't available. Enforces ADMIN-only mutation endpoints by reading
     * the role claim {@link com.aireak.common.web.filter.JwtAuthenticationFilter} already
     * validated and stashed in {@link AuthenticatedUserContext}.
     */
    private void requireAdminRole() {
        String role = AuthenticatedUserContext.get().map(AuthenticatedUser::role).orElse(null);
        if (!ROLE_ADMIN.equals(role)) {
            throw new ForbiddenException("ADMIN role required for this operation");
        }
    }

    /**
     * GET /api/v1/matches?q=&page=&size= — public catalog browse/search, no auth required
     * (see match-catalog-service's {@code jwt.excluded-paths}: method-scoped so the POST above
     * on the same path still requires a token).
     */
    @GetMapping
    public ResponseEntity<MatchListResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);

        ListMatchesUseCase.MatchPage result = listMatchesUseCase.listMatches(q, safePage, safeSize);
        return ResponseEntity.ok(toListResponse(result));
    }

    /** GET /api/v1/matches/{matchId} — public match detail, no auth required (method-scoped exclusion). */
    @GetMapping("/{matchId}")
    public ResponseEntity<MatchResponse> get(@PathVariable String matchId) {
        return getMatchUseCase.getMatch(matchId)
                .map(m -> ResponseEntity.ok(toResponse(m)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MatchListResponse toListResponse(ListMatchesUseCase.MatchPage result) {
        return new MatchListResponse(
                result.items().stream().map(this::toResponse).toList(),
                result.totalElements(), result.page(), result.size());
    }

    private MatchResponse toResponse(Match match) {
        List<ShowtimeResponse> showtimes = match.getShowtimes().stream()
                .map(s -> new ShowtimeResponse(s.getShowtimeId(), s.getStartTime(), s.getVenueId(),
                        s.getTotalSeats(), s.getAvailableSeats(), s.getBasePrice(), s.getCurrency()))
                .toList();
        return new MatchResponse(match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
                match.getCompetition(), match.getStatus().name(), match.getCreatedAt(), showtimes);
    }

    record CreateMatchRequest(@NotBlank String homeTeam, @NotBlank String awayTeam,
                              @NotBlank String competition) {}
    record CreateMatchResponse(String matchId) {}
    record AddShowtimeRequest(@NotNull Instant startTime, @NotBlank String venueId, @Positive int totalSeats,
                              @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal basePrice,
                              @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

    record ShowtimeResponse(String showtimeId, Instant startTime, String venueId,
                            int totalSeats, int availableSeats, BigDecimal basePrice, String currency) {}
    record MatchResponse(String matchId, String homeTeam, String awayTeam, String competition,
                        String status, Instant createdAt, List<ShowtimeResponse> showtimes) {}
    record MatchListResponse(List<MatchResponse> items, long totalElements, int page, int size) {}
}
