package com.aireak.payment.adapter.out.gateway;

/**
 * A business-level payment decline (card refused, invalid request) — not a gateway/network
 * fault. Listed in {@code ignoreExceptions} for the {@code payment-gateway} Retry and
 * CircuitBreaker instances so declines neither trigger a pointless retry nor count toward
 * tripping the breaker for unrelated bookings.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }

    public PaymentDeclinedException(String message, Throwable cause) {
        super(message, cause);
    }
}
