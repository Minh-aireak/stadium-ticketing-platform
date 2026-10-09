package com.aireak.booking.domain.model;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.booking.domain.event.SeatsReturnRequestedEvent;
import com.aireak.booking.domain.exception.CancellationConflictException;
import com.aireak.booking.domain.exception.InvalidBookingStatusException;
import com.aireak.booking.domain.exception.MaxTicketsExceededException;
import com.aireak.booking.domain.exception.SeatCancellationException;
import com.aireak.booking.domain.exception.TicketIssuanceInProgressException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Booking {

    // Matches the browser's own limit in SeatSelectionPage and ticket-inventory-service's
    // SeatRequestLimits.MAX_SEATS_PER_REQUEST. The three used to disagree (8 / 10 / none),
    // which meant the only number a customer ever saw was the browser's.
    private static final int MAX_TICKETS = 8;

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
    // True once ticket-inventory-service has given a FINAL refusal for these seats — they are
    // SOLD to a different booking, or the showtime has no seat inventory at all. Distinct from
    // "inventoryConfirmed is still false" (retry later) because retrying cannot change it: this
    // is what takes the booking out of InventoryConfirmationReconciler's queue and into a gauge a
    // human is alerted on. See InventoryConfirmationRefusedException.
    private boolean inventorySaleRefused;
    // Seats the customer has cancelled one at a time (see cancelSeatsByCustomer). A booking
    // cancelled as a whole is read through getCancelledSeatCodes() as having every seat cancelled,
    // whether it was cancelled here or on any other path, so rows cancelled before seats could be
    // cancelled singly need no backfill.
    private final Set<String> cancelledSeatCodes;
    // What has been asked of payment-service so far, summed. Never more than amount — see
    // cancelSeatsByCustomer, which is what keeps it so.
    private BigDecimal refundedAmount;
    private final List<Object> domainEvents = new ArrayList<>();

    private Booking(String bookingId, String customerId, String customerEmail, String showtimeId,
                    SeatSelection seatSelection, BookingAmount amount,
                    BookingStatus status, Instant createdAt, String idempotencyKey, Long version,
                    boolean inventoryConfirmed, boolean inventorySaleRefused,
                    Collection<String> cancelledSeatCodes, BigDecimal refundedAmount) {
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
        this.inventorySaleRefused = inventorySaleRefused;
        this.cancelledSeatCodes = new LinkedHashSet<>(cancelledSeatCodes);
        this.refundedAmount = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
    }

    // Creates a new Booking in DRAFT state with invariant checks.
    public static Booking create(String customerId, String customerEmail, String showtimeId,
                                  SeatSelection seatSelection, BookingAmount amount,
                                  String idempotencyKey) {
        // Required here and deliberately NOT in reconstitute(), which has to keep reading back
        // rows written before this check existed — the same split SeatSelection's javadoc sets out.
        // customerEmail is denormalized onto the booking at creation and is the only address the
        // confirmation and cancellation emails are ever sent to, so a booking without one is a
        // booking whose customer cannot be told anything about it. AuthenticatedUser#email is null
        // for a token carrying no email claim, which is every token InternalServiceTokenProvider
        // mints; no caller can currently reach this with one, and this is what keeps it that way.
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new IllegalArgumentException("Booking requires a customerEmail to notify");
        }
        if (seatSelection.count() > MAX_TICKETS) {
            throw new MaxTicketsExceededException(seatSelection.count(), MAX_TICKETS);
        }
        if (new HashSet<>(seatSelection.seatCodes()).size() != seatSelection.count()) {
            throw new IllegalArgumentException("SeatSelection must not contain duplicate seat codes");
        }
        String bookingId = UUID.randomUUID().toString();
        return new Booking(bookingId, customerId, customerEmail, showtimeId,
                seatSelection, amount, BookingStatus.DRAFT, Instant.now(), idempotencyKey, null, false, false,
                List.of(), BigDecimal.ZERO);
    }

    // Reconstitute from persistence — no events raised.
    public static Booking reconstitute(String bookingId, String customerId, String customerEmail, String showtimeId,
                                        SeatSelection seatSelection, BookingAmount amount,
                                        BookingStatus status, Instant createdAt,
                                        String idempotencyKey, Long version, boolean inventoryConfirmed,
                                        boolean inventorySaleRefused) {
        return reconstitute(bookingId, customerId, customerEmail, showtimeId, seatSelection, amount, status,
                createdAt, idempotencyKey, version, inventoryConfirmed, inventorySaleRefused,
                List.of(), BigDecimal.ZERO);
    }

    // Reconstitute from persistence, including seats the customer has cancelled singly (V8).
    public static Booking reconstitute(String bookingId, String customerId, String customerEmail, String showtimeId,
                                        SeatSelection seatSelection, BookingAmount amount,
                                        BookingStatus status, Instant createdAt,
                                        String idempotencyKey, Long version, boolean inventoryConfirmed,
                                        boolean inventorySaleRefused, Collection<String> cancelledSeatCodes,
                                        BigDecimal refundedAmount) {
        return new Booking(bookingId, customerId, customerEmail, showtimeId, seatSelection, amount, status, createdAt,
                idempotencyKey, version, inventoryConfirmed, inventorySaleRefused, cancelledSeatCodes, refundedAmount);
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
            // The remaining balance, not the full amount: seats the customer cancelled earlier
            // have already been refunded on their own (see cancelSeatsByCustomer).
            domainEvents.add(RefundRequestedEvent.ofRemainingBalance(bookingId, reason));
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
        // Same request id on every call — see RefundRequestedEvent#forLatePayment.
        domainEvents.add(RefundRequestedEvent.forLatePayment(bookingId, reason));
    }

    /**
     * What a customer asking to cancel {@code requestedSeatCodes} would get, decided from this
     * booking's state alone and without changing it. An empty request means every seat.
     *
     * <ul>
     *   <li>Seats that are not this booking's → {@link SeatCancellationException} (422).</li>
     *   <li>Every requested seat already cancelled → {@link CustomerCancellation.AlreadyCancelled}:
     *       a repeat is answered like the first request, in whatever state the booking is now.</li>
     *   <li>DRAFT → refused: the creation saga still owns the booking on another thread.</li>
     *   <li>PENDING_PAYMENT → only as a whole. The PaymentIntent was created for the full amount
     *       and cannot be shrunk to fit a smaller booking; pay first, then cancel single seats.</li>
     *   <li>CONFIRMED → seat by seat, once ticket-inventory-service has finished selling the seats
     *       to this booking. Before that, see {@link TicketIssuanceInProgressException}. A sale it
     *       refused for good ({@code inventorySaleRefused}) has nothing in flight, so it is no
     *       reason to wait.</li>
     * </ul>
     *
     * The cancellation deadline is not checked here: it needs the showtime's kickoff, which this
     * aggregate does not hold — see {@link CancellationWindow}.
     */
    public CustomerCancellation planCustomerCancellation(List<String> requestedSeatCodes) {
        List<String> requested = requestedSeatCodes == null || requestedSeatCodes.isEmpty()
                ? seatSelection.seatCodes()
                : List.copyOf(new LinkedHashSet<>(requestedSeatCodes));

        List<String> unknown = requested.stream().filter(code -> !seatSelection.seatCodes().contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new SeatCancellationException(
                    "Seats " + String.join(", ", unknown) + " are not part of booking " + bookingId);
        }

        List<String> alreadyCancelled = getCancelledSeatCodes();
        List<String> toCancel = requested.stream().filter(code -> !alreadyCancelled.contains(code)).toList();
        if (toCancel.isEmpty()) {
            return new CustomerCancellation.AlreadyCancelled(requested);
        }

        return switch (status) {
            case DRAFT -> throw new InvalidBookingStatusException(
                    "The booking is still being created; try again in a moment");
            case PENDING_PAYMENT -> {
                // toCancel is always a subset of the active seats, so equal size means equal sets.
                if (toCancel.size() != activeSeatCodes().size()) {
                    throw new SeatCancellationException(
                            "An unpaid booking can only be cancelled as a whole; pay for it first to cancel single seats");
                }
                yield new CustomerCancellation.Unpaid(toCancel);
            }
            case CONFIRMED -> {
                if (!inventoryConfirmed && !inventorySaleRefused) {
                    throw new TicketIssuanceInProgressException(bookingId);
                }
                yield new CustomerCancellation.Paid(toCancel);
            }
            // Unreachable: every seat of a CANCELLED booking counts as cancelled, so toCancel is
            // empty and the request was answered above. Kept so the switch stays exhaustive.
            case CANCELLED -> new CustomerCancellation.AlreadyCancelled(requested);
        };
    }

    /**
     * Cancels {@code requestedSeatCodes} for the customer, re-deciding from this booking's state as
     * it is now — the caller's earlier {@link #planCustomerCancellation} answered from a snapshot
     * that another writer may since have moved on from.
     *
     * <p>Raises, in the one transaction that persists the change:
     * <ul>
     *   <li>{@link SeatsReturnRequestedEvent} always, so the seats come free whatever else happens;</li>
     *   <li>{@link RefundRequestedEvent} for paid seats, priced from {@code refundQuote};</li>
     *   <li>{@link BookingCancelledEvent} when no seat is left, which is what emails the customer.</li>
     * </ul>
     *
     * <p>The refund is the cancelled seats' own prices, except when this call cancels the last seat:
     * then it is whatever is left of the amount, so the customer always ends up refunded exactly what
     * they paid, never more, whatever the per-seat prices summed to.
     *
     * @param refundQuote the cancelled seats' prices; may be null when the booking was unpaid at the
     *                    caller's snapshot. If it has become paid since, this throws
     *                    {@link CancellationConflictException} and the caller asks again.
     */
    public CancellationOutcome cancelSeatsByCustomer(List<String> requestedSeatCodes, SeatRefundQuote refundQuote,
                                                     String reason) {
        CustomerCancellation plan = planCustomerCancellation(requestedSeatCodes);
        return switch (plan) {
            case CustomerCancellation.AlreadyCancelled already ->
                    CancellationOutcome.alreadyCancelled(already.seatCodes(), status == BookingStatus.CANCELLED);
            case CustomerCancellation.Unpaid unpaid -> {
                cancelledSeatCodes.addAll(unpaid.seatCodes());
                status = BookingStatus.CANCELLED;
                domainEvents.add(new BookingCancelledEvent(bookingId, customerId, customerEmail, showtimeId, reason));
                domainEvents.add(new SeatsReturnRequestedEvent(bookingId, showtimeId, unpaid.seatCodes(), reason));
                yield CancellationOutcome.cancelledNow(unpaid.seatCodes(), BigDecimal.ZERO, BigDecimal.ZERO, true);
            }
            case CustomerCancellation.Paid paid -> {
                if (refundQuote == null || !refundQuote.covers(paid.seatCodes())) {
                    throw new CancellationConflictException(bookingId);
                }
                BigDecimal quoted = refundQuote.totalFor(paid.seatCodes());
                BigDecimal refundable = amount.amount().subtract(refundedAmount).max(BigDecimal.ZERO);
                cancelledSeatCodes.addAll(paid.seatCodes());
                boolean nothingLeft = activeSeatCodes().isEmpty();
                BigDecimal refund = nothingLeft ? refundable : quoted.min(refundable);
                refundedAmount = refundedAmount.add(refund);
                if (nothingLeft) {
                    status = BookingStatus.CANCELLED;
                    domainEvents.add(new BookingCancelledEvent(bookingId, customerId, customerEmail, showtimeId, reason));
                }
                if (refund.signum() > 0) {
                    domainEvents.add(RefundRequestedEvent.forSeats(bookingId, refund, amount.currency(),
                            paid.seatCodes(), reason));
                }
                domainEvents.add(new SeatsReturnRequestedEvent(bookingId, showtimeId, paid.seatCodes(), reason));
                yield CancellationOutcome.cancelledNow(paid.seatCodes(), refund, quoted, nothingLeft);
            }
        };
    }

    /** Seats this booking still holds, in booking order. A cancelled booking holds none. */
    public List<String> activeSeatCodes() {
        if (status == BookingStatus.CANCELLED) {
            return List.of();
        }
        return seatSelection.seatCodes().stream().filter(code -> !cancelledSeatCodes.contains(code)).toList();
    }

    /**
     * Seats this booking no longer holds, in booking order: every seat of a CANCELLED booking, however
     * it got there, and otherwise the ones its customer cancelled singly.
     */
    public List<String> getCancelledSeatCodes() {
        if (status == BookingStatus.CANCELLED) {
            return seatSelection.seatCodes();
        }
        return seatSelection.seatCodes().stream().filter(cancelledSeatCodes::contains).toList();
    }

    // Records that ticketInventoryPort.confirmReservation() actually succeeded. Idempotent —
    // safe to call again on a retry.
    public void markInventoryConfirmed() {
        this.inventoryConfirmed = true;
    }

    /**
     * Records that ticket-inventory-service gave a FINAL refusal for these seats, so nothing
     * retries the confirm again. The booking stays CONFIRMED: the customer paid, was told so, and
     * that remains true — what is not true is that the seats are theirs, and no automated step
     * can fix that. It leaves {@code inventoryConfirmed} false on purpose, because it never was.
     *
     * <p>Idempotent, like {@link #markInventoryConfirmed}: the same refusal can arrive twice if
     * recording it failed the first time.
     */
    public void markInventorySaleRefused() {
        this.inventorySaleRefused = true;
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
    public boolean isInventorySaleRefused() { return inventorySaleRefused; }
    public BigDecimal getRefundedAmount()   { return refundedAmount; }

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
