package com.aireak.catalog.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Outbound adapter: Elasticsearch for match search read model.
 * Implements CQRS read side — separate from the JPA write model.
 *
 * <p>Index name: "matches". Showtimes are not part of the search document —
 * search results carry summary fields only; showtime detail is read from the
 * write-side (JPA) repository.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchMatchSearchAdapter implements MatchSearchPort {

    private static final String INDEX = "matches";

    private final ElasticsearchClient elasticsearchClient;

    @Override
    public void index(Match match) {
        try {
            MatchDocument document = toDocument(match);
            elasticsearchClient.index(req -> req
                    .index(INDEX)
                    .id(match.getMatchId())
                    .document(document));
            log.info("Indexed match: id={}, {}v{} status={}",
                    match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
        } catch (IOException e) {
            throw new MatchSearchException("Failed to index match " + match.getMatchId(), e);
        }
    }

    @Override
    public List<Match> search(String query) {
        try {
            SearchResponse<MatchDocument> response = elasticsearchClient.search(req -> req
                            .index(INDEX)
                            .query(q -> q.multiMatch(m -> m
                                    .query(query)
                                    .fields("homeTeam", "awayTeam", "competition"))),
                    MatchDocument.class);

            return response.hits().hits().stream()
                    .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                    .filter(Objects::nonNull)
                    .map(this::toMatch)
                    .toList();
        } catch (IOException e) {
            throw new MatchSearchException("Failed to search matches with query: " + query, e);
        }
    }

    private MatchDocument toDocument(Match match) {
        return new MatchDocument(
                match.getMatchId(),
                match.getHomeTeam(),
                match.getAwayTeam(),
                match.getCompetition(),
                match.getStatus().name(),
                match.getCreatedAt());
    }

    private Match toMatch(MatchDocument document) {
        return Match.reconstitute(
                document.matchId(),
                document.homeTeam(),
                document.awayTeam(),
                document.competition(),
                MatchStatus.valueOf(document.status()),
                document.createdAt(),
                List.of());
    }
}
