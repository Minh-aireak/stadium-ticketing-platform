package com.aireak.payment.application.port.in;

import java.time.Instant;

/**
 * Inbound port, card mode: close the payment windows of intents the customer never confirmed.
 * Driven by {@code PaymentWindowExpiryJob}; the decision of what "expired" means (the cutoff)
 * belongs to the caller, the decision of what to do about it belongs here.
 */
public interface ExpireCardPaymentsUseCase {
    /**
     * @param openedBefore card payments still INITIATED that were created before this instant
     * @param batchSize    at most this many in one call, oldest first
     * @return how many were actually expired -- a payment the gateway reports as succeeded or
     *         still processing is recorded as such and not counted
     */
    int closeExpiredWindows(Instant openedBefore, int batchSize);
}
