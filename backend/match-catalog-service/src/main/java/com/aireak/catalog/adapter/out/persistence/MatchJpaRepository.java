package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.domain.model.MatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface MatchJpaRepository extends JpaRepository<MatchJpaEntity, String> {

    /**
     * JOIN FETCH avoids the N+1 that a plain {@code findById} + lazy {@code getShowtimes()}
     * would cause (one extra showtimes query per match hydrated) — moot for a single match here,
     * but keeps this repository's two read paths consistent with {@link #findByStatus}.
     */
    @Query("SELECT m FROM MatchJpaEntity m LEFT JOIN FETCH m.showtimes WHERE m.matchId = :matchId")
    Optional<MatchJpaEntity> findByIdWithShowtimes(@Param("matchId") String matchId);

    /**
     * JOIN FETCH loads every match's showtimes in this one query instead of one lazy-load query
     * per match on the page (the N+1 {@code toDomain()} used to trigger). DISTINCT drops the
     * row-per-showtime duplication the join introduces; {@code countQuery} avoids counting once
     * per showtime instead of once per match.
     *
     * <p>Hibernate applies pagination in-memory for a collection fetch join (it can't do
     * {@code LIMIT}/{@code OFFSET} in SQL against a one-to-many join), so this loads every match
     * in the given status before paginating in Java. Acceptable at the catalog sizes this service
     * expects (single-digit thousands of matches); revisit with a two-query
     * (paginate-ids-then-fetch) approach if that stops being true.
     */
    @Query(value = "SELECT DISTINCT m FROM MatchJpaEntity m LEFT JOIN FETCH m.showtimes WHERE m.status = :status",
            countQuery = "SELECT COUNT(m) FROM MatchJpaEntity m WHERE m.status = :status")
    Page<MatchJpaEntity> findByStatusWithShowtimes(@Param("status") MatchStatus status, Pageable pageable);

    long countByStatus(MatchStatus status);

    @Query("SELECT COUNT(s) > 0 FROM MatchJpaEntity m JOIN m.showtimes s " +
            "WHERE s.venueId = :venueId AND s.startTime = :startTime")
    boolean existsShowtimeAtVenueAndTime(@Param("venueId") String venueId, @Param("startTime") Instant startTime);
}
