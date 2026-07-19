package com.aireak.booking.application.port.out;

import java.math.BigDecimal;

/**
 * Outbound port: calls payment-service via REST.
 * Implemented by PaymentRestAdapter with @CircuitBreaker/@Retry.
 */
public interface PaymentPort {
    void initiatePayment(String bookingId, BigDecimal amount, String currency);
}
