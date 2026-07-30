package com.aireak.payment.application.port.in;

import com.aireak.payment.domain.model.Payment;

import java.util.Optional;

/** Inbound port: look up a payment by booking or by payment id. */
public interface GetPaymentUseCase {
    Optional<Payment> getByBookingId(String bookingId);
    Optional<Payment> getById(String paymentId);
}
