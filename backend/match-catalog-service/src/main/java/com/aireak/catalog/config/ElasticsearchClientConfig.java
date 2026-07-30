package com.aireak.catalog.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Elasticsearch client configuration using elasticsearch-java 9.2.0.
 *
 * <p>Uses {@link Jackson3JsonpMapper} (new in elasticsearch-java 9.2.0) instead of the legacy
 * {@code JacksonJsonpMapper}, because {@code JacksonJsonpMapper} depends on
 * {@code com.fasterxml.jackson} (Jackson 2) which conflicts with Spring Boot 4.1's
 * Jackson 3 autoconfiguration.
 *
 * <p>Jackson 3 ({@code tools.jackson}) has JavaTimeModule built-in — no manual registration
 * needed for {@code Instant} / {@code LocalDate} fields in {@code MatchDocument}.
 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public RestClient elasticsearchRestClient(
            @Value("${elasticsearch.host}") String host,
            @Value("${elasticsearch.port}") int port,
            @Value("${elasticsearch.scheme}") String scheme) {
        return RestClient.builder(new HttpHost(host, port, scheme)).build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        // Jackson3JsonpMapper uses tools.jackson.databind.json.JsonMapper under the hood.
        // JavaTimeModule is built-in to Jackson 3 — Instant fields serialize correctly
        // without explicit registration.
        JsonMapper jsonMapper = JsonMapper.builder()
                .findAndAddModules(ElasticsearchClientConfig.class.getClassLoader())
                .build();
        return new RestClientTransport(restClient, new Jackson3JsonpMapper(jsonMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
