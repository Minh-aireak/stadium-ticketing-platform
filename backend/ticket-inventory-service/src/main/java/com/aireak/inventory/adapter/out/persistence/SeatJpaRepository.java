package com.aireak.inventory.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Read-only lookup on the {@code seats} child table, bypassing the
 * {@code SeatInventoryJpaEntity} aggregate (and its {@code EAGER} full-seat-list fetch).
 * Used on the reserve hot path, which only needs a handful of requested seat codes —
 * their status (to reject already-SOLD ones) and price (to compute the total) —
 * not the showtime's entire seat list.
 */
interface SeatJpaRepository extends JpaRepository<SeatJpaEntity, Long> {

    List<SeatJpaEntity> findByShowtimeIdAndSeatCodeIn(String showtimeId, List<String> seatCodes);
}
