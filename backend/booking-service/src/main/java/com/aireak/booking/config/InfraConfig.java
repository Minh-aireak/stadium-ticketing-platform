package com.aireak.booking.config;

import com.aireak.common.web.client.CorrelationIdRequestInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableJpaAuditing
public class InfraConfig {

    // @Primary: no -parameters compile flag here, so unqualified RestClient injection
    // can't resolve by param name — without it, adding paymentStatusRestClient below
    // makes every such injection point ambiguous at startup.
    @Primary
    @Bean
    public RestClient restClient() {
        // Explicit timeouts: a slow (not down) downstream would otherwise hang the thread
        // forever, and adapter @CircuitBreaker/@Retry never gets a chance to act.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                // Forwards X-Correlation-Id from the MDC, so the callee logs under the
                // caller's correlation ID instead of minting a new one.
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }

    // For ShowtimeScheduleRestAdapter: one read inside a customer's cancel request, so the same
    // fail-fast budget ticket-inventory-service gives its own catalog lookups.
    @Bean
    public RestClient catalogRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }

    // The cancellation deadline is a comparison against "now"; a bean, so a test can fix it.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // For reconciliation lookups (PaymentPort#checkOutcome) after an already-ambiguous
    // call — should fail fast, not stack the main client's 3s/5s budget on top.
    @Bean
    public RestClient paymentStatusRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(2));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                // Forwards X-Correlation-Id from the MDC, so the callee logs under the
                // caller's correlation ID instead of minting a new one.
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }
}
