package com.aireak.booking.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

// Outbound port: calls payment-service via REST (PaymentRestAdapter, with @CircuitBreaker/@Retry).
public interface PaymentPort {
    void initiatePayment(String bookingId, BigDecimal amount, String currency);

    // Reconciliation query for an ambiguous initiatePayment outcome (timeout/reset/circuit-open).
    // empty() means still unknown — NEVER treat as "safe to compensate": payment-service only
    // commits its row after the gateway call returns, so a 404 can just mean "not committed yet".
    Optional<PaymentOutcome> checkOutcome(String bookingId);

    // The only two terminal states worth acting on synchronously.
    enum PaymentOutcome { SUCCEEDED, FAILED }
}
