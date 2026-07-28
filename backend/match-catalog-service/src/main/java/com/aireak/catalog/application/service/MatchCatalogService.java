package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application service: manages Match lifecycle in the catalog.
 *
 * <p>On {@code publish()}: persists match, updates Elasticsearch index,
 * publishes MatchPublishedEvent to Kafka.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchCatalogService implements CreateMatchUseCase, AddShowtimeUseCase, PublishMatchUseCase,
        ListMatchesUseCase, GetMatchUseCase {

    private final MatchRepository matchRepository;
    private final MatchSearchPort matchSearchPort;
    private final DomainEventPublisher eventPublisher;

    @Override
    @Transactional
    public String createMatch(String homeTeam, String awayTeam, String competition) {
        Match match = Match.create(homeTeam, awayTeam, competition);
        matchRepository.save(match);
        log.info("Match created: id={} {} vs {}", match.getMatchId(), homeTeam, awayTeam);
        return match.getMatchId();
    }

    @Override
    @Transactional
    public void addShowtime(String matchId, Instant startTime, String venueId, int totalSeats,
                             BigDecimal basePrice, String currency) {
        Match match = findOrThrow(matchId);
        match.addShowtime(new Showtime(startTime, venueId, totalSeats, basePrice, currency));
        matchRepository.save(match);
        eventPublisher.publishAll(match.pullDomainEvents());
    }

    @Override
    @Transactional
    public void publishMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.publish();
        matchRepository.save(match);

        // Update Elasticsearch read model synchronously (could be async for large catalogs)
        matchSearchPort.index(match);

        // Publish domain events (consumed by other services)
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match published: id={}", matchId);
    }

    @Transactional
    public void completeMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.complete();
        matchRepository.save(match);
    }

    @Transactional
    public void cancelMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.cancel();
        matchRepository.save(match);
    }

    private Match findOrThrow(String matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Match not found: " + matchId));
    }

    // ----------------------------------------------------------------
    // Public catalog browse/search (read side)
    // ----------------------------------------------------------------

    @Override
    public MatchPage listMatches(String query, int page, int size) {
        if (query != null && !query.isBlank()) {
            // Elasticsearch documents carry summary fields only (no showtimes — see
            // MatchSearchPort) and can go stale after a match completes/cancels (the index is
            // only ever written on publish()), so every hit is re-read from the write-side JPA
            // repository for full showtime data and re-filtered to currently-PUBLISHED.
            List<Match> hydrated = matchSearchPort.search(query).stream()
                    .map(hit -> matchRepository.findById(hit.getMatchId()).orElse(null))
                    .filter(Objects::nonNull)
                    .filter(m -> m.getStatus() == MatchStatus.PUBLISHED)
                    .toList();
            return new MatchPage(hydrated, hydrated.size(), 0, hydrated.size());
        }

        List<Match> items = matchRepository.findByStatus(MatchStatus.PUBLISHED, page, size);
        long total = matchRepository.countByStatus(MatchStatus.PUBLISHED);
        return new MatchPage(items, total, page, size);
    }

    @Override
    public Optional<Match> getMatch(String matchId) {
        return matchRepository.findById(matchId)
                .filter(m -> m.getStatus() != MatchStatus.DRAFT);
    }
}
