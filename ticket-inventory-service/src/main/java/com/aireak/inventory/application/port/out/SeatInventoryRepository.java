package com.aireak.inventory.application.port.out;

import com.aireak.inventory.domain.model.SeatInventory;

import java.util.Optional;

/** Outbound port: SeatInventory persistence. */
public interface SeatInventoryRepository {
    void save(SeatInventory seatInventory);
    Optional<SeatInventory> findByShowtimeId(String showtimeId);
}
