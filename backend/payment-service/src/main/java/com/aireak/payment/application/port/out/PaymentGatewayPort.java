package com.aireak.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Outbound port: calls external payment gateway (VNPay, Stripe, etc.).
 * Implemented by PaymentGatewayAdapter with @CircuitBreaker/@Retry.
 */
public interface PaymentGatewayPort {
    /**
     * @param idempotencyKey what the gateway deduplicates this charge by. Constant across the
     *        adapter's own {@code @Retry} attempts, so a lost response can never become a second
     *        charge; different between distinct user-initiated attempts, so a retry is not answered
     *        with the reply the attempt before it got. See {@code Payment#chargeIdempotencyKey}
     *        for which of those two a given retry needs.
     * @return gatewayTransactionId on success
     */
    String charge(String idempotencyKey, String bookingId, BigDecimal amount, String currency);

    /** @return gatewayRefundId on success */
    String refund(String gatewayTransactionId, BigDecimal amount, String currency);

    // ---- card mode: the customer confirms in the browser, this side only opens and closes ----

    /**
     * Opens a charge for the customer to confirm with a card of their own -- no payment method,
     * no confirmation, no money moves. Same idempotency contract as {@link #charge}.
     *
     * @return the gateway's id for the intent plus the client secret Stripe.js needs to confirm it
     */
    IntentHandle createIntent(String idempotencyKey, String bookingId, BigDecimal amount, String currency);

    /** Asks the gateway what became of an intent {@link #createIntent} opened. Read-only. */
    IntentSnapshot retrieveIntent(String gatewayIntentId);

    /**
     * Closes an intent the customer never completed. The gateway refuses to cancel an intent that
     * has already succeeded, and that refusal is an answer, not an error: the snapshot then says
     * SUCCEEDED and the caller records the success instead of the expiry.
     */
    IntentSnapshot cancelIntent(String gatewayIntentId);

    record IntentHandle(String gatewayIntentId, String clientSecret) {}

    /**
     * @param outcome        what the gateway says about the intent now
     * @param failureMessage the last confirmation attempt's error, when the gateway reported one
     */
    record IntentSnapshot(IntentOutcome outcome, String failureMessage) {}

    enum IntentOutcome {
        /** Confirmed and captured: the customer has been charged. */
        SUCCEEDED,
        /** Cancelled at the gateway; can never succeed. */
        CANCELED,
        /** Still confirmable (never confirmed, declined and waiting for another card, or awaiting 3-D Secure). */
        OPEN,
        /** Confirmed and being processed by the bank; will land as one of the above. Leave alone. */
        PROCESSING
    }
}
