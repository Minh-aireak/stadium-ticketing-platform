package com.aireak.catalog.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import com.aireak.catalog.application.port.out.MatchSearchPort;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ElasticsearchMatchSearchAdapter running elasticsearch-java 9.5.1
 * client against an Elasticsearch 9.5.1 server container using Jackson 3 (tools.jackson.*).
 *
 * <p>Keep this image tag in step with the elasticsearch service in docker-compose.yaml — the
 * point of the test is to exercise the client against the server version actually deployed.</p>
 */
@Testcontainers
class ElasticsearchMatchSearchAdapterIntegrationTest {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.5.1")
                    .withEnv("xpack.security.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(3));

    static RestClient restClient;
    static ElasticsearchClient client;
    static ElasticsearchMatchSearchAdapter adapter;

    @BeforeAll
    static void setUpClient() {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();

        JsonMapper jsonMapper = JsonMapper.builder()
                .findAndAddModules(ElasticsearchMatchSearchAdapterIntegrationTest.class.getClassLoader())
                .build();

        ElasticsearchTransport transport = new RestClientTransport(restClient, new Jackson3JsonpMapper(jsonMapper));
        client = new ElasticsearchClient(transport);
        adapter = new ElasticsearchMatchSearchAdapter(client);
    }

    @AfterAll
    static void tearDownClient() throws IOException {
        restClient.close();
    }

    /**
     * Built PUBLISHED rather than through {@code Match.create()}, which yields a DRAFT: the search
     * index is only ever written for a match that has been published (MatchCatalogService calls
     * the indexer from publishMatch, completeMatch and cancelMatch), so a DRAFT document was never
     * a state this adapter had to handle — and since the query filters on status, indexing one
     * would only have tested that a document nobody writes cannot be found.
     */
    private static Match publishedMatch(String homeTeam, String awayTeam, String competition) {
        return Match.reconstitute(UUID.randomUUID().toString(), homeTeam, awayTeam, competition,
                MatchStatus.PUBLISHED, Instant.now(), List.of());
    }

    @Test
    void indexesAndSearchesRealMatch() throws IOException {
        Match match = publishedMatch("Hanoi FC", "HAGL", "V.League 1");

        adapter.index(match);
        client.indices().refresh(r -> r.index("matches"));

        MatchSearchPort.SearchResult results = adapter.search("Hanoi FC", 0, 10);

        assertThat(results.matches())
                .extracting(Match::getMatchId)
                .contains(match.getMatchId());
    }

    /**
     * Pins the two things the PUBLISHED filter depends on that cannot be read off the source: that
     * the term query reaches {@code status.keyword} rather than the analyzed {@code status} field
     * (dynamic mapping decides that, and a term query against the analyzed field silently matches
     * nothing), and that {@code totalHits} therefore counts only what the caller is handed.
     * {@code MatchCatalogService.listMatches} reports that number as the page total.
     */
    @Test
    void searchCountsAndReturnsOnlyPublishedMatches() throws IOException {
        // One token, shared by these two documents and nothing else this class indexes, so the
        // multi_match (OR by default) cannot pull in a match from another test.
        String competition = "statusfilterleague";
        Match published = publishedMatch("Live FC", "Rival FC", competition);
        Match completed = Match.reconstitute(UUID.randomUUID().toString(), "Done FC", "Rival FC",
                competition, MatchStatus.COMPLETED, Instant.now(), List.of());
        adapter.index(published);
        adapter.index(completed);
        client.indices().refresh(r -> r.index("matches"));

        MatchSearchPort.SearchResult result = adapter.search(competition, 0, 10);

        assertThat(result.matches())
                .extracting(Match::getMatchId)
                .containsExactly(published.getMatchId());
        assertThat(result.totalHits()).isEqualTo(1L);
    }

    @Test
    void searchesWithPaginationOverTenMatches() throws IOException {
        for (int i = 0; i < 15; i++) {
            Match match = publishedMatch("PaginationTeam " + i, "Away FC", "V.League 1");
            adapter.index(match);
        }
        client.indices().refresh(r -> r.index("matches"));

        MatchSearchPort.SearchResult page0 = adapter.search("PaginationTeam", 0, 10);
        assertThat(page0.matches()).hasSize(10);
        assertThat(page0.totalHits()).isEqualTo(15L);

        MatchSearchPort.SearchResult page1 = adapter.search("PaginationTeam", 1, 10);
        assertThat(page1.matches()).hasSize(5);
        assertThat(page1.totalHits()).isEqualTo(15L);

        List<String> page0Ids = page0.matches().stream().map(Match::getMatchId).toList();
        List<String> page1Ids = page1.matches().stream().map(Match::getMatchId).toList();
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }
}
