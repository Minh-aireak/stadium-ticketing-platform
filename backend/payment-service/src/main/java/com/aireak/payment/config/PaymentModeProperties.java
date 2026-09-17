package com.aireak.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Which side confirms the charge -- see {@code payment.mode} in application.yaml.
 *
 * @param mode {@link Mode#AUTO} (server confirms with the test payment method) or
 *             {@link Mode#CARD} (the customer confirms in the browser)
 * @param card the card-mode window; ignored in auto mode
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentModeProperties(Mode mode, Card card) {

    public PaymentModeProperties {
        if (mode == null) {
            mode = Mode.AUTO;
        }
        if (card == null) {
            card = new Card(8, new ExpiryJob(30_000, 50));
        }
    }

    public boolean isCardMode() {
        return mode == Mode.CARD;
    }

    public Duration window() {
        return Duration.ofMinutes(card.windowMinutes());
    }

    public enum Mode { AUTO, CARD }

    public record Card(long windowMinutes, ExpiryJob expiryJob) {
        public Card {
            if (windowMinutes <= 0) {
                throw new IllegalArgumentException("payment.card.window-minutes must be positive: " + windowMinutes);
            }
            if (expiryJob == null) {
                expiryJob = new ExpiryJob(30_000, 50);
            }
        }
    }

    public record ExpiryJob(long fixedDelayMs, int batchSize) {}
}
