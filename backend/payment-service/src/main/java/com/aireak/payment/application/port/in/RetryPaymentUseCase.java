package com.aireak.payment.application.port.in;

import java.util.Optional;

/**
 * Inbound port: retry a FAILED payment on its existing row (see {@code Payment#retry}) and
 * re-attempt the gateway charge. Called by an operator/customer action, not part of the
 * booking-creation saga.
 */
public interface RetryPaymentUseCase {
    /** @return the paymentId, or empty if no payment exists for paymentId */
    Optional<String> retry(String paymentId);
}
