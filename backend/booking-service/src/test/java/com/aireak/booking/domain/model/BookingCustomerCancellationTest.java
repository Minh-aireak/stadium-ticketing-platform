package com.aireak.booking.domain.model;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.booking.domain.event.SeatsReturnRequestedEvent;
import com.aireak.booking.domain.exception.CancellationConflictException;
import com.aireak.booking.domain.exception.CancellationWindowClosedException;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.exception.SeatCancellationException;
import com.aireak.booking.domain.exception.TicketIssuanceInProgressException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-seat cancellation (FR-21, extended to paid bookings): what may be cancelled, what is refunded,
 * and which requests the booking raises for the other services.
 */
class BookingCustomerCancellationTest {

    private static final List<String> SEATS = List.of("A1", "A2", "A3");
    private static final BigDecimal AMOUNT = new BigDecimal("450000");
    private static final SeatRefundQuote QUOTE = new SeatRefundQuote(Map.of(
            "A1", new BigDecimal("100000"), "A2", new BigDecimal("150000"), "A3", new BigDecimal("200000")));
    private static final String REASON = "Cancelled by the customer";

    private static Booking booking(BookingStatus status, boolean inventoryConfirmed, boolean saleRefused,
                                   List<String> cancelled, BigDecimal refunded) {
        return Booking.reconstitute("booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(SEATS), BookingAmount.of(AMOUNT, "VND"), status, Instant.now(), null, 1L,
                inventoryConfirmed, saleRefused, cancelled, refunded);
    }

    private static Booking paid() {
        return booking(BookingStatus.CONFIRMED, true, false, List.of(), BigDecimal.ZERO);
    }

    @Nested
    class Plan {

        @Test
        void seatsThatAreNotTheBookingsAreRefused() {
            assertThatThrownBy(() -> paid().planCustomerCancellation(List.of("A1", "Z9")))
                    .isInstanceOf(SeatCancellationException.class)
                    .hasMessageContaining("Z9");
        }

        @Test
        void anEmptyRequestMeansEverySeatStillHeld() {
            Booking partly = booking(BookingStatus.CONFIRMED, true, false, List.of("A1"), new BigDecimal("100000"));

            assertThat(partly.planCustomerCancellation(List.of()))
                    .isEqualTo(new CustomerCancellation.Paid(List.of("A2", "A3")));
        }

        @Test
        void seatsAlreadyCancelledMakeTheRequestARepeat() {
            Booking partly = booking(BookingStatus.CONFIRMED, true, false, List.of("A1"), new BigDecimal("100000"));

            assertThat(partly.planCustomerCancellation(List.of("A1")))
                    .isInstanceOf(CustomerCancellation.AlreadyCancelled.class);
        }

        @Test
        void aBookingCancelledBeforeSeatsCouldBeCancelledSinglyCountsEverySeatAsCancelled() {
            Booking legacy = booking(BookingStatus.CANCELLED, false, false, List.of(), BigDecimal.ZERO);

            assertThat(legacy.getCancelledSeatCodes()).containsExactlyElementsOf(SEATS);
            assertThat(legacy.activeSeatCodes()).isEmpty();
            assertThat(legacy.planCustomerCancellation(List.of("A2")))
                    .isInstanceOf(CustomerCancellation.AlreadyCancelled.class);
        }

        @Test
        void aDraftIsRefused() {
            Booking draft = booking(BookingStatus.DRAFT, false, false, List.of(), BigDecimal.ZERO);

            assertThatThrownBy(() -> draft.planCustomerCancellation(List.of()))
                    .isInstanceOf(InvalidBookingStatusException.class);
        }

