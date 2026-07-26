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
    List<Match> search(String query);
}
