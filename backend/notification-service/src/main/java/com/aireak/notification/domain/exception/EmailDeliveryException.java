package com.aireak.notification.domain.exception;

/**
 * Raised by an {@code EmailSenderPort} implementation when a message could not be handed to the
 * provider. Carries the provider's own status/body where available so the log line is actionable
 * (e.g. Brevo's {@code unauthorized} response for an IP that isn't on the account's allow-list).
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
