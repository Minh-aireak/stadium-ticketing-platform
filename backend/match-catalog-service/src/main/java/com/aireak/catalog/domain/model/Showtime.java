package com.aireak.catalog.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity within the Match aggregate: a scheduled showing/match at a specific time.
 * A Match can have multiple Showtimes (e.g. group stage + knockout rounds).
 *
 * <p>Package-private setters — only Match aggregate mutates Showtimes.
 */
public class Showtime {

    private final String showtimeId;
    private final Instant startTime;
    private final String venueId;
    private final int totalSeats;
    private int availableSeats;

    public Showtime(Instant startTime, String venueId, int totalSeats) {
        this.showtimeId = UUID.randomUUID().toString();
        this.startTime = Objects.requireNonNull(startTime);
        this.venueId = Objects.requireNonNull(venueId);
        if (totalSeats <= 0) throw new IllegalArgumentException("totalSeats must be positive");
        this.totalSeats = totalSeats;
        this.availableSeats = totalSeats;
    }

    public Showtime(String showtimeId, Instant startTime, String venueId,
                    int totalSeats, int availableSeats) {
        this.showtimeId = showtimeId;
        this.startTime = startTime;
        this.venueId = venueId;
        this.totalSeats = totalSeats;
        this.availableSeats = availableSeats;
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
}
