package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.Showtime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
        return jpaRepository.findById(matchId).map(this::toDomain);
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
                        .build())
                .collect(Collectors.toList());

        MatchJpaEntity entity = MatchJpaEntity.builder()
                .matchId(match.getMatchId())
                .homeTeam(match.getHomeTeam())
                .awayTeam(match.getAwayTeam())
                .competition(match.getCompetition())
                .status(match.getStatus())
                .createdAt(match.getCreatedAt())
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
                        s.getVenueId(), s.getTotalSeats(), s.getAvailableSeats()))
                .toList();
        return Match.reconstitute(
                e.getMatchId(), e.getHomeTeam(), e.getAwayTeam(),
                e.getCompetition(), e.getStatus(), e.getCreatedAt(), showtimes);
    }
}
