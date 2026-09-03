package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MatchPersistenceAdapter implements MatchRepository {

    private final MatchJpaRepository jpaRepository;

    @Override
    public void save(Match match) {
        jpaRepository.save(toJpaEntity(match));
    }

    @Override
    public Optional<Match> findById(String matchId) {
        // Single root: the fetch join already carries the showtimes, so reading the collection
        // here triggers nothing further.
        return jpaRepository.findByIdWithShowtimes(matchId)
                .map(e -> toDomain(e, e.getShowtimes()));
    }

    /**
     * Identical to {@link #findById} here — this adapter has no cache to bypass. The two stay
     * separate methods because {@link CachingMatchRepository} is what the application layer
     * actually holds, and there they are genuinely different reads.
     */
    @Override
    public Optional<Match> findByIdForUpdate(String matchId) {
        return findById(matchId);
    }

    @Override
    public Optional<Match> findByShowtimeId(String showtimeId) {
        return jpaRepository.findByShowtimeId(showtimeId)
                .map(e -> toDomain(e, e.getShowtimes()));
    }

    /**
     * Deliberately NOT on {@link MatchRepository}: an id list is a caching detail, not something
     * the application layer ever asks for. {@link CachingMatchRepository} holds this adapter by
     * its concrete type and is the only caller.
     */
    public List<String> findIdsByStatus(MatchStatus status) {
        return jpaRepository.findIdsByStatus(status);
    }

    @Override
    public List<Match> findAllByIds(Collection<String> matchIds) {
        if (matchIds.isEmpty()) {
            return List.of();
        }
        List<String> requested = matchIds.stream().distinct().toList();
        Map<String, Match> byId = assemble(jpaRepository.findByMatchIdIn(requested)).stream()
                .collect(Collectors.toMap(Match::getMatchId, Function.identity()));
        // IN makes no promise about row order, and the caller's order is meaningful (it is the
        // page order, or the search-relevance order) — so restore it here.
        return requested.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Match> findByStatus(MatchStatus status, int page, int size) {
        return assemble(jpaRepository
                .findByStatus(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Override
    public long countByStatus(MatchStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public boolean existsShowtimeAtVenueAndTime(String venueId, Instant startTime) {
        return jpaRepository.existsShowtimeAtVenueAndTime(venueId, startTime);
    }

    // ----------------------------------------------------------------
    // Multi-root assembly
    // ----------------------------------------------------------------

    /**
     * Turns roots loaded WITHOUT their showtimes into full aggregates using exactly one extra
     * query, whatever the number of roots.
     *
     * <p>{@code MatchJpaEntity.showtimes} is {@code LAZY} and deliberately never touched on these
     * entities — reading it would fire one select per match and reinstate the N+1 this two-query
     * shape exists to remove. Showtimes are passed in explicitly instead, which is why
     * {@link #toDomain} takes them as a parameter rather than calling {@code e.getShowtimes()}.
     */
    private List<Match> assemble(List<MatchJpaEntity> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        List<String> matchIds = roots.stream().map(MatchJpaEntity::getMatchId).toList();
        Map<String, List<ShowtimeJpaEntity>> showtimesByMatch = jpaRepository
                .findShowtimesByMatchIdIn(matchIds).stream()
                .collect(Collectors.groupingBy(ShowtimeJpaEntity::getMatchId));

        return roots.stream()
                .map(root -> toDomain(root, showtimesByMatch.getOrDefault(root.getMatchId(), List.of())))
                .toList();
    }

    // ----------------------------------------------------------------
    // Domain -> JPA
    // ----------------------------------------------------------------

    private MatchJpaEntity toJpaEntity(Match match) {
        List<ShowtimeJpaEntity> showtimeEntities = match.getShowtimes().stream()
                .map(s -> ShowtimeJpaEntity.builder()
                        .showtimeId(s.getShowtimeId())
                        .matchId(match.getMatchId())
                        .startTime(s.getStartTime())
                        .venueId(s.getVenueId())
                        .totalSeats(s.getTotalSeats())
                        .availableSeats(s.getAvailableSeats())
                        .basePrice(s.getBasePrice())
                        .currency(s.getCurrency())
                        .build())
                .collect(Collectors.toList());

        MatchJpaEntity entity = MatchJpaEntity.builder()
                .matchId(match.getMatchId())
                .homeTeam(match.getHomeTeam())
                .awayTeam(match.getAwayTeam())
                .competition(match.getCompetition())
                .status(match.getStatus())
                .build();
        entity.getShowtimes().clear();
        entity.getShowtimes().addAll(showtimeEntities);
        return entity;
    }

    // ----------------------------------------------------------------
    // JPA -> Domain (reconstitute)
    // ----------------------------------------------------------------

    private Match toDomain(MatchJpaEntity e, List<ShowtimeJpaEntity> showtimeEntities) {
        List<Showtime> showtimes = showtimeEntities.stream()
                .map(s -> new Showtime(
                        s.getShowtimeId(), s.getStartTime(),
                        s.getVenueId(), s.getTotalSeats(), s.getAvailableSeats(),
                        s.getBasePrice(), s.getCurrency()))
                .toList();
        return Match.reconstitute(
                e.getMatchId(), e.getHomeTeam(), e.getAwayTeam(),
                e.getCompetition(), e.getStatus(), e.getCreatedAt(), showtimes);
    }
}
