package com.aireak.booking.domain.model;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.exception.MaxTicketsExceededException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class Booking {

    private static final int MAX_TICKETS = 10;

    private final String bookingId;
    private final String customerId;
    private final String customerEmail;
    private final String showtimeId;
    private final SeatSelection seatSelection;
    private BookingAmount amount;
    private BookingStatus status;
    private final Instant createdAt;
    private final String idempotencyKey;
    // null only until first persist (create()) — Spring Data's isNew() check needs that,
    // so reconstitute() must carry the real value through unchanged after that point.
    private final Long version;
    // True once ticketInventoryPort.confirmReservation() has actually succeeded for this
    // (already-CONFIRMED) booking — see BookingOrchestrationService#confirmInventoryReservation.
    // Stays false when that best-effort call failed, so InventoryConfirmationReconciler can
    // find and retry exactly those bookings.
    private boolean inventoryConfirmed;
    private final List<Object> domainEvents = new ArrayList<>();

    private Booking(String bookingId, String customerId, String customerEmail, String showtimeId,
                    SeatSelection seatSelection, BookingAmount amount,
                    BookingStatus status, Instant createdAt, String idempotencyKey, Long version,
                    boolean inventoryConfirmed) {
        this.bookingId    = bookingId;
        this.customerId   = customerId;
        this.customerEmail = customerEmail;
        this.showtimeId   = showtimeId;
        this.seatSelection = seatSelection;
        this.amount        = amount;
        this.status        = status;
        this.createdAt     = createdAt;
        this.idempotencyKey = idempotencyKey;
        this.version        = version;
        this.inventoryConfirmed = inventoryConfirmed;
    }

    // Creates a new Booking in DRAFT state with invariant checks.
    public static Booking create(String customerId, String customerEmail, String showtimeId,
                                  SeatSelection seatSelection, BookingAmount amount,
                                  String idempotencyKey) {
        if (seatSelection.count() > MAX_TICKETS) {
            throw new MaxTicketsExceededException(seatSelection.count(), MAX_TICKETS);
        }
        if (new HashSet<>(seatSelection.seatCodes()).size() != seatSelection.count()) {
            throw new IllegalArgumentException("SeatSelection must not contain duplicate seat codes");
        }
        String bookingId = UUID.randomUUID().toString();
        return new Booking(bookingId, customerId, customerEmail, showtimeId,
                seatSelection, amount, BookingStatus.DRAFT, Instant.now(), idempotencyKey, null, false);
    }

    // Reconstitute from persistence — no events raised.
    public static Booking reconstitute(String bookingId, String customerId, String customerEmail, String showtimeId,
                                        SeatSelection seatSelection, BookingAmount amount,
                                        BookingStatus status, Instant createdAt,
                                        String idempotencyKey, Long version, boolean inventoryConfirmed) {
        return new Booking(bookingId, customerId, customerEmail, showtimeId, seatSelection, amount, status, createdAt,
                idempotencyKey, version, inventoryConfirmed);
    }

    // Overwrites the placeholder amount recorded at draft-creation time with the authoritative
    // price ticket-inventory-service computed from each seat's tier (see
    // BookingOrchestrationService#createBooking, Step 2b) — must run before markPendingPayment,
    // initiatePayment, or any domain event that carries `amount`, since a client-supplied amount
    // is never trusted for the actual charge.
    public void applyReservedPrice(BookingAmount amount) {
        require(BookingStatus.DRAFT, "applyReservedPrice");
        this.amount = amount;
    }

    // mark PENDING_PAYMENT if DRAFT
    public void markPendingPayment() {
        require(BookingStatus.DRAFT, "markPendingPayment");
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    // confirm (only if PENDING_PAYMENT)
    public void confirm() {
        require(BookingStatus.PENDING_PAYMENT, "confirm");
        this.status = BookingStatus.CONFIRMED;
        domainEvents.add(new BookingConfirmedEvent(bookingId, customerId, customerEmail, showtimeId,
                seatSelection.seatCodes(), amount));
    }

    // No-ops if status moved past PENDING_PAYMENT — the async payment result may
    // have already confirmed/cancelled before this runs; avoids a stale "created" event.
    public void recordCreationSucceeded() {
        if (status != BookingStatus.PENDING_PAYMENT) {
            return;
        }
        domainEvents.add(new BookingCreatedEvent(bookingId, customerId, showtimeId,
                seatSelection.seatCodes(), amount));
    }

    /**
     * Cancels a booking that has not been paid for.
     *
     * <p>Raises {@link BookingCancelledEvent} from DRAFT as well as PENDING_PAYMENT, deliberately —
     * see {@code BookingTest#cancelFromDraftRaisesBookingCancelledEventWithReason}. A DRAFT booking
     * has never had {@link BookingCreatedEvent} published for it ({@link #recordCreationSucceeded}
     * is the saga's last step), so the cancellation is the first and only event a consumer sees for
     * it. That asymmetry is intentional: BOOKING_CREATED has no notification template at all (see
     * notification-service's {@code NotificationDispatchService#dispatch}), so the cancellation is
     * not an orphan of a confirmation the customer was expecting — it is the record that the
     * attempt existed and ended.
     */
    public void cancel(String reason) {
        if (status == BookingStatus.CONFIRMED || status == BookingStatus.CANCELLED) {
            throw new InvalidBookingStatusException(
                    "Cannot cancel booking in status: " + status);
        }
        this.status = BookingStatus.CANCELLED;
        domainEvents.add(new BookingCancelledEvent(bookingId, customerId, customerEmail, showtimeId, reason));
    }

    /**
     * System-triggered cancellation for a match that itself got cancelled (see
     * MatchCancelledConsumer) — unlike {@link #cancel}, this is allowed from CONFIRMED (an
     * already-paid booking) since the match no longer exists to honor it.
     *
     * <p>A booking that was CONFIRMED has been charged, so it also raises
     * {@link RefundRequestedEvent}. The aggregate decides that rather than returning a flag for
     * the caller to act on: whether money changed hands is a fact about this booking's state, and
     * pairing the two events here is what puts the refund in the same transaction — and therefore
     * the same outbox write — as the cancellation that owes it.
     */
    public void cancelDueToMatchCancellation(String reason) {
        if (status == BookingStatus.CANCELLED) {
            throw new InvalidBookingStatusException("Cannot cancel a CANCELLED booking");
        }
        boolean wasConfirmed = status == BookingStatus.CONFIRMED;
        this.status = BookingStatus.CANCELLED;
        domainEvents.add(new BookingCancelledEvent(bookingId, customerId, customerEmail, showtimeId, reason));
        if (wasConfirmed) {
            domainEvents.add(new RefundRequestedEvent(bookingId, reason));
        }
    }

    /**
     * The late-payment race: PAYMENT_SUCCEEDED arrives for a booking that was already cancelled
     * (see {@code BookingOrchestrationService#confirmBooking}). The booking stays cancelled — the
     * seats are long released — but the customer has now been charged for it, so the money has to
     * go back. Separate from {@link #cancelDueToMatchCancellation} because no state changes here:
     * the only outcome is the refund request.
     */
    public void requestRefundForLatePayment(String reason) {
        if (status != BookingStatus.CANCELLED) {
            throw new InvalidBookingStatusException(
                    "Late-payment refund only applies to a CANCELLED booking, but status is " + status);
        }
        domainEvents.add(new RefundRequestedEvent(bookingId, reason));
    }

    // Records that ticketInventoryPort.confirmReservation() actually succeeded. Idempotent —
    // safe to call again on a retry.
    public void markInventoryConfirmed() {
        this.inventoryConfirmed = true;
    }

    // Accessors
    public String getBookingId()            { return bookingId; }
    public String getCustomerId()           { return customerId; }
    public String getCustomerEmail()        { return customerEmail; }
    public String getShowtimeId()           { return showtimeId; }
    public SeatSelection getSeatSelection() { return seatSelection; }
    public BookingAmount getAmount()        { return amount; }
    public BookingStatus getStatus()        { return status; }
    public Instant getCreatedAt()           { return createdAt; }
    public String getIdempotencyKey()       { return idempotencyKey; }
    public Long getVersion()                { return version; }
    public boolean isInventoryConfirmed()   { return inventoryConfirmed; }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }

    private void require(BookingStatus expected, String operation) {
        if (status != expected) {
            throw new InvalidBookingStatusException(
                    "Operation '" + operation + "' requires status " + expected +
                    ", current: " + status);
        }
    }
}
