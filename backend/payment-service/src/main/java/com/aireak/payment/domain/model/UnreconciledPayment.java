package com.aireak.payment.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain representation of an unreconciled payment record stored when gateway charge succeeded
 * but internal persistence failed.
 */
@Getter
@AllArgsConstructor
public class UnreconciledPayment {
    private final UUID id;
    private final String paymentId;
    private final String bookingId;
    private final String gatewayTransactionId;
    private final BigDecimal amount;
    private final String currency;
    private final String failureReason;
    private final boolean resolved;
    private final Instant createdAt;
}
