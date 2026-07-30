package com.aireak.catalog.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aireak.catalog.domain.model.Match;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ElasticsearchMatchSearchAdapter running elasticsearch-java 9.2.0
 * client against an Elasticsearch 9.2.0 server container using Jackson 3 (tools.jackson.*).
 */
@Testcontainers
class ElasticsearchMatchSearchAdapterIntegrationTest {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.0")
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

    @Test
    void indexesAndSearchesRealMatch() throws IOException {
        Match match = Match.create("Hanoi FC", "HAGL", "V.League 1");

        adapter.index(match);
        client.indices().refresh(r -> r.index("matches"));

        MatchSearchPort.SearchResult results = adapter.search("Hanoi FC", 0, 10);

        assertThat(results.matches())
                .extracting(Match::getMatchId)
                .contains(match.getMatchId());
    }

    @Test
    void searchesWithPaginationOverTenMatches() throws IOException {
        for (int i = 0; i < 15; i++) {
            Match match = Match.create("PaginationTeam " + i, "Away FC", "V.League 1");
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
