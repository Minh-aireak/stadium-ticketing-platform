package com.aireak.payment.application.port.in.command;

/**
 * Plain command translated from a Stripe {@code Event}/{@code PaymentIntent} by
 * {@code StripeWebhookController} — keeps the Stripe SDK types out of the application layer.
 *
 * @param eventId        Stripe event id, used for idempotency
 * @param eventType      Stripe event type, e.g. {@code payment_intent.succeeded}
 * @param bookingId      from the PaymentIntent's {@code bookingId} metadata (set by StripeGatewayAdapter)
 * @param paymentIntentId the Stripe PaymentIntent id (gateway transaction id)
 * @param failureMessage the last payment error message, only set for a failed PaymentIntent
 */
public record StripeWebhookEventCommand(
        String eventId,
        String eventType,
        String bookingId,
        String paymentIntentId,
        String failureMessage
) {}
