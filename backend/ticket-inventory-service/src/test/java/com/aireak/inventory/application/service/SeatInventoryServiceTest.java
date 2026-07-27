package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.SeatCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private static final List<String> SEAT_CODE_STRINGS = List.of("A1", "A2");
    private static final List<SeatCode> SEAT_CODES = List.of(new SeatCode("A1"), new SeatCode("A2"));

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
    void reserveHoldsSeatsAndPublishesEventWhenNoneAreSold() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSoldSeatCodes(eq(SHOWTIME_ID), any())).thenReturn(List.of());

        service.execute(new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS));

        verify(seatHoldPort).holdSeats(SHOWTIME_ID, SEAT_CODES, BOOKING_ID);
        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(SeatsReservedEvent.class);
    }

    @Test
    void reserveThrowsWhenInventoryDoesNotExistAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.execute(new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatInventoryNotFoundException.class);

        verify(seatHoldPort, never()).holdSeats(any(), any(), any());
        verify(eventPublisher, never()).publishAll(any());
    }

    @Test
    void reserveThrowsWhenAnyRequestedSeatIsAlreadySoldAndNeverHolds() {
        when(seatInventoryRepository.existsByShowtimeId(SHOWTIME_ID)).thenReturn(true);
        when(seatInventoryRepository.findSoldSeatCodes(eq(SHOWTIME_ID), any()))
                .thenReturn(List.of(new SeatCode("A1")));

        assertThatThrownBy(() -> service.execute(new ReserveSeatsCommand(SHOWTIME_ID, BOOKING_ID, SEAT_CODE_STRINGS)))
                .isInstanceOf(SeatsNotAvailableException.class);

        verify(seatHoldPort, never()).holdSeats(any(), any(), any());
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
