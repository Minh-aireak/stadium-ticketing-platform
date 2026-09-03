package com.aireak.payment.domain.exception;

import com.aireak.common.exception.DomainException;

/**
 * Thrown when a payment is requested for an amount or currency that differs from the one
 * booking-service recorded for the booking.
 *
 * <p>The client-facing message is deliberately vague about the expected figure: the caller owns
 * the booking and can read its amount from booking-service directly, so echoing it here adds
 * nothing, while a mismatch is far more likely to be tampering than a typo.
 */
public class PaymentAmountMismatchException extends DomainException {
    public PaymentAmountMismatchException(String message) {
        super(message);
    }
}
