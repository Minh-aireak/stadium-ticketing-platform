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
import java.util.List;
import java.util.Optional;
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
        return jpaRepository.findByIdWithShowtimes(matchId).map(this::toDomain);
    }

    @Override
    public Optional<Match> findByShowtimeId(String showtimeId) {
        return jpaRepository.findByShowtimeId(showtimeId).map(this::toDomain);
    }

    @Override
    public List<Match> findByStatus(MatchStatus status, int page, int size) {
        return jpaRepository
                .findByStatusWithShowtimes(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toDomain)
                .getContent();
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
    // Domain → JPA
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
    // JPA → Domain (reconstitute)
    // ----------------------------------------------------------------

    private Match toDomain(MatchJpaEntity e) {
        List<Showtime> showtimes = e.getShowtimes().stream()
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
