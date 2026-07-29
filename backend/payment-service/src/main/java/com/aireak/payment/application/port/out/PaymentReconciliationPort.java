package com.aireak.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Outbound port: durable holding area for payments whose gateway charge succeeded but whose
 * SUCCEEDED outcome could not be persisted to {@link PaymentRepository} even after retrying.
 * Implemented by {@code UnreconciledPaymentPersistenceAdapter}.
 */
public interface PaymentReconciliationPort {

    /**
     * Records a payment stuck between "gateway charged the customer" and "our own DB reflects
     * that" for manual reconciliation — the gateway transaction is real and must not be treated
     * as failed.
     */
    void recordUnpersistedSuccess(String paymentId, String bookingId, String gatewayTransactionId,
                                   BigDecimal amount, String currency, String failureReason);
}