        @Test
        void anUnpaidBookingGoesWholeOrNotAtAll() {
            Booking unpaid = booking(BookingStatus.PENDING_PAYMENT, false, false, List.of(), BigDecimal.ZERO);

            assertThat(unpaid.planCustomerCancellation(List.of())).isEqualTo(new CustomerCancellation.Unpaid(SEATS));
            assertThat(unpaid.planCustomerCancellation(List.of("A3", "A1", "A2")))
                    .isInstanceOf(CustomerCancellation.Unpaid.class);
            assertThatThrownBy(() -> unpaid.planCustomerCancellation(List.of("A1")))
                    .isInstanceOf(SeatCancellationException.class)
                    .hasMessageContaining("as a whole");
        }

        @Test
        void aPaidBookingWhoseSeatsAreStillBeingSoldMustWait() {
            Booking issuing = booking(BookingStatus.CONFIRMED, false, false, List.of(), BigDecimal.ZERO);

            assertThatThrownBy(() -> issuing.planCustomerCancellation(List.of("A1")))
                    .isInstanceOf(TicketIssuanceInProgressException.class);
        }

        @Test
        void aPaidBookingWhoseSaleWasRefusedForGoodNeedNotWait() {
            Booking refused = booking(BookingStatus.CONFIRMED, false, true, List.of(), BigDecimal.ZERO);

            assertThat(refused.planCustomerCancellation(List.of("A1")))
                    .isEqualTo(new CustomerCancellation.Paid(List.of("A1")));
        }

        @Test
        void aSeatNamedTwiceIsCancelledOnce() {
            assertThat(paid().planCustomerCancellation(List.of("A2", "A2")))
                    .isEqualTo(new CustomerCancellation.Paid(List.of("A2")));
        }
    }

    @Nested
    class Apply {

        @Test
        void onePaidSeatIsRefundedAtItsOwnPriceAndReturnedWhileTheBookingStaysConfirmed() {
            Booking booking = paid();

            CancellationOutcome outcome = booking.cancelSeatsByCustomer(List.of("A2"), QUOTE, REASON);

            assertThat(outcome.kind()).isEqualTo(CancellationOutcome.Kind.CANCELLED_NOW);
            assertThat(outcome.refundAmount()).isEqualByComparingTo("150000");
            assertThat(outcome.bookingCancelled()).isFalse();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.activeSeatCodes()).containsExactly("A1", "A3");
            assertThat(booking.getCancelledSeatCodes()).containsExactly("A2");
            assertThat(booking.getRefundedAmount()).isEqualByComparingTo("150000");

            List<Object> events = booking.pullDomainEvents();
            assertThat(events).noneMatch(BookingCancelledEvent.class::isInstance);
            RefundRequestedEvent refund = (RefundRequestedEvent) events.stream()
                    .filter(RefundRequestedEvent.class::isInstance).findFirst().orElseThrow();
            assertThat(refund.amount()).isEqualByComparingTo("150000");
            assertThat(refund.currency()).isEqualTo("VND");
            assertThat(refund.seatCodes()).containsExactly("A2");
            assertThat(refund.refundRequestId()).isNotBlank();
            SeatsReturnRequestedEvent seatsBack = (SeatsReturnRequestedEvent) events.stream()
                    .filter(SeatsReturnRequestedEvent.class::isInstance).findFirst().orElseThrow();
            assertThat(seatsBack.seatCodes()).containsExactly("A2");
            assertThat(seatsBack.showtimeId()).isEqualTo("showtime-1");
        }

        @Test
        void cancellingTheLastSeatCancelsTheBookingAndRefundsWhateverIsLeft() {
            // A1 and A2 were cancelled earlier for 240 000 rather than 250 000 — whatever the reason,
            // the last cancellation settles the balance so the customer gets back exactly what they paid.
            Booking booking = booking(BookingStatus.CONFIRMED, true, false, List.of("A1", "A2"), new BigDecimal("240000"));

            CancellationOutcome outcome = booking.cancelSeatsByCustomer(List.of("A3"), QUOTE, REASON);

            assertThat(outcome.bookingCancelled()).isTrue();
            assertThat(outcome.quotedAmount()).isEqualByComparingTo("200000");
            assertThat(outcome.refundAmount()).isEqualByComparingTo("210000");
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getRefundedAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(booking.pullDomainEvents()).anyMatch(BookingCancelledEvent.class::isInstance);
        }

