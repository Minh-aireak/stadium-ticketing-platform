package com.aireak.inventory.application.port.out;

import com.aireak.inventory.domain.model.Seat;
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
     * Targeted lookup for the reserve hot path: fetches only the requested {@code seatCodes}
     * (with their status/tier/price) instead of loading the full (EAGER-fetched)
     * {@link SeatInventory} aggregate, so it stays cheap under a per-showtime lock even when
     * the showtime has thousands of seats. Used both to reject already-SOLD seats and to
     * compute the authoritative total price from each seat's tier — never trust a
     * client-supplied amount for that.
     */
    List<Seat> findSeatsByCodes(String showtimeId, List<SeatCode> seatCodes);
}
