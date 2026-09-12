package com.aireak.booking.application.port.out;

import java.math.BigDecimal;

// Outbound port: calls payment-service via REST (PaymentRestAdapter, with @CircuitBreaker/@Retry).
public interface PaymentPort {
    void initiatePayment(String bookingId, BigDecimal amount, String currency);

    // Reconciliation query for an initiatePayment whose outcome this side never learned
    // (timeout/reset/circuit-open), and for the scheduled job that sweeps PENDING_PAYMENT bookings.
    // Never throws for a failed lookup: that is UNKNOWN, and the caller decides how patient to be.
    PaymentOutcome checkOutcome(String bookingId);

    enum PaymentOutcome {
        // Terminal, per payment-service's own row. The only two the saga acts on synchronously.
        SUCCEEDED,
        FAILED,
        // payment-service has a row for this booking and is still working it (INITIATED, or any
        // status this side does not recognise). Something will arrive; wait.
        IN_FLIGHT,
        // payment-service answered, and has no row for this booking. PaymentService#execute commits
        // its INITIATED row before it ever calls the gateway, so this is not "charged but not yet
        // written" — the only way to see it is that no initiate request ever reached that commit.
        // The window in which a request is on its way there is milliseconds; a booking that still
        // gets this answer minutes later has no payment and never will, and only the scheduled job
        // acts on it (BookingReconciliationJob#abandonAfterMinutes) — never the saga in-line.
        // This used to be folded into "unknown" on the strength of a comment claiming the row was
        // committed after the gateway call, which left every such booking PENDING_PAYMENT forever.
        NOT_FOUND,
        // The question could not be asked or answered: transport failure, 5xx, 401/403, empty body.
        // Says nothing about the payment. Retry later; never compensate on it.
        UNKNOWN
    }
}
