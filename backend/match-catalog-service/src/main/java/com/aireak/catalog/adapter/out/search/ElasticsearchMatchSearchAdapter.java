package com.aireak.catalog.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.aireak.catalog.application.port.out.MatchSearchException;
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

    /**
     * Elasticsearch refuses any request whose {@code from + size} exceeds the index's
     * {@code index.max_result_window} (10 000 by default) — it answers 400, which surfaces here as
     * an unhandled exception and reaches the caller as a 500 on a public endpoint. Nothing stops a
     * client asking for page 200; bounding it here turns "too deep to answer" into an empty page,
     * which is what it means.
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * The index has no explicit mapping, so Elasticsearch's dynamic mapping makes {@code status} a
     * {@code text} field with a {@code keyword} sub-field. A term query has to go to the sub-field:
     * the analyzed {@code text} one holds {@code published}, lowercased, and would never match the
     * enum constant.
     */
    private static final String STATUS_KEYWORD_FIELD = "status.keyword";

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
    public SearchResult search(String query, int page, int size) {
        // Computed in long: MatchController clamps size to [1,100] but leaves page unbounded above,
        // so `page * size` as int wraps — ?q=x&page=2147483647 lands on from = -100, which
        // Elasticsearch rejects with a 400 and the caller sees as a 500.
        int safeSize = Math.max(size, 0);
        long offset = (long) Math.max(page, 0) * safeSize;
        if (offset + safeSize > MAX_RESULT_WINDOW) {
            // Past what the index will page through at all. An empty page is the honest answer and
            // costs no round trip; totalHits stays 0 because we never got to ask.
            return new SearchResult(List.of(), 0);
        }
        int from = (int) offset;
        try {
            // Restricted to PUBLISHED here rather than only after the fact, so that totalHits
            // counts the same matches the caller is given. MatchCatalogService re-filters the
            // page it gets back, and used to be the ONLY thing that did: the total came straight
            // from Elasticsearch and therefore counted completed and cancelled matches that the
            // re-filter then removed, so a page of 10 could report 47 results and hand back 6.
            // A `filter` clause, not a `must`: match status contributes nothing to relevance.
            SearchResponse<MatchDocument> response = elasticsearchClient.search(req -> req
                            .index(INDEX)
                            .from(from)
                            .size(safeSize)
                            .query(q -> q.bool(b -> b
                                    .must(mustQuery -> mustQuery.multiMatch(m -> m
                                            .query(query)
                                            .fields("homeTeam", "awayTeam", "competition")))
                                    .filter(filterQuery -> filterQuery.term(t -> t
                                            .field(STATUS_KEYWORD_FIELD)
                                            .value(MatchStatus.PUBLISHED.name()))))),
                    MatchDocument.class);

            List<Match> matches = response.hits().hits().stream()
                    .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                    .filter(Objects::nonNull)
                    .map(this::toMatch)
                    .toList();

            long totalHits = response.hits().total() != null ? response.hits().total().value() : matches.size();
            return new SearchResult(matches, totalHits);
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
