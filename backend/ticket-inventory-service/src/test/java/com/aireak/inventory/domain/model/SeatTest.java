package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.exception.SeatAlreadySoldException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatTest {

    @Test
    void newSeatStartsAvailableWithNoOwner() {
        Seat seat = new Seat(new SeatCode("A1"));

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getReservedByBookingId()).isNull();
    }

    @Test
    void sellFromAvailableMarksSoldForBooking() {
        Seat seat = new Seat(new SeatCode("A1"));

        seat.sell("booking-1");

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(seat.getReservedByBookingId()).isEqualTo("booking-1");
    }

    @Test
    void sellFromReservedMarksSoldForBooking() {
        Seat seat = new Seat(new SeatCode("A1"), SeatStatus.RESERVED, "booking-1");

        seat.sell("booking-1");

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void sellIsIdempotentForTheSameBookingAlreadySold() {
        Seat seat = new Seat(new SeatCode("A1"), SeatStatus.SOLD, "booking-1");

        seat.sell("booking-1");

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(seat.getReservedByBookingId()).isEqualTo("booking-1");
    }

    @Test
    void sellRejectsWhenAlreadySoldToADifferentBooking() {
        Seat seat = new Seat(new SeatCode("A1"), SeatStatus.SOLD, "booking-1");

        assertThatThrownBy(() -> seat.sell("booking-2"))
                .isInstanceOf(SeatAlreadySoldException.class);
        // The original sale must survive a rejected competing claim.
        assertThat(seat.getReservedByBookingId()).isEqualTo("booking-1");
    }
}
