package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.event.SeatsSoldEvent;

import java.util.*;

/**
 * Aggregate Root: SeatInventory — one per showtime.
 *
 * <p><strong>Core invariant</strong>: no double-booking. {@code sellSeats()} is the only
 * mutating operation left on this aggregate — reserve/release now live entirely in Redis
 * (see {@code SeatHoldPort}) and never touch this aggregate at all.
 *
 * <p>Concurrency protection:
 * <ul>
 *   <li>Layer 1: Redisson RLock at application service layer (before entering this method)</li>
 *   <li>Layer 2: {@code @Version} on JPA entity (optimistic lock)</li>
 * </ul>
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
        seatCodes.stream()
                .map(seats::get)
                .filter(Objects::nonNull)
                .forEach(seat -> seat.sell(bookingId));
        domainEvents.add(new SeatsSoldEvent(showtimeId, seatCodes));
    }

    public String getShowtimeId()         { return showtimeId; }
    public Collection<Seat> getSeats()    { return Collections.unmodifiableCollection(seats.values()); }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }
}
