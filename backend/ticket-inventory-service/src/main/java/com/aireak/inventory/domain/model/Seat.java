package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.exception.SeatAlreadySoldException;

import java.util.Objects;

/**
 * Entity within the SeatInventory aggregate.
 * Represents one physical seat for a specific showtime.
 */
public class Seat {

    private final SeatCode seatCode;
    private SeatStatus status;
    private String reservedByBookingId; // null when AVAILABLE; the owning booking once RESERVED or SOLD

    public Seat(SeatCode seatCode) {
        this.seatCode = Objects.requireNonNull(seatCode);
        this.status = SeatStatus.AVAILABLE;
    }

    // Reconstitution constructor
    public Seat(SeatCode seatCode, SeatStatus status, String reservedByBookingId) {
        this.seatCode = Objects.requireNonNull(seatCode);
        this.status = Objects.requireNonNull(status);
        this.reservedByBookingId = reservedByBookingId;
    }

    /**
     * Marks this seat SOLD for {@code bookingId}. Idempotent if this exact booking already
     * sold it (e.g. a redelivered Kafka event); throws if a DIFFERENT booking claims a seat
     * already SOLD — the RESERVED hold that should have prevented this lives only in Redis
     * (see SeatHoldPort), so a lost/expired hold can otherwise let two bookings both "win"
     * the same seat without this check.
     */
    void sell(String bookingId) {
        if (status == SeatStatus.SOLD) {
            if (Objects.equals(reservedByBookingId, bookingId)) {
                return;
            }
            throw new SeatAlreadySoldException(seatCode, reservedByBookingId, bookingId);
        }
        this.status = SeatStatus.SOLD;
        this.reservedByBookingId = bookingId;
    }

    public SeatCode getSeatCode()          { return seatCode; }
    public SeatStatus getStatus()          { return status; }
    public String getReservedByBookingId() { return reservedByBookingId; }
}
