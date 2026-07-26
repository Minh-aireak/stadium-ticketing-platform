package com.aireak.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification Service — Kafka consumer, idempotent event processing,
 * email/SMS dispatch for booking and identity events.
 */
@SpringBootApplication(scanBasePackages = {"com.aireak.notification", "com.aireak.common"})
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
