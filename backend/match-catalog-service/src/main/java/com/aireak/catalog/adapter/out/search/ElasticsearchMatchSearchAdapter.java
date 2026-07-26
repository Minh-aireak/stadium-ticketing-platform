package com.aireak.catalog.adapter.out.search;

import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbound adapter: Elasticsearch for match search read model.
 * Implements CQRS read side — separate from the JPA write model.
 *
 * <p>Uses Elasticsearch Java client 8.x (co.elastic.clients).
 * Index name: "matches".
 *
 * <p>TODO: inject ElasticsearchClient and implement real indexing.
 * Currently a stub that logs operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchMatchSearchAdapter implements MatchSearchPort {

    // TODO: @Autowired ElasticsearchClient elasticsearchClient;
    // (requires Elasticsearch running — stub until integration env ready)

    private static final String INDEX = "matches";

    @Override
    public void index(Match match) {
        // STUB — replace with actual indexing:
        // var document = toDocument(match);
        // elasticsearchClient.index(req -> req.index(INDEX).id(match.getMatchId()).document(document));
        log.info("[STUB ES] Indexed match: id={}, {}v{} status={}",
                match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(), match.getStatus());
    }

    @Override
    public List<Match> search(String query) {
        // STUB — replace with actual ES query
        log.info("[STUB ES] Searching matches: query={}", query);
        return List.of();
    }
}
