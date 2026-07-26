package com.aireak.booking.application.service;

public class DuplicateRequestInProgressException extends RuntimeException {

    public DuplicateRequestInProgressException(String idempotencyKey) {
        super("Request with Idempotency-Key '" + idempotencyKey + "' is already being processed");
    }
}
