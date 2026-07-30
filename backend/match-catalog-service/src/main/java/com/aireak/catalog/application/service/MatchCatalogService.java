package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.exception.InvalidShowtimeException;
import com.aireak.catalog.domain.exception.MatchNotFoundException;
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
        ListMatchesUseCase, GetMatchUseCase, CancelMatchUseCase, CompleteMatchUseCase {

    private final MatchRepository matchRepository;
    private final MatchSearchPort matchSearchPort;
    private final DomainEventPublisher eventPublisher;
    private final MatchSearchIndexer matchSearchIndexer;

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
        if (!startTime.isAfter(Instant.now())) {
            throw new InvalidShowtimeException("startTime must be in the future: " + startTime);
        }
        // Exact-match check only (no showtime duration in the domain model to compute a real
        // overlap window against) — still catches the double-booking case that matters: two
        // showtimes claiming the same venue at the same instant.
        if (matchRepository.existsShowtimeAtVenueAndTime(venueId, startTime)) {
            throw new InvalidShowtimeException(
                    "Another showtime already exists at venue " + venueId + " at " + startTime);
        }

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

        // Off the request thread and outside this transaction (see MatchSearchIndexer javadoc for
        // why @Async instead of self-consuming MatchPublishedEvent) — a slow/unavailable ES
        // cluster must not hold this DB transaction, or the whole publish, open.
        matchSearchIndexer.indexAsync(match);

        // Publish domain events (consumed by other services)
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match published: id={}", matchId);
    }

    @Override
    @Transactional
    public void completeMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.complete();
        matchRepository.save(match);
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match completed: id={}", matchId);
    }

    @Override
    @Transactional
    public void cancelMatch(String matchId, String reason) {
        Match match = findOrThrow(matchId);
        match.cancel(reason);
        matchRepository.save(match);
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match cancelled: id={}, reason={}", matchId, reason);
    }

    private Match findOrThrow(String matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new MatchNotFoundException(matchId));
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
            MatchSearchPort.SearchResult searchResult = matchSearchPort.search(query, page, size);
            List<Match> items = searchResult.matches().stream()
                    .map(hit -> matchRepository.findById(hit.getMatchId()).orElse(null))
                    .filter(Objects::nonNull)
                    .filter(m -> m.getStatus() == MatchStatus.PUBLISHED)
                    .toList();
            return new MatchPage(items, searchResult.totalHits(), page, size);
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
