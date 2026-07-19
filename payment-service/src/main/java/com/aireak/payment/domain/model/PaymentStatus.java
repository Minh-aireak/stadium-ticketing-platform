package com.aireak.payment.domain.model;

/** Status of a payment transaction. */
public enum PaymentStatus {
    INITIATED,  // payment request sent to gateway
    SUCCEEDED,  // gateway confirmed success
    FAILED,     // gateway returned failure
    REFUNDED    // post-sale refund (future)
}
