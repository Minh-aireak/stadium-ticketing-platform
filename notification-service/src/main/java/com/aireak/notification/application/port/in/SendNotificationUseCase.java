package com.aireak.notification.application.port.in;

/**
 * Inbound port: use case for dispatching a notification.
 * Driving adapters (Kafka consumers) call this.
 */
public interface SendNotificationUseCase {

    /**
     * Sends a notification based on the event type and payload.
     *
     * @param eventId   idempotency key — duplicate calls with same eventId are no-ops
     * @param eventType the Kafka topic-derived event type (e.g. "booking.booking.confirmed")
     * @param payload   event payload object for template variable substitution
     */
    void send(String eventId, String eventType, Object payload);
}
