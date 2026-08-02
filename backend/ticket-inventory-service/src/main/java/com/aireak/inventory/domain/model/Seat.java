package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.exception.SeatAlreadySoldException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Entity within the SeatInventory aggregate.
 * Represents one physical seat for a specific showtime.
 *
 * <p>{@code tier}/{@code price} are fixed at seat-map generation time (see
 * {@code SeatMapLayout#generate}) — a snapshot of {@code basePrice × tier multiplier} at that
 * moment, not recomputed on every read, so a later change to a showtime's base price never
 * retroactively changes what an already-generated seat costs.
 */
public class Seat {

    private final SeatCode seatCode;
    private final SeatTier tier;
    private final BigDecimal price;
    private SeatStatus status;
    private String reservedByBookingId; // null when AVAILABLE; the owning booking once SOLD

    public Seat(SeatCode seatCode, SeatTier tier, BigDecimal price) {
        this.seatCode = Objects.requireNonNull(seatCode);
        this.tier = Objects.requireNonNull(tier);
        this.price = Objects.requireNonNull(price);
        this.status = SeatStatus.AVAILABLE;
    }

    // Reconstitution constructor
    public Seat(SeatCode seatCode, SeatStatus status, String reservedByBookingId,
                SeatTier tier, BigDecimal price) {
        this.seatCode = Objects.requireNonNull(seatCode);
        this.status = Objects.requireNonNull(status);
        this.reservedByBookingId = reservedByBookingId;
        this.tier = Objects.requireNonNull(tier);
        this.price = Objects.requireNonNull(price);
    }

    /**
     * Marks this seat SOLD for {@code bookingId}. Idempotent if this exact booking already
     * sold it (e.g. a redelivered Kafka event); throws if a DIFFERENT booking claims a seat
     * already SOLD — the RESERVED hold that should have prevented this lives only in Redis
     * (see SeatHoldPort), so a lost/expired hold can otherwise let two bookings both "win"
     * the same seat without this check.
     */
    boolean sell(String bookingId) {
        if (status == SeatStatus.SOLD) {
            if (Objects.equals(reservedByBookingId, bookingId)) {
                return false;
            }
            throw new SeatAlreadySoldException(seatCode, reservedByBookingId, bookingId);
        }
        this.status = SeatStatus.SOLD;
        this.reservedByBookingId = bookingId;
        return true;
    }

    public SeatCode getSeatCode()          { return seatCode; }
    public SeatStatus getStatus()          { return status; }
    public String getReservedByBookingId() { return reservedByBookingId; }
    public SeatTier getTier()              { return tier; }
    public BigDecimal getPrice()           { return price; }
}
