package com.aireak.inventory.adapter.out.persistence;

import com.aireak.inventory.domain.model.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Read-only lookup on the {@code seats} child table, bypassing the
 * {@code SeatInventoryJpaEntity} aggregate (and its {@code EAGER} full-seat-list fetch).
 * Used on the reserve hot path, which only needs to know which of a handful of requested
 * seat codes are SOLD — not the showtime's entire seat list.
 */
interface SeatJpaRepository extends JpaRepository<SeatJpaEntity, Long> {

    List<SeatJpaEntity> findByShowtimeIdAndSeatCodeInAndStatus(
            String showtimeId, List<String> seatCodes, SeatStatus status);
}
