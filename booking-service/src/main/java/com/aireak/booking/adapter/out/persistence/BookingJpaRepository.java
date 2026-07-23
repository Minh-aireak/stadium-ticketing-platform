package com.aireak.booking.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface BookingJpaRepository extends JpaRepository<BookingJpaEntity, String> {
    Optional<BookingJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
