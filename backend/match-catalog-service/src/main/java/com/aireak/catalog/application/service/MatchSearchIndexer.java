package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs {@link MatchSearchPort#index} off the caller's thread, on its own Spring bean so the
 * {@code @Async} proxy actually applies (a method calling {@code @Async} on itself — e.g. if this
 * lived directly on {@code MatchCatalogService} — bypasses the proxy and would run synchronously
 * anyway; see Spring's self-invocation caveat for {@code @Async}/{@code @Transactional}).
 *
 * <p><strong>Why {@code @Async} over self-consuming {@code MatchPublishedEvent}</strong>: the
 * event already exists and goes out via the outbox (see {@code InfraConfig}), so a Kafka listener
 * consuming it back into this same service would work too — but it adds a consumer group, its own
 * retry/DLT handling, and an at-least-once redelivery path the index write would need to tolerate,
 * just to get off the request thread. {@code @Async} gets the same decoupling (ES indexing no
 * longer runs inside {@code publishMatch}'s DB transaction, so a slow/unavailable ES cluster can
 * no longer hold that transaction open) with none of that — the trade-off is that a failed index
 * write here has no built-in retry, only the log line below. Revisit if that turns out to matter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchSearchIndexer {

    private final MatchSearchPort matchSearchPort;

    @Async("matchSearchIndexExecutor")
    public void indexAsync(Match match) {
        try {
            matchSearchPort.index(match);
        } catch (Exception e) {
            log.error("Elasticsearch indexing failed for match {}: {}", match.getMatchId(), e.getMessage(), e);
        }
    }
}
