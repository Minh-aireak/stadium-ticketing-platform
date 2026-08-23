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

    /**
     * The confirm-sale counterpart of {@link #findSeatsByCodes}: returns the aggregate carrying
     * ONLY {@code seatCodes}, so selling two seats in a 540-seat stadium costs two rows rather
     * than the whole seat map.
     *
     * <p><strong>A deliberately partial aggregate.</strong> {@link SeatInventory} has no
     * invariant that spans seats — {@code Seat#sell} decides on that one seat's own state, and
     * two confirms for the same showtime are serialized by the Redisson lock, not by anything
     * this aggregate holds. So loading a subset costs no correctness here. It does mean
     * {@code getSeats()} on the result is NOT the full map: use {@link #findByShowtimeId} for
     * anything that needs to see every seat, such as rendering one.
     *
     * @return empty if no inventory exists for the showtime at all — distinct from an inventory
     *         where none of {@code seatCodes} match, which returns an aggregate with no seats
     */
    Optional<SeatInventory> findByShowtimeIdWithSeats(String showtimeId, List<SeatCode> seatCodes);

    /**
     * Writes the status/booking of exactly the seats the given aggregate carries. Pairs with
     * {@link #findByShowtimeIdWithSeats}: unlike {@link #save} it never touches the aggregate
     * root row, and never reads the seats it is not about to change.
     */
    void saveSeats(SeatInventory seatInventory);
}
