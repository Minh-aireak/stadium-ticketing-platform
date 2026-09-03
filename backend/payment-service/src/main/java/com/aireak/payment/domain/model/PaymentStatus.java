package com.aireak.payment.domain.model;

/** Status of a payment transaction. */
public enum PaymentStatus {
    INITIATED,  // payment request sent to gateway
    SUCCEEDED,  // gateway confirmed success
    FAILED,     // gateway returned failure
    REFUNDED    // charge returned post-sale — see Payment#refund and RefundPaymentUseCase
}
