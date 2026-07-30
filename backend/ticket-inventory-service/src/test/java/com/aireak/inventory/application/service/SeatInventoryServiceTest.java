package com.aireak.inventory.application.service;

import com.aireak.common.exception.ForbiddenException;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.in.command.UnholdSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatStatus;
import com.aireak.inventory.domain.model.SeatTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the seat inventory orchestrator. {@link DistributedLockPort} is stubbed to
 * just run the supplied action inline (as if the lock were always immediately granted) — lock
 * contention/timeout behavior lives entirely in {@code RedissonDistributedLockAdapter}, not here.
 */
@ExtendWith(MockitoExtension.class)
class SeatInventoryServiceTest {

    private static final String SHOWTIME_ID = "showtime-1";
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "customer-1";
    private static final List<String> SEAT_CODE_STRINGS = List.of("A1", "A2");
    private static final List<SeatCode> SEAT_CODES = List.of(new SeatCode("A1"), new SeatCode("A2"));
    private static final BigDecimal A1_PRICE = new BigDecimal("100.00");
    private static final BigDecimal A2_PRICE = new BigDecimal("50.00");

    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private DistributedLockPort distributedLockPort;
    @Mock
    private SeatHoldPort seatHoldPort;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private SeatSaleConfirmer seatSaleConfirmer;

    private SeatInventoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SeatInventoryService(
                seatInventoryRepository, distributedLockPort, seatHoldPort, eventPublisher, seatSaleConfirmer);

        // Run the supplied action immediately, as if the lock were granted with zero contention.
        when(distributedLockPort.executeWithLock(anyString(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<Object> action = invocation.getArgument(4);
                    return action.get();
                });
    }

