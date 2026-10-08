package com.aireak.payment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One refund applied to a payment, keyed by the id of the request that asked for it — which is what
 * lets a redelivered request be recognised and skipped once a payment can be refunded in parts.
 */
public record RefundRecord(String refundRequestId, BigDecimal amount, String gatewayRefundId, String reason,
                           Instant refundedAt) {

    public RefundRecord {
        Objects.requireNonNull(refundRequestId, "refundRequestId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(gatewayRefundId, "gatewayRefundId must not be null");
    }
}
