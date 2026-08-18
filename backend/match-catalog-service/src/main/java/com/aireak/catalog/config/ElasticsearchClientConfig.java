package com.aireak.catalog.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Elasticsearch client configuration using elasticsearch-java 9.5.1.
 *
 * <p>Uses {@link Jackson3JsonpMapper} aligned with Spring Boot 4.1's Jackson 3
 * ({@code tools.jackson.*}) ecosystem.
 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public RestClient elasticsearchRestClient(
            @Value("${elasticsearch.host}") String host,
            @Value("${elasticsearch.port}") int port,
            @Value("${elasticsearch.scheme}") String scheme,
            @Value("${elasticsearch.username}") String username,
            @Value("${elasticsearch.password}") String password) {

        // Basic auth sent up front on every request, rather than the BasicCredentialsProvider the
        // Elasticsearch docs show. RestClient builds a fresh HttpClientContext per request, so it
        // never caches the negotiated auth scheme: with a credentials provider, every single call
        // would be a 401 challenge followed by a retry — two round trips for each search.
        String basic = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));

        return RestClient.builder(new HttpHost(host, port, scheme))
                .setDefaultHeaders(new Header[]{new BasicHeader("Authorization", "Basic " + basic)})
                .build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        // Jackson 3 JsonMapper — JavaTimeModule is built-in to Jackson 3
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
