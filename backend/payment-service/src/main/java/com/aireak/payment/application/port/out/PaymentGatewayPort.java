package com.aireak.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Outbound port: calls external payment gateway (VNPay, Stripe, etc.).
 * Implemented by PaymentGatewayAdapter with @CircuitBreaker/@Retry.
 */
public interface PaymentGatewayPort {
    /** @return gatewayTransactionId on success */
    String charge(String bookingId, BigDecimal amount, String currency);

    /** @return gatewayRefundId on success */
    String refund(String gatewayTransactionId, BigDecimal amount, String currency);
}