        @Test
        void refundsNeverAddUpToMoreThanThePaymentEvenIfThePricesDoNot() {
            Booking booking = booking(BookingStatus.CONFIRMED, true, false, List.of("A1"), new BigDecimal("400000"));

            CancellationOutcome outcome = booking.cancelSeatsByCustomer(List.of("A2"), QUOTE, REASON);

            assertThat(outcome.refundAmount()).isEqualByComparingTo("50000");
            assertThat(booking.getRefundedAmount()).isEqualByComparingTo(AMOUNT);
        }

        @Test
        void anUnpaidBookingIsCancelledWithItsSeatsReturnedAndNoRefund() {
            Booking booking = booking(BookingStatus.PENDING_PAYMENT, false, false, List.of(), BigDecimal.ZERO);

            CancellationOutcome outcome = booking.cancelSeatsByCustomer(List.of(), null, REASON);

            assertThat(outcome.bookingCancelled()).isTrue();
            assertThat(outcome.refundAmount()).isEqualByComparingTo("0");
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            List<Object> events = booking.pullDomainEvents();
            assertThat(events).anyMatch(BookingCancelledEvent.class::isInstance);
            assertThat(events).anyMatch(SeatsReturnRequestedEvent.class::isInstance);
            assertThat(events).noneMatch(RefundRequestedEvent.class::isInstance);
        }

        @Test
        void aRepeatChangesNothingAndRaisesNothing() {
            Booking booking = booking(BookingStatus.CONFIRMED, true, false, List.of("A1"), new BigDecimal("100000"));

            CancellationOutcome outcome = booking.cancelSeatsByCustomer(List.of("A1"), QUOTE, REASON);

            assertThat(outcome.isRepeat()).isTrue();
            assertThat(booking.getRefundedAmount()).isEqualByComparingTo("100000");
            assertThat(booking.pullDomainEvents()).isEmpty();
        }

        /** The caller planned on an unpaid snapshot; a payment landed before its transaction did. */
        @Test
        void aBookingThatBecamePaidAfterTheCallerLookedIsAConflictNotAFreeCancel() {
            assertThatThrownBy(() -> paid().cancelSeatsByCustomer(List.of(), null, REASON))
                    .isInstanceOf(CancellationConflictException.class);
        }
    }

    @Test
    void aMatchCancelledAfterSomeSeatsWereRefundedAsksForTheRemainingBalanceOnly() {
        Booking booking = booking(BookingStatus.CONFIRMED, true, false, List.of("A1"), new BigDecimal("100000"));

        booking.cancelDueToMatchCancellation("Match cancelled");

        RefundRequestedEvent refund = (RefundRequestedEvent) booking.pullDomainEvents().stream()
                .filter(RefundRequestedEvent.class::isInstance).findFirst().orElseThrow();
        assertThat(refund.amount()).as("null = whatever payment-service still holds").isNull();
    }

    @Nested
    class Window {

        private final Instant kickoff = Instant.parse("2026-10-10T12:00:00Z");
        private final CancellationWindow window = new CancellationWindow(kickoff, Duration.ofHours(24));

        @Test
        void closesTheCutoffBeforeKickoff() {
            assertThat(window.closesAt()).isEqualTo(Instant.parse("2026-10-09T12:00:00Z"));
        }

        @Test
        void isOpenUpToButNotIncludingTheMomentItCloses() {
            assertThat(window.isOpenAt(Instant.parse("2026-10-09T11:59:59Z"))).isTrue();
            assertThat(window.isOpenAt(Instant.parse("2026-10-09T12:00:00Z"))).isFalse();
            assertThatThrownBy(() -> window.requireOpenAt(Instant.parse("2026-10-10T08:00:00Z")))
                    .isInstanceOf(CancellationWindowClosedException.class);
        }
    }
}
