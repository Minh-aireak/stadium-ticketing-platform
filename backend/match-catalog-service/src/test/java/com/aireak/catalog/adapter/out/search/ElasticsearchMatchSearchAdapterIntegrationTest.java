package com.aireak.catalog.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aireak.catalog.domain.model.Match;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real elasticsearch-java client (backend/pom.xml pins 8.18.3) against a real server
 * on the same version (matching docker-compose.yaml's elasticsearch image tag) — catches any
 * client/server protocol mismatch that a compile-only check would miss.
 */
@Testcontainers
class ElasticsearchMatchSearchAdapterIntegrationTest {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.3")
                    .withEnv("xpack.security.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(3));

    static RestClient restClient;
    static ElasticsearchClient client;
    static ElasticsearchMatchSearchAdapter adapter;

    @BeforeAll
    static void setUpClient() {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
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
        client.indices().refresh(r -> r.index("matches")); // force visibility before searching

        List<Match> results = adapter.search("Hanoi FC");

        assertThat(results)
                .extracting(Match::getMatchId)
                .contains(match.getMatchId());
    }
}
