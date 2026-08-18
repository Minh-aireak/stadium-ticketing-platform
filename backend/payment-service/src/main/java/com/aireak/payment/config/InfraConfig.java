package com.aireak.payment.config;

import com.aireak.common.web.client.CorrelationIdRequestInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class InfraConfig {

    /**
     * RestClient for outbound calls to booking-service (ownership verification).
     * Short timeouts: this is a lightweight check that should fail fast — a stalled
     * booking-service must not block the payment request indefinitely.
     */
    @Bean
    public RestClient restClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                // Forwards X-Correlation-Id from the MDC, so the callee logs under the
                // caller's correlation ID instead of minting a new one.
                .requestInitializer(new CorrelationIdRequestInitializer())
                .build();
    }
}
