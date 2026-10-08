package com.aireak.booking.application.service;

import com.aireak.booking.application.port.in.CancelBookingCommand;
import com.aireak.booking.application.port.out.BookingCancellationLock;
import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.CancelledSeatsStore;
import com.aireak.booking.application.port.out.SeatPricingPort;
import com.aireak.booking.application.port.out.ShowtimeSchedulePort;
import com.aireak.booking.domain.exception.CancellationConflictException;
import com.aireak.booking.domain.exception.CancellationWindowClosedException;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.exception.SeatCancellationException;
import com.aireak.booking.domain.exception.TicketIssuanceInProgressException;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.CancellationOutcome;
import com.aireak.booking.domain.model.SeatRefundQuote;
import com.aireak.booking.domain.model.SeatSelection;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelBookingServiceTest {

    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "customer-1";
    private static final String SHOWTIME_ID = "showtime-1";
    private static final List<String> SEATS = List.of("A1", "A2");
    private static final BigDecimal AMOUNT = new BigDecimal("300000");
    private static final Map<String, BigDecimal> PRICES = Map.of("A1", new BigDecimal("100000"), "A2", new BigDecimal("200000"));
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingSagaSteps sagaSteps;
    @Mock private BookingCancellationLock cancellationLock;
    @Mock private CancelledSeatsStore cancelledSeatsStore;
    @Mock private ShowtimeSchedulePort showtimeSchedulePort;
    @Mock private SeatPricingPort seatPricingPort;
    @Mock private BookingCancellationLock.Claim claim;

    private CancelBookingService service;

    @BeforeEach
    void setUp() {
        service = new CancelBookingService(bookingRepository, sagaSteps, cancellationLock, cancelledSeatsStore,
                showtimeSchedulePort, seatPricingPort, Clock.fixed(NOW, ZoneOffset.UTC), 24);
    }

    private static Booking booking(BookingStatus status, boolean inventoryConfirmed, List<String> cancelled,
                                   BigDecimal refunded) {
        return Booking.reconstitute(BOOKING_ID, CUSTOMER_ID, "customer-1@example.com", SHOWTIME_ID,
                new SeatSelection(SEATS), BookingAmount.of(AMOUNT, "VND"), status, NOW.minus(Duration.ofDays(1)),
                null, 3L, inventoryConfirmed, false, cancelled, refunded);
    }

    private static Booking paid() {
        return booking(BookingStatus.CONFIRMED, true, List.of(), BigDecimal.ZERO);
    }

    private static CancelBookingCommand cancel(String... seats) {
        return new CancelBookingCommand(BOOKING_ID, CUSTOMER_ID, List.of(seats));
    }

    @Nested
    class WhoMayCancel {

        @Test
        void anUnknownBookingIs404AndNothingElseRuns() {
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelBooking(cancel()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(cancellationLock, cancelledSeatsStore, sagaSteps);
        }

        @Test
        void someoneElsesBookingIs403BeforeRedisOrTheLockIsTouched() {
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(paid()));

            assertThatThrownBy(() -> service.cancelBooking(new CancelBookingCommand(BOOKING_ID, "intruder", List.of())))
                    .isInstanceOf(IdentityMismatchException.class);
            verifyNoInteractions(cancellationLock, cancelledSeatsStore, sagaSteps, showtimeSchedulePort);
        }
    }

    /** "đã update rồi thì return → đồng bộ trạng thái trong Redis" */
    @Nested
    class Repeats {

        @Test
        void aRepeatRecordedInRedisIsAnsweredWithoutTheLockOrAWrite() {
            Booking cancelled = booking(BookingStatus.CANCELLED, true, SEATS, AMOUNT);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(cancelled));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of("A1", "A2"));

            Booking result = service.cancelBooking(cancel());

            assertThat(result).isSameAs(cancelled);
            verifyNoInteractions(cancellationLock, sagaSteps, showtimeSchedulePort, seatPricingPort);
            verify(cancelledSeatsStore, never()).record(anyString(), anyList());
        }

        @Test
        void aRepeatRedisDoesNotKnowAboutIsAnsweredFromTheDatabaseAndWrittenBackToRedis() {
            Booking cancelled = booking(BookingStatus.CANCELLED, false, List.of(), BigDecimal.ZERO);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(cancelled));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());

            Booking result = service.cancelBooking(cancel("A1"));

            assertThat(result).isSameAs(cancelled);
            verify(cancelledSeatsStore).record(BOOKING_ID, SEATS);
            verifyNoInteractions(cancellationLock, sagaSteps);
        }

        @Test
        void redisKnowingOnlySomeOfTheRequestedSeatsIsNotARepeat() {
            Booking partly = booking(BookingStatus.CONFIRMED, true, List.of("A1"), new BigDecimal("100000"));
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(partly));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of("A1"));
            when(showtimeSchedulePort.kickoffOf(SHOWTIME_ID)).thenReturn(NOW.plus(Duration.ofDays(3)));
            when(seatPricingPort.pricesOf(SHOWTIME_ID, List.of("A2"))).thenReturn(PRICES);
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of("A1", "A2")), any(), anyString()))
                    .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.CANCELLED_NOW, List.of("A2"),
                            new BigDecimal("200000"), new BigDecimal("200000"), true));
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(booking(BookingStatus.CANCELLED, true, SEATS, AMOUNT));

            service.cancelBooking(cancel("A1", "A2"));

            verify(sagaSteps).cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of("A1", "A2")), any(), anyString());
        }
    }

    @Nested
    class UnpaidBooking {

        @Test
        void isCancelledWholeUnderTheLockWithNoRemoteReadsAndRedisIsUpdatedAfterTheCommit() {
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, false, List.of(), BigDecimal.ZERO)));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of()), isNull(), anyString()))
                    .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.CANCELLED_NOW, SEATS,
                            BigDecimal.ZERO, BigDecimal.ZERO, true));
            Booking after = booking(BookingStatus.CANCELLED, false, SEATS, BigDecimal.ZERO);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(after);

            Booking result = service.cancelBooking(cancel());

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(cancelledSeatsStore).record(BOOKING_ID, SEATS);
            verify(claim).close();
            verifyNoInteractions(showtimeSchedulePort, seatPricingPort);
        }

        @Test
        void cannotBeCancelledSeatBySeat() {
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, false, List.of(), BigDecimal.ZERO)));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());

            assertThatThrownBy(() -> service.cancelBooking(cancel("A1")))
                    .isInstanceOf(SeatCancellationException.class);
            verifyNoInteractions(cancellationLock, sagaSteps);
        }

        @Test
        void aDraftIsRefusedBecauseTheCreationSagaStillOwnsIt() {
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(booking(BookingStatus.DRAFT, false, List.of(), BigDecimal.ZERO)));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());

            assertThatThrownBy(() -> service.cancelBooking(cancel()))
                    .isInstanceOf(InvalidBookingStatusException.class)
                    .hasMessageContaining("still being created");
            verifyNoInteractions(cancellationLock, sagaSteps);
        }
    }

    @Nested
    class PaidSeats {

        @BeforeEach
        void paidBooking() {
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(paid()));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());
        }

        @Test
        void aSeatInsideTheWindowIsCancelledAndQuotedAtItsOwnPrice() {
            when(showtimeSchedulePort.kickoffOf(SHOWTIME_ID)).thenReturn(NOW.plus(Duration.ofDays(3)));
            when(seatPricingPort.pricesOf(SHOWTIME_ID, List.of("A2"))).thenReturn(Map.of("A2", new BigDecimal("200000")));
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of("A2")), any(), anyString()))
                    .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.CANCELLED_NOW, List.of("A2"),
                            new BigDecimal("200000"), new BigDecimal("200000"), false));
            Booking after = booking(BookingStatus.CONFIRMED, true, List.of("A2"), new BigDecimal("200000"));
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(after);

            Booking result = service.cancelBooking(cancel("A2"));

            ArgumentCaptor<SeatRefundQuote> quote = ArgumentCaptor.forClass(SeatRefundQuote.class);
            verify(sagaSteps).cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of("A2")), quote.capture(),
                    eq(CancelBookingService.CUSTOMER_CANCELLATION_REASON));
            assertThat(quote.getValue().totalFor(List.of("A2"))).isEqualByComparingTo("200000");
            assertThat(result.activeSeatCodes()).containsExactly("A1");
            verify(cancelledSeatsStore).record(BOOKING_ID, List.of("A2"));
            verify(claim).close();
        }

        @Test
        void exactlyTwentyFourHoursBeforeKickoffIsAlreadyTooLate() {
            when(showtimeSchedulePort.kickoffOf(SHOWTIME_ID)).thenReturn(NOW.plus(Duration.ofHours(24)));

            assertThatThrownBy(() -> service.cancelBooking(cancel("A1")))
                    .isInstanceOf(CancellationWindowClosedException.class)
                    .hasMessageContaining("24 hours before kickoff");
            verifyNoInteractions(seatPricingPort, cancellationLock, sagaSteps);
        }

        @Test
        void aMinuteBeforeTheDeadlineIsStillInTime() {
            when(showtimeSchedulePort.kickoffOf(SHOWTIME_ID)).thenReturn(NOW.plus(Duration.ofHours(24)).plusSeconds(60));
            when(seatPricingPort.pricesOf(SHOWTIME_ID, List.of("A1"))).thenReturn(PRICES);
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), eq(List.of("A1")), any(), anyString()))
                    .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.CANCELLED_NOW, List.of("A1"),
                            new BigDecimal("100000"), new BigDecimal("100000"), false));
            when(sagaSteps.findOrThrow(BOOKING_ID))
                    .thenReturn(booking(BookingStatus.CONFIRMED, true, List.of("A1"), new BigDecimal("100000")));

            assertThat(service.cancelBooking(cancel("A1")).getCancelledSeatCodes()).containsExactly("A1");
        }

        @Test
        void ticketsStillBeingIssuedAre409AndNothingIsRead() {
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(booking(BookingStatus.CONFIRMED, false, List.of(), BigDecimal.ZERO)));

            assertThatThrownBy(() -> service.cancelBooking(cancel("A1")))
                    .isInstanceOf(TicketIssuanceInProgressException.class);
            verifyNoInteractions(showtimeSchedulePort, seatPricingPort, cancellationLock, sagaSteps);
        }
    }

    @Nested
    class Concurrency {

        @BeforeEach
        void unpaidBooking() {
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, false, List.of(), BigDecimal.ZERO)));
            when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());
        }

        /** User decision: the second of two concurrent cancels is answered 409 at once. */
        @Test
        void aCancelThatFindsTheLockHeldIs409AndWritesNothing() {
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelBooking(cancel()))
                    .isInstanceOf(CancellationInProgressException.class);
            verify(sagaSteps, never()).cancelSeatsByCustomer(anyString(), anyList(), any(), anyString());
            verify(cancelledSeatsStore, never()).record(anyString(), anyList());
        }

        @Test
        void anOptimisticLockFailureIsRetriedOnceOnTheFreshRow() {
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), anyList(), any(), anyString()))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, BOOKING_ID))
                    .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.ALREADY_CANCELLED, SEATS,
                            BigDecimal.ZERO, BigDecimal.ZERO, true));
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(booking(BookingStatus.CANCELLED, false, SEATS, BigDecimal.ZERO));

            Booking result = service.cancelBooking(cancel());

            assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(sagaSteps, times(2)).cancelSeatsByCustomer(eq(BOOKING_ID), anyList(), any(), anyString());
            verify(claim).close();
        }

        @Test
        void aSecondOptimisticLockFailureIs409AndTheLockIsStillReleased() {
            when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(claim));
            when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), anyList(), any(), anyString()))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, BOOKING_ID));

            assertThatThrownBy(() -> service.cancelBooking(cancel()))
                    .isInstanceOf(CancellationConflictException.class);
            verify(claim).close();
            verify(cancelledSeatsStore, never()).record(anyString(), anyList());
        }
    }

    @Test
    void aClaimFailingToCloseIsTheAdaptersProblemNotTheRequests() {
        // The port promises close() never throws; a mock that honours it is enough to pin that the
        // service does not depend on anything else from the claim.
        BookingCancellationLock.Claim quietClaim = mock(BookingCancellationLock.Claim.class);
        when(bookingRepository.findById(BOOKING_ID))
                .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, false, List.of(), BigDecimal.ZERO)));
        when(cancelledSeatsStore.find(BOOKING_ID)).thenReturn(Set.of());
        when(cancellationLock.tryAcquire(BOOKING_ID)).thenReturn(Optional.of(quietClaim));
        when(sagaSteps.cancelSeatsByCustomer(eq(BOOKING_ID), anyList(), any(), anyString()))
                .thenReturn(new CancellationOutcome(CancellationOutcome.Kind.CANCELLED_NOW, SEATS,
                        BigDecimal.ZERO, BigDecimal.ZERO, true));
        when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(booking(BookingStatus.CANCELLED, false, SEATS, BigDecimal.ZERO));

        service.cancelBooking(cancel());

        verify(quietClaim).close();
    }
}
