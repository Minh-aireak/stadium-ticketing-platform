package com.aireak.payment.domain.exception;

import com.aireak.common.exception.DomainException;

public class InvalidPaymentStatusException extends DomainException {
    public InvalidPaymentStatusException(String message) { super(message); }
}
