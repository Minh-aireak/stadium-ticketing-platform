package com.aireak.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, String> {
    Optional<PaymentJpaEntity> findByBookingId(String bookingId);
}
