package com.aireak.inventory.config;

import com.aireak.common.web.client.CorrelationIdRequestInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// Domain events go out via the Postgres outbox (see adapter.out.persistence.outbox) and
// Debezium CDC now — no direct Kafka producer beans needed here anymore.
@Configuration
@EnableJpaAuditing
public class InfraConfig {

    /**
     * Backs {@code ShowtimeCatalogRestAdapter#requireBookable}, which every seat hold and every
     * reservation calls before it does anything else.
     *
     * <p>Was built with no request factory at all, which means no read timeout: a
     * match-catalog-service that accepts the connection and then never answers (hung, not down —
     * down fails fast with a connection refusal) blocks the calling thread indefinitely. That is
     * worse here than the lost request itself, because {@code requireBookable} runs while holding
     * a {@code @Bulkhead(name = "seat-inventory")} permit: 100 stalled calls exhaust the semaphore
     * permanently and every subsequent hold and reserve is rejected with 503, platform-wide, with
     * no recovery until the far side answers. The bulkhead exists precisely to stop threads piling
     * up, and an unbounded read is how it gets defeated.
     *
     * <p>Budget is deliberately tighter than booking-service's 3s/5s: this call sits in front of
     * the contended buy path, not behind an already-ambiguous outcome, so failing fast and telling
     * the customer to retry beats holding their request open.
     */
    @Bean(name = "catalogRestClient")
    public RestClient catalogRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(requestFactory)
                // Forwards X-Correlation-Id from the MDC, so match-catalog-service logs the
                // showtime lookup under the correlation ID of the hold request that caused it.
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }
}
