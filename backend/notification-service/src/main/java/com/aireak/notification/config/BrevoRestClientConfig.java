package com.aireak.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The {@link RestClient} used to call Brevo's Transactional Email API. Timeouts are short and
 * explicit: a stalled provider must not pin a Kafka listener thread — a failed send is logged and
 * dropped (see {@code TransactionalEmailService}), which is strictly better than blocking
 * consumption of every subsequent event behind it.
 */
@Configuration
public class BrevoRestClientConfig {

    // RestClient.builder(), not an injected RestClient.Builder bean: Boot 4.1 does not
    // auto-configure one here (spring-boot-starter-web alone no longer brings it), and this client
    // needs nothing from the application-wide defaults anyway. Mirrors payment-service's InfraConfig.
    @Bean
    public RestClient brevoRestClient(BrevoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-key", properties.apiKey())
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
