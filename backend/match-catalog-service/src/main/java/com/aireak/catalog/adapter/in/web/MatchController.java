package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.application.port.out.MatchSearchException;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.StadiumCatalog;
import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/** Inbound REST adapter: match catalog management endpoints. */
@Slf4j
@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String RETRY_AFTER_SECONDS = "1";

    // matches.home_team, away_team and competition are each VARCHAR(100) (V1__init_schema.sql),
    // and CreateMatchRequest carried only @NotBlank. A longer name therefore travelled all the way
    // to the INSERT, where Postgres answered "value too long for type character varying(100)" — a
    // DataIntegrityViolationException raised at commit, which GlobalExceptionHandler can only
    // report as 409 "The request conflicts with existing data". Bounding it here answers 400 and
    // names the offending field instead.
    private static final int MAX_NAME_LENGTH = 100;

    private final CreateMatchUseCase createMatchUseCase;
    private final AddShowtimeUseCase addShowtimeUseCase;
    private final PublishMatchUseCase publishMatchUseCase;
    private final ListMatchesUseCase listMatchesUseCase;
    private final GetMatchUseCase getMatchUseCase;
    private final CancelMatchUseCase cancelMatchUseCase;
    private final CompleteMatchUseCase completeMatchUseCase;

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
        addShowtimeUseCase.addShowtime(matchId, req.startTime(), req.stadiumId(),
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
     * PUT /api/v1/matches/{matchId}/cancel — ADMIN only. Raises {@code MatchCancelledEvent},
     * which booking-service consumes to cancel active bookings for this match's showtimes and
     * refund the ones already paid; notification-service emails affected customers via the
     * existing booking-cancelled flow (see {@code MatchCancelledEvent} javadoc).
     */
    @PutMapping("/{matchId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String matchId,
                                       @Valid @RequestBody CancelMatchRequest req) {
        requireAdminRole();
        cancelMatchUseCase.cancelMatch(matchId, req.reason());
        return ResponseEntity.ok().build();
    }

    /** PUT /api/v1/matches/{matchId}/complete — ADMIN only. */
    @PutMapping("/{matchId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String matchId) {
        requireAdminRole();
        completeMatchUseCase.completeMatch(matchId);
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

    /** GET /api/v1/matches/stadiums — fixed stadium choices for showtime creation. */
    @GetMapping("/stadiums")
    public List<StadiumResponse> listStadiums() {
        return StadiumCatalog.list().stream()
                .map(stadium -> new StadiumResponse(stadium.id(), stadium.name(), stadium.totalSeats(),
                        stadium.levels(), stadium.design()))
                .toList();
    }

    /** GET /api/v1/matches/{matchId} — public match detail, no auth required (method-scoped exclusion). */
    @GetMapping("/{matchId}")
    public ResponseEntity<MatchResponse> get(@PathVariable String matchId) {
        return getMatchUseCase.getMatch(matchId)
                .map(m -> ResponseEntity.ok(toResponse(m)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * An unreachable Elasticsearch cluster becomes 503 with a Retry-After, not the 500 a bare
     * RuntimeException would otherwise collect from {@code GlobalExceptionHandler}. 500 tells the
     * caller this service is broken and retrying is pointless; the cluster being down is neither.
     * Only the {@code q=} branch of {@link #list} touches it — the unfiltered browse beside it
     * reads Postgres and keeps working — so what fails here is the search box, not the catalog.
     *
     * <p>Controller-local rather than another {@code @ControllerAdvice}: a handler on the
     * controller wins over every advice whatever its order, which is the whole problem
     * {@code CatalogOverloadExceptionHandler}'s javadoc exists to describe.
     *
     * <p>Logs here because nothing else does — the adapter throws without logging, and moving this
     * off {@code handleGenericException} takes away the only {@code log.error} the failure had.
     * The detail is a constant for the same reason the handlers for a malformed body and a
     * constraint violation use one: {@link MatchSearchException}'s message embeds the customer's
     * query and its cause embeds the cluster's host and port.
     */
    @ExceptionHandler(MatchSearchException.class)
    public ResponseEntity<ProblemDetail> handleSearchUnavailable(MatchSearchException ex) {
        log.error("Match search is unavailable", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Match search is temporarily unavailable");
        problem.setType(URI.create("https://aireak.com/errors/search-unavailable"));
        problem.setTitle("Search Unavailable");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    private MatchListResponse toListResponse(ListMatchesUseCase.MatchPage result) {
        return new MatchListResponse(
                result.items().stream().map(this::toResponse).toList(),
                result.totalElements(), result.page(), result.size());
    }

    private MatchResponse toResponse(Match match) {
        List<ShowtimeResponse> showtimes = match.getShowtimes().stream()
                .map(s -> {
                    String stadiumName = StadiumCatalog.find(s.getVenueId())
                            .map(StadiumCatalog.StadiumDefinition::name)
                            .orElse(s.getVenueId());
                    return new ShowtimeResponse(s.getShowtimeId(), s.getStartTime(), s.getVenueId(),
                            stadiumName, s.getTotalSeats(), s.getAvailableSeats(),
                            s.getBasePrice(), s.getCurrency());
                })
                .toList();
        return new MatchResponse(match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
                match.getCompetition(), match.getStatus().name(), match.getCreatedAt(), showtimes);
    }

    record CreateMatchRequest(@NotBlank @Size(max = MAX_NAME_LENGTH) String homeTeam,
                              @NotBlank @Size(max = MAX_NAME_LENGTH) String awayTeam,
                              @NotBlank @Size(max = MAX_NAME_LENGTH) String competition) {}
    record CreateMatchResponse(String matchId) {}
    record AddShowtimeRequest(@NotNull Instant startTime, @NotBlank String stadiumId,
                              @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal basePrice,
                              @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
    record CancelMatchRequest(@NotBlank String reason) {}

    record StadiumResponse(String id, String name, int totalSeats, int levels, String design) {}
    record ShowtimeResponse(String showtimeId, Instant startTime, String stadiumId, String stadiumName,
                            int totalSeats, int availableSeats, BigDecimal basePrice, String currency) {}
    record MatchResponse(String matchId, String homeTeam, String awayTeam, String competition,
                        String status, Instant createdAt, List<ShowtimeResponse> showtimes) {}
    record MatchListResponse(List<MatchResponse> items, long totalElements, int page, int size) {}
}
