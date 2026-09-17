package com.aireak.payment.application.port.out;

import com.aireak.payment.domain.model.Payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
    void save(Payment payment);

    /**
     * Inserts a new payment. Returns false if a payment for this bookingId
     * already exists (unique constraint) instead of throwing — used as the
     * DB-level idempotency backstop when the Redis guard fails open.
     */
    boolean tryInsert(Payment payment);

    Optional<Payment> findByBookingId(String bookingId);
    Optional<Payment> findById(String paymentId);
    /**
     * Card-mode payments still INITIATED that were created before {@code cutoff} -- the ones whose
     * payment window has closed. Oldest first, at most {@code limit}, so one run of the expiry job
     * has a bounded number of gateway calls to make.
     */
    List<Payment> findOpenCardPaymentsCreatedBefore(Instant cutoff, int limit);
}
