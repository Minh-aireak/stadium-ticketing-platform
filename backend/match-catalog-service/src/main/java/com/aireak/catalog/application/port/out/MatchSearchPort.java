package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;

import java.util.List;

/**
 * Outbound port: Elasticsearch search index.
 * Implemented by ElasticsearchMatchSearchAdapter.
 * CQRS read model — updated asynchronously after match state changes.
 */
public interface MatchSearchPort {
    void index(Match match);

    /**
     * @throws MatchSearchException if the cluster cannot be reached. Callers on a request thread
     *         must map it to 503 rather than let it reach a generic 500 — see
     *         {@code MatchController#handleSearchUnavailable}.
     */
    SearchResult search(String query, int page, int size);

    record SearchResult(List<Match> matches, long totalHits) {}
}
