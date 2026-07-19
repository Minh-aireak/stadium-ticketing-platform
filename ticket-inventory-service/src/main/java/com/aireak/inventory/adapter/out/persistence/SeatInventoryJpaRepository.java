package com.aireak.inventory.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SeatInventoryJpaRepository extends JpaRepository<SeatInventoryJpaEntity, String> {
    Optional<SeatInventoryJpaEntity> findByShowtimeId(String showtimeId);
}
