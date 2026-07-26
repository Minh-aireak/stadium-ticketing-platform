package com.aireak.payment.application.port.in;

import com.aireak.payment.domain.model.Payment;

import java.util.Optional;

/** Inbound port: look up a payment by the booking it belongs to. */
public interface GetPaymentUseCase {
    Optional<Payment> getByBookingId(String bookingId);
}