    @Test
    void reserveHoldsSeatsAndPublishesEventAndReturnsTotalPriceWhenNoneAreSold() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of(
                availableSeat("A1", A1_PRICE), availableSeat("A2", A2_PRICE)));

        BigDecimal totalPrice = service.execute(
                new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, CUSTOMER_ID, SEAT_CODE_STRINGS));

        assertThat(totalPrice).isEqualByComparingTo(A1_PRICE.add(A2_PRICE));
        verify(seatHoldPort).confirmHold(SHOWTIME_ID, SEAT_CODES, CUSTOMER_ID, BOOKING_ID);
        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(SeatsReservedEvent.class);
    }

    @Test
    void reserveThrowsWhenInventoryDoesNotExistAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(
                new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, CUSTOMER_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatInventoryNotFoundException.class);

        verify(seatHoldPort, never()).confirmHold(any(), any(), any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void reserveThrowsWhenAnyRequestedSeatIsAlreadySoldAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of(
                soldSeat("A1", A1_PRICE), availableSeat("A2", A2_PRICE)));

        assertThatThrownBy(() -> service.execute(
                new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, CUSTOMER_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatsNotAvailableException.class);

        verify(seatHoldPort, never()).confirmHold(any(), any(), any(), any());
    }

    @Test
    void reserveThrowsWhenARequestedSeatCodeDoesNotExistInTheInventoryAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        // Only A1 comes back — A2 doesn't exist in this showtime's inventory. Must not silently
        // undercharge by pricing just the seats it could find.
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(availableSeat("A1", A1_PRICE)));

        assertThatThrownBy(() -> service.execute(
                new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, CUSTOMER_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatsNotAvailableException.class);

        verify(seatHoldPort, never()).confirmHold(any(), any(), any(), any());
    }

    @Test
    void reserveReleasesTheHoldAndPropagatesWhenPublishingTheEventFails() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of(
                availableSeat("A1", A1_PRICE), availableSeat("A2", A2_PRICE)));
        RuntimeException outboxFailure = new RuntimeException("outbox write failed");
        doThrow(outboxFailure).when(eventPublisher).publishAll(any());

        assertThatThrownBy(() -> service.execute(
                new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, CUSTOMER_ID, SEAT_CODE_STRINGS)))
                .isSameAs(outboxFailure);

        verify(seatHoldPort).confirmHold(SHOWTIME_ID, SEAT_CODES, CUSTOMER_ID, BOOKING_ID);
        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
    }

    @Test
    void holdPlacesACustomerOwnedHoldAndReturnsTotalPriceWithoutPublishingAnyEvent() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of(
                availableSeat("A1", A1_PRICE), availableSeat("A2", A2_PRICE)));

        BigDecimal totalPrice = service.execute(new HoldSeatsCommand(SHOWTIME_ID, CUSTOMER_ID, SEAT_CODE_STRINGS));

        assertThat(totalPrice).isEqualByComparingTo(A1_PRICE.add(A2_PRICE));
        verify(seatHoldPort).holdSeats(SHOWTIME_ID, SEAT_CODES, CUSTOMER_ID);
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void holdThrowsWhenAnyRequestedSeatIsAlreadySoldAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSeatsByCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of(
                soldSeat("A1", A1_PRICE), availableSeat("A2", A2_PRICE)));

        assertThatThrownBy(() -> service.execute(new HoldSeatsCommand(SHOWTIME_ID, CUSTOMER_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatsNotAvailableException.class);

        verify(seatHoldPort, never()).holdSeats(any(), any(), any());
    }

    @Test
    void unholdReleasesTheCustomerOwnedHold() {
        service.execute(new UnholdSeatsCommand(SHOWTIME_ID, CUSTOMER_ID, SEAT_CODE_STRINGS));

        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, SEAT_CODES, CUSTOMER_ID);
        verify(eventPublisher, never()).publishAll(any());
    }

    private static Seat availableSeat(String code, BigDecimal price) {
        return new Seat(new SeatCode(code), SeatStatus.AVAILABLE, null, SeatTier.STANDARD, price);
    }

    private static Seat soldSeat(String code, BigDecimal price) {
        return new Seat(new SeatCode(code), SeatStatus.SOLD, BOOKING_ID, SeatTier.STANDARD, price);
    }

    @Test
    void releaseRemovesHoldsAndPublishesEvent() {
        service.execute(new ReleaseSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS));

        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(SeatsReleasedEvent.class);
    }

    // CRIT-2-01: an internal-service-token release (no requestingCustomerId — see
    // ReleaseSeatsCommand's 3-arg constructor, exercised by releaseRemovesHoldsAndPublishesEvent
    // above) is trusted unconditionally and never calls isHeldByCustomerAndBooking. A
    // customer-token release must own the reservation it's releasing.
    @Test
    void releaseWithCustomerTokenSucceedsWhenCallerOwnsTheReservation() {
        when(seatHoldPort.isHeldByCustomerAndBooking(SHOWTIME_ID, SEAT_CODES, CUSTOMER_ID, BOOKING_ID))
                .thenReturn(true);

        service.execute(new ReleaseSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS, CUSTOMER_ID));

        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
        verify(eventPublisher).publishAll(any());
    }

    @Test
    void releaseWithCustomerTokenThrowsForbiddenWhenCallerDoesNotOwnTheReservationAndNeverReleases() {
        String attackerId = "attacker-1";
        when(seatHoldPort.isHeldByCustomerAndBooking(SHOWTIME_ID, SEAT_CODES, attackerId, BOOKING_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.execute(
                new ReleaseSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS, attackerId)))
                .isInstanceOf(ForbiddenException.class);

        verify(seatHoldPort, never()).releaseHolds(any(), any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void confirmDelegatesToSeatSaleConfirmerThenReleasesTheHold() {
        service.execute(new ConfirmSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS));

        verify(seatSaleConfirmer).confirmSale(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
        verify(seatHoldPort).releaseHolds(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
    }

    @Test
    void confirmDoesNotReleaseTheHoldWhenSaleConfirmationFails() {
        doThrow(new SeatInventoryNotFoundException(SHOWTIME_ID))
                .when(seatSaleConfirmer).confirmSale(any(), any(), any());

        assertThatThrownBy(() -> service.execute(new ConfirmSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatInventoryNotFoundException.class);

        verify(seatHoldPort, never()).releaseHolds(any(), any(), any());
    }
}
