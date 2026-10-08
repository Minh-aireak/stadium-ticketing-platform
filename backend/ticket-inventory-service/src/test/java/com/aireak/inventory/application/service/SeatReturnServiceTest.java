package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.command.ReturnSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsReturnedEvent;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import com.aireak.inventory.domain.model.SeatTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Giving back seats a customer cancelled: the paid ones go SOLD → AVAILABLE with an event for
 * exactly them, the booking's holds go, and a redelivery changes nothing.
 */
@ExtendWith(MockitoExtension.class)
class SeatReturnServiceTest {

    private static final String SHOWTIME_ID = "showtime-1";
    private static final String BOOKING_ID = "booking-1";
    private static final SeatCode A1 = new SeatCode("A1");
    private static final SeatCode A2 = new SeatCode("A2");
    private static final BigDecimal PRICE = new BigDecimal("150000");

    @Mock private DistributedLockPort distributedLockPort;
    @Mock private SeatHoldPort seatHoldPort;
    @Mock private SeatInventoryRepository seatInventoryRepository;
    @Mock private DomainEventPublisher eventPublisher;

    private SeatReturnService service;

    @BeforeEach
    void setUp() {
        service = new SeatReturnService(distributedLockPort, new SeatReturner(seatInventoryRepository, eventPublisher),
                seatHoldPort);
        when(distributedLockPort.executeWithLock(anyString(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<Object> action = invocation.getArgument(4);
                    return action.get();
                });
    }

    private static SeatInventory inventoryWith(Seat... seats) {
        return SeatInventory.reconstitute(SHOWTIME_ID, List.of(seats));
    }

    @Test
    void putsTheBookingsSoldSeatsBackOnSaleUnderTheShowtimeLockThenDropsItsHolds() {
        SeatInventory inventory = inventoryWith(
                new Seat(A1, SeatStatus.SOLD, BOOKING_ID, SeatTier.STANDARD, PRICE),
                new Seat(A2, SeatStatus.SOLD, BOOKING_ID, SeatTier.STANDARD, PRICE));
        when(seatInventoryRepository.findByShowtimeIdWithSeats(SHOWTIME_ID, List.of(A1)))
                .thenReturn(Optional.of(inventory));

        service.execute(new ReturnSeatsCommand(SHOWTIME_ID, BOOKING_ID, List.of("A1")));

        verify(distributedLockPort).executeWithLock(eq("inventory:" + SHOWTIME_ID), anyLong(), anyLong(), any(), any());
        assertThat(inventory.getSeats()).filteredOn(seat -> seat.getSeatCode().equals(A1))
                .singleElement()
                .satisfies(seat -> {
                    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
                    assertThat(seat.getReservedByBookingId()).isNull();
                });

        InOrder durableFirst = inOrder(seatInventoryRepository, eventPublisher, seatHoldPort);
        durableFirst.verify(seatInventoryRepository).saveSeats(inventory);
        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        durableFirst.verify(eventPublisher).publishAll(published.capture());
        durableFirst.verify(seatHoldPort).releaseHolds(SHOWTIME_ID, List.of(A1), BOOKING_ID);

        SeatsReturnedEvent event = (SeatsReturnedEvent) published.getValue().get(0);
        assertThat(event.seatCodes()).containsExactly(A1);
        assertThat(event.bookingId()).isEqualTo(BOOKING_ID);
    }

    /** An unpaid booking only ever held its seats: nothing to un-sell, nothing to count back. */
    @Test
    void anUnpaidBookingsSeatsOnlyLoseTheirHoldsAndRaiseNoEvent() {
        SeatInventory inventory = inventoryWith(new Seat(A1, SeatTier.STANDARD, PRICE));
        when(seatInventoryRepository.findByShowtimeIdWithSeats(SHOWTIME_ID, List.of(A1)))
                .thenReturn(Optional.of(inventory));

        service.execute(new ReturnSeatsCommand(SHOWTIME_ID, BOOKING_ID, List.of("A1")));

        verify(seatInventoryRepository, never()).saveSeats(any());
        verify(eventPublisher, never()).publishAll(any());
        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, List.of(A1), BOOKING_ID);
    }

    @Test
    void aSeatSoldToAnotherBookingIsNotThisBookingsToGiveBack() {
        SeatInventory inventory = inventoryWith(new Seat(A1, SeatStatus.SOLD, "someone-else", SeatTier.STANDARD, PRICE));
        when(seatInventoryRepository.findByShowtimeIdWithSeats(SHOWTIME_ID, List.of(A1)))
                .thenReturn(Optional.of(inventory));

        service.execute(new ReturnSeatsCommand(SHOWTIME_ID, BOOKING_ID, List.of("A1")));

        assertThat(inventory.getSeats()).singleElement()
                .satisfies(seat -> assertThat(seat.getReservedByBookingId()).isEqualTo("someone-else"));
        verify(eventPublisher, never()).publishAll(any());
    }

    /** At-least-once delivery: the second run finds the seat AVAILABLE and counts nothing back twice. */
    @Test
    void aRedeliveredRequestChangesNothingTheSecondTime() {
        SeatInventory inventory = inventoryWith(new Seat(A1, SeatStatus.SOLD, BOOKING_ID, SeatTier.STANDARD, PRICE));
        when(seatInventoryRepository.findByShowtimeIdWithSeats(SHOWTIME_ID, List.of(A1)))
                .thenReturn(Optional.of(inventory));
        ReturnSeatsCommand command = new ReturnSeatsCommand(SHOWTIME_ID, BOOKING_ID, List.of("A1"));

        service.execute(command);
        service.execute(command);

        verify(eventPublisher).publishAll(any());
    }
}
