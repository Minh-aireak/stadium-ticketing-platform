package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchRepository {
    void save(Match match);
    Optional<Match> findById(String matchId);
    Optional<Match> findByShowtimeId(String showtimeId);

    /**
     * Every match for {@code matchIds}, in the order given, in a FIXED number of queries
     * regardless of how many ids are passed. Ids with no match are skipped.
     *
     * <p>This is the batch replacement for calling {@link #findById} in a loop.
     */
    List<Match> findAllByIds(Collection<String> matchIds);

    /** Page of matches in the given status, newest first. */
    List<Match> findByStatus(MatchStatus status, int page, int size);
    long countByStatus(MatchStatus status);

    /** True if any match (regardless of its own status) already has a showtime at this exact venue/startTime. */
    boolean existsShowtimeAtVenueAndTime(String venueId, Instant startTime);
}
