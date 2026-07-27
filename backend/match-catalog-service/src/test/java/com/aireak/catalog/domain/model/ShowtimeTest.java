package com.aireak.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowtimeTest {

    @Test
    void newShowtimeStartsWithAvailableSeatsEqualToTotal() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 100);

        assertThat(showtime.getTotalSeats()).isEqualTo(100);
        assertThat(showtime.getAvailableSeats()).isEqualTo(100);
        assertThat(showtime.getShowtimeId()).isNotBlank();
    }

    @Test
    void constructorRejectsNonPositiveTotalSeats() {
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullStartTimeOrVenue() {
        assertThatThrownBy(() -> new Showtime(null, "venue-1", 100))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), null, 100))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void decrementAvailableSeatsReducesCount() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 100);

        showtime.decrementAvailableSeats(30);

        assertThat(showtime.getAvailableSeats()).isEqualTo(70);
    }

    @Test
    void decrementAvailableSeatsRejectsWhenNotEnoughSeatsLeft() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 10);

        assertThatThrownBy(() -> showtime.decrementAvailableSeats(11))
                .isInstanceOf(IllegalStateException.class);
        assertThat(showtime.getAvailableSeats()).isEqualTo(10);
    }

    @Test
    void reconstitutionConstructorPreservesGivenValues() {
        Showtime showtime = new Showtime("showtime-1", Instant.parse("2024-06-01T18:00:00Z"),
                "venue-2", 200, 150);

        assertThat(showtime.getShowtimeId()).isEqualTo("showtime-1");
        assertThat(showtime.getTotalSeats()).isEqualTo(200);
        assertThat(showtime.getAvailableSeats()).isEqualTo(150);
    }
}
