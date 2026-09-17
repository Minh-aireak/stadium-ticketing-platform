package com.aireak.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Service — Bounded Context: Payment.
 * Integrates with external payment gateway, publishes payment result events to Kafka.
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.aireak.payment", "com.aireak.common"})
@ConfigurationPropertiesScan
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
