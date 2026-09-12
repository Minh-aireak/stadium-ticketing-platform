package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.GetShowtimeUseCase;
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
import com.aireak.catalog.domain.model.StadiumCatalog;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        ListMatchesUseCase, GetMatchUseCase, GetShowtimeUseCase, CancelMatchUseCase, CompleteMatchUseCase {

    private final MatchRepository matchRepository;
    private final MatchSearchPort matchSearchPort;
    private final DomainEventPublisher eventPublisher;
    private final ShowtimeSeatCounterInitializer showtimeSeatCounterInitializer;

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
    public void addShowtime(String matchId, Instant startTime, String stadiumId,
                             BigDecimal basePrice, String currency) {
        if (!startTime.isAfter(Instant.now())) {
            throw new InvalidShowtimeException("startTime must be in the future: " + startTime);
        }
        StadiumCatalog.StadiumDefinition stadium = StadiumCatalog.find(stadiumId)
                .orElseThrow(() -> new InvalidShowtimeException("Unknown stadium: " + stadiumId));
        // Exact-match check only (no showtime duration in the domain model to compute a real
        // overlap window against) — still catches the double-booking case that matters: two
        // showtimes claiming the same venue at the same instant.
        if (matchRepository.existsShowtimeAtVenueAndTime(stadiumId, startTime)) {
            throw new InvalidShowtimeException(
                    "Another showtime already exists at stadium " + stadiumId + " at " + startTime);
        }

        Match match = findOrThrow(matchId);
        Showtime showtime = new Showtime(startTime, stadiumId, stadium.totalSeats(), basePrice, currency);
        match.addShowtime(showtime);
        matchRepository.save(match);
        eventPublisher.publishAll(match.pullDomainEvents());

        // Every showtime gets its live seat counter seeded here, at creation, rather than lazily on
        // the first sale — see ShowtimeSeatCounterInitializer for why that invariant is worth having.
        // Deliberately fail-open and inside this transaction: a Redis hiccup must not fail the
        // creation, and a counter seeded for a showtime whose transaction then rolls back is a key
        // under a UUID that will never be used again, which expires on its own.
        showtimeSeatCounterInitializer.initializeCounter(showtime.getShowtimeId(), showtime.getTotalSeats());

        log.info("Showtime added: match={}, showtime={}, stadium={}, totalSeats={}, startTime={}",
                matchId, showtime.getShowtimeId(), stadiumId, showtime.getTotalSeats(), startTime);
    }

    @Override
    @Transactional
    public void publishMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.publish();
        matchRepository.save(match);

        // Publish domain events — consumed by other services, and by this one: the search index
        // is written by MatchSearchIndexConsumer off MatchPublishedEvent, never from here, so a
        // slow or absent Elasticsearch cannot hold this transaction open and a failed index
        // write is retried and dead-lettered like any other event instead of being lost. See
        // MatchSearchIndexReconciler.
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match published: id={}", matchId);
    }

    @Override
    @Transactional
    public void completeMatch(String matchId) {
        Match match = findOrThrow(matchId);
        match.complete();
        matchRepository.save(match);
        // complete() only accepts a PUBLISHED match, so there is always a search document to
        // remove. MatchCompletedEvent is what removes it (see MatchSearchIndexReconciler), and
        // that is what keeps the search total honest — see listMatches.
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match completed: id={}", matchId);
    }

    @Override
    @Transactional
    public void cancelMatch(String matchId, String reason) {
        Match match = findOrThrow(matchId);
        // cancel() also accepts a DRAFT match, which was never indexed. MatchCancelledEvent drives
        // the index either way: the reconciler deletes a non-PUBLISHED match's document, and
        // deleting one that was never written is a no-op — so an unpublished match stays out of
        // the public search index entirely, rather than sitting in it for the status filter to hide.
        match.cancel(reason);
        matchRepository.save(match);
        eventPublisher.publishAll(match.pullDomainEvents());
        log.info("Match cancelled: id={}, reason={}", matchId, reason);
    }

    /**
     * Every write use case above loads through here, and every one of them saves what it gets
     * back — so this deliberately takes the uncached, un-overlaid read
     * ({@link MatchRepository#findByIdForUpdate}) rather than the one the browse endpoints use.
     * See that method for what went into Postgres when it did not.
     */
    private Match findOrThrow(String matchId) {
        return matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(() -> new MatchNotFoundException(matchId));
    }

    // ----------------------------------------------------------------
    // Public catalog browse/search (read side)
    // ----------------------------------------------------------------

    // Bulkhead + RateLimiter guard only this read side, never the admin writes above: browse is
    // what a flash sale actually floods, and it is the only path here with no other bound on it
    // (the writes are rare, authenticated and already serialized by their transaction). Once the
    // permits or the per-second budget are gone, calls are rejected immediately
    // (BulkheadFullException / RequestNotPermitted, mapped to 503 by
    // CatalogOverloadExceptionHandler) rather than queueing Tomcat threads on CatalogHikariPool.
    @Override
    @Bulkhead(name = "catalog-read", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "catalog-read")
    public MatchPage listMatches(String query, int page, int size) {
        if (query != null && !query.isBlank()) {
            // Elasticsearch documents carry summary fields only (no showtimes — see
            // MatchSearchPort), so every hit is re-read from the write-side JPA repository for
            // full showtime data.
            //
            // Re-read as ONE batch, not one findById per hit: findAllByIds keeps the hits in
            // relevance order and drops ids that no longer resolve, so the only thing lost versus
            // the old per-hit loop is the query-per-hit.
            //
            // The PUBLISHED filter below is now a backstop, not the only guard: the query itself
            // asks Elasticsearch for PUBLISHED documents (see the adapter), and completeMatch and
            // cancelMatch re-index so it can answer that truthfully. That is what makes totalHits
            // describe the same set as `items` — it used to count matches this filter then
            // dropped, because the index was written on publish() and never again. What is left
            // for the filter to catch is the indexing lag: the index follows the lifecycle event
            // through the outbox and Kafka, so a match completed a moment ago can still be a
            // PUBLISHED document.
            MatchSearchPort.SearchResult searchResult = matchSearchPort.search(query, page, size);
            List<String> hitIds = searchResult.matches().stream().map(Match::getMatchId).toList();
            List<Match> items = matchRepository.findAllByIds(hitIds).stream()
                    .filter(m -> m.getStatus() == MatchStatus.PUBLISHED)
                    .toList();
            return new MatchPage(items, searchResult.totalHits(), page, size);
        }

        List<Match> items = matchRepository.findByStatus(MatchStatus.PUBLISHED, page, size);
        long total = matchRepository.countByStatus(MatchStatus.PUBLISHED);
        return new MatchPage(items, total, page, size);
    }

    @Override
    @Bulkhead(name = "catalog-read", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "catalog-read")
    public Optional<Match> getMatch(String matchId) {
        return matchRepository.findById(matchId)
                .filter(m -> m.getStatus() != MatchStatus.DRAFT);
    }

    /**
     * On {@code catalog-showtime}, not {@code catalog-read}, because this is not a browse
     * endpoint. It is the one call ticket-inventory-service's
     * {@code ShowtimeCatalogRestAdapter#requireBookable} makes in front of every seat hold and
     * every reservation, and that adapter has no cache for a positive answer — a rejection here
     * reaches it as a 503, which it now passes on to the customer as a 503 of its own rather than
     * as a closed booking window (see {@code ShowtimeCatalogUnavailableException}).
     *
     * <p>Sharing browse's budget therefore meant anonymous, unauthenticated traffic could take
     * the entire buy path down: browse floods first (it is the endpoint a flash sale hammers),
     * exhausts the shared permits, and every attempt to actually buy a ticket is shed with it.
     * The two now fail independently, which is the only arrangement where shedding browse is
     * load-shedding rather than an outage.
     */
    @Override
    @Bulkhead(name = "catalog-showtime", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "catalog-showtime")
    public Optional<ShowtimeDetails> getShowtime(String showtimeId) {
        return matchRepository.findByShowtimeId(showtimeId)
                .flatMap(match -> match.getShowtimes().stream()
                        .filter(showtime -> showtime.getShowtimeId().equals(showtimeId))
                        .findFirst()
                        .map(showtime -> new ShowtimeDetails(
                                showtime.getShowtimeId(), showtime.getStartTime(), match.getStatus())));
    }
}
