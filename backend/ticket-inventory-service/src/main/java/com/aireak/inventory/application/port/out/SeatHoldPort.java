package com.aireak.inventory.application.port.out;

import com.aireak.inventory.domain.model.SeatCode;

import java.util.List;
import java.util.Map;

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
     * Batched hold lookup for a whole seat map — one round trip instead of one per seat (see
     * {@code SeatMapQueryService}, the only caller). Returns an entry for each of
     * {@code seatCodes} that currently has an active (non-expired) hold, mapped to the id of the
     * <em>customer</em> who owns it — for a hold already confirmed into a booking (see
     * {@link #confirmHold}) that is the customer component, not the bookingId.
     *
     * <p>Returned the held subset alone until the seat map needed to tell a customer's own hold
     * from someone else's. It could not: every hold rendered identically as "someone is
     * mid-checkout", so a customer who reloaded the seat page — or backed into it out of
     * checkout — saw the seats they were still holding greyed out as another shopper's, with no
     * way to select them and nothing to do but wait out the TTL.
     */
    Map<SeatCode, String> findHoldOwners(String showtimeId, List<SeatCode> seatCodes);

    /**
     * True unless some seat in {@code seatCodes} currently has an active hold confirmed (see
     * {@link #confirmHold}) into a <em>different</em> customer/booking than the one given —
     * i.e. the caller is not trying to release a reservation it doesn't own. A seat with no
     * active hold at all (already released, or its TTL expired) passes vacuously, matching
     * {@link #releaseHolds}'s own idempotent no-op semantics for such seats.
     *
     * <p>Used by the customer-token release path (see {@code ReleaseSeatsCommand}) to stop a
     * customer from releasing another customer's active reservation just by knowing or guessing
     * its bookingId — the bookingId path variable alone is not proof of ownership.
     *
     * <p><strong>Not an ownership predicate, and deliberately not named like one.</strong> It was
     * previously called {@code isHeldByCustomerAndBooking}, which reads as "these seats are held
     * by this customer" — a claim it does not make and must never be used for, since a caller
     * holding nothing at all also gets {@code true}. That is correct for release (there is
     * nothing to protect) and wrong for anything that needs proof of possession; the name now
     * says which of the two it is.
     */
    boolean isFreeOfHoldsByOtherOwners(String showtimeId, List<SeatCode> seatCodes, String customerId,
                                       String bookingId);
}
