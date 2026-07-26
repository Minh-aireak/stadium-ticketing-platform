package com.aireak.booking.application.service;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the saga orchestrator. Every collaborator is mocked — this only
 * exercises {@link BookingOrchestrationService}'s own control flow (compensation,
 * idempotency, ambiguous-payment reconciliation), not the collaborators' behavior.
 */
@ExtendWith(MockitoExtension.class)
class BookingOrchestrationServiceTest {

    private static final String CUSTOMER_ID = "customer-1";
    private static final String SHOWTIME_ID = "showtime-1";
    private static final List<String> SEAT_CODES = List.of("A1", "A2");
    private static final BigDecimal AMOUNT = new BigDecimal("150.00");
    private static final String CURRENCY = "USD";
    private static final String BOOKING_ID = "booking-123";

    @Mock
    private BookingSagaSteps sagaSteps;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private TicketInventoryPort ticketInventoryPort;
    @Mock
    private PaymentPort paymentPort;
    @Mock
    private IdempotencyStore idempotencyStore;

    private BookingOrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new BookingOrchestrationService(
                sagaSteps, bookingRepository, ticketInventoryPort, paymentPort, idempotencyStore);
    }

    private void stubCreateDraftBooking() {
        when(sagaSteps.createDraftBooking(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(BOOKING_ID);
    }

    @Nested
    class HappyPath {

        @Test
        void createBookingWithoutIdempotencyKeyReturnsPendingPaymentAndNeverTouchesIdempotencyStore() {
            stubCreateDraftBooking();

            BookingOrchestrationService.BookingCreationResult result =
                    service.createBooking(null, CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            verify(ticketInventoryPort).reserveSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).markPendingPayment(BOOKING_ID);
            verify(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);
            verify(sagaSteps).recordCreationSucceeded(BOOKING_ID);
            verifyNoInteractions(idempotencyStore);
        }

        @Test
        void createBookingWithFreshIdempotencyKeyClaimsAndCompletesIt() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();

            service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            verify(idempotencyStore).complete("idem-1", BOOKING_ID);
            verify(idempotencyStore, never()).release(anyString());
        }
    }

    @Nested
    class IdempotencyReplay {

        @Test
        void completedClaimShortCircuitsAndReturnsExistingBookingWithoutRunningTheSaga() {
            when(idempotencyStore.claim("idem-1"))
                    .thenReturn(new IdempotencyClaim.Completed("existing-booking"));
            when(bookingRepository.findById("existing-booking"))
                    .thenReturn(Optional.of(confirmedBooking("existing-booking")));

            BookingOrchestrationService.BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo("existing-booking");
            assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void inProgressClaimThrowsDuplicateRequestException() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.InProgress());

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isInstanceOf(DuplicateRequestInProgressException.class);

            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void redisMissFallsBackToDbAndReplaysWithoutRerunningTheSaga() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            Booking existing = pendingPaymentBooking("existing-booking");
            when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

            BookingOrchestrationService.BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo("existing-booking");
            verify(idempotencyStore).complete("idem-1", "existing-booking");
            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void uniqueKeyRaceOnCreateDraftBookingShortCircuitsAndReturnsTheWinningRequestsBooking() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            DataIntegrityViolationException raceException = new DataIntegrityViolationException("duplicate key");
            when(sagaSteps.createDraftBooking(eq("idem-1"), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(raceException);
            Booking winner = pendingPaymentBooking("other-request-booking");
            when(bookingRepository.findByIdempotencyKey("idem-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));

            BookingOrchestrationService.BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo("other-request-booking");
            assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            // Returns immediately with the winner's result — never re-runs Steps 2-5 against a
            // booking this request doesn't own, and reuses the Booking already read inside
            // createDraftBooking's catch block instead of a second findById() round-trip.
            verify(idempotencyStore, times(1)).complete("idem-1", "other-request-booking");
            verify(bookingRepository, never()).findById(anyString());
            verifyNoInteractions(ticketInventoryPort, paymentPort);
        }
    }

    @Nested
    class Compensation {

        @Test
        void seatReservationFailureCancelsBookingAndReleasesClaimThenRethrows() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            RuntimeException reservationFailure = new OutboundServiceUnavailableException(
                    "Ticket inventory unavailable", new RuntimeException("timeout"));
            doThrow(reservationFailure).when(ticketInventoryPort)
                    .reserveSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(reservationFailure);

            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
            verify(sagaSteps, never()).markPendingPayment(anyString());
            verify(paymentPort, never()).initiatePayment(anyString(), any(), anyString());
        }

        @Test
        void markPendingPaymentFailureReleasesSeatsCancelsBookingAndReleasesClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            RuntimeException transitionFailure = new RuntimeException("db error");
            doThrow(transitionFailure).when(sagaSteps).markPendingPayment(BOOKING_ID);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(transitionFailure);

            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
            verify(paymentPort, never()).initiatePayment(anyString(), any(), anyString());
        }

        @Test
        void definiteHttpErrorOnPaymentInitiationCompensatesImmediatelyWithoutReconciliation() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            HttpClientErrorException rejected = HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
            doThrow(rejected).when(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(rejected);

            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
            // A definite HTTP response means payment never started — no need to ask checkOutcome.
            verify(paymentPort, never()).checkOutcome(anyString());
        }
    }

    @Nested
    class AmbiguousPaymentReconciliation {

        @Test
        void ambiguousFailureReconciledAsSucceededConfirmsBookingSynchronouslyWithoutReleasingClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID))
                    .thenReturn(Optional.of(PaymentPort.PaymentOutcome.SUCCEEDED));
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pendingPaymentBooking(BOOKING_ID));
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(confirmedBooking(BOOKING_ID)));

            BookingOrchestrationService.BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
            verify(sagaSteps).markConfirmed(BOOKING_ID);
            verify(idempotencyStore).complete("idem-1", BOOKING_ID);
            verify(idempotencyStore, never()).release(anyString());
            // Cancellation/seat release must never happen once reconciliation says SUCCEEDED.
            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        }

        @Test
        void ambiguousFailureReconciledAsSucceededButLostRaceToConsumerFallsBackSafely() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID))
                    .thenReturn(Optional.of(PaymentPort.PaymentOutcome.SUCCEEDED));
            // PaymentResultConsumer already resolved this booking on another thread —
            // confirmBooking()'s own guard/state-check fails.
            when(sagaSteps.findOrThrow(BOOKING_ID))
                    .thenThrow(new IllegalArgumentException("Booking not found"));

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            // Must still release the claim and surface the ORIGINAL ambiguous exception,
            // not the confirmBooking() race exception, so a client retry is possible.
            verify(idempotencyStore).release("idem-1");
            verify(idempotencyStore, never()).complete(anyString(), anyString());
        }

        @Test
        void ambiguousFailureReconciledAsFailedCancelsBookingAndReleasesClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID))
                    .thenReturn(Optional.of(PaymentPort.PaymentOutcome.FAILED));

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
        }

        @Test
        void stillAmbiguousOutcomeLeavesBookingUntouchedAndReleasesClaimForRetry() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            // Genuinely unknown outcome: must NOT cancel/release seats — the async
            // PaymentResultConsumer or the reconciliation job is the real backstop.
            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
            verify(idempotencyStore).release("idem-1");
        }
    }

    @Nested
    class KnownGap {

        // This currently FAILS against the present implementation: createBooking() claims
        // the idempotency key BEFORE calling createDraftBooking(), but only wraps that call's
        // DataIntegrityViolationException case — any other failure (e.g. MaxTicketsExceededException,
        // a transient DB error) propagates straight out of createBooking() without releasing the
        // claim. The Redis IN_PROGRESS marker then sticks for its full 90s TTL, so a legitimate
        // retry with the same Idempotency-Key gets a 409 DuplicateRequestInProgressException even
        // though nothing is actually in flight. See BookingOrchestrationService#createBooking —
        // the fix is to wrap the createDraftBooking(...) call in the same
        // try { ... } catch (Exception e) { releaseIdempotencyClaim(idempotencyKey); throw e; }
        // shape already used for every later step.
        @Test
        void createDraftBookingFailureShouldReleaseTheIdempotencyClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
            RuntimeException failure = new RuntimeException("transient failure creating draft booking");
            when(sagaSteps.createDraftBooking(eq("idem-1"), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(failure);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(failure);

            verify(idempotencyStore).release("idem-1");
        }
    }

    @Nested
    class ConfirmBooking {

        @Test
        void confirmsAndFinalizesReservationWhenPendingPayment() {
            Booking pending = pendingPaymentBooking(BOOKING_ID);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pending);

            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps).markConfirmed(BOOKING_ID);
            verify(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).markInventoryConfirmed(BOOKING_ID);
        }

        @Test
        void isNoOpWhenBookingAlreadyConfirmed() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(confirmedBooking(BOOKING_ID));

            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps, never()).markConfirmed(anyString());
            verify(ticketInventoryPort, never()).confirmReservation(anyString(), anyString(), any());
        }

        @Test
        void isNoOpWhenBookingAlreadyCancelled() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(cancelledBooking(BOOKING_ID));

            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps, never()).markConfirmed(anyString());
        }

        @Test
        void confirmReservationFailureIsSwallowedAndLeftForReconciliation() {
            Booking pending = pendingPaymentBooking(BOOKING_ID);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pending);
            doThrow(new RuntimeException("ticket-inventory unavailable"))
                    .when(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            // Must not throw: the booking is already durably CONFIRMED (markConfirmed already
            // committed) — a failure here must not surface as a caller-visible error.
            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps).markConfirmed(BOOKING_ID);
            // Left false on failure so InventoryConfirmationReconciler picks it up later.
            verify(sagaSteps, never()).markInventoryConfirmed(anyString());
        }
    }

    @Nested
    class RetryInventoryConfirmation {

        @Test
        void retriesConfirmReservationAndMarksItConfirmedOnSuccessWithoutTheConfirmBookingGuard() {
            Booking alreadyConfirmed = confirmedBooking(BOOKING_ID);

            service.retryInventoryConfirmation(alreadyConfirmed);

            verify(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).markInventoryConfirmed(BOOKING_ID);
            // No PENDING_PAYMENT guard: the caller-supplied Booking is used directly, so
            // findOrThrow/markConfirmed (confirmBooking()'s own steps) are never touched.
            verify(sagaSteps, never()).findOrThrow(anyString());
            verify(sagaSteps, never()).markConfirmed(anyString());
        }

        @Test
        void retryFailureIsSwallowed() {
            Booking alreadyConfirmed = confirmedBooking(BOOKING_ID);
            doThrow(new RuntimeException("still unavailable"))
                    .when(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            service.retryInventoryConfirmation(alreadyConfirmed);

            verify(sagaSteps, never()).markInventoryConfirmed(anyString());
        }
    }

    @Nested
    class CancelBookingOnPaymentFailure {

        @Test
        void cancelsAndReleasesSeatsWhenPendingPayment() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pendingPaymentBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, SHOWTIME_ID, SEAT_CODES, "declined");

            verify(sagaSteps).cancelBooking(BOOKING_ID, "declined");
            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
        }

        @Test
        void isNoOpWhenAlreadyConfirmedSoSoldSeatsAreNeverReleased() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(confirmedBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, SHOWTIME_ID, SEAT_CODES, "late failure signal");

            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        }

        @Test
        void isNoOpWhenAlreadyCancelled() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(cancelledBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, SHOWTIME_ID, SEAT_CODES, "redelivered event");

            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        }
    }

    private static Booking pendingPaymentBooking(String bookingId) {
        Booking booking = reconstituted(bookingId, BookingStatus.DRAFT);
        booking.markPendingPayment();
        return booking;
    }

    private static Booking confirmedBooking(String bookingId) {
        Booking booking = pendingPaymentBooking(bookingId);
        booking.confirm();
        booking.pullDomainEvents();
        return booking;
    }

    private static Booking cancelledBooking(String bookingId) {
        Booking booking = reconstituted(bookingId, BookingStatus.DRAFT);
        booking.cancel("test cancellation");
        booking.pullDomainEvents();
        return booking;
    }

    private static Booking reconstituted(String bookingId, BookingStatus status) {
        return Booking.reconstitute(bookingId, CUSTOMER_ID, SHOWTIME_ID,
                new SeatSelection(SEAT_CODES), BookingAmount.of(AMOUNT, CURRENCY),
                status, java.time.Instant.now(), null, 0L, false);
    }
}
