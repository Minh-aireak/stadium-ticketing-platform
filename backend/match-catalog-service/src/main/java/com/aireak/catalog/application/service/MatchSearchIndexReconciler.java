package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.ReconcileMatchSearchIndexUseCase;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Writes the search index from the database, one match at a time, whenever that match's
 * lifecycle changes.
 *
 * <p><strong>Why this replaced the {@code @Async} indexer.</strong> Publishing a match is a
 * dual write — Postgres, then Elasticsearch — with no transaction spanning both. The previous
 * indexer ran the Elasticsearch half on a thread pool right after the commit, caught whatever
 * went wrong and logged it. That was the right call for keeping a slow cluster out of the
 * publish transaction and the wrong one for everything after: a match published while
 * Elasticsearch was down (one on this very stack, 2026-09-08) went into Postgres, never into
 * the index, and nothing would ever try again. Browse showed it; search could not find it;
 * no counter moved.
 *
 * <p>The durable channel already existed. {@code publishMatch}, {@code completeMatch} and
 * {@code cancelMatch} each write a domain event to the outbox, which Debezium relays to Kafka
 * with at-least-once delivery, and every consumer on the platform sits behind
 * {@code AbstractKafkaConsumerConfig}'s retry-then-dead-letter handler. So the index is now
 * written by {@code MatchSearchIndexConsumer} consuming those events back: Elasticsearch down for
 * a moment costs a retry, down for longer parks the record on the {@code -dlt} topic where
 * {@code MatchSearchIndexDeadLetterConsumer} alerts, and a fresh consumer group with
 * {@code auto-offset-reset=earliest} rebuilds the whole index from the topics' history. The
 * javadoc of the old indexer weighed this option and turned it down as too much machinery
 * "just to get off the request thread"; durability is a different reason, and it is enough.
 *
 * <p><strong>The event is a trigger, not the data.</strong> The match is re-read here, through
 * the uncached {@link MatchRepository#findByIdForUpdate} — the local Guava tier is per instance
 * and a cancel committed on the other instance can still read as PUBLISHED from this one's cache
 * for up to five seconds, which is longer than the outbox round trip. Writing what the database
 * says makes the outcome independent of event order (the three events ride three topics, with
 * no ordering between them) and of duplicate delivery. A PUBLISHED match is indexed; anything
 * else is deleted, which is also how a cancelled DRAFT — never public, never indexed — stays out
 * of the index rather than sitting in it for the query's status filter to hide.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSearchIndexReconciler implements ReconcileMatchSearchIndexUseCase {

    private final MatchRepository matchRepository;
    private final MatchSearchPort matchSearchPort;

    /**
     * Throws whatever {@link MatchSearchPort} throws: the consumer's error handler owns the retry
     * and the dead-letter, and a swallowed exception here would put the old silent gap back.
     * A match id that no longer resolves is the one thing not worth a retry — there is nothing
     * to index and nothing to correct, so it is logged and the record is acknowledged.
     */
    @Override
    public void reconcile(String matchId) {
        Optional<Match> found = matchRepository.findByIdForUpdate(matchId);
        if (found.isEmpty()) {
            log.warn("Search index reconcile skipped: match {} is not in the database", matchId);
            return;
        }
        Match match = found.get();
        if (match.getStatus() == MatchStatus.PUBLISHED) {
            matchSearchPort.index(match);
        } else {
            matchSearchPort.delete(matchId);
        }
        log.info("Search index reconciled: match={}, status={}", matchId, match.getStatus());
    }
}
