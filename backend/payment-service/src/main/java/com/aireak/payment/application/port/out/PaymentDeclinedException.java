package com.aireak.payment.application.port.out;

/**
 * A business-level payment decline (card refused, invalid request) — not a gateway/network
 * fault. Listed in {@code ignoreExceptions} for the {@code payment-gateway} Retry and
 * CircuitBreaker instances so declines neither trigger a pointless retry nor count toward
 * tripping the breaker for unrelated bookings.
 *
 * <p>Part of {@link PaymentGatewayPort}'s contract rather than the Stripe adapter's, because
 * {@code PaymentService} has to distinguish a decline from a gateway fault to decide the saga's
 * next step. Living in the adapter package made the application layer import downward into an
 * adapter — the one dependency direction hexagonal architecture forbids — and would have tied
 * that decision to Stripe specifically, when any gateway can decline a card.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }

    public PaymentDeclinedException(String message, Throwable cause) {
        super(message, cause);
    }
}
