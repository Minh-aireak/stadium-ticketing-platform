package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchRepository {
    void save(Match match);

    /**
     * Read side. May be served from a cache, and the {@code availableSeats} it carries is
     * deliberately the LIVE counter's value rather than the committed column — see
     * {@code CachingMatchRepository}. Never use it to load an aggregate you intend to
     * {@link #save}: use {@link #findByIdForUpdate}.
     */
    Optional<Match> findById(String matchId);

    /**
     * Write side: the committed aggregate, straight from the database, no cache tier and no live
     * seat overlay.
     *
     * <p>A write here is load-mutate-save, so whatever this hands back is what gets persisted.
     * Loading it through {@link #findById} meant persisting two things the caller never chose:
     * the live Redis seat count, over the very column that counter is a cache of (and which
     * {@code SoldSeatsProjectionService} then reseeds Redis FROM, making a transient drift
     * permanent); and a possibly stale showtime list, which
     * {@code MatchJpaEntity.showtimes} being {@code orphanRemoval = true} turns into deleted
     * rows — the local cache tier cannot be invalidated across instances, and this service runs
     * two.
     */
    Optional<Match> findByIdForUpdate(String matchId);

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
