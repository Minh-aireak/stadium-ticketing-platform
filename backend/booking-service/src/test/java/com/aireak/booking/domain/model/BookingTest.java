package com.aireak.booking.domain.model;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.exception.MaxTicketsExceededException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final SeatSelection SEATS = new SeatSelection(List.of("A1", "A2"));
    private static final BookingAmount AMOUNT = BookingAmount.of(new BigDecimal("100.00"), "USD");

    @Test
    void createStartsInDraftWithNullVersionAndNoEvents() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, "idem-key-1");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DRAFT);
        assertThat(booking.getVersion()).isNull();
        assertThat(booking.getBookingId()).isNotBlank();
        assertThat(booking.getIdempotencyKey()).isEqualTo("idem-key-1");
        assertThat(booking.pullDomainEvents()).isEmpty();
    }

    @Test
    void createAllowsNullIdempotencyKey() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        assertThat(booking.getIdempotencyKey()).isNull();
    }

    @Test
    void createRejectsAMissingCustomerEmail() {
        // The address is denormalized onto the booking here and is the only one the confirmation
        // and cancellation emails ever go to. A token with no email claim — which is every token
        // InternalServiceTokenProvider mints — yields a null AuthenticatedUser#email, and a
        // booking built from one could never tell its customer anything about itself.
        assertThatThrownBy(() -> Booking.create("customer-1", null, "showtime-1", SEATS, AMOUNT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerEmail");

        assertThatThrownBy(() -> Booking.create("customer-1", "  ", "showtime-1", SEATS, AMOUNT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstituteStillAcceptsARowWrittenBeforeTheEmailWasRequired() {
        // reconstitute() must stay permissive where create() does not: rows persisted before the
        // check existed still have to load. Same split SeatSelection's javadoc sets out.
        Booking booking = Booking.reconstitute("booking-1", "customer-1", null, "showtime-1",
                SEATS, AMOUNT, BookingStatus.DRAFT, Instant.now(), null, 0L, false);

        assertThat(booking.getCustomerEmail()).isNull();
    }

    @Test
    void createRejectsMoreThanMaxTickets() {
        List<String> elevenSeats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11");

        assertThatThrownBy(() -> Booking.create("customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(elevenSeats), AMOUNT, null))
                .isInstanceOf(MaxTicketsExceededException.class);
    }

    @Test
    void createAllowsExactlyMaxTickets() {
        List<String> tenSeats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10");

        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(tenSeats), AMOUNT, null);

        assertThat(booking.getSeatSelection().count()).isEqualTo(10);
    }

    @Test
    void createRejectsDuplicateSeatCodes() {
        List<String> duplicated = List.of("A1", "A1", "A2");

        assertThatThrownBy(() -> Booking.create("customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(duplicated), AMOUNT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPendingPaymentTransitionsFromDraft() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        booking.markPendingPayment();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void markPendingPaymentRejectsFromNonDraftStatus() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();

        assertThatThrownBy(booking::markPendingPayment)
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void confirmFromPendingPaymentRaisesBookingConfirmedEvent() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();

        booking.confirm();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        List<Object> events = booking.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(BookingConfirmedEvent.class);
        assertThat(((BookingConfirmedEvent) events.get(0)).bookingId()).isEqualTo(booking.getBookingId());
    }

    @Test
    void confirmRejectsWhenNotPendingPayment() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        assertThatThrownBy(booking::confirm)
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void confirmRejectsWhenAlreadyConfirmed() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();
        booking.confirm();

        assertThatThrownBy(booking::confirm)
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void cancelFromDraftRaisesBookingCancelledEventWithReason() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        booking.cancel("no seats available");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        List<Object> events = booking.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(BookingCancelledEvent.class);
        assertThat(((BookingCancelledEvent) events.get(0)).reason()).isEqualTo("no seats available");
    }

    @Test
    void cancelFromPendingPaymentSucceeds() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();

        booking.cancel("payment failed");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelRejectsWhenAlreadyConfirmed() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();
        booking.confirm();

        assertThatThrownBy(() -> booking.cancel("too late"))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void cancelRejectsWhenAlreadyCancelled() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.cancel("first cancel");

        assertThatThrownBy(() -> booking.cancel("second cancel"))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    /**
     * A CONFIRMED booking has been charged, so cancelling it owes the customer their money back.
     * Both events come out of the same call, which is what puts the refund in the same transaction
     * — and the same outbox write — as the cancellation.
     */
    @Test
    void cancellingAConfirmedBookingRaisesBothCancellationAndRefundRequest() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();
        booking.confirm();
        booking.pullDomainEvents(); // discard BookingConfirmedEvent

        booking.cancelDueToMatchCancellation("Match cancelled by organizer");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        List<Object> events = booking.pullDomainEvents();
        assertThat(events).hasSize(2);
        assertThat(((BookingCancelledEvent) events.get(0)).reason()).isEqualTo("Match cancelled by organizer");
        RefundRequestedEvent refund = (RefundRequestedEvent) events.get(1);
        assertThat(refund.bookingId()).isEqualTo(booking.getBookingId());
        assertThat(refund.reason()).isEqualTo("Match cancelled by organizer");
    }

    /** A booking that never reached CONFIRMED was never charged — refunding it would be wrong. */
    @Test
    void cancellingAnUnpaidBookingRaisesNoRefundRequest() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        booking.cancelDueToMatchCancellation("Match cancelled by organizer");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.pullDomainEvents())
                .hasSize(1)
                .hasOnlyElementsOfType(BookingCancelledEvent.class);
    }

    @Test
    void latePaymentRefundRequestRaisesTheEventWithoutChangingStatus() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.cancel("customer changed their mind");
        booking.pullDomainEvents();

        booking.requestRefundForLatePayment("Payment succeeded after booking cancellation");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.pullDomainEvents())
                .hasSize(1)
                .hasOnlyElementsOfType(RefundRequestedEvent.class);
    }

    /** Guards the aggregate against a refund being requested for a booking still on its way. */
    @Test
    void latePaymentRefundRequestRejectsABookingThatIsNotCancelled() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();

        assertThatThrownBy(() -> booking.requestRefundForLatePayment("late payment"))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void cancelDueToMatchCancellationRejectsWhenAlreadyCancelled() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.cancel("first cancel");

        assertThatThrownBy(() -> booking.cancelDueToMatchCancellation("second cancel"))
                .isInstanceOf(InvalidBookingStatusException.class);
    }

    @Test
    void recordCreationSucceededRaisesEventWhenStillPendingPayment() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();

        booking.recordCreationSucceeded();

        List<Object> events = booking.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(BookingCreatedEvent.class);
    }

    @Test
    void recordCreationSucceededIsNoOpWhenStatusMovedPastPendingPaymentViaConfirm() {
        // Simulates PaymentResultConsumer's confirm() winning the race against the
        // synchronous recordCreationSucceeded() step in BookingOrchestrationService.
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.markPendingPayment();
        booking.confirm();
        booking.pullDomainEvents(); // drain the BookingConfirmedEvent

        booking.recordCreationSucceeded();

        assertThat(booking.pullDomainEvents()).isEmpty();
    }

    @Test
    void recordCreationSucceededIsNoOpWhenStillDraft() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        booking.recordCreationSucceeded();

        assertThat(booking.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);
        booking.cancel("test");

        List<Object> firstPull = booking.pullDomainEvents();
        List<Object> secondPull = booking.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }

    @Test
    void reconstitutePreservesVersionStatusAndRaisesNoEvents() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");

        Booking booking = Booking.reconstitute("booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                SEATS, AMOUNT, BookingStatus.CONFIRMED, createdAt, "idem-1", 5L, true);

        assertThat(booking.getVersion()).isEqualTo(5L);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getCreatedAt()).isEqualTo(createdAt);
        assertThat(booking.isInventoryConfirmed()).isTrue();
        assertThat(booking.pullDomainEvents()).isEmpty();
    }

    @Test
    void reconstituteWithNullVersionRepresentsAPreExistingRowLoadedBeforeFirstSave() {
        // version is only ever null for an in-memory create()-d booking; reconstitute()
        // just carries whatever persistence handed it through unchanged (see the field's
        // javadoc on why that distinction matters for JPA's isNew() check).
        Booking booking = Booking.reconstitute("booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                SEATS, AMOUNT, BookingStatus.DRAFT, Instant.now(), null, 0L, false);

        assertThat(booking.getVersion()).isEqualTo(0L);
    }

    @Test
    void createStartsWithInventoryNotConfirmed() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        assertThat(booking.isInventoryConfirmed()).isFalse();
    }

    @Test
    void markInventoryConfirmedSetsFlag() {
        Booking booking = Booking.create("customer-1", "customer-1@example.com", "showtime-1", SEATS, AMOUNT, null);

        booking.markInventoryConfirmed();

        assertThat(booking.isInventoryConfirmed()).isTrue();
    }
}
