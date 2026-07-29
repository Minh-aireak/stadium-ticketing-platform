package com.aireak.payment.application.port.out;

/**
 * Outbound port: persistence for already-processed Stripe webhook event ids.
 * Stripe retries a webhook delivery until it receives a 2xx response, so the same
 * event id can arrive more than once — this makes reconciliation idempotent.
 */
public interface ProcessedWebhookEventRepository {

    /**
     * @param eventId the Stripe event id (e.g. {@code evt_1AbC...})
     * @return true if this event was already processed
     */
    boolean existsByEventId(String eventId);

    /**
     * Marks an event as processed.
     *
     * @param eventId   the Stripe event id
     * @param eventType the Stripe event type (e.g. {@code payment_intent.succeeded}), for auditing
     */
    void markProcessed(String eventId, String eventType);
}
