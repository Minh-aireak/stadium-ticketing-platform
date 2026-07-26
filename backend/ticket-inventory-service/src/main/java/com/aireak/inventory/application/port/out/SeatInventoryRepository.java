package com.aireak.inventory.application.port.out;

import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;

import java.util.List;
import java.util.Optional;

/** Outbound port: SeatInventory persistence. */
public interface SeatInventoryRepository {
    void save(SeatInventory seatInventory);
    Optional<SeatInventory> findByShowtimeId(String showtimeId);
    boolean existsByShowtimeId(String showtimeId);

    /**
     * Targeted lookup for the reserve hot path: which of {@code seatCodes} are currently SOLD.
     * Queries the {@code seats} table directly instead of loading the full (EAGER-fetched)
     * {@link SeatInventory} aggregate, so it stays cheap under a per-showtime lock even when
     * the showtime has thousands of seats.
     */
    List<SeatCode> findSoldSeatCodes(String showtimeId, List<SeatCode> seatCodes);
}
