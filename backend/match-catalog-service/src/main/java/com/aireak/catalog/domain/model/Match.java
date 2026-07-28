package com.aireak.catalog.domain.model;

import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.catalog.domain.exception.InvalidMatchStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Aggregate Root: Match — Catalog bounded context.
 *
 * <p>Owns Showtime entities — no external code can directly mutate a Showtime.
 * This enforces the "one aggregate boundary" rule.
 *
 * <p>Admin use cases: create → publish → complete/cancel.
 */
public class Match {

    private final String matchId;
    private String homeTeam;
    private String awayTeam;
    private String competition;    // e.g. "V.League 1", "AFC Champions League"
    private MatchStatus status;
    private final Instant createdAt;
    private final List<Showtime> showtimes = new ArrayList<>();
    private final List<Object> domainEvents = new ArrayList<>();

    private Match(String matchId, String homeTeam, String awayTeam,
                  String competition, MatchStatus status, Instant createdAt) {
        this.matchId = matchId;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.competition = competition;
        this.status = status;
        this.createdAt = createdAt;
    }

    // ----------------------------------------------------------------
    // Factory
    // ----------------------------------------------------------------

    public static Match create(String homeTeam, String awayTeam, String competition) {
        String matchId = UUID.randomUUID().toString();
        return new Match(matchId, homeTeam, awayTeam, competition, MatchStatus.DRAFT, Instant.now());
    }

    public static Match reconstitute(String matchId, String homeTeam, String awayTeam,
                                      String competition, MatchStatus status,
                                      Instant createdAt, List<Showtime> showtimes) {
        Match match = new Match(matchId, homeTeam, awayTeam, competition, status, createdAt);
        match.showtimes.addAll(showtimes);
        return match;
    }

    // ----------------------------------------------------------------
    // Domain behavior
    // ----------------------------------------------------------------

    public void addShowtime(Showtime showtime) {
        if (status != MatchStatus.DRAFT) {
            throw new InvalidMatchStatusException("Cannot add showtime to match in status: " + status);
        }
        showtimes.add(showtime);
        domainEvents.add(new ShowtimeAddedEvent(matchId, showtime.getShowtimeId(), showtime.getTotalSeats(),
                showtime.getBasePrice(), showtime.getCurrency()));
    }

    /**
     * Publishes the match — makes it visible to customers.
     * Invariant: must have at least one showtime.
     */
    public void publish() {
        if (status != MatchStatus.DRAFT) {
            throw new InvalidMatchStatusException("Only DRAFT matches can be published, current: " + status);
        }
        if (showtimes.isEmpty()) {
            throw new InvalidMatchStatusException("Cannot publish a match with no showtimes");
        }
        this.status = MatchStatus.PUBLISHED;
        domainEvents.add(new MatchPublishedEvent(matchId, homeTeam, awayTeam, competition));
    }

    public void complete() {
        if (status != MatchStatus.PUBLISHED) {
            throw new InvalidMatchStatusException("Only PUBLISHED matches can be completed, current: " + status);
        }
        this.status = MatchStatus.COMPLETED;
    }

    public void cancel() {
        if (status == MatchStatus.COMPLETED || status == MatchStatus.CANCELLED) {
            throw new InvalidMatchStatusException("Cannot cancel a " + status + " match");
        }
        this.status = MatchStatus.CANCELLED;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public String getMatchId()            { return matchId; }
    public String getHomeTeam()           { return homeTeam; }
    public String getAwayTeam()           { return awayTeam; }
    public String getCompetition()        { return competition; }
    public MatchStatus getStatus()        { return status; }
    public Instant getCreatedAt()         { return createdAt; }
    public List<Showtime> getShowtimes()  { return Collections.unmodifiableList(showtimes); }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }
}
