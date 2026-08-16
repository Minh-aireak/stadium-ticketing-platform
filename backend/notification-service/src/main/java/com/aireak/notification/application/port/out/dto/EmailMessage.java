package com.aireak.notification.application.port.out.dto;

import java.util.Objects;

/**
 * One outbound transactional email, in the shape every {@code EmailSenderPort} implementation
 * needs regardless of provider.
 *
 * <p>{@code textBody} is not optional: a text/plain alternative alongside the HTML is what keeps
 * the message readable in text-only clients and materially improves deliverability (HTML-only
 * mail scores worse with spam filters). {@code toName} may be null when the recipient's display
 * name isn't known.
 */
public record EmailMessage(
        String to,
        String toName,
        String subject,
        String htmlBody,
        String textBody
) {
    public EmailMessage {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(htmlBody, "htmlBody must not be null");
        Objects.requireNonNull(textBody, "textBody must not be null");
    }
}
