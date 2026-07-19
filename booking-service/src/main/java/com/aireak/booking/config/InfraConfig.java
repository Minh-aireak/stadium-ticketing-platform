package com.aireak.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

/**
 * Infrastructure configuration for booking-service.
 * Enables JPA auditing and provides RestClient bean for outbound REST calls.
 */
@Configuration
@EnableJpaAuditing
public class InfraConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
