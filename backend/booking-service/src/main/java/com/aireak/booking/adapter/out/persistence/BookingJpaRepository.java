package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.domain.model.BookingStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface BookingJpaRepository extends JpaRepository<BookingJpaEntity, String> {
    Optional<BookingJpaEntity> findByIdempotencyKey(String idempotencyKey);

    List<BookingJpaEntity> findByStatusAndInventoryConfirmedFalseAndInventorySaleRefusedFalseAndUpdatedAtBefore(
            BookingStatus status, Instant updatedBefore, Limit limit);

    long countByInventorySaleRefusedTrue();

    List<BookingJpaEntity> findByStatusAndUpdatedAtBefore(
            BookingStatus status, Instant updatedBefore, Limit limit);

    Page<BookingJpaEntity> findByCustomerId(String customerId, Pageable pageable);
    long countByCustomerId(String customerId);

    List<BookingJpaEntity> findByShowtimeIdAndStatusNot(String showtimeId, BookingStatus excludedStatus);
}
