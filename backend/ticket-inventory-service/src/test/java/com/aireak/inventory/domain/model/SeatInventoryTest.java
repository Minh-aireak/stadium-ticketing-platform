package com.aireak.inventory.domain.model;

import com.aireak.inventory.domain.event.SeatsSoldEvent;
import com.aireak.inventory.domain.exception.SeatAlreadySoldException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatInventoryTest {

    private static final SeatCode A1 = new SeatCode("A1");
    private static final SeatCode A2 = new SeatCode("A2");
    private static final SeatCode A3 = new SeatCode("A3");

    @Test
    void createStartsAllSeatsAvailable() {
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1, A2, A3));

        assertThat(inventory.getSeats()).hasSize(3);
        assertThat(inventory.getSeats()).allSatisfy(seat ->
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE));
        assertThat(inventory.pullDomainEvents()).isEmpty();
    }

    @Test
    void sellSeatsMarksOnlyRequestedSeatsSoldAndRaisesEvent() {
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1, A2, A3));

        inventory.sellSeats(List.of(A1, A2), "booking-1");

        assertThat(statusOf(inventory, A1)).isEqualTo(SeatStatus.SOLD);
        assertThat(statusOf(inventory, A2)).isEqualTo(SeatStatus.SOLD);
        assertThat(statusOf(inventory, A3)).isEqualTo(SeatStatus.AVAILABLE);

        List<Object> events = inventory.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(SeatsSoldEvent.class);
        assertThat(((SeatsSoldEvent) events.get(0)).seatCodes()).containsExactly(A1, A2);
    }

    private static SeatStatus statusOf(SeatInventory inventory, SeatCode seatCode) {
        return inventory.getSeats().stream()
                .filter(seat -> seat.getSeatCode().equals(seatCode))
                .findFirst()
                .orElseThrow()
                .getStatus();
    }

    @Test
    void sellSeatsIgnoresSeatCodesNotInThisInventory() {
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1));

        inventory.sellSeats(List.of(A1, new SeatCode("Z9")), "booking-1");

        assertThat(inventory.getSeats()).hasSize(1);
        // No exception for the unknown code — the event still records everything requested.
        assertThat(((SeatsSoldEvent) inventory.pullDomainEvents().get(0)).seatCodes())
                .containsExactly(A1, new SeatCode("Z9"));
    }

    @Test
    void sellSeatsPropagatesSeatAlreadySoldToADifferentBooking() {
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1));
        inventory.sellSeats(List.of(A1), "booking-1");
        inventory.pullDomainEvents();

        assertThatThrownBy(() -> inventory.sellSeats(List.of(A1), "booking-2"))
                .isInstanceOf(SeatAlreadySoldException.class);
    }

    @Test
    void reconstitutePreservesSeatStatesAndRaisesNoEvents() {
        Seat sold = new Seat(A1, SeatStatus.SOLD, "booking-1");
        Seat available = new Seat(A2, SeatStatus.AVAILABLE, null);

        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(sold, available));

        assertThat(inventory.getSeats()).containsExactlyInAnyOrder(sold, available);
        assertThat(inventory.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullDomainEventsClearsTheList() {
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1));
        inventory.sellSeats(List.of(A1), "booking-1");

        List<Object> firstPull = inventory.pullDomainEvents();
        List<Object> secondPull = inventory.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }
}
