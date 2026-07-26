package com.aireak.payment.domain.exception;

import com.aireak.common.exception.DomainException;

public class DuplicatePaymentException extends DomainException {
    public DuplicatePaymentException(String message) { super(message); }
}
