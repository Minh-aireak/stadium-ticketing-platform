package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;

import java.util.*;

/**
 * Aggregate Root: SeatInventory — one per showtime.
 *
 * <p><strong>Core invariant</strong>: no double-booking.
 * {@code reserveSeats()} atomically checks availability for ALL requested seats
 * before mutating any — all-or-nothing semantics within the aggregate.
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

    /** Factory: create a new inventory for a showtime with given seat codes. */
    public static SeatInventory create(String showtimeId, List<SeatCode> seatCodes) {
        Map<SeatCode, Seat> seats = new LinkedHashMap<>();
        seatCodes.forEach(code -> seats.put(code, new Seat(code)));
        return new SeatInventory(showtimeId, seats);
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
     * Reserves a list of seats for a booking.
     * All-or-nothing: if ANY seat is unavailable, throws without mutating state.
     *
     * @throws SeatsNotAvailableException if any seat is not AVAILABLE
     */
    public void reserveSeats(List<SeatCode> seatCodes, String bookingId) {
        // Check ALL before mutating ANY
        List<SeatCode> unavailable = seatCodes.stream()
                .filter(code -> {
                    Seat seat = seats.get(code);
                    return seat == null || !seat.isAvailable();
                })
                .toList();

        if (!unavailable.isEmpty()) {
            throw new SeatsNotAvailableException(showtimeId, unavailable);
        }

        seatCodes.forEach(code -> seats.get(code).reserve(bookingId));
        domainEvents.add(new SeatsReservedEvent(showtimeId, bookingId, seatCodes));
    }

    /**
     * Releases previously reserved seats (on payment failure or timeout).
     */
    public void releaseSeats(List<SeatCode> seatCodes) {
        seatCodes.stream()
                .map(seats::get)
                .filter(Objects::nonNull)
                .filter(s -> s.getStatus() == SeatStatus.RESERVED)
                .forEach(Seat::release);
        domainEvents.add(new SeatsReleasedEvent(showtimeId, seatCodes));
    }

    /**
     * Marks reserved seats as SOLD (on payment success).
     */
    public void sellSeats(List<SeatCode> seatCodes) {
        seatCodes.stream()
                .map(seats::get)
                .filter(Objects::nonNull)
                .forEach(Seat::sell);
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
