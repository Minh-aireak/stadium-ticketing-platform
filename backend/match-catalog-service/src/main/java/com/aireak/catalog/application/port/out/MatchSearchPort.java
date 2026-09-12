package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;

import java.util.List;

/**
 * Outbound port: Elasticsearch search index.
 * Implemented by ElasticsearchMatchSearchAdapter.
 * CQRS read model — reconciled by {@code MatchSearchIndexReconciler} from the match lifecycle
 * events this service publishes through its outbox.
 */
public interface MatchSearchPort {
    void index(Match match);

    /**
     * Removes the match's document; a match that has no document is not an error. The index holds
     * PUBLISHED matches only — {@code MatchSearchIndexReconciler} deletes on any other status —
     * so this is how a cancelled or completed match leaves search, and how a match that was
     * never public stays out of it.
     */
    void delete(String matchId);

    /**
     * @throws MatchSearchException if the cluster cannot be reached. Callers on a request thread
     *         must map it to 503 rather than let it reach a generic 500 — see
     *         {@code MatchController#handleSearchUnavailable}.
     */
    SearchResult search(String query, int page, int size);

    record SearchResult(List<Match> matches, long totalHits) {}
}
