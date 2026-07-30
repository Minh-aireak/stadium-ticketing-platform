package com.aireak.payment.application.port.out;

import com.aireak.payment.domain.model.UnreconciledPayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    /**
     * Retrieves unresolved unreconciled payment records created before the specified cutoff timestamp.
     *
     * @param cutoff timestamp threshold
     * @param limit maximum number of records to return
     * @return list of unresolved unreconciled payments
     */
    List<UnreconciledPayment> findUnresolvedOlderThan(Instant cutoff, int limit);
}

