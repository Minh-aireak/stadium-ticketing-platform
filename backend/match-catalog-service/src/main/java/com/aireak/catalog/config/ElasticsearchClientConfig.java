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

    /**
     * Tighter than the 1s this client already defaults to, only so the three budgets below are
     * stated in one place rather than two of them being inherited.
     */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /**
     * Replaces the client's own 30s default, which is a reasonable figure for a reindex and a
     * wildly wrong one for the browse query on a public endpoint.
     */
    private static final int SOCKET_TIMEOUT_MS = 3_000;

    /**
     * How long a caller waits for one of the pool's connections before giving up. See the bean's
     * javadoc — this is the budget that was previously absent altogether.
     */
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 2_000;

    /**
     * Backs {@code ElasticsearchMatchSearchAdapter}, so every {@code ?q=} browse on the public
     * catalog endpoint goes through here while holding a {@code @Bulkhead(name = "catalog-read")}
     * permit.
     *
     * <p>Carried no timeouts at all. Unlike Spring's {@code RestClient} — which really does hang
     * forever unconfigured, as inventory's {@code catalogRestClient} did — this one is Apache-based
     * and defaults to connect 1s / socket 30s, so a single call against a wedged cluster is bounded.
     * A single call is not the problem. The pool leases only 10 connections per route
     * ({@code DEFAULT_MAX_CONN_PER_ROUTE}) and nothing bounded the wait for a lease, so concurrent
     * callers queued behind each other in waves of ten, each wave costing a full socket timeout:
     * measured against a socket that accepts and then stays silent, 25 concurrent calls finished in
     * 30s/60s/90s bands. The bulkhead permits 200, and at 200 the same measurement gives a median
     * of 330s and a slowest caller of 601s — ten minutes holding a permit for a cluster that never
     * answered.
     *
     * <p>That is what makes it worth fixing rather than tolerating. The permit is held for the whole
     * queued wait, and {@code catalog-read} is shared with {@code getMatch}, {@code getShowtime} and
     * the no-query browse — none of which touch Elasticsearch at all. A cluster that is merely slow
     * therefore takes the entire public read side to 503, and keeps it there long after the cluster
     * recovers while the backlog drains ten at a time.
     *
     * <p>The connection-request budget is the one that actually collapses the waves: with it, the
     * same 25-call stall finishes in ~3s flat instead of 90s, because callers that cannot get a
     * connection are shed rather than queued. That is the same choice already made explicitly one
     * file over in application.yaml, where the bulkhead's {@code maxWaitDuration} and the rate
     * limiter's {@code timeoutDuration} are both 0 — "shed the excess as a 503 immediately instead
     * of turning the limiter into a second queue". The connection pool was that second queue.
     */
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
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(CONNECT_TIMEOUT_MS)
                        .setSocketTimeout(SOCKET_TIMEOUT_MS)
                        .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS))
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
