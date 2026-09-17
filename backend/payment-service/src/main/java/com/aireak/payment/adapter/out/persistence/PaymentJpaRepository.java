package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.domain.model.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, String> {
    Optional<PaymentJpaEntity> findByBookingId(String bookingId);

    // Card payments still waiting on the customer past the payment window -- what
    // PaymentWindowExpiryJob cancels. clientSecret IS NOT NULL is the card-mode discriminator
    // (Payment#isCardMode); an auto-mode INITIATED row is a charge in flight and must be left alone.
    List<PaymentJpaEntity> findByStatusAndClientSecretIsNotNullAndCreatedAtBefore(
            PaymentStatus status, Instant cutoff, Pageable pageable);
}
