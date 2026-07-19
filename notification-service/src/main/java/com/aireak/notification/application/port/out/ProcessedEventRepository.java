package com.aireak.notification.application.port.out;

/**
 * Outbound port: persistence for already-processed event IDs.
 * Used to implement idempotent Kafka consumer — duplicate messages are skipped.
 */
public interface ProcessedEventRepository {

    /**
     * @param eventId the event ID to check
     * @return true if this event was already processed successfully
     */
    boolean existsByEventId(String eventId);

    /**
     * Marks an event as processed. Must be persisted in the same transaction
     * as the notification delivery outcome to avoid phantom re-processing.
     *
     * @param eventId  the Kafka event envelope's eventId
     * @param eventType the event type for auditing
     */
    void markProcessed(String eventId, String eventType);
}
