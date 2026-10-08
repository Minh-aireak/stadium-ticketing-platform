package com.aireak.booking.application.service;

import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import com.aireak.booking.application.port.out.InventoryConfirmationRefusedException;
import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.exception.ResourceNotFoundException;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private static final String CUSTOMER_EMAIL = "customer-1@example.com";
    private static final String SHOWTIME_ID = "showtime-1";
    private static final List<String> SEAT_CODES = List.of("A1", "A2");
    private static final BigDecimal AMOUNT = new BigDecimal("150.00");
    // Distinct from AMOUNT (the client-supplied placeholder) so tests can verify the saga
    // charges the server-computed price returned by ticketInventoryPort.reserveSeats(), never
    // the client's amount.
    private static final BigDecimal SERVER_AMOUNT = new BigDecimal("175.00");
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
        when(sagaSteps.createDraftBooking(any(), anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(BOOKING_ID);
    }

    private void stubReserveSeats() {
        when(ticketInventoryPort.reserveSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES))
                .thenReturn(new TicketInventoryPort.ReservedPrice(SERVER_AMOUNT, CURRENCY));
    }

    /**
     * The body below is invented by this test, and has to be: no payment-service on this platform
     * sends it. {@code PaymentController#initiate} catches {@code DuplicatePaymentException} and
     * answers 202 Accepted -- pinned there by
     * {@code initiateReturnsAcceptedWhenAnIdempotentPaymentAttemptIsStillInProgress} -- so
     * {@code initiatePayment} returns normally on the live path and never reaches the branch this
     * exercises at all. Both halves came from one commit, 6e2a1ee, which is why they have never
     * disagreed loudly enough to be noticed.
     *
     * <p>Kept rather than deleted because the branch is the safety net for the day the 202 catch
     * goes: what it must never do is cancel a booking whose gateway charge is still running.
     * {@code PaymentRestAdapterTest} pins both answers as they leave the adapter, so the claim
     * that only one of them exists today is checked against a real HTTP exchange rather than
     * against this stub.
     */
    @Test
    void a4xxRefusalNamingAnInFlightPaymentIsAcceptedThoughPaymentServiceAnswers202Instead() {
        when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
        stubCreateDraftBooking();
        stubReserveSeats();
        HttpClientErrorException alreadyProcessing = HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                HttpHeaders.EMPTY,
                ("{\"type\":\"https://aireak.com/errors/domain-error\"," +
                        "\"detail\":\"Payment for booking " + BOOKING_ID +
                        " is already being processed\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        doThrow(alreadyProcessing).when(paymentPort)
                .initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);

        BookingCreationResult result = service.createBooking(
                "idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

        assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
        assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(sagaSteps).recordCreationSucceeded(BOOKING_ID);
        verify(idempotencyStore).complete("idem-1", BOOKING_ID);
        verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
        verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        verify(paymentPort, never()).checkOutcome(anyString());
    }

    @Nested
    class HappyPath {

        @Test
        void createBookingWithoutIdempotencyKeyReturnsPendingPaymentAndNeverTouchesIdempotencyStore() {
            stubCreateDraftBooking();
            stubReserveSeats();

            BookingCreationResult result =
                    service.createBooking(null, CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
            verify(ticketInventoryPort).reserveSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).applyReservedPrice(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            verify(sagaSteps).markPendingPayment(BOOKING_ID);
            // Charges the server-computed price, never the client-supplied AMOUNT.
            verify(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            verify(sagaSteps).recordCreationSucceeded(BOOKING_ID);
            verifyNoInteractions(idempotencyStore);
        }

        @Test
        void createBookingWithFreshIdempotencyKeyClaimsAndCompletesIt() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            stubReserveSeats();

            service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

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

            BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo("existing-booking");
            assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void inProgressClaimThrowsDuplicateRequestException() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.InProgress());

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isInstanceOf(DuplicateRequestInProgressException.class);

            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void redisMissFallsBackToDbAndReplaysWithoutRerunningTheSaga() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            Booking existing = pendingPaymentBooking("existing-booking");
            when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

            BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

            assertThat(result.bookingId()).isEqualTo("existing-booking");
            verify(idempotencyStore).complete("idem-1", "existing-booking");
            verifyNoInteractions(sagaSteps, ticketInventoryPort, paymentPort);
        }

        @Test
        void uniqueKeyRaceOnCreateDraftBookingShortCircuitsAndReturnsTheWinningRequestsBooking() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            DataIntegrityViolationException raceException = new DataIntegrityViolationException("duplicate key");
            when(sagaSteps.createDraftBooking(eq("idem-1"), anyString(), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(raceException);
            Booking winner = pendingPaymentBooking("other-request-booking");
            when(bookingRepository.findByIdempotencyKey("idem-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));

            BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

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

        /**
         * Step 1's own compensation, and the reason {@code createDraftBooking} is wrapped at all.
         * The claim is taken before Step 1 runs, and only the unique-key race is handled inside
         * {@code createDraftBooking} — so without the surrounding catch, any other failure there
         * (MaxTicketsExceededException, a transient DB error) would propagate straight out with
         * the claim still held. The Redis IN_PROGRESS marker would then stick for its full 90s
         * TTL and answer the customer's perfectly legitimate retry with a 409, for a booking that
         * was never created and is not in flight anywhere.
         */
        @Test
        void draftBookingFailureReleasesTheIdempotencyClaimBeforeRethrowing() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            when(bookingRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
            RuntimeException failure = new RuntimeException("transient failure creating draft booking");
            when(sagaSteps.createDraftBooking(eq("idem-1"), anyString(), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(failure);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(failure);

            verify(idempotencyStore).release("idem-1");
            // Nothing downstream of Step 1 ran, so there is nothing else to compensate.
            verifyNoInteractions(ticketInventoryPort, paymentPort);
        }

        @Test
        void seatReservationFailureCancelsBookingAndReleasesClaimThenRethrows() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            RuntimeException reservationFailure = new OutboundServiceUnavailableException(
                    "Ticket inventory unavailable", new RuntimeException("timeout"));
            doThrow(reservationFailure).when(ticketInventoryPort)
                    .reserveSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(reservationFailure);

            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
            verify(sagaSteps, never()).markPendingPayment(anyString());
            verify(paymentPort, never()).initiatePayment(anyString(), any(), anyString());
        }

        @Test
        void applyReservedPriceFailureReleasesSeatsCancelsBookingAndReleasesClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            stubReserveSeats();
            RuntimeException persistFailure = new RuntimeException("db error");
            doThrow(persistFailure).when(sagaSteps).applyReservedPrice(BOOKING_ID, SERVER_AMOUNT, CURRENCY);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(persistFailure);

            // Seats were already held by the (successful) reserveSeats call above, so this
            // failure must release them like any other post-reservation failure.
            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
            verify(sagaSteps, never()).markPendingPayment(anyString());
            verify(paymentPort, never()).initiatePayment(anyString(), any(), anyString());
        }

        @Test
        void markPendingPaymentFailureReleasesSeatsCancelsBookingAndReleasesClaim() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            stubReserveSeats();
            RuntimeException transitionFailure = new RuntimeException("db error");
            doThrow(transitionFailure).when(sagaSteps).markPendingPayment(BOOKING_ID);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
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
            stubReserveSeats();
            HttpClientErrorException rejected = HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
            doThrow(rejected).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
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
            stubReserveSeats();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(PaymentPort.PaymentOutcome.SUCCEEDED);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pendingPaymentBooking(BOOKING_ID));
            when(bookingRepository.findById(BOOKING_ID))
                    .thenReturn(Optional.of(confirmedBooking(BOOKING_ID)));

            BookingCreationResult result =
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY);

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
            stubReserveSeats();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(PaymentPort.PaymentOutcome.SUCCEEDED);
            // PaymentResultConsumer already resolved this booking on another thread —
            // confirmBooking()'s own guard/state-check fails.
            when(sagaSteps.findOrThrow(BOOKING_ID))
                    .thenThrow(new IllegalArgumentException("Booking not found"));

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
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
            stubReserveSeats();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(PaymentPort.PaymentOutcome.FAILED);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            verify(sagaSteps).cancelBooking(eq(BOOKING_ID), anyString());
            verify(idempotencyStore).release("idem-1");
        }

        @Test
        void stillAmbiguousOutcomeLeavesBookingUntouchedAndReleasesClaimForRetry() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            stubReserveSeats();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(PaymentPort.PaymentOutcome.UNKNOWN);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            // Genuinely unknown outcome: must NOT cancel/release seats — the async
            // PaymentResultConsumer or the reconciliation job is the real backstop.
            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
            verify(idempotencyStore).release("idem-1");
        }

        /**
         * NOT_FOUND is a firm answer from payment-service, and BookingReconciliationJob does act
         * on it — but not here. This is the one moment the initiate request may still be on its
         * way to payment-service's INITIATED commit, so in-line it has to be treated exactly like
         * no answer at all.
         */
        @Test
        void paymentNotFoundRightAfterTheTimeoutIsNotYetGroundsToCancel() {
            when(idempotencyStore.claim("idem-1")).thenReturn(new IdempotencyClaim.Claimed());
            stubCreateDraftBooking();
            stubReserveSeats();
            OutboundServiceUnavailableException ambiguous = new OutboundServiceUnavailableException(
                    "Payment service unavailable", new RuntimeException("timeout"));
            doThrow(ambiguous).when(paymentPort).initiatePayment(BOOKING_ID, SERVER_AMOUNT, CURRENCY);
            when(paymentPort.checkOutcome(BOOKING_ID)).thenReturn(PaymentPort.PaymentOutcome.NOT_FOUND);

            assertThatThrownBy(() ->
                    service.createBooking("idem-1", CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID, SEAT_CODES, AMOUNT, CURRENCY))
                    .isSameAs(ambiguous);

            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
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
        void requestsRefundWhenPaymentSucceedsAfterBookingWasCancelled() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(cancelledBooking(BOOKING_ID));

            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps, never()).markConfirmed(anyString());
            verify(sagaSteps).requestRefundForLatePayment(
                    BOOKING_ID, "Payment succeeded after booking cancellation");
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
            // ...but only a failure that might clear. A refusal takes the other branch below.
            verify(sagaSteps, never()).markInventorySaleRefused(anyString());
        }

        /**
         * The seat is SOLD to a different booking, so no number of retries can make it this
         * booking's. Left as an ordinary failure it stayed in
         * InventoryConfirmationReconciler's query forever: re-asked every five minutes, logged
         * identically to a dependency having a bad afternoon, and permanently holding a slot in
         * that job's capped batch.
         */
        @Test
        void aRefusedSaleIsRecordedInsteadOfBeingLeftForTheReconciler() {
            Booking pending = pendingPaymentBooking(BOOKING_ID);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pending);
            doThrow(new InventoryConfirmationRefusedException(
                    "Seat A1 already sold to booking other-booking, cannot confirm for booking " + BOOKING_ID, null))
                    .when(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            // Still must not throw: the booking is durably CONFIRMED either way.
            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps).markConfirmed(BOOKING_ID);
            verify(sagaSteps).markInventorySaleRefused(BOOKING_ID);
            // Never claimed as confirmed, because it never was.
            verify(sagaSteps, never()).markInventoryConfirmed(anyString());
        }

        /**
         * And recording it is itself best-effort. If that write fails the booking stays in the
         * reconciler's queue, gets refused again, and comes back here -- which is the right
         * outcome. Letting it escape would fail the Kafka listener for a booking that is already
         * durably CONFIRMED.
         */
        @Test
        void aRefusalThatCannotBeRecordedIsStillNotAllowedToFailTheListener() {
            Booking pending = pendingPaymentBooking(BOOKING_ID);
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pending);
            doThrow(new InventoryConfirmationRefusedException("Seat A1 already sold", null))
                    .when(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
            doThrow(new RuntimeException("database is down"))
                    .when(sagaSteps).markInventorySaleRefused(BOOKING_ID);

            service.confirmBooking(BOOKING_ID);

            verify(sagaSteps).markInventorySaleRefused(BOOKING_ID);
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
            // A retry that failed for a reason that might clear stays in the queue.
            verify(sagaSteps, never()).markInventorySaleRefused(anyString());
        }

        /**
         * The reconciler is where a refusal is most likely to be seen, not confirmBooking: by the
         * time this job runs the booking's Redis hold (TTL 10 minutes) has usually lapsed, which
         * is exactly how another customer came to buy the seat. The retry has to be able to give
         * up, or the job re-asks this booking for the life of the row.
         */
        @Test
        void aRetryThatIsRefusedForGoodStopsBeingRetried() {
            Booking alreadyConfirmed = confirmedBooking(BOOKING_ID);
            doThrow(new InventoryConfirmationRefusedException(
                    "Seat A1 already sold to booking other-booking, cannot confirm for booking " + BOOKING_ID, null))
                    .when(ticketInventoryPort).confirmReservation(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);

            service.retryInventoryConfirmation(alreadyConfirmed);

            verify(sagaSteps).markInventorySaleRefused(BOOKING_ID);
            verify(sagaSteps, never()).markInventoryConfirmed(anyString());
        }
    }

    @Nested
    class CancelBookingOnPaymentFailure {

        @Test
        void cancelsAndReleasesSeatsWhenPendingPayment() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(pendingPaymentBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, "declined");

            verify(sagaSteps).cancelBooking(BOOKING_ID, "declined");
            verify(ticketInventoryPort).releaseSeats(SHOWTIME_ID, BOOKING_ID, SEAT_CODES);
        }

        @Test
        void isNoOpWhenAlreadyConfirmedSoSoldSeatsAreNeverReleased() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(confirmedBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, "late failure signal");

            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        }

        @Test
        void isNoOpWhenAlreadyCancelled() {
            when(sagaSteps.findOrThrow(BOOKING_ID)).thenReturn(cancelledBooking(BOOKING_ID));

            service.cancelBookingOnPaymentFailure(BOOKING_ID, "redelivered event");

            verify(sagaSteps, never()).cancelBooking(anyString(), anyString());
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
        }
    }

    @Nested
    class CancelBookingsForShowtime {

        // Whether a refund is owed is now decided inside the aggregate, which raises
        // RefundRequestedEvent alongside the cancellation (see BookingTest) — this service just
        // cancels each booking and never calls payment-service directly at all.
        @Test
        void cancelsEveryActiveBookingAndNeverReleasesSeatsOrCallsPaymentDirectly() {
            when(bookingRepository.findActiveByShowtimeId(SHOWTIME_ID))
                    .thenReturn(List.of(pendingPaymentBooking("booking-draft"), confirmedBooking("booking-paid")));

            service.cancelBookingsForShowtime(SHOWTIME_ID, "Match cancelled");

            verify(sagaSteps).cancelBookingDueToMatchCancellation("booking-draft", "Match cancelled");
            verify(sagaSteps).cancelBookingDueToMatchCancellation("booking-paid", "Match cancelled");
            verify(ticketInventoryPort, never()).releaseSeats(anyString(), anyString(), any());
            verifyNoInteractions(paymentPort);
        }

        @Test
        void isNoOpWhenNoActiveBookingsExistForTheShowtime() {
            when(bookingRepository.findActiveByShowtimeId(SHOWTIME_ID)).thenReturn(List.of());

            service.cancelBookingsForShowtime(SHOWTIME_ID, "Match cancelled");

            verify(sagaSteps, never()).cancelBookingDueToMatchCancellation(anyString(), anyString());
            verifyNoInteractions(paymentPort);
        }

        /**
         * The loop has no per-booking try/catch, on purpose: a cancellation that fails has to
         * reach MatchCancelledConsumer so DefaultErrorHandler retries it and, failing that, puts
         * the record on the dead-letter topic where MatchCancelledDeadLetterConsumer alerts on it.
         * Swallowing one would lose a refund silently, which is worse.
         *
         * <p>What that means, and what this pins, is that the throw leaves PARTIAL progress
         * behind: bookings earlier in the list are already CANCELLED and have already raised
         * RefundRequestedEvent. Reachable -- bookings carries a JPA {@code @Version}, so a
         * PaymentResultConsumer confirming a booking for this same showtime concurrently is enough
         * to make save() throw ObjectOptimisticLockingFailureException. The dead-letter alert's
         * wording is corrected in the same commit as this test, because it told the operator that
         * nothing had happened.
         */
        @Test
        void aFailurePartWayThroughLeavesTheBookingsBeforeItAlreadyCancelled() {
            when(bookingRepository.findActiveByShowtimeId(SHOWTIME_ID))
                    .thenReturn(List.of(confirmedBooking("booking-1"), confirmedBooking("booking-2"),
                            confirmedBooking("booking-3")));
            // One stub covering every invocation rather than one keyed to "booking-2": under
            // MockitoExtension's default STRICT_STUBS an argument-specific stub makes the first
            // call, for booking-1, a stubbing mismatch instead of the plain call it is.
            doAnswer(invocation -> {
                if ("booking-2".equals(invocation.getArgument(0))) {
                    throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                            Booking.class, "booking-2");
                }
                return null;
            }).when(sagaSteps).cancelBookingDueToMatchCancellation(anyString(), anyString());

            assertThatThrownBy(() -> service.cancelBookingsForShowtime(SHOWTIME_ID, "Match cancelled"))
                    .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);

            // Already done, and already owed a refund -- not "NOT cancelled".
            verify(sagaSteps).cancelBookingDueToMatchCancellation("booking-1", "Match cancelled");
            // Never reached, which is the half the alert was right about.
            verify(sagaSteps, never()).cancelBookingDueToMatchCancellation("booking-3", "Match cancelled");
        }
    }

    @Nested
    class ListMyBookings {

        @Test
        void returnsThePageAndTotalFromTheRepository() {
            Booking booking = confirmedBooking(BOOKING_ID);
            when(bookingRepository.findByCustomerId(CUSTOMER_ID, 0, 20)).thenReturn(List.of(booking));
            when(bookingRepository.countByCustomerId(CUSTOMER_ID)).thenReturn(1L);

            var page = service.listByCustomer(CUSTOMER_ID, 0, 20);

            assertThat(page.items()).containsExactly(booking);
            assertThat(page.totalElements()).isEqualTo(1L);
            assertThat(page.page()).isEqualTo(0);
            assertThat(page.size()).isEqualTo(20);
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
        return Booking.reconstitute(bookingId, CUSTOMER_ID, CUSTOMER_EMAIL, SHOWTIME_ID,
                new SeatSelection(SEAT_CODES), BookingAmount.of(AMOUNT, CURRENCY),
                status, java.time.Instant.now(), null, 0L, false, false);
    }
}
