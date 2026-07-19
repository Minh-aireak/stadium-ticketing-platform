package com.aireak.payment.application.port.out;

import com.aireak.payment.domain.model.Payment;

import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findByBookingId(String bookingId);
    Optional<Payment> findById(String paymentId);
}
