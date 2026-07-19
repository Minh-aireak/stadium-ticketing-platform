package com.aireak.inventory.domain.model;

import java.util.Objects;

/**
 * Entity within the SeatInventory aggregate.
 * Represents one physical seat for a specific showtime.
 */
public class Seat {

    private final SeatCode seatCode;
    private SeatStatus status;
    private String reservedByBookingId; // null when AVAILABLE or SOLD

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

    public boolean isAvailable() { return status == SeatStatus.AVAILABLE; }

    void reserve(String bookingId) {
        this.status = SeatStatus.RESERVED;
        this.reservedByBookingId = bookingId;
    }

    void release() {
        this.status = SeatStatus.AVAILABLE;
        this.reservedByBookingId = null;
    }

    void sell() {
        this.status = SeatStatus.SOLD;
    }

    public SeatCode getSeatCode()          { return seatCode; }
    public SeatStatus getStatus()          { return status; }
    public String getReservedByBookingId() { return reservedByBookingId; }
}
