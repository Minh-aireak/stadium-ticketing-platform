package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.event.SeatsReturnedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;

import java.util.*;

/**
 * Aggregate Root: SeatInventory — one per showtime.
 *
 * <p><strong>Core invariant</strong>: no double-booking. {@code sellSeats()} and its inverse,
 * {@code returnSeats()} (a paid booking cancelling seats), are the only mutating operations left on
 * this aggregate — reserve/release now live entirely in Redis (see {@code SeatHoldPort}) and never
 * touch this aggregate at all.
 *
 * <p>Concurrency protection:
 * <ul>
 *   <li>Redisson RLock at the application service layer, held across the whole confirm — this is
 *       what serializes two confirms for the same showtime</li>
 *   <li>{@code Seat#sell} itself, which rejects a seat already SOLD by a DIFFERENT booking, so a
 *       lost or expired hold cannot let two bookings both win the same seat unnoticed</li>
 * </ul>
 * A JPA {@code @Version} on the aggregate root used to be listed here as a second layer; it never
 * incremented on this path and was removed — see {@code SeatInventoryJpaEntity}.
 */
public class SeatInventory {

    private final String showtimeId;
    private final Map<SeatCode, Seat> seats;
    private final List<Object> domainEvents = new ArrayList<>();

    private SeatInventory(String showtimeId, Map<SeatCode, Seat> seats) {
        this.showtimeId = Objects.requireNonNull(showtimeId);
        this.seats = new LinkedHashMap<>(seats);
    }

    /** Factory: create a new inventory for a showtime from already-built seats (see {@link SeatMapLayout}). */
    public static SeatInventory create(String showtimeId, List<Seat> seats) {
        Map<SeatCode, Seat> bySeatCode = new LinkedHashMap<>();
        seats.forEach(seat -> bySeatCode.put(seat.getSeatCode(), seat));
        return new SeatInventory(showtimeId, bySeatCode);
    }

    /** Reconstitute from persistence. */
    public static SeatInventory reconstitute(String showtimeId, List<Seat> seatList) {
        Map<SeatCode, Seat> seats = new LinkedHashMap<>();
        seatList.forEach(s -> seats.put(s.getSeatCode(), s));
        return new SeatInventory(showtimeId, seats);
    }

    // ----------------------------------------------------------------
    // Domain behavior
    // ----------------------------------------------------------------

    /**
     * Marks reserved seats as SOLD for {@code bookingId} (on payment success).
     * Idempotent per-booking; throws {@link com.aireak.inventory.domain.exception.SeatAlreadySoldException}
     * if a seat is already SOLD to a different booking (see {@link Seat#sell}).
     */
    public void sellSeats(List<SeatCode> seatCodes, String bookingId) {
        List<SeatCode> newlySold = seatCodes.stream()
                .map(seats::get)
                .filter(Objects::nonNull)
                .filter(seat -> seat.sell(bookingId))
                .map(Seat::getSeatCode)
                .toList();
        if (!newlySold.isEmpty()) {
            domainEvents.add(new SeatsSoldEvent(showtimeId, newlySold));
        }
    }

    /**
     * Puts back on sale the seats of {@code seatCodes} that are SOLD to {@code bookingId} — its
     * customer cancelled them. Idempotent: a second call finds them AVAILABLE and changes nothing.
     * Raises {@link SeatsReturnedEvent} for exactly the seats that changed, if any did.
     *
     * @return the seats that went from SOLD to AVAILABLE
     */
    public List<SeatCode> returnSeats(List<SeatCode> seatCodes, String bookingId) {
        List<SeatCode> returned = seatCodes.stream()
                .map(seats::get)
                .filter(Objects::nonNull)
                .filter(seat -> seat.returnToSale(bookingId))
                .map(Seat::getSeatCode)
                .toList();
        if (!returned.isEmpty()) {
            domainEvents.add(new SeatsReturnedEvent(showtimeId, bookingId, returned));
        }
        return returned;
    }

    public String getShowtimeId()         { return showtimeId; }
    public Collection<Seat> getSeats()    { return Collections.unmodifiableCollection(seats.values()); }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }
}
