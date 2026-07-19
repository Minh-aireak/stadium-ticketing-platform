package com.aireak.booking.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface BookingJpaRepository extends JpaRepository<BookingJpaEntity, String> {
}
