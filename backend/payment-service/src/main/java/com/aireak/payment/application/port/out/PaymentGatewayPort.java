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
}
