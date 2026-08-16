package com.aireak.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brevo (formerly Sendinblue) Transactional Email API settings, bound from
 * {@code BREVO_API_KEY} / {@code BREVO_SENDER_EMAIL} / {@code BREVO_SENDER_NAME} (see
 * application.yaml). None of these have defaults — the service fails to start rather than
 * silently sending from a placeholder address or with no credentials.
 *
 * <p>{@code senderEmail} must be a sender Brevo has verified for the account
 * (Dashboard &gt; Senders, Domains &amp; Dedicated IPs), otherwise every send is rejected.
 */
@ConfigurationProperties(prefix = "brevo")
public record BrevoProperties(
        String apiKey,
        String senderEmail,
        String senderName,
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
