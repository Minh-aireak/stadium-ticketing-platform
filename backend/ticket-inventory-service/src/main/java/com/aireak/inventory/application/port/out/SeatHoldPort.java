package com.aireak.inventory.application.port.out;

import com.aireak.inventory.domain.model.SeatCode;

import java.util.List;
import java.util.Set;

/**
 * Outbound port: temporary (TTL-based) seat holds.
 *
 * <p>Replaces writing a {@code RESERVED} row to Postgres on every reservation
 * attempt. A hold lives only in Redis with an expiry — no DB write on the hot
 * path, and an abandoned hold (payment never completed) self-cleans via TTL
 * instead of needing an explicit release or a cleanup job.
 *
 * <p>Implemented by {@code RedissonSeatHoldAdapter}. Callers are expected to
 * already hold the per-showtime {@code DistributedLockPort} lock — this port
 * does not itself provide cross-request atomicity beyond that.
 */
public interface SeatHoldPort {

    /**
     * Attempts to place a hold on every given seat, all-or-nothing.
     *
     * @throws com.aireak.inventory.domain.exception.SeatsNotAvailableException
     *         if any seat already has an active hold (any previously-placed
     *         holds from this call are rolled back before the exception propagates)
     */
    void holdSeats(String showtimeId, List<SeatCode> seatCodes, String bookingId);

    /** Releases holds owned by {@code bookingId} for the given seats. No-op for seats not held by it. */
    void releaseHolds(String showtimeId, List<SeatCode> seatCodes, String bookingId);

    /**
     * Confirms a pre-booking hold (see {@link #holdSeats}, owner = customerId, placed via the
     * standalone hold endpoint at seat-selection time) into a booking-owned hold, all-or-nothing.
     * For each seat: if it's currently held by {@code previousOwnerId}, hands it over to
     * {@code newOwnerId} (fresh TTL); otherwise (never pre-held, or the pre-hold already expired)
     * falls back to placing a brand-new hold, same as {@link #holdSeats}. Any seat held by a
     * different owner fails the whole call — same rollback semantics as {@link #holdSeats}.
     *
     * @throws com.aireak.inventory.domain.exception.SeatsNotAvailableException
     *         if any seat is currently held by an owner other than {@code previousOwnerId}
     */
    void confirmHold(String showtimeId, List<SeatCode> seatCodes, String previousOwnerId, String newOwnerId);

    /**
     * Batched hold check for a whole seat map — one round trip instead of one per seat (see
     * {@code SeatMapQueryService}, the only caller). Returns the subset of {@code seatCodes}
     * that currently have an active (non-expired) hold, regardless of owner.
     */
    Set<SeatCode> findHeld(String showtimeId, List<SeatCode> seatCodes);
}
