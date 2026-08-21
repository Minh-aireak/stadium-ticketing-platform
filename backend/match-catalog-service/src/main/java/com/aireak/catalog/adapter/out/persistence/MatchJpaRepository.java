package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.domain.model.MatchStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read strategy here follows one rule: <b>JOIN FETCH for a single root, IN for many roots.</b>
 *
 * <p>A collection fetch join costs two things that both scale with the number of roots. It repeats
 * every root column once per child row on the wire, and — worse — it makes SQL pagination
 * impossible: Hibernate cannot apply {@code LIMIT}/{@code OFFSET} without truncating some root's
 * collection, so it falls back to loading the entire result set and paginating in memory
 * ({@code HHH000104}). With one root neither cost exists, so {@link #findByIdWithShowtimes} keeps
 * its fetch join; the multi-root reads below load roots and showtimes as two queries instead.
 *
 * <p>No {@code DISTINCT} anywhere: since Hibernate 6 root entities are de-duplicated in memory
 * automatically, so writing {@code DISTINCT} in JPQL only pushes a real {@code SELECT DISTINCT}
 * down to Postgres, making it sort/hash-unique the very rows the join just widened.
 */
interface MatchJpaRepository extends JpaRepository<MatchJpaEntity, String> {

    /** Single root — the fetch join costs one extra copy of a ~110-byte row per showtime, and no pagination is involved. */
    @Query("SELECT m FROM MatchJpaEntity m LEFT JOIN FETCH m.showtimes WHERE m.matchId = :matchId")
    Optional<MatchJpaEntity> findByIdWithShowtimes(@Param("matchId") String matchId);

    @Query("SELECT m FROM MatchJpaEntity m JOIN FETCH m.showtimes s WHERE s.showtimeId = :showtimeId")
    Optional<MatchJpaEntity> findByShowtimeId(@Param("showtimeId") String showtimeId);

    /**
     * Roots only, so {@code LIMIT}/{@code OFFSET} actually reaches SQL and a page request reads
     * one page instead of the whole status set. Showtimes arrive via
     * {@link #findShowtimesByMatchIdIn}.
     *
     * <p>Returns {@code List}, not {@code Page}, on purpose: a {@code Page} return type makes
     * Spring Data run a COUNT alongside the content query, and nothing here consumes it — the
     * caller wants the rows, and a total comes from {@link #countByStatus} when it is actually
     * asked for. {@code Pageable} still applies the limit/offset either way.
     */
    List<MatchJpaEntity> findByStatus(MatchStatus status, Pageable pageable);

    /** Roots for an explicit id set — the batch form of {@link #findByIdWithShowtimes}. */
    List<MatchJpaEntity> findByMatchIdIn(Collection<String> matchIds);

    /**
     * Second half of every multi-root read: all showtimes for a page of matches in one
     * {@code WHERE match_id IN (...)}, index-backed by {@code idx_showtimes_match}. Ordered so a
     * match's showtimes come out chronologically instead of in whatever order Postgres returns.
     */
    @Query("SELECT s FROM ShowtimeJpaEntity s WHERE s.matchId IN :matchIds ORDER BY s.startTime ASC")
    List<ShowtimeJpaEntity> findShowtimesByMatchIdIn(@Param("matchIds") Collection<String> matchIds);

    /**
     * Ids only — no join, no entity hydration. The id-list cache in {@code CachingMatchRepository}
     * needs nothing else, and used to obtain these by fetch-joining every match of the status
     * together with all its showtimes and then discarding everything but the id.
     */
    @Query("SELECT m.matchId FROM MatchJpaEntity m WHERE m.status = :status ORDER BY m.createdAt DESC")
    List<String> findIdsByStatus(@Param("status") MatchStatus status);

    long countByStatus(MatchStatus status);

    @Query("SELECT COUNT(s) > 0 FROM MatchJpaEntity m JOIN m.showtimes s " +
            "WHERE s.venueId = :venueId AND s.startTime = :startTime")
    boolean existsShowtimeAtVenueAndTime(@Param("venueId") String venueId, @Param("startTime") Instant startTime);

    @Modifying
    @Query(value = "UPDATE showtimes SET available_seats = GREATEST(available_seats - :count, 0) " +
            "WHERE showtime_id = :showtimeId", nativeQuery = true)
    int decrementAvailableSeats(@Param("showtimeId") String showtimeId, @Param("count") int count);

    /** Fresh available_seats value right after {@link #decrementAvailableSeats}, for write-through into Redis. */
    @Query("SELECT s.availableSeats FROM ShowtimeJpaEntity s WHERE s.showtimeId = :showtimeId")
    Optional<Integer> findAvailableSeats(@Param("showtimeId") String showtimeId);
}
