package com.aireak.catalog.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity within the Match aggregate: a scheduled showing/match at a specific time.
 * A Match can have multiple Showtimes (e.g. group stage + knockout rounds).
 *
 * <p>Package-private setters — only Match aggregate mutates Showtimes.
 *
 * <p>{@code basePrice}/{@code currency} are the per-showtime ticket price catalog-service
 * carries — ticket-inventory-service snapshots the actual per-seat price (basePrice × tier
 * multiplier) once, at seat-map generation time, off a {@code ShowtimeAddedEvent} carrying
 * these two fields (see {@code Match#addShowtime}).
 */
public class Showtime {

    private final String showtimeId;
    private final Instant startTime;
    private final String venueId;
    private final int totalSeats;
    private int availableSeats;
    private final BigDecimal basePrice;
    private final String currency;

    public Showtime(Instant startTime, String venueId, int totalSeats, BigDecimal basePrice, String currency) {
        this.showtimeId = UUID.randomUUID().toString();
        this.startTime = Objects.requireNonNull(startTime);
        this.venueId = Objects.requireNonNull(venueId);
        if (totalSeats <= 0) throw new IllegalArgumentException("totalSeats must be positive");
        this.totalSeats = totalSeats;
        this.availableSeats = totalSeats;
        this.basePrice = requirePositive(basePrice);
        this.currency = Objects.requireNonNull(currency);
    }

    public Showtime(String showtimeId, Instant startTime, String venueId,
                    int totalSeats, int availableSeats, BigDecimal basePrice, String currency) {
        this.showtimeId = showtimeId;
        this.startTime = startTime;
        this.venueId = venueId;
        this.totalSeats = totalSeats;
        this.availableSeats = availableSeats;
        this.basePrice = basePrice;
        this.currency = currency;
    }

    private static BigDecimal requirePositive(BigDecimal basePrice) {
        Objects.requireNonNull(basePrice);
        if (basePrice.signum() <= 0) throw new IllegalArgumentException("basePrice must be positive");
        return basePrice;
    }

    void decrementAvailableSeats(int count) {
        if (availableSeats < count) throw new IllegalStateException("Not enough available seats");
        availableSeats -= count;
    }

    public String getShowtimeId()   { return showtimeId; }
    public Instant getStartTime()   { return startTime; }
    public String getVenueId()      { return venueId; }
    public int getTotalSeats()      { return totalSeats; }
    public int getAvailableSeats()  { return availableSeats; }
    public BigDecimal getBasePrice() { return basePrice; }
    public String getCurrency()     { return currency; }
}
