package com.aireak.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowtimeTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("150000");

    @Test
    void newShowtimeStartsWithAvailableSeatsEqualToTotal() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 100, BASE_PRICE, "VND");

        assertThat(showtime.getTotalSeats()).isEqualTo(100);
        assertThat(showtime.getAvailableSeats()).isEqualTo(100);
        assertThat(showtime.getShowtimeId()).isNotBlank();
        assertThat(showtime.getBasePrice()).isEqualByComparingTo(BASE_PRICE);
        assertThat(showtime.getCurrency()).isEqualTo("VND");
    }

    @Test
    void constructorRejectsNonPositiveTotalSeats() {
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", 0, BASE_PRICE, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", -5, BASE_PRICE, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullStartTimeOrVenue() {
        assertThatThrownBy(() -> new Showtime(null, "venue-1", 100, BASE_PRICE, "VND"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), null, 100, BASE_PRICE, "VND"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNonPositiveBasePrice() {
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", 100, BigDecimal.ZERO, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", 100, new BigDecimal("-1"), "VND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Showtime(Instant.now(), "venue-1", 100, null, "VND"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void decrementAvailableSeatsReducesCount() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 100, BASE_PRICE, "VND");

        showtime.decrementAvailableSeats(30);

        assertThat(showtime.getAvailableSeats()).isEqualTo(70);
    }

    @Test
    void decrementAvailableSeatsRejectsWhenNotEnoughSeatsLeft() {
        Showtime showtime = new Showtime(Instant.now(), "venue-1", 10, BASE_PRICE, "VND");

        assertThatThrownBy(() -> showtime.decrementAvailableSeats(11))
                .isInstanceOf(IllegalStateException.class);
        assertThat(showtime.getAvailableSeats()).isEqualTo(10);
    }

    @Test
    void reconstitutionConstructorPreservesGivenValues() {
        Showtime showtime = new Showtime("showtime-1", Instant.parse("2024-06-01T18:00:00Z"),
                "venue-2", 200, 150, BASE_PRICE, "VND");

        assertThat(showtime.getShowtimeId()).isEqualTo("showtime-1");
        assertThat(showtime.getTotalSeats()).isEqualTo(200);
        assertThat(showtime.getAvailableSeats()).isEqualTo(150);
        assertThat(showtime.getBasePrice()).isEqualByComparingTo(BASE_PRICE);
        assertThat(showtime.getCurrency()).isEqualTo("VND");
    }
}
