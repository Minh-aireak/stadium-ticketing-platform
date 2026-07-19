package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.Showtime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Application service: manages Match lifecycle in the catalog.
 *
 * <p>On {@code publish()}: persists match, updates Elasticsearch index,
 * publishes MatchPublishedEvent to Kafka.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchCatalogService {

    private final MatchRepository matchRepository;
    private final MatchSearchPort matchSearchPort;
    private final DomainEventPublisher eventPublisher;

    @Transactional
    public String createMatch(String homeTeam, String awayTeam, String competition) {
        Match match = Match.create(homeTeam, awayTeam, competition);
        matchRepository.save(match);
        log.info("Match created: id={} {} vs {}", match.getMatchId(), homeTeam, awayTeam);
        return match.getMatchId();
    }

    @Transactional
    public void addShowtime(String matchId, Instant startTime, String venueId, int totalSeats) {
        Match match = findOrThrow(matchId);
        match.addShowtime(new Showtime(startTime, venueId, totalSeats));
        matchRepository.save(match);
    }

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
}
